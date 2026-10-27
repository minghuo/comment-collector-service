package com.sysj.collector.domain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysj.collector.core.router.LinkPlatformRouter;
import com.sysj.collector.domain.document.MasterTask;
import com.sysj.collector.domain.dao.MasterTaskDao;
import com.sysj.collector.exception.CollectorException;
import com.sysj.collector.exception.QueueFullException;
import com.sysj.collector.model.DispatchErrorCodes;
import com.sysj.collector.model.LinkDispatchRequest;
import com.sysj.collector.model.LinkDispatchResult;
import com.sysj.collector.model.LinkIssue;
import com.sysj.collector.model.TaskCreateCommand;
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
 * <h3>逐链接可解释性（2026-10-27 补强）</h3>
 * 每条链接都会得到一条 {@link LinkDispatchResult.LinkResult}，无法分派时带稳定错误码
 * （{@link DispatchErrorCodes}）与原因；非法链接不再被静默丢弃，
 * 而是进 {@code invalidUrls} 并标 {@code INVALID_LINK} —— 调用方据此能按链接重试或换链接，
 * 而不是收到一个 200 却不知道哪条链接没生效。
 *
 * <h3>整批语义</h3>
 * 队列容量按组预留（{@link TaskManagementService} 内部原子预留整组名额）。
 * 并发突发下后到的组可能被 503 拒绝：已建成的组照常执行，
 * 被拒的组归入 {@code skipped} 并注明"队列已满"，调用方可只对失败分组重试。
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
     * @return 分派结果：批次 ID、各平台子任务、逐链接判定（失败带错误码与原因）
     * @throws CollectorException links 为空（无法给出逐链接原因）
     */
    public LinkDispatchResult dispatch(LinkDispatchRequest request) {
        if (request == null || request.getLinks() == null || request.getLinks().isEmpty()) {
            throw new CollectorException("links 不能为空");
        }
        List<String> inputs = request.getLinks();

        // 1. 链接清洗 + 逐链接判定（保留首次出现顺序；重复链接只判定一次）
        boolean hasCookie = StringUtils.isNotBlank(request.getCookie())
                || hasCookieInParams(request.getRequestParams());

        Map<String, Group> groups = new LinkedHashMap<>();
        List<LinkIssue> invalidIssues = new ArrayList<>();
        List<LinkIssue> unsupportedIssues = new ArrayList<>();
        List<LinkDispatchResult.LinkResult> linkResults = new ArrayList<>();
        Map<String, Integer> indexOfUrl = new LinkedHashMap<>();
        LinkedHashSet<String> urls = new LinkedHashSet<>();

        for (String raw : inputs) {
            String url = clean(raw);
            if (url == null) {
                String reason = StringUtils.isBlank(raw) ? "链接为空" : "不是合法的 http/https 链接";
                invalidIssues.add(issue(raw, DispatchErrorCodes.INVALID_LINK, reason));
                linkResults.add(result(raw, "INVALID", null, null, DispatchErrorCodes.INVALID_LINK, reason));
                continue;
            }
            if (!urls.add(url)) {
                continue;
            }
            LinkPlatformRouter.Classification c = LinkPlatformRouter.classify(url, hasCookie);
            if (c.supported()) {
                LinkPlatformRouter.Route route = c.route();
                groups.computeIfAbsent(groupKey(route.platformCode(), route.featureCode()),
                        k -> new Group(route)).urls.add(url);
                indexOfUrl.put(url, linkResults.size());
                // 先按"已识别"落位，分组执行后再回填 DISPATCHED / SKIPPED
                linkResults.add(result(url, "SKIPPED", route, null, null, null));
                continue;
            }
            boolean invalid = DispatchErrorCodes.INVALID_LINK.equals(c.errorCode());
            (invalid ? invalidIssues : unsupportedIssues)
                    .add(issue(url, c.errorCode(), c.reason()));
            linkResults.add(result(url, invalid ? "INVALID" : "UNSUPPORTED", null, null, c.errorCode(), c.reason()));
        }

        // 2. 逐组建任务（预检配置 → 原子容量预留 → 拆分提交，全在 createMasterTask 内完成）
        String dispatchId = UUID.randomUUID().toString().replace("-", "");
        List<LinkDispatchResult.DispatchedTask> tasks = new ArrayList<>();
        List<LinkDispatchResult.SkippedGroup> skipped = new ArrayList<>();
        int dispatchedUrls = 0;

        for (Group group : groups.values()) {
            // 2.1 预检功能配置：未配置/被禁用的平台提前跳过，与 auto-task-web 的"功能未开启"语义一致
            try {
                configService.loadFeatureConfig(group.route.platformCode(), group.route.featureCode());
            } catch (Exception e) {
                log.info("自动分派跳过分组: platform={} feature={} urlCount={} reason={}",
                        group.route.platformCode(), group.route.featureCode(), group.urls.size(), e.getMessage());
                String reason = "功能未开启或不存在: platform=" + group.route.platformCode()
                        + " feature=" + group.route.featureCode();
                skipped.add(skippedGroup(group, DispatchErrorCodes.FEATURE_DISABLED, reason));
                markGroupLinks(linkResults, indexOfUrl, group, "SKIPPED", null,
                        DispatchErrorCodes.FEATURE_DISABLED, reason);
                continue;
            }

            // 2.2 建任务：整组原子预留容量，队列满时该组跳过（不回滚已建成的其他组）
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
                markGroupLinks(linkResults, indexOfUrl, group, "DISPATCHED", masterTask.getId(), null, null);
            } catch (QueueFullException qfe) {
                log.warn("自动分派分组被队列拒绝: platform={} urlCount={} queue={}/{}",
                        group.route.platformCode(), group.urls.size(), qfe.getQueueSize(), qfe.getQueueCapacity());
                String reason = "服务繁忙：异步队列已满，请稍后重试";
                skipped.add(skippedGroup(group, DispatchErrorCodes.QUEUE_FULL, reason));
                markGroupLinks(linkResults, indexOfUrl, group, "SKIPPED", null,
                        DispatchErrorCodes.QUEUE_FULL, reason);
            }
        }

        // 3. 组装结果：success=false 时给出整批错误码，供 controller 映射 400/422/503
        boolean success = dispatchedUrls > 0;
        LinkDispatchResult.LinkDispatchResultBuilder builder = LinkDispatchResult.builder()
                .success(success)
                .dispatchId(dispatchId)
                .receivedUrls(inputs.size())
                .totalUrls(urls.size())
                .dispatchedUrls(dispatchedUrls)
                .unsupportedUrls(unsupportedIssues.stream().map(LinkIssue::getUrl).collect(Collectors.toList()))
                .invalidUrls(invalidIssues)
                .unsupportedLinks(unsupportedIssues)
                .linkResults(linkResults)
                .tasks(tasks)
                .skipped(skipped);
        if (!success) {
            String code = dominantErrorCode(invalidIssues, unsupportedIssues, skipped);
            builder.errorCode(code).message(messageOf(code, invalidIssues, unsupportedIssues, skipped));
        }
        LinkDispatchResult result = builder.build();

        log.info("评论任务自动分派完成: dispatchId={} received={} unique={} dispatched={} invalid={} unsupported={} tasks={} skipped={} success={}",
                dispatchId, inputs.size(), urls.size(), dispatchedUrls, invalidIssues.size(),
                unsupportedIssues.size(), tasks.size(), skipped.size(), success);
        return result;
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
     *
     * @return 清洗后的链接；清洗后为空或不是 http/https 时返回 null（由调用方登记为非法链接）
     */
    private static String clean(String line) {
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

    private static LinkIssue issue(String url, String errorCode, String reason) {
        return LinkIssue.builder().url(url).errorCode(errorCode).reason(reason).build();
    }

    private static LinkDispatchResult.LinkResult result(String url, String status,
                                                        LinkPlatformRouter.Route route,
                                                        String taskId, String errorCode, String reason) {
        return LinkDispatchResult.LinkResult.builder()
                .url(url)
                .status(status)
                .platformCode(route == null ? null : route.platformCode())
                .featureCode(route == null ? null : route.featureCode())
                .platformName(route == null ? null : route.platformName())
                .taskId(taskId)
                .errorCode(errorCode)
                .reason(reason)
                .build();
    }

    private static LinkDispatchResult.SkippedGroup skippedGroup(Group group, String errorCode, String reason) {
        return LinkDispatchResult.SkippedGroup.builder()
                .platformCode(group.route.platformCode())
                .featureCode(group.route.featureCode())
                .platformName(group.route.platformName())
                .urlCount(group.urls.size())
                .errorCode(errorCode)
                .reason(reason)
                .build();
    }

    /** 分组结果回填到该组每条链接的判定上（组内链接共享同一结局）。 */
    private static void markGroupLinks(List<LinkDispatchResult.LinkResult> linkResults,
                                       Map<String, Integer> indexOfUrl,
                                       Group group,
                                       String status,
                                       String taskId,
                                       String errorCode,
                                       String reason) {
        for (String url : group.urls) {
            Integer idx = indexOfUrl.get(url);
            if (idx == null || idx < 0 || idx >= linkResults.size()) {
                continue;
            }
            LinkDispatchResult.LinkResult row = linkResults.get(idx);
            row.setStatus(status);
            row.setTaskId(taskId);
            row.setErrorCode(errorCode);
            row.setReason(reason);
        }
    }

    /**
     * 整批错误码：用于把"一条都没成功"映射成恰当 HTTP 状态码。
     *
     * <p>优先级：队列满（503，可重试） &gt; 全非法（400，请求问题） &gt; 平台/链接不支持（422）。
     */
    private static String dominantErrorCode(List<LinkIssue> invalidIssues,
                                            List<LinkIssue> unsupportedIssues,
                                            List<LinkDispatchResult.SkippedGroup> skipped) {
        boolean allQueueFull = !skipped.isEmpty() && skipped.stream()
                .allMatch(s -> DispatchErrorCodes.QUEUE_FULL.equals(s.getErrorCode()));
        if (allQueueFull && invalidIssues.isEmpty() && unsupportedIssues.isEmpty()) {
            return DispatchErrorCodes.QUEUE_FULL;
        }
        if (unsupportedIssues.isEmpty() && skipped.isEmpty()) {
            return DispatchErrorCodes.INVALID_LINK;
        }
        boolean anyPlatformMissing = unsupportedIssues.stream()
                .anyMatch(i -> DispatchErrorCodes.PLATFORM_UNSUPPORTED.equals(i.getErrorCode()));
        if (anyPlatformMissing || invalidIssues.size() > 0 && unsupportedIssues.isEmpty() && !skipped.isEmpty()) {
            return DispatchErrorCodes.PLATFORM_UNSUPPORTED;
        }
        if (!unsupportedIssues.isEmpty()) {
            return DispatchErrorCodes.LINK_UNSUPPORTED;
        }
        return skipped.get(0).getErrorCode();
    }

    private static String messageOf(String code, List<LinkIssue> invalidIssues,
                                    List<LinkIssue> unsupportedIssues,
                                    List<LinkDispatchResult.SkippedGroup> skipped) {
        return switch (code) {
            case DispatchErrorCodes.QUEUE_FULL -> "服务繁忙：异步队列已满，请稍后重试";
            case DispatchErrorCodes.INVALID_LINK -> "没有可用的链接：非法链接 " + invalidIssues.size() + " 条";
            case DispatchErrorCodes.PLATFORM_UNSUPPORTED ->
                    "没有可用的链接：未接入的平台或功能未开启（不支持 " + (unsupportedIssues.size() + skipped.size())
                            + " 组/条）";
            case DispatchErrorCodes.LINK_UNSUPPORTED ->
                    "没有可用的链接：平台已接入但链接形态不支持（" + unsupportedIssues.size() + " 条）";
            default -> "没有可用的链接";
        };
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
