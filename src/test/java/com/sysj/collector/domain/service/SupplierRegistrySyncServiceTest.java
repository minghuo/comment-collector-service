package com.sysj.collector.domain.service;

import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SupplierRegistrySyncService#mergeMissingFeatures} 单元测试：
 * 等级功能项的缺失补齐（新增平台自动进入各等级的供应商偏好，已有项不覆盖）。
 */
class SupplierRegistrySyncServiceTest {

    private static FeatureProviderConfig entry(String platform, String feature, int threshold) {
        FeatureProviderConfig fc = new FeatureProviderConfig();
        fc.setPlatformCode(platform);
        fc.setFeatureCode(feature);
        fc.setProviderOrder(List.of(platform + "_p1"));
        fc.setActivationThreshold(threshold);
        return fc;
    }

    private static PlatformFeatureConfig config(String platform, String feature, String... providerKeys) {
        PlatformFeatureConfig config = new PlatformFeatureConfig();
        config.setPlatformCode(platform);
        config.setFeatureCode(feature);
        List<PlatformFeatureConfig.ProviderConfig> providers = new java.util.ArrayList<>();
        for (String key : providerKeys) {
            PlatformFeatureConfig.ProviderConfig p = new PlatformFeatureConfig.ProviderConfig();
            p.setProviderKey(key);
            providers.add(p);
        }
        config.setProviders(providers);
        return config;
    }

    @Test
    void missingPlatformsAreAppendedWithProviderOrder() {
        List<PlatformFeatureConfig> configs = List.of(
                config("weibo", "comment", "weibo_official", "local_crawler"),
                config("tieba", "comment", "tieba_local"),
                config("vivo_bbs", "comment", "vivo_bbs_local"));
        List<FeatureProviderConfig> existing = List.of(entry("weibo", "comment", 10));

        List<FeatureProviderConfig> missing = SupplierRegistrySyncService.mergeMissingFeatures(
                existing, configs, 10);

        assertEquals(2, missing.size(), "补齐 tieba 与 vivo_bbs 两个缺失功能项");
        assertEquals("tieba", missing.get(0).getPlatformCode());
        assertEquals(List.of("tieba_local"), missing.get(0).getProviderOrder(), "providerOrder = 全部供应商 key");
        assertEquals("vivo_bbs", missing.get(1).getPlatformCode());
        assertEquals(10, missing.get(0).getActivationThreshold(), "沿用该等级现有阈值");
    }

    @Test
    void existingEntriesAreNeverDuplicated() {
        List<PlatformFeatureConfig> configs = List.of(config("tieba", "comment", "tieba_local"));
        List<FeatureProviderConfig> existing = List.of(entry("tieba", "comment", 3));

        assertTrue(SupplierRegistrySyncService.mergeMissingFeatures(existing, configs, 10).isEmpty(),
                "已有功能项不重复追加");
    }

    @Test
    void emptyTierGetsAllConfiguredFeatures() {
        List<PlatformFeatureConfig> configs = List.of(
                config("weibo", "comment", "weibo_official"),
                config("honor_bbs", "comment", "honor_bbs_local"));

        List<FeatureProviderConfig> missing = SupplierRegistrySyncService.mergeMissingFeatures(
                List.of(), configs, 0);

        assertEquals(2, missing.size());
        assertEquals(0, missing.get(0).getActivationThreshold(), "等级无现有项时阈值为 0（始终启用首供应商）");
    }

    @Test
    void disabledOrProviderlessConfigsAreSkipped() {
        PlatformFeatureConfig empty = config("douyin", "comment");
        List<FeatureProviderConfig> missing = SupplierRegistrySyncService.mergeMissingFeatures(
                List.of(), List.of(empty), 0);
        assertTrue(missing.isEmpty(), "无供应商的功能配置不生成功能项");
    }
}
