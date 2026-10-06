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
     * <p>更新后调用方须淘汰配置缓存，速率与延迟均为共享状态，下一次取令牌即按新速率执行（令牌桶在 Redis）。
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
     * 供应商自动注册（**代码即配置**，幂等）：把注解声明的供应商写入功能配置。
     *
     * <ol>
     *   <li>功能文档不存在 → 创建（status=true，单供应商）；</li>
     *   <li>文档存在但缺该 providerKey → 追加数组元素（注解里的参数只是初值）；</li>
     *   <li>已存在 → 只把 capabilities 对齐为代码声明（{@code $} 位置操作符），
     *       运维调过的速率/优先级/健康开关等参数一律不动。</li>
     * </ol>
     *
     * @return 注册结果：CREATED_FEATURE / ADDED / ALIGNED / EXISTS
     */
    public String upsertProvider(String platformCode, String featureCode, String featureName,
                                 PlatformFeatureConfig.ProviderConfig provider) {
        String docId = platformCode + ":" + featureCode;
        boolean docExists = mongoTemplate.exists(
                Query.query(Criteria.where("_id").is(docId)), PlatformFeatureConfig.class);
        if (!docExists) {
            PlatformFeatureConfig doc = new PlatformFeatureConfig();
            doc.setId(docId);
            doc.setPlatformCode(platformCode);
            doc.setFeatureCode(featureCode);
            doc.setFeatureName(featureName == null || featureName.isBlank()
                    ? platformCode + "/" + featureCode : featureName);
            doc.setStatus(true);
            doc.setProviders(new java.util.ArrayList<>(List.of(provider)));
            try {
                mongoTemplate.insert(doc);
                return "CREATED_FEATURE";
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发创建竞争：文档已被其他实例建出，落到下面的追加/对齐逻辑
            }
        }
        Query absent = Query.query(Criteria.where("_id").is(docId)
                .and("providers.providerKey").ne(provider.getProviderKey()));
        if (mongoTemplate.updateFirst(absent, new Update().push("providers", provider),
                PlatformFeatureConfig.class).getModifiedCount() > 0) {
            return "ADDED";
        }
        UpdateResult aligned = mongoTemplate.updateFirst(
                providerElementQuery(platformCode, featureCode, provider.getProviderKey()),
                new Update().set("providers.$.capabilities", provider.getCapabilities()),
                PlatformFeatureConfig.class);
        return aligned.getMatchedCount() > 0 ? "ALIGNED" : "EXISTS";
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
