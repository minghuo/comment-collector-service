package com.sysj.collector.core.circuit;

import com.sysj.collector.domain.dao.SupplierStateDao;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.SupplierState;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 供应商熔断器 —— 健康状态的**唯一读写入口**（修正 C-12）。
 *
 * <h3>修复的是什么</h3>
 * 此前健康状态读写分裂：{@code DynamicProviderRouter.markProviderFailure()} 写
 * {@code supplier_state.health_status}，而路由过滤读的是
 * {@code platform_feature_config.providers[].is_healthy} —— 两条数据通路，写的没人读；
 * 且没有任何恢复路径，供应商一旦被标记就永远 DOWN。
 *
 * <p>现在：
 * <ul>
 *   <li>{@code supplier_state.circuit_state} 是**唯一健康真相源**，本类的内存状态即时写穿到它；</li>
 *   <li>重启后从 DB 惰性恢复，OPEN 状态不会因为重启就丢失；</li>
 *   <li>有真正的恢复路径：{@code OPEN → (冷却) → HALF_OPEN → (探测全成功) → CLOSED}；</li>
 *   <li>{@code providers[].is_healthy} 降级为"运维强制下线的 kill switch"，
 *       与熔断状态**取与**（两者都放行才可用），不再承担运行时健康语义。</li>
 * </ul>
 *
 * <h3>并发模型</h3>
 * 每个 {@code platform:feature:providerKey} 一个 {@link Breaker}，
 * 状态变更在 Breaker 内部锁内完成；滑动窗口只保留最近 {@code slidingWindowSize} 次调用。
 *
 * <h3>写库节流</h3>
 * 每次调用都写 Mongo 代价过大。策略：**状态迁移一定立即落库**（rare，且必须持久），
 * 纯计数更新则按 {@code collector.circuit.persist-interval-seconds} 节流。
 * 代价是进程被 kill -9 时最多丢失一个节流窗口的计数（不影响熔断判定正确性，因为窗口本身在内存里）。
 */
@Slf4j
@Component
public class ProviderCircuitBreaker {

    private final SupplierStateDao supplierStateDao;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${collector.circuit.enabled:true}")
    private boolean enabled;

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

    private CircuitBreakerConfig config;
    private long persistIntervalMs;

