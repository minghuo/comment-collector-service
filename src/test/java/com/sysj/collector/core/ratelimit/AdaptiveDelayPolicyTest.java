package com.sysj.collector.core.ratelimit;

import com.sysj.collector.core.ratelimit.AdaptiveDelayPolicy.Params;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AdaptiveDelayPolicy} 单元测试：指数平滑、"失败只增不减"、clamp 上下界，
 * 以及耗时未知（{@code latencyMs <= 0}）这条分支。
 *
 * <p>最后这组用例是回归用例：这里曾经写成 {@code Math.max(current, current)}（恒等式），
 * 于是"亚毫秒级的快速失败"（latencyMs 算出来是 0）永远不会降速，速率一直顶在上限，
 * 与类注释约定的"失败时 new_delay 只允许增大"直接矛盾（对应风险 R12）。
 */
class AdaptiveDelayPolicyTest {

    private static final Params DEFAULT = Params.defaults(); // (targetConcurrency=1.0, start=500, max=60000)

    // ---------- 耗时未知（latencyMs <= 0）----------

    @Test
    void unknownLatencySuccessKeepsDelay() {
        assertEquals(1_000L, AdaptiveDelayPolicy.nextDelay(1_000L, 0L, true, DEFAULT));
    }

    @Test
    void unknownLatencyFailureDoublesDelay() {
        assertEquals(2_000L, AdaptiveDelayPolicy.nextDelay(1_000L, 0L, false, DEFAULT));
    }

    @Test
    void unknownLatencyFailureRespectsMaxDelay() {
        assertEquals(60_000L, AdaptiveDelayPolicy.nextDelay(40_000L, 0L, false, DEFAULT));
    }

    @Test
    void unknownLatencyFailureWithUninitializedDelayStartsFromStartDelay() {
        // current <= 0 → 用 start_delay(500) 起步，失败翻倍 → 1000（修复前恒为 500）
        assertEquals(1_000L, AdaptiveDelayPolicy.nextDelay(0L, 0L, false, DEFAULT));
    }

    @Test
    void negativeLatencyIsTreatedAsUnknown() {
        assertEquals(2_000L, AdaptiveDelayPolicy.nextDelay(1_000L, -1L, false, DEFAULT));
    }

    // ---------- 已知耗时 ----------

    @Test
    void successSmoothsTowardTargetDelay() {
        // target = 1000 / 1.0 = 1000；smoothed = (10000 + 1000) / 2 = 5500
        assertEquals(5_500L, AdaptiveDelayPolicy.nextDelay(10_000L, 1_000L, true, DEFAULT));
    }

    @Test
    void failureNeverLowersDelayEvenWhenCallWasFast() {
        // 失败时 smoothed 被抬回 current：max(10000, (10000+1)/2=5000) = 10000
        assertEquals(10_000L, AdaptiveDelayPolicy.nextDelay(10_000L, 1L, false, DEFAULT));
    }

    @Test
    void failureRaisesDelayWhenSmoothedIsHigher() {
        // smoothed = (1000 + 8000) / 2 = 4500 > current 1000 → 失败取 4500
        assertEquals(4_500L, AdaptiveDelayPolicy.nextDelay(1_000L, 8_000L, false, DEFAULT));
    }

    @Test
    void resultIsClampedToStartDelay() {
        // smoothed = (500 + 1) / 2 = 250 → 低于 start_delay(500) → 500
        assertEquals(500L, AdaptiveDelayPolicy.nextDelay(500L, 1L, true, DEFAULT));
    }

    @Test
    void resultIsClampedToMaxDelay() {
        // smoothed = (60000 + 1000000) / 2 = 530000 → 高于 max_delay(60000) → 60000
        assertEquals(60_000L, AdaptiveDelayPolicy.nextDelay(60_000L, 1_000_000L, true, DEFAULT));
    }

    @Test
    void targetConcurrencyDividesLatency() {
        // target = 8000 / 4.0 = 2000；smoothed = (10000 + 2000) / 2 = 6000
        Params p = new Params(4.0, 500L, 60_000L);
        assertEquals(6_000L, AdaptiveDelayPolicy.nextDelay(10_000L, 8_000L, true, p));
    }

    // ---------- Params.sanitized ----------

    @Test
    void sanitizedFixesIllegalParams() {
        Params p = new Params(0.0, 0L, 0L).sanitized();
        assertEquals(1.0, p.targetConcurrency());
        assertEquals(500L, p.startDelayMs());
        assertEquals(500L, p.maxDelayMs());
    }

    @Test
    void sanitizedFixesInvertedBounds() {
        Params p = new Params(2.0, 5_000L, 1_000L).sanitized();
        assertEquals(5_000L, p.startDelayMs());
        assertEquals(5_000L, p.maxDelayMs());
    }

    // ---------- effectiveRate ----------

    @Test
    void effectiveRateIsCappedByStaticRate() {
        // 静态上限 0.5/s；delay=500ms → 1000/500 = 2.0/s，被上限压回 0.5
        assertEquals(0.5, AdaptiveDelayPolicy.effectiveRate(0.5, 500L));
    }

    @Test
    void effectiveRateFallsBelowStaticRateWhenDelayGrows() {
        assertEquals(0.25, AdaptiveDelayPolicy.effectiveRate(2.0, 4_000L));
    }

    @Test
    void effectiveRateDegeneratesOnIllegalInput() {
        assertEquals(0.0, AdaptiveDelayPolicy.effectiveRate(0.0, 1_000L));
        assertEquals(0.5, AdaptiveDelayPolicy.effectiveRate(0.5, 0L));
    }

    // ---------- warmUpFactor ----------

    @Test
    void warmUpFactorRampsLinearly() {
        assertEquals(0.0, AdaptiveDelayPolicy.warmUpFactor(0L, 60L));
        assertEquals(0.5, AdaptiveDelayPolicy.warmUpFactor(30_000L, 60L));
        assertEquals(1.0, AdaptiveDelayPolicy.warmUpFactor(60_000L, 60L));
        assertEquals(1.0, AdaptiveDelayPolicy.warmUpFactor(120_000L, 60L));
        // 未开启预热 → 不压制速率
        assertEquals(1.0, AdaptiveDelayPolicy.warmUpFactor(0L, 0L));
    }
}
