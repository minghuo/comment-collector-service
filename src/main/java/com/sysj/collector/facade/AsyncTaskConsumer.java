package com.sysj.collector.facade;

import com.sysj.collector.core.scheduler.FairQuotaPolicy;
import com.sysj.collector.domain.dao.SubTaskDao;
import com.sysj.collector.domain.document.SubTask;
import com.sysj.collector.domain.document.TaskStatuses;
import com.sysj.collector.domain.service.SystemConfigService;
import com.sysj.collector.metrics.TaskMetrics;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommentCollectResult;
import com.sysj.collector.model.QueuedTask;
import com.sysj.collector.exception.QueueFullException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 异步任务消费者（阶段2：消费 Redis Stream 外置队列）。
 *
 * <h3>消费循环（每个消费者线程）</h3>
 * <ol>
 *   <li>周期性清自己名下的 pending（XREADGROUP id=0）：接管重启前的遗留、处理失败的重投；</li>
 *   <li>按公平配额选流读新消息（XREADGROUP {@code >}）：
 *       HIGH 优先，LOW 按轮次预留名额防饿死（复用 {@link FairQuotaPolicy}），
 *       HIGH 空时 LOW 兜底（阻塞读），不会饿死任何一级；</li>
 *   <li>幂等守卫 → 执行 → 结果回写 → XACK+XDEL。</li>
 * </ol>
 *
 * <h3>幂等守卫（至少一次投递的配套）</h3>
 * 消息可能因崩溃后 XCLAIM 接管、deadline 迁移竞态被执行多次。执行前查子任务状态：
 * 已是终态（SUCCESS/FAILED）或 RETRYING（归重投调度器管）→ 直接确认跳过；
 * 执行后结果回写用条件更新保证主任务计数只累加一次（见 TaskManagementService）。
 * 重复执行的代价只是一次重复采集，落库按业务主键 upsert，无重复数据。
 *
 * <h3>维护任务（每实例都跑，操作幂等）</h3>
 * <ul>
 *   <li><b>XCLAIM 接管</b>：空闲超过 claim-min-idle-ms 的 pending 消息接管到本实例消费者名下
 *       —— 覆盖"实例处理中途崩溃/停机未确认"的场景；</li>
 *   <li><b>deadline 迁移</b>：LOW 流中等待超过 max-wait-minutes 的任务迁到 HIGH 流
 *       （原内存队列 deadline 保障的外置化；两流近似下不再做逐级 aging）。</li>
 * </ul>
 */
@Slf4j
@Component
public class AsyncTaskConsumer {

    @Value("${collector.task.consumer-threads:0}")
    private int consumerThreadsDefault;

    @Value("${collector.queue.consumer-name:}")
    private String consumerNameDefault;

    /** 读新消息的阻塞时长（毫秒）。 */
    @Value("${collector.queue.block-ms:5000}")
    private long blockMs;

    /** 每隔多少次循环做一次 pending 清扫。 */
    @Value("${collector.queue.pending-drain-every:25}")
    private int pendingDrainEvery;

    /** 接管扫描间隔（毫秒）。 */
    @Value("${collector.queue.claim-sweep-ms:60000}")
    private long claimSweepMs;

    /** 消息空闲多久才算"原消费者已死"（毫秒）——必须大于单任务最大执行时长。 */
    @Value("${collector.queue.claim-min-idle-ms:600000}")
    private long claimMinIdleMs;

    private final RedisTaskStream taskStream;
    private final CommentCollectionFacade facade;
    private final TaskResultListener taskResultListener;
    private final SubTaskDao subTaskDao;
    private final TaskMetrics taskMetrics;
    private final SystemConfigService systemConfigService;
    private final RedisHealthMonitor redisHealth;

    private ExecutorService consumerExecutor;
    private String consumerName;
    private volatile boolean running = true;

    public AsyncTaskConsumer(RedisTaskStream taskStream,
                             CommentCollectionFacade facade,
                             TaskResultListener taskResultListener,
                             SubTaskDao subTaskDao,
                             TaskMetrics taskMetrics,
                             SystemConfigService systemConfigService,
                             RedisHealthMonitor redisHealth) {
        this.taskStream = taskStream;
        this.facade = facade;
        this.taskResultListener = taskResultListener;
        this.subTaskDao = subTaskDao;
        this.taskMetrics = taskMetrics;
        this.systemConfigService = systemConfigService;
        this.redisHealth = redisHealth;
    }

