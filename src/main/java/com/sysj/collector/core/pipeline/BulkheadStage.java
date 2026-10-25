package com.sysj.collector.core.pipeline;

import com.sysj.collector.core.ratelimit.FlowEffect;
import com.sysj.collector.domain.service.SystemConfigService;
import com.sysj.collector.exception.BulkheadFullException;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bulkhead 舱壁隔离（§8.1，借鉴 B-20）：限制**单个供应商**的在途调用数。
 *
 * <p>解决的问题（C-33 的线程耗尽）：32 个消费者线程可能同时压在同一个慢供应商上，
 * 一旦该下游卡住，全部消费者都被拖住，其它供应商的任务也做不了。
 * Bulkhead 把"一个下游的故障"限制在它自己的额度内。
 *
 * <p><b>取不到许可时怎么办：由 {@code providers[].flow_effect} 决定</b>（与限流阶段同一套语义）：
 * <ul>
 *   <li>{@code REJECT}（以及其它未列出取值）—— <b>立即失败</b>，抛
 *       {@link BulkheadFullException}，由门面切换到下一个候选。排队只是把"下游慢"换成"队列长"，
 *       占住的消费者线程一样多，所以默认不排队。</li>
 *   <li>{@code THROTTLE_QUEUE} —— <b>有界排队</b>：最多等
 *       {@code providers[].max_queue_wait_ms}（为 0 时取
 *       {@code collector.pipeline.bulkhead-max-queue-wait-ms}），等到就继续，等不到仍抛
 *       {@link BulkheadFullException}。适合"慢但稳、又没有备用供应商"的供应商，
 *       避免把纯粹的本地排队抖动记成上游失败（那会既浪费重投、又污染熔断窗口）。</li>
 * </ul>
 * 等待时长**始终**被 {@code collector.pipeline.bulkhead-max-queue-wait-ms} 封顶：排队占的是
 * 消费者线程，不能让某个供应商的配置把线程长期钉死在等待上（这正是 Bulkhead 要防的事）。
 * 信号量是公平模式，因此等待者是 FIFO，不会出现先到的被后到的反复插队饿死。
 *
 * <p><b>信号量按配置重建</b>：{@code max_concurrency} 在 DB 里是可改的，重建时会放弃旧信号量上
 * 仍在途的许可。因此修改配置的那一瞬间并发上限可能短暂超限一次 —— 这是可接受的，
 * 换来的是"不用重启服务即可调整并发上限"。
 *
 * <h3>2026-10-11 修订：开关与排队上限支持热覆盖</h3>
 * 三个参数此前是纯 {@code @Value}（改配置必须重启），与其它组件（熔断、限速、回调）
 * "properties 默认 + system_config 覆盖"的范式不一致。现在改为同款：
 * {@code collector.pipeline.enabled} / {@code collector.pipeline.bulkhead-enabled} /
 * {@code collector.pipeline.bulkhead-max-queue-wait-ms} 可用 DB 值覆盖，最迟 60s 生效
 * （或 {@code PUT /api/config/{key}} 立即生效）。{@code @Value} 仍然是默认值。
 * 每次调用读的是缓存的整表配置（本地 Map 查表），不额外增加 Redis/Mongo 往返。
 */
@Slf4j
@Component
public class BulkheadStage implements ProviderInvocationStage {

    private final SystemConfigService systemConfigService;

    /** 管线总开关（压测时逐项隔离用，见 §9.2）。 */
    @Value("${collector.pipeline.enabled:true}")
    private boolean pipelineEnabledDefault;

    /** Bulkhead 单独开关。 */
    @Value("${collector.pipeline.bulkhead-enabled:true}")
    private boolean bulkheadEnabledDefault;

    /**
     * 排队等待上限（毫秒）：{@code THROTTLE_QUEUE} 供应商最多等这么久；
     * {@code <=0} 表示不排队（退回"立即失败"语义）。同时也是所有供应商排队时长的封顶值。
     */
    @Value("${collector.pipeline.bulkhead-max-queue-wait-ms:5000}")
    private long bulkheadMaxQueueWaitMsDefault;

