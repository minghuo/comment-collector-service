package com.sysj.collector.core.router;


import com.sysj.collector.core.circuit.ProviderCircuitBreaker;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.ratelimit.AdaptiveDelayPolicy;
import com.sysj.collector.core.ratelimit.AdaptiveRateLimiter;
import com.sysj.collector.core.ratelimit.FlowEffect;
import com.sysj.collector.core.ratelimit.ProviderRateLimitManager;

import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;

import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import com.sysj.collector.exception.CollectorException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 动态供应商路由器 —— <b>唯一路由入口</b>（修正 C-17）。
 *
 * <p>此前存在两套并行实现：{@code route()}/{@code buildCandidates()}（死代码）与
 * {@code buildOrderedCandidates()}（门面在用）。两者逐行重复、行为不一致，
 * 且门面自行过滤健康状态、绕过了 {@code activation_threshold}（C-18）。
 * 现在统一为 {@link #select(RouteContext)} 一个入口。
 *
 * <h3>内部处理顺序</h3>
 * <pre>
 * 1. 指定供应商分支（requiredProviderKey 非空）→ 直接返回该供应商，跳过后续过滤
 * 2. 候选构建：用户等级偏好 providerOrder 在前，其余按 priority 升序追加
 * 3. 健康过滤：运维 kill switch `providers[].is_healthy == false` 剔除
 * 4. 熔断过滤：`ProviderCircuitBreaker` 判定 OPEN（或半开名额已满）的剔除   ← 修正 C-12
 * 5. 激活阈值：pending &lt; activation_threshold 时只保留候选首位
 * </pre>
 *
 * <h3>健康状态的两层语义（修正 C-12）</h3>
 * <ul>
 *   <li>{@code providerCircuitBreaker} 的 {@code supplier_state.circuit_state} = <b>运行时自动健康</b>，
 *       由真实调用结果驱动，可自动恢复（OPEN → HALF_OPEN → CLOSED）；</li>
 *   <li>{@code providers[].is_healthy} = <b>运维强制下线开关</b>，只由人改，程序不写；</li>
 *   <li>两者**取与**：都放行才可用。原先"写 {@code supplier_state}、读 {@code is_healthy}"的分裂已消除。</li>
 * </ul>
 *
 * <h3>为何流控不在这里</h3>
 * 限流令牌按"真正要执行的那一刻"领取：门面在遍历候选取用某个供应商前调用
 * {@link #tryAcquireProvider}。若在候选构建阶段就把所有候选的令牌领掉，
 * 未被使用的候选会白白消耗配额。
 *
 * <p>无熔断器：健康状态由 DB 字段控制，不做程序探测。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicProviderRouter {

    private final AdaptiveRateLimiter adaptiveRateLimiter;
    private final ProviderCircuitBreaker circuitBreaker;

    /** platformCode:featureCode → 待处理任务计数 */
    private final ConcurrentHashMap<String, AtomicInteger> pendingCountMap = new ConcurrentHashMap<>();

    // ── 路由上下文 ─────────────────────────────────────────────────────────

    /**
     * 一次路由请求的全部输入。
     *
     * @param platformCode       平台编码
     * @param featureCode        功能编码
     * @param allProviders       该功能下全部供应商（DB 返回，<b>顺序不作为路由依据</b>）
     * @param featurePreference  用户等级在此功能下的偏好配置，可为 null
     * @param requiredProviderKey 强制指定的供应商 key；非空时跳过偏好/健康/阈值/能力
     * @param requiredCapabilities 要求供应商**全部具备**的能力；空集表示不限制
     */
    public record RouteContext(
            String platformCode,
            String featureCode,
            List<ProviderConfig> allProviders,
            FeatureProviderConfig featurePreference,
            String requiredProviderKey,
            java.util.Set<Capability> requiredCapabilities) {

        /** 常规路由（无强制指定供应商、无能力要求）。 */
        public static RouteContext of(String platformCode, String featureCode,
                                      List<ProviderConfig> allProviders,
                                      FeatureProviderConfig featurePreference) {
            return new RouteContext(platformCode, featureCode, allProviders, featurePreference,
                    null, java.util.Collections.emptySet());
        }

        /** 常规路由 + 能力要求。 */
        public static RouteContext of(String platformCode, String featureCode,
                                      List<ProviderConfig> allProviders,
                                      FeatureProviderConfig featurePreference,
                                      java.util.Set<Capability> requiredCapabilities) {
            return new RouteContext(platformCode, featureCode, allProviders, featurePreference,
                    null, requiredCapabilities == null ? java.util.Collections.emptySet() : requiredCapabilities);
        }

        /** 强制指定供应商。 */
        public static RouteContext specified(String platformCode, String featureCode,
                                             List<ProviderConfig> allProviders,
                                             String requiredProviderKey) {
            return new RouteContext(platformCode, featureCode, allProviders, null,
                    requiredProviderKey, java.util.Collections.emptySet());
        }
    }

    /**
     * 路由结果。
     *
     * @param candidates 可依次尝试的候选（可能为空）
     * @param reason     候选为空时的**可诊断原因**；非空时为空串
     */
    public record RouteResult(List<ProviderConfig> candidates, String reason) {
        public boolean isEmpty() {
            return candidates.isEmpty();
        }
    }

    // ── 唯一路由入口 ───────────────────────────────────────────────────────

    /** 便捷入口：只关心候选列表；需要失败原因时用 {@link #selectDetailed(RouteContext)}。 */
    public List<ProviderConfig> select(RouteContext ctx) {
        return selectDetailed(ctx).candidates();
    }

    /**
     * 选出本次请求可依次尝试的候选供应商（有序），并给出为空时的可诊断原因。
     *
     * <p>候选列表按 §7.1 的顺序产出：
     * 候选构建 → 运维 kill switch → 熔断过滤 → **能力过滤** → 阈值判断。
     * 调用方只需按顺序执行 + 逐候选领取限流令牌。
     */
    public RouteResult selectDetailed(RouteContext ctx) {
        List<ProviderConfig> all = ctx.allProviders();
        if (all == null || all.isEmpty()) {
            log.warn("功能未配置供应商: platform={} feature={}", ctx.platformCode(), ctx.featureCode());
            return new RouteResult(List.of(), "该功能未配置供应商");
        }

        // 1. 强制指定供应商：特殊需求，跳过偏好 / 健康 / 阈值 / 能力
        if (ctx.requiredProviderKey() != null && !ctx.requiredProviderKey().isBlank()) {
            String required = ctx.requiredProviderKey();
            return all.stream()
                    .filter(p -> required.equals(p.getProviderKey()))
                    .findFirst()
                    .map(p -> new RouteResult(List.of(p), ""))
                    .orElseThrow(() -> new CollectorException("指定供应商不在配置列表中: " + required));
        }

        // 2. 候选构建：偏好优先 + 全局按 priority 显式升序
        List<ProviderConfig> candidates = buildCandidates(all, ctx.featurePreference());

        // 3. 健康过滤：两层语义取与
        //    ① providers[].is_healthy —— 运维强制下线开关（只由人改）
        //    ② circuit_state        —— 运行时自动健康（由真实调用结果驱动，可自动恢复）
        List<ProviderConfig> healthy = new ArrayList<>(candidates.size());
        for (ProviderConfig p : candidates) {
            if (!p.isHealthy()) {
                log.debug("供应商被运维强制下线，跳过: key={}", p.getProviderKey());
                continue;
            }
            if (!circuitBreaker.allowRequest(ctx.platformCode(), ctx.featureCode(), p.getProviderKey())) {
                log.debug("供应商熔断中，跳过: key={} state={}", p.getProviderKey(),
                        circuitBreaker.stateOf(ctx.platformCode(), ctx.featureCode(), p.getProviderKey()));
                continue;
            }
            healthy.add(p);
        }

        // 4. 能力过滤（§7.2）：要求"全部满足"。
        //    放在健康过滤之后：健康是"能不能用"，能力是"合不合适"，先排除硬不可用再看匹配度。
        java.util.Set<Capability> required = ctx.requiredCapabilities();
        List<ProviderConfig> capable = healthy;
        if (required != null && !required.isEmpty()) {
            capable = new ArrayList<>(healthy.size());
            for (ProviderConfig p : healthy) {
                java.util.Set<Capability> has = p.capabilitySet();
                // 未声明 capabilities 的供应商按"不限制"处理（向后兼容老配置）
                if (has.isEmpty() || Capability.covers(has, required)) {
                    capable.add(p);
                } else {
                    log.debug("供应商能力不匹配，跳过: key={} 需要={} 具备={}", p.getProviderKey(), required, has);
                }
            }
        }

        // 5. 激活阈值：待处理任务量不足时只允许候选首位（修正 C-18）
        FeatureProviderConfig pref = ctx.featurePreference();
        int threshold = pref != null ? pref.getActivationThreshold() : 0;
        if (threshold > 0 && capable.size() > 1) {
            int pending = getPendingCount(ctx.platformCode(), ctx.featureCode());
            if (pending < threshold) {
                ProviderConfig first = capable.get(0);
                log.debug("未达激活阈值，仅启用首位供应商: platform={} feature={} pending={} threshold={} key={}",
                        ctx.platformCode(), ctx.featureCode(), pending, threshold, first.getProviderKey());
                capable = List.of(first);
            }
        }

        if (capable.isEmpty()) {
            String reason = emptyReason(healthy, required);
            log.error("所有供应商均不可用: platform={} feature={} 原因={}",
                    ctx.platformCode(), ctx.featureCode(), reason);
            return new RouteResult(List.of(), reason);
        }
        return new RouteResult(capable, "");
    }

    /**
     * 给出"为什么没有候选"的可诊断原因。
     *
     * <p>刻意区分"能力不满足"与"全部不可用"：前者是**请求侧要求过高或配置缺能力**，
     * 后者是**供应商侧故障**，处置完全不同（改请求/补配置 vs 等恢复/修上游）。
     */
    private String emptyReason(List<ProviderConfig> healthy, java.util.Set<Capability> required) {
        if (healthy.isEmpty()) {
            return "当前无可用供应商（全部被熔断隔离或运维下线）";
        }
        if (required != null && !required.isEmpty()) {
            StringBuilder sb = new StringBuilder("无候选满足所需能力 ").append(required).append("；实际候选能力: ");
            for (int i = 0; i < healthy.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(healthy.get(i).getProviderKey()).append('=').append(healthy.get(i).capabilitySet());
            }
            return sb.toString();
        }
        return "当前无可用供应商";
    }

    // ── 候选列表构建 ───────────────────────────────────────────────────────

    /**
     * 合并用户偏好与全局列表，构建有序候选列表。
     *
     * <ul>
     *   <li>偏好列表中存在且 DB 中有效的供应商，按 {@code providerOrder} 给出的顺序排前面</li>
     *   <li>偏好未覆盖的供应商，<b>按 priority 显式升序</b>追加（不再依赖 DB 数组物理顺序，修正 C-17）</li>
     *   <li>同 priority 时保持 DB 返回顺序（稳定排序）</li>
     * </ul>
     */
    private List<ProviderConfig> buildCandidates(
            List<ProviderConfig> allProviders,
            FeatureProviderConfig featureConfig) {

        List<ProviderConfig> result = new ArrayList<>(allProviders.size());
        Set<String> added = new LinkedHashSet<>();

        // 偏好内的相对顺序 = providerOrder 顺序
        if (featureConfig != null && featureConfig.getProviderOrder() != null
                && !featureConfig.getProviderOrder().isEmpty()) {
            Map<String, ProviderConfig> providerMap = new HashMap<>();
            for (ProviderConfig p : allProviders) {
                providerMap.putIfAbsent(p.getProviderKey(), p);
            }
            for (String prefKey : featureConfig.getProviderOrder()) {
                ProviderConfig p = providerMap.get(prefKey);
                if (p != null && added.add(prefKey)) {
                    result.add(p);
                }
            }
        }

        // 全局兜底：显式按 priority 升序
        allProviders.stream()
                .sorted(Comparator.comparingInt(ProviderConfig::getPriority))
                .forEach(p -> {
                    if (added.add(p.getProviderKey())) {
                        result.add(p);
                    }
                });

        return result;
    }

    // ── 待处理任务计数 ─────────────────────────────────────────────────────

    public void incrementPending(String platformCode, String featureCode) {
        pendingCountMap.computeIfAbsent(pendingKey(platformCode, featureCode),
                k -> new AtomicInteger()).incrementAndGet();
    }

    public void decrementPending(String platformCode, String featureCode) {
        AtomicInteger c = pendingCountMap.get(pendingKey(platformCode, featureCode));
        if (c != null) c.decrementAndGet();
    }

    public int getPendingCount(String platformCode, String featureCode) {
        AtomicInteger c = pendingCountMap.get(pendingKey(platformCode, featureCode));
        return c != null ? c.get() : 0;
    }

    private String pendingKey(String platformCode, String featureCode) {
        return platformCode + ":" + featureCode;
    }

    // ── 限流（自适应速率 + 流控效果） ──────────────────────────────────────

    /**
     * 尝试获取一次调用许可。
     *
     * <p>有效速率 = {@code min(rate_per_second, 1000 / adaptive_delay_ms)}，
     * 再按 {@code flowEffect} 决定取不到令牌时的行为（§8.3）。
     * 由调用方在**真正执行某个候选之前**调用。
     */
    public boolean tryAcquireProvider(String platformCode, String featureCode, ProviderConfig provider) {
        String key = ProviderRateLimitManager.buildKey(platformCode, featureCode, provider.getProviderKey());
        return adaptiveRateLimiter.acquire(key, flowSpecOf(provider));
    }

    /** 当前有效速率（供监控/状态接口）。 */
    public double effectiveRateOf(String platformCode, String featureCode, ProviderConfig provider) {
        String key = ProviderRateLimitManager.buildKey(platformCode, featureCode, provider.getProviderKey());
        return adaptiveRateLimiter.effectiveRate(key, flowSpecOf(provider));
    }

    /** 由供应商配置构造流控规格（缺省字段由 ProviderConfig 的 *OrDefault 兜底）。 */
    private AdaptiveRateLimiter.FlowSpec flowSpecOf(ProviderConfig provider) {
        AdaptiveDelayPolicy.Params params = new AdaptiveDelayPolicy.Params(
                provider.targetConcurrencyOrDefault(),
                provider.startDelayOrDefault(),
                provider.maxDelayOrDefault());
        return new AdaptiveRateLimiter.FlowSpec(
                provider.getRatePerSecond(),
                params,
                FlowEffect.of(provider.getFlowEffect()),
                provider.getMaxQueueWaitMs() == null ? 0L : provider.getMaxQueueWaitMs());
    }

    // ── 供应商运行时状态（熔断 + 自适应限速，统一上报入口） ────────────────

    /**
     * 熔断闸门：判断此刻是否允许向该供应商发起调用。
     *
     * <p>与 {@link #select} 里的熔断过滤是**两件事**：
     * <ul>
     *   <li>{@code select} 的过滤发生在"选候选"的时刻，作用是**择优** —— 不要把明显不通的候选排进来；</li>
     *   <li>本方法发生在"真正执行"的时刻，作用是**闸门** —— 两刻之间可能隔着限流排队与候选遍历，
     *       熔断器完全可能刚从 CLOSED 跳到 OPEN（典型 TOCTOU）。</li>
     * </ul>
     *
     * <p><b>有副作用</b>：冷却期结束时会把状态推进到 HALF_OPEN 并占一个半开探测名额。
     * 因此它不是幂等查询，**每条调用链只能调用一次**（由 {@code CircuitBreakerStage} 保证），
     * 只读查询请用 {@link #circuitStateOf}。
     */
    public boolean allowProvider(String platformCode, String featureCode, String providerKey) {
        return circuitBreaker.allowRequest(platformCode, featureCode, providerKey);
    }

    /**
     * 上报一次调用结果 —— **熔断器与自适应限速的唯一上报入口**。
     *
     * <p>刻意做成一个方法而不是两个：{@code C-12} 的教训就是"同一份事实被两条路径分别写入"，
     * 一旦某个调用点漏报其中一个，两者的视图就会永久分裂。
     */
    public void markProviderOutcome(String platformCode, String featureCode,
                                    ProviderConfig provider, long latencyMs, boolean success) {
        String providerKey = provider.getProviderKey();
        AdaptiveDelayPolicy.Params params = new AdaptiveDelayPolicy.Params(
                provider.targetConcurrencyOrDefault(),
                provider.startDelayOrDefault(),
                provider.maxDelayOrDefault());
        if (success) {
            circuitBreaker.recordSuccess(platformCode, featureCode, providerKey, latencyMs);
        } else {
            circuitBreaker.recordFailure(platformCode, featureCode, providerKey, latencyMs);
        }
        adaptiveRateLimiter.recordOutcome(
                ProviderRateLimitManager.buildKey(platformCode, featureCode, providerKey), latencyMs, success, params);
    }

    /** 查询供应商当前熔断状态（供运维接口）。 */
    public com.sysj.collector.core.circuit.CircuitState circuitStateOf(
            String platformCode, String featureCode, String providerKey) {
        return circuitBreaker.stateOf(platformCode, featureCode, providerKey);
    }

    /** 自适应延迟快照（供运维接口）。 */
    public java.util.Map<String, Long> adaptiveDelaySnapshot() {
        return adaptiveRateLimiter.delaySnapshot();
    }
}
