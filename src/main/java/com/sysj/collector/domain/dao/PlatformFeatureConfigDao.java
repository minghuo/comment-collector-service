package com.sysj.collector.domain.dao;

import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.mongodb.client.result.UpdateResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 平台功能配置数据访问（含供应商内嵌数组的精确更新）。
 *
 * <p>集合：{@code platform_feature_config}
 *
 * <p>写法对齐 {@code auto-task-web} 的 DAO 风格：自包含、直接注入 {@link MongoTemplate}、Criteria 内联。
 * 字段名可写驼峰，{@code MongoTemplate} 会自动映射为落库的下划线名（见 {@link MasterTaskDao} 类注释）。
 */
@Slf4j
@Component
public class PlatformFeatureConfigDao {

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 保存平台功能配置。
     */
    public void save(PlatformFeatureConfig config) {
        mongoTemplate.save(config);
    }

    /**
     * 按平台编码 + 功能编码查询（走 {@code idx_platform_feature} 复合唯一索引）。
     */
    public Optional<PlatformFeatureConfig> findByPlatformCodeAndFeatureCode(
            String platformCode, String featureCode) {
        Query query = new Query();
        query.addCriteria(Criteria.where("platformCode").is(platformCode)
                .and("featureCode").is(featureCode));
        return Optional.ofNullable(mongoTemplate.findOne(query, PlatformFeatureConfig.class));
    }

    /**
     * 按平台编码查询该平台下全部功能配置。
     */
    public List<PlatformFeatureConfig> findByPlatformCode(String platformCode) {
        Query query = new Query();
        query.addCriteria(Criteria.where("platformCode").is(platformCode));
        return mongoTemplate.find(query, PlatformFeatureConfig.class);
    }

    /**
     * 查询全部启用的功能配置。
     */
    public List<PlatformFeatureConfig> findAllEnabled() {
        Query query = new Query();
        query.addCriteria(Criteria.where("status").is(true));
        return mongoTemplate.find(query, PlatformFeatureConfig.class);
    }

    /**
     * 查询**全部**功能配置（含 status=false）。
     *
     * <p>供启动期一致性校验使用：停用的配置也要校验，
     * 否则"停用期间改坏了 key，等启用时才炸"。
     */
    public List<PlatformFeatureConfig> findAll() {
        return mongoTemplate.find(new Query(), PlatformFeatureConfig.class);
    }

    // ── 供应商数组内元素的精确更新（$ 位置操作符） ─────────────────────────

    /**
     * 更新指定功能下某供应商的健康状态。
     *
     * <p>使用 {@code $} 位置操作符只更新命中的那一个数组元素，不影响同一文档下的其他供应商。
     * 更新后调用方须淘汰配置缓存。
     *
     * @return 命中的文档数（0 表示 platform / feature / providerKey 组合不存在）
     */
    public long updateProviderHealth(String platformCode, String featureCode,
                                     String providerKey, boolean isHealthy) {
        UpdateResult result = mongoTemplate.updateFirst(
                providerElementQuery(platformCode, featureCode, providerKey),
                new Update().set("providers.$.isHealthy", isHealthy),
                PlatformFeatureConfig.class);
        warnIfNotMatched("供应商健康状态", result, platformCode, featureCode, providerKey);
        return result.getMatchedCount();
    }

    /**
     * 更新指定功能下某供应商的限流速率。
     *
     * <p>更新后调用方须淘汰配置缓存，并调用 {@code ProviderRateLimitManager#syncRate} 让令牌桶热更新。
     *
     * @return 命中的文档数（0 表示组合不存在）
     */
    public long updateProviderRate(String platformCode, String featureCode,
                                   String providerKey, double ratePerSecond) {
        UpdateResult result = mongoTemplate.updateFirst(
                providerElementQuery(platformCode, featureCode, providerKey),
                new Update().set("providers.$.ratePerSecond", ratePerSecond),
                PlatformFeatureConfig.class);
        warnIfNotMatched("供应商限流速率", result, platformCode, featureCode, providerKey);
        return result.getMatchedCount();
    }

    /**
     * 构造"定位某功能下某供应商数组元素"的查询条件。
     */
    private Query providerElementQuery(String platformCode, String featureCode, String providerKey) {
        Query query = new Query();
        query.addCriteria(Criteria.where("platformCode").is(platformCode)
                .and("featureCode").is(featureCode)
                .and("providers.providerKey").is(providerKey));
        return query;
    }

    /**
     * 未命中时给出明确告警，避免字段名/取值写错导致"静默更新 0 条"。
     */
    private void warnIfNotMatched(String action, UpdateResult result,
                                  String platformCode, String featureCode, String providerKey) {
        if (result.getMatchedCount() == 0) {
            log.warn("{}更新未命中任何文档: platform={} feature={} provider={}",
                    action, platformCode, featureCode, providerKey);
        } else {
            log.info("{}已更新: platform={} feature={} provider={}",
                    action, platformCode, featureCode, providerKey);
        }
    }
}
