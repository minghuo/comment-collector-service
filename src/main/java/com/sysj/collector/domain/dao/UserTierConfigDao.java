package com.sysj.collector.domain.dao;

import com.sysj.collector.domain.document.UserTierConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
     * 等级不存在时插入（按 {@code tier_code} 判重），已存在则完全不动。
     *
     * <p>用「先查后插」而不是 {@code save}：{@code save} 以 _id 为准，
     * 人工维护的等级文档 _id 可能是 ObjectId，播种会覆盖它。并发下由
     * {@code tier_code} 唯一索引兜底（{@link DuplicateKeyException} 视为已存在）。
     *
     * @return true = 本次真的插入了新等级；false = 该等级已存在（未改动）
     */
    public boolean insertIfAbsent(UserTierConfig tierConfig) {
        if (tierConfig == null || tierConfig.getTierCode() == null) {
            return false;
        }
        if (findByTierCode(tierConfig.getTierCode()).isPresent()) {
            return false;
        }
        try {
            mongoTemplate.insert(tierConfig);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("等级已由并发写入创建，本次跳过: tierCode={}", tierConfig.getTierCode());
            return false;
        }
    }

    /**
     * 集合是否存在（区分「空库无集合」与「集合存在但等级缺失」）。
     */
    public boolean collectionExists() {
        String expected = mongoTemplate.getCollectionName(UserTierConfig.class);
        for (String name : mongoTemplate.getDb().listCollectionNames()) {
            if (expected.equals(name)) {
                return true;
            }
        }
        return false;
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
