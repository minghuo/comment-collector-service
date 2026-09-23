package com.sysj.collector.model;


import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// ── 采集请求 ──────────────────────────────────────────────────────────────

@Data
@Builder
public class CommentCollectRequest {

    /** 平台编码，如 weibo / douyin / wechat / xiaohongshu */
    private String platformCode;

    /** 功能编码，如 comment / like / repost */
    private String featureCode;

    /** 目标内容 ID */
    private String targetId;

    /** 最大采集条数 */
    private int maxCount;

    /** 调用方用户 ID */
    private String userId;

    /**
     * 用户等级编码（ENTERPRISE / VIP / NORMAL）。
     * 决定：① 任务队列调度优先级；② 起始供应商顺序。
     * 若为 null，视为 NORMAL。
     */
    private String userTierCode;

    /**
     * 强制指定供应商 key（不为 null 时跳过优先级路由）。
     * 用于特殊指标需求或测试场景。
     */
    private String specifiedProviderKey;

    /**
     * 所属主任务 ID。
     *
     * <p>不为空时，采集成功的结果会落库到 {@code comment} 集合（{@code task_id} 字段），
     * 供 {@code GET /api/tasks/{taskId}/comments} 分页读取。
     * 为空（例如纯同步即时返回场景）则只在内存中返回，不落库。
     */
    private String taskId;

    /** 所属子任务 ID（可空），随结果一并落库，便于定位是哪个链接产生的数据。 */
    private String subTaskId;

    /** 采集来源地址（原帖/原视频/原文链接），随结果一并落库。 */
    private String fromUrl;

    /** 扩展参数（游标、页码、关键词等） */
    private Map<String, String> extra;
}
