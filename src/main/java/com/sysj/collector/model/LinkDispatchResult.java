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

    /** 是否有链接真正建成了任务；false 时 HTTP 状态码为 400/422/503，原因见 {@link #errorCode} */
    private boolean success;

    /** 整批级错误码：仅当 {@link #success} 为 false 时非空，取值见 {@link DispatchErrorCodes} */
    private String errorCode;

    /** 整批级说明（成功时为 null） */
    private String message;

    /** 自动分派批次 ID（同批拆分的各平台主任务共享，用于 {@code GET /api/dispatch/{dispatchId}} 聚合查询） */
    private String dispatchId;

    /** 调用方提交的链接条数（含重复与非法链接） */
    private int receivedUrls;

    /** 清洗去重后的链接总数 */
    private int totalUrls;

    /** 成功建任务的链接数 */
    private int dispatchedUrls;

    /**
     * 无法识别平台的链接（<b>兼容字段</b>，等价于 {@link #unsupportedLinks} 的 url 列表）。
     * 新调用方改用 {@link #unsupportedLinks} / {@link #invalidUrls} 读错误码与原因。
     */
    private List<String> unsupportedUrls;

    /** 非法链接（空串、非 http/https 等），带错误码与原因 */
    private List<LinkIssue> invalidUrls;

    /** 平台未接入或链接形态不支持的链接，带错误码与原因 */
    private List<LinkIssue> unsupportedLinks;

    /** 逐链接判定结果：覆盖每条去重后的链接，按提交顺序 */
    private List<LinkResult> linkResults;

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
        /** 错误码，见 {@link DispatchErrorCodes}（FEATURE_DISABLED / QUEUE_FULL） */
        private String errorCode;
        /** 跳过原因 */
        private String reason;
    }

    /**
     * 单条链接的最终判定结果。
     *
     * <p>{@code status} 取值：{@code DISPATCHED}（已建任务） / {@code SKIPPED}（平台已识别但该分组被拒） /
     * {@code UNSUPPORTED}（平台未接入或链接形态不支持） / {@code INVALID}（不是合法链接）。
     */
    @Data
    @Builder
    public static class LinkResult {
        /** 链接（非法链接保留调用方原样输入） */
        private String url;
        /** DISPATCHED / SKIPPED / UNSUPPORTED / INVALID */
        private String status;
        /** 平台编码（未识别时为 null） */
        private String platformCode;
        /** 功能编码（未识别时为 null） */
        private String featureCode;
        /** 平台中文名（未识别时为 null） */
        private String platformName;
        /** 本条链接落到的主任务 ID（仅 DISPATCHED 有值） */
        private String taskId;
        /** 错误码（成功时为 null） */
        private String errorCode;
        /** 原因（成功时为 null） */
        private String reason;
    }
}
