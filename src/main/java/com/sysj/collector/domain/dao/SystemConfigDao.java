package com.sysj.collector.domain.dao;

import com.mongodb.client.result.UpdateResult;
import com.sysj.collector.domain.document.SystemConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统运行参数配置数据访问（集合 {@code system_config}，_id 即配置键）。
 */
@Slf4j
@Component
public class SystemConfigDao {

    @Resource
    private MongoTemplate mongoTemplate;

    /** 全量加载为 键→值 映射（供缓存整表缓存，一次 DB 读）。 */
    public Map<String, String> loadAll() {
        Map<String, String> result = new LinkedHashMap<>();
        for (SystemConfig config : mongoTemplate.findAll(SystemConfig.class)) {
            if (config.getId() != null && config.getValue() != null) {
                result.put(config.getId(), config.getValue());
            }
        }
        return result;
    }

    /** 全部配置文档（管理接口展示用）。 */
    public List<SystemConfig> findAll() {
        return mongoTemplate.findAll(SystemConfig.class);
    }

    /**
     * 写入配置（upsert；{@code _id} 已存在则更新值与说明）。
     */
    public void upsert(String key, String value, String description) {
        Query query = new Query(Criteria.where("_id").is(key));
        Update update = new Update()
                .set("value", value)
                .set("updateTime", Instant.now());
        if (description != null) {
            update.set("description", description);
        }
        mongoTemplate.upsert(query, update, SystemConfig.class);
    }

    /**
     * 键不存在时插入默认值（{@code $setOnInsert}），已存在则完全不动。
     *
     * <p>与 {@code db/init-comment-collector.js} 的播种口径一致：只补缺、不覆盖人工改过的值。
     *
     * @return true = 本次真的插入了新键；false = 键已存在（未改动）
     */
    public boolean insertIfAbsent(String key, String value, String description) {
        Query query = new Query(Criteria.where("_id").is(key));
        Update update = new Update()
                .setOnInsert("value", value)
                .setOnInsert("description", description)
                .setOnInsert("updateTime", Instant.now());
        UpdateResult result = mongoTemplate.upsert(query, update, SystemConfig.class);
        return result.getUpsertedId() != null;
    }

    /**
     * 集合是否存在。
     *
     * <p>首次发布的库执行本服务前根本不会有该集合（Mongo 按需建集合），
     * 需要在播种前区分「集合不存在 → 空库」与「集合存在但键缺失」两种情况以便日志说明。
     */
    public boolean collectionExists() {
        String expected = mongoTemplate.getCollectionName(SystemConfig.class);
        for (String name : mongoTemplate.getDb().listCollectionNames()) {
            if (expected.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 删除配置（回到 properties 默认）。 */
    public boolean delete(String key) {
        return mongoTemplate.remove(new Query(Criteria.where("_id").is(key)), SystemConfig.class)
                .getDeletedCount() > 0;
    }
}
