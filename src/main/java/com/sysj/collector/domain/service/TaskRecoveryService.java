package com.sysj.collector.domain.service;

import com.sysj.collector.domain.document.MasterTask;
import com.sysj.collector.domain.document.SubTask;
import com.sysj.collector.domain.dao.MasterTaskDao;
import com.sysj.collector.domain.dao.SubTaskDao;
import com.sysj.collector.facade.CommentCollectionFacade;
import com.sysj.collector.model.CommentCollectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 断点续采服务。
 *
 * <p>应用启动时扫描数据库中尚未完成的任务，重置子任务状态并重新提交到异步队列。
 * 保证项目重新发布后正在采集的任务能够续上。
 *
 * <p>开关由 {@code collector.recovery.*} 控制 —— 此前这两个配置**无任何读取方**（C-19 死配置）。
 * 提供开关是有实际意义的：遇到问题版本时运营需要能**先关掉恢复扫描**再排查，
 * 否则每次重启都会把一批任务重新灌进队列、放大故障。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRecoveryService {

    private final MasterTaskDao masterTaskDao;
    private final SubTaskDao subTaskDao;
    private final TaskManagementService taskManagementService;
    private final CommentCollectionFacade collectionFacade;

    /** 总开关：false 时完全不执行断点续采。 */
    @Value("${collector.recovery.enabled:true}")
    private boolean recoveryEnabled;

    /** 是否在应用启动时扫描；false 时启动不扫（保留给将来的定时/手动触发）。 */
    @Value("${collector.recovery.scan-on-startup:true}")
    private boolean scanOnStartup;

    /**
     * 应用完全启动后执行断点恢复扫描。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void scanAndRecover() {
        if (!recoveryEnabled) {
            log.info("断点续采已关闭（collector.recovery.enabled=false），跳过扫描");
            return;
        }
        if (!scanOnStartup) {
            log.info("启动扫描已关闭（collector.recovery.scan-on-startup=false），跳过扫描");
            return;
        }

        log.info("=== 断点续采扫描开始 ===");

        // 1. 扫描未完成的主任务
        List<MasterTask> pendingTasks = masterTaskDao.findByStatus("ACTIVE");
        pendingTasks.addAll(masterTaskDao.findByStatus("PENDING"));

        int totalRecovered = 0;

        for (MasterTask task : pendingTasks) {
            // 2. 扫描该任务下未完成的子任务
            List<SubTask> pendingSubTasks = subTaskDao.findByMasterTaskId(task.getId());

            for (SubTask subTask : pendingSubTasks) {
                String status = subTask.getStatus();
                if ("PENDING".equals(status) || "RUNNING".equals(status) || "RETRYING".equals(status)) {
                    // RUNNING 状态无法精确恢复，重置为 PENDING 后重新提交
                    subTask.setStatus("PENDING");
                    subTask.setRetryCount(0);
                    subTask.setNextExecuteTime(null);

                    // 3. 交给任务管理服务重新提交（内部带 taskId/subTaskId/fromUrl，
                    //    完成后会回写子任务状态并把结果落库到 comment 集合）
                    taskManagementService.submitSubTask(task, subTask);
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
