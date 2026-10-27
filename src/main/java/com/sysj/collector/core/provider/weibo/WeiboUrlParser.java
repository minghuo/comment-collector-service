package com.sysj.collector.core.provider.weibo;

import com.bewilder.weibo.WeiboTool;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微博链接解析工具（三个微博供应商共用）。
 *
 * <p>对照 {@code auto-task-web} 的 {@code WeiboCommentProcessor}：
 * mid 由 {@code WeiboTool.urlToMid} / {@code WeiboTool.videoUrlToMid} 解析，
 * uid 由正则从链接中提取（兼容 {@code /u/12345/xxx} 与 {@code /12345/xxx} 两种形式）。
 *
 * <p>2026-10-26 增补：视频（tv/show、video.weibo.com、m.weibo.cn/video）与头条文章
 * （ttarticle、card.weibo.com/article）两类链接的判定与 id 提取——视频 oid 与文章 id
 * 都不是微博 mid，须先判定类型再由供应商经对应接口换算（见 {@code WeiboLocalCrawlerProvider}）。
 * 注意 {@code WeiboTool.urlToMid} 会把 {@code card.weibo.com/article/m/show/id/} 的
 * 22 位文章 id 误当 mid 返回，文章判定必须放在 urlToMid 之前。
 */
final class WeiboUrlParser {

    private WeiboUrlParser() {
    }

    /** 匹配 https://weibo.com/u/12345/xxx 与 https://weibo.com/12345/xxx。 */
    private static final Pattern UID_PATTERN =
            Pattern.compile("(?:weibo\\.com|weibo\\.cn)/(?:u/)?(\\d+)");

    /** 视频 oid（1034:xxx 形式，tv/show 路径、video.weibo.com 的 fid 参数、m.weibo.cn/video 路径） */
    private static final Pattern VIDEO_OID_PATTERN =
            Pattern.compile("(?:tv/show/|m\\.weibo\\.cn/video/|[?&]fid=)(\\d{2,6}:\\d{10,20})");

    /** 文章 id：ttarticle 的 id/p query 参数、card.weibo.com 文章页的路径末段 */
    private static final Pattern ARTICLE_ID_QUERY_PATTERN =
            Pattern.compile("(?:[?&]id=|[?&]p=)(2\\d{7,25})");
    private static final Pattern ARTICLE_ID_PATH_PATTERN =
            Pattern.compile("card\\.weibo\\.com/article/(?:[a-z]/)?show/id/(\\d{8,25})");

    /** 文章链接：PC 端 ttarticle、手机端 ttarticle、card.weibo.com 文章页 */
    static boolean isArticleUrl(String url) {
        return StringUtils.isNotBlank(url)
                && (url.contains("ttarticle") || url.contains("card.weibo.com/article"));
    }

    /** 提取文章 id（22 位左右的长 id，不是微博 mid）。 */
    static String extractArticleId(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        Matcher path = ARTICLE_ID_PATH_PATTERN.matcher(url);
        if (path.find()) {
            return path.group(1);
        }
        Matcher query = ARTICLE_ID_QUERY_PATTERN.matcher(url);
        return query.find() ? query.group(1) : null;
    }

    /**
     * 视频链接：tv/show、video.weibo.com 的 fid、m.weibo.cn/video 路径。
     * 普通状态链接优先：{@code m.weibo.cn/status/xxx?fid=...} 这种"状态页挂视频"的链接
     * urlToMid 可正常解析 mid，按普通链路处理，不算视频链接。
     */
    static boolean isVideoUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        if (StringUtils.isNotBlank(WeiboTool.urlToMid(url))
                && StringUtils.isBlank(WeiboTool.videoUrlToMid(url))) {
            return false;
        }
        return url.contains("tv/show") || url.contains("video.weibo.com")
                || url.contains("m.weibo.cn/video/") || extractVideoOid(url) != null;
    }

    /** 提取视频 oid（1034:xxx）。 */
    static String extractVideoOid(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        Matcher matcher = VIDEO_OID_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 解析微博 mid（仅普通状态链接；视频/文章链接请走各自的换算路径）。
     *
     * <p>优先级：显式传入的 mid &gt; 普通微博链接 &gt; 原始 targetId。
     * 注意 {@code m.weibo.cn/status/{b62}} 解析出的是 b62 短 id，
     * buildComments 的 id 参数同样接受 b62（已实测），无需换算。
     */
    static String resolveMid(String explicitMid, String url, String targetId) {
        if (StringUtils.isNotBlank(explicitMid)) {
            return explicitMid;
        }
        if (StringUtils.isNotBlank(url) && !isArticleUrl(url) && !isVideoUrl(url)) {
            String mid = WeiboTool.urlToMid(url);
            if (StringUtils.isBlank(mid)) {
                mid = WeiboTool.videoUrlToMid(url);
            }
            if (StringUtils.isNotBlank(mid)) {
                return mid;
            }
        }
        return targetId;
    }

    /**
     * 从微博链接中提取作者 uid；无法识别时返回 null。
     */
    static String resolveUid(String explicitUid, String url) {
        if (StringUtils.isNotBlank(explicitUid)) {
            return explicitUid;
        }
        if (StringUtils.isBlank(url)) {
            return null;
        }
        Matcher matcher = UID_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }
}
