package com.sysj.collector.controller;

import com.sysj.collector.facade.CommentCollectionFacade;
import com.sysj.collector.model.*;
import com.sysj.collector.domain.service.TaskManagementService;
import com.sysj.collector.domain.document.MasterTask;
import com.sysj.collector.domain.document.SubTask;
import com.sysj.collector.domain.repository.SubTaskRepository;
import com.sysj.collector.domain.repository.MasterTaskRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
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
    private final MasterTaskRepository masterTaskRepository;
    private final SubTaskRepository subTaskRepository;

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
        MasterTask masterTask = masterTaskRepository.findById(taskId)
                .orElse(null);

        if (masterTask == null) {
            return ResponseEntity.notFound().build();
        }

        // 查询子任务状态
        List<SubTask> subTasks = subTaskRepository.findByMasterTaskId(taskId);

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

    // ── 队列状态 ───────────────────────────────────────────────────────────

    @GetMapping("/queue/status")
    @Operation(summary = "查询任务队列状态")
    public ResponseEntity<Map<String, Object>> queueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("queueSize", collectionFacade.getQueueSize());
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
