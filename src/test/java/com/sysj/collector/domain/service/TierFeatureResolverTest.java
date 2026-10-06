package com.sysj.collector.domain.service;

import com.sysj.collector.domain.document.UserTierConfig;
import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TierFeatureResolver} 单元测试：等级功能偏好的继承解析
 * （最近声明者优先、沿父链上溯、缺失父级/成环安全降级）。
 */
class TierFeatureResolverTest {

    private static FeatureProviderConfig entry(String platform, String feature, List<String> order, int threshold) {
        FeatureProviderConfig fc = new FeatureProviderConfig();
        fc.setPlatformCode(platform);
        fc.setFeatureCode(feature);
        fc.setProviderOrder(order);
        fc.setActivationThreshold(threshold);
        return fc;
    }

    private static UserTierConfig tier(String code, String parent, FeatureProviderConfig... configs) {
        UserTierConfig t = new UserTierConfig();
        t.setTierCode(code);
        t.setParentTierCode(parent);
        t.setFeatureConfigs(List.of(configs));
        return t;
    }

    /** ENTERPRISE(无声明) → VIP(覆盖 weibo) → NORMAL(基础全集) */
    private final Map<String, Optional<UserTierConfig>> tiers = new HashMap<>(Map.of(
            "NORMAL", Optional.of(tier("NORMAL", null,
                    entry("weibo", "comment", List.of("local_crawler", "weibo_official"), 10),
                    entry("tieba", "comment", List.of("tieba_local"), 10))),
            "VIP", Optional.of(tier("VIP", "NORMAL",
                    entry("weibo", "comment", List.of("weibo_official", "local_crawler"), 3))),
            "ENTERPRISE", Optional.of(tier("ENTERPRISE", "VIP"))));

    private final Function<String, Optional<UserTierConfig>> loader = tiers::get;

    @Test
    void childWithoutDeclarationInheritsParentEntry() {
        // ENTERPRISE 未声明 tieba：沿链继承 NORMAL 的整条配置
        var resolved = TierFeatureResolver.resolve(loader, "ENTERPRISE", "tieba", "comment");
        assertTrue(resolved.isPresent());
        assertEquals(List.of("tieba_local"), resolved.get().getProviderOrder());
        assertEquals(10, resolved.get().getActivationThreshold(), "整条继承含阈值");
    }

    @Test
    void nearestDeclarationWinsOverAncestors() {
        // weibo 三级都有：VIP 覆盖 NORMAL；ENTERPRISE 继承 VIP 的
        var vipWeibo = TierFeatureResolver.resolve(loader, "VIP", "weibo", "comment");
        assertEquals(List.of("weibo_official", "local_crawler"), vipWeibo.get().getProviderOrder());
        assertEquals(3, vipWeibo.get().getActivationThreshold());

        var enterpriseWeibo = TierFeatureResolver.resolve(loader, "ENTERPRISE", "weibo", "comment");
        assertEquals(3, enterpriseWeibo.get().getActivationThreshold(), "ENTERPRISE 未声明 → 继承 VIP 的覆盖项");
    }

    @Test
    void wholeChainMissFallsBackToEmpty() {
        assertTrue(TierFeatureResolver.resolve(loader, "ENTERPRISE", "xiaomi_bbs", "comment").isEmpty(),
                "链上都没声明的功能 → 无偏好（回退全局 priority 排序）");
    }

    @Test
    void missingParentStopsGracefully() {
        tiers.put("ORPHAN", Optional.of(tier("ORPHAN", "GHOST",
                entry("weibo", "comment", List.of("local_crawler"), 10))));
        var resolved = TierFeatureResolver.resolve(loader, "ORPHAN", "weibo", "comment");
        // 本级声明直接命中（无需上溯）
        assertTrue(resolved.isPresent());
        // 未声明且父级缺失 → 安全降级
        assertTrue(TierFeatureResolver.resolve(loader, "ORPHAN", "tieba", "comment").isEmpty());
    }

    @Test
    void cycleTerminatesWithoutInfiniteLoop() {
        tiers.put("A", Optional.of(tier("A", "B")));
        tiers.put("B", Optional.of(tier("B", "A")));
        assertTrue(TierFeatureResolver.resolve(loader, "A", "weibo", "comment").isEmpty(),
                "成环按无偏好降级，不抛栈不死循环");
    }

    @Test
    void nullOrUnknownTierIsEmpty() {
        assertTrue(TierFeatureResolver.resolve(loader, null, "weibo", "comment").isEmpty());
        assertTrue(TierFeatureResolver.resolve(loader, "  ", "weibo", "comment").isEmpty());
        assertTrue(TierFeatureResolver.resolve(loader, "NO_SUCH_TIER", "weibo", "comment").isEmpty());
    }

    @Test
    void baseTierDetection() {
        assertTrue(TierFeatureResolver.isBaseTier(tiers.get("NORMAL").get()));
        assertFalse(TierFeatureResolver.isBaseTier(tiers.get("VIP").get()));
        assertTrue(TierFeatureResolver.isBaseTier(tier("X", " ")), "空白父级视同基础等级");
        assertTrue(TierFeatureResolver.isBaseTier(null));
    }
}
