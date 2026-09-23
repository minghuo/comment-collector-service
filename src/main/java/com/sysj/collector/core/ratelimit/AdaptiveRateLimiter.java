package com.sysj.collector.core.ratelimit;

import com.sysj.collector.core.circuit.CircuitState;
import com.sysj.collector.core.circuit.CircuitStateChangedEvent;
import com.sysj.collector.domain.dao.SupplierStateDao;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自适应限速器 `[借鉴 B-11]` + 流控效果 `[借鉴 B-14]`。
 *
 * <h3>解决的问题</h3>
 * 原来每个供应商的速率是写死在 DB 里的常量（{@code rate_per_second}），
 * 于是出现"**失败越猛打越猛**"：上游变慢/变差时，服务仍以配置速率持续施压，放大故障。
 * 现在速率随实际响应时间自动下调，且失败时**只能降不能升**。
 *
 * <h3>与令牌桶、熔断器的分工</h3>
 * <ul>
 *   <li>{@link AdaptiveDelayPolicy} —— 纯计算：由本次耗时推导下一次延迟（可脱离 Spring 验证）</li>
 *   <li>本类 —— 持有每个供应商的延迟状态与预热起点，算出**有效速率**并驱动令牌桶</li>
 *   <li>{@link ProviderRateLimitManager} —— 令牌桶本身（Guava {@code RateLimiter}）</li>
 *   <li>熔断器管"**要不要打**"（三态开关），本类管"**打多快**"（连续调参）：一个是质变，一个是量变</li>
 * </ul>
 *
 * <h3>预热与熔断恢复的联动</h3>
 * 监听 {@link CircuitStateChangedEvent}：供应商从 {@code OPEN} 恢复时重新开始预热，
 * 避免"刚恢复就以满速打回去"导致立刻二次熔断 —— 这也是 {@code [B-21]} 状态迁移事件的第一处实际消费方。
 *
 * <h3>落库</h3>
 * {@code effective_qps} / {@code adaptive_delay_ms} 通过 {@code SupplierStateDao#updateAdaptiveMetrics}
 * 以 {@code $set} 定点写入，**与熔断器的整档写入互不覆盖**。写库按 key 节流。
 */
@Slf4j
@Component
public class AdaptiveRateLimiter {

    private final ProviderRateLimitManager rateLimitManager;
    private final SupplierStateDao supplierStateDao;

    /** 自适应总开关；关闭后有效速率恒等于静态速率（排障时用来隔离本机制）。 */
    @Value("${collector.ratelimit.adaptive-enabled:true}")
    private boolean adaptiveEnabled;

    /** 单次取令牌的最长等待（`REJECT` / `WARM_UP` 用）。 */
    @Value("${collector.ratelimit.reject-timeout-ms:500}")
    private long rejectTimeoutMs;

    /** `THROTTLE_QUEUE` 的默认最长排队等待（供应商配置未指定时生效）。 */
    @Value("${collector.ratelimit.max-queue-wait-ms:2000}")
    private long maxQueueWaitMs;

    /** 冷启动 / 熔断恢复后的预热时长（秒）。 */
    @Value("${collector.ratelimit.warm-up-seconds:30}")
    private long warmUpSeconds;

    /** 派生指标写库节流（秒）。 */
    @Value("${collector.ratelimit.persist-interval-seconds:30}")
    private long persistIntervalSeconds;

    private long persistIntervalMs;

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public AdaptiveRateLimiter(ProviderRateLimitManager rateLimitManager,
                               SupplierStateDao supplierStateDao) {
        this.rateLimitManager = rateLimitManager;
        this.supplierStateDao = supplierStateDao;
    }

    @PostConstruct
    public void init() {
        this.persistIntervalMs = Math.max(0L, persistIntervalSeconds) * 1000L;
        log.info("自适应限速参数: enabled={} 取令牌等待={}ms 排队上限={}ms 预热={}s 落库间隔={}s",
                adaptiveEnabled, rejectTimeoutMs, maxQueueWaitMs, warmUpSeconds, persistIntervalSeconds);
    }

    // ── 参数 ───────────────────────────────────────────────────────────────

    /**
     * 一次调用的流控规格。
     *
     * @param staticRate     DB 配置的静态速率（有效速率的**上限**）
     * @param params         自适应参数
     * @param effect         流控效果
     * @param maxQueueWaitMs 排队上限；&le;0 表示用全局默认 `collector.ratelimit.max-queue-wait-ms`
     */
    public record FlowSpec(double staticRate,
                           AdaptiveDelayPolicy.Params params,
                           FlowEffect effect,
                           long maxQueueWaitMs) {

        public static FlowSpec of(double staticRate, AdaptiveDelayPolicy.Params params, FlowEffect effect) {
            return new FlowSpec(staticRate, params, effect, 0L);
        }

        public FlowEffect effectOrDefault() {
            return effect == null ? FlowEffect.REJECT : effect;
        }

        public AdaptiveDelayPolicy.Params paramsOrDefault() {
            return params == null ? AdaptiveDelayPolicy.Params.defaults() : params.sanitized();
        }
    }

    // ── 对外 API ───────────────────────────────────────────────────────────

    /**
     * 尝试获取一次调用许可。
     *
     * @param key  限流 key = {@code platform:feature:providerKey}
     * @param spec 流控规格
     * @return 是否获得许可
     */
    public boolean acquire(String key, FlowSpec spec) {
        State s = state(key);
        FlowEffect effect = spec.effectOrDefault();
        double effectiveRate = effectiveRate(key, spec);

        // 令牌桶懒创建用有效速率；已存在则只在变化 >1% 时热更新
        rateLimitManager.syncRateQuietly(key, effectiveRate);

        long waitMs = effect == FlowEffect.THROTTLE_QUEUE
                ? (spec.maxQueueWaitMs() > 0 ? spec.maxQueueWaitMs() : maxQueueWaitMs)
                : rejectTimeoutMs;

        boolean acquired = rateLimitManager.tryAcquire(key, effectiveRate, waitMs);
        if (acquired) {
            s.effectiveQps = effectiveRate;
            persistIfDue(key, s);
        } else {
            log.debug("限流拒绝: key={} effect={} 有效速率={}/s delay={}ms",
                    key, effect, effectiveRate, s.delayMs);
        }
        return acquired;
    }

    /**
     * 上报一次调用结果，据此调整下一次的延迟。
     *
     * @param latencyMs 本次耗时（&le;0 表示未知）
     * @param success   是否成功
     */
    public void recordOutcome(String key, long latencyMs, boolean success,
                              AdaptiveDelayPolicy.Params params) {
        if (!adaptiveEnabled) {
            return;
        }
        State s = state(key);
        synchronized (s) {
            long before = s.delayMs;
            long after = AdaptiveDelayPolicy.nextDelay(before, latencyMs, success, params);
            s.delayMs = after;
            if (before != after) {
                log.debug("自适应延迟调整: key={} {}ms -> {}ms (latency={}ms success={})",
                        key, before, after, latencyMs, success);
            }
            persistIfDue(key, s);
        }
    }

    /** 当前有效速率（供监控/接口展示）。 */
    public double effectiveRate(String key, FlowSpec spec) {
        if (!adaptiveEnabled) {
            return spec.staticRate();
        }
        State s = state(key);
        double rate = AdaptiveDelayPolicy.effectiveRate(spec.staticRate(), s.delayMs);
        if (spec.effectOrDefault() == FlowEffect.WARM_UP) {
            long elapsed = s.warmUpStartMs <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - s.warmUpStartMs;
            rate = rate * AdaptiveDelayPolicy.warmUpFactor(elapsed, warmUpSeconds);
        }
        // 速率必须有下限：预热起点会让系数为 0，Guava RateLimiter 不接受 0 速率
        return Math.max(rate, 1e-4);
    }

    /** 当前延迟快照（key → delayMs），供运维查看。 */
    public Map<String, Long> delaySnapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        states.forEach((k, v) -> result.put(k, v.delayMs));
        return result;
    }

    /**
     * 熔断恢复时重新开始预热。
     *
     * <p>只在"进入 CLOSED"时触发：`OPEN`/`HALF_OPEN` 期间的调用本就由熔断器拦住，无需预热。
     */
    @EventListener
    public void onCircuitStateChanged(CircuitStateChangedEvent event) {
        if (event.to() != CircuitState.CLOSED || event.from() == CircuitState.CLOSED) {
            return;
        }
        state(event.supplierKey()).warmUpStartMs = System.currentTimeMillis();
        log.info("熔断恢复，重置自适应预热: key={} 预热{}s（从 {} 恢复）",
                event.supplierKey(), warmUpSeconds, event.from());
    }

    /** 冷启动/人工复位时手动重置预热起点。 */
    public void restartWarmUp(String key) {
        state(key).warmUpStartMs = System.currentTimeMillis();
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private State state(String key) {
        return states.computeIfAbsent(key, k -> new State());
    }

    /** 按 key 节流写库（`effective_qps` 用最近一次实际生效的速率）。 */
    private void persistIfDue(String key, State s) {
        long now = System.currentTimeMillis();
        if (now - s.lastPersistAt < persistIntervalMs) {
            return;
        }
        s.lastPersistAt = now;
        try {
            supplierStateDao.updateAdaptiveMetrics(key, s.effectiveQps, s.delayMs);
        } catch (Exception e) {
            log.warn("自适应指标落库失败(非关键): key={} error={}", key, e.getMessage());
        }
    }

    /** 每个供应商的自适应状态。 */
    private static final class State {
        /** 当前延迟（毫秒）；0 表示尚未初始化，由 Policy 用 startDelayMs 起步。 */
        private volatile long delayMs;
        /** 预热起点；&le;0 表示没有正在进行的预热（按"早已预热完成"处理）。 */
        private volatile long warmUpStartMs;
        /** 最近一次实际生效的速率（落库用）。 */
        private volatile double effectiveQps;
        /** 上次写库时间。 */
        private volatile long lastPersistAt;
    }
}
