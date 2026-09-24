package com.sysj.collector.exception;

/**
 * 供应商调用超时（§9.5）。
 *
 * <p>两种语义共用一个异常类型，靠 {@link #isTotalBudget()} 区分：
 * <ul>
 *   <li><b>单次调用超时</b>（{@code totalBudget=false}）：一次 {@code fetchComments} 超过
 *       {@code providers[].timeout_ms}；这是可重试的，重试阶段会再给一次机会。</li>
 *   <li><b>整链总预算耗尽</b>（{@code totalBudget=true}）：重试链的墙钟预算用尽
 *       （{@code timeout_ms × (max_retry + 1) + 退避总和}，见 §9.5），不再重试。</li>
 * </ul>
 *
 * <p><b>重要事实（不要误以为它杀了底层调用）</b>：超时的实现是"把调用丢到工作线程上 {@code Future.get(timeout)}"，
 * 到点后 {@code cancel(true)}。这保证了<b>调用方</b>在 {@code timeout_ms} 内一定返回，
 * 但**不保证**底层 OkHttp 请求被立刻终止 —— {@code http-client-utils} 的默认
 * {@code callTimeout=60s} 才是网络层真正的截止线。详见设计文档 §8.1 的"超时能保证什么"。
 */
public class ProviderTimeoutException extends ProviderInvocationException {

    /** 超时阈值（毫秒）。 */
    private final long timeoutMs;
    /** true = 整链总预算耗尽；false = 单次调用超时。 */
    private final boolean totalBudget;

    private ProviderTimeoutException(String providerKey, String message, long timeoutMs, boolean totalBudget) {
        super(providerKey, message);
        this.timeoutMs = timeoutMs;
        this.totalBudget = totalBudget;
    }

    /** 单次调用超时。 */
    public static ProviderTimeoutException singleCall(String providerKey, long timeoutMs) {
        return new ProviderTimeoutException(providerKey,
                "供应商 [" + providerKey + "] 单次调用超时（> " + timeoutMs + "ms）",
                timeoutMs, false);
    }

    /** 整链总预算耗尽。 */
    public static ProviderTimeoutException totalBudget(String providerKey, long budgetMs, int attempts) {
        return new ProviderTimeoutException(providerKey,
                "供应商 [" + providerKey + "] 调用链总预算耗尽（" + budgetMs + "ms 内已尝试 " + attempts + " 次）",
                budgetMs, true);
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public boolean isTotalBudget() {
        return totalBudget;
    }
}
