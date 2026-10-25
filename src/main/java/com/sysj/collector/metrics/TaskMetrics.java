package com.sysj.collector.metrics;


import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class TaskMetrics {

    private final MeterRegistry registry;
    private final Counter taskSubmittedCounter;
    private final Counter taskCompletedCounter;
    private final Counter taskFailedCounter;
    private final AtomicInteger queueSizeGauge;

    /** 熔断状态迁移计数器缓存，键 = {@code from->to}。 */
    private final Map<String, Counter> circuitTransitionCounters = new ConcurrentHashMap<>();

    public TaskMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.taskSubmittedCounter = Counter.builder("task.submitted")
                .description("提交的任务数")
                .register(registry);

        this.taskCompletedCounter = Counter.builder("task.completed")
                .description("完成的任务数")
                .register(registry);

        this.taskFailedCounter = Counter.builder("task.failed")
                .description("失败的任务数")
                .register(registry);

        this.queueSizeGauge = new AtomicInteger(0);
        Gauge.builder("task.queue.size", queueSizeGauge, AtomicInteger::get)
                .description("队列中的任务数")
                .register(registry);
    }

    public void recordTaskSubmitted() {
        taskSubmittedCounter.increment();
    }

    public void recordTaskCompleted() {
        taskCompletedCounter.increment();
    }

    public void recordTaskFailed() {
        taskFailedCounter.increment();
    }

    public void setQueueSize(int size) {
        queueSizeGauge.set(size);
    }

    /**
     * 熔断器状态迁移计数（2026-10-11 新增）。
     *
     * <p>此前 {@code CircuitStateChangedEvent} 被发布但全仓库无监听者（死扩展点），
     * 现在由 {@code CircuitStateChangedListener} 消费并打点，告警规则可以直接挂
     * {@code circuit.state.changes{from="CLOSED",to="OPEN"}} 而不用去数日志。
     *
     * @param from 迁移前状态名（CLOSED / OPEN / HALF_OPEN）
     * @param to   迁移后状态名
     */
    public void recordCircuitTransition(String from, String to) {
        String key = from + "->" + to;
        circuitTransitionCounters.computeIfAbsent(key, k -> Counter.builder("circuit.state.changes")
                .description("熔断器状态迁移次数")
                .tag("from", from)
                .tag("to", to)
                .register(registry)).increment();
    }
}
