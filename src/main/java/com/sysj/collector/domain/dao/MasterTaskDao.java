package com.sysj.collector.domain.dao;

import com.sysj.collector.domain.document.MasterTask;
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
 * 主任务数据访问。
 *
 * <p>集合：{@code master_task}
 *
 * <p>写法对齐 {@code auto-task-web} 的 DAO 风格：
 * 每个 DAO 自包含、直接注入 {@link MongoTemplate}、Criteria 内联在方法体里。
 *
 * <p><b>字段名可以写成驼峰</b>：{@link MongoTemplate} 在查询/更新前会经
 * {@code QueryMapper} / {@code UpdateMapper} 把字段名按实体的映射规则转换成落库名
 * （本应用启用了 {@code SnakeCaseFieldNamingStrategy}，落库为 {@code platform_code} 这类下划线形式）。
 * 因此 {@code Criteria.where("platformCode")} 与 {@code Criteria.where("platform_code")} 都能命中，
 * 与 auto-task-web 的写法保持一致。<b>唯一例外</b>：{@code @CompoundIndex(def = "...")} 中的字符串
 * 不经过映射，必须直接写落库名。
 */
@Slf4j
@Component
public class MasterTaskDao {

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 保存主任务（{@code _id} 已存在则覆盖）。
     */
    public void save(MasterTask masterTask) {
        mongoTemplate.save(masterTask);
    }

    /**
     * 按主任务 ID 查询。
     */
    public Optional<MasterTask> findById(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongoTemplate.findById(taskId, MasterTask.class));
    }

    /**
     * 按状态查询（创建时间升序）。
     */
    public List<MasterTask> findByStatus(String status) {
        Query query = new Query();
        query.addCriteria(Criteria.where("status").is(status));
        query.with(Sort.by(Sort.Direction.ASC, "createTime"));
        return mongoTemplate.find(query, MasterTask.class);
    }

    /**
     * 按用户 ID 查询（创建时间倒序）。
     */
    public List<MasterTask> findByUserId(String userId) {
        Query query = new Query();
        query.addCriteria(Criteria.where("userId").is(userId));
        query.with(Sort.by(Sort.Direction.DESC, "createTime"));
        return mongoTemplate.find(query, MasterTask.class);
    }

    /**
     * 统计某状态的任务数。
     */
    public long countByStatus(String status) {
        return mongoTemplate.count(new Query(Criteria.where("status").is(status)), MasterTask.class);
    }
}
