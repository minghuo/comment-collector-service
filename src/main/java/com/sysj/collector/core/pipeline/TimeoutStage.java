package com.sysj.collector.core.pipeline;

import com.sysj.collector.exception.ProviderInvocationException;
import com.sysj.collector.exception.ProviderTimeoutException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 单次调用限时（§9.5，借鉴 B-03）。
 *
 * <p>实现方式：把调用丢到工作线程上，调用方 {@code Future.get(timeout)}。到点后 {@code cancel(true)}。
 *
 * <h3>它保证了什么、没保证什么（必读）</h3>
 * <ul>
 *   <li><b>保证</b>：调用方（消费者线程 / 同步请求线程）一定在 {@code timeout_ms} 内返回，
 *       于是可以立刻切换到下一个候选供应商，整条任务不会因为一个卡死的下游而永久挂住。</li>
 *   <li><b>不保证</b>：底层网络请求被真的终止。{@code http-client-utils} 用的是 OkHttp，
 *       线程中断对阻塞中的 socket 读**不可靠** —— 默认 {@code callTimeout=60s}
 *       才是网络层真正的截止线。因此被中断的调用可能继续占用工作线程直到它自己超时。</li>
 *   <li><b>结论</b>：本线程池用 {@code SynchronousQueue}（不排队）+ 有上限的线程数。
 *       池满时**立即拒绝**而不是排队 —— 排队会让"限时"这个承诺失效（排队时间也计入调用方等待）。
 *       因此运维应保证 {@code timeout_ms ≤ OkHttp 的 callTimeout(默认 60s)}，
 *       并按 {@code 峰值速率 × 线程滞留时长} 估算池大小。</li>
 * </ul>
 *
 * <h3>副作用</h3>
 * <p>供应商实现代码在 {@code collector-provider-*} 线程上执行，不再是消费者线程。
 * 这对本项目无影响（供应商只用 HTTP + 局部变量，没有 ThreadLocal 依赖），但接入新供应商时要注意。
 */
@Slf4j
@Component
public class TimeoutStage implements ProviderInvocationStage {

    /** 管线总开关（压测时逐项隔离用，见 §9.2）。 */
    @Value("${collector.pipeline.enabled:true}")
    private boolean pipelineEnabled;

    /** 限时阶段单独开关。 */
    @Value("${collector.pipeline.timeout-enabled:true}")
    private boolean timeoutEnabled;

    /** 限时工作线程数；&lt;=0 表示按 CPU 自动（availableProcessors × 8，下限 64）。 */
    @Value("${collector.pipeline.timeout-threads:0}")
    private int configuredThreads;

    private ThreadPoolExecutor executor;
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();

    @PostConstruct
    public void init() {
        int threads = configuredThreads > 0
                ? configuredThreads
                : Math.max(64, Runtime.getRuntime().availableProcessors() * 8);
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "collector-provider-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        // corePoolSize=0 + SynchronousQueue：有活就新建线程（直到 max），没活就回收；**不排队**
        this.executor = new ThreadPoolExecutor(0, threads, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), factory, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
        log.info("调用限时线程池: maxThreads={}（{}）队列=不排队",
                threads, configuredThreads > 0 ? "来自配置" : "按 CPU 自动");
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public int order() {
        return Stages.TIMEOUT;
    }

    @Override
    public String name() {
        return "Timeout";
    }

    @Override
    public <T> T invoke(InvocationContext ctx, Supplier<T> next) {
        if (!pipelineEnabled || !timeoutEnabled || ctx.timeoutMs() <= 0) {
            return next.get();
        }

        Future<T> future;
        try {
            future = executor.submit(next::get);
        } catch (RejectedExecutionException ree) {
            rejected.incrementAndGet();
            throw new ProviderInvocationException(ctx.providerKey(),
                    "供应商 [" + ctx.providerKey() + "] 调用被拒：限时线程池已满（maxThreads="
                            + executor.getMaximumPoolSize() + "，活跃 " + executor.getActiveCount()
                            + "）—— 通常是下游卡死导致线程滞留，请检查该供应商的真实响应时间", ree);
        }

        try {
            return future.get(ctx.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            // 中断只能"尽力而为"：OkHttp 不响应中断时工作线程会继续占着，直到它自己的 callTimeout
            future.cancel(true);
            timedOut.incrementAndGet();
            throw ProviderTimeoutException.singleCall(ctx.providerKey(), ctx.timeoutMs());
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderInvocationException(ctx.providerKey(),
                    "供应商 [" + ctx.providerKey() + "] 调用被中断（服务正在停机或任务已取消）", ie);
        } catch (ExecutionException ee) {
            throw unwrap(ctx, ee);
        }
    }

    /** 把工作线程里的异常还原成调用方应该看到的异常。 */
    private RuntimeException unwrap(InvocationContext ctx, ExecutionException ee) {
        Throwable cause = ee.getCause() == null ? ee : ee.getCause();
        if (cause instanceof RuntimeException re) {
            return re;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        return new ProviderInvocationException(ctx.providerKey(),
                "供应商 [" + ctx.providerKey() + "] 调用失败: " + cause.getMessage(), cause);
    }

    // ── 观测 ───────────────────────────────────────────────────────────────

    /** 因线程池满被拒绝的次数。 */
    public long rejectedCount() {
        return rejected.get();
    }

    /** 单次调用超时的次数。 */
    public long timeoutCount() {
        return timedOut.get();
    }

    /** 当前活跃（含滞留）的工作线程数。 */
    public int activeCount() {
        return executor == null ? 0 : executor.getActiveCount();
    }

    /** 线程池上限。 */
    public int maxThreads() {
        return executor == null ? 0 : executor.getMaximumPoolSize();
    }
}
