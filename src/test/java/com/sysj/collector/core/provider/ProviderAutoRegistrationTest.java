package com.sysj.collector.core.provider;

import com.sysj.collector.domain.dao.PlatformFeatureConfigDao;
import com.sysj.collector.domain.dao.SupplierStateDao;
import com.sysj.collector.domain.dao.UserTierConfigDao;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.SupplierState;
import com.sysj.collector.domain.document.UserTierConfig;
import com.sysj.collector.domain.service.ProviderConfigService;
import com.sysj.collector.domain.service.SupplierRegistrySyncService;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommonEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商**代码自动注册**测试（"代码即配置"）：验证
 * {@link SupplierRegistrySyncService} 扫描 {@code @ProviderMeta} 注解后，
 * 把新供应商写入 {@code platform_feature_config}（功能文档缺失则创建、缺失则追加、
 * 已存在则只对齐能力声明），且未标注注解的实现被跳过并告警。
 */
@ExtendWith(MockitoExtension.class)
class ProviderAutoRegistrationTest {

    @Mock
    private PlatformFeatureConfigDao featureConfigDao;
    @Mock
    private SupplierStateDao supplierStateDao;
    @Mock
    private UserTierConfigDao userTierConfigDao;
    @Mock
    private ProviderConfigService configService;

    // ── 测试桩：新供应商（vivo，注解齐全）与旧式供应商（无注解，应跳过） ──

    @ProviderMeta(platform = "vivo_bbs", feature = "comment", name = "vivo社区-本地爬虫",
            featureName = "vivo社区评论采集")
    @ProviderCapability({ Capability.COMMENT, Capability.CURSOR_PAGING, Capability.SYNC_SUPPORTED })
    static class VivoStubProvider implements CommentProvider {
        @Override
        public String providerKey() {
            return "vivo_bbs_local";
        }

