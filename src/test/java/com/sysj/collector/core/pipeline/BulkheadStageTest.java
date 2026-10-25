package com.sysj.collector.core.pipeline;

import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;
import com.sysj.collector.domain.service.SystemConfigService;
import com.sysj.collector.exception.BulkheadFullException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BulkheadStage} 单元测试：REJECT 立即失败、THROTTLE_QUEUE 有界排队、
 * 全局上限封顶、开关关闭时不限制。
 *
 * <p>用真实线程占住许可来复现"并发已满"，不依赖 Spring 容器。
 */
class BulkheadStageTest {

    /** 构造一个只设了三个开关/上限字段的阶段实例（其余字段用不到）。 */
    private BulkheadStage stage(boolean bulkheadEnabled, long ceilingMs) {
        // 2026-10-11：BulkheadStage 改为"@Value 只作默认值 + system_config 同名键覆盖"，
        // 单测里用一个"原样返回默认值"的 SystemConfigService 替身即可（无 DB）。
        BulkheadStage stage = new BulkheadStage(passthroughConfigService());
        ReflectionTestUtils.setField(stage, "pipelineEnabledDefault", true);
        ReflectionTestUtils.setField(stage, "bulkheadEnabledDefault", bulkheadEnabled);
        ReflectionTestUtils.setField(stage, "bulkheadMaxQueueWaitMsDefault", ceilingMs);
        return stage;
    }

    /** system_config 替身：任何键都"不存在"，于是所有读取都退回传入的 properties 默认值。 */
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

