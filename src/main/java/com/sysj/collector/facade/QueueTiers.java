package com.sysj.collector.facade;

/**
 * 队列层级常量与判定：两级流实现优先级（阶段2 外置队列）。
 *
 * <p>原内存队列是"出队时现算有效优先级"的全序；外置到 Redis Stream 后流内只有 FIFO，
 * 优先级用**两条流**近似：用户等级优先级低于阈值 → HIGH 流，否则 LOW 流；
 * 跨流顺序由消费者按公平配额选择（HIGH 优先，LOW 保底防饿死）。
 * LOW 中等待过久的任务由 deadline 保障任务迁移到 HIGH（见 AsyncTaskConsumer 的维护扫描）。
 */
public final class QueueTiers {

    public static final String HIGH = "HIGH";
    public static final String LOW = "LOW";

    /**
     * 按用户等级优先级数值判定层级（数值越小越优先）。
     *
     * @param userPriority        用户等级优先级（NORMAL=100 / VIP=10 / ENTERPRISE=1，未知=999）
     * @param highPriorityBound   小于该值算高优先级（对应 collector.task.fair-quota-high-priority-bound）
     */
    public static String of(int userPriority, int highPriorityBound) {
        return userPriority < highPriorityBound ? HIGH : LOW;
    }

    private QueueTiers() {
    }
}
