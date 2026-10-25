package com.sysj.collector.core.router;


import com.sysj.collector.core.circuit.ProviderCircuitBreaker;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.ratelimit.AdaptiveDelayPolicy;
import com.sysj.collector.core.ratelimit.AdaptiveRateLimiter;
import com.sysj.collector.core.ratelimit.FlowEffect;


import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;

import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import com.sysj.collector.exception.CollectorException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
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
 * 3. 健康过滤：运维 kill switch `providers[].is_healthy == false` 剔除（**绝对生效**）
 * 3.1 熔断过滤：熔断器判定不可用的剔除                                  ← 修正 C-12
 *     —— 用 `isEligible`（**纯读**）。修 R1：此处曾误用有副作用的 `allowRequest`，
 *     在候选过滤阶段就扣掉半开探测名额，导致熔断恢复期系统性误拒
 * 3.2 驱逐上限 + panic 兜底（Envoy `outlier_detection` 等价物；缺省"不限制 / 关闭"）
 * 4. 能力过滤（要求全部具备 / 不得具备被排除项）
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

    // ── 熔断 panic 兜底（Envoy outlier_detection.panic_threshold 的等价物）───────────

    /**
     * panic 放行许可的存活窗口（毫秒）：从路由判定"已无可用候选"到真正发起调用之间的最大间隔。
     * 超过该窗口的许可自动作废，避免路由与执行错配后放行一个早已不该放的候选。
     */
    @Value("${collector.circuit.panic-allowance-ttl-ms:5000}")
    private long panicAllowanceTtlMs;

    /** `platformCode:featureCode` → 最近一次 panic 放行的候选（**一次性消费**）。 */
    private final ConcurrentHashMap<String, PanicAllowance> panicAllowances = new ConcurrentHashMap<>();

    /** 一次性 panic 放行许可。 */
    private record PanicAllowance(String providerKey, long armedAt) {
    }

    /**
     * 是否进入 panic（Envoy {@code panic_threshold} 语义）：可用候选占比低于阈值。
     *
     * <p>阈值 {@code <=0} 表示**关闭 panic**（缺省）。Envoy 的缺省是 50%，但本项目候选集只有
     * 1~5 个，50% 会让 3 候选场景下驱逐 2 个就 panic —— 等于把熔断关掉，所以缺省关闭，
     * 由运维按平台规模显式开启。
     */
    private static boolean panicTriggered(int eligibleCount, int poolSize, int panicThresholdPercent) {
        if (poolSize <= 0 || panicThresholdPercent <= 0) {
            return false;
        }
        return eligibleCount * 100L < (long) panicThresholdPercent * poolSize;
    }

    /** 登记一次 panic 放行许可（路由判定"无可用候选"时调用）。 */
    private void armPanicAllowance(String platformCode, String featureCode, String providerKey) {
        panicAllowances.put(pendingKey(platformCode, featureCode),
                new PanicAllowance(providerKey, System.currentTimeMillis()));
    }

    /** 一次性消费 panic 放行许可；key 不匹配、已被并发消费或已过期都不放行。 */
    private boolean consumePanicAllowance(String platformCode, String featureCode, String providerKey) {
        String key = pendingKey(platformCode, featureCode);
        PanicAllowance allowance = panicAllowances.get(key);
        if (allowance == null || !allowance.providerKey().equals(providerKey)) {
            return false;
        }
        if (!panicAllowances.remove(key, allowance)) {
            return false; // 已被同 key 的并发请求消费掉
        }
        return System.currentTimeMillis() - allowance.armedAt() <= panicAllowanceTtlMs;
    }

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
     * @param excludedCapabilities 要求供应商**不具备**（声明了其中任一项即排除）的能力；空集表示不限制
     */
    public record RouteContext(
            String platformCode,
            String featureCode,
            List<ProviderConfig> allProviders,
            FeatureProviderConfig featurePreference,
            String requiredProviderKey,
            java.util.Set<Capability> requiredCapabilities,
            java.util.Set<Capability> excludedCapabilities) {

        /** 常规路由（无强制指定供应商、无能力要求）。 */
        public static RouteContext of(String platformCode, String featureCode,
                                      List<ProviderConfig> allProviders,
                                      FeatureProviderConfig featurePreference) {
            return new RouteContext(platformCode, featureCode, allProviders, featurePreference,
                    null, java.util.Collections.emptySet(), java.util.Collections.emptySet());
        }

        /** 常规路由 + 能力要求。 */
        public static RouteContext of(String platformCode, String featureCode,
                                      List<ProviderConfig> allProviders,
                                      FeatureProviderConfig featurePreference,
                                      java.util.Set<Capability> requiredCapabilities) {
            return of(platformCode, featureCode, allProviders, featurePreference,
                    requiredCapabilities, java.util.Collections.emptySet());
        }

        /**
         * 常规路由 + 双向能力约束。
         *
         * @param requiredCapabilities 必须全部具备；空集不限制
         * @param excludedCapabilities 声明了其中任一项即排除；空集不限制
         */
        public static RouteContext of(String platformCode, String featureCode,
                                      List<ProviderConfig> allProviders,
                                      FeatureProviderConfig featurePreference,
                                      java.util.Set<Capability> requiredCapabilities,
                                      java.util.Set<Capability> excludedCapabilities) {
            return new RouteContext(platformCode, featureCode, allProviders, featurePreference,
                    null,
                    requiredCapabilities == null ? java.util.Collections.emptySet() : requiredCapabilities,
                    excludedCapabilities == null ? java.util.Collections.emptySet() : excludedCapabilities);
        }

        /** 强制指定供应商。 */
        public static RouteContext specified(String platformCode, String featureCode,
                                             List<ProviderConfig> allProviders,
                                             String requiredProviderKey) {
            return new RouteContext(platformCode, featureCode, allProviders, null,
                    requiredProviderKey, java.util.Collections.emptySet(), java.util.Collections.emptySet());
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
     * 候选构建 → 运维 kill switch → 熔断过滤（**纯读** {@code isEligible}）→ 驱逐上限 / panic 兜底
     * → **能力过滤** → 阈值判断。
     * 调用方只需按顺序执行 + 逐候选领取限流令牌；真正的熔断闸门（会消耗半开探测名额）
     * 在执行侧的 {@code CircuitBreakerStage}，**不在这里**。
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
        //    ① providers[].is_healthy —— 运维强制下线开关（只由人改，**绝对生效**：不参与熔断、也不参与 panic 兜底）
        //    ② circuit_state        —— 运行时自动健康（由真实调用结果驱动，可自动恢复）
        //
        //    修 R1：这里的熔断判定必须用**纯读**的 isEligible，绝不能用有副作用的 allowRequest
        //    （后者在冷却期满时会推进 OPEN→HALF_OPEN 并扣掉一个半开探测名额）。旧写法在候选过滤
        //    阶段就把名额烧光，等真正执行时闸门只剩拒绝 —— 熔断恢复期系统性误拒，
        //    half-open-calls=1 时永不自愈。真正的闸门只在 CircuitBreakerStage 处开一次。
        ProviderCircuitBreaker.EjectionPolicy ejectionPolicy = circuitBreaker.ejectionPolicy();
        int ejectionBudget = ejectionPolicy.maxEjectionPercent() >= 100
                ? Integer.MAX_VALUE
                : (int) Math.floor((long) candidates.size() * ejectionPolicy.maxEjectionPercent() / 100.0);
        List<ProviderConfig> opsHealthy = new ArrayList<>(candidates.size());
        List<ProviderConfig> healthy = new ArrayList<>(candidates.size());
        int circuitRejected = 0;
        int readmitted = 0;
        for (ProviderConfig p : candidates) {
            if (!p.isHealthy()) {
                log.debug("供应商被运维强制下线，跳过: key={}", p.getProviderKey());
                continue;
            }
            opsHealthy.add(p);
            if (circuitBreaker.isEligible(ctx.platformCode(), ctx.featureCode(), p.getProviderKey())) {
                healthy.add(p);
                continue;
            }
            if (circuitRejected >= ejectionBudget) {
                // 3.1 驱逐比例上限（Envoy outlier_detection.max_ejection_percent 的等价物）：
                //     再驱逐一个就会超过上限 ⇒ **不驱逐**这个候选，把它留在池子里。
                //     熔断器的目的是隔离坏节点，不是让平台整体不可用（本项目候选集常只有 1~5 个）。
                readmitted++;
                healthy.add(p);
                continue;
            }
            circuitRejected++;
            log.debug("供应商熔断中，跳过: key={} state={}", p.getProviderKey(),
                    circuitBreaker.stateOf(ctx.platformCode(), ctx.featureCode(), p.getProviderKey()));
        }
        if (readmitted > 0) {
            log.warn("熔断驱逐比例达上限({}%)，回收 {} 个候选: platform={} feature={} 已驱逐={} 候选总数={}",
                    ejectionPolicy.maxEjectionPercent(), readmitted, ctx.platformCode(), ctx.featureCode(),
                    circuitRejected, candidates.size());
        }

        // 3.2 panic 兜底（Envoy outlier_detection.panic_threshold 的等价物）：在池候选占比低于阈值时，
        //     忽略熔断隔离结果，放行**一个**最不坏候选（优先仍可用的，其次按优先级取被熔断的那个），
        //     并登记**一次性**许可（交 allowProvider 消费）。缺省 0 = 关闭，不改变既有语义。
        if (panicTriggered(healthy.size(), opsHealthy.size(), ejectionPolicy.panicThresholdPercent())) {
            ProviderConfig fallback = healthy.isEmpty() ? opsHealthy.get(0) : healthy.get(0);
            log.warn("熔断 panic 放行: platform={} feature={} 在池={} 可用={} 阈值={}% 放行 key={}",
                    ctx.platformCode(), ctx.featureCode(), opsHealthy.size(), healthy.size(),
                    ejectionPolicy.panicThresholdPercent(), fallback.getProviderKey());
            healthy = List.of(fallback);
            armPanicAllowance(ctx.platformCode(), ctx.featureCode(), fallback.getProviderKey());
        }

        // 4. 能力过滤（§7.2）：要求"全部满足"，且"不得具备被排除项"。
        //    放在健康过滤之后：健康是"能不能用"，能力是"合不合适"，先排除硬不可用再看匹配度。
        java.util.Set<Capability> required = ctx.requiredCapabilities();
        java.util.Set<Capability> excluded = ctx.excludedCapabilities();
        boolean hasConstraints = (required != null && !required.isEmpty())
                || (excluded != null && !excluded.isEmpty());
        List<ProviderConfig> capable = healthy;
        if (hasConstraints) {
            capable = new ArrayList<>(healthy.size());
            for (ProviderConfig p : healthy) {
                java.util.Set<Capability> has = p.capabilitySet();
                // 未声明 capabilities 的供应商按"不限制"处理（向后兼容老配置）
                if (has.isEmpty()
                        || (Capability.covers(has, required)
                            && (excluded == null || excluded.stream().noneMatch(has::contains)))) {
                    capable.add(p);
                } else {
                    log.debug("供应商能力不匹配，跳过: key={} 需要={} 排除={} 具备={}",
                            p.getProviderKey(), required, excluded, has);
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
            String reason = emptyReason(healthy, required, excluded);
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
    private String emptyReason(List<ProviderConfig> healthy, java.util.Set<Capability> required,
                               java.util.Set<Capability> excluded) {
        if (healthy.isEmpty()) {
            return "当前无可用供应商（全部被熔断隔离或运维下线）";
        }
        boolean hasRequired = required != null && !required.isEmpty();
        boolean hasExcluded = excluded != null && !excluded.isEmpty();
        if (hasRequired || hasExcluded) {
            StringBuilder sb = new StringBuilder("无候选满足能力约束");
            if (hasRequired) {
                sb.append("（要求全部具备 ").append(required).append("）");
            }
            if (hasExcluded) {
                sb.append("（要求不具备 ").append(excluded).append("）");
            }
            sb.append("；实际候选能力: ");
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
        String key = AdaptiveRateLimiter.buildKey(platformCode, featureCode, provider.getProviderKey());
        return adaptiveRateLimiter.acquire(key, flowSpecOf(provider));
    }

    /** 当前有效速率（供监控/状态接口）。 */
    public double effectiveRateOf(String platformCode, String featureCode, ProviderConfig provider) {
        String key = AdaptiveRateLimiter.buildKey(platformCode, featureCode, provider.getProviderKey());
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
     *   <li>{@code select} 的过滤发生在"选候选"的时刻，作用是**择优** —— 不要把明显不通的候选排进来，
     *       但它必须是**纯读**（{@code isEligible}），不得有任何副作用；</li>
     *   <li>本方法发生在"真正执行"的时刻，作用是**闸门** —— 两刻之间可能隔着限流排队与候选遍历，
     *       熔断器完全可能刚从 CLOSED 跳到 OPEN（典型 TOCTOU）。</li>
     * </ul>
     *
     * <p><b>有副作用</b>：冷却期结束时会把状态推进到 HALF_OPEN 并占一个半开探测名额。
     * 因此它不是幂等查询，**每条调用链只能调用一次**（由 {@code CircuitBreakerStage} 保证）；
     * 只读查询请用 {@link #circuitStateOf}。修 R1 后，整个路由里**只有这里**会消耗半开探测名额。
     *
     * <p>panic 兜底：若路由刚因 panic 阈值登记了一次性许可（且候选正是本供应商），
     * 这里在熔断器拒绝时放行一次，并立即消费掉许可（见 {@link #consumePanicAllowance}）。
     */
    public boolean allowProvider(String platformCode, String featureCode, String providerKey) {
        if (circuitBreaker.allowRequest(platformCode, featureCode, providerKey)) {
            consumePanicAllowance(platformCode, featureCode, providerKey); // 清掉可能残留的许可
            return true;
        }
        if (consumePanicAllowance(platformCode, featureCode, providerKey)) {
            log.warn("熔断 panic 放行生效（忽略熔断隔离一次）: platform={} feature={} key={}",
                    platformCode, featureCode, providerKey);
            return true;
        }
        return false;
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
                AdaptiveRateLimiter.buildKey(platformCode, featureCode, providerKey), latencyMs, success, params);
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
