package com.sysj.collector.domain.dao;

import com.sysj.collector.domain.document.SubTask;
import com.sysj.collector.domain.document.TaskStatuses;
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
import java.util.stream.Stream;

/**
 * 子任务数据访问。
 *
 * <p>集合：{@code sub_task}
 *
 * <p>写法对齐 {@code auto-task-web} 的 DAO 风格：自包含、直接注入 {@link MongoTemplate}、Criteria 内联。
 * 字段名可写驼峰，{@code MongoTemplate} 会自动映射为落库的下划线名（见 {@link MasterTaskDao} 类注释）。
 */
@Slf4j
@Component
public class SubTaskDao {

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 保存子任务。
     */
    public void save(SubTask subTask) {
        mongoTemplate.save(subTask);
    }

    /**
     * 批量保存子任务。
     *
     * <p>与 auto-task-web 的 {@code saveAll} 一致：优先 {@code insertAll}，
     * 一旦因 {@code _id} 冲突等原因失败，退化为逐条 {@code save}（upsert），保证不丢数据。
     */
    public void saveAll(List<SubTask> subTasks) {
        if (subTasks == null || subTasks.isEmpty()) {
            return;
        }
        try {
            mongoTemplate.insertAll(subTasks);
        } catch (Exception e) {
            log.warn("insertAll 失败，退化为逐条 save: size={} error={}", subTasks.size(), e.getMessage());
            for (SubTask subTask : subTasks) {
                save(subTask);
            }
        }
    }

    /**
     * 按子任务 ID 查询。
     */
    public Optional<SubTask> findById(String subTaskId) {
        if (subTaskId == null || subTaskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongoTemplate.findById(subTaskId, SubTask.class));
    }

    /**
     * 按主任务 ID 查询全部子任务（创建时间升序）。
     */
    public List<SubTask> findByMasterTaskId(String masterTaskId) {
        Query query = new Query();
        query.addCriteria(Criteria.where("masterTaskId").is(masterTaskId));
        query.with(Sort.by(Sort.Direction.ASC, "createTime"));
        return mongoTemplate.find(query, SubTask.class);
    }

    /**
     * 按主任务 ID 流式查询（数据量大时避免一次性载入内存）。
     */
    public Stream<SubTask> streamByMasterTaskId(String masterTaskId) {
        Query query = new Query();
        query.addCriteria(Criteria.where("masterTaskId").is(masterTaskId));
        query.with(Sort.by(Sort.Direction.ASC, "createTime"));
        return mongoTemplate.stream(query, SubTask.class);
    }

    /**
     * 按状态列表批量查询（用于断点续采）。
     */
    public List<SubTask> findByStatusIn(List<String> statuses) {
        Query query = new Query();
        query.addCriteria(Criteria.where("status").in(statuses));
        query.with(Sort.by(Sort.Direction.ASC, "createTime"));
        return mongoTemplate.find(query, SubTask.class);
    }

    /**
     * 条件终态写入：仅当子任务尚未处于终态时写 SUCCESS/FAILED，返回是否由本次写入。
     *
     * <p>外置队列是**至少一次**投递（崩溃后 XCLAIM 接管、deadline 迁移竞态都会重复投递），
     * 该条件更新保证同一子任务的终态只被写一次 —— 调用方据此决定是否累加主任务计数，
     * 重复投递不会造成计数翻倍。
     *
     * @return true = 本次写入生效（首次终态）；false = 已是终态（重复投递跳过）
     */
    public boolean markTerminalIfFirst(String subTaskId, String terminalStatus,
                                       String providerKey, String resultJson, String errorMessage) {
        Query query = new Query(Criteria.where("id").is(subTaskId)
                .and("status").in(TaskStatuses.SUB_NON_TERMINAL));
        Update update = new Update()
                .set("status", terminalStatus)
                .set("errorMessage", errorMessage)
                .set("completeTime", Instant.now())
                .set("updateTime", Instant.now());
        if (providerKey != null) {
            update.set("providerKey", providerKey);
        }
        if (resultJson != null) {
            update.set("result", resultJson);
        }
        return mongoTemplate.updateFirst(query, update, SubTask.class).getModifiedCount() == 1;
    }

    /**
     * 条件写入 RETRYING（失败重投）：仅当子任务仍处于非终态白名单时写入。
     *
     * <p>此前 {@code TaskManagementService.scheduleRetry} 直接整文档 {@code save}，
     * 会把已被并发路径写成 SUCCESS/FAILED 的子任务复活成 RETRYING（重复采集、主任务计数错乱）。
     *
     * @return true = 本次写入生效；false = 已是终态（放弃重投）
     */
    public boolean markRetryingIfNotTerminal(String subTaskId, int retryCount, String errorMessage,
                                             Instant nextExecuteTime) {
        Query query = new Query(Criteria.where("id").is(subTaskId)
                .and("status").in(TaskStatuses.SUB_NON_TERMINAL));
        Update update = new Update()
                .set("status", TaskStatuses.SUB_RETRYING)
                .set("retryCount", retryCount)
                .set("errorMessage", errorMessage)
                .set("nextExecuteTime", nextExecuteTime)
                .set("completeTime", null)
                .set("updateTime", Instant.now());
        return mongoTemplate.updateFirst(query, update, SubTask.class).getModifiedCount() == 1;
    }

