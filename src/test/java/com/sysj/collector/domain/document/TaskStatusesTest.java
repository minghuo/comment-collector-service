package com.sysj.collector.domain.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TaskStatuses#masterTerminalStatusOf} 单元测试：
 * 终态口径与历史 {@code refreshMasterTaskStatus} 完全一致（全部失败才 FAILED）。
 */
class TaskStatusesTest {

    @Test
    void allFailedMeansFailed() {
        assertEquals(TaskStatuses.MASTER_FAILED, TaskStatuses.masterTerminalStatusOf(0, 3));
        assertEquals(TaskStatuses.MASTER_FAILED, TaskStatuses.masterTerminalStatusOf(0, 1));
    }

    @Test
    void anySuccessMeansCompleted() {
        assertEquals(TaskStatuses.MASTER_COMPLETED, TaskStatuses.masterTerminalStatusOf(1, 2));
        assertEquals(TaskStatuses.MASTER_COMPLETED, TaskStatuses.masterTerminalStatusOf(2, 0));
    }
}