    @PostConstruct
    public void start() {
        int threads = consumerThreadsDefault > 0
                ? consumerThreadsDefault
                : Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
        this.consumerName = consumerNameDefault == null || consumerNameDefault.isBlank()
                ? defaultConsumerName()
                : consumerNameDefault;
        this.consumerExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "collector-stream-" + System.nanoTime());
            t.setDaemon(false);
            return t;
        });
        for (int i = 0; i < threads; i++) {
            consumerExecutor.submit(this::consumeLoop);
        }
        log.info("Redis Stream 消费者已启动: consumer={} threads={} blockMs={} claimMinIdle={}ms",
                consumerName, threads, blockMs, claimMinIdleMs);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        consumerExecutor.shutdown();
        try {
            if (!consumerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("消费者停止超时：未确认消息将留在 PEL，由 XCLAIM 接管或下次启动后的 pending 清扫接管");
                consumerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            consumerExecutor.shutdownNow();
        }
    }

    // ── 消费循环 ───────────────────────────────────────────────────────────

    private void consumeLoop() {
        FairRound round = new FairRound();
        int iterations = 0;
        while (running) {
            try {
                // 0. Redis 不可用（降级模式）：消费本地兜底队列，Redis 恢复后自动切回流消费
                if (!facade.isRedisAvailable() || !taskStream.isGroupsReady()) {
                    QueuedTask local = facade.pollLocalTask();
                    if (local == null) {
                        sleepQuiet(1000);
                        continue;
                    }
                    CommentCollectRequest request = local.toRequest();
                    try {
                        executeAndReport(request);
                    } catch (Exception e) {
                        // 结果回写失败：Redis 降级期与流内消息不同——本地没有 PEL 可依赖，
                        // 交回兜底队列等下一轮（结果回写的条件更新保证幂等，重复执行无害）
                        log.warn("本地兜底任务处理失败，退避后重试: subTaskId={} error={}",
                                request.getSubTaskId(), e.getMessage());
                        facade.offerLocalBack(local);
                        sleepQuiet(5000);
                    }
                    continue;
                }

                // 1. 周期性清自己名下 pending（重启接管 + 处理失败重投），优先于新消息
                if (++iterations % Math.max(1, pendingDrainEvery) == 0 && drainPending()) {
                    continue;
                }

                // 2. 公平配额选流读一条
                FairQuotaPolicy policy = currentPolicy();
                replanRoundIfNeeded(round, policy);
                Picked picked = readWithFairQuota(round);
                if (picked == null) {
                    continue;
                }
                round.picksRemaining--;
                if (QueueTiers.LOW.equals(picked.tier()) && round.lowPriorityRemaining > 0) {
                    round.lowPriorityRemaining--;
                }
                taskMetrics.setQueueSize((int) taskStream.totalLength());
                process(picked.tier(), picked.record());
            } catch (Exception e) {
                // 阻塞读/处理异常都不是致命的：退避后继续；停机由 running 标志与 shutdownNow 收口
                log.error("消费循环异常，退避后继续", e);
                sleepQuiet(1000);
            }
        }
    }

    /** 清扫本消费者名下 pending；返回是否清扫到了消息。 */
    private boolean drainPending() {
        boolean found = false;
        for (String tier : List.of(QueueTiers.HIGH, QueueTiers.LOW)) {
            for (MapRecord<String, Object, Object> record : taskStream.readPending(tier, consumerName)) {
                found = true;
                process(tier, record);
            }
        }
        return found;
    }

    /**
     * 按公平配额读一条新消息。
     *
     * <p>LOW 有预留名额 → 先短阻塞试 LOW（捞不到立即放弃本轮预留，避免为公平饿死高优先级）；
     * 否则 HIGH 优先（阻塞读），HIGH 空时 LOW 兜底（阻塞读）——两级都不会饿死。
     *
     * @return 一条消息及其来源流；两条流都暂时为空时返回 null（外层继续循环）
     */
    private Picked readWithFairQuota(FairRound round) {
        if (round.lowPriorityRemaining > 0) {
            List<MapRecord<String, Object, Object>> low =
                    taskStream.readNew(QueueTiers.LOW, consumerName, Math.min(200, blockMs), 1);
            if (!low.isEmpty()) {
                return new Picked(QueueTiers.LOW, low.get(0));
            }
            round.lowPriorityRemaining = 0;
        }
        List<MapRecord<String, Object, Object>> high =
                taskStream.readNew(QueueTiers.HIGH, consumerName, blockMs, 1);
        if (!high.isEmpty()) {
            return new Picked(QueueTiers.HIGH, high.get(0));
        }
        List<MapRecord<String, Object, Object>> low =
                taskStream.readNew(QueueTiers.LOW, consumerName, blockMs, 1);
        return low.isEmpty() ? null : new Picked(QueueTiers.LOW, low.get(0));
    }

    /**
     * 处理一条流内消息：解码 → 幂等守卫 → 执行 → 回写 → 确认。
     *
     * <p><b>确认纪律</b>：只有"守卫跳过 / 结果已回写"才 XACK+XDEL；
     * 守卫或回写本身失败（如 Mongo/Redis 抖动）**不确认**，消息留在 PEL 由 XCLAIM 重投。
     * 毒消息（载荷解析失败）记录后直接确认丢弃。
     */
    private void process(String tier, MapRecord<String, Object, Object> record) {
        RecordId id = record.getId();
        QueuedTask task;
        try {
            task = taskStream.decode(record);
        } catch (Exception e) {
            log.error("毒消息丢弃: stream={} id={} error={}", tier, id, e.getMessage());
            try {
                taskStream.ackAndDelete(tier, id);
            } catch (Exception ignored) {
                redisHealth.markFailure("ackAndDelete", ignored);
            }
            return;
        }
        CommentCollectRequest request = task.toRequest();

        try {
            // 幂等守卫：终态/重投中的子任务不再执行
            SubTask subTask = subTaskDao.findById(request.getSubTaskId()).orElse(null);
            if (subTask == null) {
                log.warn("子任务不存在（主任务可能已被清理），确认跳过: subTaskId={}", request.getSubTaskId());
                taskStream.ackAndDelete(tier, id);
                return;
            }
            String status = subTask.getStatus();
            if (TaskStatuses.SUB_SUCCESS.equals(status) || TaskStatuses.SUB_FAILED.equals(status)
                    || TaskStatuses.SUB_RETRYING.equals(status)) {
                log.debug("子任务已处于 {}，确认跳过重复投递: subTaskId={}", status, request.getSubTaskId());
                taskStream.ackAndDelete(tier, id);
                return;
            }

            // 执行 + 回写
            executeAndReport(request);
            taskStream.ackAndDelete(tier, id);
        } catch (Exception e) {
            // 不确认：留 PEL，等 XCLAIM（claim-min-idle-ms 后）或下次 pending 清扫重投
            redisHealth.markFailure("process", e);
            log.error("消息处理失败，保留待重投: stream={} id={} subTaskId={} error={}",
                    tier, id, request.getSubTaskId(), e.getMessage(), e);
            sleepQuiet(5000);
        }
    }

    /**
     * 执行一次采集并回写结果（流内消息与本地兜底任务共用）。
     *
     * @throws Exception 结果回写失败（调用方决定重投策略；执行异常已收敛为 error 参数）
     */
    private void executeAndReport(CommentCollectRequest request) throws Exception {
        CommentCollectResult result = null;
        Throwable error = null;
        try {
            result = facade.doCollect(request);
        } catch (Exception e) {
            error = e;
            log.error("异步采集执行异常: subTaskId={} error={}", request.getSubTaskId(), e.getMessage(), e);
        }
        taskResultListener.onSubTaskOutcome(request, result, error);
        if (result != null && result.isSuccess()) {
            taskMetrics.recordTaskCompleted();
        } else {
            taskMetrics.recordTaskFailed();
        }
    }

    // ── 维护任务 ───────────────────────────────────────────────────────────

    /** XCLAIM 接管：把空闲超过阈值的 pending 消息（崩溃实例遗留）接管到本实例名下。降级模式跳过。 */
    @Scheduled(fixedDelayString = "${collector.queue.claim-sweep-ms:60000}")
    public void claimStaleMessages() {
        if (!facade.isRedisAvailable()) {
            return;
        }
        for (String tier : List.of(QueueTiers.HIGH, QueueTiers.LOW)) {
            try {
                List<RecordId> stale = new ArrayList<>();
                for (var message : taskStream.pending(tier, 200)) {
                    if (message.getElapsedTimeSinceLastDelivery().toMillis() >= claimMinIdleMs) {
                        stale.add(message.getId());
                    }
                }
                if (stale.isEmpty()) {
                    continue;
                }
                taskStream.claim(tier, consumerName, claimMinIdleMs, stale);
                log.info("XCLAIM 接管{}条遗留消息: stream={} consumer={}", stale.size(), tier, consumerName);
            } catch (Exception e) {
                log.warn("XCLAIM 接管扫描失败(非关键): stream={} error={}", tier, e.getMessage());
            }
        }
    }

    /**
     * deadline 保障：LOW 流中等待超过 max-wait-minutes 的任务迁移到 HIGH 流。
     * 原内存队列 deadline 保障的外置化（两流近似下不再做逐级 aging）。
     *
     * <p>迁移竞态：条目可能刚被消费者取走（已投递未确认，XRANGE 仍可见），
     * 此时迁移会产生一次重复投递 —— 由消费者的幂等守卫与终态条件更新兜底，代价可控。
     */
    @Scheduled(fixedDelayString = "${collector.queue.promotion-scan-ms:60000}")
    public void promoteDeadlineForced() {
        if (!facade.isRedisAvailable()) {
            return;
        }
        long maxWaitMinutes = systemConfigService.getLong("collector.task.max-wait-minutes", 30);
        long maxWaitMs = Math.max(1, maxWaitMinutes) * 60_000L;
        try {
            for (MapRecord<String, Object, Object> record : taskStream.readOldest(QueueTiers.LOW, 200)) {
                QueuedTask task;
                try {
                    task = taskStream.decode(record);
                } catch (Exception e) {
                    continue; // 毒消息留给消费者处理
                }
                if (task.isDeadlineForced()
                        || System.currentTimeMillis() - task.getEnqueueTimeMs() < maxWaitMs) {
                    continue;
                }
                task.setDeadlineForced(true);
                task.setTier(QueueTiers.HIGH);
                taskStream.enqueue(task);
                taskStream.delete(QueueTiers.LOW, record.getId());
                log.warn("Deadline保障触发: 任务等待超{}分钟, 迁移到HIGH流: subTaskId={}",
                        maxWaitMinutes, task.getSubTaskId());
            }
        } catch (Exception e) {
            log.warn("deadline 迁移扫描失败(非关键): {}", e.getMessage());
        }
    }

    // ── 降级恢复：本地兜底队列回流 ─────────────────────────────────────────

    /**
     * Redis 恢复后把本地兜底队列回流到流。
     *
     * <p>回流预算 = 远端剩余容量（避免挤占正常入队）；预算内逐条 poll → XADD。
     * 回流中被拒（容量又满/Redis 再故障）时任务**放回兜底队列尾部**等下一轮——
     * 极少发生且只影响回流顺序；即使兜底队列在降级期间崩溃丢失，
     * 子任务在 Mongo 是 RUNNING，启动恢复扫描会重新提交。
     */
    @Scheduled(fixedDelayString = "${collector.queue.fallback-drain-ms:5000}")
    public void drainLocalFallbackQueue() {
        try {
            if (facade.localFallbackSize() == 0 || !facade.isRedisAvailable()
                    || !taskStream.isGroupsReady()) {
                return;
            }
            int budget = facade.remoteRemainingCapacity();
            if (budget <= 0) {
                return;
            }
            int drained = 0;
            while (drained < budget) {
                QueuedTask task = facade.pollLocalTask();
                if (task == null) {
                    break;
                }
                try {
                    facade.enqueueRemote(task);
                    drained++;
                } catch (QueueFullException qfe) {
                    facade.offerLocalBack(task);
                    log.info("回流被容量拒绝，暂缓（剩余兜底{}条）: subTaskId={}",
                            facade.localFallbackSize(), task.getSubTaskId());
                    break;
                } catch (Exception e) {
                    facade.offerLocalBack(task);
                    redisHealth.markFailure("drainLocalFallback", e);
                    break;
                }
            }
            if (drained > 0) {
                log.info("本地兜底队列回流完成: drained={} remaining={}",
                        drained, facade.localFallbackSize());
            }
        } catch (Exception e) {
            log.warn("本地兜底队列回流异常(非关键): {}", e.getMessage());
        }
    }

    // ── 动态配置读取（system_config 覆盖 properties 默认，60s 缓存） ────────

    private FairQuotaPolicy currentPolicy() {
        return new FairQuotaPolicy(
                systemConfigService.getDouble("collector.task.fair-quota-ratio", 0.3),
                systemConfigService.getDouble("collector.task.fair-quota-threshold", 0.7),
                systemConfigService.getInt("collector.task.fair-quota-high-priority-bound", 500),
                systemConfigService.getInt("collector.task.fair-quota-batch-size", 20));
    }

    /** 轮次开始（或首次进入）时重新规划：统计两级流长度，计算本轮留给 LOW 的名额。 */
    private void replanRoundIfNeeded(FairRound round, FairQuotaPolicy policy) {
        if (round.picksRemaining > 0) {
            return;
        }
        long highLen = taskStream.lengthOf(QueueTiers.HIGH);
        long lowLen = taskStream.lengthOf(QueueTiers.LOW);
        round.picksRemaining = policy.batchSize();
        round.lowPriorityRemaining = policy.lowPrioritySlots((int) (highLen + lowLen), (int) highLen);
        if (round.lowPriorityRemaining > 0) {
            log.debug("公平配额轮次开始: high={} low={} 预留低优先级名额={}",
                    highLen, lowLen, round.lowPriorityRemaining);
        }
    }

    private static String defaultConsumerName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "consumer-" + Long.toHexString(System.currentTimeMillis());
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一次成功读取：来源流 + 消息。 */
    private record Picked(String tier, MapRecord<String, Object, Object> record) {
    }

    /** 每个消费者线程独立的公平配额轮次状态。 */
    private static final class FairRound {
        private int picksRemaining;
        private int lowPriorityRemaining;
    }
}
