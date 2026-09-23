package com.sysj.collector.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 采集结果DTO。
 */
@Data
@Builder
public class CommentCollectResult {

    /** 是否成功 */
    private boolean success;

    /** 使用的供应商key */
    private String providerUsed;

    /** 任务ID（异步模式返回） */
    private String taskId;

    /** 采集到的评论列表 */
    private List<Comment> comments;

    /** 错误信息 */
    private String errorMessage;

    /** 是否有更多数据 */
    private boolean hasMore;

    /** 下一页游标 */
    private String nextCursor;

    /**
     * 快速构建失败结果。
     */
    public static CommentCollectResult failed(String errorMessage) {
        return CommentCollectResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
