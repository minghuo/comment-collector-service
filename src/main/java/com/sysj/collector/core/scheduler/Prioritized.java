package com.sysj.collector.core.scheduler;

/**
 * 可参与优先级调度任务的抽象。
 *
 * <p>关键点：**优先级在出队时现算**（{@link #effectivePriority()} 是方法而非字段快照），
 * 这样 aging / deadline 提升能立即生效 —— 这是修复"队列元素优先级变更后不重排导致防饥饿失效"的核心。
 */
public interface Prioritized {

    /** 有效优先级，**数值越小越优先**。 */
    int effectivePriority();

    /** 入队序号，用于同优先级时的先来先服务（FIFO）。 */
    long enqueueSeq();
}