        @Override
        public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
            return null;
        }
    }

    static class LegacyStubProvider implements CommentProvider {
        @Override
        public String providerKey() {
            return "legacy_no_meta";
        }

        @Override
        public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
            return null;
        }
    }

    @Test
    void annotatedProviderIsRegisteredIntoFeatureConfig() {
        // 场景：DB 无 vivo_bbs 功能文档 → 应创建（CREATED_FEATURE）
        when(featureConfigDao.upsertProvider(
                eq("vivo_bbs"), eq("comment"), eq("vivo社区评论采集"), any())).thenReturn("CREATED_FEATURE");
        when(featureConfigDao.findAll()).thenReturn(List.of());
        when(supplierStateDao.findAll()).thenReturn(List.of());
        when(userTierConfigDao.findAllOrderByPriority()).thenReturn(List.of());

        SupplierRegistrySyncService service = new SupplierRegistrySyncService(
                featureConfigDao, supplierStateDao, userTierConfigDao, configService,
                List.of(new VivoStubProvider(), new LegacyStubProvider()));
        SupplierRegistrySyncService.SyncReport report = service.sync();

        // 上报注解的供应商被注册（平台/功能/名称逐参数对齐）
        verify(featureConfigDao).upsertProvider(
                eq("vivo_bbs"), eq("comment"), eq("vivo社区评论采集"),
                org.mockito.ArgumentMatchers.argThat(p ->
                        "vivo_bbs_local".equals(p.getProviderKey())
                                && p.getRatePerSecond() == 0.5
                                && p.getCapabilities() != null
                                && p.getCapabilities().contains("COMMENT")
                                && p.getCapabilities().contains("CURSOR_PAGING")));
        // 未标注注解的供应商不注册
        verify(featureConfigDao, never()).upsertProvider(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.argThat(p -> "legacy_no_meta".equals(p.getProviderKey())));
        // 注册后缓存淘汰，读到的配置立即包含新供应商
        verify(configService).evictFeatureCache("vivo_bbs", "comment");
        assertEquals(1, report.providersRegistered());
        assertEquals(0, report.providersAligned());
    }

    @Test
    void existingProviderOnlyAlignsCapabilities() {
        // 场景：DB 已有该供应商 → 只对齐能力声明，速率等运维参数不动
        when(featureConfigDao.upsertProvider(anyString(), anyString(), anyString(), any()))
                .thenReturn("ALIGNED");
        when(featureConfigDao.findAll()).thenReturn(List.of());
        when(supplierStateDao.findAll()).thenReturn(List.of());
        when(userTierConfigDao.findAllOrderByPriority()).thenReturn(List.of());

        SupplierRegistrySyncService service = new SupplierRegistrySyncService(
                featureConfigDao, supplierStateDao, userTierConfigDao, configService,
                List.of(new VivoStubProvider()));
        SupplierRegistrySyncService.SyncReport report = service.sync();

        assertEquals(0, report.providersRegistered());
        assertEquals(1, report.providersAligned());
    }

    @Test
    void registeredProviderFlowsIntoSupplierStateAndTiers() {
        // 全链路：代码注册 → findAll 读到新配置 → supplier_state 补条目 → 基础等级追加功能项
        when(featureConfigDao.upsertProvider(anyString(), anyString(), anyString(), any()))
                .thenReturn("ADDED");
        PlatformFeatureConfig vivoConfig = new PlatformFeatureConfig();
        vivoConfig.setPlatformCode("vivo_bbs");
        vivoConfig.setFeatureCode("comment");
        PlatformFeatureConfig.ProviderConfig p = new PlatformFeatureConfig.ProviderConfig();
        p.setProviderKey("vivo_bbs_local");
        vivoConfig.setProviders(List.of(p));
        // 注册后 findAll 返回新配置（模拟同一轮 sync 内的先后关系）
        when(featureConfigDao.findAll()).thenReturn(List.of(vivoConfig));
        when(supplierStateDao.findAll()).thenReturn(List.of());
        when(supplierStateDao.upsertIdentity(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        UserTierConfig baseTier = new UserTierConfig();
        baseTier.setTierCode("NORMAL");
        baseTier.setFeatureConfigs(List.of());
        when(userTierConfigDao.findAllOrderByPriority()).thenReturn(List.of(baseTier));

        SupplierRegistrySyncService service = new SupplierRegistrySyncService(
                featureConfigDao, supplierStateDao, userTierConfigDao, configService,
                List.of(new VivoStubProvider()));
        SupplierRegistrySyncService.SyncReport report = service.sync();

        verify(supplierStateDao).upsertIdentity(
                eq("vivo_bbs:comment:vivo_bbs_local"), eq("vivo_bbs"), eq("comment"), eq("vivo_bbs_local"));
        verify(userTierConfigDao).save(org.mockito.ArgumentMatchers.argThat(t ->
                "NORMAL".equals(t.getTierCode()) && t.getFeatureConfigs().stream()
                        .anyMatch(fc -> "vivo_bbs".equals(fc.getPlatformCode()))));
        assertEquals(1, report.providersRegistered());
        assertEquals(1, report.supplierStatesAdded());
        assertEquals(1, report.tierFeatureEntriesAdded());
        assertTrue(report.staleKeys().isEmpty());
    }

    @Test
    void unregisteredSupplierStateKeyReportedAsStale() {
        // supplier_state 里有、配置里没有的条目 → stale 告警
        when(featureConfigDao.findAll()).thenReturn(List.of());
        SupplierState retired = new SupplierState();
        retired.setSupplierKey("weibo:comment:company_a_retired");
        when(supplierStateDao.findAll()).thenReturn(List.of(retired));
        when(userTierConfigDao.findAllOrderByPriority()).thenReturn(List.of());

        SupplierRegistrySyncService service = new SupplierRegistrySyncService(
                featureConfigDao, supplierStateDao, userTierConfigDao, configService, List.of());
        SupplierRegistrySyncService.SyncReport report = service.sync();

        assertEquals(1, report.staleKeys().size());
        assertEquals("weibo:comment:company_a_retired", report.staleKeys().get(0));
    }
}
