package com.sysj.collector.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 账号历史采集结果。
 *
 * <p>与 {@link CommentCollectResult} 的区别只有"载荷类型"（{@code items} 而非 {@code comments}）
 * 和错误位置；分页语义一致：{@code hasMore} + {@code nextCursor} 由门面原样取自供应商
 * {@link CommonEntity#getHaseMore()} / {@link CommonEntity#getNextUrl()}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountHistoryCollectResult {

    private boolean success;

    /** 实际执行成功的供应商 key。 */
    private String providerUsed;

    private String taskId;

    /** 本次取到的账号历史条目（已落库）。 */
    private List<AccountHistoryItem> items;

    private String errorMessage;

    /** 供应商是否还有下一页。 */
    private boolean hasMore;

    /** 下一页游标（各平台口径不同，原样回传）。 */
    private String nextCursor;

    /** 失败结果（同步入口与门面换供应商耗尽时使用）。 */
    public static AccountHistoryCollectResult failed(String message) {
        return AccountHistoryCollectResult.builder()
                .success(false)
                .errorMessage(message)
                .items(List.of())
                .build();
    }

    /**
     * 转成任务监听器认识的结果类型。
     *
     * <p>{@code TaskManagementService}/{@code TaskResultListener} 的回写契约是
     * {@link CommentCollectResult}（子任务重试判定、续采判定、主任务计数、回调都基于它）。
     * 账号历史**复用同一套任务编排**，因此只做一次形状转换，不复制一套任务状态机 ——
     * 否则重试/续采/回调三处逻辑会与评论链路分叉。
     */
    public CommentCollectResult toCommentCollectResult() {
        return CommentCollectResult.builder()
                .success(success)
                .providerUsed(providerUsed)
                .taskId(taskId)
                .errorMessage(errorMessage)
                .hasMore(hasMore)
                .nextCursor(nextCursor)
                .comments(List.of())
                .build();
    }
}
