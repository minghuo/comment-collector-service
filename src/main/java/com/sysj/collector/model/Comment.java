package com.sysj.collector.model;

import com.bewilder.parser.CommonParser;
import com.bewilder.parser.TimeParser;
import com.bewilder.tools.CommonTools;
import com.bewilder.weibo.WeiboTool;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.Map;

/**
 * 通用评论模型（供应商 → 框架的传输对象，也是同步接口的返回体）。
 *
 * <p>字段以 <b>auto-task-web</b> 各平台评论 POJO 的并集为准：
 * 微博评论（followCount/fansCount/statusCount/gender/location/description/verifyType/verifyInfo/bigfans）、
 * 微博转发（repostUrl/repostCount）、微信与视频号评论（ipLocation）等字段统一收敛到本类，
 * 平台无关的补充信息放 {@link #extra}。落库时由 {@code CommentDoc} 转存，见 §5.5。
 *
 * <p>字段命名与落库名（下划线式）的对应由 {@code CommentDoc} 处理，本类只做传输。
 */
@Data
@Builder
public class Comment {

    /** 评论/转发唯一 ID（平台侧）。 */
    private String commentId;

    /** 父评论 ID；主评论为 null。 */
    private String parentCommentId;

    /** 内容 ID（微博 mid / 视频 mid / 文章 URL 等），落库时用于业务主键去重。 */
    private String mid;

    /** 评论者 ID。 */
    private String uid;

    /** 评论者昵称。 */
    private String userName;

    /** 评论正文。 */
    private String text;

    /** 评论时间（格式化后的字符串，如 2026-09-23 12:00:00）。 */
    private String time;

    /** 点赞数。 */
    private Integer likeCount;

    /** 回复数 / 评论数。 */
    private Integer replyCount;

    /** 转发数（微博转发专用）。 */
    private Integer repostCount;

    /** 来源（微博评论的 source；微信/头条的 post_location）。 */
    private String source;

    /** IP 属地。 */
    private String ipLocation;

    // ── 微博评论特有（auto-task-web 的 WeiboComment） ──────────────────────

    /** 关注数。 */
    private long followCount;

    /** 粉丝数。 */
    private long fansCount;

    /** 发文数。 */
    private int statusCount;

    /** 性别。 */
    private String gender;

    /** 用户位置。 */
    private String location;

    /** 用户描述。 */
    private String description;

    /** 用户认证类型。 */
    private String verifyType;

    /** 用户认证信息。 */
    private String verifyInfo;

    /** 是否铁粉：1 是 / 0 否。 */
    private String bigfans;

    // ── 微博转发特有（auto-task-web 的 WeiboRepost） ──────────────────────

    /** 转发者微博地址。 */
    private String repostUrl;

    // ── 通用 ──────────────────────────────────────────────────────────────

    /** 入库时间戳。 */
    private long insertTime;

    /** 平台相关补充字段（不落固定列时放这里）。 */
    private Map<String, Object> extra;

