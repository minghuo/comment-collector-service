package com.sysj.collector.domain.dao;

import com.sysj.collector.domain.document.SupplierState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 供应商运行时状态数据访问。
 *
 * <p>集合：{@code supplier_state}
 *
 * <p>写法对齐 {@code auto-task-web} 的 DAO 风格：自包含、直接注入 {@link MongoTemplate}、Criteria 内联。
 * 字段名可写驼峰，{@code MongoTemplate} 会自动映射为落库的下划线名（见 {@link MasterTaskDao} 类注释）。
 */
@Slf4j
@Component
public class SupplierStateDao {

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 保存供应商状态。
     */
    public void save(SupplierState supplierState) {
        mongoTemplate.save(supplierState);
    }

    /**
     * 按供应商 Key 查询（{@code platform:feature:providerKey}）。
     */
    public Optional<SupplierState> findBySupplierKey(String supplierKey) {
        if (supplierKey == null || supplierKey.isBlank()) {
            return Optional.empty();
        }
        Query query = new Query();
        query.addCriteria(Criteria.where("supplierKey").is(supplierKey));
        return Optional.ofNullable(mongoTemplate.findOne(query, SupplierState.class));
    }

    /**
     * 查询某功能下全部供应商状态。
     */
    public List<SupplierState> findByPlatformCodeAndFeatureCode(String platformCode, String featureCode) {
        Query query = new Query();
        query.addCriteria(Criteria.where("platformCode").is(platformCode)
                .and("featureCode").is(featureCode));
        query.with(Sort.by(Sort.Direction.ASC, "supplierKey"));
        return mongoTemplate.find(query, SupplierState.class);
    }

    /**
     * 只更新自适应限速的两个派生字段。
     *
     * <p><b>必须用 `$set` 定点更新，不能 `save()` 整档</b>：{@code supplier_state} 同时被熔断器
     * （`circuit_state` / 计数）和自适应限速（`effective_qps` / `adaptive_delay_ms`）写入，
     * 整档替换会让两个写者互相覆盖 —— 正是 C-12 那类"两个写者一个真相"问题的翻版。
     *
     * @return 是否命中文档（文档不存在时为 false，不做 upsert）
     */
    public boolean updateAdaptiveMetrics(String supplierKey, double effectiveQps, long adaptiveDelayMs) {
        if (supplierKey == null || supplierKey.isBlank()) {
            return false;
        }
        Query query = new Query(Criteria.where("supplierKey").is(supplierKey));
        Update update = new Update()
                .set("effectiveQps", effectiveQps)
                .set("adaptiveDelayMs", adaptiveDelayMs)
                .set("updateTime", Instant.now());
        return mongoTemplate.updateFirst(query, update, SupplierState.class).getMatchedCount() > 0;
    }

    /** 全量查询（注册表同步用）。 */
    public List<SupplierState> findAll() {
        return mongoTemplate.findAll(SupplierState.class);
    }

    /**
     * 按身份字段补建供应商状态条目（注册表同步用，幂等）。
     *
     * <p>身份字段（supplierKey / platformCode / featureCode / providerKey）用 `$set`
     * 始终对齐配置；运行时状态（熔断/计数/健康）用 `$setOnInsert` 只在**文档不存在**时写初值
     * —— 已在线的供应商其熔断状态与计数不会被同步任务触碰（与 init 脚本同口径）。
     *
     * @return true = 本次新建了文档；false = 文档已存在（仅对齐身份字段）
     */
    public boolean upsertIdentity(String supplierKey, String platformCode, String featureCode, String providerKey) {
        Instant now = Instant.now();
        Query query = new Query(Criteria.where("_id").is(supplierKey));
        Update update = new Update()
                .set("supplierKey", supplierKey)
                .set("platformCode", platformCode)
                .set("featureCode", featureCode)
                .set("providerKey", providerKey)
                .setOnInsert("circuitState", "CLOSED")
                .setOnInsert("healthStatus", "UP")
                .setOnInsert("currentQps", 0.0)
                .setOnInsert("effectiveQps", 0.0)
                .setOnInsert("queueDepth", 0)
                .setOnInsert("consecutiveFailures", 0)
                .setOnInsert("totalSuccess", 0L)
                .setOnInsert("totalFailure", 0L)
                .setOnInsert("avgResponseTime", 0.0)
                .setOnInsert("lastHeartbeat", now)
                .setOnInsert("updateTime", now);
        return mongoTemplate.upsert(query, update, SupplierState.class).getUpsertedId() != null;
    }
}
