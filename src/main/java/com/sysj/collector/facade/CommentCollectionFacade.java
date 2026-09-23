package com.sysj.collector.facade;

import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.router.DynamicProviderRouter;
import com.sysj.collector.core.router.RetryableProviderExecutor;
import com.sysj.collector.domain.dao.CommentDao;
import com.sysj.collector.domain.document.CommentDataType;
import com.sysj.collector.domain.document.CommentDoc;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;
import com.sysj.collector.domain.document.UserTierConfig;
import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import com.sysj.collector.domain.service.ProviderConfigService;
import com.sysj.collector.exception.CollectorException;
import com.sysj.collector.metrics.TaskMetrics;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommentCollectResult;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 评论采集门面服务。
 *
 * <h3>供应商切换</h3>
 * 当当前供应商重试达 maxRetry 后自动切换到下一候选供应商。
 * 切换时排除已失败的供应商 key。
 *
 * <h3>防饥饿调度</h3>
 * <ul>
 *   <li><b>Aging机制</b>：任务在队列中每等待 agingIntervalSeconds 秒提升一次优先级</li>
 *   <li><b>Deadline保障</b>：超过 maxWaitMinutes 未执行的任务自动提到最高优先级</li>
 *   <li><b>公平配额</b>：高优先级任务占比超过 fairQuotaThreshold 时，
 *       每个消费轮次至少分配 fairQuotaRatio 的处理能力给低优先级任务</li>
 * </ul>
 */
@Slf4j
@Service
public class CommentCollectionFacade {

    @Value("${collector.task.aging-interval-seconds:60}")
    private int agingIntervalSeconds;

    @Value("${collector.task.aging-priority-boost:10}")
    private int agingPriorityBoost;

    @Value("${collector.task.max-wait-minutes:30}")
    private int maxWaitMinutes;

    @Value("${collector.task.fair-quota-ratio:0.3}")
    private double fairQuotaRatio;

    @Value("${collector.task.fair-quota-threshold:0.7}")
    private double fairQuotaThreshold;

    private final ProviderConfigService configService;
    private final DynamicProviderRouter router;
    private final ApplicationContext applicationContext;
    private final TaskMetrics taskMetrics;
    private final CommentDao commentDao;

    /** 按有效优先级排序的异步任务队列 */
    private final PriorityBlockingQueue<PrioritizedTask> taskQueue =
            new PriorityBlockingQueue<>(1024);

    /** 记录每个任务的入队时间，用于 aging 计算 */
    private final ConcurrentHashMap<PrioritizedTask, Long> enqueueTimeMap =
            new ConcurrentHashMap<>();

    /** 异步任务消费线程池 */
    private final ExecutorService asyncExecutor;

    /** Aging 检查定时器 */
    private final ScheduledExecutorService agingScheduler;

    /** 公平配额计数器（当前消费轮次中已处理的低优先级任务数） */
    private final AtomicInteger fairRoundProcessed = new AtomicInteger(0);
    private final AtomicBoolean fairRoundActive = new AtomicBoolean(false);

