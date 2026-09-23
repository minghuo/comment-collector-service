package com.sysj.collector.core.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 支持"出队时重算优先级"的阻塞优先队列。
 *
 * <h3>为什么不直接用 {@link java.util.concurrent.PriorityBlockingQueue}</h3>
 * {@code PriorityBlockingQueue} 只在插入/删除时堆化：元素在队列中等待期间，
 * 即使它的优先级字段被 aging 提升，堆内位置也不会变化 —— 防饥饿机制因此完全失效。
 *
 * <p>本实现把任务放在普通列表中，**每次出队时按当前 {@link Prioritized#effectivePriority()} 现算最优先者**，
 * 优先级变化立刻生效（队列规模是"等待中的采集任务"，量级可控，扫描成本远低于一次 HTTP 调用）。
 *
 * <h3>阻塞与唤醒</h3>
 * 用 {@link Semaphore} 做唤醒信号：{@link #offer} 释放一个许可，{@link #take} 先取许可再取任务。
 * 许可数可能多于实际任务数（{@link #poll()} 直接取走任务时不会消耗许可），
 * 因此 {@link #take} 采用"取到许可后仍取不到任务就继续等"的循环，不会误返回 null。
 *
 * <h3>容量与背压（修正 C-19）</h3>
 * 支持有界容量：构造时给定 {@code capacity > 0} 即为有界，{@link #offer} 在满时**返回 false 而不是阻塞或无限增长**，
 * 由调用方据此拒绝请求（入口返回 503）。{@code capacity <= 0} 表示无界（保留旧行为，仅用于测试）。
 * 原先队列无界，`collector.task.queue-capacity` 因此是个给出"有背压"错觉的死配置。
 *
 * @param <T> 任务类型
 */
public class PriorityTaskQueue<T extends Prioritized> {

    /** 出队顺序：有效优先级升序，其次入队序号升序。 */
    private static final Comparator<Prioritized> ORDER =
            Comparator.comparingInt(Prioritized::effectivePriority)
                    .thenComparingLong(Prioritized::enqueueSeq);

    private final List<T> items = new ArrayList<>();

    private final Semaphore available = new Semaphore(0);

    /** 剩余容量许可；无界时为 null。 */
    private final Semaphore slots;

    /** 容量上限；<=0 表示无界。 */
    private final int capacity;

    private final AtomicLong sequence = new AtomicLong(0);

    /** 无界队列（测试用）。 */
    public PriorityTaskQueue() {
        this(0);
    }

    /**
     * @param capacity 容量上限；&lt;=0 表示无界
     */
    public PriorityTaskQueue(int capacity) {
        this.capacity = capacity;
        this.slots = capacity > 0 ? new Semaphore(capacity) : null;
    }

    /** 取下一个入队序号（调用方在构造/入队前领取）。 */
    public long nextSeq() {
        return sequence.incrementAndGet();
    }

    /**
     * 入队并唤醒一个等待中的消费者（**非原子**的便捷形式：先预留 1 个名额再入队）。
     *
     * <p>需要"整批一起决定受理与否"的场景请用 {@link #tryReserve(int)} + {@link #offerReserved}，
     * 否则并发下会出现"先检查后入队"的竞态。
     *
     * @return {@code true} 入队成功；{@code false} 队列已满（未入队，调用方应据此拒绝该请求）
     */
    public boolean offer(T task) {
        if (!tryReserve(1)) {
            return false;
        }
        offerReserved(task);
        return true;
    }

    /**
     * 原子预留 {@code n} 个名额。
     *
     * <p>用于"整批受理或整批拒绝"：并发提交时若各自先查 {@code remainingCapacity()} 再入队，
     * 所有请求都会看到还有余量而放行（TOCTOU），过载保护形同虚设。
     *
     * @return {@code true} 已预留 n 个；{@code false} 余量不足，**一个也没有预留**
     */
    public boolean tryReserve(int n) {
        if (n <= 0) {
            return true;
        }
        if (slots == null) {
            return true;
        }
        return slots.tryAcquire(n);
    }

    /** 归还 {@code n} 个未被使用的预留名额（配合 {@link #tryReserve} 的 try/finally 使用）。 */
    public void release(int n) {
        if (slots != null && n > 0) {
            slots.release(n);
        }
    }

    /**
     * 使用已预留的名额入队（不再获取许可）。
     *
     * <p>调用前必须已通过 {@link #tryReserve(int)} 预留对应数量的名额。
     */
    public void offerReserved(T task) {
        synchronized (items) {
            items.add(task);
        }
        available.release();
    }

    /**
     * 阻塞取出当前最优先的任务（按 {@link Prioritized#effectivePriority()} 现算）。
     */
    public T take() throws InterruptedException {
        while (true) {
            available.acquire();
            T task = poll();
            if (task != null) {
                return task;
            }
        }
    }

    /** 非阻塞取出当前最优先的任务；队列为空返回 null。 */
    public T poll() {
        synchronized (items) {
            return removeBest(items, null);
        }
    }

    /**
     * 非阻塞取出**满足条件**的任务中最优先的一个；无匹配返回 null。
     *
     * <p>公平配额用它来"优先捞一个低优先级任务"。
     */
    public T pollIf(Predicate<T> predicate) {
        synchronized (items) {
            return removeBest(items, predicate);
        }
    }

    /** 当前队列快照（用于 aging 扫描与构成统计）。 */
    public List<T> snapshot() {
        synchronized (items) {
            return new ArrayList<>(items);
        }
    }

    /** 队列长度。 */
    public int size() {
        synchronized (items) {
            return items.size();
        }
    }

    /** 容量上限；&lt;=0 表示无界。 */
    public int capacity() {
        return capacity;
    }

    /**
     * 剩余可入队数量。
     *
     * @return 无界队列返回 {@link Integer#MAX_VALUE}
     */
    public int remainingCapacity() {
        return slots == null ? Integer.MAX_VALUE : slots.availablePermits();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 返回满足条件（predicate 为 null 时表示全部）的最优先元素并移除。
     */
    @SuppressWarnings("unchecked")
    private T removeBest(List<T> list, Predicate<T> predicate) {
        T best = null;
        for (T t : list) {
            if (predicate != null && !predicate.test(t)) {
                continue;
            }
            if (best == null || ORDER.compare(t, best) < 0) {
                best = t;
            }
        }
        if (best != null) {
            list.remove(best);
            if (slots != null) {
                // 归还容量许可：漏掉这一步会让有界队列"只出不进"，可用容量单调递减
                slots.release();
            }
        }
        return best;
    }
}
