package com.sysj.collector.core.pipeline;

import com.sysj.collector.exception.ProviderInvocationException;
import com.sysj.collector.exception.ProviderTimeoutException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 重试（§8.1，借鉴 B-19/B-04）。
 *
 * <p>原先是独立工具类 {@code RetryableProviderExecutor}，P2-8 把它的逻辑收进装饰器链并删除了那个类 ——
 * 同一件事有两处实现，迟早会出现"只改了一处"的偏差（C-12 的教训）。
 *
 * <p>退避仍是指数式 {@code base × 2^(n-1)}（默认 500/1000/2000ms，base 可配）。
 * 单供应商的退避策略配置化属 P3-5，这里只提供全局基数。
 *
 * <h3>为什么 Retry 在 Timeout 外层</h3>
 * <p>设计文档 §8.1 的旧图把 Timeout 画在 Retry 外面（整链一个预算），但 §9.5 的数值口径是
 * "单次调用超时 = {@code providers[].timeout_ms}"、且明确给出
 * {@code 子任务整体 = 单次调用超时 × (max_retry+1) + 退避总和} —— 那个公式只有在
 * **每次尝试各自限时**时才成立。P2-8 按 §9.5 实现（Retry 在外、Timeout 在内），
 * 同时在本阶段加一条**整链墙钟预算**（同一个公式）兜住"重试链无限拖长"，
 * 使两条文档陈述都成立、且调用方等待时间有硬上界。§8.1 的图已同步改正。
 */
@Slf4j
@Component
public class RetryStage implements ProviderInvocationStage {

    /** 管线总开关（压测时逐项隔离用，见 §9.2）。 */
    @Value("${collector.pipeline.enabled:true}")
    private boolean pipelineEnabled;

    /** 退避基数（毫秒）：第 n 次重试等待 {@code base × 2^(n-1)}。 */
    @Value("${collector.pipeline.retry-backoff-base-ms:500}")
    private long backoffBaseMs;

    @Override
    public int order() {
        return Stages.RETRY;
    }

    @Override
    public String name() {
        return "Retry";
    }

    @Override
    public <T> T invoke(InvocationContext ctx, Supplier<T> next) {
        if (!pipelineEnabled) {
            return next.get();
        }

        long budget = ctx.totalBudgetMs();
        long deadline = budget <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + budget;
        RuntimeException last = null;

        for (int attempt = 0; attempt <= ctx.maxRetry(); attempt++) {
            if (attempt > 0) {
                long delay = backoffBaseMs * (1L << Math.min(attempt - 1, 20));
                if (System.currentTimeMillis() + delay > deadline) {
                    throw ProviderTimeoutException.totalBudget(ctx.providerKey(), budget, attempt);
                }
                log.warn("重试 {}/{}: provider={} 等待{}ms", attempt, ctx.maxRetry(), ctx.providerKey(), delay);
                sleep(delay, ctx);
            }

            try {
                return next.get();
            } catch (RuntimeException e) {
                last = e;
                log.warn("调用失败 attempt={}/{}: provider={} error={}",
                        attempt, ctx.maxRetry(), ctx.providerKey(), e.getMessage());
                // 只在"还有重试可做"时才把预算耗尽单独报出来：maxRetry=0 时没有重试链，
                // 此时单次超时的异常本身就是最准确的消息，再包一层"总预算耗尽"只会误导排障。
                if (ctx.maxRetry() > 0 && System.currentTimeMillis() >= deadline) {
                    throw ProviderTimeoutException.totalBudget(ctx.providerKey(), budget, attempt + 1);
                }
            }
        }

        // 单次调用超时的异常本身就是可诊断的（含 providerKey 与阈值），不必再包一层
        if (ctx.maxRetry() == 0 || last == null) {
            throw last != null ? last : new ProviderInvocationException(ctx.providerKey(),
                    "供应商 [" + ctx.providerKey() + "] 未执行任何尝试");
        }
        throw new ProviderInvocationException(ctx.providerKey(),
                "供应商 [" + ctx.providerKey() + "] 重试 " + ctx.maxRetry() + " 次后仍失败: " + last.getMessage(),
                last);
    }

    /**
     * 退避等待。
     *
     * <p>被中断时**不重试**：中断意味着"服务在停机或任务被取消"，继续重试只会拖住停机。
     */
    private void sleep(long delay, InvocationContext ctx) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ProviderInvocationException(ctx.providerKey(),
                    "供应商 [" + ctx.providerKey() + "] 重试等待被中断", ie);
        }
    }
}
