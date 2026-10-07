package com.sysj.collector.facade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisTaskStream#isBusyGroup(Throwable)} 单元测试：
 * BUSYGROUP 判定必须沿异常 cause 链查找 —— Spring 6 起
 * {@code getMessage()} 不再拼接 cause 内容，只看顶层消息会把
 * "组已存在"误判成真失败（线上曾因此造成降级模式 10 秒一次的反复横跳）。
 */
class RedisTaskStreamTest {

    @Test
    void busyGroupDetectedThroughFullCauseChain() {
        // Lettuce 抛出 → Spring 包装成 RedisSystemException：BUSYGROUP 藏在 cause 链里
        RuntimeException busyGroup = new RuntimeException(
                "io.lettuce.core.RedisCommandExecutionException: BUSYGROUP Consumer Group name already exists");
        Exception wrapped = new RuntimeException("Error in execution", busyGroup);
        Exception outer = new IllegalStateException("createGroup failed", wrapped);

        assertTrue(RedisTaskStream.isBusyGroup(outer), "沿 cause 链应命中 BUSYGROUP");
    }

    @Test
    void busyGroupOnTopLevelMessageAlsoDetected() {
        assertTrue(RedisTaskStream.isBusyGroup(
                new RuntimeException("BUSYGROUP Consumer Group name already exists")));
    }

    @Test
    void nonBusyGroupErrorsAreNotMatched() {
        assertFalse(RedisTaskStream.isBusyGroup(new RuntimeException("Error in execution",
                new RuntimeException("ERR The XGROUP subcommand requires the key to exist"))),
                "流不存在 ≠ 组已存在，不应误判");
        assertFalse(RedisTaskStream.isBusyGroup(new RuntimeException("connection refused")));
        assertFalse(RedisTaskStream.isBusyGroup(null));
    }

    @Test
    void cyclicCauseChainTerminates() {
        // 防御：人为构造的互相引用异常链（A→B→A）不应死循环
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertFalse(RedisTaskStream.isBusyGroup(a));
    }
}
