package com.sysj.collector.core.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenBucketMath} 单元测试：分布式令牌桶的纯算术（Lua 脚本与 Java 共用同一口径）。
 */
class TokenBucketMathTest {

    @Test
    void capacityIsOneSecondOfHeadroom() {
        assertEquals(1.0, TokenBucketMath.capacity(0.5));
        assertEquals(10.0, TokenBucketMath.capacity(10.0));
    }

    @Test
    void refillStartsFullForUninitializedBucket() {
        assertEquals(10.0, TokenBucketMath.refill(-1, 0, 1000, 10.0));
    }

    @Test
    void refillAccruesWithElapsedTime() {
        // 满 10 桶在 t=0 消费到 0，t=1000ms 后按 10/s 补回 10 个
        assertEquals(10.0, TokenBucketMath.refill(0, 0, 1000, 10.0), 0.001);
        // 500ms 补 5 个
        assertEquals(5.0, TokenBucketMath.refill(0, 0, 500, 10.0), 0.001);
        // 不会超过容量
        assertEquals(10.0, TokenBucketMath.refill(9.5, 0, 10_000, 10.0), 0.001);
    }

    @Test
    void deficitReflectsRate() {
        // 空桶、10/s：凑 1 个令牌需要 100ms
        assertEquals(100L, TokenBucketMath.deficitMs(0.0, 10.0));
        // 0.5 个令牌：还差 0.5 → 50ms
        assertEquals(50L, TokenBucketMath.deficitMs(0.5, 10.0));
        // 有令牌：无亏空
        assertEquals(0L, TokenBucketMath.deficitMs(1.0, 10.0));
        // 极低速率下不除零：1/s → 1000ms
        assertEquals(1000L, TokenBucketMath.deficitMs(0.0, 1.0));
    }

    @Test
    void refillNeverDecreasesTokens() {
        double tokens = TokenBucketMath.refill(5.0, 2000, 1000, 10.0);
        assertTrue(tokens >= 5.0, "时钟回拨不应扣减令牌");
    }
}
