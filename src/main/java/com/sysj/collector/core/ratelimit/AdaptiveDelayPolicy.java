package com.sysj.collector.core.ratelimit;

/**
 * 自适应限速的**纯计算**部分 `[借鉴 B-11]`。
 *
 * <p>把"由本次调用的结果推导下一次的延迟"抽成无状态静态方法，便于脱离 Spring 与令牌桶直接验证
 * （设计文档 §8.2.1）。真正的状态（当前延迟、预热起点、令牌桶）在 {@link AdaptiveRateLimiter} 里。
 *
 * <pre>
 * target_delay = latency_ms / target_concurrency
 * new_delay    = (old_delay + target_delay) / 2          ← 指数平滑，避免单次抖动直接决定速率
 * 失败时        new_delay 只允许增大，不允许减小            ← 关键：失败后不能因为"这次很快"就提速
 * new_delay    = clamp(new_delay, start_delay_ms, max_delay_ms)
 * effective_rate = min(static_rate, 1000 / new_delay)
 * </pre>
 */
public final class AdaptiveDelayPolicy {

    private AdaptiveDelayPolicy() {
    }

    /**
     * 单个供应商的自适应参数。
     *
     * @param targetConcurrency 目标并发（&le;0 视为 1.0）
     * @param startDelayMs      延迟下限，也是初始值（&le;0 视为 500）
     * @param maxDelayMs        延迟上限（必须 &ge; startDelayMs，否则取 startDelayMs）
     */
    public record Params(double targetConcurrency, long startDelayMs, long maxDelayMs) {

        public static Params defaults() {
            return new Params(1.0, 500L, 60_000L);
        }

        /** 收敛非法配置，保证后续计算不会出现除零/上下界颠倒。 */
        public Params sanitized() {
            double concurrency = targetConcurrency <= 0 ? 1.0 : targetConcurrency;
            long start = startDelayMs <= 0 ? 500L : startDelayMs;
            long max = maxDelayMs < start ? start : maxDelayMs;
            return new Params(concurrency, start, max);
        }
    }

    /**
     * 推导下一次的延迟。
     *
     * @param currentDelayMs 当前延迟；&le;0 表示尚未初始化，用 {@code startDelayMs} 起步
     * @param latencyMs      本次调用耗时；&le;0（未知）时视为不改变
     * @param success        本次是否成功
     * @param params         参数
     * @return 下一次使用的延迟（已 clamp）
     */
    public static long nextDelay(long currentDelayMs, long latencyMs, boolean success, Params params) {
        Params p = params.sanitized();
        long current = currentDelayMs <= 0 ? p.startDelayMs() : currentDelayMs;

        if (latencyMs <= 0) {
            // 耗时未知：只能依据成败调整，不做平滑
            return clamp(success ? current : Math.max(current, current), p);
        }

        long target = (long) (latencyMs / p.targetConcurrency());
        long smoothed = (current + target) / 2;

        if (!success) {
            // 失败时只允许增大：否则"失败但很快返回"会把速率提回去，放大故障
            smoothed = Math.max(current, smoothed);
        }
        return clamp(smoothed, p);
    }

    /**
     * 由延迟换算有效速率。
     *
     * @param staticRatePerSecond DB 配置的静态速率（这是**上限**，自适应只会往下压）
     * @param delayMs             当前延迟
     * @return {@code min(staticRate, 1000 / delayMs)}，且保证 &gt; 0
     */
    public static double effectiveRate(double staticRatePerSecond, long delayMs) {
        if (staticRatePerSecond <= 0) {
            return 0;
        }
        if (delayMs <= 0) {
            return staticRatePerSecond;
        }
        double adaptive = 1000.0 / delayMs;
        return Math.min(staticRatePerSecond, adaptive);
    }

    /**
     * 预热系数：从 {@code warmUpSeconds} 前的 0 平滑升到 1。
     *
     * @param elapsedMs      距预热起点的毫秒数
     * @param warmUpSeconds  预热时长；&le;0 表示不预热（直接返回 1）
     * @return {@code [0, 1]}
     */
    public static double warmUpFactor(long elapsedMs, long warmUpSeconds) {
        if (warmUpSeconds <= 0) {
            return 1.0;
        }
        if (elapsedMs <= 0) {
            return 0.0;
        }
        double ratio = (double) elapsedMs / (warmUpSeconds * 1000.0);
        return Math.min(1.0, Math.max(0.0, ratio));
    }

    private static long clamp(long value, Params p) {
        if (value < p.startDelayMs()) {
            return p.startDelayMs();
        }
        return Math.min(value, p.maxDelayMs());
    }
}
