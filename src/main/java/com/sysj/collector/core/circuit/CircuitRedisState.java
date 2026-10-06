package com.sysj.collector.core.circuit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.sysj.collector.domain.document.SupplierState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器的 Redis 共享状态层（阶段2 多实例共享）—— 状态机迁移的**唯一真相源**。
 *
 * <p>此前熔断状态是每实例一份内存 + Mongo 写穿（观测），多实例下"A 实例熔断、
 * B 实例继续打"。现在状态放 Redis，迁移用 **Lua 脚本原子完成**：
 * <ul>
 *   <li>状态机：{@code CLOSED → OPEN → HALF_OPEN → CLOSED}，语义与原内存实现一致
 *       （冷却期结束自动转半开、半开探测名额、探测名额超时重新发放）；</li>
 *   <li>滑动窗口：ZSET（member = 标志位+序号），按"最近 N 次"裁剪，评估口径见
 *       {@link SlidingWindowEvaluator}；</li>
 *   <li>转 CLOSED 时顺带写预热起点（自适应限速的 warm-up 恢复联动，替代原 Spring 事件监听）。</li>
 * </ul>
 *
 * <p>每次 allow/上报 = 一次 Redis 往返（本机 Redis 亚毫秒级），相对秒级的供应商调用可忽略。
 *
 * <h3>Redis 数据丢失的兜底</h3>
 * Redis 被清空后所有供应商从 CLOSED 起步（重新观察失败率）。为避免
 * "Redis 重启但 Mongo 里明明是 OPEN"的窗口，首次触碰某供应商时若 Redis 无状态且
 * Mongo {@code supplier_state.circuit_state == OPEN}，则把 OPEN 状态**播种**回 Redis。
 */
@Slf4j
@Component
public class CircuitRedisState {

    @Value("${collector.circuit.redis-key-prefix:collector:circuit}")
    private String keyPrefix;

    private final StringRedisTemplate redisTemplate;

    /** 本实例已触碰过的供应商 key（snapshot/trackedCount 用；同时守护 Mongo 播种只做一次）。 */
    private final ConcurrentHashMap<String, Boolean> knownKeys = new ConcurrentHashMap<>();

