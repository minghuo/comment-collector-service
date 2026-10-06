package com.sysj.collector.core.ratelimit;

import com.sysj.collector.domain.dao.SupplierStateDao;
import com.sysj.collector.domain.service.SystemConfigService;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自适应限速器 `[借鉴 B-11]` + 流控效果 `[借鉴 B-14]`（阶段2：Redis 分布式令牌桶）。
 *
 * <h3>解决的问题</h3>
 * 原来每个供应商的速率是写死在 DB 里的常量（{@code rate_per_second}），
 * 于是出现"**失败越猛打越猛**"：上游变慢/变差时，服务仍以配置速率持续施压，放大故障。
 * 现在速率随实际响应时间自动下调，且失败时**只能降不能升**。
 *
 * <h3>阶段2：多实例共享</h3>
 * 此前令牌桶是每实例一个（Guava），N 实例部署 = 聚合速率放大 N 倍。
 * 现在令牌桶状态在 Redis（HASH：{@code tokens, lastMs}），由 Lua 脚本原子
 * "按流逝时间补充 → 判定"，**所有实例共享同一份速率配额**；
 * 自适应延迟（delayMs）与预热起点同样是共享状态：
 * <ul>
 *   <li>delay —— 任一实例上报结果后更新（后写胜出），下次取令牌所有实例生效；</li>
 *   <li>warm-up 起点 —— 熔断恢复（Redis 熔断脚本迁移到 CLOSED）时写入，
 *       替代原 Spring 事件监听（多实例下事件本来就只在发生迁移的那台机器触发）。</li>
 * </ul>
 *
 * <h3>与令牌桶、熔断器的分工</h3>
 * <ul>
 *   <li>{@link AdaptiveDelayPolicy} / {@link TokenBucketMath} —— 纯计算，可脱离 Spring 验证；</li>
 *   <li>本类 —— 读共享延迟算出**有效速率**，驱动 Redis 令牌桶（客户端重试循环实现等待语义）；</li>
 *   <li>熔断器管"**要不要打**"（三态开关），本类管"**打多快**"（连续调参）：一个是质变，一个是量变</li>
 * </ul>
 *
 * <h3>落库</h3>
 * {@code effective_qps} / {@code adaptive_delay_ms} 通过 {@code SupplierStateDao#updateAdaptiveMetrics}
 * 以 {@code $set} 定点写入（观测副本），按 key 节流。
 */
@Slf4j
@Component
public class AdaptiveRateLimiter {

    public static final String FIELD_TOKENS = "tokens";
    public static final String FIELD_LAST_MS = "lastMs";

    @Value("${collector.ratelimit.redis-key-prefix:collector:ratelimit}")
    private String keyPrefix;

    /** 自适应总开关；关闭后有效速率恒等于静态速率（排障时用来隔离本机制）。 */
    @Value("${collector.ratelimit.adaptive-enabled:true}")
    private boolean adaptiveEnabledDefault;

    /** 单次取令牌的最长等待（`REJECT` / `WARM_UP` 用）。 */
    @Value("${collector.ratelimit.reject-timeout-ms:500}")
    private long rejectTimeoutMsDefault;

    /** `THROTTLE_QUEUE` 的默认最长排队等待（供应商配置未指定时生效）。 */
    @Value("${collector.ratelimit.max-queue-wait-ms:2000}")
    private long maxQueueWaitMsDefault;

    /** 冷启动 / 熔断恢复后的预热时长（秒）。 */
    @Value("${collector.ratelimit.warm-up-seconds:30}")
    private long warmUpSecondsDefault;

    /** 派生指标写库节流（秒）。 */
    @Value("${collector.ratelimit.persist-interval-seconds:30}")
    private long persistIntervalSecondsDefault;

    private final StringRedisTemplate redisTemplate;
    private final SupplierStateDao supplierStateDao;
    private final SystemConfigService systemConfigService;

    /** 本实例已触碰的限流 key（快照/落库节流用）。 */
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    /**
     * 令牌桶一步（补充 + 判定）：KEYS=[bucket]；ARGV=[now, rate, capacity]。
     * 返回 {allowed(0/1), deficitMs} —— 未取到时 deficitMs 是凑够 1 个令牌的毫秒数，
     * 客户端据此决定重试间隔（等待语义在客户端实现，见 {@link #acquire}）。
     */
    private static final DefaultRedisScript<List> TOKEN_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local cap = tonumber(ARGV[3])
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens') or '-1')
            local last = tonumber(redis.call('HGET', KEYS[1], 'lastMs') or '0')
            if tokens < 0 then
              tokens = cap
              last = now
            end
            tokens = math.min(cap, tokens + (now - last) / 1000.0 * rate)
            if tokens >= 1 then
              redis.call('HMSET', KEYS[1], 'tokens', tokens - 1, 'lastMs', now)
              return {'1', '0'}
            end
            redis.call('HMSET', KEYS[1], 'tokens', tokens, 'lastMs', now)
            local deficit = (1 - tokens) / math.max(rate, 1e-9) * 1000.0
            return {'0', tostring(deficit)}
            """, List.class);

    public AdaptiveRateLimiter(StringRedisTemplate redisTemplate,
                               SupplierStateDao supplierStateDao,
                               SystemConfigService systemConfigService) {
        this.redisTemplate = redisTemplate;
        this.supplierStateDao = supplierStateDao;
        this.systemConfigService = systemConfigService;
    }

    @PostConstruct
    public void init() {
        log.info("自适应限速参数(默认值,可被 system_config 覆盖): enabled={} 取令牌等待={}ms 排队上限={}ms 预热={}s 落库间隔={}s 令牌桶=Redis(多实例共享)",
                adaptiveEnabled(), rejectTimeoutMs(), maxQueueWaitMs(), warmUpSeconds(), persistIntervalSeconds());
    }

    // ── 参数 ───────────────────────────────────────────────────────────────

    /**
     * 一次调用的流控规格。
     *
     * @param staticRate     DB 配置的静态速率（有效速率的**上限**）
     * @param params         自适应参数
     * @param effect         流控效果
     * @param maxQueueWaitMs 排队上限；&le;0 表示用全局默认 `collector.ratelimit.max-queue-wait-ms`
     */
    public record FlowSpec(double staticRate,
                           AdaptiveDelayPolicy.Params params,
                           FlowEffect effect,
                           long maxQueueWaitMs) {

        public static FlowSpec of(double staticRate, AdaptiveDelayPolicy.Params params, FlowEffect effect) {
            return new FlowSpec(staticRate, params, effect, 0L);
        }

        public FlowEffect effectOrDefault() {
            return effect == null ? FlowEffect.REJECT : effect;
        }

        public AdaptiveDelayPolicy.Params paramsOrDefault() {
            return params == null ? AdaptiveDelayPolicy.Params.defaults() : params.sanitized();
        }
    }

    // ── 对外 API ───────────────────────────────────────────────────────────

    /**
     * 尝试获取一次调用许可（Redis 分布式令牌桶，多实例共享配额）。
     *
     * <p>等待语义在客户端实现：取不到令牌时按脚本给出的亏空时间重试，
     * 直到超过流控效果对应的等待预算（{@code REJECT/WARM_UP} = reject-timeout-ms，
     * {@code THROTTLE_QUEUE} = max-queue-wait-ms）。
     *
     * @param key  限流 key = {@code platform:feature:providerKey}
     * @param spec 流控规格
     * @return 是否获得许可
     */
    public boolean acquire(String key, FlowSpec spec) {
        FlowEffect effect = spec.effectOrDefault();
        double effectiveRate = Math.max(effectiveRate(key, spec), 1e-4);
        double capacity = TokenBucketMath.capacity(effectiveRate);

        long waitMs = effect == FlowEffect.THROTTLE_QUEUE
                ? (spec.maxQueueWaitMs() > 0 ? spec.maxQueueWaitMs() : maxQueueWaitMs())
                : rejectTimeoutMs();
        long deadline = System.currentTimeMillis() + waitMs;

        try {
            while (true) {
                long now = System.currentTimeMillis();
                List<Object> result = redisTemplate.execute(TOKEN_SCRIPT,
                        List.of(bucketKey(key)),
                        String.valueOf(now), String.valueOf(effectiveRate), String.valueOf(capacity));
                if (result != null && "1".equals(str(result, 0))) {
                    State s = state(key);
                    s.effectiveQps = effectiveRate;
                    s.lastStaticRate = spec.staticRate();
                    persistIfDue(key, s);
                    return true;
                }
                if (System.currentTimeMillis() >= deadline) {
                    log.debug("限流拒绝: key={} effect={} 有效速率={}/s", key, effect, effectiveRate);
                    return false;
                }
                long deficit = parseLong(str(result, 1), 50L);
                long remaining = deadline - System.currentTimeMillis();
                Thread.sleep(Math.max(10, Math.min(deficit, Math.min(remaining, 200))));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // Redis 抖动：按放行兜底（限流是保护动作，自身故障不应掐断采集）
            log.warn("限流取令牌失败，按放行兜底: key={} error={}", key, e.getMessage());
            return true;
        }
    }

    /**
     * 上报一次调用结果，据此调整共享的自适应延迟。
     *
     * <p><b>绝不向上抛</b>：本方法在调用管线的 finally 里执行（管线外层把上报异常
     * 当成供应商失败会污染切换/熔断统计），Redis 故障时只记日志、放弃本次调整
     * —— 延迟是"连续调参"量，丢一次调整的代价远小于把健康调用判成失败。
     *
     * @param latencyMs 本次耗时（&le;0 表示未知）
     * @param success   是否成功
     */
    public void recordOutcome(String key, long latencyMs, boolean success,
                              AdaptiveDelayPolicy.Params params) {
        if (!adaptiveEnabled()) {
            return;
        }
        try {
            State s = state(key);
            long before = delayOf(key);
            long after = AdaptiveDelayPolicy.nextDelay(before, latencyMs, success, params);
            if (before != after) {
                redisTemplate.opsForValue().set(delayKey(key), String.valueOf(after));
                log.debug("自适应延迟调整: key={} {}ms -> {}ms (latency={}ms success={})",
                        key, before, after, latencyMs, success);
            }
            s.effectiveQps = AdaptiveDelayPolicy.effectiveRate(s.lastStaticRate, after);
            persistIfDue(key, s);
        } catch (Exception e) {
            log.warn("自适应延迟调整失败(非关键，不影响本次调用结果): key={} error={}", key, e.getMessage());
        }
    }

    /** 当前有效速率（供监控/接口展示）。 */
    public double effectiveRate(String key, FlowSpec spec) {
        if (!adaptiveEnabled()) {
            return spec.staticRate();
        }
        long delay = delayOf(key);
        double rate = AdaptiveDelayPolicy.effectiveRate(spec.staticRate(), delay);
        if (spec.effectOrDefault() == FlowEffect.WARM_UP) {
            long start = warmUpStartMs(key);
            long elapsed = start <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - start;
            rate = rate * AdaptiveDelayPolicy.warmUpFactor(elapsed, warmUpSeconds());
        }
        // 速率必须有下限：预热起点会让系数为 0，令牌桶不接受 0 速率
        return Math.max(rate, 1e-4);
    }

    /** 当前延迟快照（key → delayMs），供运维查看。 */
    public Map<String, Long> delaySnapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        states.keySet().forEach(key -> result.put(key, delayOf(key)));
        return result;
    }

    /** 冷启动/人工复位时手动重置预热起点。 */
    public void restartWarmUp(String key) {
        redisTemplate.opsForValue().set(warmUpKey(key), String.valueOf(System.currentTimeMillis()));
    }

    /** 限流 key（platform:feature:providerKey）。 */
    public static String buildKey(String platformCode, String featureCode, String providerKey) {
        return platformCode + ":" + featureCode + ":" + providerKey;
    }

    // ── 共享状态读取 ───────────────────────────────────────────────────────

    /** 共享的自适应延迟（Redis；无值=未初始化，返回 0 由 Policy 用 startDelayMs 起步）。 */
    private long delayOf(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(delayKey(key));
            return raw == null ? 0 : parseLong(raw, 0);
        } catch (Exception e) {
            log.warn("读取共享延迟失败(按未初始化处理): key={} error={}", key, e.getMessage());
            return 0;
        }
    }

    /** 共享的预热起点（毫秒）；&le;0 表示没有正在进行的预热。 */
    private long warmUpStartMs(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(warmUpKey(key));
            return raw == null ? 0 : parseLong(raw, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    // ── 动态配置读取（system_config 覆盖 properties 默认，60s 缓存） ────────

    private boolean adaptiveEnabled() {
        return systemConfigService.getBool("collector.ratelimit.adaptive-enabled", adaptiveEnabledDefault);
    }

    private long rejectTimeoutMs() {
        return systemConfigService.getLong("collector.ratelimit.reject-timeout-ms", rejectTimeoutMsDefault);
    }

    private long maxQueueWaitMs() {
        return systemConfigService.getLong("collector.ratelimit.max-queue-wait-ms", maxQueueWaitMsDefault);
    }

    private long warmUpSeconds() {
        return systemConfigService.getLong("collector.ratelimit.warm-up-seconds", warmUpSecondsDefault);
    }

    private long persistIntervalSeconds() {
        return systemConfigService.getLong("collector.ratelimit.persist-interval-seconds", persistIntervalSecondsDefault);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private State state(String key) {
        return states.computeIfAbsent(key, k -> new State());
    }

    /** 按 key 节流写库（`effective_qps` 用最近一次实际生效的速率）。 */
    private void persistIfDue(String key, State s) {
        long now = System.currentTimeMillis();
        if (now - s.lastPersistAt < persistIntervalSeconds() * 1000L) {
            return;
        }
        s.lastPersistAt = now;
        try {
            supplierStateDao.updateAdaptiveMetrics(key, s.effectiveQps, delayOf(key));
        } catch (Exception e) {
            log.warn("自适应指标落库失败(非关键): key={} error={}", key, e.getMessage());
        }
    }

    private String delayKey(String key) {
        return keyPrefix + ":delay:" + key;
    }

    private String warmUpKey(String key) {
        return keyPrefix + ":warmup:" + key;
    }

    private String bucketKey(String key) {
        return keyPrefix + ":bucket:" + key;
    }

    private static String str(List<Object> result, int index) {
        if (result == null || index >= result.size() || result.get(index) == null) {
            return null;
        }
        return result.get(index).toString();
    }

    private static long parseLong(String raw, long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return (long) Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 每个限流 key 的本实例观测状态（落库节流；共享状态在 Redis）。 */
    private static final class State {
        /** 最近一次实际生效的速率（落库用）。 */
        private volatile double effectiveQps;
        /** 最近的静态速率（effectiveQps 推导用）。 */
        private volatile double lastStaticRate;
        /** 上次写库时间。 */
        private volatile long lastPersistAt;
    }
}
