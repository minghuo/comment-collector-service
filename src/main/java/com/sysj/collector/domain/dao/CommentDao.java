package com.sysj.collector.domain.dao;

import com.sysj.collector.domain.document.CommentDoc;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Stream;

/**
 * 评论采集结果数据访问（集合 {@code comment}）。
 *
 * <p>写法对齐 {@code auto-task-web} 的 DAO 风格：自包含、直接注入 {@link MongoTemplate}、Criteria 内联。
 * 字段名可写驼峰，{@code MongoTemplate} 会自动映射为落库的下划线名。
 *
 * <p>用途：为非即时返回的任务保存结果，并支持按 {@code taskId} 持续分页读取
 * （对应 auto-task-web 的 {@code countByTaskId} / {@code getCommentsByTaskId} / {@code streamByTaskId} 三件套）。
 */
@Slf4j
@Component
public class CommentDao {

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 保存单条（{@code _id} 已存在则覆盖，天然幂等）。
     */
    public void save(CommentDoc doc) {
        mongoTemplate.save(doc);
    }

    /**
     * 批量保存。
     *
     * <p>与 auto-task-web 的写法一致：优先 {@code insertAll}，
     * 一旦因 {@code _id} 冲突等原因失败，退化为逐条 {@code save}（upsert），保证不丢数据。
     *
     * @return 实际写入条数
     */
    public int saveAll(List<CommentDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        try {
            mongoTemplate.insertAll(docs);
            return docs.size();
        } catch (Exception e) {
            log.warn("insertAll 失败，退化为逐条 save: size={} error={}", docs.size(), e.getMessage());
            int saved = 0;
            for (CommentDoc doc : docs) {
                try {
                    save(doc);
                    saved++;
                } catch (Exception ex) {
                    log.error("保存评论失败: id={} error={}", doc.getId(), ex.getMessage());
                }
            }
            return saved;
        }
    }

    /**
     * 按任务分页查询（入库时间倒序）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     */
    public List<CommentDoc> findByTaskId(String taskId, int page, int size) {
        Query query = new Query();
        query.addCriteria(Criteria.where("taskId").is(taskId));
        query.with(Sort.by(Sort.Direction.DESC, "insertTime"));
        query.skip((long) (Math.max(page, 1) - 1) * size);
        query.limit(size);
        return mongoTemplate.find(query, CommentDoc.class);
    }

    /**
     * 按任务 + 数据类型分页查询。
     */
    public List<CommentDoc> findByTaskIdAndDataType(String taskId, String dataType, int page, int size) {
        Query query = new Query();
        query.addCriteria(Criteria.where("taskId").is(taskId).and("dataType").is(dataType));
        query.with(Sort.by(Sort.Direction.DESC, "insertTime"));
        query.skip((long) (Math.max(page, 1) - 1) * size);
        query.limit(size);
        return mongoTemplate.find(query, CommentDoc.class);
    }

    /**
     * 统计任务下的结果条数。
     */
    public long countByTaskId(String taskId) {
        return mongoTemplate.count(new Query(Criteria.where("taskId").is(taskId)), CommentDoc.class);
    }

    /**
     * 统计任务下某数据类型的条数。
     */
    public long countByTaskIdAndDataType(String taskId, String dataType) {
        return mongoTemplate.count(
                new Query(Criteria.where("taskId").is(taskId).and("dataType").is(dataType)),
                CommentDoc.class);
    }

    /**
     * 统计子任务下的结果条数（用于运行中任务的进度展示）。
     */
    public long countBySubTaskId(String subTaskId) {
        return mongoTemplate.count(new Query(Criteria.where("subTaskId").is(subTaskId)), CommentDoc.class);
    }

    /**
     * 按任务流式读取（导出/大结果集时避免一次性载入内存）。
     */
    public Stream<CommentDoc> streamByTaskId(String taskId) {
        Query query = new Query();
        query.addCriteria(Criteria.where("taskId").is(taskId));
        query.with(Sort.by(Sort.Direction.DESC, "insertTime"));
        return mongoTemplate.stream(query, CommentDoc.class);
    }

    /**
     * 删除某任务的全部结果。
     */
    public long deleteByTaskId(String taskId) {
        return mongoTemplate.remove(new Query(Criteria.where("taskId").is(taskId)), CommentDoc.class)
                .getDeletedCount();
    }
}
