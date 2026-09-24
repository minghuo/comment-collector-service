package com.sysj.collector.core.pipeline;

import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;
import com.sysj.collector.model.CommentCollectRequest;

/**
 * 一次供应商调用的不可变上下文（§8.1/§9.2）。
 *
 * <p>管线中的每个阶段都是**无状态单例**，所有"本次调用的差异"都从这个 record 里读 ——
 * 这样阶段可以安全地被多线程共享，也不会把配置缓存在阶段里（配置刷新后立即生效）。
 *
 * <p>数值解析口径（务必与设计文档 §9.5 保持一致）：
 * <ul>
 *   <li>{@code timeoutMs} = 单个供应商**一次尝试**的墙钟上限；优先取
 *       {@code providers[].timeout_ms}，缺失时取全局 {@code collector.pipeline.timeout-ms}。</li>
 *   <li>{@code maxConcurrency} = 该供应商的**在途调用上限**（Bulkhead）；0 表示不限。</li>
 *   <li>{@code maxRetry} = 额外重试次数（0 = 只调用一次），来自 {@code providers[].max_retry}。</li>
 * </ul>
 *
 * @param request         原始采集请求（供应商实现需要它）
 * @param platformCode    平台码
 * @param featureCode     功能码
 * @param provider        该供应商的 DB 配置（速率、重试、并发、超时、流控效果都在这里）
 * @param timeoutMs       单次尝试超时（毫秒）；&lt;=0 表示不限时
 * @param maxConcurrency  并发上限；&lt;=0 表示不限
 * @param maxRetry        额外重试次数
 */
public record InvocationContext(
        CommentCollectRequest request,
        String platformCode,
        String featureCode,
        ProviderConfig provider,
        long timeoutMs,
        int maxConcurrency,
        int maxRetry) {

    /** 供应商 Bean 名。 */
    public String providerKey() {
        return provider.getProviderKey();
    }

    /** 供应商运行时状态 key：{@code platform:feature:provider}，与 {@code supplier_state.supplier_key} 同构。 */
    public String supplierKey() {
        return platformCode + ":" + featureCode + ":" + provider.getProviderKey();
    }

    /**
     * 整条重试链的墙钟预算（毫秒）—— 设计文档 §9.5 的公式：
     * {@code 单次调用超时 × (max_retry + 1) + 退避总和}。
     *
     * <p>退避总和与 {@link RetryStage} 的退避实现保持同一份公式（500ms × 2^(n-1)），
     * 因此这里只算一次：{@code 500 × (2^maxRetry - 1)}。
     */
    public long totalBudgetMs() {
        if (timeoutMs <= 0) {
            return 0L;
        }
        long backoff = 500L * ((1L << Math.min(maxRetry, 20)) - 1L);
        return timeoutMs * (maxRetry + 1L) + backoff;
    }
}
