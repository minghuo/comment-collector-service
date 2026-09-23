package com.sysj.collector.core.circuit;

/**
 * 熔断器参数（每个供应商共用同一套阈值，见设计文档 §8.5）。
 *
 * @param failureRateThreshold  失败率阈值（%），达到即 OPEN
 * @param slowCallMs            慢调用判定（毫秒）；&le;0 表示不做慢调用统计
 * @param slowCallRateThreshold 慢调用比例阈值（%）
 * @param slidingWindowSize     滑动窗口请求数（只统计最近 N 次调用）
 * @param minimumCalls          触发统计所需的最小请求数，未达此数不熔断
 * @param openSeconds           熔断时长（秒），冷却结束后转 HALF_OPEN
 * @param halfOpenCalls         半开放行的探测请求数，全部成功才回到 CLOSED
 */
public record CircuitBreakerConfig(
        double failureRateThreshold,
        long slowCallMs,
        double slowCallRateThreshold,
        int slidingWindowSize,
        int minimumCalls,
        long openSeconds,
        int halfOpenCalls) {

    /** 兜底默认值（与 {@code application.properties} 的默认值保持一致）。 */
    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(50.0, 10_000L, 80.0, 20, 5, 60L, 3);
    }

    /** 参数自检：非法值收敛到安全范围，避免配错就把所有供应商永久熔断。 */
    public CircuitBreakerConfig sanitized() {
        return new CircuitBreakerConfig(
                failureRateThreshold <= 0 || failureRateThreshold > 100 ? 50.0 : failureRateThreshold,
                Math.max(0L, slowCallMs),
                slowCallRateThreshold <= 0 || slowCallRateThreshold > 100 ? 80.0 : slowCallRateThreshold,
                Math.max(2, slidingWindowSize),
                Math.max(1, Math.min(minimumCalls, Math.max(2, slidingWindowSize))),
                Math.max(1L, openSeconds),
                Math.max(1, halfOpenCalls));
    }
}
