package com.sysj.collector.domain.service;

import com.sysj.collector.domain.dao.PlatformFeatureConfigDao;
import com.sysj.collector.domain.dao.SupplierStateDao;
import com.sysj.collector.domain.dao.UserTierConfigDao;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.SupplierState;
import com.sysj.collector.domain.document.UserTierConfig;
import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商注册表写入测试（Mockito）：模拟存量库场景——
 * {@code platform_feature_config} 已配好 15 个供应商（含 VIVO/OPPO/贴吧/荣耀/华为/小米 6 个新平台），
 * 但 {@code supplier_state} 只有旧的 9 个、{@code user_tier_config} 缺新平台的功能项——
 * 执行 {@link SupplierRegistrySyncService#sync()} 后：
 * <ol>
 *   <li>6 个新平台的供应商全部写入 supplier_state（旧条目只做身份对齐、不重建）；</li>
 *   <li>新平台功能项写入**基础等级** NORMAL 的用户权限（高等级缺项靠继承，不重复补）；</li>
 *   <li>配置中已删除的供应商条目被识别为失效并报告。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SupplierRegistrySyncWriteTest {

    /** 6 个新平台的 (platform, providerKey) —— 用户点名的 VIVO/OPPO 等都在这里 */
    private static final String[][] NEW_PLATFORMS = {
            {"tieba", "tieba_local"},
            {"honor_bbs", "honor_bbs_local"},
            {"huawei_bbs", "huawei_bbs_local"},
            {"oppo_bbs", "oppo_bbs_local"},
            {"vivo_bbs", "vivo_bbs_local"},
            {"xiaomi_bbs", "xiaomi_bbs_local"},
    };

    @Mock
    private PlatformFeatureConfigDao featureConfigDao;
    @Mock
    private SupplierStateDao supplierStateDao;
    @Mock
    private UserTierConfigDao userTierConfigDao;

    private SupplierRegistrySyncService service;

    /** supplier_state 里已存在的旧条目 key（init 脚本播种过的 9 个） */
    private final Set<String> existingStateKeys = new HashSet<>(List.of(
            "weibo:comment:weibo_official",
            "weibo:comment:local_crawler",
            "weibo:repost:weibo_repost_local",
            "wechat:comment:wechat_sy",
            "wechat_video:comment:wechat_video_sy",
            "bilibili:comment:bilibili_golaxy",
            "douyin:comment:douyin_golaxy",
            "xhs:comment:xhs_golaxy",
            "toutiao:comment:toutiao_local"));

    @BeforeEach
    void setUp() {
        service = new SupplierRegistrySyncService(featureConfigDao, supplierStateDao, userTierConfigDao,
                org.mockito.Mockito.mock(com.sysj.collector.domain.service.ProviderConfigService.class),
                List.of());
    }

    @Test
    void newPlatformsWrittenIntoSupplierStateAndUserTiers() {
        // ── Given：配置 15 个供应商（9 旧 + 6 新）──
        when(featureConfigDao.findAll()).thenReturn(allFifteenConfigs());

        // supplier_state 只有旧的 9 条：upsertIdentity 对新 key 返回 true（新建），旧 key 返回 false
        when(supplierStateDao.upsertIdentity(anyString(), any(), any(), any()))
                .thenAnswer(inv -> existingStateKeys.add(inv.getArgument(0, String.class)));
        when(supplierStateDao.findAll()).thenAnswer(inv ->
                existingStateKeys.stream().map(this::stateWithKey).collect(Collectors.toList()));

        // 用户等级：NORMAL（基础等级）缺 6 个新平台；VIP 也缺（但应靠继承不补）；ENTERPRISE 全量
        UserTierConfig normal = tier("NORMAL", null, oldFeatureEntries());
        UserTierConfig vip = tier("VIP", "NORMAL", oldFeatureEntries());
        UserTierConfig enterprise = tier("ENTERPRISE", "VIP", allFeatureEntries());
        when(userTierConfigDao.findAllOrderByPriority()).thenReturn(List.of(normal, vip, enterprise));

        // ── When ──
        SupplierRegistrySyncService.SyncReport report = service.sync();

        // ── Then：报告数字 ──
        assertEquals(15, report.configuredSuppliers(), "配置中共 15 个供应商");
        assertEquals(6, report.supplierStatesAdded(), "新建 6 个新平台的 supplier_state");
        assertEquals(6, report.tierFeatureEntriesAdded(), "基础等级 NORMAL 追加 6 个功能项");
        assertTrue(report.staleKeys().isEmpty(), "无失效条目");

        // ── Then：supplier_state 逐个写入 6 个新平台（含用户点名的 VIVO/OPPO）──
        verify(supplierStateDao, times(15)).upsertIdentity(anyString(), any(), any(), any());
        for (String[] p : NEW_PLATFORMS) {
            verify(supplierStateDao).upsertIdentity(
                    eq(p[0] + ":comment:" + p[1]), eq(p[0]), eq("comment"), eq(p[1]));
        }
        // 已存在的旧条目也被身份对齐（$set 身份字段），但不会重复新建
        verify(supplierStateDao).upsertIdentity(
                eq("weibo:comment:weibo_official"), eq("weibo"), eq("comment"), eq("weibo_official"));
        assertTrue(existingStateKeys.contains("vivo_bbs:comment:vivo_bbs_local"));
        assertTrue(existingStateKeys.contains("oppo_bbs:comment:oppo_bbs_local"));

        // ── Then：用户权限（user_tier_config）——只保存基础等级 NORMAL，且包含全部功能项 ──
        verify(userTierConfigDao, times(1)).save(any(UserTierConfig.class));
        verify(userTierConfigDao).save(org.mockito.ArgumentMatchers.argThat(saved -> {
            if (!"NORMAL".equals(saved.getTierCode())) {
                return false;
            }
            Set<String> features = saved.getFeatureConfigs() == null ? Set.of()
                    : saved.getFeatureConfigs().stream()
                            .map(fc -> fc.getPlatformCode() + ":" + fc.getFeatureCode())
                            .collect(Collectors.toSet());
            // 15 个供应商对应 14 个唯一功能项（weibo:comment 有两个供应商）
            return features.size() == 14
                    && features.containsAll(List.of("vivo_bbs:comment", "oppo_bbs:comment",
                            "tieba:comment", "honor_bbs:comment", "huawei_bbs:comment", "xiaomi_bbs:comment"));
        }));
        // VIP 缺项不被补（继承基础等级），ENTERPRISE 全量无需追加 → 二者均不落库
        assertFalse(vip.getFeatureConfigs().stream()
                .anyMatch(fc -> "vivo_bbs".equals(fc.getPlatformCode())), "VIP 不重复补（继承 NORMAL）");
    }

    @Test
    void staleEntriesDetectedWhenConfigRemoved() {
        when(featureConfigDao.findAll()).thenReturn(allFifteenConfigs());
        // supplier_state 多了一个配置里已不存在的条目（如已下线的渠道）
        Set<String> keys = new HashSet<>(existingStateKeys);
        keys.add("weibo:comment:company_a_retired");
        when(supplierStateDao.upsertIdentity(anyString(), any(), any(), any()))
                .thenAnswer(inv -> keys.add(inv.getArgument(0, String.class)));
        when(supplierStateDao.findAll()).thenAnswer(inv ->
                keys.stream().map(this::stateWithKey).collect(Collectors.toList()));
        UserTierConfig normal = tier("NORMAL", null, allFeatureEntries());
        when(userTierConfigDao.findAllOrderByPriority()).thenReturn(List.of(normal));

        SupplierRegistrySyncService.SyncReport report = service.sync();

        assertEquals(1, report.staleKeys().size());
        assertEquals("weibo:comment:company_a_retired", report.staleKeys().get(0));
        assertEquals(0, report.tierFeatureEntriesAdded(), "NORMAL 已全量，无需追加");
    }

    // ── 测试数据构造 ───────────────────────────────────────────────────────

    /** 15 个平台的完整配置（与 init 脚本 FEATURE_CONFIGS 同构，每个平台一个 provider）。 */
    private List<PlatformFeatureConfig> allFifteenConfigs() {
        List<PlatformFeatureConfig> configs = new ArrayList<>();
        configs.add(config("weibo", "comment", "weibo_official"));
        configs.add(config("weibo", "comment", "local_crawler"));
        configs.add(config("weibo", "repost", "weibo_repost_local"));
        configs.add(config("wechat", "comment", "wechat_sy"));
        configs.add(config("wechat_video", "comment", "wechat_video_sy"));
        configs.add(config("bilibili", "comment", "bilibili_golaxy"));
        configs.add(config("douyin", "comment", "douyin_golaxy"));
        configs.add(config("xhs", "comment", "xhs_golaxy"));
        configs.add(config("toutiao", "comment", "toutiao_local"));
        for (String[] p : NEW_PLATFORMS) {
            configs.add(config(p[0], "comment", p[1]));
        }
        return configs;
    }

    private PlatformFeatureConfig config(String platform, String feature, String providerKey) {
        PlatformFeatureConfig config = new PlatformFeatureConfig();
        config.setPlatformCode(platform);
        config.setFeatureCode(feature);
        PlatformFeatureConfig.ProviderConfig p = new PlatformFeatureConfig.ProviderConfig();
        p.setProviderKey(providerKey);
        config.setProviders(new ArrayList<>(List.of(p)));
        return config;
    }

    /** 9 个旧平台的功能项（缺 6 个新平台）。 */
    private List<FeatureProviderConfig> oldFeatureEntries() {
        return allFeatureEntries().stream()
                .filter(fc -> !isNewPlatform(fc.getPlatformCode()))
                .collect(Collectors.toList());
    }

    /** 15 个平台的功能项。 */
    private List<FeatureProviderConfig> allFeatureEntries() {
        List<FeatureProviderConfig> entries = new ArrayList<>();
        for (PlatformFeatureConfig config : allFifteenConfigs()) {
            FeatureProviderConfig fc = new FeatureProviderConfig();
            fc.setPlatformCode(config.getPlatformCode());
            fc.setFeatureCode(config.getFeatureCode());
            fc.setProviderOrder(config.getProviders().stream()
                    .map(PlatformFeatureConfig.ProviderConfig::getProviderKey)
                    .collect(Collectors.toList()));
            fc.setActivationThreshold(10);
            entries.add(fc);
        }
        return entries;
    }

    private boolean isNewPlatform(String platformCode) {
        for (String[] p : NEW_PLATFORMS) {
            if (p[0].equals(platformCode)) {
                return true;
            }
        }
        return false;
    }

    private UserTierConfig tier(String code, String parent, List<FeatureProviderConfig> configs) {
        UserTierConfig t = new UserTierConfig();
        t.setTierCode(code);
        t.setParentTierCode(parent);
        t.setFeatureConfigs(new ArrayList<>(configs));
        return t;
    }

    private SupplierState stateWithKey(String key) {
        SupplierState state = new SupplierState();
        state.setSupplierKey(key);
        return state;
    }
}
