package com.sysj.collector.facade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisHealthMonitor} 状态机单元测试：HEALTHY → DEGRADED → HEALTHY 切换。
 * （探活 probe 需要 Redis，不在单测范围；这里验证标记逻辑与降级时长口径。）
 */
class RedisHealthMonitorTest {

    @Test
    void startsHealthy() {
        RedisHealthMonitor monitor = new RedisHealthMonitor(null);
        assertTrue(monitor.isAvailable());
        assertEquals(0, monitor.degradedDurationMs());
    }

    @Test
    void markFailureEntersDegradedAndIsIdempotent() {
        RedisHealthMonitor monitor = new RedisHealthMonitor(null);
        monitor.markFailure("enqueue", new RuntimeException("connection refused"));
        assertFalse(monitor.isAvailable());
        long duration1 = monitor.degradedDurationMs();
        assertTrue(duration1 >= 0);

        // 重复失败不重置降级起点（duration 只增不减）
        monitor.markFailure("readNew", new RuntimeException("timeout"));
        assertFalse(monitor.isAvailable());
        assertTrue(monitor.degradedDurationMs() >= duration1);
    }

    @Test
    void markSuccessRecovers() {
        RedisHealthMonitor monitor = new RedisHealthMonitor(null);
        monitor.markFailure("enqueue", new RuntimeException("connection refused"));
        assertFalse(monitor.isAvailable());

        monitor.markSuccess();
        assertTrue(monitor.isAvailable());
        assertEquals(0, monitor.degradedDurationMs());

        // 恢复后再次失败可重新进入降级
        monitor.markFailure("ackAndDelete", new RuntimeException("broken pipe"));
        assertFalse(monitor.isAvailable());
    }

    @Test
    void markSuccessWhenHealthyIsNoOp() {
        RedisHealthMonitor monitor = new RedisHealthMonitor(null);
        monitor.markSuccess();
        assertTrue(monitor.isAvailable());
    }
}
