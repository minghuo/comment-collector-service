package com.sysj.collector.domain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysj.collector.core.router.LinkPlatformRouter;
import com.sysj.collector.domain.document.MasterTask;
import com.sysj.collector.domain.dao.MasterTaskDao;
import com.sysj.collector.exception.CollectorException;
import com.sysj.collector.exception.QueueFullException;
import com.sysj.collector.model.LinkDispatchRequest;
import com.sysj.collector.model.TaskCreateCommand;
import com.sysj.collector.model.LinkDispatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 混合平台链接自动分派服务（移植自 auto-task-web 的 {@code CommentDispatchService}）。
 *
 * <p>接收混合平台的链接，按链接特征自动识别平台（{@link LinkPlatformRouter}），
 * 按平台分组后逐组创建主任务（复用 {@link TaskManagementService} 的原子容量预留与拆分逻辑），
 * 免去调用方按平台手工分组多次提交。
 *
 * <h3>与 auto-task-web 版本的差异</h3>
 * <ul>
 *   <li>分组目标从"任务类型字符串"改为本服务的 {@code platformCode + featureCode}；
 *       平台内选供应商仍走统一路由（用户偏好/健康/熔断/阈值），不在这里指定。</li>
 *   <li>微博双供应商路由用能力约束表达（有 cookie → 要求 {@code LOGIN_STATE}；
 *       无 cookie → 排除 {@code LOGIN_STATE}），而不是两个任务类型。</li>
 *   <li>不处理 Excel 上传：本服务面向系统间调用，链接经 JSON 列表提交。
 *       跳过原因的语义保持一致（"功能未开启或不存在"）。</li>
 * </ul>
 *
 * <h3>整批语义</h3>
 * 队列容量按组预留（{@link TaskManagementService} 内部原子预留整组名额）。
 * 并发突发下后到的组可能被 503 拒绝：已建成的组照常执行，
 * 被拒的组归入 {@code skipped} 并注明"队列已满"，结果里如实体报，调用方可只对失败分组重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkDispatchService {

    private final TaskManagementService taskManagementService;
    private final ProviderConfigService configService;
    private final MasterTaskDao masterTaskDao;
    private final ObjectMapper objectMapper;

    /**
     * 自动分派混合平台链接。
     *
     * @param request 分派请求（链接列表 + 可选 cookie / 透传参数）
     * @return 分派结果：批次 ID、各平台子任务、无法识别链接、被跳过分组
     * @throws CollectorException 链接清洗后为空
     */
    public LinkDispatchResult dispatch(LinkDispatchRequest request) {
        if (request == null || request.getLinks() == null || request.getLinks().isEmpty()) {
            throw new CollectorException("links 不能为空");
        }

        // 1. 链接清洗去重（保留首次出现顺序）
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String link : request.getLinks()) {
            String url = normalizeUrl(link);
            if (url != null) {
                urls.add(url);
            }
        }
        if (urls.isEmpty()) {
            throw new CollectorException("links 清洗后为空（仅保留 http/https 开头的链接）");
        }

        // 2. cookie 判定：显式字段优先，其次 requestParams 里的 cookie 键
        boolean hasCookie = StringUtils.isNotBlank(request.getCookie())
                || hasCookieInParams(request.getRequestParams());

        // 3. 逐链接路由分组
        Map<String, Group> groups = new LinkedHashMap<>();
        List<String> unsupportedUrls = new ArrayList<>();
        for (String url : urls) {
            LinkPlatformRouter.Route route = LinkPlatformRouter.route(url, hasCookie);
            if (route == null) {
                unsupportedUrls.add(url);
                continue;
            }
            groups.computeIfAbsent(groupKey(route.platformCode(), route.featureCode()),
                    k -> new Group(route)).urls.add(url);
        }

        // 4. 逐组建任务（预检配置 → 原子容量预留 → 拆分提交，全在 createMasterTask 内完成）
        String dispatchId = UUID.randomUUID().toString().replace("-", "");
        List<LinkDispatchResult.DispatchedTask> tasks = new ArrayList<>();
        List<LinkDispatchResult.SkippedGroup> skipped = new ArrayList<>();
        int dispatchedUrls = 0;

        for (Group group : groups.values()) {
            // 4.1 预检功能配置：未配置/被禁用的平台提前跳过，与 auto-task-web 的"功能未开启"语义一致
            try {
                configService.loadFeatureConfig(group.route.platformCode(), group.route.featureCode());
            } catch (Exception e) {
                log.info("自动分派跳过分组: platform={} feature={} urlCount={} reason={}",
                        group.route.platformCode(), group.route.featureCode(), group.urls.size(), e.getMessage());
                skipped.add(LinkDispatchResult.SkippedGroup.builder()
                        .platformCode(group.route.platformCode())
                        .featureCode(group.route.featureCode())
                        .platformName(group.route.platformName())
                        .urlCount(group.urls.size())
                        .reason("功能未开启或不存在")
                        .build());
                continue;
            }

            // 4.2 建任务：整组原子预留容量，队列满时该组跳过（不回滚已建成的其他组）
            List<String> requiredCaps = capabilityNames(group.route.requiredCapabilities());
            List<String> excludedCaps = capabilityNames(group.route.excludedCapabilities());
            try {
                MasterTask masterTask = taskManagementService.createMasterTask(TaskCreateCommand.builder()
                        .userId(request.getUserId())
                        .userTierCode(request.getUserTierCode())
                        .platformCode(group.route.platformCode())
                        .featureCode(group.route.featureCode())
                        .links(group.urls)
                        .mode("ASYNC")
                        .requestParams(request.getRequestParams())
                        .supplierConstraint(request.getSupplierConstraint())
                        .callbackUrl(request.getCallbackUrl())
                        .dispatchId(dispatchId)
                        .requiredCapabilities(requiredCaps)
                        .excludedCapabilities(excludedCaps)
                        .autoPage(request.getAutoPage())
                        .build());
                dispatchedUrls += group.urls.size();
                tasks.add(LinkDispatchResult.DispatchedTask.builder()
                        .taskId(masterTask.getId())
                        .platformCode(group.route.platformCode())
                        .featureCode(group.route.featureCode())
                        .platformName(group.route.platformName())
                        .urlCount(group.urls.size())
                        .build());
            } catch (QueueFullException qfe) {
                log.warn("自动分派分组被队列拒绝: platform={} urlCount={} queue={}/{}",
                        group.route.platformCode(), group.urls.size(), qfe.getQueueSize(), qfe.getQueueCapacity());
                skipped.add(LinkDispatchResult.SkippedGroup.builder()
                        .platformCode(group.route.platformCode())
                        .featureCode(group.route.featureCode())
                        .platformName(group.route.platformName())
                        .urlCount(group.urls.size())
                        .reason("服务繁忙：异步队列已满，请稍后重试")
                        .build());
            }
        }

        log.info("评论任务自动分派完成: dispatchId={} total={} dispatched={} unsupported={} tasks={} skipped={}",
                dispatchId, urls.size(), dispatchedUrls, unsupportedUrls.size(), tasks.size(), skipped.size());

        return LinkDispatchResult.builder()
                .dispatchId(dispatchId)
                .totalUrls(urls.size())
                .dispatchedUrls(dispatchedUrls)
                .unsupportedUrls(unsupportedUrls)
                .tasks(tasks)
                .skipped(skipped)
                .build();
    }

    /**
     * 查询分派批次的全部主任务（按创建时间升序），供批次进度聚合。
     */
    public List<MasterTask> findBatchTasks(String dispatchId) {
        return masterTaskDao.findByDispatchId(dispatchId);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /** 同批同平台分组（组内链接共用同一条路由结果）。 */
    private static final class Group {
        private final LinkPlatformRouter.Route route;
        private final List<String> urls = new ArrayList<>();

        private Group(LinkPlatformRouter.Route route) {
            this.route = route;
        }
    }

    private static String groupKey(String platformCode, String featureCode) {
        return platformCode + ":" + featureCode;
    }

    /** 能力枚举 → 配置/落库用名称列表；空集返回 null（表示"不限制"，与手工建任务行为一致）。 */
    private static List<String> capabilityNames(List<com.sysj.collector.core.provider.Capability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return null;
        }
        return capabilities.stream().map(Enum::name).collect(Collectors.toList());
    }

    /**
     * 链接清洗（与 auto-task-web 的 splitCrawlerUrls 一致）：
     * 去首尾空白与 # 注释，仅保留 http/https 开头的链接。
     */
    private static String normalizeUrl(String line) {
        if (StringUtils.isBlank(line)) {
            return null;
        }
        String url = StringUtils.trim(line);
        url = url.replaceAll("#.*", "").trim();
        if (StringUtils.isBlank(url) || !url.startsWith("http")) {
            return null;
        }
        return url;
    }

    /** requestParams（JSON字符串）里是否带了非空 cookie 键；解析失败按无 cookie 处理。 */
    private boolean hasCookieInParams(String requestParams) {
        if (StringUtils.isBlank(requestParams)) {
            return false;
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(requestParams, new TypeReference<Map<String, Object>>() {
            });
            Object cookie = raw.get("cookie");
            return cookie != null && StringUtils.isNotBlank(String.valueOf(cookie));
        } catch (Exception e) {
            log.warn("requestParams 解析失败，按无 cookie 处理: {}", requestParams);
            return false;
        }
    }
}
