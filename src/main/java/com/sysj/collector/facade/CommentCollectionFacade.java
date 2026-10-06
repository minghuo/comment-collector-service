package com.sysj.collector.facade;

import com.sysj.collector.core.pipeline.ProviderInvocationPipeline;
import com.sysj.collector.core.scheduler.PriorityTaskQueue;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.router.DynamicProviderRouter;
import com.sysj.collector.domain.dao.CommentDao;
import com.sysj.collector.domain.document.CommentDataType;
import com.sysj.collector.domain.document.CommentDoc;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;
import com.sysj.collector.domain.document.UserTierConfig;
import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import com.sysj.collector.domain.service.ProviderConfigService;
import com.sysj.collector.domain.service.SystemConfigService;
import com.sysj.collector.exception.ProviderInvocationException;
import com.sysj.collector.exception.QueueFullException;
import com.sysj.collector.metrics.TaskMetrics;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommentCollectResult;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;
import com.sysj.collector.model.QueuedTask;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 评论采集门面服务。
 *
 * <h3>供应商切换</h3>
 * 当当前供应商重试达 maxRetry 后自动切换到下一候选供应商。
 * 切换时排除已失败的供应商 key。
 *
 * <h3>外置队列（阶段2：Redis Stream）</h3>
 * 异步任务不再进 JVM 内存队列，而是序列化后写入 Redis Stream（{@link RedisTaskStream}）：
 * <ul>
 *   <li>提交与执行彻底分离——进程重启后未完成任务仍留在流里，由消费者组继续消费，
 *       配合 XCLAIM 接管实现"重启任务稳定运行"；</li>
 *   <li>优先级用 HIGH/LOW 两条流近似（{@link QueueTiers}），跨流顺序由
 *       {@link AsyncTaskConsumer} 按公平配额选择；</li>
 *   <li>容量在 XADD 的 Lua 脚本内原子检查，严格不超卖（{@code collector.task.queue-capacity}）；
 *       {@link #reserveCapacity(int)} 是入口整批预检的快照；</li>
 *   <li>投递是**至少一次**：处理完成才 XACK+XDEL，崩溃未确认的消息会被接管重投，
 *       因此子任务终态写入与主任务计数必须幂等（见 TaskManagementService）。</li>
 * </ul>
 */
@Slf4j
@Service
public class CommentCollectionFacade {

    /** 异步队列容量上限；超过则新的异步提交被拒绝（过载保护）。 */
    @Value("${collector.task.queue-capacity:10000}")
    private int queueCapacity;

    /** 高优先级层级判定阈值（有效优先级小于该值进 HIGH 流）。 */
    @Value("${collector.task.fair-quota-high-priority-bound:500}")
    private int highPriorityBoundDefault;

    private final ProviderConfigService configService;
    private final DynamicProviderRouter router;
    private final ProviderInvocationPipeline pipeline;
    private final ApplicationContext applicationContext;
    private final TaskMetrics taskMetrics;
    private final CommentDao commentDao;
    private final RedisTaskStream taskStream;
    private final SystemConfigService systemConfigService;
    private final RedisHealthMonitor redisHealth;

    /**
     * 本地兜底队列（阶段2.5）：Redis 不可用时任务先落这里，恢复后由消费者回流到流。
     * 复用出队现算优先级的有界队列（HIGH 先行）；容量与 Redis 流共用同一配置，
     * 在 {@link #init()} 中创建（容量来自 @Value，构造阶段还没注入）。
     */
    private PriorityTaskQueue<LocalEntry> localFallbackQueue;

    /** 本地兜底队列的入队序号。 */
    private final AtomicLong localSeq = new AtomicLong();

    public CommentCollectionFacade(ProviderConfigService configService,
                                   DynamicProviderRouter router,
                                   ProviderInvocationPipeline pipeline,
                                   ApplicationContext applicationContext,
                                   TaskMetrics taskMetrics,
                                   CommentDao commentDao,
                                   RedisTaskStream taskStream,
                                   SystemConfigService systemConfigService,
                                   RedisHealthMonitor redisHealth) {
        this.configService = configService;
        this.router = router;
        this.pipeline = pipeline;
        this.applicationContext = applicationContext;
        this.taskMetrics = taskMetrics;
        this.commentDao = commentDao;
        this.taskStream = taskStream;
        this.systemConfigService = systemConfigService;
        this.redisHealth = redisHealth;
    }

    /** 容量配置注入完成后创建有界兜底队列。 */
    @jakarta.annotation.PostConstruct
    public void init() {
        this.localFallbackQueue = new PriorityTaskQueue<>(Math.max(0, queueCapacity));
        log.info("本地兜底队列就绪: capacity={}（Redis 故障时接管任务提交与消费）", queueCapacity);
    }

    // ── 同步采集 ───────────────────────────────────────────────────────────

    public CommentCollectResult collectSync(CommentCollectRequest request) {
        return doCollect(request);
    }

    // ── 异步采集（Redis Stream 外置队列 + 本地兜底） ────────────────────────

    /**
     * 提交异步任务：序列化后 XADD 到对应层级的流（Lua 内原子容量检查）。
     *
     * <p><b>Redis 降级兜底</b>：Redis 不可用（故障标记或操作异常）时任务落入
     * {@link #localFallbackQueue}，由消费者在本实例直接消化；Redis 恢复后由消费者的
     * 回流任务重新入队。兜底队列是**内存态**——降级期间进程崩溃会丢消息，
     * 但子任务在 Mongo 里是 RUNNING，启动恢复扫描会重新提交，不产生永久丢失。
     *
     * <p>提交后**没有返回 Future**——执行方是消费者组（可能不在本实例），
     * 结果回写由消费者完成后经 {@link TaskResultListener} 通知任务管理服务。
     *
     * @return 消息 ID（降级入队时为 {@code local:序号}）
     * @throws QueueFullException 队列容量已满（Redis 容量或本地兜底容量）
     */
    public String enqueueAsync(CommentCollectRequest request) {
        int userPriority = resolveUserPriority(request.getUserTierCode());
        int bound = systemConfigService.getInt("collector.task.fair-quota-high-priority-bound",
                highPriorityBoundDefault);
        String tier = QueueTiers.of(userPriority, bound);
        QueuedTask task = QueuedTask.of(request, tier);

        if (redisHealth.isAvailable()) {
            try {
                String messageId = taskStream.enqueue(task);
                taskMetrics.recordTaskSubmitted();
                log.debug("任务入队: userId={} tier={} priority={} messageId={} queueSize={}/{}",
                        request.getUserId(), tier, userPriority, messageId,
                        taskStream.totalLength(), queueCapacity);
                return messageId;
            } catch (QueueFullException qfe) {
                throw qfe;  // 真实容量已满，转发过载语义
            } catch (Exception e) {
                redisHealth.markFailure("enqueue", e);
                // 落到下面的本地兜底
            }
        }
        return enqueueLocal(task, userPriority);
    }

    /** 降级入队：本地有界队列，满则 503。 */
    private String enqueueLocal(QueuedTask task, int userPriority) {
        LocalEntry entry = new LocalEntry(task, QueueTiers.HIGH.equals(task.getTier()) ? 0 : 999,
                localSeq.incrementAndGet());
        if (!localFallbackQueue.offer(entry)) {
            throw queueFull();
        }
        taskMetrics.recordTaskSubmitted();
        log.warn("任务落入本地兜底队列: userId={} tier={} localSize={}（Redis 恢复后自动回流；"
                + "降级期间进程崩溃由启动恢复扫描兜底）", task.getUserId(), task.getTier(), localFallbackQueue.size());
        return "local:" + entry.seq;
    }

    /**
     * 入口 fail-fast 过载检查：**快照**判断"当前积压 + 本次所需"是否超容量。
     *
     * <p>真正的硬保证在 {@link RedisTaskStream#enqueue} 的 Lua 原子检查里（每条消息 XADD 时
     * 单独校验，绝不超卖）。本方法的意义是让整批请求在建任务前就拿到 503，
     * 避免出现"主任务已建、子任务逐个被拒"的半受理状态。
     *
     * <p>Redis 不可用时退化为检查本地兜底队列余量（与 enqueue 的降级路径对齐）。
     *
     * @param needed 本次请求需要的队列名额（等于链接数）
     * @throws QueueFullException 快照余量不足
     */
    public void reserveCapacity(int needed) {
        int slots = Math.max(1, needed);
        if (redisHealth.isAvailable()) {
            try {
                if (taskStream.hasCapacityFor(slots)) {
                    return;
                }
                throw queueFull();
            } catch (QueueFullException qfe) {
                throw qfe;
            } catch (Exception e) {
                redisHealth.markFailure("hasCapacityFor", e);
            }
        }
        // 降级：检查本地兜底队列余量
        if (localFallbackQueue.remainingCapacity() < slots) {
            throw new QueueFullException(localFallbackQueue.size(), queueCapacity,
                    localFallbackQueue.remainingCapacity());
        }
    }

    /** 归还未使用的预留名额。外置队列后 XADD 逐条原子校验容量，本方法**不再需要**，保留为空操作以兼容调用方。 */
    public void releaseCapacity(int unused) {
        // no-op：容量在每条消息 XADD 时原子校验，无预留信号量需要归还
    }

    /** 构造队列满异常（统一带上 size / capacity / remaining 三个数字；Redis 不可用时按本地兜底队列口径）。 */
    private QueueFullException queueFull() {
        long size;
        try {
            size = taskStream.totalLength();
        } catch (Exception e) {
            size = localFallbackQueue.size();
        }
        return new QueueFullException((int) size, queueCapacity,
                (int) Math.max(0, queueCapacity - size));
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    /** 当前积压量 = 流内未确认消息 + 本地兜底队列（降级期间的任务）。 */
    public int getQueueSize() {
        long remote = 0;
        if (redisHealth.isAvailable()) {
            try {
                remote = taskStream.totalLength();
            } catch (Exception e) {
                redisHealth.markFailure("totalLength", e);
            }
        }
        long overflow = (long) remote + localFallbackQueue.size();
        return (int) Math.min(Integer.MAX_VALUE, overflow);
    }

    public int getRemainingCapacity() {
        return Math.max(0, queueCapacity - getQueueSize());
    }

    // ── Redis 降级协作（消费者使用） ───────────────────────────────────────

    /** Redis 是否可用（false = 降级模式，消费者转本地兜底队列）。 */
    public boolean isRedisAvailable() {
        return redisHealth.isAvailable();
    }

    /** 本地兜底队列积压量。 */
    public int localFallbackSize() {
        return localFallbackQueue.size();
    }

    /** 消费者从本地兜底队列取一条（按优先级，HIGH 先行）；空返回 null。 */
    public QueuedTask pollLocalTask() {
        LocalEntry entry = localFallbackQueue.poll();
        return entry == null ? null : entry.task();
    }

    /** 回流失败时把任务放回本地兜底队列（尾部，极少发生：仅在回流预算内并发竞争时）。 */
    public void offerLocalBack(QueuedTask task) {
        LocalEntry entry = new LocalEntry(task, QueueTiers.HIGH.equals(task.getTier()) ? 0 : 999,
                localSeq.incrementAndGet());
        if (!localFallbackQueue.offer(entry)) {
            // 本地队列也满了：放弃回插。该子任务在 Mongo 是 RUNNING，启动恢复扫描会重新提交
            log.error("本地兜底队列已满且回流被拒，任务交由启动恢复扫描兜底: subTaskId={}", task.getSubTaskId());
        }
    }

    /** 远端（Redis 流）剩余容量；Redis 不可用返回 0（消费者回流任务据此暂停）。 */
    public int remoteRemainingCapacity() {
        if (!redisHealth.isAvailable()) {
            return 0;
        }
        try {
            return Math.max(0, queueCapacity - (int) taskStream.totalLength());
        } catch (Exception e) {
            redisHealth.markFailure("totalLength", e);
            return 0;
        }
    }

    /** 直接把兜底任务重新入队到 Redis 流（回流任务专用；不做降级兜底，失败向上抛）。 */
    public String enqueueRemote(QueuedTask task) {
        return taskStream.enqueue(task);
    }

    private int resolveUserPriority(String tierCode) {
        if (tierCode == null) return 999;
        return configService.loadUserTierConfig(tierCode)
                .map(UserTierConfig::getPriority)
                .orElse(999);
    }

    // ── 核心采集逻辑（消费者线程与同步接口共用；带供应商切换） ──────────────

    /**
     * 执行一次采集（选供应商 + 管线调用 + 落库）。**只做采集本身**，
     * 子任务/主任务状态回写由调用方负责（同步接口直接返回；消费者回调
     * {@link TaskResultListener}）。
     */
    public CommentCollectResult doCollect(CommentCollectRequest request) {
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
                            featurePreference,
                            Capability.parse(request.getRequiredCapabilities()),
                            Capability.parse(request.getExcludedCapabilities())));

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

    /**
     * 用户等级供应商偏好（含等级继承）：本等级未声明时沿 parentTierCode 继承父等级的
     * 整条配置（最近声明者优先），解析细节见 {@code ProviderConfigService#resolveFeaturePreference}。
     */
    private FeatureProviderConfig resolveFeaturePreference(
            String tierCode, String platformCode, String featureCode) {
        return configService.resolveFeaturePreference(tierCode, platformCode, featureCode)
                .orElse(null);
    }

    // ── 本地兜底队列条目 ───────────────────────────────────────────────────

    /**
     * 兜底队列条目：包装 {@link QueuedTask} 以复用 {@link PriorityTaskQueue} 的
     * "出队时现算优先级"（HIGH 层级任务优先消化）。
     */
    private record LocalEntry(QueuedTask task, int tierPriority, long seq)
            implements com.sysj.collector.core.scheduler.Prioritized {

        @Override
        public int effectivePriority() {
            return tierPriority;
        }

        @Override
        public long enqueueSeq() {
            return seq;
        }
    }
}
