package com.sysj.collector.domain.document;

/**
 * 评论数据类型常量（落库到 {@code comment.data_type}）。
 *
 * <p>一个平台可能有多种数据类型（微博：评论 / 转发），因此判别维度是
 * {@code dataType} 而不是 {@code platformCode}。
 *
 * <p>命名与 auto-task-web 的集合名保持一致（{@code weibo_comment} / {@code weibo_repost} / …），
 * 便于从对方数据迁移或人工比对。
 */
public final class CommentDataType {

    private CommentDataType() {
    }

    public static final String WEIBO_COMMENT = "weibo_comment";
    public static final String WEIBO_REPOST = "weibo_repost";
    public static final String WECHAT_COMMENT = "wechat_comment";
    public static final String WECHAT_VIDEO_COMMENT = "wechat_video_comment";
    public static final String BILIBILI_COMMENT = "bilibili_comment";
    public static final String DOUYIN_COMMENT = "douyin_comment";
    public static final String XHS_COMMENT = "xhs_comment";
    public static final String TOUTIAO_COMMENT = "toutiao_comment";

    /**
     * 由平台编码 + 功能编码推断默认数据类型。
     *
     * <p>约定：功能编码用 {@code repost} 表示转发，其余（{@code comment}）按平台取评论类型。
     */
    public static String of(String platformCode, String featureCode) {
        boolean repost = "repost".equalsIgnoreCase(featureCode);
        if ("weibo".equalsIgnoreCase(platformCode)) {
            return repost ? WEIBO_REPOST : WEIBO_COMMENT;
        }
        if ("wechat".equalsIgnoreCase(platformCode)) {
            return WECHAT_COMMENT;
        }
        if ("wechat_video".equalsIgnoreCase(platformCode)) {
            return WECHAT_VIDEO_COMMENT;
        }
        if ("bilibili".equalsIgnoreCase(platformCode)) {
            return BILIBILI_COMMENT;
        }
        if ("douyin".equalsIgnoreCase(platformCode)) {
            return DOUYIN_COMMENT;
        }
        if ("xhs".equalsIgnoreCase(platformCode) || "xiaohongshu".equalsIgnoreCase(platformCode)) {
            return XHS_COMMENT;
        }
        if ("toutiao".equalsIgnoreCase(platformCode)) {
            return TOUTIAO_COMMENT;
        }
        // 未登记的平台：退化为平台名 + 功能名，保证仍有可读的判别值
        return platformCode + "_" + (featureCode == null ? "comment" : featureCode);
    }
}
