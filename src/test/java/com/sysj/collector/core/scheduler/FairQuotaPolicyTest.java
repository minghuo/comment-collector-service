package com.sysj.collector.core.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FairQuotaPolicy} 单元测试：低优先级预留名额的启用条件与上限。
 */
class FairQuotaPolicyTest {

    private final FairQuotaPolicy policy = new FairQuotaPolicy(0.3, 0.7, 500, 20);

    @Test
    void noSlotsWhenQueueEmptyOrAllHigh() {
        assertEquals(0, policy.lowPrioritySlots(0, 0));
        // 队列里清一色高优先级：无低优先级可让
        assertEquals(0, policy.lowPrioritySlots(10, 10));
        // 没有高优先级任务：不启用配额
        assertEquals(0, policy.lowPrioritySlots(10, 0));
    }

    @Test
    void noSlotsBelowHighRatioThreshold() {
        // 高优先级占比 20% < 阈值 70%：不启用
        assertEquals(0, policy.lowPrioritySlots(10, 2));
    }

    @Test
    void slotsEnabledAtThresholdWithRatioCap() {
        // 占比 80% ≥ 70%：预留 ceil(20 × 0.3) = 6 个
        assertEquals(6, policy.lowPrioritySlots(10, 8));
    }

    @Test
    void slotsAtLeastOneWhenEnabled() {
        // batchSize=1 时按比例算出 0.3 → 向上取整保底 1
        FairQuotaPolicy tiny = new FairQuotaPolicy(0.3, 0.7, 500, 1);
        assertEquals(1, tiny.lowPrioritySlots(10, 8));
    }

    @Test
    void isHighPriorityUsesConfiguredBound() {
        assertEquals(true, policy.isHighPriority(499));
        assertEquals(false, policy.isHighPriority(500));
    }
}
