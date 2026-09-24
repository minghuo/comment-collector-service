package com.sysj.collector.exception;

/**
 * Bulkhead 舱壁拒绝：该供应商的并发调用数已达上限（§8.1，借鉴 B-20）。
 *
 * <p><b>刻意快速失败而不是排队等待</b>：Bulkhead 的目的就是"防止某一个下游把线程耗光"，
 * 如果在这里排队，等待的线程同样是线程，问题只是从"下游慢"变成"队列长"。
 * 因此取不到许可立即抛出，让门面把这个候选记为失败并**切换到下一个候选**，
 * 压力由路由层转移，而不是堆在这里。
 *
 * <p>注意：本异常计入供应商失败统计（会喂给熔断器），因此**不要**把它当成"服务过载"的调用方错误 ——
 * 它描述的是"这个供应商此刻太挤"，属于供应商侧事实。
 */
public class BulkheadFullException extends ProviderInvocationException {

    /** 当前在途调用数。 */
    private final int active;
    /** 配置的并发上限。 */
    private final int maxConcurrency;

    public BulkheadFullException(String providerKey, int active, int maxConcurrency) {
        super(providerKey, "供应商 [" + providerKey + "] 并发已达上限（在途 " + active
                + "，上限 " + maxConcurrency + "），不再排队");
        this.active = active;
        this.maxConcurrency = maxConcurrency;
    }

    public int getActive() {
        return active;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }
}