    public BulkheadStage(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    /** 管线总开关：DB 覆盖 properties 默认。 */
    private boolean pipelineEnabled() {
        return systemConfigService.getBool("collector.pipeline.enabled", pipelineEnabledDefault);
    }

    /** Bulkhead 开关：DB 覆盖 properties 默认。 */
    private boolean bulkheadEnabled() {
        return systemConfigService.getBool("collector.pipeline.bulkhead-enabled", bulkheadEnabledDefault);
    }

    /** 排队上限：DB 覆盖 properties 默认。 */
    private long bulkheadMaxQueueWaitMs() {
        return systemConfigService.getLong("collector.pipeline.bulkhead-max-queue-wait-ms",
                bulkheadMaxQueueWaitMsDefault);
    }

    private final ConcurrentHashMap<String, Holder> holders = new ConcurrentHashMap<>();
    private final Object holderLock = new Object();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong queueTimeout = new AtomicLong();
    private final AtomicLong queueWaitMillis = new AtomicLong();

    /** 启动即打印生效值：运维不用改配置就能确认"到底排不排队、最多等多久"。 */
    @PostConstruct
    void logQueueSemantics() {
        log.info("Bulkhead 取许可语义: REJECT/其它=不排队立即失败; THROTTLE_QUEUE=有界排队，上限 {}ms（<=0 表示不排队）",
                bulkheadMaxQueueWaitMs());
        log.info("Bulkhead 开关: enabled={} bulkhead-enabled={}（均可用 system_config 同名键热覆盖）",
                pipelineEnabled(), bulkheadEnabled());
    }

    @Override
    public int order() {
        return Stages.BULKHEAD;
    }

    @Override
    public String name() {
        return "Bulkhead";
    }

    @Override
    public <T> T invoke(InvocationContext ctx, java.util.function.Supplier<T> next) {
        if (!pipelineEnabled() || !bulkheadEnabled() || ctx.maxConcurrency() <= 0) {
            return next.get();
        }

        Holder holder = holderFor(ctx.supplierKey(), ctx.maxConcurrency());
        long waitMs = queueWaitMs(ctx);
        if (!acquire(holder, waitMs)) {
            rejected.incrementAndGet();
            throw new BulkheadFullException(ctx.providerKey(), holder.inUse.get(),
                    holder.maxConcurrency, waitMs);
        }
        holder.inUse.incrementAndGet();
        try {
            // 失败、超时、异常路径都必须归还许可，否则额度会被永久泄漏
            return next.get();
        } finally {
            holder.inUse.decrementAndGet();
            holder.semaphore.release();
        }
    }

    /**
     * 取许可：{@code waitMs <= 0} 时立即返回（REJECT 语义）；否则最多等 {@code waitMs}。
     *
     * <p>被中断时恢复中断标记并按"未取得许可"处理 —— 调用方已经不需要这个结果了，
     * 继续往下执行反而会给上游多发一次请求。
     */
    private boolean acquire(Holder holder, long waitMs) {
        if (waitMs <= 0L) {
            return holder.semaphore.tryAcquire();
        }
        long begin = System.nanoTime();
        try {
            boolean acquired = holder.semaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
            queueWaitMillis.addAndGet((System.nanoTime() - begin) / 1_000_000L);
            if (!acquired) {
                queueTimeout.incrementAndGet();
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queueTimeout.incrementAndGet();
            return false;
        }
    }

    /**
     * 本次调用允许的排队等待时长。
     *
     * <p>只有 {@code flow_effect=THROTTLE_QUEUE} 才排队；时长取
     * {@code providers[].max_queue_wait_ms}，为 0/缺失时退回全局
     * {@code collector.pipeline.bulkhead-max-queue-wait-ms}，并**始终**被该全局值封顶。
     */
    private long queueWaitMs(InvocationContext ctx) {
        long ceiling = Math.max(0L, bulkheadMaxQueueWaitMs());
        if (ceiling == 0L || FlowEffect.of(ctx.provider().getFlowEffect()) != FlowEffect.THROTTLE_QUEUE) {
            return 0L;
        }
        Long configured = ctx.provider().getMaxQueueWaitMs();
        long requested = configured == null || configured <= 0L ? ceiling : configured;
        return Math.min(requested, ceiling);
    }

    /**
     * 取该供应商的信号量；配置值变化时按新上限重建。
     *
     * <p>为什么要加锁：{@code ConcurrentHashMap} 本身线程安全，但"判断 + 替换"不是原子的，
     * 并发首次调用可能各建一个信号量，导致上限被放大一倍。
     */
    private Holder holderFor(String supplierKey, int maxConcurrency) {
        Holder existing = holders.get(supplierKey);
        if (existing != null && existing.maxConcurrency == maxConcurrency) {
            return existing;
        }
        synchronized (holderLock) {
            Holder current = holders.get(supplierKey);
            if (current != null && current.maxConcurrency == maxConcurrency) {
                return current;
            }
            Holder created = new Holder(maxConcurrency);
            holders.put(supplierKey, created);
            log.info("Bulkhead 额度{}: key={} maxConcurrency={}{}",
                    current == null ? "初始化" : "重建", supplierKey, maxConcurrency,
                    current == null ? "" : "（原 " + current.maxConcurrency + "）");
            return created;
        }
    }

    /** 单个供应商的额度。 */
    private static final class Holder {
        private final Semaphore semaphore;
        private final int maxConcurrency;
        private final AtomicInteger inUse = new AtomicInteger();

        private Holder(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            this.semaphore = new Semaphore(maxConcurrency, true);
        }
    }

    // ── 观测 ───────────────────────────────────────────────────────────────

    /** 累计被拒绝的次数（≥0），含"排队超时"那一部分。 */
    public long rejectedCount() {
        return rejected.get();
    }

    /**
     * 累计"排队等到上限仍未取得许可"的次数（≤ 走排队分支的次数）。
     * 与 {@link #rejectedCount()} 的差值即"立即失败（未排队）"的次数。
     */
    public long queueTimeoutCount() {
        return queueTimeout.get();
    }

    /** 累计排队等待耗时（毫秒）：走上排队分支的调用，在等待上花掉的总墙钟时间。 */
    public long totalQueueWaitMillis() {
        return queueWaitMillis.get();
    }

    /** 当前每个供应商的"在途 / 上限"快照，用于运维诊断。 */
    public Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        holders.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), e.getValue().inUse.get() + "/" + e.getValue().maxConcurrency));
        return out;
    }
}
