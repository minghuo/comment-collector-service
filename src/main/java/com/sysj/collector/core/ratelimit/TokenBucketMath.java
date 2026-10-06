package com.sysj.collector.core.ratelimit;

/**
 * Redis 令牌桶的**纯计算**部分（阶段2 多实例共享限速）。
 *
 * <p>真正的令牌桶状态在 Redis（HASH：{@code tokens, lastMs}），由 Lua 脚本原子地
 * "按流逝时间补充令牌 → 判断能否取走"。本类把其中的算术抽出来做单测与对齐：
 * 补充量、亏空时间（客户端据此决定重试间隔）的口径必须与 Lua 脚本一致。
 *
 * <p>桶容量取 {@code max(1, rate)}（约 1 秒的突发余量）：
 * 与 Guava RateLimiter 的"匀速发放"不同，分布式令牌桶允许短突发，
 * 换来的是每供应商每次获取只做一次 Redis 往返。
 */
public final class TokenBucketMath {

    private TokenBucketMath() {
    }

    /** 桶容量：约 1 秒的突发余量，下限 1。 */
    public static double capacity(double ratePerSecond) {
        return Math.max(1.0, ratePerSecond);
    }

    /**
     * 按流逝时间补充令牌（不写入，Lua 侧同口径实现）。
     *
     * @param tokens        当前令牌数；&lt;0 表示桶未初始化（按满桶起步）
     * @param lastRefillMs  上次结算时间；桶未初始化时忽略
     * @param nowMs         当前时间
     * @param ratePerSecond 速率；&le;0 按极小值处理（避免除零）
     */
    public static double refill(double tokens, long lastRefillMs, long nowMs, double ratePerSecond) {
        double rate = ratePerSecond <= 0 ? 1e-9 : ratePerSecond;
        double cap = capacity(rate);
        if (tokens < 0) {
            return cap;
        }
        long elapsed = Math.max(0, nowMs - lastRefillMs);
        double refilled = tokens + elapsed / 1000.0 * rate;
        return Math.min(cap, refilled);
    }

    /**
     * 距离凑够 1 个令牌还需要的毫秒数（取不到令牌时客户端据此决定重试间隔）。
     *
     * @param tokens 当前令牌数（&ge;0）
     * @param rate   速率
     */
    public static long deficitMs(double tokens, double ratePerSecond) {
        double rate = ratePerSecond <= 0 ? 1e-9 : ratePerSecond;
        if (tokens >= 1) {
            return 0;
        }
        return (long) Math.ceil((1.0 - tokens) / rate * 1000.0);
    }
}
