package com.sysj.collector.facade;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis 健康监测（阶段2.5 降级兜底）。
 *
 * <p>Redis 现在承载任务队列、熔断状态、限速配额三件关键事。本监测器维护
 * HEALTHY / DEGRADED 两态：
 * <ul>
 *   <li><b>进入降级</b>：任何队列/熔断/限流操作抛异常时由调用方 {@link #markFailure} 标记 ——
 *       各组件随即走本地兜底（入队落本地队列、熔断放行兜底、限流放行兜底），
 *       **服务不因 Redis 宕机停摆**；</li>
 *   <li><b>恢复</b>：降级期间定时 PING 探测（默认 10s），探活成功后 {@link #markSuccess}
 *       退出降级，并触发消费组重建（{@code RedisTaskStream#ensureGroups}）与
 *       本地兜底队列回流（消费者的 drain 任务）。</li>
 * </ul>
 *
 * <p>单实例内两态切换是并发安全的（synchronized）；判定粒度是"实例级"——
 * 一次操作失败即降级、一次探活成功即恢复，宁可多降级（本地兜底成本可控）也不带病硬打。
 */
@Slf4j
@Component
public class RedisHealthMonitor {

    /** 探活间隔（毫秒）。 */
    @Value("${collector.queue.redis-probe-ms:10000}")
    private long probeMs;

    private final StringRedisTemplate redisTemplate;

    /** true = HEALTHY；false = DEGRADED（本地兜底接管）。 */
    private volatile boolean available = true;

    private volatile long degradedSince;

    public RedisHealthMonitor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isAvailable() {
        return available;
    }

    /** 降级已持续时长（毫秒）；健康时为 0。供诊断/指标用。 */
    public long degradedDurationMs() {
        return available ? 0 : System.currentTimeMillis() - degradedSince;
    }

    /**
     * 标记一次 Redis 操作失败 → 进入降级模式。
     *
     * @param op 操作名（enqueue/readNew/ackAndDelete…，供日志定位）
     */
    public synchronized void markFailure(String op, Exception cause) {
        if (available) {
            available = false;
            degradedSince = System.currentTimeMillis();
            // 完整堆栈入日志：Spring 6 起 getMessage() 不再拼接 cause，
            // 只打 message 会丢掉真正的服务端错误（如 BUSYGROUP / no such key）
            log.error("Redis 操作失败，进入降级模式（任务落本地兜底队列，熔断/限流放行兜底）: op={} error={}",
                    op, rootMessage(cause), cause);
        }
    }

    /** 异常链最底层的可读消息。 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
    }

    /** 探活成功 → 恢复。 */
    public synchronized void markSuccess() {
        if (!available) {
            available = true;
            log.info("Redis 已恢复（降级持续{}ms），退出降级模式", degradedDurationMs());
        }
    }

    /**
     * 降级期间定时探活；恢复后立即重建消费组（group 可能随 Redis 数据一起丢了）。
     * 健康状态下跳过（各组件的操作成功本身就是探活）。
     */
    @Scheduled(fixedDelayString = "${collector.queue.redis-probe-ms:10000}")
    public void probe() {
        if (available) {
            return;
        }
        try {
            redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            markSuccess();
        } catch (Exception e) {
            // 仍不可用：下轮再探
        }
    }
}
