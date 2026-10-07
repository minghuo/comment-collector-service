package com.sysj.collector.domain.service;

import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
import com.sysj.collector.domain.dao.PlatformFeatureConfigDao;
import com.sysj.collector.domain.dao.SupplierStateDao;
import com.sysj.collector.domain.dao.UserTierConfigDao;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.SupplierState;
import com.sysj.collector.domain.document.UserTierConfig;
import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 供应商注册表同步服务：让**派生表**自动跟上 {@code platform_feature_config} 的变更，
 * 并让 {@code platform_feature_config} 自动跟上**代码**（代码即配置）。
 *
 * <h3>同步内容（全部幂等，可反复执行）</h3>
 * <ol>
 *   <li><b>代码注册（第 0 步）</b>：扫描全部 {@link com.sysj.collector.core.provider.CommentProvider}
 *       实现类上的 {@code @ProviderMeta} 注解，把平台/功能/供应商写入 {@code platform_feature_config}
 *       —— 功能文档不存在则创建、缺供应商则追加（注解参数为初值）、已存在则只对齐能力声明。
 *       <b>新增供应商只需实现类 + @Component + @ProviderMeta，无需任何 DB 配置</b>；
 *       未标注注解的供应商跳过并告警（沿用手工配置）；</li>
 *   <li><b>supplier_state</b>：为每个配置中的 {@code platform:feature:providerKey} 补建条目——
 *       身份字段 `$set` 对齐配置，运行时状态（熔断/计数/健康）`$setOnInsert` 只在文档不存在时写初值，
 *       在线供应商的运行状态**不会被触碰**（与 init 脚本同口径）；</li>
 *   <li><b>user_tier_config</b>：为每个**基础等级**追加缺失的功能项（providerOrder 取该功能的全部供应商，
 *       activationThreshold 沿用该等级现有功能项的阈值），已有的功能项**原样保留**——
 *       运维手工调整过的 providerOrder 不会被覆盖；高等级经 {@code parentTierCode} 继承，不重复补；</li>
 *   <li><b>失效检测</b>：supplier_state 里存在、但配置已删除的条目仅记告警（保留历史，不删除）。</li>
 * </ol>
 *
 * <h3>触发时机</h3>
 * 启动完成后一次 + 定时巡检（默认 5 分钟）+ 管理接口手动触发
 * （{@code POST /api/config/sync-registry}）。
 */
@Slf4j
@Service
public class SupplierRegistrySyncService {

    private final PlatformFeatureConfigDao featureConfigDao;
    private final SupplierStateDao supplierStateDao;
    private final UserTierConfigDao userTierConfigDao;
    private final com.sysj.collector.domain.service.ProviderConfigService configService;
    /** 全部供应商实现（Spring 注入；@ProviderMeta 声明注册元数据）。 */
    private final List<CommentProvider> codeProviders;

    public SupplierRegistrySyncService(PlatformFeatureConfigDao featureConfigDao,
                                       SupplierStateDao supplierStateDao,
                                       UserTierConfigDao userTierConfigDao,
                                       ProviderConfigService configService,
                                       List<CommentProvider> codeProviders) {
        this.featureConfigDao = featureConfigDao;
        this.supplierStateDao = supplierStateDao;
        this.userTierConfigDao = userTierConfigDao;
        this.configService = configService;
        this.codeProviders = codeProviders == null ? List.of() : codeProviders;
    }

