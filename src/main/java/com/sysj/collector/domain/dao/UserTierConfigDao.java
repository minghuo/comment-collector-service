package com.sysj.collector.domain.dao;

import com.sysj.collector.domain.document.UserTierConfig;
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
 * 用户等级配置数据访问。
 *
 * <p>集合：{@code user_tier_config}
 *
 * <p>写法对齐 {@code auto-task-web} 的 DAO 风格：自包含、直接注入 {@link MongoTemplate}、Criteria 内联。
 * 字段名可写驼峰，{@code MongoTemplate} 会自动映射为落库的下划线名（见 {@link MasterTaskDao} 类注释）。
 */
@Slf4j
@Component
public class UserTierConfigDao {

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 保存用户等级配置。
     */
    public void save(UserTierConfig tierConfig) {
        mongoTemplate.save(tierConfig);
    }

    /**
     * 按等级编码查询（{@code tier_code} 唯一索引）。
     */
    public Optional<UserTierConfig> findByTierCode(String tierCode) {
        if (tierCode == null || tierCode.isBlank()) {
            return Optional.empty();
        }
        Query query = new Query();
        query.addCriteria(Criteria.where("tierCode").is(tierCode));
        return Optional.ofNullable(mongoTemplate.findOne(query, UserTierConfig.class));
    }

    /**
     * 查询全部等级配置（优先级升序，数值越小越优先）。
     */
    public List<UserTierConfig> findAllOrderByPriority() {
        Query query = new Query();
        query.with(Sort.by(Sort.Direction.ASC, "priority"));
        return mongoTemplate.find(query, UserTierConfig.class);
    }
}
