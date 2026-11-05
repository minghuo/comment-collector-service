package com.sysj.collector.domain.document;

import com.sysj.collector.model.AccountHistoryItem;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

/**
 * 账号历史结果文档（集合 {@code account_history}）。
 *
 * <p><b>为什么独立集合而不是塞进 {@code comment}</b>：账号历史的一条记录是"作品"，
 * 评论的一条记录是"互动"；两者可查维度、导出格式、生命周期都不同（账号历史天然按
 * {@code account_id + publish_time} 排序与增量，评论按 {@code task_id + insert_time}）。
 * 混在一张表里会让两边的索引互相迁就，查询时还要额外过滤判别字段。
 *
 * <p><b>业务主键</b>：{@link #id} = {@code taskId + "-" + accountId + "-" + itemId}。
 * 同一任务下重复翻页/重试取到同一条作品时，Mongo 侧 {@code replaceOne(upsert)} 只保留一条，
 * 天然幂等 —— 与 {@code CommentDoc} 的主键口径保持一致（任务维度而不是作品维度）。
 *
 * <p><b>落库字段名</b>：应用启用 {@code SnakeCaseFieldNamingStrategy}，Java 字段在集合中为
 * 下划线式（{@code task_id}、{@code account_id}、{@code item_id}、{@code publish_time} …）；
 * 注解里的 {@code def} 字符串不经过命名策略，必须直接写落库名。
 */
@Data
@Document(collection = "account_history")
@CompoundIndexes({
        @CompoundIndex(name = "idx_task_insert", def = "{'task_id': 1, 'insert_time': -1}"),
        @CompoundIndex(name = "idx_task_account", def = "{'task_id': 1, 'account_id': 1}"),
        @CompoundIndex(name = "idx_task_item", def = "{'task_id': 1, 'item_id': 1}")
})
public class AccountHistoryDoc {

    /** 业务主键，见类注释。 */
    @Id
    private String id;

    /** 所属主任务 ID（分页查询的主维度）。 */
    private String taskId;

    /** 所属子任务 ID（可空，便于定位是哪个链接/续采轮次出的数据）。 */
    private String subTaskId;

    /** 平台编码：toutiao / douyin / xhs / weibo / bilibili。 */
    private String platformCode;

    /** 实际执行的供应商 key。 */
    private String providerKey;

    /** 账号标识（各平台口径不同）。 */
    private String accountId;

    /** 账号昵称。 */
    private String accountName;

    // ── 作品字段 ──────────────────────────────────────────────────────────

    /** 平台内作品唯一标识。 */
    private String itemId;

    /** 归一化作品类型：article / video / weitoutiao / unknown。 */
    private String itemType;

    private String title;

    private String summary;

    /** 作品详情页地址。 */
    private String url;

    private String coverUrl;

    /** 发布时间，epoch 毫秒。 */
    private Long publishTime;

    private Long readCount;

    private Long commentCount;

    private Long diggCount;

    private Long repostCount;

    /** 采集来源地址（账号主页链接）。 */
    private String fromUrl;

    /** 入库时间，epoch 毫秒。 */
    private long insertTime;

    /** 平台原始字段（未归一化部分）。 */
    private Map<String, Object> extra;

    /**
     * 由条目构造文档。
     *
     * @param platformCode 平台码
     * @param providerKey  实际供应商 key
     * @param taskId       主任务 ID（落库主键的一部分，不能为空）
     * @param subTaskId    子任务 ID，可空
     * @param fromUrl      采集来源（账号主页链接），可空
     * @param item         条目
     */
    public static AccountHistoryDoc from(String platformCode, String providerKey, String taskId,
                                         String subTaskId, String fromUrl, AccountHistoryItem item) {
        AccountHistoryDoc doc = new AccountHistoryDoc();
        doc.setTaskId(taskId);
        doc.setSubTaskId(subTaskId);
        doc.setPlatformCode(platformCode);
        doc.setProviderKey(providerKey);
        doc.setAccountId(item.getAccountId());
        doc.setAccountName(item.getAccountName());
        doc.setItemId(item.getItemId());
        doc.setItemType(item.getItemType());
        doc.setTitle(item.getTitle());
        doc.setSummary(item.getSummary());
        doc.setUrl(item.getUrl());
        doc.setCoverUrl(item.getCoverUrl());
        doc.setPublishTime(item.getPublishTime());
        doc.setReadCount(item.getReadCount());
        doc.setCommentCount(item.getCommentCount());
        doc.setDiggCount(item.getDiggCount());
        doc.setRepostCount(item.getRepostCount());
        doc.setFromUrl(fromUrl);
        doc.setInsertTime(item.getInsertTime() > 0 ? item.getInsertTime() : System.currentTimeMillis());
        doc.setExtra(item.getExtra());
        doc.setId(buildId(taskId, item.getAccountId(), item.getItemId(),
                item.getTitle(), item.getPublishTime()));
        return doc;
    }

    /**
     * 业务主键。{@code itemId} 缺失时退化为"账号 + 标题 + 发布时间"的稳定摘要（十六进制），
     * 保证同一作品重复入库仍能命中同一条（供应商偶发缺 id 时不会重复堆数据）。
     */
    public static String buildId(String taskId, String accountId, String itemId,
                                 String title, Long publishTime) {
        String suffix = itemId;
        if (suffix == null || suffix.isBlank()) {
            suffix = "noid-" + Integer.toHexString(
                    java.util.Objects.hash(accountId, title, publishTime));
        }
        return taskId + "-" + (accountId == null ? "unknown" : accountId) + "-" + suffix;
    }
}
