package com.sysj.collector.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysj.collector.domain.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费组自愈（审计 #10）：{@code groupsReady} 是**进程内**标志，
 * Redis 侧被 FLUSHALL / 换库 / 删 key 之后标志仍为 true，
 * 旧实现只在 {@code !groupsReady} 时补建 ⇒ 消费组永不重建、消费静默停摆。
 *
 * <p>本测试锁住两条行为：回验发现组消失必须置回未就绪；探活周期必须真的重建组。
 */
@ExtendWith(MockitoExtension.class)
class RedisTaskStreamGroupHealingTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOps;

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private RedisHealthMonitor healthMonitor;

    private RedisTaskStream newStream() {
        RedisTaskStream stream = new RedisTaskStream(
                redisTemplate, new ObjectMapper(), systemConfigService, healthMonitor);
        ReflectionTestUtils.setField(stream, "streamHigh", "collector:tasks:high");
        ReflectionTestUtils.setField(stream, "streamLow", "collector:tasks:low");
        ReflectionTestUtils.setField(stream, "group", "collector-consumers");
        return stream;
    }

    /** 流/组不存在：XINFO GROUPS 抛 no such key（FLUSHALL 之后的真实形态）。 */
    private void stubGroupMissing() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(redisTemplate.<Object, Object>opsForStream()).thenReturn(streamOps);
        when(streamOps.groups(anyString())).thenThrow(new RuntimeException("ERR no such key"));
    }

    @Test
    void ensureGroupsCreatesGroupOnBothStreams() {
        stubGroupMissing();
        RedisTaskStream stream = newStream();

        assertTrue(stream.ensureGroups());
        assertTrue(stream.isGroupsReady());
        verify(streamOps, times(2)).createGroup(anyString(), any(ReadOffset.class), eq("collector-consumers"));
    }

    @Test
    void reEnsureGroupsDetectsVanishedGroupAndMarksNotReady() {
        stubGroupMissing();
        when(healthMonitor.isAvailable()).thenReturn(true);
        RedisTaskStream stream = newStream();
        assertTrue(stream.ensureGroups());

        // FLUSHALL 之后组没了：XINFO GROUPS 依旧报 no such key（stubGroupMissing 已设）
        stream.reEnsureGroups();

        assertFalse(stream.isGroupsReady(), "回验发现组消失后必须置回未就绪，否则探活永远不补建");
    }

    @Test
    void probeCycleRebuildsGroupAfterMarkGroupsLost() {
        stubGroupMissing();
        when(healthMonitor.isAvailable()).thenReturn(true);
        RedisTaskStream stream = newStream();
        assertTrue(stream.ensureGroups());
        verify(streamOps, times(2)).createGroup(anyString(), any(ReadOffset.class), eq("collector-consumers"));

        stream.markGroupsLost("测试：模拟 Redis FLUSHALL");
        assertFalse(stream.isGroupsReady());

        stream.reEnsureGroups();

        assertTrue(stream.isGroupsReady(), "探活周期应把消费组重新建起来");
        verify(streamOps, times(4)).createGroup(anyString(), any(ReadOffset.class), eq("collector-consumers"));
    }

    @Test
    void reEnsureGroupsDoesNothingWhileRedisUnavailable() {
        when(healthMonitor.isAvailable()).thenReturn(false);
        RedisTaskStream stream = newStream();

        stream.reEnsureGroups();

        assertFalse(stream.isGroupsReady(), "Redis 不可用时不做任何重建尝试");
    }

    @Test
    void noGroupDetectedThroughCauseChain() {
        Throwable nested = new RuntimeException("Redis command failed",
                new IllegalStateException("NOGROUP No such key 'collector:tasks:high' "
                        + "or consumer group 'collector-consumers' in XREADGROUP"));

        assertTrue(AsyncTaskConsumer.isNoGroup(nested));
        assertFalse(AsyncTaskConsumer.isNoGroup(new RuntimeException("connection reset by peer")));
        assertFalse(AsyncTaskConsumer.isNoGroup(new RuntimeException("BUSYGROUP consumer group exists")));
        assertFalse(AsyncTaskConsumer.isNoGroup(null));
    }
}
