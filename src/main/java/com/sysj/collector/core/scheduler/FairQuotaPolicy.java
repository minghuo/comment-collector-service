package com.sysj.collector.core.scheduler;

/**
 * 公平配额策略（纯计算，无状态，便于单测与验证）。
 *
 * <h3>规则</h3>
 * <ol>
 *   <li>每个消费轮次 = {@code batchSize} 次取任务；</li>
 *   <li>轮次开始时若"高优先级任务占比 ≥ {@code threshold}"，则本轮预留
 *       {@code ceil(batchSize × ratio)} 个名额给低优先级任务，否则整轮都按正常优先级取；</li>
 *   <li>预留名额只在**确实存在低优先级任务**时才生效：取不到就立刻放弃本轮剩余预留，
 *       改为正常优先级取任务 —— 避免"为了公平把高优先级饿死"。</li>
 * </ol>
 *
 * <p>高优先级的判定阈值 {@code highPriorityBound} 是可配置的，不再是代码里的魔数。
 */
public class FairQuotaPolicy {

    private final double ratio;

    private final double threshold;

    private final int highPriorityBound;

    private final int batchSize;

    public FairQuotaPolicy(double ratio, double threshold, int highPriorityBound, int batchSize) {
        this.ratio = ratio;
        this.threshold = threshold;
        this.highPriorityBound = highPriorityBound;
        this.batchSize = Math.max(1, batchSize);
    }

    /** 一个消费轮次的名额数。 */
    public int batchSize() {
        return batchSize;
    }

    /** 是否属于"高优先级"（有效优先级小于阈值）。 */
    public boolean isHighPriority(int effectivePriority) {
        return effectivePriority < highPriorityBound;
    }

    /** 高优先级判定阈值（供日志与状态接口展示）。 */
    public int highPriorityBound() {
        return highPriorityBound;
    }

    /**
     * 计算本轮应预留多少个"低优先级名额"。
     *
     * @param total     当前队列长度
     * @param highCount 其中高优先级任务数
     * @return 预留名额；0 表示本轮不启用公平配额
     */
    public int lowPrioritySlots(int total, int highCount) {
        if (total <= 0 || highCount <= 0) {
            return 0;
        }
        // 队列里清一色高优先级任务时无低优先级可让，不启用配额
        if (highCount >= total) {
            return 0;
        }
        if ((double) highCount / total < threshold) {
            return 0;
        }
        int slots = (int) Math.ceil(batchSize * ratio);
        return Math.max(1, Math.min(slots, batchSize));
    }
}
