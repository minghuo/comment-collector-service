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
     * 熔断观测副本的**定点 upsert**（熔断器专用，修 R2）。
     *
     * <p><b>为什么不是 {@code save()} 整档替换</b>：
     * <ol>
     *   <li>{@code save()} 在 {@code id} 为空时走 insert，而 {@code supplier_state} 的
     *       {@code _id} 约定等于 {@code supplierKey}（见 {@link #upsertIdentity}）——
     *       于是第二次写穿就撞 {@code idx_supplier_key} 唯一索引抛 {@code DuplicateKeyException}，
     *       被调用方 {@code catch} 成"非关键"日志，{@code circuit_state} / {@code health_status} /
     *       {@code circuit_opened_at} 从此**永远停在初值**：运维看到的健康状态与真实熔断状态脱节，
     *       而且 Redis 侧播种再也读不到 OPEN；</li>
     *   <li>整档替换还会把自适应限速写的 {@code effective_qps} / {@code adaptive_delay_ms}
     *       覆盖掉 —— 正是 {@link #updateAdaptiveMetrics} 注释里警告的"两个写者一个真相"的翻版。</li>
     * </ol>
     * 因此这里按 {@code _id = supplierKey} upsert，且只 {@code $set} 熔断器自己拥有的字段：
     * 别人的字段只在文档不存在时给初值；计数字段仅在实体上非 null 时写入（本实例还没累计过就保留库里已有的值）。
     *
     * @return 是否新建了文档
     */
    public boolean upsertBreakerSnapshot(SupplierState state) {
        if (state == null || state.getSupplierKey() == null || state.getSupplierKey().isBlank()) {
            return false;
        }
        Instant now = Instant.now();
        Query query = new Query(Criteria.where("_id").is(state.getSupplierKey()));
        Update update = new Update()
                .set("supplierKey", state.getSupplierKey())
                .set("platformCode", state.getPlatformCode())
                .set("featureCode", state.getFeatureCode())
                .set("circuitState", state.getCircuitState())
                .set("healthStatus", state.getHealthStatus())
                .set("lastHeartbeat", state.getLastHeartbeat() == null ? now : state.getLastHeartbeat())
                .set("updateTime", state.getUpdateTime() == null ? now : state.getUpdateTime())
                // 其它写者拥有的字段只在文档不存在时给初值，绝不覆盖
                .setOnInsert("currentQps", 0.0)
                .setOnInsert("effectiveQps", 0.0)
                .setOnInsert("adaptiveDelayMs", 0L)
                .setOnInsert("queueDepth", 0);
        if (state.getCircuitOpenedAt() == null) {
            update.unset("circuitOpenedAt"); // 转回 CLOSED 时必须显式清空
        } else {
            update.set("circuitOpenedAt", state.getCircuitOpenedAt());
        }
        if (state.getConsecutiveFailures() != null) {
            update.set("consecutiveFailures", state.getConsecutiveFailures());
        }
        if (state.getTotalSuccess() != null) {
            update.set("totalSuccess", state.getTotalSuccess());
        }
        if (state.getTotalFailure() != null) {
            update.set("totalFailure", state.getTotalFailure());
        }
        if (state.getLastSuccessTime() != null) {
            update.set("lastSuccessTime", state.getLastSuccessTime());
        }
        if (state.getLastFailureTime() != null) {
            update.set("lastFailureTime", state.getLastFailureTime());
        }
        if (state.getAvgResponseTime() != null) {
            update.set("avgResponseTime", state.getAvgResponseTime());
        }
        return mongoTemplate.upsert(query, update, SupplierState.class).getUpsertedId() != null;
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
     * <p>{@code effectiveQps <= 0} 视为**未知**（限流阶段未执行时
     * {@code AdaptiveDelayPolicy.effectiveRate} 恒返回 0），此时只更新延迟、不覆盖已有 qps，
     * 避免把"未知"写成"完全不能跑"。
     *
     * @return 是否命中文档（文档不存在时为 false，不做 upsert）
     */
    public boolean updateAdaptiveMetrics(String supplierKey, double effectiveQps, long adaptiveDelayMs) {
        if (supplierKey == null || supplierKey.isBlank()) {
            return false;
        }
        Query query = new Query(Criteria.where("supplierKey").is(supplierKey));
        Update update = new Update()
                .set("adaptiveDelayMs", adaptiveDelayMs)
                .set("updateTime", Instant.now());
        if (effectiveQps > 0) {
            update.set("effectiveQps", effectiveQps);
        }
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
