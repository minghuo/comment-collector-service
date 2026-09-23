package com.sysj.collector.metrics;


import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class TaskMetrics {

    private final Counter taskSubmittedCounter;
    private final Counter taskCompletedCounter;
    private final Counter taskFailedCounter;
    private final AtomicInteger queueSizeGauge;

    public TaskMetrics(MeterRegistry registry) {
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
}