    /**
     * 放行判定 + 冷却期满转半开 + 半开名额扣减 + 名额超时重发。
     * KEYS=[state]；ARGV=[now, openSeconds, halfOpenCalls]。
     * 返回 {allowed(0/1), prevState, newState}。
     */
    private static final DefaultRedisScript<List> TRY_ACQUIRE = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local openMs = tonumber(ARGV[2]) * 1000
            local halfCalls = tonumber(ARGV[3])
            local state = redis.call('HGET', KEYS[1], 'state')
            local prev = state or 'CLOSED'
            if prev == 'OPEN' then
              local openedAt = tonumber(redis.call('HGET', KEYS[1], 'openedAt') or '0')
              if now - openedAt < openMs then
                return {'0', prev, 'OPEN'}
              end
              redis.call('HMSET', KEYS[1], 'state', 'HALF_OPEN', 'halfOpenStarted', now,
                         'halfOpenPermits', halfCalls, 'halfOpenSuccesses', 0)
              return {'1', prev, 'HALF_OPEN'}
            end
            if prev == 'HALF_OPEN' then
              local permits = tonumber(redis.call('HGET', KEYS[1], 'halfOpenPermits') or '0')
              local started = tonumber(redis.call('HGET', KEYS[1], 'halfOpenStarted') or '0')
              if permits <= 0 then
                if now - started >= openMs then
                  redis.call('HMSET', KEYS[1], 'halfOpenStarted', now,
                             'halfOpenPermits', halfCalls, 'halfOpenSuccesses', 0)
                  permits = halfCalls
                else
                  return {'0', prev, 'HALF_OPEN'}
                end
              end
              redis.call('HINCRBY', KEYS[1], 'halfOpenPermits', -1)
              return {'1', prev, 'HALF_OPEN'}
            end
            if not state then
              redis.call('HSET', KEYS[1], 'state', 'CLOSED')
            end
            return {'1', prev, 'CLOSED'}
            """, List.class);

    /**
     * 记录一次结果：半开探测成败迁移 / CLOSED 滑动窗口评估熔断 / OPEN 迟到结果只记账。
     * KEYS=[state, window, seq, warmup]；
     * ARGV=[now, failed, slow, windowSize, minimumCalls, failureRateThreshold, slowRateThreshold, halfOpenCalls]。
     * 返回 {changed(0/1), from, to, reason}。
     */
    private static final DefaultRedisScript<List> RECORD_OUTCOME = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local failed = tonumber(ARGV[2])
            local slow = tonumber(ARGV[3])
            local windowSize = tonumber(ARGV[4])
            local minCalls = tonumber(ARGV[5])
            local failThr = tonumber(ARGV[6])
            local slowThr = tonumber(ARGV[7])
            local halfCalls = tonumber(ARGV[8])
            local state = redis.call('HGET', KEYS[1], 'state') or 'CLOSED'
            local prev = state
            if state == 'HALF_OPEN' then
              if failed == 1 then
                redis.call('HMSET', KEYS[1], 'state', 'OPEN', 'openedAt', now, 'halfOpenPermits', 0)
                redis.call('DEL', KEYS[2])
                return {'1', prev, 'OPEN', '半开探测失败'}
              end
              local succ = tonumber(redis.call('HINCRBY', KEYS[1], 'halfOpenSuccesses', 1))
              if succ >= halfCalls then
                redis.call('HMSET', KEYS[1], 'state', 'CLOSED', 'openedAt', 0, 'halfOpenPermits', 0)
                redis.call('DEL', KEYS[2])
                redis.call('SET', KEYS[4], now)
                return {'1', prev, 'CLOSED', '半开探测全部成功'}
              end
              return {'0', prev, 'HALF_OPEN', '半开探测成功 ' .. succ .. '/' .. halfCalls}
            end
            if state == 'OPEN' then
              -- 熔断前发出的请求迟到返回：只记账不改状态（窗口已清）
              return {'0', prev, 'OPEN', ''}
            end
            local seq = redis.call('INCR', KEYS[3])
            local flags = (failed == 1 and 'f' or '') .. (slow == 1 and 's' or '')
            redis.call('ZADD', KEYS[2], seq, flags .. ':' .. seq)
            local card = redis.call('ZCARD', KEYS[2])
            if card > windowSize then
              redis.call('ZREMRANGEBYRANK', KEYS[2], 0, card - windowSize - 1)
            end
            card = redis.call('ZCARD', KEYS[2])
            if card >= minCalls then
              local members = redis.call('ZRANGE', KEYS[2], 0, -1)
              local f = 0
              local s = 0
              for i = 1, #members do
                local flagPart = string.match(members[i], '^(.-):')
                if string.find(flagPart or '', 'f', 1, true) then f = f + 1 end
                if string.find(flagPart or '', 's', 1, true) then s = s + 1 end
              end
              local failRate = f * 100.0 / card
              if failRate >= failThr then
                redis.call('HMSET', KEYS[1], 'state', 'OPEN', 'openedAt', now, 'halfOpenPermits', 0)
                redis.call('DEL', KEYS[2])
                return {'1', prev, 'OPEN', string.format('失败率 %.1f%% (%d/%d) 达阈值 %.1f%%', failRate, f, card, failThr)}
              end
              if slowThr > 0 then
                local slowRate = s * 100.0 / card
                if slowRate >= slowThr then
                  redis.call('HMSET', KEYS[1], 'state', 'OPEN', 'openedAt', now, 'halfOpenPermits', 0)
                  redis.call('DEL', KEYS[2])
                  return {'1', prev, 'OPEN', string.format('慢调用比例 %.1f%% (%d/%d) 达阈值 %.1f%%', slowRate, s, card, slowThr)}
                end
              end
            end
            return {'0', prev, 'CLOSED', ''}
            """, List.class);

    /** 人工复位：KEYS=[state, window, warmup]；ARGV=[now]。 */
    private static final DefaultRedisScript<Long> RESET_SCRIPT = new DefaultRedisScript<>("""
            redis.call('HMSET', KEYS[1], 'state', 'CLOSED', 'openedAt', 0,
                       'halfOpenPermits', 0, 'halfOpenSuccesses', 0)
            redis.call('DEL', KEYS[2])
            redis.call('SET', KEYS[3], ARGV[1])
            return 1
            """, Long.class);

    public CircuitRedisState(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ── 对外操作 ───────────────────────────────────────────────────────────

    /**
     * 放行判定（原子）。副作用：冷却期满自动转 HALF_OPEN、占半开名额、名额超时重发。
     *
     * @param seedFromMongo Mongo 侧历史状态（首次触碰时播种用，可为 null）
     */
    public AcquireResult tryAcquire(String supplierKey, long now, long openSeconds, int halfOpenCalls,
                                    SupplierState seedFromMongo) {
        registerAndSeed(supplierKey, seedFromMongo);
        List<Object> result = redisTemplate.execute(TRY_ACQUIRE,
                List.of(stateKey(supplierKey)),
                String.valueOf(now), String.valueOf(openSeconds), String.valueOf(halfOpenCalls));
        return new AcquireResult(
                "1".equals(str(result, 0)),
                str(result, 1) == null ? "CLOSED" : str(result, 1),
                str(result, 2) == null ? "CLOSED" : str(result, 2));
    }

    /**
     * 记录一次调用结果（原子）。返回是否发生了状态迁移（调用方据此发事件/立即落库）。
     */
    public OutcomeResult recordOutcome(String supplierKey, long now, boolean failed, boolean slow,
                                       CircuitBreakerConfig config) {
        List<Object> result = redisTemplate.execute(RECORD_OUTCOME,
                List.of(stateKey(supplierKey), windowKey(supplierKey), seqKey(supplierKey),
                        warmupKeyOf(supplierKey)),
                String.valueOf(now),
                failed ? "1" : "0",
                slow ? "1" : "0",
                String.valueOf(config.slidingWindowSize()),
                String.valueOf(config.minimumCalls()),
                String.valueOf(config.failureRateThreshold()),
                String.valueOf(config.slowCallRateThreshold()),
                String.valueOf(config.halfOpenCalls()));
        return new OutcomeResult(
                "1".equals(str(result, 0)),
                str(result, 1) == null ? "CLOSED" : str(result, 1),
                str(result, 2) == null ? "CLOSED" : str(result, 2),
                str(result, 3) == null ? "" : str(result, 3));
    }

    /** 当前状态（HGET state）。 */
    public CircuitState stateOf(String supplierKey) {
        Object state = redisTemplate.opsForHash().get(stateKey(supplierKey), "state");
        return parseState(state == null ? null : state.toString());
    }

    /** 人工复位到 CLOSED 并清窗口（同时重置预热起点）。 */
    public void reset(String supplierKey) {
        knownKeys.put(supplierKey, Boolean.TRUE);
        redisTemplate.execute(RESET_SCRIPT,
                List.of(stateKey(supplierKey), windowKey(supplierKey), warmupKeyOf(supplierKey)),
                String.valueOf(System.currentTimeMillis()));
    }

    /** 本实例已知的供应商 key 集合（快照用）。 */
    public List<String> knownSupplierKeys() {
        return List.copyOf(knownKeys.keySet());
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /** 首次触碰：注册 key；若 Redis 无状态且 Mongo 历史为 OPEN，播种回 Redis。 */
    private void registerAndSeed(String supplierKey, SupplierState seedFromMongo) {
        if (knownKeys.putIfAbsent(supplierKey, Boolean.TRUE) != null) {
            return;
        }
        if (seedFromMongo == null || !"OPEN".equals(seedFromMongo.getCircuitState())) {
            return;
        }
        try {
            Boolean exists = redisTemplate.hasKey(stateKey(supplierKey));
            if (Boolean.FALSE.equals(exists)) {
                long openedAt = seedFromMongo.getCircuitOpenedAt() == null
                        ? System.currentTimeMillis()
                        : seedFromMongo.getCircuitOpenedAt().toEpochMilli();
                redisTemplate.opsForHash().putAll(stateKey(supplierKey), Map.of(
                        "state", "OPEN",
                        "openedAt", String.valueOf(openedAt)));
                log.warn("Redis 无熔断状态，已从 Mongo 播种 OPEN: key={} openedAt={}", supplierKey, openedAt);
            }
        } catch (Exception e) {
            log.warn("熔断状态播种失败（按 Redis 现状继续）: key={} error={}", supplierKey, e.getMessage());
        }
    }

    private String stateKey(String supplierKey) {
        return keyPrefix + ":state:" + supplierKey;
    }

    private String windowKey(String supplierKey) {
        return keyPrefix + ":window:" + supplierKey;
    }

    private String seqKey(String supplierKey) {
        return keyPrefix + ":seq:" + supplierKey;
    }

    /** 预热起点 key：熔断恢复转 CLOSED 时由 Lua 写入（自适应限速读取）。 */
    public String warmupKeyOf(String supplierKey) {
        return keyPrefix + ":warmup:" + supplierKey;
    }

    private static String str(List<Object> result, int index) {
        if (result == null || index >= result.size() || result.get(index) == null) {
            return null;
        }
        return result.get(index).toString();
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

    /** tryAcquire 结果。 */
    public record AcquireResult(boolean allowed, String prevState, String newState) {
    }

    /** recordOutcome 结果。 */
    public record OutcomeResult(boolean changed, String from, String to, String reason) {
    }
}
