package com.sysj.collector.facade;

import com.sysj.collector.core.pipeline.ProviderInvocationPipeline;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.router.DynamicProviderRouter;
import com.sysj.collector.core.scheduler.FairQuotaPolicy;
import com.sysj.collector.core.scheduler.Prioritized;
import com.sysj.collector.core.scheduler.PriorityTaskQueue;
import com.sysj.collector.domain.dao.CommentDao;
import com.sysj.collector.domain.document.CommentDataType;
import com.sysj.collector.domain.document.CommentDoc;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;
import com.sysj.collector.domain.document.UserTierConfig;
import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import com.sysj.collector.domain.service.ProviderConfigService;
import com.sysj.collector.exception.CollectorException;
import com.sysj.collector.exception.ProviderInvocationException;
import com.sysj.collector.exception.QueueFullException;
import com.sysj.collector.metrics.TaskMetrics;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommentCollectResult;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

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

    /** aging 检查间隔（秒）：等待每超过该时长提升一次优先级。 */
    @Value("${collector.task.aging-interval-seconds:60}")
    private int agingIntervalSeconds;

    /** 每次 aging 提升的优先级数值（有效优先级 = basePriority - boost，数值越小越优先）。 */
    @Value("${collector.task.aging-priority-boost:10}")
    private int agingPriorityBoost;

    /** 最长等待时长（分钟）：超过后直接提到最高优先级（deadline 保障）。 */
    @Value("${collector.task.max-wait-minutes:30}")
    private int maxWaitMinutes;

    /** 公平配额：每个轮次留给低优先级任务的比例。 */
    @Value("${collector.task.fair-quota-ratio:0.3}")
    private double fairQuotaRatio;

    /** 公平配额：高优先级任务占比达到该比例才启用配额。 */
    @Value("${collector.task.fair-quota-threshold:0.7}")
    private double fairQuotaThreshold;

    /** 公平配额：有效优先级小于该值算"高优先级"（替代原先硬编码的 500）。 */
    @Value("${collector.task.fair-quota-high-priority-bound:500}")
    private int fairQuotaHighPriorityBound;

    /** 公平配额：一个消费轮次的名额数。 */
    @Value("${collector.task.fair-quota-batch-size:20}")
    private int fairQuotaBatchSize;

    /** 异步队列容量上限；超过则新的 ASYNC 提交被拒绝（过载保护，修正 C-19 的死配置）。 */
    @Value("${collector.task.queue-capacity:10000}")
    private int queueCapacity;

    /** 消费者线程数；&lt;=0 表示按 CPU 自动（availableProcessors × 2）。 */
    @Value("${collector.task.consumer-threads:0}")
    private int consumerThreads;

    private final ProviderConfigService configService;
    private final DynamicProviderRouter router;
    private final ProviderInvocationPipeline pipeline;
    private final ApplicationContext applicationContext;
    private final TaskMetrics taskMetrics;
    private final CommentDao commentDao;

    /**
     * 异步任务队列：**出队时按当前有效优先级现算**，因此 aging / deadline 提升立即生效。
     * 不再使用 {@code PriorityBlockingQueue}（它只在插入时堆化，元素优先级变化后不会重排）。
     *
     * <p>实例在 {@link #init()} 中创建：容量来自 {@code @Value}，构造阶段还没注入。
     */
    private PriorityTaskQueue<PrioritizedTask> taskQueue;

    /** 公平配额策略（由配置构造，在 {@link #init()} 中初始化）。 */
    private FairQuotaPolicy fairQuotaPolicy;

    /** 每个消费者线程独立的公平配额轮次状态（避免跨线程互相干扰）。 */
    private final ThreadLocal<FairRound> fairRound = ThreadLocal.withInitial(FairRound::new);

    /** 异步任务消费线程池（在 {@link #init()} 中按配置创建）。 */
    private ExecutorService asyncExecutor;

    /** Aging 检查定时器 */
    private final ScheduledExecutorService agingScheduler;

    public CommentCollectionFacade(ProviderConfigService configService,
                                   DynamicProviderRouter router,
                                   ProviderInvocationPipeline pipeline,
                                   ApplicationContext applicationContext,
                                   TaskMetrics taskMetrics,
                                   CommentDao commentDao) {
        this.configService = configService;
        this.router = router;
        this.pipeline = pipeline;
        this.applicationContext = applicationContext;
        this.taskMetrics = taskMetrics;
        this.commentDao = commentDao;
        this.agingScheduler = Executors.newSingleThreadScheduledExecutor(r ->
                new Thread(r, "collector-aging-checker"));
    }

    /**
     * 容器完成依赖与配置注入后再创建队列与后台线程。
     *
     * <p>放在 {@code @PostConstruct} 而不是构造函数里：队列容量、消费者线程数与公平配额
     * 都来自 {@code @Value} 注入的配置项，构造阶段这些字段还没赋值。
     */
    @PostConstruct
    public void init() {
        this.fairQuotaPolicy = new FairQuotaPolicy(
                fairQuotaRatio, fairQuotaThreshold, fairQuotaHighPriorityBound, fairQuotaBatchSize);
        // 有界队列：满时 offer 返回 false，入口据此返回 503，而不是无界堆积到 OOM
        this.taskQueue = new PriorityTaskQueue<>(Math.max(0, queueCapacity));

        int threads = consumerThreads > 0
                ? consumerThreads
                : Runtime.getRuntime().availableProcessors() * 2;
        this.asyncExecutor = Executors.newFixedThreadPool(threads,
                r -> new Thread(r, "collector-async-" + System.nanoTime()));

        log.info("防饥饿调度参数: agingInterval={}s boost={} maxWait={}min | 公平配额 ratio={} threshold={} "
                        + "highPriorityBound={} batchSize={}",
                agingIntervalSeconds, agingPriorityBoost, maxWaitMinutes,
                fairQuotaRatio, fairQuotaThreshold, fairQuotaHighPriorityBound, fairQuotaBatchSize);
        log.info("过载保护与线程池: queueCapacity={} consumerThreads={}（{}）",
                taskQueue.capacity(), threads, consumerThreads > 0 ? "来自配置" : "按 CPU 自动");

        startAsyncConsumer(threads);
        startAgingChecker();
    }

    @PreDestroy
    public void shutdown() {
        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
        }
        agingScheduler.shutdown();
    }

    // ── 同步采集 ───────────────────────────────────────────────────────────

    public CommentCollectResult collectSync(CommentCollectRequest request) {
        return doCollect(request);
    }

    // ── 异步采集（带优先级队列 + 防饥饿 + 过载保护） ────────────────────────

    /**
     * 提交异步任务（**非原子**：内部先预留 1 个名额）。
     *
     * <p>并发突发下，多个请求可能同时通过入口检查；能否受理最终由这里的原子预留决定。
     * 需要"整批受理或整批拒绝"的调用方请先用 {@link #reserveCapacity(int)}。
     *
     * @throws QueueFullException 队列已满
     */
    public CompletableFuture<CommentCollectResult> collectAsync(CommentCollectRequest request) {
        if (!taskQueue.tryReserve(1)) {
            throw queueFull();
        }
        return collectAsyncReserved(request);
    }

    /**
     * 提交异步任务，**使用调用方已预留的名额**（不再尝试预留）。
     *
     * <p>配合 {@link #reserveCapacity(int)} 实现"整批受理或整批拒绝"：
     * 入口一次性预留本次请求的全部链接名额，之后逐个提交绝不会因容量失败。
     */
    public CompletableFuture<CommentCollectResult> collectAsyncReserved(CommentCollectRequest request) {
        int userPriority = resolveUserPriority(request.getUserTierCode());
        CompletableFuture<CommentCollectResult> future = new CompletableFuture<>();
        PrioritizedTask task = new PrioritizedTask(
                userPriority, taskQueue.nextSeq(), System.currentTimeMillis(), request, future);

        taskQueue.offerReserved(task);

        taskMetrics.recordTaskSubmitted();
        taskMetrics.setQueueSize(taskQueue.size());
        log.debug("任务入队: userId={} tier={} priority={} queueSize={}/{}",
                request.getUserId(), request.getUserTierCode(), userPriority,
                taskQueue.size(), taskQueue.capacity());
        return future;
    }

    /**
     * 入口 fail-fast 过载检查：**原子预留**本次请求所需的全部名额。
     *
     * <p>放在创建任何任务记录之前调用，因此拒绝时不会留下"主任务已建、子任务拆了一半"的脏数据。
     *
     * <p>为什么必须"原子预留"而不是"查一下剩余容量"：并发突发时所有请求几乎同时到达，
     * 各自查容量都会看到还有余量 → 全部放行 → 过载保护失效（实测 10 并发全部 200 就是这个原因）。
     *
     * <p>为什么要求"整批都放得下"：否则一个 10 链接的请求会出现部分受理，
     * 调用方既拿不到 503 也无法整批重试。宁可整批拒绝。
     *
     * <p>未被用掉的名额必须通过 {@link #releaseCapacity(int)} 归还。
     *
     * @param needed 本次请求需要的队列名额（等于链接数）
     * @throws QueueFullException 余量不足
     */
    public void reserveCapacity(int needed) {
        if (!taskQueue.tryReserve(Math.max(1, needed))) {
            throw queueFull();
        }
    }

    /** 构造队列满异常（统一带上 size / capacity / remaining 三个数字）。 */
    private QueueFullException queueFull() {
        return new QueueFullException(taskQueue.size(), taskQueue.capacity(), taskQueue.remainingCapacity());
    }

    /** 归还未使用的预留名额（配合 {@link #reserveCapacity(int)} 的 try/finally 使用）。 */
    public void releaseCapacity(int unused) {
        taskQueue.release(unused);
    }

    /** 单名额的过载检查（等价于 {@code reserveCapacity(1)}，用于只提交一个任务的入口）。 */
    public void ensureCapacity() {
        reserveCapacity(1);
    }

    public int getQueueCapacity() {
        return taskQueue.capacity();
    }

    public int getRemainingCapacity() {
        return taskQueue.remainingCapacity();
    }

    private int resolveUserPriority(String tierCode) {
        if (tierCode == null) return 999;
        return configService.loadUserTierConfig(tierCode)
                .map(UserTierConfig::getPriority)
                .orElse(999);
    }

    // ── 异步消费循环（带公平配额） ─────────────────────────────────────────

    private void startAsyncConsumer(int threads) {
        for (int i = 0; i < threads; i++) {
            asyncExecutor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    PrioritizedTask task = null;
                    try {
                        task = consumeWithFairQuota();
                        if (task != null) {
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
     * <p>规则见 {@link FairQuotaPolicy}：
     * 高优先级占比达阈值时，每个消费轮次预留一部分名额给低优先级任务；
     * 若此刻队列里没有低优先级任务，立即放弃本轮剩余预留，改为正常取任务，避免把高优先级饿死。
     *
     * <p>轮次状态放在 {@link ThreadLocal} 中，**每个消费者线程独立计数** ——
     * 原先用共享的 AtomicBoolean/AtomicInteger 会让多个线程互相干扰。
     */
    private PrioritizedTask consumeWithFairQuota() throws InterruptedException {
        FairRound round = fairRound.get();

        // 轮次结束（或首次进入）时重新规划本轮
        if (round.picksRemaining <= 0) {
            List<PrioritizedTask> snapshot = taskQueue.snapshot();
            int total = snapshot.size();
            int highCount = 0;
            for (PrioritizedTask t : snapshot) {
                if (fairQuotaPolicy.isHighPriority(t.effectivePriority())) {
                    highCount++;
                }
            }
            round.picksRemaining = fairQuotaPolicy.batchSize();
            round.lowPriorityRemaining = fairQuotaPolicy.lowPrioritySlots(total, highCount);
            if (round.lowPriorityRemaining > 0) {
                log.debug("公平配额轮次开始: total={} high={} 预留低优先级名额={}",
                        total, highCount, round.lowPriorityRemaining);
            }
        }

        // 本轮仍有预留名额 → 先捞低优先级任务
        if (round.lowPriorityRemaining > 0) {
            PrioritizedTask low = taskQueue.pollIf(
                    t -> !fairQuotaPolicy.isHighPriority(t.effectivePriority()));
            if (low != null) {
                round.lowPriorityRemaining--;
                round.picksRemaining--;
                return low;
            }
            // 队列里已无低优先级任务：放弃本轮剩余预留，接下来正常取
            round.lowPriorityRemaining = 0;
        }

        // 正常模式：取当前最优先任务（阻塞）
        round.picksRemaining--;
        return taskQueue.take();
    }

    // ── Aging 机制 ─────────────────────────────────────────────────────────

    /**
     * Aging 检查：周期性重算等待中任务的有效优先级。
     *
     * <p>因为 {@link PriorityTaskQueue} 在**出队时**才计算优先级，这里只需更新任务上的
     * agingBoost / deadline 标记，下一次出队就会按新优先级排序 —— 不再需要"重新入队"。
     */
    private void startAgingChecker() {
        agingScheduler.scheduleAtFixedRate(() -> {
                    try {
                        long now = System.currentTimeMillis();
                        for (PrioritizedTask task : taskQueue.snapshot()) {
                            long elapsedSeconds = (now - task.enqueueTime()) / 1000;

                            // Deadline 保障：等待超过 maxWaitMinutes 的任务直接提到最高优先级
                            if (elapsedSeconds > maxWaitMinutes * 60L) {
                                if (!task.isDeadlineForced()) {
                                    task.forceHighestPriority();
                                    log.warn("Deadline保障触发: 任务等待{}秒, 提升到最高优先级", elapsedSeconds);
                                }
                                continue;
                            }

                            // Aging：每 agingIntervalSeconds 提升一次（阶梯递增）
                            if (agingIntervalSeconds <= 0) {
                                continue;
                            }
                            long boosts = elapsedSeconds / agingIntervalSeconds;
                            int expectedBoost = (int) Math.min(Integer.MAX_VALUE, boosts * (long) agingPriorityBoost);
                            if (expectedBoost > task.agingBoost()) {
                                task.setAgingBoost(expectedBoost);
                                log.debug("Aging提升: 任务等待{}秒, 提升{}点, 有效优先级={}",
                                        elapsedSeconds, expectedBoost, task.effectivePriority());
                            }
                        }
                    } catch (Exception e) {
                        log.error("Aging检查异常", e);
                    }
                }, 30, Math.max(5, agingIntervalSeconds / 3), TimeUnit.SECONDS);
    }

    // ── 核心采集逻辑（带供应商切换） ──────────────────────────────────────

    private CommentCollectResult doCollect(CommentCollectRequest request) {
        String platform = request.getPlatformCode();
        String feature = request.getFeatureCode();

        // 1. 从缓存/DB 加载功能配置（含供应商列表）
        PlatformFeatureConfig featureConfig = configService.loadFeatureConfig(platform, feature);
        List<ProviderConfig> allProviders = featureConfig.getProviders();
        if (allProviders == null || allProviders.isEmpty()) {
            return CommentCollectResult.failed("该功能未配置供应商");
        }

        // 2. 强制指定供应商（特殊需求，走路由的 requiredProviderKey 分支，跳过偏好/健康/阈值）
        if (request.getSpecifiedProviderKey() != null) {
            List<ProviderConfig> specified = router.select(DynamicProviderRouter.RouteContext.specified(
                    platform, feature, allProviders, request.getSpecifiedProviderKey()));
            return executeProviderRetry(request, specified.get(0));
        }

        // 3. 加载用户等级供应商偏好
        FeatureProviderConfig featurePreference = resolveFeaturePreference(
                request.getUserTierCode(), platform, feature);

        router.incrementPending(platform, feature);
        try {
            // 4. 统一路由入口：候选构建 → 健康/熔断过滤 → 能力过滤 → 激活阈值
            DynamicProviderRouter.RouteResult routeResult = router.selectDetailed(
                    DynamicProviderRouter.RouteContext.of(platform, feature, allProviders,
                            featurePreference, Capability.parse(request.getRequiredCapabilities())));

            List<ProviderConfig> candidates = routeResult.candidates();
            if (candidates.isEmpty()) {
                // 用路由给出的可诊断原因：区分"能力不满足"与"全部不可用"
                return CommentCollectResult.failed(routeResult.reason());
            }

            // 5. 遍历候选供应商并执行，失败时自动切换
            //    限流、熔断闸门、Bulkhead、重试、单次调用限时、结果上报全部在调用管线内完成（§9.2），
            //    门面只负责"选谁 + 失败后换谁 + 落库"。
            Set<String> excludedKeys = new HashSet<>();
            Map<String, String> providerErrors = new LinkedHashMap<>();
            for (ProviderConfig providerConfig : candidates) {
                String key = providerConfig.getProviderKey();
                if (excludedKeys.contains(key)) continue;

                CommentCollectResult result = executeProviderWithSwitch(
                        request, providerConfig, excludedKeys, providerErrors);

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
     * <p>调用本身交给 {@link ProviderInvocationPipeline}：Bulkhead → 熔断闸门 → 限流 →
     * 重试 → 单次限时，并在管线内统一上报结果。本方法只做"取 Bean / 组装结果 / 记录失败原因"。
     *
     * @param providerErrors 失败原因收集器（key → 原因），用于最终失败信息里带出真实原因
     */
    private CommentCollectResult executeProviderWithSwitch(
            CommentCollectRequest request,
            ProviderConfig providerConfig,
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
            CommonEntity<Comment> result = pipeline.invoke(request, providerConfig, provider);
            return toResult(request, key, result);

        } catch (Exception e) {
            log.error("供应商执行失败，切换: provider={} error={}", key, e.getMessage());

            providerErrors.put(key, e.getMessage());

            // 排除该供应商，由外层循环切换到下一个候选
            excludedKeys.add(key);

            // 返回 null 触发外层循环切换到下一个供应商
            return null;
        }
    }

    /**
     * 对单个供应商执行（不切换）—— 指定供应商路径。
     *
     * <p>与切换路径共用同一条调用管线，区别是失败后**不再换人**。
     *
     * <p><b>失败语义（P2-8 的有意变更）</b>：调用方点名要某个供应商时，该供应商不可用属于
     * **服务端依赖问题**，因此 {@link ProviderInvocationException}（熔断/限流/Bulkhead/超时/上游失败）
     * 直接向上抛，由 {@code GlobalExceptionHandler} 映射成 **503 + code=PROVIDER_UNAVAILABLE**，
     * 而不是像"候选全灭"那样回 200 + {@code success=false}。
     * 理由：调用方既然点名了供应商，就该知道"是它不行、可以稍后重试"，
     * 而不是拿到一个无法区分的失败体去猜是参数错了还是下游挂了。
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
            CommonEntity<Comment> result = pipeline.invoke(request, providerConfig, provider);
            return toResult(request, key, result);
        } catch (ProviderInvocationException e) {
            throw e;   // → 503 PROVIDER_UNAVAILABLE
        } catch (Exception e) {
            throw new ProviderInvocationException(key, "供应商 [" + key + "] 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 结果后处理：落库 → 组装返回。
     *
     * <p><b>失败判定口径（重要）</b>：供应商实现按约定有两种失败表达方式 ——
     * ① 抛异常；② 返回 {@code status != STATUS_SUCCESS}。
     * 该判定已上移到 {@link ProviderInvocationPipeline}（它必须在重试/熔断/统计之内，
     * 而这里同时承担落库职责，不适合做判定），因此本方法收到的 entity 必定是成功的。
     */
    private CommentCollectResult toResult(CommentCollectRequest request, String providerKey,
                                          CommonEntity<Comment> entity) {
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

    /**
     * 公平配额的轮次状态（每个消费者线程一份）。
     */
    private static final class FairRound {
        /** 本轮剩余取任务名额。 */
        private int picksRemaining;
        /** 本轮剩余"留给低优先级"的名额。 */
        private int lowPriorityRemaining;
    }

    // ── 优先级任务包装（带 Aging 支持） ────────────────────────────────────

    /**
     * 队列中的任务。
     *
     * <p>{@link #effectivePriority()} 是**现算**的（basePriority - agingBoost，被 deadline 强制时直接取 0），
     * 而不是缓存字段 —— 这样 aging 一改 {@code agingBoost}，下一次出队立刻按新优先级排序。
     */
    private static final class PrioritizedTask implements Prioritized {

        /** deadline 强制提权时的优先级（最高）。 */
        private static final int FORCED_PRIORITY = 0;

        private final int basePriority;
        private final long seq;
        private final long enqueueTime;
        private final CommentCollectRequest request;
        private final CompletableFuture<CommentCollectResult> future;

        private volatile int agingBoost = 0;
        private volatile boolean deadlineForced = false;

        PrioritizedTask(int basePriority, long seq, long enqueueTime,
                        CommentCollectRequest request,
                        CompletableFuture<CommentCollectResult> future) {
            this.basePriority = basePriority;
            this.seq = seq;
            this.enqueueTime = enqueueTime;
            this.request = request;
            this.future = future;
        }

        @Override
        public int effectivePriority() {
            if (deadlineForced) {
                return FORCED_PRIORITY;
            }
            return Math.max(0, basePriority - agingBoost);
        }

        @Override
        public long enqueueSeq() {
            return seq;
        }

        CommentCollectRequest request() {
            return request;
        }

        CompletableFuture<CommentCollectResult> future() {
            return future;
        }

        long enqueueTime() {
            return enqueueTime;
        }

        int agingBoost() {
            return agingBoost;
        }

        void setAgingBoost(int boost) {
            this.agingBoost = boost;
        }

        boolean isDeadlineForced() {
            return deadlineForced;
        }

        void forceHighestPriority() {
            this.deadlineForced = true;
        }

        int basePriority() {
            return basePriority;
        }
    }
}
