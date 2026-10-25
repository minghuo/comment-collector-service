package com.sysj.collector.exception;

/**
 * Bulkhead 舱壁拒绝：该供应商的并发调用数已达上限（§8.1，借鉴 B-20）。
 *
 * <p><b>默认刻意快速失败</b>：Bulkhead 的目的就是"防止某一个下游把线程耗光"，
 * 如果无界排队，等待的线程同样是线程，问题只是从"下游慢"变成"队列长"。
 * 因此 {@code flow_effect=REJECT} 时取不到许可立即抛出，让门面把这个候选记为失败并
 * **切换到下一个候选**，压力由路由层转移，而不是堆在这里。
 *
 * <p><b>但"有界排队"是允许的</b>：{@code flow_effect=THROTTLE_QUEUE} 的供应商会先排队等待
 * （上限见 {@code collector.pipeline.bulkhead-max-queue-wait-ms}），等满仍未取得许可才抛本异常。
 * 两种来源用 {@link #getWaitedMs()} 区分：{@code 0} = 未排队直接拒绝，{@code >0} = 排队到上限后拒绝。
 * 排队是为了不把纯本地的并发抖动记成上游失败 —— 那会既触发无谓重投，又污染熔断窗口。
 *
 * <p>注意：本异常计入供应商失败统计（会喂给熔断器），因此**不要**把它当成"服务过载"的调用方错误 ——
 * 它描述的是"这个供应商此刻太挤"，属于供应商侧事实。
 */
public class BulkheadFullException extends ProviderInvocationException {

    /** 当前在途调用数。 */
    private final int active;
    /** 配置的并发上限。 */
    private final int maxConcurrency;
    /** 本次允许的排队等待时长（毫秒）；0 表示未排队、直接拒绝。 */
    private final long waitedMs;

    /** 未排队、直接拒绝（{@code REJECT} 语义）。 */
    public BulkheadFullException(String providerKey, int active, int maxConcurrency) {
        this(providerKey, active, maxConcurrency, 0L);
    }

    /**
     * @param waitedMs 本次排队等待的上限（毫秒）；0 表示没有排队。
     */
    public BulkheadFullException(String providerKey, int active, int maxConcurrency, long waitedMs) {
        super(providerKey, "供应商 [" + providerKey + "] 并发已达上限（在途 " + active
                + "，上限 " + maxConcurrency + "）"
                + (waitedMs > 0L ? "，已排队等待 " + waitedMs + "ms 仍未取得许可" : "，不再排队"));
        this.active = active;
        this.maxConcurrency = maxConcurrency;
        this.waitedMs = Math.max(0L, waitedMs);
    }

    public int getActive() {
        return active;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    /** 本次排队等待的上限（毫秒）；0 = 未排队。 */
    public long getWaitedMs() {
        return waitedMs;
    }
}
