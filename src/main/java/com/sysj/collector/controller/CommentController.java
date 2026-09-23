package com.sysj.collector.controller;

import com.sysj.collector.facade.CommentCollectionFacade;
import com.sysj.collector.model.*;
import com.sysj.collector.domain.service.TaskManagementService;
import com.sysj.collector.domain.document.CommentDoc;
import com.sysj.collector.domain.document.MasterTask;
import com.sysj.collector.domain.document.SubTask;
import com.sysj.collector.domain.dao.CommentDao;
import com.sysj.collector.domain.dao.SubTaskDao;
import com.sysj.collector.domain.dao.MasterTaskDao;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 评论采集控制器。
 *
 * <p>提供同步采集、异步采集、任务查询等接口。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "评论采集接口", description = "支持同步/异步采集、多平台多供应商路由")
public class CommentController {

    private final CommentCollectionFacade collectionFacade;
    private final TaskManagementService taskManagementService;
    private final MasterTaskDao masterTaskDao;
    private final SubTaskDao subTaskDao;
    private final CommentDao commentDao;

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

        // 1. 创建主任务
        MasterTask masterTask = taskManagementService.createMasterTask(
                request.getUserId(),
                request.getUserTierCode(),
                request.getPlatform(),
                request.getFunction(),
                request.getLinks(),
                "ASYNC",
                request.getRequestParams(),
                request.getSupplierConstraint(),
                request.getCallbackUrl()
        );

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

        MasterTask masterTask = taskManagementService.createMasterTask(
                request.getUserId(),
                request.getUserTierCode(),
                request.getPlatform(),
                request.getFunction(),
                links,
                "ASYNC",
                request.getRequestParams(),
                request.getSupplierConstraint(),
                request.getCallbackUrl()
        );

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("taskId", masterTask.getId());
        response.put("totalLinks", masterTask.getTotalLinks());

        return ResponseEntity.ok(response);
    }

    // ── 任务查询 ───────────────────────────────────────────────────────────

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询任务状态和结果")
    public ResponseEntity<TaskQueryResponse> queryTask(@PathVariable String taskId) {
        MasterTask masterTask = masterTaskDao.findById(taskId)
                .orElse(null);

        if (masterTask == null) {
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
            @RequestParam(required = false) String dataType) {

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
