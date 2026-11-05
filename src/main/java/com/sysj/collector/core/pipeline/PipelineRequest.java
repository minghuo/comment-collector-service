package com.sysj.collector.core.pipeline;

/**
 * 调用管线可接受的请求最小契约。
 *
 * <p>管线只依赖"平台码 + 功能码"两件事：它们决定
 * {@link InvocationContext#supplierKey()}（{@code platform:feature:provider}，与
 * {@code supplier_state.supplier_key} 同构）以及熔断/自适应限速的上报维度。
 * 具体业务字段由供应商实现自己读取。
 *
 * <p>因此管线不再绑定 {@code CommentCollectRequest}：评论采集（{@code CommentCollectRequest}）
 * 与账号历史（{@code AccountHistoryRequest}）等不同功能共用同一条
 * Bulkhead → CircuitBreaker → RateLimit → Retry → Timeout 链，
 * 新增功能只需让请求类实现本接口，不需要复制一份管线。
 */
public interface PipelineRequest {

    /** 平台编码（如 toutiao / douyin）。 */
    String getPlatformCode();

    /** 功能编码（如 comment / account_history）。 */
    String getFeatureCode();
}
