package com.sysj.collector.core.circuit;

import com.sysj.collector.metrics.TaskMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link CircuitStateChangedListener} 单元测试：
 * 状态迁移事件被真正消费并打点（此前事件发布后无人接收）。
 */
class CircuitStateChangedListenerTest {

    private CircuitStateChangedEvent event(CircuitState from, CircuitState to, String reason) {
        return new CircuitStateChangedEvent("vivo_bbs:comment:vivo_bbs_local", from, to, reason, Instant.now());
    }

    @Test
    void transitionIsCountedWithFromToTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CircuitStateChangedListener listener = new CircuitStateChangedListener(new TaskMetrics(registry));

        listener.onStateChanged(event(CircuitState.CLOSED, CircuitState.OPEN, "失败率 60% 超过 50%"));
        listener.onStateChanged(event(CircuitState.CLOSED, CircuitState.OPEN, "失败率 70% 超过 50%"));
        listener.onStateChanged(event(CircuitState.OPEN, CircuitState.HALF_OPEN, "冷却期结束"));
        listener.onStateChanged(event(CircuitState.HALF_OPEN, CircuitState.CLOSED, "探测全部成功"));

        assertEquals(2.0, registry.get("circuit.state.changes")
                .tag("from", "CLOSED").tag("to", "OPEN").counter().count(), 0.0001);
        assertEquals(1.0, registry.get("circuit.state.changes")
                .tag("from", "OPEN").tag("to", "HALF_OPEN").counter().count(), 0.0001);
        assertEquals(1.0, registry.get("circuit.state.changes")
                .tag("from", "HALF_OPEN").tag("to", "CLOSED").counter().count(), 0.0001);
    }

    @Test
    void metricFailureDoesNotPropagate() {
        TaskMetrics metrics = mock(TaskMetrics.class);
        doThrow(new IllegalStateException("registry closed")).when(metrics).recordCircuitTransition(anyString(), anyString());

        CircuitStateChangedListener listener = new CircuitStateChangedListener(metrics);

        assertDoesNotThrow(() -> listener.onStateChanged(event(CircuitState.CLOSED, CircuitState.OPEN, "x")));
        verify(metrics).recordCircuitTransition("CLOSED", "OPEN");
    }
}
