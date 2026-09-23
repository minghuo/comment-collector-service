package com.sysj.collector.domain.service;

import com.sysj.collector.domain.document.MasterTask;
import com.sysj.collector.domain.document.SubTask;
import com.sysj.collector.domain.repository.MasterTaskRepository;
import com.sysj.collector.domain.repository.SubTaskRepository;
import com.sysj.collector.facade.CommentCollectionFacade;
import com.sysj.collector.model.CommentCollectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * 断点续采服务。
 *
 * <p>应用启动时扫描数据库中尚未完成的任务，重置子任务状态并重新提交到异步队列。
 * 保证项目重新发布后正在采集的任务能够续上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRecoveryService {

    private final MasterTaskRepository masterTaskRepository;
    private final SubTaskRepository subTaskRepository;
    private final TaskManagementService taskManagementService;
    private final CommentCollectionFacade collectionFacade;

    /**
     * 应用完全启动后执行断点恢复扫描。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void scanAndRecover() {
        log.info("=== 断点续采扫描开始 ===");

        // 1. 扫描未完成的主任务
        List<MasterTask> pendingTasks = masterTaskRepository.findByStatus("ACTIVE");
        pendingTasks.addAll(masterTaskRepository.findByStatus("PENDING"));

        int totalRecovered = 0;

        for (MasterTask task : pendingTasks) {
            // 2. 扫描该任务下未完成的子任务
            List<SubTask> pendingSubTasks = subTaskRepository.findByMasterTaskId(task.getId());

            for (SubTask subTask : pendingSubTasks) {
                String status = subTask.getStatus();
                if ("PENDING".equals(status) || "RUNNING".equals(status) || "RETRYING".equals(status)) {
                    // RUNNING 状态无法精确恢复，重置为 PENDING
                    subTask.setStatus("PENDING");
                    subTask.setRetryCount(0);
                    subTask.setNextExecuteTime(null);

                    // 3. 构造 CommentCollectRequest 提交到异步队列
                    CommentCollectRequest collectRequest = CommentCollectRequest.builder()
                            .platformCode(task.getPlatformCode())
                            .featureCode(task.getFeatureCode())
                            .targetId(extractTargetId(subTask.getLink()))
                            .userId(task.getUserId())
                            .userTierCode(task.getUserTierCode())
                            .build();

                    collectionFacade.collectAsync(collectRequest);
                    subTaskRepository.save(subTask);
                    totalRecovered++;
                }
            }
        }

        if (totalRecovered > 0) {
            log.info("断点续采完成: 共恢复{}个子任务", totalRecovered);
        } else {
            log.info("断点续采: 无需恢复的任务");
        }

        log.info("=== 断点续采扫描完成 ===");
    }

    /**
     * 从采集链接中提取 targetId。
     * 简单实现：取URL最后一段path；链接格式不确定时返回完整链接。
     */
    private String extractTargetId(String link) {
        if (link == null || link.isBlank()) return null;
        int lastSlash = link.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < link.length() - 1) {
            String id = link.substring(lastSlash + 1);
            // 去掉可能的查询参数
            int queryIdx = id.indexOf('?');
            return queryIdx > 0 ? id.substring(0, queryIdx) : id;
        }
        return link;
    }
}
