package com.sysj.collector.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 混合平台链接自动分派结果。
 *
 * <p>与 auto-task-web 的 {@code CommentDispatchService} 返回结构对齐：
 * 批次 ID、各平台子任务、无法识别的链接、被跳过的分组及原因。
 */
@Data
@Builder
public class LinkDispatchResult {

    /** 自动分派批次 ID（同批拆分的各平台主任务共享，用于 {@code GET /api/dispatch/{dispatchId}} 聚合查询） */
    private String dispatchId;

    /** 清洗去重后的链接总数 */
    private int totalUrls;

    /** 成功建任务的链接数 */
    private int dispatchedUrls;

    /** 无法识别平台的链接（未接入平台或格式不符；微博视频链接未提供 cookie 也在此列） */
    private List<String> unsupportedUrls;

    /** 各平台子任务 */
    private List<DispatchedTask> tasks;

    /** 被跳过的分组及原因（功能未开启/队列已满等） */
    private List<SkippedGroup> skipped;

    /** 单个平台子任务 */
    @Data
    @Builder
    public static class DispatchedTask {
        /** 主任务 ID */
        private String taskId;
        /** 平台编码 */
        private String platformCode;
        /** 功能编码 */
        private String featureCode;
        /** 平台中文名 */
        private String platformName;
        /** 本组链接数 */
        private int urlCount;
    }

    /** 被跳过的分组 */
    @Data
    @Builder
    public static class SkippedGroup {
        /** 平台编码 */
        private String platformCode;
        /** 功能编码 */
        private String featureCode;
        /** 平台中文名 */
        private String platformName;
        /** 本组链接数 */
        private int urlCount;
        /** 跳过原因 */
        private String reason;
    }
}
