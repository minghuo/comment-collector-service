package com.sysj.collector.core.circuit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SlidingWindowEvaluator} 单元测试：计数滑动窗口的熔断评估口径
 * （与原内存实现一致：失败率阈值、慢调用率阈值、最小样本数）。
 */
class SlidingWindowEvaluatorTest {

    @Test
    void memberEncodingRoundTrips() {
        String m = SlidingWindowEvaluator.member(true, false, 42L);
        assertTrue(SlidingWindowEvaluator.isFailed(m));
        assertFalse(SlidingWindowEvaluator.isSlow(m));
        assertTrue(m.endsWith(":42"));

        String both = SlidingWindowEvaluator.member(true, true, 7L);
        assertTrue(SlidingWindowEvaluator.isFailed(both));
        assertTrue(SlidingWindowEvaluator.isSlow(both));

        String clean = SlidingWindowEvaluator.member(false, false, 9L);
        assertFalse(SlidingWindowEvaluator.isFailed(clean));
        assertFalse(SlidingWindowEvaluator.isSlow(clean));
    }

    @Test
    void belowMinimumCallsNeverOpens() {
        List<String> allFailed = List.of("f:1", "f:2", "f:3", "f:4");
        assertFalse(SlidingWindowEvaluator.evaluate(allFailed, 5, 50, 80).open(),
                "样本数不足不评估，避免冷启动误判");
    }

    @Test
    void failureRateAtThresholdOpens() {
        List<String> window = List.of("f:1", "f:2", "f:3", "s:4", ":5");   // 失败 3/5 = 60%
        var evaluation = SlidingWindowEvaluator.evaluate(window, 5, 50, 80);
        assertTrue(evaluation.open());
        assertEquals(5, evaluation.total());
        assertEquals(3, evaluation.failed());
        assertTrue(evaluation.reason().contains("失败率"));
    }

    @Test
    void slowRateAtThresholdOpens() {
        List<String> window = List.of("fs:1", "s:2", "s:3", "s:4", ":5");  // 慢 4/5 = 80%
        var evaluation = SlidingWindowEvaluator.evaluate(window, 5, 50, 80);
        assertTrue(evaluation.open());
        assertTrue(evaluation.reason().contains("慢调用"));
    }

    @Test
    void healthyWindowStaysClosed() {
        List<String> window = List.of(":1", ":2", ":3", ":4", "f:5");      // 失败 20% 慢 0%
        assertFalse(SlidingWindowEvaluator.evaluate(window, 5, 50, 80).open());
    }

    @Test
    void slowEvaluationDisabledWhenThresholdZero() {
        List<String> window = List.of("s:1", "s:2", "s:3", "s:4", "s:5");  // 全慢但未失败
        assertFalse(SlidingWindowEvaluator.evaluate(window, 5, 50, 0).open(),
                "slowRateThreshold<=0 表示关闭慢调用统计");
    }

    @Test
    void malformedMemberTreatedAsSuccess() {
        List<String> window = List.of("f:1", "f:2", "f:3", "garbage-no-flag", ":5");
        // garbage 无 f 标志按成功计：失败 3/5 = 60% 仍然达标
        assertTrue(SlidingWindowEvaluator.evaluate(window, 5, 50, 80).open());
    }
}
