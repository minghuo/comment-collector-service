package com.sysj.collector.core.router;


import com.sysj.collector.core.ratelimit.ProviderRateLimitManager;

import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;
import com.sysj.collector.domain.document.SupplierState;
import com.sysj.collector.domain.repository.SupplierStateRepository;

import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 动态供应商路由器。
 *
 * <h3>候选列表构建逻辑</h3>
 * <pre>
 * 1. 取用户等级偏好列表 providerOrder（来自 user_tier_config）
 * 2. 偏好列表中的 key → 按偏好顺序排在候选列表前部
 * 3. 全局供应商列表中未出现在偏好列表的供应商 → 按 priority 追加在后部
 * </pre>
 *
 * <h3>路由检查顺序（对候选列表逐一遍历）</h3>
 * <ol>
 *   <li>isHealthy = true（来自 DB，运维手动维护）</li>
 *   <li>激活阈值：非首位供应商需 pendingCount >= activationThreshold</li>
 *   <li>限流令牌：tryAcquire（等待 ≤ 500ms）</li>
 * </ol>
 *
 * <p>无熔断器：健康状态由 DB 字段控制，不做程序探测。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicProviderRouter {

    private final ProviderRateLimitManager rateLimitManager;
    private final SupplierStateRepository supplierStateRepository;

    /** platformCode:featureCode → 待处理任务计数 */
    private final ConcurrentHashMap<String, AtomicInteger> pendingCountMap = new ConcurrentHashMap<>();

    // ── 主路由方法 ─────────────────────────────────────────────────────────

    /**
     * 在候选供应商中选出第一个可用的供应商配置。
     *
     * @param platformCode      平台编码
     * @param featureCode       功能编码
     * @param allProviders      该功能下所有供应商（DB 返回，按 priority ASC）
     * @param featureConfig     用户等级在此功能下的偏好配置（可为 null）
     * @return 可用的 ProviderConfig；全部不可用时返回 empty
     */
    public Optional<ProviderConfig> route(
            String platformCode,
            String featureCode,
            List<ProviderConfig> allProviders,
            FeatureProviderConfig featureConfig) {

        List<ProviderConfig> candidates = buildCandidates(allProviders, featureConfig);
        int pending = getPendingCount(platformCode, featureCode);
        int threshold = featureConfig != null ? featureConfig.getActivationThreshold() : 0;

        for (int i = 0; i < candidates.size(); i++) {
            ProviderConfig p = candidates.get(i);
            String key = p.getProviderKey();
            String rlKey = ProviderRateLimitManager.buildKey(platformCode, featureCode, key);

            // 1. 健康状态（DB 字段，运维维护）
            if (!p.isHealthy()) {
                log.debug("供应商不健康，跳过: key={}", key);
                continue;
            }

            // 2. 激活阈值（首位供应商不受限）
            if (i > 0 && pending < threshold) {
                log.debug("任务量未达阈值，跳过: key={} pending={} threshold={}", key, pending, threshold);
                continue;
            }

            // 3. 限流令牌（速率来自 DB，最长等待 500ms）
            if (!rateLimitManager.tryAcquire(rlKey, p.getRatePerSecond(), 500)) {
                log.warn("限流拒绝，跳过: key={} rate={}/s", key, p.getRatePerSecond());
                continue;
            }

            log.info("路由成功: platform={} feature={} provider={} priority={} pending={}",
                    platformCode, featureCode, key, p.getPriority(), pending);
            return Optional.of(p);
        }

        log.error("所有供应商均不可用: platform={} feature={} pending={}", platformCode, featureCode, pending);
        return Optional.empty();
    }

    // ── 候选列表构建 ───────────────────────────────────────────────────────

    /**
     * 合并用户偏好与全局列表，构建有序候选列表。
     *
     * <ul>
     *   <li>偏好列表中存在且 DB 中有效的供应商，按偏好顺序排前面</li>
     *   <li>偏好列表未覆盖的供应商，按全局 priority 追加在后面</li>
     *   <li>featureConfig 为 null 时，直接返回全局列表</li>
     * </ul>
     */
    private List<ProviderConfig> buildCandidates(
            List<ProviderConfig> allProviders,
            FeatureProviderConfig featureConfig) {

        if (featureConfig == null || featureConfig.getProviderOrder() == null
                || featureConfig.getProviderOrder().isEmpty()) {
            return allProviders;
        }

        // key → ProviderConfig，用于快速查找
        Map<String, ProviderConfig> providerMap = allProviders.stream()
                .collect(Collectors.toMap(ProviderConfig::getProviderKey, p -> p, (a, b) -> a));

        List<ProviderConfig> result = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();

        // 按偏好顺序插入
        for (String prefKey : featureConfig.getProviderOrder()) {
            ProviderConfig p = providerMap.get(prefKey);
            if (p != null && added.add(prefKey)) {
                result.add(p);
            }
        }

        // 补充全局列表中未包含的供应商（按原始 priority 顺序）
        for (ProviderConfig p : allProviders) {
            if (added.add(p.getProviderKey())) {
                result.add(p);
            }
        }

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

    // ── 新增：供 Facade 调用的公共方法 ────────────────────────────────────

    /**
     * 构建有序候选供应商列表（合并用户偏好 + 全局优先级）。
     * 公开方法，供 Facade 直接获取完整列表进行遍历切换。
     */
    public List<ProviderConfig> buildOrderedCandidates(
            List<ProviderConfig> allProviders,
            FeatureProviderConfig featureConfig) {

        if (featureConfig == null || featureConfig.getProviderOrder() == null
                || featureConfig.getProviderOrder().isEmpty()) {
            return allProviders;
        }

        Map<String, ProviderConfig> providerMap = allProviders.stream()
                .collect(Collectors.toMap(ProviderConfig::getProviderKey, p -> p, (a, b) -> a));

        List<ProviderConfig> result = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();

        for (String prefKey : featureConfig.getProviderOrder()) {
            ProviderConfig p = providerMap.get(prefKey);
            if (p != null && added.add(prefKey)) {
                result.add(p);
            }
        }

        for (ProviderConfig p : allProviders) {
            if (added.add(p.getProviderKey())) {
                result.add(p);
            }
        }

        return result;
    }

    /**
     * 尝试获取供应商限流令牌。
     */
    public boolean tryAcquireProvider(String platformCode, String featureCode,
                                       String providerKey, double ratePerSecond) {
        String rlKey = ProviderRateLimitManager.buildKey(platformCode, featureCode, providerKey);
        return rateLimitManager.tryAcquire(rlKey, ratePerSecond, 500);
    }

    /**
     * 标记供应商连续失败（更新数据库状态）。
     */
    public void markProviderFailure(String platformCode, String featureCode,
                                     String providerKey) {
        try {
            String key = PlatformFeatureConfig.buildStateKey(platformCode, featureCode, providerKey);
            SupplierState state = supplierStateRepository.findBySupplierKey(key)
                    .orElseGet(() -> {
                        SupplierState s = new SupplierState();
                        s.setSupplierKey(key);
                        s.setPlatformCode(platformCode);
                        s.setFeatureCode(featureCode);
                        s.setConsecutiveFailures(0);
                        return s;
                    });
            state.setConsecutiveFailures(
                    (state.getConsecutiveFailures() == null ? 0 : state.getConsecutiveFailures()) + 1);
            state.setLastFailureTime(java.time.Instant.now());
            state.setHealthStatus("DOWN");
            supplierStateRepository.save(state);
        } catch (Exception e) {
            log.warn("更新供应商失败状态异常(非关键): provider={}", providerKey, e);
        }
    }
}