    // ──────────────────────────────────────────────────────────────────────
    // 解析入口
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 从 Golaxy（中科天玑）评论接口返回的单条 JSON 构建 Comment。
     *
     * <p>字段映射与 auto-task-web 的
     * {@code BiliComment.buildFromGolaxy} / {@code DyComment.buildFromGolaxy} / {@code XhsComment.buildFromGolaxy}
     * 完全一致（B站 / 抖音 / 小红书 三个平台共用同一套字段）。
     *
     * <p>注意 {@code publish_time} 是毫秒时间戳，与微信接口的秒级时间戳不同，勿做 ×1000。
     */
    public static Comment buildFromGolaxy(String dataStr) {
        long timeLong = CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.publish_time"));
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(dataStr, "$.comment_id"))
                .parentCommentId(CommonParser.getJsonPathOne(dataStr, "$.root_comment_id"))
                .uid(CommonParser.getJsonPathOne(dataStr, "$.author_id"))
                .userName(CommonParser.getJsonPathOne(dataStr, "$.author_name"))
                .time(timeLong > 0 ? TimeParser.dateFormatString(new Date(timeLong)) : null)
                .text(CommonParser.getJsonPathOne(dataStr, "$.content"))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.likes_count")))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.comments_count")))
                .ipLocation(CommonParser.getJsonPathOne(dataStr, "$.post_location"))
                .insertTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 从微博 Golaxy（中科天玑）评论接口返回的单条 JSON 构建 Comment。
     *
     * <p>字段映射与 auto-task-web 的 {@code WeiboComment.builderFromGolaxy} 一致
     * （微博的 Golaxy 返回比通用版多出用户扩展字段，故单独一个方法）。
     */
    public static Comment buildFromWeiboGolaxy(String dataStr) {
        long timeLong = CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.publish_time"));
        Integer verifyType = CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.verified_type"));
        Integer verifyTypeExt = CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.verified_type_ext"));
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(dataStr, "$.comment_id"))
                .parentCommentId(CommonParser.getJsonPathOne(dataStr, "$.root_comment_id"))
                .uid(CommonParser.getJsonPathOne(dataStr, "$.author_id"))
                .userName(CommonParser.getJsonPathOne(dataStr, "$.author_name"))
                .followCount(CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.friends_count")))
                .fansCount(CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.followers_count")))
                .statusCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.statuses_count")))
                .gender(WeiboTool.changeSex(CommonParser.getJsonPathOne(dataStr, "$.gender")))
                .location(CommonParser.getJsonPathOne(dataStr, "$.location"))
                .description(CommonParser.getJsonPathOne(dataStr, "$.description"))
                .verifyType(WeiboTool.changeVtype(verifyType))
                .verifyInfo(CommonParser.getJsonPathOne(dataStr, "$.verified_reason"))
                .text(CommonParser.getJsonPathOne(dataStr, "$.content"))
                .time(timeLong > 0 ? TimeParser.dateFormatString(new Date(timeLong)) : null)
                .source(CommonParser.getJsonPathOne(dataStr, "$.post_location"))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.comments_count")))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.likes_count")))
                .insertTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 从微博本地接口（weibo.com/ajax/statuses/buildComments）返回的单条 JSON 构建 Comment。
     *
     * <p>字段映射与 auto-task-web 的 {@code WeiboComment.builderLocal} 一致。
     *
     * @param dataStr         单条评论 JSON
     * @param parentCommentId 父评论 ID（采二级评论时传入，主评论传 null）
     */
    public static Comment buildFromWeiboLocal(String dataStr, String parentCommentId) {
        Integer verifyType = CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.user.verified_type"));
        Integer verifyTypeExt = CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.user.verified_type_ext"));
        String fansName = CommonParser.getJsonPathOne(dataStr, "$.user.fansIcon.name");
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(dataStr, "$.id"))
                .parentCommentId(parentCommentId)
                .uid(CommonParser.getJsonPathOne(dataStr, "$.user.id"))
                .userName(CommonParser.getJsonPathOne(dataStr, "$.user.screen_name"))
                .followCount(CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.user.friends_count")))
                .fansCount(CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.user.followers_count")))
                .statusCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.user.statuses_count")))
                .gender(WeiboTool.changeSex(CommonParser.getJsonPathOne(dataStr, "$.user.gender")))
                .location(CommonParser.getJsonPathOne(dataStr, "$.user.location"))
                .description(CommonParser.getJsonPathOne(dataStr, "$.user.description"))
                .verifyType(WeiboTool.authorType(verifyType, verifyTypeExt))
                .verifyInfo(CommonParser.getJsonPathOne(dataStr, "$.user.verified_reason"))
                .bigfans(StringUtils.isNotBlank(fansName) && fansName.contains("loyal_fans") ? "1" : "0")
                .text(CommonParser.getJsonPathOne(dataStr, "$.text_raw"))
                .time(TimeParser.dateFormatString(new Date(CommonTools.stringToLong(
                        CommonParser.getJsonPathOne(dataStr, "$.created_at")))))
                .source(CommonParser.getJsonPathOne(dataStr, "$.source"))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.total_number")))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.like_counts")))
                .insertTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 从微博转发接口（weibo.com/ajax/statuses/repostTimeline）返回的单条 JSON 构建 Comment。
     *
     * <p>字段映射与 auto-task-web 的 {@code WeiboRepost.build} 一致。
     */
    public static Comment buildFromWeiboRepost(String dataStr) {
        String uid = CommonParser.getJsonPathOne(dataStr, "$.user.id");
        String mblogid = CommonParser.getJsonPathOne(dataStr, "$.mblogid");
        String sourceStr = CommonParser.getJsonPathOne(dataStr, "$.source");
        String ipLocation = CommonParser.getJsonPathOne(dataStr, "$.region_name");
        if (StringUtils.isNotBlank(ipLocation)) {
            ipLocation = ipLocation.replace("发布于 ", "");
        }
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(dataStr, "$.id"))
                .mid(CommonParser.getJsonPathOne(dataStr, "$.id"))
                .uid(uid)
                .userName(CommonParser.getJsonPathOne(dataStr, "$.user.screen_name"))
                .text(CommonParser.getJsonPathOne(dataStr, "$.text_raw"))
                .time(TimeParser.dateFormatString(new Date(CommonTools.stringToLong(
                        CommonParser.getJsonPathOne(dataStr, "$.created_at")))))
                .source(StringUtils.isNotBlank(sourceStr) ? CommonParser.getXpathOne(sourceStr, "//a") : null)
                .ipLocation(ipLocation)
                .repostUrl(StringUtils.isNotBlank(uid) && StringUtils.isNotBlank(mblogid)
                        ? "https://weibo.com/" + uid + "/" + mblogid : null)
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.attitudes_count")))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.comments_count")))
                .repostCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.reposts_count")))
                .insertTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 从微信公众号评论接口返回的单条 JSON 构建 Comment。
     *
     * <p>字段映射与 auto-task-web 的 {@code WechatComment.build} 一致。
     * 注意 {@code create_time} 是<b>秒级</b>时间戳，需要 ×1000。
     */
    public static Comment buildFromWechat(String dataStr, String parentCommentId) {
        long timeLong = CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.create_time")) * 1000L;
        return Comment.builder()
                // 注意：auto-task-web 此处把 user_name 放在 uid、nick_name 放在 userName，保持原样以对齐数据
                .commentId(CommonParser.getJsonPathOne(dataStr, "$.content_id"))
                .uid(CommonParser.getJsonPathOne(dataStr, "$.user_name"))
                .userName(CommonParser.getJsonPathOne(dataStr, "$.nick_name"))
                .time(timeLong > 0 ? TimeParser.dateFormatString(new Date(timeLong)) : null)
                .text(CommonParser.getJsonPathOne(dataStr, "$.content"))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.like_num")))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.reply_count")))
                .ipLocation(CommonParser.getJsonPathOne(dataStr, "$.location"))
                .parentCommentId(parentCommentId)
                .insertTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 从微信视频号评论接口返回的单条 JSON 构建 Comment。
     *
     * <p>字段映射与 auto-task-web 的 {@code WechatVideoComment.build} 一致。
     */
    public static Comment buildFromWechatVideo(String dataStr, String parentCommentId) {
        long timeLong = CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.create_time")) * 1000L;
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(dataStr, "$.comment_id"))
                .uid(CommonParser.getJsonPathOne(dataStr, "$.user_name"))
                .userName(CommonParser.getJsonPathOne(dataStr, "$.nickname"))
                .time(timeLong > 0 ? TimeParser.dateFormatString(new Date(timeLong)) : null)
                .text(CommonParser.getJsonPathOne(dataStr, "$.content"))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.like_count")))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.reply_count")))
                .ipLocation(CommonParser.getJsonPathOne(dataStr, "$.location"))
                .parentCommentId(parentCommentId)
                .insertTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 从今日头条评论接口返回的单条 JSON 构建 Comment。
     *
     * <p>字段映射与 auto-task-web 的 {@code TtComment.build} 一致。
     * {@code reply_count} 缺失时回退 {@code forward_count}
     * （原实现用 {@code "$.reply_count|$.forward_count"} 这种非标准 JSONPath，恒为空）。
     */
    public static Comment buildFromToutiao(String dataStr) {
        long timeLong = CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.create_time")) * 1000L;
        String replyCountStr = CommonParser.getJsonPathOne(dataStr, "$.reply_count");
        if (StringUtils.isBlank(replyCountStr)) {
            replyCountStr = CommonParser.getJsonPathOne(dataStr, "$.forward_count");
        }
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(dataStr, "$.id"))
                .uid(CommonParser.getJsonPathOne(dataStr, "$.user_id"))
                .userName(CommonParser.getJsonPathOne(dataStr, "$.user_name"))
                .time(timeLong > 0 ? TimeParser.dateFormatString(new Date(timeLong)) : null)
                .text(CommonParser.getJsonPathOne(dataStr, "$.text"))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.digg_count")))
                .parentCommentId(CommonParser.getJsonPathOne(dataStr, "$.root"))
                .replyCount(CommonTools.stringToInteger(replyCountStr))
                .ipLocation(CommonParser.getJsonPathOne(dataStr, "$.publish_loc_info"))
                .insertTime(System.currentTimeMillis())
                .build();
    }
}