    /** 同步结果报告。 */
    public record SyncReport(int providersRegistered, int providersAligned,
                             int configuredSuppliers, int supplierStatesAdded,
                             int tierFeatureEntriesAdded, List<String> staleKeys) {

        @Override
        public String toString() {
            return "代码注册供应商=" + providersRegistered + "(新增)/" + providersAligned + "(能力对齐)"
                    + ", 配置供应商=" + configuredSuppliers
                    + ", 新建supplier_state=" + supplierStatesAdded
                    + ", 追加等级功能项=" + tierFeatureEntriesAdded
                    + ", 失效条目=" + staleKeys.size();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        syncQuietly("启动");
    }

    @Scheduled(fixedDelayString = "${collector.sync.registry-ms:300000}")
    public void onSchedule() {
        syncQuietly("巡检");
    }

    /** 手动触发入口（管理接口调用），异常向上抛由调用方处理。 */
    public SyncReport sync() {
        // 0. 代码注册：@ProviderMeta 声明的供应商先写入配置表（随后 findAll 即能读到它们）
        int[] registration = registerCodeProviders();

        List<PlatformFeatureConfig> configs = featureConfigDao.findAll();
        List<String> configuredKeys = new ArrayList<>();
        int added = 0;

        // 1. supplier_state：身份对齐 + 缺失补建
        for (PlatformFeatureConfig config : configs) {
            if (config.getProviders() == null) {
                continue;
            }
            for (PlatformFeatureConfig.ProviderConfig provider : config.getProviders()) {
                String supplierKey = PlatformFeatureConfig.buildStateKey(
                        config.getPlatformCode(), config.getFeatureCode(), provider.getProviderKey());
                configuredKeys.add(supplierKey);
                try {
                    if (supplierStateDao.upsertIdentity(supplierKey, config.getPlatformCode(),
                            config.getFeatureCode(), provider.getProviderKey())) {
                        added++;
                        log.info("supplier_state 新建条目: {} ({} / {})", supplierKey,
                                provider.getProviderKey(), provider.getName());
                    }
                } catch (Exception e) {
                    log.warn("supplier_state 条目同步失败(跳过): key={} error={}", supplierKey, e.getMessage());
                }
            }
        }

        // 2. user_tier_config：追加缺失的功能项
        int tierEntries;
        try {
            tierEntries = syncTierFeatureConfigs(configs);
        } catch (Exception e) {
            log.warn("user_tier_config 功能项同步失败(本轮跳过): {}", e.getMessage());
            tierEntries = 0;
        }

        // 3. 失效检测：状态表有、配置没有（只告警不删除）
        List<String> staleKeys = findStaleKeys(configuredKeys);
        if (!staleKeys.isEmpty()) {
            log.warn("supplier_state 存在已从配置移除的供应商条目（保留历史，请人工确认）: {}", staleKeys);
        }

        SyncReport report = new SyncReport(registration[0], registration[1],
                configuredKeys.size(), added, tierEntries, staleKeys);
        log.info("供应商注册表同步完成: {}", report);
        return report;
    }

    /**
     * 第 0 步：扫描全部 {@link CommentProvider} 实现，把 {@code @ProviderMeta}
     * 声明的供应商写入 {@code platform_feature_config}。
     *
     * @return [新增条目数, 能力对齐条目数]
     */
    private int[] registerCodeProviders() {
        int added = 0;
        int aligned = 0;
        for (CommentProvider provider : codeProviders) {
            ProviderMeta meta = AnnotationUtils.findAnnotation(provider.getClass(), ProviderMeta.class);
            if (meta == null) {
                log.warn("供应商未标注 @ProviderMeta，跳过自动注册（需手工维护 platform_feature_config）: key={}",
                        provider.providerKey());
                continue;
            }
            PlatformFeatureConfig.ProviderConfig config = buildProviderConfig(meta, provider);
            try {
                String result = featureConfigDao.upsertProvider(
                        meta.platform(), meta.feature(), meta.featureName(), config);
                switch (result) {
                    case "CREATED_FEATURE", "ADDED" -> {
                        added++;
                        log.info("代码注册供应商入库: {} → {}/{} ({})", provider.providerKey(),
                                meta.platform(), meta.feature(), result);
                    }
                    case "ALIGNED" -> aligned++;
                    default -> log.debug("供应商配置已存在且能力一致: key={}", provider.providerKey());
                }
                // 能力对齐/条目新增都淘汰该功能配置缓存，60s 内生效
                configService.evictFeatureCache(meta.platform(), meta.feature());
            } catch (Exception e) {
                log.warn("代码注册供应商失败(跳过): key={} error={}", provider.providerKey(), e.getMessage());
            }
        }
        return new int[]{added, aligned};
    }

    /** 由注解构造配置数组元素（能力取实现类 @ProviderCapability 的代码声明）。 */
    private static PlatformFeatureConfig.ProviderConfig buildProviderConfig(
            ProviderMeta meta, CommentProvider provider) {
        PlatformFeatureConfig.ProviderConfig config = new PlatformFeatureConfig.ProviderConfig();
        config.setProviderKey(provider.providerKey());
        config.setName(meta.name());
        config.setRatePerSecond(meta.ratePerSecond());
        config.setMaxRetry(meta.maxRetry());
        config.setPriority(meta.priority());
        config.setHealthy(true);
        config.setMaxConcurrency(meta.maxConcurrency());
        config.setTimeoutMs(meta.timeoutMs());
        config.setFlowEffect(meta.flowEffect());
        config.setMaxQueueWaitMs(meta.maxQueueWaitMs());
        ProviderCapability capability = AnnotationUtils.findAnnotation(
                provider.getClass(), ProviderCapability.class);
        config.setCapabilities(capability == null ? List.of()
                : java.util.Arrays.stream(capability.value())
                        .map(Enum::name).collect(Collectors.toList()));
        return config;
    }

    /** 启动/巡检路径：同步失败只记日志（Mongo 抖动不应放大为应用问题）。 */
    private void syncQuietly(String trigger) {
        try {
            sync();
        } catch (Exception e) {
            log.warn("供应商注册表同步失败({}): {}", trigger, e.getMessage());
        }
    }

    /** 为**基础等级**追加缺失的功能项（高等级靠 parentTierCode 继承，不重复补）；返回追加条数。 */
    private int syncTierFeatureConfigs(List<PlatformFeatureConfig> configs) {
        List<UserTierConfig> tiers = userTierConfigDao.findAllOrderByPriority();
        validateParentChains(tiers);
        int added = 0;
        for (UserTierConfig tier : tiers) {
            if (!TierFeatureResolver.isBaseTier(tier)) {
                continue;   // 高等级继承基础等级：新功能项只补到基础等级，避免逐等级重复
            }
            List<FeatureProviderConfig> existing = tier.getFeatureConfigs() == null
                    ? new ArrayList<>() : new ArrayList<>(tier.getFeatureConfigs());
            int defaultThreshold = existing.isEmpty() ? 0 : existing.get(0).getActivationThreshold();
            List<FeatureProviderConfig> missing = mergeMissingFeatures(existing, configs, defaultThreshold);
            if (missing.isEmpty()) {
                continue;
            }
            existing.addAll(missing);
            tier.setFeatureConfigs(existing);
            userTierConfigDao.save(tier);
            added += missing.size();
            log.info("基础等级 {} 追加 {} 个功能项（高等级经 parentTierCode 自动继承）: {}",
                    tier.getTierCode(), missing.size(),
                    missing.stream().map(fc -> fc.getPlatformCode() + ":" + fc.getFeatureCode())
                            .collect(Collectors.joining(", ")));
        }
        return added;
    }

    /** 父链校验：父等级不存在 / 成环只告警（解析器会安全降级为无偏好，不阻断采集）。 */
    private void validateParentChains(List<UserTierConfig> tiers) {
        Map<String, UserTierConfig> byCode = tiers.stream()
                .collect(Collectors.toMap(UserTierConfig::getTierCode, t -> t, (a, b) -> a));
        for (UserTierConfig tier : tiers) {
            String current = tier.getParentTierCode();
            Set<String> visited = new HashSet<>();
            for (int hop = 0; current != null && !current.isBlank() && hop < TierFeatureResolver.MAX_HOPS; hop++) {
                if (!visited.add(current)) {
                    log.warn("等级父链成环（该链继承降级为无偏好）: tier={} 环起于 {}",
                            tier.getTierCode(), current);
                    break;
                }
                UserTierConfig parent = byCode.get(current);
                if (parent == null) {
                    log.warn("等级父层级不存在（该链继承在此截断）: tier={} parentTierCode={}",
                            tier.getTierCode(), current);
                    break;
                }
                current = parent.getParentTierCode();
            }
        }
    }

    /**
     * 纯计算：找出等级缺失的功能项（供单测）。
     *
     * @param existing         该等级已有的功能项
     * @param configs          全部功能配置
     * @param defaultThreshold 新增功能项使用的激活阈值（沿用该等级现有项）
     * @return 需要追加的功能项（providerOrder = 该功能全部供应商 key，按配置顺序）
     */
    static List<FeatureProviderConfig> mergeMissingFeatures(List<FeatureProviderConfig> existing,
                                                            List<PlatformFeatureConfig> configs,
                                                            int defaultThreshold) {
        Set<String> present = existing == null ? Set.of() : existing.stream()
                .map(fc -> fc.getPlatformCode() + ":" + fc.getFeatureCode())
                .collect(Collectors.toSet());
        List<FeatureProviderConfig> missing = new ArrayList<>();
        for (PlatformFeatureConfig config : configs) {
            String key = config.getPlatformCode() + ":" + config.getFeatureCode();
            if (present.contains(key) || config.getProviders() == null || config.getProviders().isEmpty()) {
                continue;
            }
            FeatureProviderConfig npc = new FeatureProviderConfig();
            npc.setPlatformCode(config.getPlatformCode());
            npc.setFeatureCode(config.getFeatureCode());
            npc.setProviderOrder(config.getProviders().stream()
                    .map(PlatformFeatureConfig.ProviderConfig::getProviderKey)
                    .collect(Collectors.toList()));
            npc.setActivationThreshold(defaultThreshold);
            missing.add(npc);
        }
        return missing;
    }

    /** 状态表有、配置没有的失效 key。 */
    private List<String> findStaleKeys(List<String> configuredKeys) {
        Set<String> configured = new HashSet<>(configuredKeys);
        return supplierStateDao.findAll().stream()
                .map(SupplierState::getSupplierKey)
                .filter(key -> !configured.contains(key))
                .collect(Collectors.toList());
    }
}
