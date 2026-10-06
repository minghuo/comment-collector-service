package com.sysj.collector.domain.dao;

import com.sysj.collector.domain.document.MasterTask;
import com.sysj.collector.domain.document.TaskStatuses;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    /**
     * 按自动分派批次 ID 查询同批拆分的全部主任务（创建时间升序）。
     */
    public List<MasterTask> findByDispatchId(String dispatchId) {
        if (dispatchId == null || dispatchId.isBlank()) {
            return List.of();
        }
        Query query = new Query();
        query.addCriteria(Criteria.where("dispatchId").is(dispatchId));
        query.with(Sort.by(Sort.Direction.ASC, "createTime"));
        return mongoTemplate.find(query, MasterTask.class);
    }

    /**
     * 按提交幂等键查询主任务。
     *
     * <p>配合唯一部分索引 {@code idx_idempotency_key}（见 {@code db/init-comment-collector.js}）：
     * 同一幂等键并发提交时，后到的写入会因唯一索引失败，由调用方回查本方法返回已建成的那条。
     */
    public Optional<MasterTask> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongoTemplate.findOne(
                new Query(Criteria.where("idempotencyKey").is(idempotencyKey)), MasterTask.class));
    }

    /**
     * 原子扩张主任务的链接总数（自动翻页续采子任务计入总数，使"成功+失败 >= 总数"的终态判定持续成立）。
     *
     * @return 扩张后的主任务文档；任务不存在时为空
     */
    public Optional<MasterTask> incTotalLinks(String masterTaskId, int delta) {
        Query query = new Query(Criteria.where("id").is(masterTaskId));
        Update update = new Update()
                .inc("totalLinks", delta)
                .set("updateTime", Instant.now());
        MasterTask updated = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), MasterTask.class);
        return Optional.ofNullable(updated);
    }

    /**
     * 原子累加一个子任务的终态计数（替代"全量拉取子任务重算"的 O(N²) 收敛）。
     *
     * <p>每个子任务到达终态时恰好调用一次：{@code successLinks}/{@code failedLinks}
     * 各自单调累加，{@code successLinks + failedLinks} 即已完成数，
     * 与 {@code totalLinks} 比较即可判断是否全部终态，无需扫描子任务集合。
     *
     * @return 累加后的主任务文档；主任务不存在时为空
     */
    public Optional<MasterTask> recordSubTaskOutcome(String masterTaskId, boolean success) {
        Query query = new Query(Criteria.where("id").is(masterTaskId));
        Update update = new Update()
                .inc(success ? "successLinks" : "failedLinks", 1)
                .set("updateTime", Instant.now());
        MasterTask updated = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), MasterTask.class);
        return Optional.ofNullable(updated);
    }

    /**
     * 条件终态迁移：仅当主任务尚未处于终态时写入 COMPLETED / FAILED 并记录完成时间。
     *
     * <p>并发下多个子任务同时判断"已全部终态"，只有一个能通过 {@code status nin 终态} 的
     * 条件更新完成迁移（findAndModify 原子性），其余返回空 —— 终态不会被反复覆盖。
     *
     * @return 迁移后的主任务文档；已处于终态（或任务不存在）时为空
     */
    public Optional<MasterTask> transitionToTerminalIfFirst(String masterTaskId, long success, long failed) {
        Query query = new Query(Criteria.where("id").is(masterTaskId)
                .and("status").nin(TaskStatuses.MASTER_TERMINAL));
        Update update = new Update()
                .set("status", TaskStatuses.masterTerminalStatusOf(success, failed))
                .set("completeTime", Instant.now())
                .set("updateTime", Instant.now());
        MasterTask updated = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), MasterTask.class);
        return Optional.ofNullable(updated);
    }
}
