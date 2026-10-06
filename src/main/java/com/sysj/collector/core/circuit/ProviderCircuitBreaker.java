package com.sysj.collector.core.circuit;

import com.sysj.collector.domain.dao.SupplierStateDao;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.SupplierState;
import com.sysj.collector.domain.service.SystemConfigService;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 供应商熔断器 —— 健康状态的**唯一读写入口**（修正 C-12）。
 *
 * <h3>阶段2：状态上移 Redis，多实例共享</h3>
 * 此前状态是每实例一份内存（Mongo 只做写穿观测），多实例下"A 实例熔断、B 实例继续打"。
 * 现在状态机/滑动窗口/半开名额全部在 Redis，由 {@link CircuitRedisState} 的 Lua 脚本
 * **原子迁移**，所有实例看到同一份真相：
 * <ul>
 *   <li>{@code supplier_state.circuit_state}（Mongo）保留为**观测副本**（写穿 + 节流），
 *       供运维查看与 Redis 丢失后的播种恢复；</li>
 *   <li>状态迁移即发 {@link CircuitStateChangedEvent}（本实例观测到迁移时发布）；
 *       {@code providers[].is_healthy} 仍是运维 kill switch，与熔断状态取与（路由侧处理）；</li>
 *   <li>Redis 被清空 → 从 Mongo 播种 OPEN，其余从 CLOSED 重新观察。</li>
 * </ul>
 *
 * <h3>统计口径</h3>
 * {@code totalSuccess/totalFailure/avgResponseTime} 是**本实例本地累计**（聚合观测值，
 * 不参与熔断判定——判定只看 Redis 共享窗口），Mongo 里因此是各实例的近似聚合。
 */
@Slf4j
@Component
public class ProviderCircuitBreaker {

    private final SupplierStateDao supplierStateDao;
    private final ApplicationEventPublisher eventPublisher;
    private final SystemConfigService systemConfigService;
    private final CircuitRedisState redisState;

    // ── 以下 @Value 仅为**默认值**：同名 system_config 键存在时以 DB 为准 ──

    @Value("${collector.circuit.enabled:true}")
    private boolean enabledDefault;

    @Value("${collector.circuit.failure-rate-threshold:50}")
    private double failureRateThreshold;

    @Value("${collector.circuit.slow-call-ms:10000}")
    private long slowCallMs;

    @Value("${collector.circuit.slow-call-rate-threshold:80}")
    private double slowCallRateThreshold;

    @Value("${collector.circuit.sliding-window-size:20}")
    private int slidingWindowSize;

    @Value("${collector.circuit.minimum-calls:5}")
    private int minimumCalls;

    @Value("${collector.circuit.open-seconds:60}")
    private long openSeconds;

    @Value("${collector.circuit.half-open-calls:3}")
    private int halfOpenCalls;

    @Value("${collector.circuit.persist-interval-seconds:30}")
    private long persistIntervalSeconds;

    /** 本实例本地累计（观测聚合）：supplierKey → totals。 */
    private final ConcurrentHashMap<String, Totals> localTotals = new ConcurrentHashMap<>();

    /** 每供应商写库节流时间戳。 */
    private final ConcurrentHashMap<String, Long> lastPersistAt = new ConcurrentHashMap<>();

    public ProviderCircuitBreaker(SupplierStateDao supplierStateDao,
                                  ApplicationEventPublisher eventPublisher,
                                  SystemConfigService systemConfigService,
                                  CircuitRedisState redisState) {
        this.supplierStateDao = supplierStateDao;
        this.eventPublisher = eventPublisher;
        this.systemConfigService = systemConfigService;
        this.redisState = redisState;
    }

    @PostConstruct
    public void init() {
        CircuitBreakerConfig config = config();
        log.info("熔断器参数(默认值,可被 system_config 覆盖): enabled={} 失败率阈值={}% 慢调用={}ms/{}% 窗口={} 最小调用={} 熔断={}s 半开探测={} 落库间隔={}s 状态存储=Redis(多实例共享)",
                enabled(), config.failureRateThreshold(), config.slowCallMs(), config.slowCallRateThreshold(),
                config.slidingWindowSize(), config.minimumCalls(), config.openSeconds(),
                config.halfOpenCalls(), persistIntervalSeconds);
    }

    // ── 对外 API（与原实现签名一致） ──────────────────────────────────────

