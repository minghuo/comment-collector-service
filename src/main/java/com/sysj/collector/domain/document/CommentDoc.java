package com.sysj.collector.domain.document;

import com.sysj.collector.model.Comment;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

/**
 * 评论采集结果文档（统一集合 {@code comment}）。
 *
 * <p><b>为什么是单集合而不是一类型一集合</b>：本服务是<b>中间件</b>，同一套任务/分页/去重/清理逻辑要覆盖
 * 微博评论、微博转发、微信评论、视频号评论、B站/抖音/小红书/头条评论等全部数据类型。
 * 用 {@link #dataType} 做判别字段后，分页查询、计数、索引与归档都只需一套；
 * auto-task-web 采用"一类型一集合"是因为它面向按类型导出 Excel 的后台场景，本服务不需要。
 *
 * <p><b>业务主键</b>：{@link #id} = {@code taskId + "-" + (mid|fromUrlHash) + "-" + commentId}，
 * 保证同一任务下同一条评论只落一条（参考 auto-task-web 各 Processor 的
 * {@code comment.setId(taskId + "-" + mid + "-" + commentId)} 写法）。
 *
 * <p><b>落库字段名</b>：应用启用了 {@code SnakeCaseFieldNamingStrategy}，
 * 因此下列 Java 字段在 MongoDB 中为下划线式（{@code task_id}、{@code data_type}、{@code comment_id} …）。
 */
@Data
@Document(collection = "comment")
@CompoundIndexes({
        // def 字符串不经过命名策略，必须写落库名
        @CompoundIndex(name = "idx_task_insert", def = "{'task_id': 1, 'insert_time': -1}"),
        @CompoundIndex(name = "idx_task_datatype", def = "{'task_id': 1, 'data_type': 1}")
})
public class CommentDoc {

    /** 业务主键，见类注释。 */
    @Id
    private String id;

    /** 所属主任务 ID（分页查询的主维度）。 */
    private String taskId;

    /** 所属子任务 ID（可空，便于定位是哪个链接出的数据）。 */
    private String subTaskId;

    /**
     * 数据类型，取值见 {@code CommentDataType}：
     * {@code weibo_comment} / {@code weibo_repost} / {@code wechat_comment} /
     * {@code wechat_video_comment} / {@code bilibili_comment} / {@code douyin_comment} /
     * {@code xhs_comment} / {@code toutiao_comment}。
     */
    private String dataType;

    /** 平台编码：weibo / wechat / wechat_video / bilibili / douyin / xhs / toutiao。 */
    private String platformCode;

    /** 实际执行的供应商 key。 */
    private String providerKey;

    /** 采集来源地址（原帖/原视频/原文链接）。 */
    private String fromUrl;

    /** 内容 ID（微博 mid、视频 mid 等）。 */
    private String mid;

    // ── 评论字段 ──────────────────────────────────────────────────────────

    private String commentId;
    private String parentCommentId;
    private String uid;
    private String userName;
    private String text;
    private String time;
    private Integer likeCount;
    private Integer replyCount;
    private Integer repostCount;
    private String source;
    private String ipLocation;

    // ── 微博用户扩展字段 ──────────────────────────────────────────────────

    private long followCount;
    private long fansCount;
    private int statusCount;
    private String gender;
    private String location;
    private String description;
    private String verifyType;
    private String verifyInfo;
    private String bigfans;

    // ── 微博转发扩展字段 ──────────────────────────────────────────────────

    private String repostUrl;

    // ── 通用 ──────────────────────────────────────────────────────────────

    /** 入库时间戳（毫秒）。 */
    private long insertTime;

    /** 平台相关补充字段。 */
    private Map<String, Object> extra;

    /**
     * 由传输对象构建落库文档。
     *
     * @param dataType     数据类型（见 {@link #dataType}）
     * @param platformCode 平台编码
     * @param providerKey  实际执行的供应商 key
     * @param taskId       主任务 ID（为 null 时不落库，由调用方保证）
     * @param subTaskId    子任务 ID（可空）
     * @param fromUrl      采集来源地址
     * @param c            供应商返回的评论
     */
    public static CommentDoc from(String dataType, String platformCode, String providerKey,
                                  String taskId, String subTaskId, String fromUrl, Comment c) {
        CommentDoc d = new CommentDoc();
        d.setId(buildId(taskId, fromUrl, c));
        d.setTaskId(taskId);
        d.setSubTaskId(subTaskId);
        d.setDataType(dataType);
        d.setPlatformCode(platformCode);
        d.setProviderKey(providerKey);
        d.setFromUrl(fromUrl);
        d.setMid(c.getMid());
        d.setCommentId(c.getCommentId());
        d.setParentCommentId(c.getParentCommentId());
        d.setUid(c.getUid());
        d.setUserName(c.getUserName());
        d.setText(c.getText());
        d.setTime(c.getTime());
        d.setLikeCount(c.getLikeCount());
        d.setReplyCount(c.getReplyCount());
        d.setRepostCount(c.getRepostCount());
        d.setSource(c.getSource());
        d.setIpLocation(c.getIpLocation());
        d.setFollowCount(c.getFollowCount());
        d.setFansCount(c.getFansCount());
        d.setStatusCount(c.getStatusCount());
        d.setGender(c.getGender());
        d.setLocation(c.getLocation());
        d.setDescription(c.getDescription());
        d.setVerifyType(c.getVerifyType());
        d.setVerifyInfo(c.getVerifyInfo());
        d.setBigfans(c.getBigfans());
        d.setRepostUrl(c.getRepostUrl());
        d.setInsertTime(c.getInsertTime() > 0 ? c.getInsertTime() : System.currentTimeMillis());
        d.setExtra(c.getExtra());
        return d;
    }

    /**
     * 业务主键：{@code taskId-mid-commentId}；mid 为空时退化为 {@code taskId-commentId}；
     * commentId 也为空时用 {@code nanoTime} 兜底（避免整批数据被同一条覆盖）。
     *
     * <p>与 auto-task-web 各 Processor 的写法保持一致。
     */
    private static String buildId(String taskId, String fromUrl, Comment c) {
        String biz = c.getMid() != null && !c.getMid().isBlank() ? c.getMid() : fromUrl;
        String cid = c.getCommentId();
        StringBuilder sb = new StringBuilder();
        sb.append(taskId == null ? "unknown" : taskId).append('-');
        if (biz != null && !biz.isBlank()) {
            sb.append(biz).append('-');
        }
        sb.append(cid != null && !cid.isBlank() ? cid : String.valueOf(System.nanoTime()));
        return sb.toString();
    }
}
