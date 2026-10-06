package com.sysj.collector.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SubTaskRetryPolicy} 单元测试：可重投判定与指数退避。
 */
class SubTaskRetryPolicyTest {

    @Test
    void transientFailuresAreRetryable() {
        assertTrue(SubTaskRetryPolicy.isRetryable("执行异常: Read timeout"));
        assertTrue(SubTaskRetryPolicy.isRetryable("供应商 [weibo_official] 返回失败状态: STATUS_ERROR"));
        assertTrue(SubTaskRetryPolicy.isRetryable("限流未获取到令牌"));
        assertTrue(SubTaskRetryPolicy.isRetryable(null));
        assertTrue(SubTaskRetryPolicy.isRetryable(" "));
    }

    @Test
    void deterministicFailuresAreNotRetryable() {
        // 链接不支持：供应商返回 STATUS_URL_ERROR
        assertFalse(SubTaskRetryPolicy.isRetryable(
                "供应商 [wechat_sy] 返回失败状态: STATUS_URL_ERROR / 链接不支持"));
        // 供应商 Bean 未注册：部署问题
        assertFalse(SubTaskRetryPolicy.isRetryable("Bean 未注册"));
        assertFalse(SubTaskRetryPolicy.isRetryable("供应商 Bean 未找到: providerKey=xxx"));
        // 功能未配置供应商：配置缺失
        assertFalse(SubTaskRetryPolicy.isRetryable("该功能未配置供应商"));
    }

    @Test
    void shouldRetryRespectsMaxRetry() {
        assertTrue(SubTaskRetryPolicy.shouldRetry(0, 2, "超时"));
        assertTrue(SubTaskRetryPolicy.shouldRetry(1, 2, "超时"));
        assertFalse(SubTaskRetryPolicy.shouldRetry(2, 2, "超时"), "达到上限不再重投");
        assertFalse(SubTaskRetryPolicy.shouldRetry(0, 2, "STATUS_URL_ERROR"), "确定性失败即使有余额也不重投");
    }

    @Test
    void backoffDoublesAndCaps() {
        assertEquals(2000L, SubTaskRetryPolicy.backoffDelayMs(1, 2000, 600000));
        assertEquals(4000L, SubTaskRetryPolicy.backoffDelayMs(2, 2000, 600000));
        assertEquals(8000L, SubTaskRetryPolicy.backoffDelayMs(3, 2000, 600000));
        assertEquals(600000L, SubTaskRetryPolicy.backoffDelayMs(10, 2000, 600000), "封顶生效");
        assertEquals(2000L, SubTaskRetryPolicy.backoffDelayMs(0, 2000, 600000), "非法次数按首次处理");
    }
}
