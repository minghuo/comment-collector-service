package com.sysj.collector.controller;

import com.sysj.collector.facade.CommentCollectionFacade;
import com.sysj.collector.model.*;
import com.sysj.collector.domain.service.LinkDispatchService;
import com.sysj.collector.domain.service.TaskManagementService;
import com.sysj.collector.domain.document.CommentDoc;
import com.sysj.collector.domain.document.MasterTask;
import com.sysj.collector.domain.document.SubTask;
import com.sysj.collector.domain.document.TaskStatuses;
import com.sysj.collector.domain.dao.CommentDao;
import com.sysj.collector.domain.dao.SubTaskDao;
import com.sysj.collector.domain.dao.MasterTaskDao;
import com.sysj.collector.config.ApiKeyAuthenticator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 评论采集控制器。
 *
 * <p>提供同步采集、异步采集、任务查询等接口。
 *
 * <p><b>修 R5</b>：任务查询接口此前只按 taskId 查询，调用方可任意指定 taskId
 * （{@code userId} 也完全由请求体提供）⇒ 读到别人的采集结果（IDOR）。
 * 现由 {@link #ownershipAllowed} 提供开关式归属校验，缺省关闭以保持存量调用方行为。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "评论采集接口", description = "支持同步/异步采集、多平台多供应商路由")
public class CommentController {

    private final CommentCollectionFacade collectionFacade;
    private final TaskManagementService taskManagementService;
    private final LinkDispatchService linkDispatchService;
    private final MasterTaskDao masterTaskDao;
    private final SubTaskDao subTaskDao;
    private final CommentDao commentDao;

    /** 凭据注册表：归属校验需要把 API-Key 映射为调用方身份（修 R5）。 */
    private final ApiKeyAuthenticator apiKeyAuthenticator;

    /** 任务归属校验开关（修 R5），缺省关闭 = 保持存量调用方行为。 */
    @Value("${collector.auth.task-isolation-enabled:false}")
    private boolean taskIsolationEnabled;

    // ── 归属校验（修 R5） ──────────────────────────────────────────────────

    /**
     * 任务读取归属校验。
     *
     * <p>用 {@code collector.auth.api-key-owners} 把请求头里的密钥映射为调用方身份，
     * 与 {@code master_task.userId} 比对。<b>身份无法识别或任务无归属方时一律拒绝</b>（fail closed）。
     *
     * <p>校验失败统一返回 <b>404</b> 而非 403：403 会确认"任务存在但不是你的"，
     * 等于给调用方一个存在性预言机。
     *
     * <p>缺省关闭：内部直连场景没有身份概念，强行开启会让所有调用方读不到数据。
     */
    private boolean ownershipAllowed(MasterTask task, HttpServletRequest request) {
        if (!taskIsolationEnabled) {
            return true;
        }
        String owner = apiKeyAuthenticator.ownerOf(request.getHeader(apiKeyAuthenticator.headerName()));
        String taskOwner = task.getUserId();
        if (StringUtils.isBlank(owner) || StringUtils.isBlank(taskOwner)) {
            return false;
        }
        return constantTimeEquals(owner, taskOwner);
    }

    /** 身份比对同样走常量时间，避免比对耗时泄露归属信息。 */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }


    // ── 同步采集 ───────────────────────────────────────────────────────────

    @PostMapping("/collect")
    @Operation(summary = "同步采集评论", description = "即时返回采集结果，适用于单链接场景")
    public ResponseEntity<CommentCollectResult> collectSync(@RequestBody CommentCollectRequest request) {
        log.info("同步采集请求: platform={} targetId={} userId={}",
                request.getPlatformCode(), request.getTargetId(), request.getUserId());
        CommentCollectResult result = collectionFacade.collectSync(request);
        return ResponseEntity.ok(result);
    }

    // ── 异步采集 ───────────────────────────────────────────────────────────

    @PostMapping("/collect/async")
    @Operation(summary = "异步提交采集任务", description = "适用于多链接或供应商不支持即时返回的场景")
    public ResponseEntity<Map<String, Object>> collectAsync(@RequestBody @Valid TaskSubmitRequest request) {
        List<String> links = request.getLinks();
        if (links == null || links.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "links不能为空"));
        }
        log.info("异步采集请求: userId={} platform={} links={}",
                request.getUserId(), request.getPlatform(), links.size());

        // 1. 创建主任务（idempotencyKey 非空时重复提交返回已有任务）
        MasterTask masterTask = taskManagementService.createMasterTask(TaskCreateCommand.builder()
                .userId(request.getUserId())
                .userTierCode(request.getUserTierCode())
                .platformCode(request.getPlatform())
                .featureCode(request.getFunction())
                .links(request.getLinks())
                .mode("ASYNC")
                .requestParams(request.getRequestParams())
                .supplierConstraint(request.getSupplierConstraint())
                .callbackUrl(request.getCallbackUrl())
                .idempotencyKey(request.getIdempotencyKey())
                .autoPage(request.getAutoPage())
                .build());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("taskId", masterTask.getId());
        response.put("message", "任务已提交");

        return ResponseEntity.ok(response);
    }

    // ── 创建异步任务（通用） ───────────────────────────────────────────────

    @PostMapping("/tasks")
    @Operation(summary = "创建异步采集任务", description = "通用接口，支持多链接多参数")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody @Valid TaskSubmitRequest request) {
        List<String> links = request.getLinks();
        if (links == null || links.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "links不能为空"));
        }
        log.info("创建任务请求: userId={} platform={} links={}",
                request.getUserId(), request.getPlatform(), links.size());

        MasterTask masterTask = taskManagementService.createMasterTask(TaskCreateCommand.builder()
                .userId(request.getUserId())
                .userTierCode(request.getUserTierCode())
                .platformCode(request.getPlatform())
                .featureCode(request.getFunction())
                .links(links)
                .mode("ASYNC")
                .requestParams(request.getRequestParams())
                .supplierConstraint(request.getSupplierConstraint())
                .callbackUrl(request.getCallbackUrl())
                .idempotencyKey(request.getIdempotencyKey())
                .autoPage(request.getAutoPage())
                .build());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("taskId", masterTask.getId());
        response.put("totalLinks", masterTask.getTotalLinks());

        return ResponseEntity.ok(response);
    }

    // ── 混合平台链接自动分派 ───────────────────────────────────────────────

    /**
     * 混合平台链接自动分派：按链接特征识别平台，拆分为各平台主任务。
     *
     * <p>微博、B站等双供应商平台按 cookie 有无路由（有 → 本地采集，无 → Golaxy 渠道）；
     * 未接入平台的链接、非法链接都逐条返回错误码与原因（{@code invalidUrls} / {@code unsupportedLinks} /
     * {@code linkResults}），不再静默丢弃。
     *
     * <p>HTTP 状态码由 {@link LinkDispatchStatus} 决定：有任务建成 200；全非法 400；
     * 平台未接入/链接形态不支持/功能未开启 422；队列已满 503。
     * 部分成功同样返回 200，失败的分组在 {@code skipped}，失败链接在 {@code linkResults} 里逐条标注。
     */
    @PostMapping("/collect/dispatch")
    @Operation(summary = "混合平台链接自动分派",
            description = "提交混合平台链接，自动识别平台并拆分为各平台异步任务；"
                    + "返回批次ID、各平台子任务、逐链接判定与错误码（200/400/422/503）")
    public ResponseEntity<LinkDispatchResult> dispatchLinks(@RequestBody LinkDispatchRequest request) {
        log.info("混合平台自动分派请求: userId={} links={}",
                request == null ? null : request.getUserId(),
                request == null || request.getLinks() == null ? 0 : request.getLinks().size());
        LinkDispatchResult result = linkDispatchService.dispatch(request);
        return ResponseEntity.status(LinkDispatchStatus.resolve(result)).body(result);
    }

    /**
     * 分派批次进度聚合：返回同批拆分的各平台主任务状态。
     */
    @GetMapping("/dispatch/{dispatchId}")
    @Operation(summary = "查询分派批次进度", description = "按批次ID聚合同批各平台主任务的状态与计数")
    public ResponseEntity<Map<String, Object>> dispatchBatchStatus(@PathVariable String dispatchId) {
        List<MasterTask> tasks = linkDispatchService.findBatchTasks(dispatchId);
        if (tasks.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> taskList = new ArrayList<>();
        int totalLinks = 0;
        int successLinks = 0;
        int failedLinks = 0;
        boolean allFinished = true;
        for (MasterTask task : tasks) {
            Map<String, Object> item = new HashMap<>();
            item.put("taskId", task.getId());
            item.put("platformCode", task.getPlatformCode());
            item.put("featureCode", task.getFeatureCode());
            item.put("status", task.getStatus());
            item.put("totalLinks", task.getTotalLinks());
            item.put("successLinks", task.getSuccessLinks());
            item.put("failedLinks", task.getFailedLinks());
            item.put("createTime", task.getCreateTime());
            item.put("updateTime", task.getUpdateTime());
            taskList.add(item);

            totalLinks += task.getTotalLinks();
            successLinks += task.getSuccessLinks();
            failedLinks += task.getFailedLinks();
            // 终态判定统一走 TaskStatuses（2026-10-11 起新增 PARTIAL，不能只比 COMPLETED/FAILED，
            // 否则部分成功的主任务会被判成"仍在跑"，批次进度永远不 finished）
            if (!TaskStatuses.MASTER_TERMINAL.contains(task.getStatus())) {
                allFinished = false;
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("dispatchId", dispatchId);
        body.put("batchFinished", allFinished);
        body.put("totalLinks", totalLinks);
        body.put("successLinks", successLinks);
        body.put("failedLinks", failedLinks);
        body.put("tasks", taskList);
        return ResponseEntity.ok(body);
    }

    // ── 任务查询 ───────────────────────────────────────────────────────────

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询任务状态和结果")
    public ResponseEntity<TaskQueryResponse> queryTask(@PathVariable String taskId,
                                                      HttpServletRequest request) {
        MasterTask masterTask = masterTaskDao.findById(taskId)
                .orElse(null);

        if (masterTask == null) {
            return ResponseEntity.notFound().build();
        }

        // 归属校验（修 R5）：开关关闭时本行必为 true，行为与改前完全一致
        if (!ownershipAllowed(masterTask, request)) {
            log.warn("任务查询被拒绝（归属不匹配或无法识别调用方）: taskId={} ip={}",
                    taskId, request.getRemoteAddr());
            return ResponseEntity.notFound().build();
        }

        // 查询子任务状态
        List<SubTask> subTasks = subTaskDao.findByMasterTaskId(taskId);

        List<TaskQueryResponse.LinkStatus> linkStatuses = subTasks.stream()
                .map(st -> TaskQueryResponse.LinkStatus.builder()
                        .link(st.getLink())
                        .status(st.getStatus())
                        .supplier(st.getProviderKey())
                        .subTaskId(st.getId())
                        .errorMessage(st.getErrorMessage())
                        .build())
                .collect(Collectors.toList());

        TaskQueryResponse response = TaskQueryResponse.builder()
                .masterTaskId(masterTask.getId())
                .status(masterTask.getStatus())
                .links(linkStatuses)
                .createTime(masterTask.getCreateTime())
                .updateTime(masterTask.getUpdateTime())
                .totalLinks(masterTask.getTotalLinks())
                .successLinks(masterTask.getSuccessLinks())
                .failedLinks(masterTask.getFailedLinks())
                .build();

        return ResponseEntity.ok(response);
    }

    // ── 采集结果分页查询 ───────────────────────────────────────────────────

    /**
     * 按任务分页读取已落库的采集结果。
     *
     * <p>用于"不能即时返回"以及"需要持续翻页"的任务：任务提交后返回 taskId，
     * 调用方用本接口分页拉取已经采到的数据，无需等待全部完成。
     *
     * @param taskId   主任务 ID
     * @param page     页码，从 1 开始
     * @param size     每页条数
     * @param dataType 可选，按数据类型过滤（weibo_comment / weibo_repost / wechat_comment /
     *                 wechat_video_comment / bilibili_comment / douyin_comment / xhs_comment / toutiao_comment）
     */
    @GetMapping("/tasks/{taskId}/comments")
    @Operation(summary = "分页查询采集结果", description = "按任务读取已落库的评论/转发数据")
    public ResponseEntity<Map<String, Object>> queryComments(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String dataType,
            HttpServletRequest request) {

        // 归属校验（修 R5）：仅在开关打开时查主任务；关闭时保持"任务不存在也返回 200 空页"的既有行为
        if (taskIsolationEnabled) {
            MasterTask masterTask = masterTaskDao.findById(taskId).orElse(null);
            if (masterTask == null || !ownershipAllowed(masterTask, request)) {
                log.warn("采集结果查询被拒绝: taskId={} ip={}", taskId, request.getRemoteAddr());
                return ResponseEntity.notFound().build();
            }
        }

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 1000);

        List<CommentDoc> rows = (dataType == null || dataType.isBlank())
                ? commentDao.findByTaskId(taskId, safePage, safeSize)
                : commentDao.findByTaskIdAndDataType(taskId, dataType, safePage, safeSize);
        long total = (dataType == null || dataType.isBlank())
                ? commentDao.countByTaskId(taskId)
                : commentDao.countByTaskIdAndDataType(taskId, dataType);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("taskId", taskId);
        body.put("dataType", dataType);
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("total", total);
        body.put("data", rows);
        return ResponseEntity.ok(body);
    }

    // ── 队列状态 ───────────────────────────────────────────────────────────

    @GetMapping("/queue/status")
    @Operation(summary = "查询任务队列状态",
            description = "包含队列长度、容量上限与剩余名额；剩余为 0 时新的异步提交会被拒绝（HTTP 503）")
    public ResponseEntity<Map<String, Object>> queueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("queueSize", collectionFacade.getQueueSize());
        status.put("queueCapacity", collectionFacade.getQueueCapacity());
        status.put("queueRemaining", collectionFacade.getRemainingCapacity());
        // 队列长度可能是 0 而名额已满：消费者取走任务后才开始执行，在途任务同样占用名额
        status.put("overloaded", collectionFacade.getRemainingCapacity() <= 0);
        return ResponseEntity.ok(status);
    }

    // ── 健康检查 ───────────────────────────────────────────────────────────

    @GetMapping("/health")
    @Operation(summary = "健康检查")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "UP");
        status.put("service", "comment-collector-service");
        return ResponseEntity.ok(status);
    }
}
