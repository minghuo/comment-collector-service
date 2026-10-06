package com.sysj.collector.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Redis Stream 队列消息载荷（外置队列，阶段2）。
 *
 * <p>刻意做成**扁平 DTO** 而不是直接序列化 {@link CommentCollectRequest}：
 * 后者是 @Builder 传输对象，不带 Jackson 反序列化支持；队列载荷是存储契约，
 * 独立成类后字段增减只影响编解码一处。生产者 {@link #of(CommentCollectRequest, String)}，
 * 消费者 {@link #toRequest()}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueuedTask {

    // ── 采集请求字段（与 CommentCollectRequest 一一对应） ──────────────────

    private String taskId;
    private String subTaskId;
    private String platformCode;
    private String featureCode;
    private String targetId;
    private String fromUrl;
    private String userId;
    private String userTierCode;
    private String specifiedProviderKey;
    private List<String> requiredCapabilities;
    private List<String> excludedCapabilities;
    private Map<String, String> extra;

    // ── 队列元数据 ────────────────────────────────────────────────────────

    /** 入队时间戳（毫秒）——deadline 保障promotion用它判断"等了多久"。 */
    private long enqueueTimeMs;

    /** true = 已被 deadline 保障强制提到最高优先级（从 LOW 迁移到 HIGH 的消息）。 */
    private boolean deadlineForced;

    /** 入队层级：HIGH / LOW（信息性字段，供排查）。 */
    private String tier;

    /** 生产者入口。 */
    public static QueuedTask of(CommentCollectRequest request, String tier) {
        return QueuedTask.builder()
                .taskId(request.getTaskId())
                .subTaskId(request.getSubTaskId())
                .platformCode(request.getPlatformCode())
                .featureCode(request.getFeatureCode())
                .targetId(request.getTargetId())
                .fromUrl(request.getFromUrl())
                .userId(request.getUserId())
                .userTierCode(request.getUserTierCode())
                .specifiedProviderKey(request.getSpecifiedProviderKey())
                .requiredCapabilities(request.getRequiredCapabilities())
                .excludedCapabilities(request.getExcludedCapabilities())
                .extra(request.getExtra())
                .enqueueTimeMs(System.currentTimeMillis())
                .deadlineForced(false)
                .tier(tier)
                .build();
    }

    /** 消费者出口。 */
    public CommentCollectRequest toRequest() {
        return CommentCollectRequest.builder()
                .taskId(taskId)
                .subTaskId(subTaskId)
                .platformCode(platformCode)
                .featureCode(featureCode)
                .targetId(targetId)
                .fromUrl(fromUrl)
                .userId(userId)
                .userTierCode(userTierCode)
                .specifiedProviderKey(specifiedProviderKey)
                .requiredCapabilities(requiredCapabilities)
                .excludedCapabilities(excludedCapabilities)
                .extra(extra)
                .build();
    }
}
