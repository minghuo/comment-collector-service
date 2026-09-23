package com.sysj.collector.core.ratelimit;

/**
 * 流控效果 `[借鉴 B-14]`。
 *
 * <p>决定"令牌桶取不到令牌时怎么办"：
 * <ul>
 *   <li>{@link #REJECT} —— 立即跳过该供应商（默认，快速失败，把压力转移给下一个候选）</li>
 *   <li>{@link #WARM_UP} —— 冷启动/熔断恢复后按预热曲线从低速率平滑升到目标速率，
 *       避免刚恢复就被打满（拒绝语义同 REJECT，只是速率被压低）</li>
 *   <li>{@link #THROTTLE_QUEUE} —— 匀速排队，最多等 {@code maxQueueWaitMs} 才拒绝；
 *       适合"慢但稳、且没有备用供应商"的场景</li>
 * </ul>
 */
public enum FlowEffect {

    /** 取不到令牌立即跳过。 */
    REJECT,

    /** 预热：恢复后速率从低位平滑爬升。 */
    WARM_UP,

    /** 匀速排队：等待至多 maxQueueWaitMs。 */
    THROTTLE_QUEUE;

    /** 宽松解析：未知/空值回退 {@link #REJECT}，避免配置写错就让采购链路失败。 */
    public static FlowEffect of(String raw) {
        if (raw == null || raw.isBlank()) {
            return REJECT;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return REJECT;
        }
    }
}
