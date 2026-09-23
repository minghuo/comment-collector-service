package com.sysj.collector.domain.service;


import com.sysj.collector.domain.document.PlatformFeatureConfig;

import com.sysj.collector.domain.document.UserTierConfig;

import com.sysj.collector.domain.repository.PlatformFeatureConfigRepository;

import com.sysj.collector.domain.repository.UserTierConfigRepository;

import com.sysj.collector.exception.CollectorException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 配置领域服务。
 *
 * <p>所有查询走 Caffeine 本地缓存（TTL 60s），
 * 运维修改 MongoDB 后可调用 evict 接口立即刷新，
 * 或等待 TTL 自然过期（最长 60s 延迟）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderConfigService {

    private final PlatformFeatureConfigRepository featureConfigRepo;
    private final UserTierConfigRepository userTierConfigRepo;
    private final MongoTemplate mongoTemplate;

    // ── 查询（带缓存） ─────────────────────────────────────────────────────

    /**
     * 查询平台功能配置（含嵌套供应商列表）。
     * 缓存 key = "platformCode:featureCode"。
     */
    @Cacheable(value = "featureConfig", key = "#platformCode + ':' + #featureCode")
    public PlatformFeatureConfig loadFeatureConfig(String platformCode, String featureCode) {
        return featureConfigRepo
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
        return userTierConfigRepo.findByTierCode(tierCode);
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

    // ── MongoDB 运维操作（不通过 Repository，直接用 MongoTemplate 精确更新） ─

    /**
     * 更新指定功能下某供应商的健康状态。
     * 使用 MongoDB 数组过滤器做精确字段更新，不影响其他供应商。
     * 更新后调用方须手动淘汰缓存。
     */
    public void updateProviderHealth(String platformCode, String featureCode,
                                     String providerKey, boolean isHealthy) {
        Query query = new Query(
                Criteria.where("platformCode").is(platformCode)
                        .and("featureCode").is(featureCode)
                        .and("providers.providerKey").is(providerKey));
        Update update = new Update()
                .set("providers.$.isHealthy", isHealthy);
        mongoTemplate.updateFirst(query, update, PlatformFeatureConfig.class);
        log.info("供应商健康状态已更新: platform={} feature={} provider={} isHealthy={}",
                platformCode, featureCode, providerKey, isHealthy);
    }

    /**
     * 更新指定功能下某供应商的限流速率。
     * 更新后调用方须手动淘汰缓存 + 调用限流管理器热更新速率。
     */
    public void updateProviderRate(String platformCode, String featureCode,
                                   String providerKey, double ratePerSecond) {
        Query query = new Query(
                Criteria.where("platformCode").is(platformCode)
                        .and("featureCode").is(featureCode)
                        .and("providers.providerKey").is(providerKey));
        Update update = new Update()
                .set("providers.$.ratePerSecond", ratePerSecond);
        mongoTemplate.updateFirst(query, update, PlatformFeatureConfig.class);
        log.info("供应商限流速率已更新: platform={} feature={} provider={} rate={}",
                platformCode, featureCode, providerKey, ratePerSecond);
    }
}
