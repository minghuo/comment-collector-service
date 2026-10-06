package com.sysj.collector.core.circuit;

import java.util.List;

/**
 * 计数滑动窗口的**纯计算**部分（阶段2 多实例共享熔断）。
 *
 * <p>窗口状态在 Redis ZSET（member = 标志位 + 序号，score = 序号），最近
 * {@code windowSize} 次调用按序号裁剪。评估口径与原内存实现完全一致：
 * <ul>
 *   <li>样本数 &lt; minimumCalls → 不评估；</li>
 *   <li>失败率 ≥ failureRateThreshold → 熔断；</li>
 *   <li>慢调用率 ≥ slowCallRateThreshold → 熔断（slowCallMs &gt; 0 才启用）。</li>
 * </ul>
 * 本类负责"给一批样本标志 → 是否熔断 + 原因"，Lua 侧只做存取。
 *
 * <p>标志位编码：{@code f} = 失败，{@code s} = 慢调用，组合如 {@code fs}；
 * member 格式 {@code "<flags>:<seq>"}（seq 保证唯一）。
 */
public final class SlidingWindowEvaluator {

    private SlidingWindowEvaluator() {
    }

    /** 编码一条窗口样本（member）。 */
    public static String member(boolean failed, boolean slow, long seq) {
        String flags = (failed ? "f" : "") + (slow ? "s" : "");
        return flags + ":" + seq;
    }

    /** 从 member 解出标志位（防御性：解析失败按"成功且不慢"处理）。 */
    public static boolean isFailed(String member) {
        return flagsOf(member).indexOf('f') >= 0;
    }

    public static boolean isSlow(String member) {
        return flagsOf(member).indexOf('s') >= 0;
    }

    private static String flagsOf(String member) {
        if (member == null) {
            return "";
        }
        int cut = member.indexOf(':');
        return cut < 0 ? member : member.substring(0, cut);
    }

    /** 评估结果。 */
    public record Evaluation(boolean open, String reason, int total, int failed, int slow) {

        public static Evaluation notOpen(int total, int failed, int slow) {
            return new Evaluation(false, "", total, failed, slow);
        }
    }

    /**
     * 评估是否应当熔断（CLOSED 状态下的窗口评估；半开探测的成败迁移不在此处）。
     *
     * @param members               窗口内全部样本标志（按任意序，数量已由调用方裁剪到 windowSize）
     * @param minimumCalls          触发评估的最小样本数
     * @param failureRateThreshold  失败率阈值（%）
     * @param slowRateThreshold     慢调用率阈值（%；&le;0 表示关闭慢调用统计）
     */
    public static Evaluation evaluate(List<String> members, int minimumCalls,
                                      double failureRateThreshold, double slowRateThreshold) {
        int total = members.size();
        if (total < minimumCalls) {
            return Evaluation.notOpen(total, 0, 0);
        }
        int failed = 0;
        int slow = 0;
        for (String m : members) {
            if (isFailed(m)) failed++;
            if (isSlow(m)) slow++;
        }
        double failureRate = failed * 100.0 / total;
        if (failureRate >= failureRateThreshold) {
            return new Evaluation(true, String.format("失败率 %.1f%% (%d/%d) 达阈值 %.1f%%",
                    failureRate, failed, total, failureRateThreshold), total, failed, slow);
        }
        if (slowRateThreshold > 0) {
            double slowRate = slow * 100.0 / total;
            if (slowRate >= slowRateThreshold) {
                return new Evaluation(true, String.format("慢调用比例 %.1f%% (%d/%d) 达阈值 %.1f%%",
                        slowRate, slow, total, slowRateThreshold), total, failed, slow);
            }
        }
        return Evaluation.notOpen(total, failed, slow);
    }
}