    public CommentCollectionFacade(ProviderConfigService configService,
                                   DynamicProviderRouter router,
                                   ApplicationContext applicationContext,
                                   TaskMetrics taskMetrics,
                                   CommentDao commentDao) {
        this.configService = configService;
        this.router = router;
        this.applicationContext = applicationContext;
        this.taskMetrics = taskMetrics;
        this.commentDao = commentDao;
        this.asyncExecutor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2,
                r -> new Thread(r, "collector-async-" + System.nanoTime()));
        this.agingScheduler = Executors.newSingleThreadScheduledExecutor(r ->
                new Thread(r, "collector-aging-checker"));
        // 启动异步消费循环
        startAsyncConsumer();
        // 启动 aging 检查定时器
        startAgingChecker();
    }

    @PreDestroy
    public void shutdown() {
        asyncExecutor.shutdown();
        agingScheduler.shutdown();
    }

    // ── 同步采集 ───────────────────────────────────────────────────────────

    public CommentCollectResult collectSync(CommentCollectRequest request) {
        return doCollect(request);
    }

    // ── 异步采集（带优先级队列 + 防饥饿） ──────────────────────────────────

    public CompletableFuture<CommentCollectResult> collectAsync(CommentCollectRequest request) {
        int userPriority = resolveUserPriority(request.getUserTierCode());
        CompletableFuture<CommentCollectResult> future = new CompletableFuture<>();
        PrioritizedTask task = new PrioritizedTask(userPriority, request, future);
        taskQueue.offer(task);
        enqueueTimeMap.put(task, System.currentTimeMillis());
        taskMetrics.recordTaskSubmitted();
        log.debug("任务入队: userId={} tier={} priority={} queueSize={}",
                request.getUserId(), request.getUserTierCode(), userPriority, taskQueue.size());
        return future;
    }

    private int resolveUserPriority(String tierCode) {
        if (tierCode == null) return 999;
        return configService.loadUserTierConfig(tierCode)
                .map(UserTierConfig::getPriority)
                .orElse(999);
    }

    // ── 异步消费循环（带公平配额） ─────────────────────────────────────────

    private void startAsyncConsumer() {
        int threads = Runtime.getRuntime().availableProcessors() * 2;
        for (int i = 0; i < threads; i++) {
            asyncExecutor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    PrioritizedTask task = null;
                    try {
                        task = consumeWithFairQuota();
                        if (task != null) {
                            enqueueTimeMap.remove(task);
                            taskMetrics.setQueueSize(taskQueue.size());
                            CommentCollectResult result = doCollect(task.request());
                            task.future().complete(result);
                            if (result.isSuccess()) {
                                taskMetrics.recordTaskCompleted();
                            } else {
                                taskMetrics.recordTaskFailed();
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        // 必须让 future 异常完成，否则调用方（含 TaskRecoveryService / 任务完成回调）会永久阻塞
                        log.error("异步任务执行异常", e);
                        if (task != null) {
                            taskMetrics.recordTaskFailed();
                            task.future().completeExceptionally(e);
                        }
                    }
                }
            });
        }
    }

    /**
     * 公平配额消费：从队列中取出一个任务。
     *
     * <p>当高优先级（priority < 500）任务占比超过阈值时触发公平配额模式，
     * 每个消费轮次至少分配 fairQuotaRatio 的处理能力给低优先级任务。
     */
    private PrioritizedTask consumeWithFairQuota() throws InterruptedException {
        // 检查是否需要进入公平配额模式
        long highCount = taskQueue.stream().filter(t -> t.priority() < 500).count();
        long total = taskQueue.size();
        double ratio = total > 0 ? (double) highCount / total : 0;

        if (ratio >= fairQuotaThreshold && total > 5 && fairRoundActive.compareAndSet(false, true)) {
            // 进入公平配额轮次
            fairRoundActive.set(true);
            fairRoundProcessed.set(0);
        }

        if (fairRoundActive.get()) {
            int batchSize = (int) (total > 0 ? Math.max(1, total * fairQuotaRatio) : 1);
            int processed = fairRoundProcessed.get();

            if (processed < batchSize) {
                // 本批次中优先处理低优先级任务
                PrioritizedTask lowTask = pollLowPriority();
                if (lowTask != null) {
                    fairRoundProcessed.incrementAndGet();
                    return lowTask;
                }
            }

            // 低优先级任务已处理完或达到配额，退出公平模式
            fairRoundActive.set(false);
        }

        // 正常模式：取最高优先级任务（阻塞）
        return taskQueue.take();
    }

    /**
     * 从队列中取出一个低优先级（priority >= 500）任务，非阻塞。
     */
    private PrioritizedTask pollLowPriority() {
        // 遍历队列找一个低优先级任务
        for (PrioritizedTask t : taskQueue) {
            if (t.priority() >= 500) {
                if (taskQueue.remove(t)) {
                    return t;
                }
            }
        }
        return null;
    }

    // ── Aging 机制 ─────────────────────────────────────────────────────────

    private void startAgingChecker() {
        agingScheduler.scheduleAtFixedRate(() -> {
                    try {
                        long now = System.currentTimeMillis();
                        for (Map.Entry<PrioritizedTask, Long> entry : enqueueTimeMap.entrySet()) {
                            PrioritizedTask task = entry.getKey();
                            long elapsed = now - entry.getValue();
                            long elapsedSeconds = elapsed / 1000;

                            // Deadline 保障：超过 maxWaitMinutes 未执行的任务提升到最高优先级
                            if (elapsedSeconds > maxWaitMinutes * 60L) {
                                task.setEffectivePriority(0);
                                log.warn("Deadline保障触发: 任务等待{}秒, 提升到最高优先级", elapsedSeconds);
                                continue;
                            }

                            // Aging：每 agingIntervalSeconds 提升一次
                            long boosts = elapsedSeconds / agingIntervalSeconds;
                            int expectedBoost = (int) (boosts * agingPriorityBoost);
                            if (expectedBoost > task.getAgingBoost()) {
                                task.setAgingBoost(expectedBoost);
                                log.debug("Aging提升: 任务等待{}秒, 提升{}点", elapsedSeconds, expectedBoost);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Aging检查异常", e);
                    }
                }, 30, 30, TimeUnit.SECONDS);
    }

    // ── 核心采集逻辑（带供应商切换） ──────────────────────────────────────

    private CommentCollectResult doCollect(CommentCollectRequest request) {
        String platform = request.getPlatformCode();
        String feature = request.getFeatureCode();

        // 1. 从缓存/DB 加载功能配置（含供应商列表）
        PlatformFeatureConfig featureConfig = configService.loadFeatureConfig(platform, feature);

        // 2. 强制指定供应商（特殊需求，跳过路由）
        if (request.getSpecifiedProviderKey() != null) {
            return executeSpecified(request, featureConfig);
        }

        // 3. 加载用户等级供应商偏好
        FeatureProviderConfig featurePreference = resolveFeaturePreference(
                request.getUserTierCode(), platform, feature);

        router.incrementPending(platform, feature);
        try {
            // 4. 获取候选供应商列表
            List<ProviderConfig> allProviders = featureConfig.getProviders();
            if (allProviders == null || allProviders.isEmpty()) {
                return CommentCollectResult.failed("该功能未配置供应商");
            }
            List<ProviderConfig> candidates = router.buildOrderedCandidates(
                    allProviders, featurePreference);

            // 5. 排除不可用的供应商
            List<ProviderConfig> available = candidates.stream()
                    .filter(ProviderConfig::isHealthy)
                    .toList();

            if (available.isEmpty()) {
                return CommentCollectResult.failed("当前无可用供应商，请稍后重试");
            }

            // 6. 遍历候选供应商并执行，失败时自动切换
            Set<String> excludedKeys = new HashSet<>();
            Map<String, String> providerErrors = new LinkedHashMap<>();
            for (ProviderConfig providerConfig : available) {
                String key = providerConfig.getProviderKey();
                if (excludedKeys.contains(key)) continue;

                // 限流检查
                boolean acquired = router.tryAcquireProvider(platform, feature,
                        key, providerConfig.getRatePerSecond());
                if (!acquired) {
                    log.warn("供应商限流跳过: key={}", key);
                    providerErrors.put(key, "限流未获取到令牌");
                    continue;
                }

                // 执行（含重试）
                CommentCollectResult result = executeProviderWithSwitch(
                        request, providerConfig, available, excludedKeys, providerErrors);

                if (result != null && result.isSuccess()) {
                    return result;
                }

                excludedKeys.add(key);
            }

            return CommentCollectResult.failed("所有供应商均不可用: " + providerErrors);

        } finally {
            router.decrementPending(platform, feature);
        }
    }

    /**
     * 执行单个供应商，失败时记录排除。
     * 返回 null 表示需要切换供应商，返回非 null 为最终结果。
     *
     * @param providerErrors 失败原因收集器（key → 原因），用于最终失败信息里带出真实原因
     */
    private CommentCollectResult executeProviderWithSwitch(
            CommentCollectRequest request,
            ProviderConfig providerConfig,
            List<ProviderConfig> allAvailable,
            Set<String> excludedKeys,
            Map<String, String> providerErrors) {

        String key = providerConfig.getProviderKey();
        CommentProvider provider;
        try {
            provider = applicationContext.getBean(key, CommentProvider.class);
        } catch (Exception e) {
            log.error("供应商 Bean 未找到: providerKey={}", key);
            providerErrors.put(key, "Bean 未注册");
            excludedKeys.add(key);
            return null; // 切换供应商
        }

        try {
            CommonEntity<Comment> result =
                    RetryableProviderExecutor.execute(provider, request, providerConfig.getMaxRetry());

            return toResult(request, key, result);

        } catch (Exception e) {
            log.error("供应商执行失败，切换: provider={} error={}", key, e.getMessage());

            providerErrors.put(key, e.getMessage());

            // 标记该供应商失败，路由器后续将跳过
            excludedKeys.add(key);

            // 通知路由器该供应商不可用（连续失败标记）
            router.markProviderFailure(platform(request), feature(request), key);

            // 返回 null 触发外层循环切换到下一个供应商
            return null;
        }
    }

    private String platform(CommentCollectRequest r) { return r.getPlatformCode(); }
    private String feature(CommentCollectRequest r) { return r.getFeatureCode(); }

    /**
     * 强制指定供应商执行。
     */
    private CommentCollectResult executeSpecified(
            CommentCollectRequest request, PlatformFeatureConfig featureConfig) {

        String key = request.getSpecifiedProviderKey();
        ProviderConfig providerConfig = featureConfig.getProviders().stream()
                .filter(p -> p.getProviderKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new CollectorException("指定供应商不在配置列表中: " + key));

        return executeProviderRetry(request, providerConfig);
    }

    /**
     * 对单个供应商执行重试逻辑（不切换）。
     */
    private CommentCollectResult executeProviderRetry(
            CommentCollectRequest request, ProviderConfig providerConfig) {

        String key = providerConfig.getProviderKey();
        CommentProvider provider;
        try {
            provider = applicationContext.getBean(key, CommentProvider.class);
        } catch (Exception e) {
            return CommentCollectResult.failed("供应商 Bean 未注册: " + key);
        }

        try {
            CommonEntity<Comment> result =
                    RetryableProviderExecutor.execute(provider, request, providerConfig.getMaxRetry());

            return toResult(request, key, result);

        } catch (Exception e) {
            return CommentCollectResult.failed("供应商 [" + key + "] 执行失败: " + e.getMessage());
        }
    }

    /**
     * 统一的结果处理：判定成败 → 落库 → 组装返回。
     *
     * <p><b>失败判定口径（重要）</b>：供应商实现按约定有两种失败表达方式 ——
     * ① 抛异常；② 返回 {@code status != STATUS_SUCCESS}。二者都必须被识别为失败，
     * 否则会出现"不重试、不切换、还返回 success=true + 空列表"的错误行为。
     * 这里统一抛出 {@link CollectorException}，由调用方按失败处理（触发重试或供应商切换）。
     */
    private CommentCollectResult toResult(CommentCollectRequest request, String providerKey,
                                          CommonEntity<Comment> entity) {
        if (entity == null) {
            throw new CollectorException("供应商 [" + providerKey + "] 返回 null");
        }
        if (entity.getStatus() != CommonStatusEnum.STATUS_SUCCESS) {
            throw new CollectorException("供应商 [" + providerKey + "] 返回失败状态: " + entity.getStatus()
                    + (entity.getMsg() == null ? "" : " / " + entity.getMsg()));
        }
        List<Comment> comments = entity.getDataList() == null ? Collections.emptyList() : entity.getDataList();
        persist(request, providerKey, comments);
        return CommentCollectResult.builder()
                .success(true)
                .providerUsed(providerKey)
                .comments(comments)
                .hasMore(Boolean.TRUE.equals(entity.getHaseMore()))
                .nextCursor(entity.getNextUrl())
                .build();
    }

    /**
     * 落库采集结果。
     *
     * <p>仅在 {@code request.taskId} 非空时落库 —— 目的是为"不能即时返回"以及
     * "需要持续翻页"的任务提供数据返回支持（{@code GET /api/tasks/{taskId}/comments}）。
     * 纯同步即时返回的场景不必落库。
     *
     * <p>落库失败只记日志，不影响本次结果返回。
     *
     * @return 实际写入条数
     */
    private int persist(CommentCollectRequest request, String providerKey, List<Comment> comments) {
        String taskId = request.getTaskId();
        if (taskId == null || taskId.isBlank() || comments.isEmpty()) {
            return 0;
        }
        String dataType = CommentDataType.of(request.getPlatformCode(), request.getFeatureCode());
        String fromUrl = request.getFromUrl();
        List<CommentDoc> docs = new ArrayList<>(comments.size());
        for (Comment c : comments) {
            docs.add(CommentDoc.from(dataType, request.getPlatformCode(), providerKey,
                    taskId, request.getSubTaskId(), fromUrl, c));
        }
        try {
            int saved = commentDao.saveAll(docs);
            log.info("采集结果已落库: taskId={} subTaskId={} dataType={} provider={} saved={}/{}",
                    taskId, request.getSubTaskId(), dataType, providerKey, saved, comments.size());
            return saved;
        } catch (Exception e) {
            log.error("采集结果落库失败（不影响本次返回）: taskId={} error={}", taskId, e.getMessage(), e);
            return 0;
        }
    }

    private FeatureProviderConfig resolveFeaturePreference(
            String tierCode, String platformCode, String featureCode) {

        if (tierCode == null) return null;
        return configService.loadUserTierConfig(tierCode)
                .flatMap(tierConfig -> {
                    if (tierConfig.getFeatureConfigs() == null) return Optional.empty();
                    return tierConfig.getFeatureConfigs().stream()
                            .filter(fc -> platformCode.equals(fc.getPlatformCode())
                                    && featureCode.equals(fc.getFeatureCode()))
                            .findFirst();
                })
                .orElse(null);
    }

    public int getQueueSize() {
        return taskQueue.size();
    }

    // ── 优先级任务包装（带 Aging 支持） ────────────────────────────────────

    private static class PrioritizedTask implements Comparable<PrioritizedTask> {
        private final int basePriority;
        private final CommentCollectRequest request;
        private final CompletableFuture<CommentCollectResult> future;
        private volatile int agingBoost = 0;
        private volatile int effectivePriority;

        PrioritizedTask(int basePriority, CommentCollectRequest request,
                        CompletableFuture<CommentCollectResult> future) {
            this.basePriority = basePriority;
            this.request = request;
            this.future = future;
            this.effectivePriority = basePriority;
        }

        int priority() { return effectivePriority; }
        CommentCollectRequest request() { return request; }
        CompletableFuture<CommentCollectResult> future() { return future; }

        int getAgingBoost() { return agingBoost; }
        void setAgingBoost(int boost) {
            this.agingBoost = boost;
            this.effectivePriority = Math.max(0, basePriority - boost);
        }
        void setEffectivePriority(int p) { this.effectivePriority = p; }
        int getBasePriority() { return basePriority; }

        @Override
        public int compareTo(PrioritizedTask o) {
            return Integer.compare(this.effectivePriority, o.effectivePriority);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PrioritizedTask that)) return false;
            return future == that.future;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(future);
        }
    }
}
