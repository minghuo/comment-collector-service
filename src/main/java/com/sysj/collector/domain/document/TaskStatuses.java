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
    /** 全部子任务终态且至少一个成功。 */
    public static final String MASTER_COMPLETED = "COMPLETED";
    /** 全部子任务终态且全部失败。 */
    public static final String MASTER_FAILED = "FAILED";

    /** 主任务终态集合。 */
    public static final List<String> MASTER_TERMINAL = List.of(MASTER_COMPLETED, MASTER_FAILED);

    // ── 子任务（sub_task.status） ─────────────────────────────────────────

    public static final String SUB_PENDING = "PENDING";
    public static final String SUB_RUNNING = "RUNNING";
    /** 等待延迟重投（见 {@code SubTaskRetryScheduler}）。 */
    public static final String SUB_RETRYING = "RETRYING";
    public static final String SUB_SUCCESS = "SUCCESS";
    public static final String SUB_FAILED = "FAILED";

    /**
     * 终态判定（与历史 {@code refreshMasterTaskStatus} 口径一致）：
     * 全部失败 → FAILED；有任一成功 → COMPLETED。
     *
     * @param success 成功子任务数
     * @param failed  失败子任务数
     */
    public static String masterTerminalStatusOf(long success, long failed) {
        return failed > 0 && success == 0 ? MASTER_FAILED : MASTER_COMPLETED;
    }

    private TaskStatuses() {
    }
}
