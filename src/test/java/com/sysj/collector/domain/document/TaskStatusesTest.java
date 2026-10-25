package com.sysj.collector.domain.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskStatuses#masterTerminalStatusOf} 单元测试。
 *
 * <p>2026-10-11 口径变更：部分失败不再并入 COMPLETED，而是独立的 PARTIAL 终态。
 */
class TaskStatusesTest {

    @Test
    void allFailedMeansFailed() {
        assertEquals(TaskStatuses.MASTER_FAILED, TaskStatuses.masterTerminalStatusOf(0, 3));
        assertEquals(TaskStatuses.MASTER_FAILED, TaskStatuses.masterTerminalStatusOf(0, 1));
    }

    @Test
    void noFailureMeansCompleted() {
        assertEquals(TaskStatuses.MASTER_COMPLETED, TaskStatuses.masterTerminalStatusOf(2, 0));
        assertEquals(TaskStatuses.MASTER_COMPLETED, TaskStatuses.masterTerminalStatusOf(1, 0));
    }

    @Test
    void mixedOutcomeMeansPartial() {
        assertEquals(TaskStatuses.MASTER_PARTIAL, TaskStatuses.masterTerminalStatusOf(1, 2));
        assertEquals(TaskStatuses.MASTER_PARTIAL, TaskStatuses.masterTerminalStatusOf(21, 1));
    }

    @Test
    void allThreeOutcomesAreTerminal() {
        assertTrue(TaskStatuses.MASTER_TERMINAL.contains(TaskStatuses.MASTER_COMPLETED));
        assertTrue(TaskStatuses.MASTER_TERMINAL.contains(TaskStatuses.MASTER_FAILED));
        assertTrue(TaskStatuses.MASTER_TERMINAL.contains(TaskStatuses.MASTER_PARTIAL));
        assertFalse(TaskStatuses.MASTER_TERMINAL.contains(TaskStatuses.MASTER_ACTIVE));
        assertFalse(TaskStatuses.MASTER_TERMINAL.contains(TaskStatuses.MASTER_PENDING));
    }

    @Test
    void terminalStatusIsAlwaysInTerminalList() {
        long[][] cases = {{0, 0}, {0, 1}, {1, 0}, {1, 1}, {3, 0}, {0, 3}, {21, 1}};
        for (long[] c : cases) {
            String status = TaskStatuses.masterTerminalStatusOf(c[0], c[1]);
            assertTrue(TaskStatuses.MASTER_TERMINAL.contains(status),
                    "success=" + c[0] + " failed=" + c[1] + " -> " + status);
        }
    }
}
