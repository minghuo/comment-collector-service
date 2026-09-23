package com.sysj.collector.domain.service;


import com.sysj.collector.domain.document.PlatformFeatureConfig;

import com.sysj.collector.domain.document.UserTierConfig;

import com.sysj.collector.domain.dao.PlatformFeatureConfigDao;

import com.sysj.collector.domain.dao.UserTierConfigDao;

import com.sysj.collector.exception.CollectorException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 配置领域服务。
 *
 * <p>所有查询走 Caffeine 本地缓存（TTL 60s），
 * 运维修改 MongoDB 后可调用 evict 接口立即刷新，
 * 或等待 TTL 自然过期（最长 60s 延迟）。
 *
 * <p>数据访问一律委托给 Dao（{@link PlatformFeatureConfigDao} / {@link UserTierConfigDao}），
 * 本类不直接持有 {@code MongoTemplate}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderConfigService {

    private final PlatformFeatureConfigDao featureConfigDao;
    private final UserTierConfigDao userTierConfigDao;

    // ── 查询（带缓存） ─────────────────────────────────────────────────────

    /**
     * 查询平台功能配置（含嵌套供应商列表）。
     * 缓存 key = "platformCode:featureCode"。
     */
    @Cacheable(value = "featureConfig", key = "#platformCode + ':' + #featureCode")
    public PlatformFeatureConfig loadFeatureConfig(String platformCode, String featureCode) {
        return featureConfigDao
                .findByPlatformCodeAndFeatureCode(platformCode, featureCode)
                .filter(PlatformFeatureConfig::isStatus)
                .orElseThrow(() -> new CollectorException(
                        "功能配置不存在或已禁用: platform=" + platformCode + " feature=" + featureCode));
    }

    /**
     * 查询用户等级配置。
     * 若 tierCode 不存在，返回 empty（调用方降级为全局优先级）。
     */
    @Cacheable(value = "userTierConfig", key = "#tierCode")
    public Optional<UserTierConfig> loadUserTierConfig(String tierCode) {
        return userTierConfigDao.findByTierCode(tierCode);
    }

    // ── 缓存管理 ───────────────────────────────────────────────────────────

    /**
     * 淘汰指定功能的缓存（运维修改 DB 后调用）。
     */
    @CacheEvict(value = "featureConfig", key = "#platformCode + ':' + #featureCode")
    public void evictFeatureCache(String platformCode, String featureCode) {
        log.info("缓存已淘汰: platform={} feature={}", platformCode, featureCode);
    }

    /**
     * 淘汰指定等级的缓存。
     */
    @CacheEvict(value = "userTierConfig", key = "#tierCode")
    public void evictUserTierCache(String tierCode) {
        log.info("用户等级缓存已淘汰: tierCode={}", tierCode);
    }

    /**
     * 全量淘汰所有缓存（紧急情况使用）。
     */
    @CacheEvict(value = {"featureConfig", "userTierConfig"}, allEntries = true)
    public void evictAll() {
        log.info("全量缓存淘汰完成");
    }

    // ── 供应商数组元素的运维更新（委托 Dao） ───────────────────────────────

    /**
     * 更新指定功能下某供应商的健康状态。
     *
     * <p>更新成功后自动淘汰该功能的配置缓存。
     * <b>注意</b>：缓存淘汰依赖 Spring 代理，因此本方法必须由<b>外部</b>调用（同类内部调用不会触发 {@code @CacheEvict}）。
     *
     * @return 命中的文档数；0 表示 platform / feature / providerKey 组合不存在
     */
    @CacheEvict(value = "featureConfig", key = "#platformCode + ':' + #featureCode")
    public long updateProviderHealth(String platformCode, String featureCode,
                                     String providerKey, boolean isHealthy) {
        return featureConfigDao.updateProviderHealth(platformCode, featureCode, providerKey, isHealthy);
    }

    /**
     * 更新指定功能下某供应商的限流速率。
     *
     * <p>更新成功后自动淘汰该功能的配置缓存；调用方还需调用
     * {@code ProviderRateLimitManager#syncRate} 让令牌桶热更新。
     *
     * @return 命中的文档数；0 表示 platform / feature / providerKey 组合不存在
     */
    @CacheEvict(value = "featureConfig", key = "#platformCode + ':' + #featureCode")
    public long updateProviderRate(String platformCode, String featureCode,
                                   String providerKey, double ratePerSecond) {
        return featureConfigDao.updateProviderRate(platformCode, featureCode, providerKey, ratePerSecond);
    }
}
