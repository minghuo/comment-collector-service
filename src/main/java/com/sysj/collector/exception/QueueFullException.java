package com.sysj.collector.exception;

/**
 * 过载保护：异步任务队列名额不足，新提交被拒绝 `[借鉴 B-16]`。
 *
 * <p>由入口层转换为 **HTTP 503 Service Unavailable**（而不是让请求排队到 OOM）。
 * 触发点是 {@code CommentCollectionFacade#reserveCapacity(int)}（原子预留失败），
 * 容量来自 {@code collector.task.queue-capacity}（修正 C-19 的死配置）。
 *
 * <p>注意语义：拒绝的判据是**剩余名额不足**，不是"队列里有 N 个任务"。
 * 消费者是"取走任务后才开始慢速执行"，因此队列长度常常是 0 而名额已被在途任务占满 ——
 * 只报 {@code size} 会让人看到"0/2 却满了"这种费解的信息，所以同时带上 {@code remaining}。
 */
public class QueueFullException extends RuntimeException {

    /** 抛出时的队列长度（等待中的任务数，不含正在执行的任务）。 */
    private final int queueSize;

    /** 队列容量上限；&lt;=0 表示无界。 */
    private final int queueCapacity;

    /** 抛出时的剩余名额。 */
    private final int remaining;

    public QueueFullException(int queueSize, int queueCapacity, int remaining) {
        super(String.format("异步队列名额不足（剩余 %d，容量 %d，等待中 %d），请稍后重试",
                remaining, queueCapacity, queueSize));
        this.queueSize = queueSize;
        this.queueCapacity = queueCapacity;
        this.remaining = remaining;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public int getRemaining() {
        return remaining;
    }
}
