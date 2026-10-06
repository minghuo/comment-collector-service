package com.sysj.collector.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QueuedTask} 编解码单元测试：Redis Stream 载荷 JSON 往返无损。
 * （队列是跨重启/跨实例的存储契约，字段丢失 = 任务丢参数，必须防回归。）
 */
class QueuedTaskTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void jsonRoundTripPreservesAllFields() throws Exception {
        QueuedTask task = QueuedTask.builder()
                .taskId("MT1696")
                .subTaskId("ST1696")
                .platformCode("weibo")
                .featureCode("comment")
                .targetId("https://weibo.com/1719304541/N2yB7AbCd")
                .fromUrl("https://weibo.com/1719304541/N2yB7AbCd")
                .userId("u001")
                .userTierCode("VIP")
                .specifiedProviderKey(null)
                .requiredCapabilities(List.of("LOGIN_STATE"))
                .excludedCapabilities(List.of())
                .extra(Map.of("cookie", "SUB=xxx", "sort", "time"))
                .enqueueTimeMs(1759650000000L)
                .deadlineForced(true)
                .tier(QueueTiersPlaceholder.HIGH)
                .build();

        String json = objectMapper.writeValueAsString(task);
        QueuedTask decoded = objectMapper.readValue(json, QueuedTask.class);

        assertEquals(task.getTaskId(), decoded.getTaskId());
        assertEquals(task.getSubTaskId(), decoded.getSubTaskId());
        assertEquals(task.getPlatformCode(), decoded.getPlatformCode());
        assertEquals(task.getFeatureCode(), decoded.getFeatureCode());
        assertEquals(task.getTargetId(), decoded.getTargetId());
        assertEquals(task.getUserId(), decoded.getUserId());
        assertEquals(task.getUserTierCode(), decoded.getUserTierCode());
        assertEquals(task.getRequiredCapabilities(), decoded.getRequiredCapabilities());
        assertEquals(task.getExcludedCapabilities(), decoded.getExcludedCapabilities());
        assertEquals(task.getExtra(), decoded.getExtra());
        assertEquals(task.getEnqueueTimeMs(), decoded.getEnqueueTimeMs());
        assertEquals(task.isDeadlineForced(), decoded.isDeadlineForced());
        assertEquals(task.getTier(), decoded.getTier());
    }

    @Test
    void toRequestCarriesAllFields() {
        QueuedTask task = QueuedTask.builder()
                .taskId("MT1")
                .subTaskId("ST1")
                .platformCode("bilibili")
                .featureCode("comment")
                .targetId("https://www.bilibili.com/video/BV1GJ411x7h7")
                .fromUrl("https://www.bilibili.com/video/BV1GJ411x7h7")
                .userId("u002")
                .userTierCode("NORMAL")
                .requiredCapabilities(List.of("COMMENT"))
                .excludedCapabilities(null)
                .extra(Map.of("cursor", "1695"))
                .build();

        CommentCollectRequest request = task.toRequest();
        assertEquals("MT1", request.getTaskId());
        assertEquals("ST1", request.getSubTaskId());
        assertEquals("bilibili", request.getPlatformCode());
        assertEquals("comment", request.getFeatureCode());
        assertEquals(List.of("COMMENT"), request.getRequiredCapabilities());
        assertTrue(request.getExcludedCapabilities() == null
                || request.getExcludedCapabilities().isEmpty());
        assertEquals(Map.of("cursor", "1695"), request.getExtra());
    }

    /** 避免测试对 facade 包产生依赖的占位常量。 */
    private static final class QueueTiersPlaceholder {
        private static final String HIGH = "HIGH";
    }
}