    /**
     * 条件领取一次重投：仅当子任务仍是 RETRYING（到期扫描读到的那个状态）时置为 RUNNING。
     *
     * <p>多实例部署下 {@code SubTaskRetryScheduler} 会在每个实例上同时扫到同一批到期子任务；
     * 该条件更新即"抢占"——只有 {@code modifiedCount == 1} 的实例可以入队，
     * 其余实例静默跳过（无需分布式锁，见审计项：定时任务无分布式锁）。
     *
     * @return true = 本次实例领取成功；false = 已被其它实例领取或已终态
     */
    public boolean markRunningIfRetrying(String subTaskId) {
        Query query = new Query(Criteria.where("id").is(subTaskId)
                .and("status").is(TaskStatuses.SUB_RETRYING));
        Update update = new Update()
                .set("status", TaskStatuses.SUB_RUNNING)
                .set("startTime", Instant.now())
                .set("updateTime", Instant.now());
        return mongoTemplate.updateFirst(query, update, SubTask.class).getModifiedCount() == 1;
    }

    /**
     * 队列满时把已领取（RUNNING）的重投任务推回 RETRYING，等下次扫描。
     *
     * @return true = 本次写回生效
     */
    public boolean markRetryingFromRunning(String subTaskId, Instant nextExecuteTime) {
        Query query = new Query(Criteria.where("id").is(subTaskId)
                .and("status").is(TaskStatuses.SUB_RUNNING));
        Update update = new Update()
                .set("status", TaskStatuses.SUB_RETRYING)
                .set("nextExecuteTime", nextExecuteTime)
                .set("updateTime", Instant.now());
        return mongoTemplate.updateFirst(query, update, SubTask.class).getModifiedCount() == 1;
    }

    /**
     * 条件领取一次"首次投递"：仅当子任务仍是 PENDING 时置为 RUNNING。
     *
     * <p>此前 {@code TaskManagementService.submitSubTask} 是"整文档 save 成 RUNNING 再入队"，
     * 在<b>多实例</b>下没有抢占语义：两个实例同时投递同一子任务会双双执行、主任务计数翻倍
     * （与重投路径已有的 {@link #markRunningIfRetrying} 不对称）。改为条件更新后，
     * 只有 {@code modifiedCount == 1} 的实例可以入队。
     *
     * @return true = 本次实例领取成功；false = 已被其它实例领取或已不是 PENDING
     */
    public boolean markRunningIfPending(String subTaskId) {
        Query query = new Query(Criteria.where("id").is(subTaskId)
                .and("status").is(TaskStatuses.SUB_PENDING));
        Update update = new Update()
                .set("status", TaskStatuses.SUB_RUNNING)
                .set("startTime", Instant.now())
                .set("updateTime", Instant.now());
        return mongoTemplate.updateFirst(query, update, SubTask.class).getModifiedCount() == 1;
    }

    /**
     * 条件领取一次"陈旧 RUNNING 子任务"（启动恢复路径）：仅当子任务仍为 RUNNING 且
     * {@code update_time < staleBefore} 时重新领取（刷新 startTime/updateTime、重置重试计数）。
     *
     * <p>为什么需要它：{@code TaskRecoveryService} 在启动时把 ACTIVE/PENDING 主任务下
     * PENDING/RUNNING 的子任务重新入队 —— 但"RUNNING"只说明**某个**实例正在跑，
     * 也完全可能是上一个实例崩溃留下的墓碑（子任务卡在 RUNNING 永不推进）。
     * 用"更新时刻足够旧"作为存活性判据：真的还在跑的实例会持续刷新 updateTime，
     * 于是条件不成立、不会造成重复投递；墓碑则被安全接管。
     *
     * @param staleBefore 早于该时刻仍无更新的 RUNNING 视为陈旧
     * @return true = 本次实例接管成功；false = 仍在被其它实例推进或已终态
     */
    public boolean markRunningIfStale(String subTaskId, Instant staleBefore) {
        Query query = new Query(Criteria.where("id").is(subTaskId)
                .and("status").is(TaskStatuses.SUB_RUNNING)
                .and("updateTime").lt(staleBefore));
        Update update = new Update()
                .set("status", TaskStatuses.SUB_RUNNING)
                .set("startTime", Instant.now())
                .set("updateTime", Instant.now())
                .set("retryCount", 0)
                .set("nextExecuteTime", null);
        return mongoTemplate.updateFirst(query, update, SubTask.class).getModifiedCount() == 1;
    }

    /**
     * 查询重投到期的子任务（RETRYING 且 next_execute_time 已到，创建时间升序，限量）。
     *
     * <p>配套部分索引 {@code idx_retry_scan}（{@code db/init-comment-collector.js}），
     * 只索引 RETRYING 状态的文档，扫描开销与待重投规模成正比。
     */
    public List<SubTask> findDueRetries(int limit) {
        Query query = new Query();
        query.addCriteria(Criteria.where("status").is("RETRYING")
                .and("nextExecuteTime").lte(Instant.now()));
        query.with(Sort.by(Sort.Direction.ASC, "nextExecuteTime"));
        query.limit(Math.max(1, limit));
        return mongoTemplate.find(query, SubTask.class);
    }

    /**
     * 分页查询某主任务下的子任务。
     */
    public List<SubTask> getSubTasksByMasterTaskId(String masterTaskId, int page, int pageSize) {
        Query query = new Query();
        query.addCriteria(Criteria.where("masterTaskId").is(masterTaskId));
        query.with(Sort.by(Sort.Direction.ASC, "createTime"));
        query.skip((long) (page - 1) * pageSize);
        query.limit(pageSize);
        return mongoTemplate.find(query, SubTask.class);
    }

    /**
     * 统计某主任务下的子任务数量。
     */
    public long countByMasterTaskId(String masterTaskId) {
        return mongoTemplate.count(new Query(Criteria.where("masterTaskId").is(masterTaskId)), SubTask.class);
    }

    /**
     * 删除某主任务下的全部子任务。
     */
    public long deleteByMasterTaskId(String masterTaskId) {
        return mongoTemplate.remove(new Query(Criteria.where("masterTaskId").is(masterTaskId)), SubTask.class)
                .getDeletedCount();
    }
}
