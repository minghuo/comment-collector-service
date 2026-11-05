package com.sysj.collector.domain.dao;

import com.sysj.collector.domain.document.AccountHistoryDoc;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 账号历史结果 DAO（集合 {@code account_history}）。
 *
 * <p>写法与 {@link CommentDao} 保持一致：自包含、直接 {@link MongoTemplate}、Criteria 内联；
 * 字段名可写驼峰，{@code MongoTemplate} 会按全局 {@code SnakeCaseFieldNamingStrategy}
 * 映射为落库的下划线名（{@code task_id}、{@code publish_time} …）。
 *
 * <p><b>写入为什么用 upsert</b>：同一条作品可能被"首次采集 + 多次重试 + 自动翻页重叠页"
 * 重复取到，业务主键（{@code taskId-accountId-itemId}）相同。{@code replaceOne + upsert}
 * 让后者覆盖前者而不是插入新行，结果集大小只取决于作品数，与调度次数无关。
 */
@Slf4j
@Component
public class AccountHistoryDao {

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 批量保存（按业务主键 upsert，幂等）。
     *
     * @return 实际写入条数（upsert + modify）
     */
    public int saveAll(List<AccountHistoryDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        try {
            BulkOperations ops = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED,
                    AccountHistoryDoc.class);
            for (AccountHistoryDoc doc : docs) {
                ops.replaceOne(new Query(Criteria.where("_id").is(doc.getId())), doc,
                        FindAndReplaceOptions.options().upsert());
            }
            com.mongodb.bulk.BulkWriteResult result = ops.execute();
            return (int) (result.getUpserts().size() + result.getModifiedCount());
        } catch (Exception e) {
            log.warn("账号历史批量 upsert 失败，退化为逐条 save: size={} error={}",
                    docs.size(), e.getMessage());
            int saved = 0;
            for (AccountHistoryDoc doc : docs) {
                try {
                    mongoTemplate.save(doc);
                    saved++;
                } catch (Exception ex) {
                    log.error("保存账号历史失败: id={} error={}", doc.getId(), ex.getMessage());
                }
            }
            return saved;
        }
    }

    /**
     * 按任务分页（{@code accountId} 非空时只取该账号），发布时间倒序。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     */
    public List<AccountHistoryDoc> pageByTaskId(String taskId, String accountId, int page, int size) {
        Query query = new Query(taskCriteria(taskId, accountId));
        query.with(Sort.by(Sort.Order.desc("publishTime"), Sort.Order.desc("insertTime")));
        query.skip((long) (Math.max(page, 1) - 1) * size);
        query.limit(size);
        return mongoTemplate.find(query, AccountHistoryDoc.class);
    }

    /** 按任务计数（{@code accountId} 非空时只算该账号）。 */
    public long countByTaskId(String taskId, String accountId) {
        return mongoTemplate.count(new Query(taskCriteria(taskId, accountId)), AccountHistoryDoc.class);
    }

    /** 删除某任务的全部账号历史（任务清理/重跑用）。 */
    public long deleteByTaskId(String taskId) {
        return mongoTemplate.remove(new Query(Criteria.where("taskId").is(taskId)),
                AccountHistoryDoc.class).getDeletedCount();
    }

    private static Criteria taskCriteria(String taskId, String accountId) {
        Criteria criteria = Criteria.where("taskId").is(taskId);
        if (StringUtils.hasText(accountId)) {
            criteria = criteria.and("accountId").is(accountId);
        }
        return criteria;
    }
}
