package com.sysj.collector.domain.dao;

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

    /** 删除配置（回到 properties 默认）。 */
    public boolean delete(String key) {
        return mongoTemplate.remove(new Query(Criteria.where("_id").is(key)), SystemConfig.class)
                .getDeletedCount() > 0;
    }
}
