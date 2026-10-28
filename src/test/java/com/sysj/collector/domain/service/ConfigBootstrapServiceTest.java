package com.sysj.collector.domain.service;

import com.sysj.collector.domain.dao.PlatformFeatureConfigDao;
import com.sysj.collector.domain.dao.SystemConfigDao;
import com.sysj.collector.domain.dao.UserTierConfigDao;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.UserTierConfig;
import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import com.sysj.collector.domain.service.ConfigBootstrapService.BaselineStatus;
import com.sysj.collector.domain.service.ConfigBootstrapService.BootstrapReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConfigBootstrapService} 单元测试。
 *
 * <p>覆盖首次发布场景：两个集合都不存在 / 只缺部分 / 已齐全 / 写入失败 / 自举关闭，
 * 断言补齐的数量、只补缺不覆盖、以及异常不阻断启动。
 */
class ConfigBootstrapServiceTest {

    private SystemConfigDao systemConfigDao;
    private UserTierConfigDao userTierConfigDao;
    private PlatformFeatureConfigDao platformFeatureConfigDao;
    private ConfigBootstrapService service;

    @BeforeEach
    void setUp() {
        systemConfigDao = mock(SystemConfigDao.class);
        userTierConfigDao = mock(UserTierConfigDao.class);
        platformFeatureConfigDao = mock(PlatformFeatureConfigDao.class);
        service = new ConfigBootstrapService(systemConfigDao, userTierConfigDao, platformFeatureConfigDao);
        ReflectionTestUtils.setField(service, "enabled", true);
        when(platformFeatureConfigDao.findAll()).thenReturn(List.of());
        when(userTierConfigDao.findAllOrderByPriority()).thenReturn(new ArrayList<>());
        when(systemConfigDao.insertIfAbsent(anyString(), anyString(), anyString())).thenReturn(true);
        when(userTierConfigDao.insertIfAbsent(any())).thenReturn(true);
    }

