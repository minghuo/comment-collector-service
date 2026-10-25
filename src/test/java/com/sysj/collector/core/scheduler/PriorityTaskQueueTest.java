package com.sysj.collector.core.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PriorityTaskQueue} 单元测试：有界容量、原子预留/归还、出队时按当前有效优先级现算、
 * 阻塞出队唤醒。2026-10-11 起两个信号量均为公平模式，已删除无调用方的 {@code pollIf}。
 */
class PriorityTaskQueueTest {

    /** 测试任务：有效优先级 = basePriority - boost。 */
    private static final class Task implements Prioritized {
        private final String name;
        private final int basePriority;
        private final long seq;
        private volatile int boost;

        private Task(String name, int basePriority, long seq) {
            this.name = name;
            this.basePriority = basePriority;
            this.seq = seq;
        }

        @Override
        public int effectivePriority() {
            return Math.max(0, basePriority - boost);
        }

        @Override
        public long enqueueSeq() {
            return seq;
        }
    }

    @Test
    void boundedQueueRejectsWhenFull() {
        PriorityTaskQueue<Task> queue = new PriorityTaskQueue<>(2);
        assertTrue(queue.tryReserve(2));
        assertFalse(queue.tryReserve(1), "容量应已耗尽");
        queue.offerReserved(new Task("a", 10, queue.nextSeq()));
        queue.offerReserved(new Task("b", 10, queue.nextSeq()));
        assertEquals(2, queue.size());
        assertEquals(0, queue.remainingCapacity());
    }

    @Test
    void reserveThenReleaseReturnsCapacity() {
        PriorityTaskQueue<Task> queue = new PriorityTaskQueue<>(1);
        assertTrue(queue.tryReserve(1));
        assertFalse(queue.tryReserve(1));
        queue.release(1);
        assertTrue(queue.tryReserve(1));
    }

    @Test
    void pollReturnsHighestEffectivePriority() {
        PriorityTaskQueue<Task> queue = new PriorityTaskQueue<>(0);
        Task low = new Task("low", 100, queue.nextSeq());
        Task high = new Task("high", 10, queue.nextSeq());
        queue.offerReserved(low);
        queue.offerReserved(high);

        assertSameTask(high, queue.poll());
        assertSameTask(low, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    void priorityChangeAppliesOnDequeue() {
        PriorityTaskQueue<Task> queue = new PriorityTaskQueue<>(0);
        Task a = new Task("a", 10, queue.nextSeq());
        Task b = new Task("b", 20, queue.nextSeq());
        queue.offerReserved(a);
        queue.offerReserved(b);

        // b 在队列中等待时提升优先级（20-15=5 优于 a 的 10）：出队现算，立即可见（PriorityBlockingQueue 做不到）
        b.boost = 15;
        assertSameTask(b, queue.poll());
        assertSameTask(a, queue.poll());
    }

    @Test
    void samePriorityFifoByEnqueueSeq() {
        PriorityTaskQueue<Task> queue = new PriorityTaskQueue<>(0);
        Task first = new Task("first", 10, queue.nextSeq());
        Task second = new Task("second", 10, queue.nextSeq());
        queue.offerReserved(second);
        queue.offerReserved(first);

        assertSameTask(first, queue.poll());
        assertSameTask(second, queue.poll());
    }

    @Test
    void pollReleasesCapacityPermit() {
        // 有界队列"只出不进"回归：取走任务后容量许可必须归还
        PriorityTaskQueue<Task> bounded = new PriorityTaskQueue<>(1);
        assertTrue(bounded.tryReserve(1));
        bounded.offerReserved(new Task("x", 1, bounded.nextSeq()));
        assertEquals(0, bounded.remainingCapacity());
        assertSameTask(bounded.snapshot().get(0), bounded.poll());
        assertTrue(bounded.tryReserve(1), "取走任务后容量许可应已归还");
    }

    @Test
    void takeBlocksUntilOfferThenReturnsTask() throws Exception {
        PriorityTaskQueue<Task> queue = new PriorityTaskQueue<>(0);
        Task expected = new Task("only", 10, queue.nextSeq());

        Thread consumer = new Thread(() -> {
            try {
                Task taken = queue.take();
                assertEquals(expected.name, taken == null ? null : taken.name);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        Thread.sleep(50);
        queue.offerReserved(expected);
        consumer.join(2000);
        assertFalse(consumer.isAlive(), "take() 应在 offer 后被唤醒返回");
        assertTrue(queue.isEmpty());
    }

    private static void assertSameTask(Task expected, Task actual) {
        assertEquals(expected.name, actual == null ? null : actual.name);
    }
}
