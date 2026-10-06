package com.sysj.collector.model;

import com.bewilder.parser.CommonParser;
import com.bewilder.parser.TimeParser;
import com.bewilder.tools.CommonTools;
import com.bewilder.weibo.WeiboTool;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
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
    // 社区类平台解析入口（荣耀/华为/OPPO/vivo/小米/贴吧，对照 auto-task-web 的 CommentsFactory）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 从百度贴吧楼层/楼中楼 JSON 构建 Comment（对照 CommentsFactory.tieba）。
     *
     * <p>itemStr 为楼层（page_pc 接口 post_list 项，author_id 需按 user_list 反查）
     * 或楼中楼（floor 接口 subpost_list 项，author 内嵌）；authorStr 为楼层项对应的
     * user_list 用户 JSON（楼中楼传 null）。
     * content 为 [{type,text}] 数组，经 $.content.text 逗号拼接（表情为 image_emoticonN 码）。
     */
    public static Comment buildFromTieba(String itemStr, String authorStr, String parentCommentId) {
        boolean hasAuthorEntry = StringUtils.isNotBlank(authorStr);
        String nameShow = hasAuthorEntry ? CommonParser.getJsonPathOne(authorStr, "$.name_show")
                : CommonParser.getJsonPathOne(itemStr, "$.author.name_show");
        if (StringUtils.isBlank(nameShow)) {
            nameShow = hasAuthorEntry ? CommonParser.getJsonPathOne(authorStr, "$.name")
                    : CommonParser.getJsonPathOne(itemStr, "$.author.name");
        }
        Date date = new Date(CommonTools.stringToLong(CommonParser.getJsonPathOne(itemStr, "$.time")) * 1000L);
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(itemStr, "$.id"))
                .uid(hasAuthorEntry ? CommonParser.getJsonPathOne(authorStr, "$.id")
                        : CommonParser.getJsonPathOne(itemStr, "$.author.id"))
                .userName(nameShow)
                .time(TimeParser.dateFormatString(date))
                .text(CommonParser.getJsonPathManyString(itemStr, "$.content.text"))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(itemStr, "$.agree.agree_num")))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(itemStr, "$.sub_post_number")))
                .ipLocation(hasAuthorEntry ? CommonParser.getJsonPathOne(authorStr, "$.ip_address") : null)
                .parentCommentId(parentCommentId)
                .insertTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 从华为社区（vmall 俱乐部）评论 JSON 构建 Comment（对照 CommentsFactory.huaweiBbs）。
     * 评论与回复共用同一 JSON 结构，回复的 parentCommentId 由接口返回。
     */
    public static Comment buildFromHuaweiBbs(String commentStr) {
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(commentStr, "$.commentId"))
                .uid(CommonParser.getJsonPathOne(commentStr, "$.authorInfo.userId"))
                .userName(CommonParser.getJsonPathOne(commentStr, "$.authorInfo.nickName"))
                .time(TimeParser.dateFormatString(new Date(CommonTools.stringToLong(
                        CommonParser.getJsonPathOne(commentStr, "$.createTime")))))
                .text(CommonParser.getJsonPathOne(commentStr, "$.content"))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.likes")))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.replies")))
                .parentCommentId(CommonParser.getJsonPathOne(commentStr, "$.parentCommentId"))
                .ipLocation(CommonParser.getJsonPathOne(commentStr, "$.ipLocation"))
                .insertTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 从 OPPO 社区楼层/楼中楼 JSON 构建 Comment（对照 CommentsFactory.oppoBbs）。
     * 楼层行（post/list）与楼中楼（commentList/comment/list）字段名不同，
     * 按是否含 fusername 区分：楼层 commentId=$.pid、楼中楼 commentId=$.id 且 parentCommentId=$.pid。
     */
    public static Comment buildFromOppoBbs(String commentStr) {
        String inlineUserName = CommonParser.getJsonPathOne(commentStr, "$.fusername");
        boolean inline = StringUtils.isNotBlank(inlineUserName);
        Date date = TimeParser.stringFormatDate(CommonParser.getJsonPathOne(commentStr, "$.dateline"));
        Comment.CommentBuilder builder = Comment.builder()
                .commentId(CommonParser.getJsonPathOne(commentStr, inline ? "$.id" : "$.pid"))
                .uid(CommonParser.getJsonPathOne(commentStr, inline ? "$.fuid" : "$.uid"))
                .userName(inline ? inlineUserName : CommonParser.getJsonPathOne(commentStr, "$.author.nickname"))
                .time(date == null ? null : TimeParser.dateFormatString(date))
                .text(CommonParser.getJsonPathOne(commentStr, "$.content"))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr,
                        inline ? "$.commentPraiseCount" : "$.praise")))
                .ipLocation(CommonParser.getJsonPathOne(commentStr, "$.ipLocation"))
                .source(CommonParser.getJsonPathOne(commentStr, "$.source"))
                .insertTime(System.currentTimeMillis());
        if (inline) {
            builder.parentCommentId(CommonParser.getJsonPathOne(commentStr, "$.pid"));
        } else {
            builder.replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.commentTotal")));
        }
        return builder.build();
    }

    /**
     * 从 vivo 社区单条评论 JSON 构建 Comment（对照 CommentsFactory.vivoBbs）。
     * 注意 parentCommentId 取 $.commentId（回复 JSON 内该字段指所属评论；主评论无此字段为 null）。
     */
    public static Comment buildFromVivoBbs(String commentStr) {
        long timeLong = CommonTools.stringToLong(CommonParser.getJsonPathOne(commentStr, "$.createTime"));
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(commentStr, "$.id"))
                .uid(CommonParser.getJsonPathOne(commentStr, "$.openId"))
                .userName(CommonParser.getJsonPathOne(commentStr, "$.userName"))
                .time(TimeParser.dateFormatString(new Date(timeLong)))
                .text(CommonParser.getJsonPathOne(commentStr, "$.text"))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.likeNum")))
                .parentCommentId(CommonParser.getJsonPathOne(commentStr, "$.commentId"))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.replyNum")))
                .ipLocation(CommonParser.getJsonPathOne(commentStr, "$.ipLocation"))
                .source(CommonParser.getJsonPathOne(commentStr, "$.model"))
                .insertTime(System.currentTimeMillis())
                .build();
    }

    /** vivo 顶层评论 + topReplyDtos 预览回复展平（对照 CommentsFactory.vivoBbsWithReplies）。 */
    public static List<Comment> buildFromVivoBbsWithReplies(String commentStr) {
        List<String> replyStrList = CommonParser.getJsonPathMany(commentStr, "$.topReplyDtos");
        List<Comment> comments = new ArrayList<>();
        Comment main = buildFromVivoBbs(commentStr);
        comments.add(main);
        if (replyStrList != null) {
            for (String replyStr : replyStrList) {
                comments.add(buildFromVivoBbs(replyStr));
            }
        }
        return comments;
    }

    /**
     * 从荣耀社区（club.honor.com，Discuz）字段 JSON 构建 Comment（对照 CommentsFactory.honorBbs）。
     * 输入是爬虫 XPath 解析后的字段 JSON（时间保留原始相对格式，经 TimeParser 归一化）。
     */
    public static Comment buildFromHonorBbs(String commentStr) {
        Date date = TimeParser.stringFormatDate(CommonParser.getJsonPathOne(commentStr, "$.time"));
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(commentStr, "$.commentId"))
                .uid(CommonParser.getJsonPathOne(commentStr, "$.uid"))
                .userName(CommonParser.getJsonPathOne(commentStr, "$.userName"))
                .time(date == null ? null : TimeParser.dateFormatString(date))
                .text(CommonParser.getJsonPathOne(commentStr, "$.text"))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.likeCount")))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.replyCount")))
                .parentCommentId(CommonParser.getJsonPathOne(commentStr, "$.parentCommentId"))
                .ipLocation(CommonParser.getJsonPathOne(commentStr, "$.ipLocation"))
                .source(CommonParser.getJsonPathOne(commentStr, "$.source"))
                .insertTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 从小米社区（api.vip.miui.com）单条评论 JSON 构建 Comment（对照 CommentsFactory.xiaomiBbs），
     * 不含回复；浏览量进 extra.viewCount。
     */
    public static Comment buildFromXiaomiBbs(String commentStr, String parentId) {
        Date date = new Date(CommonTools.stringToLong(CommonParser.getJsonPathOne(commentStr, "$.createTime")));
        Map<String, Object> extra = new HashMap<>();
        extra.put("viewCount", CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.viewCount")));
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(commentStr, "$.commentId"))
                .insertTime(System.currentTimeMillis())
                .userName(CommonParser.getJsonPathOne(commentStr, "$.author.name"))
                .uid(CommonParser.getJsonPathOne(commentStr, "$.author.id"))
                .time(TimeParser.dateFormatString(date))
                .text(CommonParser.getJsonPathOne(commentStr, "$.textContent"))
                .ipLocation(CommonParser.getJsonPathOne(commentStr, "$.ipRegion"))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.likeCnt")))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.commentCnt")))
                .source(CommonParser.getJsonPathOne(commentStr, "$.deviceType"))
                .parentCommentId(parentId)
                .extra(extra)
                .build();
    }

    /** 小米社区评论 + $.reply 子回复展平（对照 CommentsFactory.xiaomiBbsWithReplies）。 */
    public static List<Comment> buildFromXiaomiBbsWithReplies(String commentStr, String parentId) {
        List<String> replyStrList = CommonParser.getJsonPathMany(commentStr, "$.reply");
        List<Comment> comments = new ArrayList<>();
        Comment main = buildFromXiaomiBbs(commentStr, parentId);
        comments.add(main);
        if (replyStrList != null) {
            for (String replyStr : replyStrList) {
                comments.add(buildFromXiaomiBbs(replyStr, main.getCommentId()));
            }
        }
        return comments;
    }

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