    /**
     * 是否放行一次请求。
     *
     * <p>副作用：{@code OPEN} 且冷却期已结束时推进到 {@code HALF_OPEN} 并占一个探测名额
     * （Redis Lua 内原子完成）。
     */
    public boolean allowRequest(String platformCode, String featureCode, String providerKey) {
        if (!enabled()) {
            return true;
        }
        String supplierKey = supplierKey(platformCode, featureCode, providerKey);
        try {
            CircuitRedisState.AcquireResult result = redisState.tryAcquire(
                    supplierKey, System.currentTimeMillis(), config().openSeconds(),
                    config().halfOpenCalls(), seedCandidate(supplierKey));
            publishIfTransitioned(supplierKey, result.prevState(), result.newState(), "冷却期结束");
            return result.allowed();
        } catch (Exception e) {
            // Redis 抖动：放行兜底（宁可多打一次，不能因健康检查自身故障掐断全部采集）
            log.warn("熔断放行判定失败，按放行兜底: key={} error={}", supplierKey, e.getMessage());
            return true;
        }
    }

    /** 记录一次成功调用。 */
    public void recordSuccess(String platformCode, String featureCode, String providerKey, long latencyMs) {
        record(platformCode, featureCode, providerKey, latencyMs, false);
    }

    /** 记录一次失败调用。 */
    public void recordFailure(String platformCode, String featureCode, String providerKey, long latencyMs) {
        record(platformCode, featureCode, providerKey, latencyMs, true);
    }

    /** 查询当前熔断状态（无副作用）。 */
    public CircuitState stateOf(String platformCode, String featureCode, String providerKey) {
        if (!enabled()) {
            return CircuitState.CLOSED;
        }
        try {
            return redisState.stateOf(supplierKey(platformCode, featureCode, providerKey));
        } catch (Exception e) {
            return CircuitState.CLOSED;
        }
    }

