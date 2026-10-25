package com.sysj.collector.domain.document;

import java.util.List;

/**
 * 任务状态常量与终态判定（收敛此前散落在各服务里的字符串字面量）。
 *
 * <p>历史代码里主任务/子任务状态是裸字符串且多处重复（"PENDING"、"ACTIVE"…），
 * 本类先作为**唯一书写处**：新代码一律引用这里，存量字面量随改动逐步替换。
 */
public final class TaskStatuses {

    // ── 主任务（master_task.status） ──────────────────────────────────────

    /** 已创建未开始（建任务流程内短暂存在）。 */
    public static final String MASTER_PENDING = "PENDING";
    /** 执行中。 */
    public static final String MASTER_ACTIVE = "ACTIVE";
    /** 全部子任务终态且全部成功。 */
    public static final String MASTER_COMPLETED = "COMPLETED";
    /** 全部子任务终态且全部失败。 */
    public static final String MASTER_FAILED = "FAILED";
    /**
     * 全部子任务终态，既非全成功也非全失败（部分成功）。
     *
     * <p>2026-10-11 新增：此前"部分失败"被写成 {@link #MASTER_COMPLETED}，
     * 调用方只看 {@code COMPLETED} 会以为"这一批全采到了"，实际有链接没采到 ——
     * 失败信息只留在 {@code failedLinks} 计数里，容易被漏读。现在三态互斥。
     */
    public static final String MASTER_PARTIAL = "PARTIAL";

    /** 主任务终态集合（三态都算"跑完了"）。 */
    public static final List<String> MASTER_TERMINAL =
            List.of(MASTER_COMPLETED, MASTER_FAILED, MASTER_PARTIAL);

    // ── 子任务（sub_task.status） ─────────────────────────────────────────

    public static final String SUB_PENDING = "PENDING";
    public static final String SUB_RUNNING = "RUNNING";
    /** 等待延迟重投（见 {@code SubTaskRetryScheduler}）。 */
    public static final String SUB_RETRYING = "RETRYING";
    public static final String SUB_SUCCESS = "SUCCESS";
    public static final String SUB_FAILED = "FAILED";

    /** 子任务终态集合。 */
    public static final List<String> SUB_TERMINAL = List.of(SUB_SUCCESS, SUB_FAILED);

    /**
     * 子任务非终态白名单（{@code in} 语义，比 {@code nin 终态} 更严）。
     *
     * <p>条件更新一律用白名单：`status.nin(终态)` 只排除两个终态，任何未知/脏状态
     * 都会被当成"可以写"；白名单下只有这三个状态允许被推入下一状态。
     */
    public static final List<String> SUB_NON_TERMINAL = List.of(SUB_PENDING, SUB_RUNNING, SUB_RETRYING);

    /**
     * 终态判定（三态互斥）：
     * 无失败 → COMPLETED；有失败且无成功 → FAILED；两者都有 → PARTIAL。
     *
     * <p>2026-10-11 改口径：部分失败不再并入 COMPLETED。调用方若只关心"跑完了没"，
     * 请用 {@link #MASTER_TERMINAL} 判断，而不是只比 {@code COMPLETED}。
     *
     * @param success 成功子任务数
     * @param failed  失败子任务数
     */
    public static String masterTerminalStatusOf(long success, long failed) {
        if (failed <= 0) {
            return MASTER_COMPLETED;
        }
        return success > 0 ? MASTER_PARTIAL : MASTER_FAILED;
    }

    private TaskStatuses() {
    }
}
