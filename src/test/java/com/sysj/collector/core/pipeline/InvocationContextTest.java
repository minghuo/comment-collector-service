package com.sysj.collector.core.pipeline;

import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link InvocationContext#totalBudgetMs(long)} 的回归用例。
 *
 * <p>这里曾经硬编码 500ms 作为退避基数，而 {@code RetryStage} 的退避基数来自
 * {@code collector.pipeline.retry-backoff-base-ms}（system_config 可改）。两者一旦不一致，
 * 改大基数后"预算"就小于真实退避总和，重试会被 {@code ProviderTimeoutException.totalBudget}
 * 提前打断（对应风险 R10）。用例把"基数必须参与计算、非法基数回退 500"钉死。
 */
class InvocationContextTest {

    /** 单次超时 15s、最多重试 2 次（即最多 3 次尝试）。 */
    private InvocationContext ctx(long timeoutMs, int maxRetry) {
        return new InvocationContext(null, "demo", "comment", null, timeoutMs, 1, maxRetry);
    }

    @Test
    void budgetUsesGivenBackoffBase() {
        // 15000 * 3 + 500 * (2^2 - 1) = 45000 + 1500
        assertEquals(46_500L, ctx(15_000L, 2).totalBudgetMs(500L));
        // 15000 * 3 + 2000 * 3 = 45000 + 6000
        assertEquals(51_000L, ctx(15_000L, 2).totalBudgetMs(2_000L));
    }

    @Test
    void illegalBackoffBaseFallsBackTo500() {
        assertEquals(46_500L, ctx(15_000L, 2).totalBudgetMs(0L));
        assertEquals(46_500L, ctx(15_000L, 2).totalBudgetMs(-1L));
    }

    @Test
    void noRetryMeansNoBackoff() {
        assertEquals(15_000L, ctx(15_000L, 0).totalBudgetMs(500L));
    }

    @Test
    void zeroTimeoutMeansNoBudget() {
        assertEquals(0L, ctx(0L, 2).totalBudgetMs(500L));
    }

    @Test
    void supplierKeyIsPlatformFeatureProvider() {
        ProviderConfig provider = new ProviderConfig();
        provider.setProviderKey("key");
        InvocationContext ctx = new InvocationContext(null, "demo", "comment", provider, 15_000L, 1, 2);
        assertEquals("demo:comment:key", ctx.supplierKey());
    }
}