    /** 全部已知供应商的熔断状态快照（供运维接口/指标使用）。 */
    public Map<String, CircuitState> snapshot() {
        Map<String, CircuitState> result = new LinkedHashMap<>();
        for (String key : redisState.knownSupplierKeys()) {
            try {
                result.put(key, redisState.stateOf(key));
            } catch (Exception ignored) {
                // Redis 抖动时跳过该 key，不拖垮整个快照
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** 已知的供应商 key 数量（供自检）。 */
    public int trackedCount() {
        return redisState.knownSupplierKeys().size();
    }

    /**
     * 人工强制恢复：把熔断状态复位到 CLOSED 并清空滑动窗口（Redis 原子）。
     */
    public void reset(String platformCode, String featureCode, String providerKey) {
        String supplierKey = supplierKey(platformCode, featureCode, providerKey);
        redisState.reset(supplierKey);
        log.info("熔断器人工复位: key={}", supplierKey);
        persistThrottled(supplierKey, CircuitState.CLOSED, true);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private void record(String platformCode, String featureCode, String providerKey,
                        long latencyMs, boolean failed) {
        if (!enabled()) {
            return;
        }
        String supplierKey = supplierKey(platformCode, featureCode, providerKey);
        try {
            boolean slow = config().slowCallMs() > 0 && latencyMs >= config().slowCallMs();
            CircuitRedisState.OutcomeResult result = redisState.recordOutcome(
                    supplierKey, System.currentTimeMillis(), failed, slow, config());
            Totals totals = localTotals.computeIfAbsent(supplierKey, k -> new Totals());
            totals.record(failed, latencyMs);
            if (result.changed()) {
                publishTransition(supplierKey, result.from(), result.to(), result.reason());
                persistThrottled(supplierKey, parseState(result.to()), true);
            } else {
                persistThrottled(supplierKey, parseState(result.to()), false);
            }
        } catch (Exception e) {
            // 上报失败不能影响采集主流程；Redis 状态以其它调用点的上报为准
            log.warn("熔断结果上报失败(非关键): key={} error={}", supplierKey, e.getMessage());
        }
    }

    private void publishIfTransitioned(String supplierKey, String from, String to, String reason) {
        if (from != null && !from.equals(to)) {
            publishTransition(supplierKey, from, to, reason);
        }
    }

    private void publishTransition(String supplierKey, String from, String to, String reason) {
        CircuitState fromState = parseState(from);
        CircuitState toState = parseState(to);
        if (fromState == toState) {
            return;
        }
        if (toState == CircuitState.OPEN) {
            log.error("熔断器 OPEN: key={} ← {} 原因={} 冷却={}s", supplierKey, fromState, reason, config().openSeconds());
        } else if (toState == CircuitState.HALF_OPEN) {
            log.warn("熔断器 HALF_OPEN: key={} ← {} 原因={}", supplierKey, fromState, reason);
        } else {
            log.info("熔断器 CLOSED: key={} ← {} 原因={}", supplierKey, fromState, reason);
        }
        try {
            eventPublisher.publishEvent(new CircuitStateChangedEvent(
                    supplierKey, fromState, toState, reason, Instant.now()));
        } catch (Exception e) {
            log.warn("发布熔断状态事件失败(非关键): key={} error={}", supplierKey, e.getMessage());
        }
    }

    /** Mongo 观测副本写穿：状态迁移立即落库，纯计数按 interval 节流。 */
    private void persistThrottled(String supplierKey, CircuitState state, boolean force) {
        long now = System.currentTimeMillis();
        Long last = lastPersistAt.get(supplierKey);
        if (!force && last != null && now - last < persistIntervalMs()) {
            return;
        }
        lastPersistAt.put(supplierKey, now);
        try {
            Totals totals = localTotals.get(supplierKey);
            SupplierState doc = new SupplierState();
            int idx = supplierKey.indexOf(':');
            int idx2 = idx > 0 ? supplierKey.indexOf(':', idx + 1) : -1;
            doc.setSupplierKey(supplierKey);
            doc.setPlatformCode(idx > 0 ? supplierKey.substring(0, idx) : null);
            doc.setFeatureCode(idx > 0 && idx2 > 0 ? supplierKey.substring(idx + 1, idx2) : null);
            doc.setCircuitState(state.name());
            doc.setCircuitOpenedAt(state == CircuitState.CLOSED ? null : Instant.now());
            doc.setHealthStatus(switch (state) {
                case CLOSED -> "UP";
                case HALF_OPEN -> "DEGRADED";
                case OPEN -> "DOWN";
            });
            if (totals != null) {
                doc.setConsecutiveFailures(totals.consecutiveFailures);
                doc.setTotalSuccess(totals.success.get());
                doc.setTotalFailure(totals.failure.get());
                doc.setLastSuccessTime(totals.lastSuccessTime);
                doc.setLastFailureTime(totals.lastFailureTime);
                doc.setAvgResponseTime(totals.avgResponseTime);
            }
            doc.setLastHeartbeat(Instant.now());
            doc.setUpdateTime(Instant.now());
            supplierStateDao.save(doc);
        } catch (Exception e) {
            log.warn("熔断状态落库失败(非关键): key={} error={}", supplierKey, e.getMessage());
        }
    }

    /** Mongo 历史状态（首次触碰播种用；本实例已触碰过的直接传 null 免去无谓读库）。 */
    private SupplierState seedCandidate(String supplierKey) {
        if (localTotals.containsKey(supplierKey) || lastPersistAt.containsKey(supplierKey)) {
            return null; // 已触碰过：Redis 状态必然已建立
        }
        try {
            return supplierStateDao.findBySupplierKey(supplierKey).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String supplierKey(String platformCode, String featureCode, String providerKey) {
        return PlatformFeatureConfig.buildStateKey(platformCode, featureCode, providerKey);
    }

    private static CircuitState parseState(String raw) {
        if (raw == null || raw.isBlank()) {
            return CircuitState.CLOSED;
        }
        try {
            return CircuitState.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CircuitState.CLOSED;
        }
    }

    // ── 动态配置读取 ───────────────────────────────────────────────────────

    private boolean enabled() {
        return systemConfigService.getBool("collector.circuit.enabled", enabledDefault);
    }

    private long persistIntervalMs() {
        return systemConfigService.getLong("collector.circuit.persist-interval-seconds",
                persistIntervalSeconds) * 1000L;
    }

    /**
     * 当前生效的熔断参数（每次评估现读；配置来自 60s 缓存，开销是一次内存 Map 查找）。
     */
    private CircuitBreakerConfig config() {
        return new CircuitBreakerConfig(
                systemConfigService.getDouble("collector.circuit.failure-rate-threshold", failureRateThreshold),
                systemConfigService.getLong("collector.circuit.slow-call-ms", slowCallMs),
                systemConfigService.getDouble("collector.circuit.slow-call-rate-threshold", slowCallRateThreshold),
                (int) systemConfigService.getLong("collector.circuit.sliding-window-size", slidingWindowSize),
                (int) systemConfigService.getLong("collector.circuit.minimum-calls", minimumCalls),
                systemConfigService.getLong("collector.circuit.open-seconds", openSeconds),
                (int) systemConfigService.getLong("collector.circuit.half-open-calls", halfOpenCalls)
        ).sanitized();
    }

    /** 本实例本地累计（观测聚合，不参与判定）。 */
    private static final class Totals {
        private final AtomicLong success = new AtomicLong();
        private final AtomicLong failure = new AtomicLong();
        private volatile int consecutiveFailures;
        private volatile Instant lastSuccessTime;
        private volatile Instant lastFailureTime;
        private volatile double avgResponseTime;
        private final AtomicLong successSamples = new AtomicLong();

        void record(boolean failed, long latencyMs) {
            Instant now = Instant.now();
            if (failed) {
                failure.incrementAndGet();
                consecutiveFailures++;
                lastFailureTime = now;
            } else {
                success.incrementAndGet();
                consecutiveFailures = 0;
                lastSuccessTime = now;
            }
            if (latencyMs > 0) {
                long samples = successSamples.incrementAndGet();
                avgResponseTime = avgResponseTime <= 0
                        ? latencyMs
                        : avgResponseTime + (latencyMs - avgResponseTime) / Math.min(samples, 1000);
            }
        }
    }
}
