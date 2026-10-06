package com.sysj.collector.facade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link QueueTiers} 单元测试：用户等级优先级 → 流层级判定。
 */
class QueueTiersTest {

    @Test
    void highPriorityTiersGoToHighStream() {
        assertEquals(QueueTiers.HIGH, QueueTiers.of(1, 500));    // ENTERPRISE
        assertEquals(QueueTiers.HIGH, QueueTiers.of(10, 500));   // VIP
        assertEquals(QueueTiers.HIGH, QueueTiers.of(100, 500));  // NORMAL
        assertEquals(QueueTiers.HIGH, QueueTiers.of(499, 500));  // 阈值边界内
    }

    @Test
    void lowPriorityTiersGoToLowStream() {
        assertEquals(QueueTiers.LOW, QueueTiers.of(500, 500));   // 等于阈值不算高
        assertEquals(QueueTiers.LOW, QueueTiers.of(999, 500));   // 未知等级默认
        assertEquals(QueueTiers.LOW, QueueTiers.of(1000, 500));
    }
}
