package com.sysj.collector.domain.service;

import com.sysj.collector.domain.document.UserTierConfig;
import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 等级功能偏好的**继承解析器**（纯计算，便于单测）。
 *
 * <p>规则：从请求的等级出发，沿 {@code parentTierCode} 向上逐级查找该平台/功能的
 * 功能项——<b>最近声明者优先</b>（本等级声明的整条覆盖父等级同名项）；
 * 整条链都未声明时返回 empty（调用方回退全局 priority 排序）。
 *
 * <p>防呆：父链缺失（父等级不存在）即停止；环（A→B→A）用 visited 集合截断；
 * 最大跳数限制兜底。所有异常路径都安全降级为"无偏好"，绝不阻断采集。
 */
public final class TierFeatureResolver {

    /** 父链最大深度（防御配置错误导致的极长/环链）。 */
    static final int MAX_HOPS = 10;

    /**
     * 解析等级在某功能下的偏好（含继承）。
     *
     * @param tierLoader  等级编码 → 等级配置（调用方提供缓存后的加载函数，可返回 empty）
     * @param tierCode    请求的等级编码（null = 无等级，直接 empty）
     * @param platformCode 平台编码
     * @param featureCode  功能编码
     */
    public static Optional<FeatureProviderConfig> resolve(
            Function<String, Optional<UserTierConfig>> tierLoader,
            String tierCode, String platformCode, String featureCode) {
        if (tierCode == null || tierCode.isBlank()) {
            return Optional.empty();
        }
        String current = tierCode.trim();
        Set<String> visited = new HashSet<>();
        for (int hop = 0; hop < MAX_HOPS && current != null && !current.isBlank(); hop++) {
            if (!visited.add(current)) {
                return Optional.empty();   // 配置成环：按无偏好降级
            }
            Optional<UserTierConfig> loaded = tierLoader.apply(current);
            if (loaded == null || loaded.isEmpty()) {
                return Optional.empty();   // 父等级不存在：停止上溯
            }
            UserTierConfig tierConfig = loaded.get();
            Optional<FeatureProviderConfig> hit = findEntry(tierConfig, platformCode, featureCode);
            if (hit.isPresent()) {
                return hit;
            }
            current = tierConfig.getParentTierCode();
        }
        return Optional.empty();
    }

    /** 本等级自己声明的功能项。 */
    private static Optional<FeatureProviderConfig> findEntry(
            UserTierConfig tier, String platformCode, String featureCode) {
        if (tier.getFeatureConfigs() == null) {
            return Optional.empty();
        }
        return tier.getFeatureConfigs().stream()
                .filter(fc -> platformCode.equals(fc.getPlatformCode())
                        && featureCode.equals(fc.getFeatureCode()))
                .findFirst();
    }

    /** 是否基础等级（无父等级）——注册表同步只向基础等级补缺失功能项。 */
    public static boolean isBaseTier(UserTierConfig tier) {
        return tier == null || tier.getParentTierCode() == null || tier.getParentTierCode().isBlank();
    }

    private TierFeatureResolver() {
    }
}
