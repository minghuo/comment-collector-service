package com.sysj.collector.model;

import com.bewilder.parser.CommonParser;
import com.bewilder.parser.TimeParser;
import com.bewilder.tools.CommonTools;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 账号历史条目：某个平台账号发布过的一条作品（文章 / 视频 / 微头条 / 动态）。
 *
 * <p><b>与 {@link Comment} 的区别</b>：评论是"某条作品下的互动"，账号历史是
 * "某个账号发过什么"，前者按作品聚合、后者按账号聚合。两者共用
 * {@link CommonEntity} 作为供应商返回壳（分页字段 {@code haseMore}/{@code nextUrl} 语义一致），
 * 但条目字段差异很大，因此独立建模而不是复用 {@code Comment}。
 *
 * <p><b>字段口径</b>：
 * <ul>
 *   <li>{@code itemId} —— 平台内的作品唯一标识（头条为 {@code group_id}，缺失时取 {@code item_id}）；</li>
 *   <li>{@code accountId} —— 作品归属账号在该平台的标识；</li>
 *   <li>{@code publishTime} —— 作品发布时间，epoch 毫秒（平台给秒则统一乘 1000）；</li>
 *   <li>{@code itemType} —— 归一化后的作品类型：{@code article} / {@code video} / {@code weitoutiao} / {@code unknown}；</li>
 *   <li>{@code extra} —— 平台原始字段中未归一的部分，键名保持平台原样，便于后续加字段而不改表结构。</li>
 * </ul>
 *
 * <p>解析方法一律做成 {@code static} 纯函数（不发起 HTTP），单测直接喂平台 JSON 片段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountHistoryItem {

    /** 平台内作品唯一标识。 */
    private String itemId;

    /** 作品归属账号在该平台的标识。 */
    private String accountId;

    /** 账号昵称（平台返回则带上，便于结果可读）。 */
    private String accountName;

    /** 作品标题；视频/微头条无标题时为空。 */
    private String title;

    /** 作品摘要/正文节选。 */
    private String summary;

    /** 作品详情页地址。 */
    private String url;

    /** 封面图地址。 */
    private String coverUrl;

    /** 归一化作品类型：article / video / weitoutiao / unknown。 */
    private String itemType;

    /** 发布时间，epoch 毫秒；平台未返回时为 null。 */
    private Long publishTime;

    /** 阅读数（平台不返回则为 null）。 */
    private Long readCount;

    /** 评论数。 */
    private Long commentCount;

    /** 点赞数。 */
    private Long diggCount;

    /** 转发/分享数。 */
    private Long repostCount;

    /** 入库时间，epoch 毫秒。 */
    private long insertTime;

    /** 平台原始字段（未归一化部分）。 */
    private java.util.Map<String, Object> extra;

    /**
     * 由小红书用户作品流（{@code /api/sns/web/v1/user_posted}）的单条 JSON 构造条目。
     *
     * <p>{@code user_posted} 的 {@code $.data.notes[]} 每项是
     * {@code {id, note_card:{...}, xsec_token}}，正文、封面、作者、互动数都在
     * {@code note_card} 下；部分版本也会把字段平铺到顶层，因此按"卡片优先、顶层回退"取值。
     *
     * <p>{@code xsec_token} 是详情页链接的必需参数（不带会 404/空数据），单独放
     * {@link #extra} 的 {@code xsecToken}，由上层拼链接时决定是否附加。
     */
    public static AccountHistoryItem buildFromXhs(String dataStr) {
        String itemId = firstNonBlank(dataStr, "$.id", "$.note_id", "$.note_card.note_id");
        return AccountHistoryItem.builder()
                .itemId(itemId)
                .accountId(firstNonBlank(dataStr, "$.note_card.user.user_id", "$.user.user_id"))
                .accountName(firstNonBlank(dataStr, "$.note_card.user.nickname", "$.user.nickname"))
                .title(firstNonBlank(dataStr, "$.note_card.display_title", "$.display_title",
                        "$.note_card.title", "$.title"))
                .summary(firstNonBlank(dataStr, "$.note_card.desc", "$.desc",
                        "$.note_card.summary", "$.summary"))
                .url(itemId == null ? null : "https://www.xiaohongshu.com/explore/" + itemId)
                .coverUrl(firstNonBlank(dataStr, "$.note_card.cover.url_default", "$.note_card.cover.url",
                        "$.cover.url_default", "$.cover.url"))
                .itemType(xhsItemType(dataStr))
                .publishTime(epochMillis(firstNonBlank(dataStr, "$.note_card.time", "$.time",
                        "$.note_card.create_time")))
                .commentCount(longOrNull(firstNonBlank(dataStr, "$.note_card.interact_info.comment_count")))
                .diggCount(longOrNull(firstNonBlank(dataStr, "$.note_card.interact_info.liked_count")))
                .repostCount(longOrNull(firstNonBlank(dataStr, "$.note_card.interact_info.share_count")))
                .insertTime(System.currentTimeMillis())
                .extra(xhsExtra(dataStr))
                .build();
    }

    /** {@code note_card.type=video} 归一为 video，其余（normal/图文）归一为 note。 */
    static String xhsItemType(String dataStr) {
        String type = firstNonBlank(dataStr, "$.note_card.type", "$.type");
        return type != null && type.toLowerCase().contains("video") ? "video" : "note";
    }

    private static java.util.Map<String, Object> xhsExtra(String dataStr) {
        String xsecToken = firstNonBlank(dataStr, "$.xsec_token", "$.note_card.xsec_token");
        if (xsecToken == null) {
            return null;
        }
        java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
        extra.put("xsecToken", xsecToken);
        return extra;
    }

    /**
     * 由头条用户作品流的一屏 JSON 元素构造条目。
     *
     * <p>头条不同入口返回的字段名不一致（PC 用户流用 {@code group_id/publish_time}，
     * 部分接口用 {@code item_id/create_time}，微头条还可能是 {@code thread_id}），
     * 因此按"优先精确、逐级回退"取值，取不到就留 null —— 宁缺字段也不要造值。
     *
     * @param dataStr 单个作品的 JSON 片段
     * @return 条目；{@code dataStr} 无任何标识时仍返回对象（itemId 为 null），由上层决定是否丢弃
     */
    public static AccountHistoryItem buildFromToutiao(String dataStr) {
        String itemId = firstNonBlank(dataStr, "$.group_id", "$.item_id", "$.thread_id");
        String articleUrl = firstNonBlank(dataStr, "$.article_url", "$.display_url", "$.url");
        String coverUrl = firstNonBlank(dataStr, "$.image_url", "$.large_image.url",
                "$.middle_image.url", "$.image_list[0].url");
        String accountName = firstNonBlank(dataStr, "$.media_user.screen_name", "$.source");
        String accountId = firstNonBlank(dataStr, "$.media_user.user_id", "$.media_user.id",
                "$.user_id");
        Long publishTime = epochMillis(firstNonBlank(dataStr, "$.publish_time", "$.create_time",
                "$.behot_time", "$.datetime"));
        return AccountHistoryItem.builder()
                .itemId(itemId)
                .accountId(accountId)
                .accountName(accountName)
                .title(firstNonBlank(dataStr, "$.title", "$.content"))
                .summary(firstNonBlank(dataStr, "$.abstract", "$.content", "$.description"))
                .url(articleUrl)
                .coverUrl(coverUrl)
                .itemType(toutiaoItemType(dataStr, articleUrl))
                .publishTime(publishTime)
                .readCount(longOrNull(firstNonBlank(dataStr, "$.read_count", "$.go_detail_count")))
                .commentCount(longOrNull(firstNonBlank(dataStr, "$.comment_count")))
                .diggCount(longOrNull(firstNonBlank(dataStr, "$.digg_count")))
                .repostCount(longOrNull(firstNonBlank(dataStr, "$.repin_count", "$.forward_count")))
                .insertTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 归一化头条作品类型：有 {@code article_url} 视为文章；{@code cell_type=video} 或
     * 存在 {@code video_duration} 视为视频；{@code thread_id} 或 {@code content} 视为微头条。
     */
    static String toutiaoItemType(String dataStr, String articleUrl) {
        String cellType = firstNonBlank(dataStr, "$.cell_type", "$.item_type");
        if (cellType != null && cellType.toLowerCase().contains("video")) {
            return "video";
        }
        if (firstNonBlank(dataStr, "$.video_duration", "$.video_id") != null) {
            return "video";
        }
        if (articleUrl != null && articleUrl.contains("/article/")) {
            return "article";
        }
        if (firstNonBlank(dataStr, "$.thread_id") != null) {
            return "weitoutiao";
        }
        if (firstNonBlank(dataStr, "$.content") != null) {
            return "weitoutiao";
        }
        return articleUrl != null ? "article" : "unknown";
    }

    /**
     * 时间归一化：头条多数入口给秒级 {@code publish_time}，少数给毫秒。小于 1e12 视为秒。
     */
    static Long epochMillis(String raw) {
        Long value = CommonTools.stringToLong(raw);
        if (value == null || value <= 0) {
            return null;
        }
        return value < 1_000_000_000_000L ? value * 1000L : value;
    }

    /** 时间戳格式化（结果导出时用），{@code null} 安全。 */
    public String publishTimeText() {
        return publishTime == null ? null : TimeParser.dateFormatString(new Date(publishTime));
    }

    private static Long longOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Integer value = CommonTools.stringToInteger(raw);
        return value == null ? null : value.longValue();
    }

    /** 依次尝试多个 JSONPath，返回第一个非空白值。 */
    private static String firstNonBlank(String dataStr, String... paths) {
        for (String path : paths) {
            String value = CommonParser.getJsonPathOne(dataStr, path);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
