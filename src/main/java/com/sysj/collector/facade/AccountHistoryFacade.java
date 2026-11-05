package com.sysj.collector.facade;

import com.sysj.collector.core.pipeline.ProviderInvocationPipeline;
import com.sysj.collector.core.provider.AccountHistoryProvider;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.router.DynamicProviderRouter;
import com.sysj.collector.domain.dao.AccountHistoryDao;
import com.sysj.collector.domain.document.AccountHistoryDoc;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;
import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import com.sysj.collector.domain.service.ProviderConfigService;
import com.sysj.collector.exception.ProviderInvocationException;
import com.sysj.collector.model.AccountHistoryCollectRequest;
import com.sysj.collector.model.AccountHistoryCollectResult;
import com.sysj.collector.model.AccountHistoryItem;
import com.sysj.collector.model.CommonEntity;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 账号历史采集门面服务。
 *
 * <p>与评论采集（{@link CommentCollectionFacade}）刻意分成两条链路，但共享同一套基础设施：
 * 平台功能配置（{@code platform_feature_config}，{@code feature_code=account_history}）、
 * 统一路由（{@link DynamicProviderRouter}）与统一调用管线（{@link ProviderInvocationPipeline}：
 * Bulkhead → 熔断 → 重试 → 限流 → 单次限时）。
 *
 * <p><b>为什么单独一个门面</b>：账号历史的供应商契约是
 * {@link AccountHistoryProvider}（返回作品列表），与评论的 {@link com.sysj.collector.model.Comment}
 * 载荷不同；强行塞进评论门面会让落库与结果组装到处 if/else。新增平台时只需实现
 * {@link AccountHistoryProvider} 并在 {@code platform_feature_config} 里挂上该功能的供应商即可，
 * 本类与控制器都不用改。
 *
 * <p>任务拆分/重试/计数/回调不在这里：异步场景由 {@code TaskManagementService} 复用
 * {@code master_task}/{@code sub_task}，结果包装成 {@link com.sysj.collector.model.CommentCollectResult}
 * 回写给 {@code TaskResultListener}（见 {@link AccountHistoryCollectResult#toCommentCollectResult()}）。
 */
@Slf4j
@Service
public class AccountHistoryFacade {

    private final ProviderConfigService configService;
    private final DynamicProviderRouter router;
    private final ProviderInvocationPipeline pipeline;
    private final ApplicationContext applicationContext;
    private final AccountHistoryDao accountHistoryDao;

    public AccountHistoryFacade(ProviderConfigService configService,
                                DynamicProviderRouter router,
                                ProviderInvocationPipeline pipeline,
                                ApplicationContext applicationContext,
                                AccountHistoryDao accountHistoryDao) {
        this.configService = configService;
        this.router = router;
        this.pipeline = pipeline;
        this.applicationContext = applicationContext;
        this.accountHistoryDao = accountHistoryDao;
    }

    /** 同步采集：执行一次（一页）并直接返回。 */
    public AccountHistoryCollectResult collectSync(AccountHistoryCollectRequest request) {
        return doCollect(request);
    }

    /**
     * 执行一次账号历史采集（选供应商 + 管线调用 + 落库）。**只做采集本身**，
     * 子任务/主任务状态回写由调用方负责。
     */
    public AccountHistoryCollectResult doCollect(AccountHistoryCollectRequest request) {
        String platform = request.getPlatformCode();
        String feature = request.getFeatureCode();

        // 1. 功能配置（含供应商列表）；未配置/已禁用会抛 CollectorException
        PlatformFeatureConfig featureConfig = configService.loadFeatureConfig(platform, feature);
        List<ProviderConfig> allProviders = featureConfig.getProviders();
        if (allProviders == null || allProviders.isEmpty()) {
            return AccountHistoryCollectResult.failed("该功能未配置供应商");
        }

        // 2. 指定供应商：走路由的 requiredProviderKey 分支，失败后不再换人
        if (request.getSpecifiedProviderKey() != null) {
            List<ProviderConfig> specified = router.select(DynamicProviderRouter.RouteContext.specified(
                    platform, feature, allProviders, request.getSpecifiedProviderKey()));
            if (specified.isEmpty()) {
                return AccountHistoryCollectResult.failed(
                        "指定的供应商不可用: " + request.getSpecifiedProviderKey());
            }
            return executeProviderRetry(request, specified.get(0));
        }

        // 3. 用户等级供应商偏好（含等级继承）
        FeatureProviderConfig featurePreference = configService
                .resolveFeaturePreference(request.getUserTierCode(), platform, feature)
                .orElse(null);

        router.incrementPending(platform, feature);
        try {
            // 4. 统一路由入口：候选构建 → 健康/熔断过滤 → 能力过滤 → 激活阈值
            DynamicProviderRouter.RouteResult routeResult = router.selectDetailed(
                    DynamicProviderRouter.RouteContext.of(platform, feature, allProviders,
                            featurePreference,
                            Capability.parse(request.getRequiredCapabilities()),
                            Capability.parse(request.getExcludedCapabilities())));

            List<ProviderConfig> candidates = routeResult.candidates();
            if (candidates.isEmpty()) {
                return AccountHistoryCollectResult.failed(routeResult.reason());
            }

            // 5. 遍历候选并执行，失败自动切换
            Set<String> excludedKeys = new HashSet<>();
            Map<String, String> providerErrors = new LinkedHashMap<>();
            for (ProviderConfig providerConfig : candidates) {
                String key = providerConfig.getProviderKey();
                if (excludedKeys.contains(key)) {
                    continue;
                }

                AccountHistoryCollectResult result = executeProviderWithSwitch(
                        request, providerConfig, excludedKeys, providerErrors);
                if (result != null && result.isSuccess()) {
                    return result;
                }
                excludedKeys.add(key);
            }

            return AccountHistoryCollectResult.failed("所有供应商均不可用: " + providerErrors);
        } finally {
            router.decrementPending(platform, feature);
        }
    }

    /** 执行单个供应商，失败时记录排除；返回 null 表示需要切换供应商。 */
    private AccountHistoryCollectResult executeProviderWithSwitch(
            AccountHistoryCollectRequest request,
            ProviderConfig providerConfig,
            Set<String> excludedKeys,
            Map<String, String> providerErrors) {

        String key = providerConfig.getProviderKey();
        AccountHistoryProvider provider;
        try {
            provider = applicationContext.getBean(key, AccountHistoryProvider.class);
        } catch (Exception e) {
            log.error("账号历史供应商 Bean 未找到: providerKey={}", key);
            providerErrors.put(key, "Bean 未注册");
            excludedKeys.add(key);
            return null;
        }

        try {
            CommonEntity<AccountHistoryItem> entity =
                    pipeline.invoke(request, providerConfig, () -> provider.fetchHistory(request));
            return toResult(request, key, entity);
        } catch (Exception e) {
            log.error("账号历史供应商执行失败，切换: provider={} error={}", key, e.getMessage());
            providerErrors.put(key, e.getMessage());
            excludedKeys.add(key);
            return null;
        }
    }

    /**
     * 指定供应商路径（不切换）。{@link ProviderInvocationException} 原样向上抛，
     * 由 {@code GlobalExceptionHandler} 映射成 503 PROVIDER_UNAVAILABLE。
     */
    private AccountHistoryCollectResult executeProviderRetry(
            AccountHistoryCollectRequest request, ProviderConfig providerConfig) {

        String key = providerConfig.getProviderKey();
        AccountHistoryProvider provider;
        try {
            provider = applicationContext.getBean(key, AccountHistoryProvider.class);
        } catch (Exception e) {
            return AccountHistoryCollectResult.failed("供应商 Bean 未注册: " + key);
        }

        try {
            CommonEntity<AccountHistoryItem> entity =
                    pipeline.invoke(request, providerConfig, () -> provider.fetchHistory(request));
            return toResult(request, key, entity);
        } catch (ProviderInvocationException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderInvocationException(key, "供应商 [" + key + "] 执行失败: " + e.getMessage(), e);
        }
    }

    /** 结果后处理：落库 → 组装返回。收到的 entity 必定是成功态（判定已在管线内完成）。 */
    private AccountHistoryCollectResult toResult(AccountHistoryCollectRequest request,
                                                 String providerKey,
                                                 CommonEntity<AccountHistoryItem> entity) {
        List<AccountHistoryItem> items = entity.getDataList() == null
                ? Collections.emptyList() : entity.getDataList();
        persist(request, providerKey, items);
        return AccountHistoryCollectResult.builder()
                .success(true)
                .providerUsed(providerKey)
                .items(items)
                .hasMore(Boolean.TRUE.equals(entity.getHaseMore()))
                .nextCursor(entity.getNextUrl())
                .build();
    }

    /**
     * 落库采集结果。仅在 {@code request.taskId} 非空时落库（同步即时返回不落库）；
     * 落库失败只记日志，不影响本次返回。
     *
     * @return 实际写入条数
     */
    private int persist(AccountHistoryCollectRequest request, String providerKey,
                        List<AccountHistoryItem> items) {
        String taskId = request.getTaskId();
        if (taskId == null || taskId.isBlank() || items.isEmpty()) {
            return 0;
        }
        String accountId = request.resolveAccountId();
        String fromUrl = request.getFromUrl();
        List<AccountHistoryDoc> docs = new ArrayList<>(items.size());
        for (AccountHistoryItem item : items) {
            if (item.getItemId() == null || item.getItemId().isBlank()) {
                continue;
            }
            docs.add(AccountHistoryDoc.from(request.getPlatformCode(), providerKey,
                    taskId, request.getSubTaskId(), fromUrl, item));
        }
        if (docs.isEmpty()) {
            return 0;
        }
        try {
            int saved = accountHistoryDao.saveAll(docs);
            log.info("账号历史已落库: taskId={} subTaskId={} platform={} accountId={} provider={} saved={}/{}",
                    taskId, request.getSubTaskId(), request.getPlatformCode(), accountId,
                    providerKey, saved, items.size());
            return saved;
        } catch (Exception e) {
            log.error("账号历史落库失败（不影响本次返回）: taskId={} error={}", taskId, e.getMessage(), e);
            return 0;
        }
    }
}
