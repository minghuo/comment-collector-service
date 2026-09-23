package com.sysj.collector.core.ratelimit;


import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 供应商限流管理器。
 *
 * <p>以 {@code "platformCode:featureCode:providerKey"} 为 key，
 * 每个供应商在每个功能下维护独立的令牌桶（Guava RateLimiter）。
 *
 * <p>令牌桶懒创建：第一次 tryAcquire 时以 DB 中配置的 ratePerSecond 初始化。
 * 速率变更时调用 {@link #syncRate} 热更新，无需重启服务。
 *
 * <p>不同平台/功能下的同名供应商（如 weibo:comment:local_crawler 与 douyin:comment:local_crawler）
 * 各自拥有独立令牌桶，互不影响。
 */
@Slf4j
@Component
public class ProviderRateLimitManager {

    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    /**
     * 尝试获取令牌。
     *
     * @param rateLimiterKey  限流 key = "platform:feature:providerKey"
     * @param ratePerSecond   该供应商的限流速率（来自 DB）
     * @param timeoutMs       最长等待毫秒
     * @return true = 成功获取令牌；false = 超时/被限流
     */
    public boolean tryAcquire(String rateLimiterKey, double ratePerSecond, long timeoutMs) {
        RateLimiter limiter = limiters.computeIfAbsent(rateLimiterKey, key -> {
            log.info("初始化限流器: key={} rate={}/s", key, ratePerSecond);
            return RateLimiter.create(ratePerSecond);
        });

        boolean acquired = limiter.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        if (!acquired) {
            log.warn("限流拒绝: key={} rate={}/s", rateLimiterKey, limiter.getRate());
        }
        return acquired;
    }

    /**
     * 热更新限流速率（DB 修改 ratePerSecond 后调用）。
     * 若该 key 的令牌桶尚未创建，直接忽略（下次 tryAcquire 时懒创建新速率）。
     */
    public void syncRate(String rateLimiterKey, double newRate) {
        RateLimiter limiter = limiters.get(rateLimiterKey);
        if (limiter != null) {
            double oldRate = limiter.getRate();
            limiter.setRate(newRate);
            log.info("限流速率已热更新: key={} {} -> {}/s", rateLimiterKey, oldRate, newRate);
        }
    }

    /**
     * 删除指定 key 的令牌桶（下次访问时重新懒创建）。
     * 用于彻底重置某供应商的限流状态。
     */
    public void remove(String rateLimiterKey) {
        limiters.remove(rateLimiterKey);
        log.info("限流器已移除: key={}", rateLimiterKey);
    }

    /**
     * 获取所有当前活跃限流器的速率快照（用于监控）。
     */
    public Map<String, Double> snapshot() {
        Map<String, Double> result = new ConcurrentHashMap<>();
        limiters.forEach((k, v) -> result.put(k, v.getRate()));
        return result;
    }

    /**
     * 构造限流器 key。
     */
    public static String buildKey(String platformCode, String featureCode, String providerKey) {
        return platformCode + ":" + featureCode + ":" + providerKey;
    }
}
