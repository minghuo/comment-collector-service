package com.sysj.collector.domain.dao;

import com.sysj.collector.domain.document.SupplierState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

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
}
