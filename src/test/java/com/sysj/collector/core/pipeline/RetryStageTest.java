package com.sysj.collector.core.pipeline;

import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;
import com.sysj.collector.domain.service.SystemConfigService;
import com.sysj.collector.exception.NonRetryableProviderException;
import com.sysj.collector.exception.ProviderInvocationException;
import com.sysj.collector.exception.RateLimitedException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetryStage} 单元测试：重试次数来自 {@code providers[].max_retry}（= 任务所用平台功能配置），
 * 且"确定性失败"与"限流拒绝"不消耗重试。
 *
 * <p>背景：供应商实现原先各自带 {@code RETRY = 3} 的内层循环，既不读配置、也不经限流，
 * 一次 {@code fetch} 就向上游偷偷多发几次请求。改造后供应商只发一次请求，
 * 重试全部收敛到本阶段 —— 这里的用例把该契约钉死：
 * <ol>
 *   <li>尝试次数 = maxRetry + 1（maxRetry 来自配置，不是硬编码的 3）；</li>
 *   <li>{@link NonRetryableProviderException}（链接不支持/内容已删除）立即上抛，一次请求都不多；</li>
 *   <li>{@link RateLimitedException}（令牌桶没额度）立即上抛，不靠重试硬顶；</li>
 *   <li>Retry 必须包在 RateLimit 外层，重试才真正重新领令牌（见 {@link ProviderInvocationStage.Stages}）。</li>
 * </ol>
 */
class RetryStageTest {

    /** 退避基数压到 1ms，避免用例真的等秒级。 */
    private RetryStage stage() {
        RetryStage stage = new RetryStage(passthroughConfigService());
        ReflectionTestUtils.setField(stage, "pipelineEnabledDefault", true);
        ReflectionTestUtils.setField(stage, "backoffBaseMsDefault", 1L);
        return stage;
    }

    /** system_config 替身：任何键都不存在 ⇒ 读取退回 properties 默认值。 */
    private static SystemConfigService passthroughConfigService() {
        SystemConfigService service = Mockito.mock(SystemConfigService.class);
        Mockito.when(service.getBool(Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        Mockito.when(service.getLong(Mockito.anyString(), Mockito.anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        Mockito.when(service.getInt(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return service;
    }

    private InvocationContext ctx(int maxRetry) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProviderKey("demo_provider");
        provider.setMaxRetry(maxRetry);
        return new InvocationContext(null, "demo", "comment", provider, 15_000L, 1, maxRetry);
    }

    @Test
    void retriesUpToConfiguredMaxRetryThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = stage().invoke(ctx(2), () -> {
            if (calls.incrementAndGet() < 3) {
                throw new ProviderInvocationException("demo_provider", "上游失败 code=500");
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, calls.get(), "maxRetry=2 ⇒ 最多 3 次尝试");
    }

    @Test
    void maxRetryComesFromConfigNotHardcodedThree() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(ProviderInvocationException.class, () -> stage().invoke(ctx(0), () -> {
            calls.incrementAndGet();
            throw new ProviderInvocationException("demo_provider", "上游失败 code=500");
        }));
        assertEquals(1, calls.get(), "maxRetry=0 ⇒ 只发一次请求");

        calls.set(0);
        assertThrows(ProviderInvocationException.class, () -> stage().invoke(ctx(4), () -> {
            calls.incrementAndGet();
            throw new ProviderInvocationException("demo_provider", "上游失败 code=500");
        }));
        assertEquals(5, calls.get(), "maxRetry=4 ⇒ 5 次尝试（不再固定为 3）");
    }

    @Test
    void exhaustedRetriesReportTheConfiguredCount() {
        ProviderInvocationException ex = assertThrows(ProviderInvocationException.class,
                () -> stage().invoke(ctx(2), () -> {
                    throw new ProviderInvocationException("demo_provider", "上游失败 code=500");
                }));
        assertTrue(ex.getMessage().contains("重试 2 次后仍失败"), "实际消息: " + ex.getMessage());
    }

    @Test
    void deterministicFailureIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        NonRetryableProviderException original = new NonRetryableProviderException(
                "demo_provider", "返回失败状态: STATUS_URL_ERROR / 非永久链接");

        NonRetryableProviderException thrown = assertThrows(NonRetryableProviderException.class,
                () -> stage().invoke(ctx(3), () -> {
                    calls.incrementAndGet();
                    throw original;
                }));

        assertSame(original, thrown);
        assertEquals(1, calls.get(), "确定性失败不该白耗供应商 DPS");
    }

    @Test
    void rateLimitedIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        RateLimitedException original = new RateLimitedException("demo_provider", "REJECT", 0.5d);

        RateLimitedException thrown = assertThrows(RateLimitedException.class,
                () -> stage().invoke(ctx(3), () -> {
                    calls.incrementAndGet();
                    throw original;
                }));

        assertSame(original, thrown);
        assertEquals(1, calls.get(), "限流拒绝是本服务保护动作，重试只会加剧排队");
    }

    @Test
    void retryWrapsRateLimitSoEachRetryAcquiresAToken() {
        // 装配顺序：数值小的在外层。Retry 在外 ⇒ 每次重试都重新过 RateLimit 领令牌，
        // 这正是"重试要占用供应商 DPS 配额"的契约；反过来就会白嫖额度。
        assertTrue(ProviderInvocationStage.Stages.RETRY < ProviderInvocationStage.Stages.RATE_LIMIT,
                "Retry 必须在 RateLimit 外层");
        assertTrue(ProviderInvocationStage.Stages.BULKHEAD < ProviderInvocationStage.Stages.CIRCUIT_BREAKER);
        assertTrue(ProviderInvocationStage.Stages.CIRCUIT_BREAKER < ProviderInvocationStage.Stages.RETRY);
        assertTrue(ProviderInvocationStage.Stages.RATE_LIMIT < ProviderInvocationStage.Stages.TIMEOUT,
                "Timeout 必须最内层：每次尝试各自限时");
        assertEquals(ProviderInvocationStage.Stages.RETRY, stage().order());
    }

    @Test
    void disabledPipelinePassesThroughWithoutRetry() {
        RetryStage stage = stage();
        ReflectionTestUtils.setField(stage, "pipelineEnabledDefault", false);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(ProviderInvocationException.class, () -> stage.invoke(ctx(3), () -> {
            calls.incrementAndGet();
            throw new ProviderInvocationException("demo_provider", "上游失败 code=500");
        }));
        assertEquals(1, calls.get(), "总开关关闭时不重试");
    }
}