    private BaselineStatus status(BootstrapReport report, String collection) {
        return report.baselines().stream()
                .filter(b -> collection.equals(b.collection()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("空库首次发布：system_config 与 user_tier_config 集合都不存在时补齐全部基线")
    void emptyDatabaseSeedsAllBaselines() {
        when(systemConfigDao.collectionExists()).thenReturn(false);
        when(systemConfigDao.loadAll()).thenReturn(Map.of());
        when(userTierConfigDao.collectionExists()).thenReturn(false);

        BootstrapReport report = service.bootstrap();

        assertTrue(report.healthy(), "首次发布补齐后应判定为健康");
        BaselineStatus systemConfig = status(report, "system_config");
        assertFalse(systemConfig.collectionExists(), "校验时应如实反映集合不存在");
        assertEquals(ConfigBootstrapService.SYSTEM_CONFIG_SEEDS.size(), systemConfig.missingBefore());
        assertEquals(ConfigBootstrapService.SYSTEM_CONFIG_SEEDS.size(), systemConfig.seeded(),
                "所有基线键都应补种");
        assertEquals(systemConfig.expectedBaseline(), systemConfig.documentCount());

        BaselineStatus tierConfig = status(report, "user_tier_config");
        assertEquals(3, tierConfig.missingBefore(), "ENTERPRISE/VIP/NORMAL 三个基线等级");
        assertEquals(3, tierConfig.seeded());
        assertEquals(3, tierConfig.documentCount());

        verify(systemConfigDao, times(ConfigBootstrapService.SYSTEM_CONFIG_SEEDS.size()))
                .insertIfAbsent(anyString(), anyString(), anyString());
        verify(userTierConfigDao, times(3)).insertIfAbsent(any());
    }

    @Test
    @DisplayName("只补缺失键：已存在的键一个都不写，缺口数如实统计")
    void onlyMissingKeysAreSeeded() {
        when(systemConfigDao.collectionExists()).thenReturn(true);
        Map<String, String> present = new LinkedHashMap<>();
        present.put("collector.circuit.enabled", "false");
        present.put("collector.pipeline.enabled", "true");
        when(systemConfigDao.loadAll()).thenReturn(present);
        when(userTierConfigDao.collectionExists()).thenReturn(true);
        when(userTierConfigDao.findAllOrderByPriority()).thenReturn(List.of(tier("NORMAL", null, 100)));

        BootstrapReport report = service.bootstrap();

        BaselineStatus systemConfig = status(report, "system_config");
        int expectedMissing = ConfigBootstrapService.SYSTEM_CONFIG_SEEDS.size() - 2;
        assertEquals(expectedMissing, systemConfig.missingBefore());
        assertEquals(expectedMissing, systemConfig.seeded());
        assertTrue(systemConfig.healthy());
        // 人工改过的 collector.circuit.enabled=false 不得被基线 true 覆盖：不调用即可
        verify(systemConfigDao, never()).insertIfAbsent(
                org.mockito.ArgumentMatchers.eq("collector.circuit.enabled"), anyString(), anyString());

        BaselineStatus tierConfig = status(report, "user_tier_config");
        assertEquals(2, tierConfig.missingBefore(), "只缺 ENTERPRISE/VIP");
        assertEquals(2, tierConfig.seeded());
        assertEquals(3, tierConfig.documentCount(), "已存在的 1 个等级 + 本次补种 2 个 = 基线 3 个");
    }

    @Test
    @DisplayName("已有等级不被覆盖：三个等级齐全时一次插入都不发生")
    void existingTiersAreNotTouched() {
        when(systemConfigDao.collectionExists()).thenReturn(true);
        when(systemConfigDao.loadAll()).thenReturn(ConfigBootstrapService.systemConfigDefaults());
        when(userTierConfigDao.collectionExists()).thenReturn(true);
        when(userTierConfigDao.findAllOrderByPriority()).thenReturn(List.of(
                tier("ENTERPRISE", "VIP", 1), tier("VIP", "NORMAL", 10), tier("NORMAL", null, 100)));

        BootstrapReport report = service.bootstrap();

        assertTrue(report.healthy());
        assertEquals(0, status(report, "system_config").seeded());
        assertEquals(0, status(report, "user_tier_config").seeded());
        verify(systemConfigDao, never()).insertIfAbsent(anyString(), anyString(), anyString());
        verify(userTierConfigDao, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("等级基线带功能项快照：providerOrder 取该功能全部供应商，阈值取等级基线")
    void tierSeedCarriesFeatureSnapshot() {
        when(systemConfigDao.collectionExists()).thenReturn(true);
        when(systemConfigDao.loadAll()).thenReturn(ConfigBootstrapService.systemConfigDefaults());
        when(platformFeatureConfigDao.findAll()).thenReturn(List.of(
                feature("weibo", "comment", "weibo_official", "weibo_local"),
                feature("bilibili", "comment", "bilibili_golaxy")));
        when(userTierConfigDao.collectionExists()).thenReturn(true);

        service.bootstrap();

        ArgumentCaptor<UserTierConfig> captor = ArgumentCaptor.forClass(UserTierConfig.class);
        verify(userTierConfigDao, times(3)).insertIfAbsent(captor.capture());
        UserTierConfig normal = captor.getAllValues().stream()
                .filter(t -> "NORMAL".equals(t.getTierCode()))
                .findFirst().orElseThrow();
        assertEquals(100, normal.getPriority());
        assertEquals(2, normal.getFeatureConfigs().size(), "两个功能配置各生成一个功能项");
        FeatureProviderConfig weibo = normal.getFeatureConfigs().get(0);
        assertEquals("weibo", weibo.getPlatformCode());
        assertEquals(List.of("weibo_official", "weibo_local"), weibo.getProviderOrder());
        assertEquals(10, weibo.getActivationThreshold(), "基础等级阈值取基线 10");

        UserTierConfig enterprise = captor.getAllValues().stream()
                .filter(t -> "ENTERPRISE".equals(t.getTierCode()))
                .findFirst().orElseThrow();
        assertEquals(0, enterprise.getFeatureConfigs().get(0).getActivationThreshold());
        assertEquals("VIP", enterprise.getParentTierCode());
    }

    @Test
    @DisplayName("写入失败不算健康：缺口未补齐时 healthy=false 且不抛异常")
    void partialSeedIsUnhealthy() {
        when(systemConfigDao.collectionExists()).thenReturn(true);
        when(systemConfigDao.loadAll()).thenReturn(Map.of());
        when(systemConfigDao.insertIfAbsent(anyString(), anyString(), anyString())).thenReturn(false);
        when(userTierConfigDao.collectionExists()).thenReturn(true);

        BootstrapReport report = service.bootstrap();

        assertFalse(report.healthy());
        BaselineStatus systemConfig = status(report, "system_config");
        assertEquals(ConfigBootstrapService.SYSTEM_CONFIG_SEEDS.size(), systemConfig.missingBefore());
        assertEquals(0, systemConfig.seeded());
        assertTrue(systemConfig.message().contains("仅补种成功"));
    }

    @Test
    @DisplayName("MongoDB 异常不阻断启动：校验失败只记状态，bootstrap() 正常返回")
    void mongoFailureDoesNotThrow() {
        when(systemConfigDao.collectionExists()).thenThrow(new IllegalStateException("connection refused"));
        when(userTierConfigDao.collectionExists()).thenThrow(new IllegalStateException("connection refused"));

        BootstrapReport report = service.bootstrap();

        assertFalse(report.healthy());
        assertTrue(status(report, "system_config").message().contains("校验失败"));
        assertEquals(-1, status(report, "system_config").documentCount());
        assertTrue(status(report, "user_tier_config").message().contains("校验失败"));
    }

    @Test
    @DisplayName("自举开关关闭：不写 DB，报告标记 skipped")
    void disabledBootstrapSkipsWrites() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.run(null);

        BootstrapReport report = service.lastReport();
        assertNotNull(report);
        assertTrue(report.skipped());
        assertFalse(report.healthy(), "跳过不算健康，需运维自行确认");
        verify(systemConfigDao, never()).insertIfAbsent(anyString(), anyString(), anyString());
        verify(userTierConfigDao, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("父链自检：父等级不存在时只告警，不影响补种结果")
    void brokenParentChainOnlyWarns() {
        when(systemConfigDao.collectionExists()).thenReturn(true);
        when(systemConfigDao.loadAll()).thenReturn(ConfigBootstrapService.systemConfigDefaults());
        when(userTierConfigDao.collectionExists()).thenReturn(true);
        // 已有 VIP 的父级 NORMAL 被人删掉，同时 ENTERPRISE 也缺失 → 补种后父链自动恢复
        when(userTierConfigDao.findAllOrderByPriority()).thenReturn(List.of(tier("VIP", "NORMAL", 10)));

        BootstrapReport report = service.bootstrap();

        assertEquals(2, status(report, "user_tier_config").seeded(), "缺 ENTERPRISE 与 NORMAL");
        assertTrue(report.healthy());
    }

    // ── 测试夹具 ───────────────────────────────────────────────────────────

    private static UserTierConfig tier(String code, String parent, int priority) {
        UserTierConfig tier = new UserTierConfig();
        tier.setTierCode(code);
        tier.setParentTierCode(parent);
        tier.setPriority(priority);
        return tier;
    }

    private static PlatformFeatureConfig feature(String platform, String featureCode, String... providers) {
        PlatformFeatureConfig config = new PlatformFeatureConfig();
        config.setPlatformCode(platform);
        config.setFeatureCode(featureCode);
        List<PlatformFeatureConfig.ProviderConfig> list = new ArrayList<>();
        for (String key : providers) {
            PlatformFeatureConfig.ProviderConfig p = new PlatformFeatureConfig.ProviderConfig();
            p.setProviderKey(key);
            list.add(p);
        }
        config.setProviders(list);
        return config;
    }
}
