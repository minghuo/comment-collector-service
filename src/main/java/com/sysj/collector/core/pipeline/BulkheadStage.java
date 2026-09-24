package com.sysj.collector.core.pipeline;

import com.sysj.collector.exception.BulkheadFullException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bulkhead 舱壁隔离（§8.1，借鉴 B-20）：限制**单个供应商**的在途调用数。
 *
 * <p>解决的问题（C-33 的线程耗尽）：32 个消费者线程可能同时压在同一个慢供应商上，
 * 一旦该下游卡住，全部消费者都被拖住，其它供应商的任务也做不了。
 * Bulkhead 把"一个下游的故障"限制在它自己的额度内。
 *
 * <p><b>为什么立即失败而不是排队</b>：见 {@link BulkheadFullException} 的类注释 ——
 * 排队只是把"下游慢"换成"队列长"，等待的线程一样被占用。立即失败后由门面切换到下一个候选，
 * 压力转移到路由层。
 *
 * <p><b>信号量按配置重建</b>：{@code max_concurrency} 在 DB 里是可改的，重建时会放弃旧信号量上
 * 仍在途的许可。因此修改配置的那一瞬间并发上限可能短暂超限一次 —— 这是可接受的，
 * 换来的是"不用重启服务即可调整并发上限"。
 */
@Slf4j
@Component
public class BulkheadStage implements ProviderInvocationStage {

    /** 管线总开关（压测时逐项隔离用，见 §9.2）。 */
    @Value("${collector.pipeline.enabled:true}")
    private boolean pipelineEnabled;

    /** Bulkhead 单独开关。 */
    @Value("${collector.pipeline.bulkhead-enabled:true}")
    private boolean bulkheadEnabled;

    private final ConcurrentHashMap<String, Holder> holders = new ConcurrentHashMap<>();
    private final Object holderLock = new Object();
    private final AtomicLong rejected = new AtomicLong();

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
        if (!pipelineEnabled || !bulkheadEnabled || ctx.maxConcurrency() <= 0) {
            return next.get();
        }

        Holder holder = holderFor(ctx.supplierKey(), ctx.maxConcurrency());
        if (!holder.semaphore.tryAcquire()) {
            rejected.incrementAndGet();
            throw new BulkheadFullException(ctx.providerKey(), holder.inUse.get(), holder.maxConcurrency);
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

    /** 累计被拒绝的次数（≥0）。 */
    public long rejectedCount() {
        return rejected.get();
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
