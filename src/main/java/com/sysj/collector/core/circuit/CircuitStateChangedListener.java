package com.sysj.collector.core.circuit;

import com.sysj.collector.metrics.TaskMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 熔断状态迁移事件的观测监听器（2026-10-11 新增）。
 *
 * <h3>为什么加这个类</h3>
 * {@link CircuitStateChangedEvent} 由 {@link ProviderCircuitBreaker#publishTransition} 发布，
 * 但此前**全仓库没有任何 {@code @EventListener}** —— 事件发布后即被丢弃，是个死扩展点，
 * 外部告警只能去 grep 日志（日志行还会被日志级别变更或降噪改动静默掉）。
 * 本监听器把迁移打成 Micrometer 计数器
 * {@code circuit.state.changes{from=...,to=...}}，告警规则与面板可直接挂载。
 *
 * <p>人类可读的迁移日志仍由 {@link ProviderCircuitBreaker} 打印（含冷却时间等上下文），
 * 本类**不重复打日志**，只在 DEBUG 级别留一行便于排查"计数与日志对不上"的情况。
 *
 * <p>监听器是旁路：打点失败（如注册表已关闭）绝不影响采集主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitStateChangedListener {

    private final TaskMetrics taskMetrics;

    @EventListener
    public void onStateChanged(CircuitStateChangedEvent event) {
        try {
            taskMetrics.recordCircuitTransition(event.from().name(), event.to().name());
            if (log.isDebugEnabled()) {
                log.debug("熔断状态迁移已计数: key={} {}→{} reason={}",
                        event.supplierKey(), event.from(), event.to(), event.reason());
            }
        } catch (Exception e) {
            // 观测链路的问题不应冒泡到供应商调用
            log.warn("熔断状态迁移打点失败(非关键): key={} error={}", event.supplierKey(), e.getMessage());
        }
    }
}