    /** 并发上限固定为 1，方便用"一个占位线程"复现满载。 */
    private InvocationContext ctx(String flowEffect, Long maxQueueWaitMs) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProviderKey("demo_provider");
        provider.setFlowEffect(flowEffect);
        provider.setMaxQueueWaitMs(maxQueueWaitMs);
        return new InvocationContext(null, "demo", "comment", provider, 15_000L, 1, 2);
    }

    /**
     * 启动一个占住唯一许可的线程：进入临界区后阻塞，直到测试线程调用 release()。
     */
    private Thread occupier(BulkheadStage stage, InvocationContext ctx, CountDownLatch entered,
                            CountDownLatch release) {
        Thread t = new Thread(() -> stage.invoke(ctx, () -> {
            entered.countDown();
            awaitQuietly(release, 10_000L);
            return "held";
        }), "bulkhead-occupier");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void awaitQuietly(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void rejectEffectFailsImmediatelyEvenWhenProviderConfiguredToQueue() throws Exception {
        BulkheadStage stage = stage(true, 5000L);
        // provider 自己配了 30s 排队，但 flow_effect=REJECT ⇒ 不排队，直接拒绝
        InvocationContext ctx = ctx("REJECT", 30_000L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        occupier(stage, ctx, entered, release);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "占位线程应已进入临界区");

        long start = System.nanoTime();
        BulkheadFullException ex = assertThrows(BulkheadFullException.class,
                () -> stage.invoke(ctx, () -> "second"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(0L, ex.getWaitedMs(), "REJECT 不应排队");
        assertEquals(1, ex.getActive());
        assertEquals(1, ex.getMaxConcurrency());
        assertTrue(elapsedMs < 1000L, "应立即失败，实测 " + elapsedMs + "ms");
        assertEquals(1L, stage.rejectedCount());
        assertEquals(0L, stage.totalQueueWaitMillis());
        release.countDown();
    }

    @Test
    void throttleQueueWaitsForPermitThenSucceeds() throws Exception {
        BulkheadStage stage = stage(true, 5000L);
        InvocationContext ctx = ctx("THROTTLE_QUEUE", 5000L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        occupier(stage, ctx, entered, release);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "占位线程应已进入临界区");

        // 200ms 后释放许可：排队的调用应当等到并成功，而不是被拒绝
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        releaser.setDaemon(true);
        releaser.start();

        long start = System.nanoTime();
        String result = assertDoesNotThrow(() -> stage.invoke(ctx, () -> "second"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals("second", result);
        assertTrue(elapsedMs >= 150L, "应等到许可，实测仅 " + elapsedMs + "ms");
        assertEquals(0L, stage.queueTimeoutCount());
        assertTrue(stage.totalQueueWaitMillis() >= 150L, "排队耗时应被计入");
        assertEquals(0L, stage.rejectedCount());
    }

    @Test
    void queueWaitIsCappedByGlobalCeiling() throws Exception {
        BulkheadStage stage = stage(true, 200L);
        // provider 要求排 30s，但全局上限 200ms 必须封顶
        InvocationContext ctx = ctx("THROTTLE_QUEUE", 30_000L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        occupier(stage, ctx, entered, release);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "占位线程应已进入临界区");

        long start = System.nanoTime();
        BulkheadFullException ex = assertThrows(BulkheadFullException.class,
                () -> stage.invoke(ctx, () -> "second"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(200L, ex.getWaitedMs(), "排队时长应被全局上限封顶");
        assertTrue(elapsedMs >= 150L && elapsedMs < 2000L, "应在约 200ms 后放弃，实测 " + elapsedMs + "ms");
        assertEquals(1L, stage.queueTimeoutCount());
        assertTrue(stage.totalQueueWaitMillis() >= 150L);
        assertEquals(1L, stage.rejectedCount());
        release.countDown();
    }

    @Test
    void ceilingZeroDisablesQueueingEvenForThrottleQueue() throws Exception {
        BulkheadStage stage = stage(true, 0L);
        InvocationContext ctx = ctx("THROTTLE_QUEUE", 30_000L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        occupier(stage, ctx, entered, release);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "占位线程应已进入临界区");

        BulkheadFullException ex = assertThrows(BulkheadFullException.class,
                () -> stage.invoke(ctx, () -> "second"));
        assertEquals(0L, ex.getWaitedMs());
        assertEquals(0L, stage.totalQueueWaitMillis());
        release.countDown();
    }

    @Test
    void disabledBulkheadDoesNotLimitConcurrency() throws Exception {
        BulkheadStage stage = stage(false, 5000L);
        InvocationContext ctx = ctx("REJECT", null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        occupier(stage, ctx, entered, release);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "占位线程应已进入临界区");

        assertEquals("second", stage.invoke(ctx, () -> "second"));
        assertEquals(0L, stage.rejectedCount());
        release.countDown();
    }

    @Test
    void maxConcurrencyZeroMeansUnlimited() {
        BulkheadStage stage = stage(true, 5000L);
        ProviderConfig provider = new ProviderConfig();
        provider.setProviderKey("no_limit");
        provider.setFlowEffect("REJECT");
        // maxConcurrency = 0 ⇒ 不限并发，连占位都不需要
        InvocationContext ctx = new InvocationContext(null, "demo", "comment", provider, 15_000L, 0, 2);
        assertEquals("a", stage.invoke(ctx, () -> "a"));
        assertEquals("b", stage.invoke(ctx, () -> "b"));
        assertEquals(0L, stage.rejectedCount());
    }

    @Test
    void nullProviderQueueWaitFallsBackToCeilingInsteadOfFailing() throws Exception {
        BulkheadStage stage = stage(true, 150L);
        InvocationContext ctx = ctx("THROTTLE_QUEUE", null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        occupier(stage, ctx, entered, release);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "占位线程应已进入临界区");

        BulkheadFullException ex = assertThrows(BulkheadFullException.class,
                () -> stage.invoke(ctx, () -> "second"));
        // max_queue_wait_ms 缺失时退回全局上限（而不是当成"不排队"）
        assertEquals(150L, ex.getWaitedMs());
        release.countDown();
    }

    @Test
    void snapshotTracksInUsePerSupplier() throws Exception {
        BulkheadStage stage = stage(true, 5000L);
        InvocationContext ctx = ctx("REJECT", null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        occupier(stage, ctx, entered, release);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "占位线程应已进入临界区");

        assertEquals("1/1", stage.snapshot().get(ctx.supplierKey()));
        AtomicReference<String> afterRelease = new AtomicReference<>();
        release.countDown();
        Thread.sleep(200L);
        afterRelease.set(stage.snapshot().get(ctx.supplierKey()));
        assertEquals("0/1", afterRelease.get(), "释放后 inUse 应归零");
        assertNotNull(afterRelease.get());
        assertNull(stage.snapshot().get("不存在的供应商"));
    }
}