    /** supplierKey → Breaker */
    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    public ProviderCircuitBreaker(SupplierStateDao supplierStateDao,
                                  ApplicationEventPublisher eventPublisher) {
        this.supplierStateDao = supplierStateDao;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void init() {
        this.config = new CircuitBreakerConfig(
                failureRateThreshold, slowCallMs, slowCallRateThreshold,
                slidingWindowSize, minimumCalls, openSeconds, halfOpenCalls).sanitized();
        this.persistIntervalMs = Math.max(0L, persistIntervalSeconds) * 1000L;
        log.info("熔断器参数: enabled={} 失败率阈值={}% 慢调用={}ms/{}% 窗口={} 最小调用={} 熔断={}s 半开探测={} 落库间隔={}s",
                enabled, config.failureRateThreshold(), config.slowCallMs(), config.slowCallRateThreshold(),
                config.slidingWindowSize(), config.minimumCalls(), config.openSeconds(),
                config.halfOpenCalls(), persistIntervalSeconds);
    }

    // ── 对外 API ───────────────────────────────────────────────────────────

    /**
     * 是否放行一次请求。
     *
     * <p>副作用：{@code OPEN} 且冷却期已结束时，本次调用会把状态推进到 {@code HALF_OPEN}
     * 并占用一个探测名额。
     */
    public boolean allowRequest(String platformCode, String featureCode, String providerKey) {
        if (!enabled) {
            return true;
        }
        return breaker(platformCode, featureCode, providerKey).tryAcquire();
    }

    /** 记录一次成功调用。 */
    public void recordSuccess(String platformCode, String featureCode, String providerKey, long latencyMs) {
        if (!enabled) {
            return;
        }
        breaker(platformCode, featureCode, providerKey).onSuccess(latencyMs);
    }

    /** 记录一次失败调用。 */
    public void recordFailure(String platformCode, String featureCode, String providerKey, long latencyMs) {
        if (!enabled) {
            return;
        }
        breaker(platformCode, featureCode, providerKey).onFailure(latencyMs);
    }

    /** 查询当前熔断状态（不产生副作用，不推进状态机）。 */
    public CircuitState stateOf(String platformCode, String featureCode, String providerKey) {
        if (!enabled) {
            return CircuitState.CLOSED;
        }
        return breaker(platformCode, featureCode, providerKey).state();
    }

    /** 全部已知供应商的熔断状态快照（供运维接口/指标使用）。 */
    public Map<String, CircuitState> snapshot() {
        Map<String, CircuitState> result = new LinkedHashMap<>();
        breakers.forEach((key, b) -> result.put(key, b.state()));
        return Collections.unmodifiableMap(result);
    }

    /** 已知的供应商 key 数量（供自检）。 */
    public int trackedCount() {
        return breakers.size();
    }

    /**
     * 人工强制恢复：把熔断状态复位到 CLOSED 并清空滑动窗口。
     *
     * <p>用于运维在确认上游已修复后立即恢复流量，不必等冷却期 + 探测。
     * 自动恢复路径（OPEN → HALF_OPEN → CLOSED）才是常态，本方法是兜底手段。
     */
    public void reset(String platformCode, String featureCode, String providerKey) {
        breaker(platformCode, featureCode, providerKey).resetToClosed();
    }

    // ── Breaker 装载 ───────────────────────────────────────────────────────

    private Breaker breaker(String platformCode, String featureCode, String providerKey) {
        String supplierKey = PlatformFeatureConfig.buildStateKey(platformCode, featureCode, providerKey);
        Breaker b = breakers.get(supplierKey);
        if (b != null) {
            return b;
        }
        Breaker created = load(supplierKey, platformCode, featureCode, providerKey);
        Breaker prev = breakers.putIfAbsent(supplierKey, created);
        return prev != null ? prev : created;
    }

    /**
     * 构造 Breaker，并从 {@code supplier_state} 恢复历史状态。
     *
     * <p>恢复的意义：进程重启后若供应商此前处于 {@code OPEN}，不应立刻重新打它；
     * 冷却期已过则由下一次 {@link #allowRequest} 自然转入半开探测。
     */
    private Breaker load(String supplierKey, String platformCode, String featureCode, String providerKey) {
        Breaker b = new Breaker(supplierKey, platformCode, featureCode, providerKey);
        try {
            supplierStateDao.findBySupplierKey(supplierKey).ifPresent(state -> {
                b.docId = state.getId();
                b.totalSuccess = state.getTotalSuccess() == null ? 0L : state.getTotalSuccess();
                b.totalFailure = state.getTotalFailure() == null ? 0L : state.getTotalFailure();
                b.consecutiveFailures = state.getConsecutiveFailures() == null ? 0 : state.getConsecutiveFailures();
                b.lastSuccessTime = state.getLastSuccessTime();
                b.lastFailureTime = state.getLastFailureTime();
                if (state.getAvgResponseTime() != null) {
                    b.avgResponseTime = state.getAvgResponseTime();
                }
                b.state = parseState(state.getCircuitState());
                if (state.getCircuitOpenedAt() != null) {
                    b.openedAtMillis = state.getCircuitOpenedAt().toEpochMilli();
                }
                if (b.state == CircuitState.OPEN && b.openedAtMillis <= 0) {
                    // 脏数据兜底：OPEN 但没有开路时间，视为刚熔断，避免立即放行
                    b.openedAtMillis = System.currentTimeMillis();
                }
                if (b.state != CircuitState.CLOSED) {
                    log.warn("从 DB 恢复熔断状态: key={} state={} openedAt={}", supplierKey, b.state, state.getCircuitOpenedAt());
                }
            });
        } catch (Exception e) {
            log.warn("读取供应商历史状态失败（按 CLOSED 起步）: key={} error={}", supplierKey, e.getMessage());
        }
        return b;
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

    // ── 单个供应商的熔断状态机 ─────────────────────────────────────────────

    /** 一次调用的观测结果。 */
    private record Call(boolean failed, boolean slow) {
    }

    private final class Breaker {

        private final String supplierKey;
        private final String platformCode;
        private final String featureCode;
        private final String providerKey;
        private final Object lock = new Object();

        /** 最近 {@code slidingWindowSize} 次调用。 */
        private final ArrayDeque<Call> window = new ArrayDeque<>();

        private CircuitState state = CircuitState.CLOSED;
        private long openedAtMillis;
        private long halfOpenStartedAtMillis;
        private int halfOpenPermits;
        private int halfOpenSuccesses;

        private long totalSuccess;
        private long totalFailure;
        private int consecutiveFailures;
        private Instant lastSuccessTime;
        private Instant lastFailureTime;
        private double avgResponseTime;

        /** DB 文档 _id（从 DB 恢复时保留，避免 save 插入重复文档）。 */
        private String docId;
        private long lastPersistAt;

        private Breaker(String supplierKey, String platformCode, String featureCode, String providerKey) {
            this.supplierKey = supplierKey;
            this.platformCode = platformCode;
            this.featureCode = featureCode;
            this.providerKey = providerKey;
        }

        private CircuitState state() {
            return state;
        }

        private boolean tryAcquire() {
            synchronized (lock) {
                long now = System.currentTimeMillis();
                if (state == CircuitState.OPEN) {
                    if (now - openedAtMillis < config.openSeconds() * 1000L) {
                        return false;
                    }
                    enterHalfOpenLocked("冷却期结束，放行 " + config.halfOpenCalls() + " 个探测");
                    persistLocked(true);
                }
                if (state == CircuitState.HALF_OPEN) {
                    // 探测名额已被领走但迟迟没有结果（例如候选排在后面、最终没被真正执行）：
                    // 若不回收，permits 会一直是 0，熔断器就永久卡在 HALF_OPEN 拒绝一切请求。
                    if (halfOpenPermits <= 0
                            && now - halfOpenStartedAtMillis >= config.openSeconds() * 1000L) {
                        log.warn("半开探测超时未回收，重新发放探测名额: key={} 已过半开 {}ms",
                                supplierKey, now - halfOpenStartedAtMillis);
                        enterHalfOpenLocked("半开探测超时未回收，重新发放探测名额");
                        persistLocked(true);
                    }
                    if (halfOpenPermits <= 0) {
                        return false;
                    }
                    halfOpenPermits--;
                    return true;
                }
                return true;
            }
        }

        private void onSuccess(long latencyMs) {
            synchronized (lock) {
                totalSuccess++;
                consecutiveFailures = 0;
                lastSuccessTime = Instant.now();
                if (latencyMs > 0) {
                    // 增量均值，避免为算平均响应时间而保留全部样本
                    avgResponseTime = avgResponseTime <= 0
                            ? latencyMs
                            : avgResponseTime + (latencyMs - avgResponseTime) / Math.min(totalSuccess, 1000);
                }

                boolean changed = false;
                if (state == CircuitState.HALF_OPEN) {
                    halfOpenSuccesses++;
                    log.info("熔断半开探测成功: key={} {}/{}", supplierKey, halfOpenSuccesses, config.halfOpenCalls());
                    if (halfOpenSuccesses >= config.halfOpenCalls()) {
                        window.clear();
                        halfOpenPermits = 0;
                        changed = transitionLocked(CircuitState.CLOSED, "半开探测全部成功");
                    }
                } else if (state == CircuitState.CLOSED) {
                    pushLocked(new Call(false, config.slowCallMs() > 0 && latencyMs >= config.slowCallMs()));
                    // 成功路径也必须评估：慢调用往往仍是"成功"，只在失败路径评估会让慢调用阈值永不生效
                    changed = evaluateLocked();
                }
                // OPEN 期间迟到的成功（请求在熔断前发出）：只记账，不改变状态。
                // 三个分支都要走到这里：否则 OPEN 期间的计数只留在内存，DB 会长期漂移。
                persistLocked(changed);
            }
        }

        private void onFailure(long latencyMs) {
            synchronized (lock) {
                totalFailure++;
                consecutiveFailures++;
                lastFailureTime = Instant.now();

                boolean changed = false;
                if (state == CircuitState.HALF_OPEN) {
                    halfOpenPermits = 0;
                    changed = transitionLocked(CircuitState.OPEN, "半开探测失败");
                } else if (state == CircuitState.CLOSED) {
                    pushLocked(new Call(true, config.slowCallMs() > 0 && latencyMs >= config.slowCallMs()));
                    changed = evaluateLocked();
                }
                persistLocked(changed);
            }
        }

        private void pushLocked(Call call) {
            window.addLast(call);
            while (window.size() > config.slidingWindowSize()) {
                window.removeFirst();
            }
        }

        /** 按滑动窗口评估是否熔断。@return 是否发生了状态迁移 */
        private boolean evaluateLocked() {
            if (window.size() < config.minimumCalls()) {
                return false;
            }
            int total = window.size();
            int failed = 0;
            int slow = 0;
            for (Call c : window) {
                if (c.failed()) failed++;
                if (c.slow()) slow++;
            }
            double failureRate = failed * 100.0 / total;
            if (failureRate >= config.failureRateThreshold()) {
                openLocked(String.format("失败率 %.1f%% (%d/%d) 达阈值 %.1f%%",
                        failureRate, failed, total, config.failureRateThreshold()));
                return true;
            }
            if (config.slowCallMs() > 0) {
                double slowRate = slow * 100.0 / total;
                if (slowRate >= config.slowCallRateThreshold()) {
                    openLocked(String.format("慢调用比例 %.1f%% (%d/%d，慢调用≥%dms) 达阈值 %.1f%%",
                            slowRate, slow, total, config.slowCallMs(), config.slowCallRateThreshold()));
                    return true;
                }
            }
            return false;
        }

        private void openLocked(String reason) {
            openedAtMillis = System.currentTimeMillis();
            halfOpenPermits = 0;
            transitionLocked(CircuitState.OPEN, reason);
        }

        /** 人工复位：清窗口、清探测状态、回 CLOSED。 */
        private void resetToClosed() {
            synchronized (lock) {
                window.clear();
                halfOpenPermits = 0;
                halfOpenSuccesses = 0;
                consecutiveFailures = 0;
                transitionLocked(CircuitState.CLOSED, "人工复位");
                persistLocked(true);
            }
        }

        private void enterHalfOpenLocked(String reason) {
            halfOpenStartedAtMillis = System.currentTimeMillis();
            halfOpenPermits = config.halfOpenCalls();
            halfOpenSuccesses = 0;
            transitionLocked(CircuitState.HALF_OPEN, reason);
        }

        /** 状态迁移（幂等：目标状态与当前相同时什么都不做）。@return 是否真的发生了迁移 */
        private boolean transitionLocked(CircuitState to, String reason) {
            if (state == to) {
                return false;
            }
            CircuitState from = state;
            state = to;
            if (to == CircuitState.OPEN) {
                log.error("熔断器 OPEN: key={} ← {} 原因={} 冷却={}s", supplierKey, from, reason, config.openSeconds());
            } else if (to == CircuitState.HALF_OPEN) {
                log.warn("熔断器 HALF_OPEN: key={} ← {} 原因={}", supplierKey, from, reason);
            } else {
                log.info("熔断器 CLOSED: key={} ← {} 原因={}", supplierKey, from, reason);
            }
            try {
                eventPublisher.publishEvent(new CircuitStateChangedEvent(supplierKey, from, to, reason, Instant.now()));
            } catch (Exception e) {
                log.warn("发布熔断状态事件失败(非关键): key={} error={}", supplierKey, e.getMessage());
            }
            return true;
        }

        /** 写穿到 {@code supplier_state}。 */
        private void persistLocked(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastPersistAt < persistIntervalMs) {
                return;
            }
            lastPersistAt = now;
            try {
                SupplierState doc = new SupplierState();
                doc.setId(docId);
                doc.setSupplierKey(supplierKey);
                doc.setPlatformCode(platformCode);
                doc.setFeatureCode(featureCode);
                doc.setProviderKey(providerKey);
                doc.setCircuitState(state.name());
                doc.setCircuitOpenedAt(state == CircuitState.CLOSED ? null : Instant.ofEpochMilli(
                        state == CircuitState.OPEN ? openedAtMillis : now));
                doc.setHealthStatus(healthStatusOf(state));
                doc.setConsecutiveFailures(consecutiveFailures);
                doc.setTotalSuccess(totalSuccess);
                doc.setTotalFailure(totalFailure);
                doc.setLastSuccessTime(lastSuccessTime);
                doc.setLastFailureTime(lastFailureTime);
                doc.setAvgResponseTime(avgResponseTime);
                doc.setLastHeartbeat(Instant.now());
                doc.setUpdateTime(Instant.now());
                supplierStateDao.save(doc);
                // save 之后文档可能被赋 _id（首次插入），回填以便后续按 _id 更新
                if (docId == null) {
                    docId = doc.getId();
                }
            } catch (Exception e) {
                // 落库失败不能影响采集主流程；内存状态仍然正确
                log.warn("熔断状态落库失败(非关键): key={} error={}", supplierKey, e.getMessage());
            }
        }

        private String healthStatusOf(CircuitState s) {
            return switch (s) {
                case CLOSED -> "UP";
                case HALF_OPEN -> "DEGRADED";
                case OPEN -> "DOWN";
            };
        }
    }
}
