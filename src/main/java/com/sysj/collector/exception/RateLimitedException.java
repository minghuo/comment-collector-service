package com.sysj.collector.exception;

/**
 * 限流未获取到令牌：该供应商当前有效速率下拿不到调用许可（§8.3，借鉴 B-11/B-14）。
 *
 * <p>由 {@code AdaptiveRateLimiter} 按 {@code providers[].flow_effect} 决定：
 * {@code REJECT} / {@code WARM_UP} 等待到限时后放弃（本异常），{@code THROTTLE_QUEUE} 排队等待。
 */
public class RateLimitedException extends ProviderInvocationException {

    /** 流控效果（REJECT / WARM_UP / THROTTLE_QUEUE）。 */
    private final String effect;
    /** 当前有效速率（许可/秒）。 */
    private final double effectiveRate;

    public RateLimitedException(String providerKey, String effect, double effectiveRate) {
        super(providerKey, "限流未获取到令牌（key=" + providerKey
                + " effect=" + effect + " 有效速率=" + String.format("%.3f", effectiveRate) + "/s）");
        this.effect = effect;
        this.effectiveRate = effectiveRate;
    }

    public String getEffect() {
        return effect;
    }

    public double getEffectiveRate() {
        return effectiveRate;
    }
}
