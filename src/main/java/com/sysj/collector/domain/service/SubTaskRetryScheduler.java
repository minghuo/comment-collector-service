package com.sysj.collector.domain.service;

import com.sysj.collector.domain.dao.MasterTaskDao;
import com.sysj.collector.domain.dao.SubTaskDao;
import com.sysj.collector.domain.document.MasterTask;
import com.sysj.collector.domain.document.SubTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 子任务级重投调度器：周期扫描 RETRYING 且到期的子任务，重新提交到异步队列。
 *
 * <p>重试闭环的"延迟"不是靠睡眠线程，而是把 {@code next_execute_time} 落库后由本调度器扫表
 * —— 因此重投计划在进程重启后依然有效（启动恢复扫描只接管 PENDING/RUNNING，
 * RETRYING 留给本调度器按既定退避时间继续，两个机制职责不重叠、也不会互相重灌）。
 *
 * <h3>扫描设计</h3>
 * <ul>
 *   <li>固定延迟轮询（上一轮扫完再计时），配合部分索引 {@code idx_retry_scan}
 *       只索引 RETRYING 文档，开销与待重投规模成正比；</li>
 *   <li>每轮限量（默认 200），避免一次扫出大量任务瞬间灌满队列 ——
 *       多出来的等下一轮（15s 后）自然接续；</li>
 *   <li>重投提交时队列满不判死，推迟后再试（见 {@link TaskManagementService#resubmitForRetry}）。</li>
 * </ul>
 *
 * <p>已知边界：与断点续采一致，扫描在**本实例**执行。多实例部署时各实例都会扫到同一批
 * RETRYING 子任务并重复提交（提交后状态即变 RUNNING，窗口只有毫秒级，风险有限但存在）；
 * 队列外置化（阶段2）时一并解决。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubTaskRetryScheduler {

    /** 每轮最多重投的子任务数。 */
    @Value("${collector.task.sub-retry-scan-limit:200}")
    private int scanLimitDefault;

    /** 重投被队列拒绝时的推迟时长（毫秒）。 */
    @Value("${collector.task.sub-retry-queue-reschedule-ms:30000}")
    private long queueRescheduleMsDefault;

    private final SubTaskDao subTaskDao;
    private final MasterTaskDao masterTaskDao;
    private final TaskManagementService taskManagementService;
    private final SystemConfigService systemConfigService;

    // ── 动态配置读取（system_config 覆盖 properties 默认，60s 缓存；
    //    扫描周期是 @Scheduled 启动期定死的，改周期需重启） ────────────────────

    private int scanLimit() {
        return (int) systemConfigService.getLong("collector.task.sub-retry-scan-limit", scanLimitDefault);
    }

    private long queueRescheduleMs() {
        return systemConfigService.getLong("collector.task.sub-retry-queue-reschedule-ms", queueRescheduleMsDefault);
    }

    /**
     * 扫描并重投到期子任务。
     */
    @Scheduled(fixedDelayString = "${collector.task.sub-retry-scan-ms:15000}",
            initialDelayString = "${collector.task.sub-retry-initial-delay-ms:20000}")
    public void scanAndResubmit() {
        List<SubTask> dueTasks = subTaskDao.findDueRetries(scanLimit());
        if (dueTasks.isEmpty()) {
            return;
        }
        log.info("子任务重投扫描: 待重投{}个", dueTasks.size());
        int resubmitted = 0;
        for (SubTask subTask : dueTasks) {
            MasterTask masterTask = masterTaskDao.findById(subTask.getMasterTaskId()).orElse(null);
            if (masterTask == null) {
                // 主任务已不存在（被清理）：把子任务收敛为终态，避免永远留在扫描结果里
                log.warn("重投跳过：主任务不存在，子任务判死: subTaskId={} masterTaskId={}",
                        subTask.getId(), subTask.getMasterTaskId());
                subTask.setStatus("FAILED");
                subTask.setErrorMessage("主任务不存在，无法重投");
                subTask.setCompleteTime(java.time.Instant.now());
                subTask.setUpdateTime(java.time.Instant.now());
                subTaskDao.save(subTask);
                continue;
            }
            taskManagementService.resubmitForRetry(masterTask, subTask,
                    queueRescheduleMs());
            resubmitted++;
        }
        log.info("子任务重投扫描完成: 已提交{}个", resubmitted);
    }
}
