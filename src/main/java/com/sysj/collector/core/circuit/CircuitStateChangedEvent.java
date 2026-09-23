package com.sysj.collector.core.circuit;

import java.time.Instant;

/**
 * 熔断状态迁移事件 `[借鉴 B-21]`。
 *
 * <p>由 {@link ProviderCircuitBreaker} 在状态真正发生变化时发布，
 * 供告警、指标、运维面板等旁路消费；发布方不感知任何监听者。
 *
 * @param supplierKey {@code platform:feature:providerKey}
 * @param from        迁移前状态
 * @param to          迁移后状态
 * @param reason      触发原因（人类可读，便于排障）
 * @param occurredAt  发生时间
 */
public record CircuitStateChangedEvent(
        String supplierKey,
        CircuitState from,
        CircuitState to,
        String reason,
        Instant occurredAt) {
}
