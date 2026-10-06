package com.sysj.collector.domain.service;

import org.apache.commons.lang3.StringUtils;

/**
 * 子任务级重试策略（纯计算，便于单测）。
 *
 * <h3>三层重试语义的分工</h3>
 * <ol>
 *   <li>管线 {@code RetryStage} —— 供应商内重试（一次 fetchComments 失败后换参数/再打同一家）；</li>
 *   <li>门面候选循环 —— 供应商间切换（当前供应商重试耗尽后换下一家）；</li>
 *   <li>本策略 + {@code SubTaskRetryScheduler} —— **子任务级重投**：全部候选耗尽后，
 *       子任务按指数退避延迟重投回队列，而不是立刻判死。</li>
 * </ol>
 *
 * <h3>哪些失败值得重投</h3>
 * 网络类/上游类失败（超时、限流、5xx、返回失败状态）是**暂时性**的，值得重投；
 * 确定性失败（链接不支持、供应商 Bean 未注册）重投多少次结果都一样，直接判死。
 * 判定基于错误消息关键字 —— 粗糙但够用，误判的代价只是多试一轮（受 maxRetry 封顶）。
 */
public final class SubTaskRetryPolicy {

    /**
     * 判断该次失败是否应重投。
     *
     * @param retryCount   已重投次数
     * @param maxRetry     最大重投次数
     * @param errorMessage 本次失败的错误消息（可空）
     */
    public static boolean shouldRetry(int retryCount, int maxRetry, String errorMessage) {
        return retryCount < maxRetry && isRetryable(errorMessage);
    }

    /**
     * 确定性失败不重投：重投多少次结果都一样，还占队列名额。
     */
    public static boolean isRetryable(String errorMessage) {
        if (StringUtils.isBlank(errorMessage)) {
            return true;
        }
        // 链接不支持（供应商返回 STATUS_URL_ERROR，如公众号临时链接）——换个时间重试也一样
        if (errorMessage.contains("STATUS_URL_ERROR") || errorMessage.contains("链接不支持")) {
            return false;
        }
        // 供应商 Bean 未注册 —— 配置与代码不一致，属部署问题
        if (errorMessage.contains("Bean 未注册") || errorMessage.contains("Bean 未找到")) {
            return false;
        }
        // 功能未配置供应商 —— 配置缺失，重投不会让配置长出来
        if (errorMessage.contains("未配置供应商")) {
            return false;
        }
        return true;
    }

    /**
     * 第 {@code retryCount} 次重投的延迟：base × 2^(retryCount-1)，封顶 maxMs。
     *
     * @param retryCount 第几次重投（从 1 开始）
     */
    public static long backoffDelayMs(int retryCount, long baseMs, long maxMs) {
        if (retryCount <= 1) {
            return Math.min(baseMs, maxMs);
        }
        long delay = baseMs;
        for (int i = 1; i < retryCount && delay < maxMs; i++) {
            delay <<= 1;
        }
        return Math.min(delay, maxMs);
    }

    private SubTaskRetryPolicy() {
    }
}
