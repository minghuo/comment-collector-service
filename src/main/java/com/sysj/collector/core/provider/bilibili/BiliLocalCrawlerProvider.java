package com.sysj.collector.core.provider.bilibili;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.tools.CommonTools;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
import com.sysj.collector.core.provider.support.HttpUtilProvider;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;
import com.sysj.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B站 - 本地爬虫供应商（wbi 签名 + 登录 Cookie）。
 *
 * <p>Bean 名称 = {@code bilibili_local}。实现对照 {@code auto-task-web} 的
 * {@code BiliCrawler} / {@code BiliCommentCrawler} / {@code BiliVideoInfoCrawler}：
 * <ul>
 *   <li>主评论：{@code api.bilibili.com/x/v2/reply/wbi/main}（w_rid 签名，需 Cookie 含 bili_ticket），
 *       游标为拼好的查询参数串（{@code mode=..&oid=..&pagination_str=..}）；</li>
 *   <li>子评论：{@code api.bilibili.com/x/v2/reply/reply}（页码翻页，无需 wbi）。</li>
 * </ul>
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code cookie} —— B站登录 Cookie，<b>必填</b>（须含 bili_ticket）</li>
 *   <li>{@code sort} —— 排序：2 最新（默认）/ 3 最热</li>
 *   <li>{@code commentId} —— 父评论 rpid，传了则采子评论（配合 {@code page} 翻页）</li>
 *   <li>{@code page} —— 子评论页码（从 1 开始）</li>
 *   <li>{@code cursor} —— 上一页返回的游标：主评论为查询参数串（含 {@code oid=}），
 *       子评论为 {@code replypage=N}（自动识别，无需调用方区分）</li>
 * </ul>
 *
 * <h3>游标约定</h3>
 * 平台在 {@code PlatformCursorKeys} 登记为 {@code extra.cursor}（与 Golaxy 渠道共用键）。
 * 两种形态通过内容区分：含 {@code oid=} 是主评论翻页参数串，{@code replypage=} 是子评论页码。
 *
 * <p>mid 语义：{@code Comment.mid} = 帖子 oid（评论区的 oid），落库业务主键即
 * {@code taskId-{oid}-{rpid}}，与 auto-task-web 的主键规则一致。
 */
@ProviderCapability({ Capability.COMMENT, Capability.SUB_COMMENT, Capability.CURSOR_PAGING,
        Capability.LOGIN_STATE, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "bilibili", feature = "comment", name = "B站-本地爬虫")
@Slf4j
@Component("bilibili_local")
public class BiliLocalCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = HttpUtilProvider.apiClient();

    private static final String WBI_SALT = "ea1db124af3c7062474693fa704f4ff8";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    /** 主评论游标形态标记：查询参数串 */
    private static final String MAIN_CURSOR_HINT = "oid=";
    /** 子评论游标形态标记：页码串 */
    private static final String REPLY_CURSOR_PREFIX = "replypage=";

    @Override
    public String providerKey() {
        return "bilibili_local";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String cookie = extra.get("cookie");
        String cursor = extra.get("cursor");
        // 主评论续采：游标参数串里已带 oid/type，无需重新解析链接
        String next = StringUtils.startsWith(cursor, REPLY_CURSOR_PREFIX) ? null
                : StringUtils.contains(cursor, MAIN_CURSOR_HINT) ? cursor
                : extra.get("next");
        String commentId = extra.get("commentId");
        String mid = resolveOid(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        log.info("[bili-local] 采集开始: mid={} commentId={} hasCookie={} cursor有参={}",
                mid, commentId, StringUtils.isNotBlank(cookie), StringUtils.isNotBlank(next));

        if (StringUtils.isBlank(cookie) || !cookie.contains("bili_ticket")) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_PARAM)
                    .msg("B站本地采集需要登录 Cookie（须含 bili_ticket），请通过 extra.cookie 提供").build();
        }
        if (StringUtils.isNotBlank(commentId)) {
            // 子评论：oid/type 来自 extra（主评论续采上下文回传）或链接解析
            String type = StringUtils.defaultIfBlank(extra.get("type"), "1");
            if (StringUtils.isBlank(mid)) {
                return CommonEntity.<Comment>builder().haseMore(false)
                        .status(CommonStatusEnum.STATUS_PARAM).msg("缺少帖子 oid（extra.mid）").build();
            }
            int page = parseReplyPage(cursor, extra.get("page"));
            return fetchReplies(mid, type, commentId, page);
        }
        return fetchMain(request.getFromUrl(), mid, extra.get("type"), cookie, extra.get("sort"), next);
    }

    /**
     * 主评论采集：首轮从链接解析 oid/type 并构造首页参数串；续轮直接用游标参数串重新签名请求
     */
    private CommonEntity<Comment> fetchMain(String fromUrl, String mid, String type, String cookie,
                                            String sort, String next) {
        String effectiveSort = StringUtils.defaultIfBlank(sort, "2");
        if (StringUtils.isBlank(next)) {
            // 首轮：解析 oid/type
            String resolvedType = StringUtils.defaultIfBlank(type, "1");
            String resolvedOid = mid;
            if (StringUtils.isBlank(resolvedOid)) {
                Map<String, String> target = resolveTargetFromUrl(fromUrl, cookie);
                resolvedOid = target.get("oid");
                resolvedType = StringUtils.defaultIfBlank(target.get("type"), "1");
            }
            if (StringUtils.isBlank(resolvedOid) || StringUtils.isBlank(resolvedType)) {
                return CommonEntity.<Comment>builder().haseMore(false)
                        .status(CommonStatusEnum.STATUS_URL_ERROR)
                        .msg("无法从链接解析 B 站评论区 oid（支持 av/BV/opus/动态链接）").build();
            }
            next = "mode=" + effectiveSort + "&oid=" + resolvedOid
                    + "&pagination_str=%7B%22offset%22%3A%22%22%7D&plat=1&seek_rpid=&type=" + resolvedType
                    + "&web_location=1315875";
        }
        String body = signedGet(next, cookie);
        return parseMain(next, body);
    }

    /**
     * wbi 签名请求：next 为查询参数串，附 w_rid/wts
     */
    private String signedGet(String next, String cookie) {
        // 对齐原实现的随机间隔，降低风控触发概率
        CommonTools.sleep(RandomUtil.randomInt(500, 1000));
        long wts = System.currentTimeMillis() / 1000;
        String wRid = DigestUtils.md5Hex(next + "&wts=" + wts + WBI_SALT).toLowerCase();
        String url = "https://api.bilibili.com/x/v2/reply/wbi/main?" + next + "&w_rid=" + wRid + "&wts=" + wts;
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Referer", "https://www.bilibili.com/video/av"
                + extractOidFromNext(next));
        headers.put("cookie", cookie);
        try {
            return httpUtil.getString(url, headers);
        } catch (Exception e) {
            log.warn("[bili-local] 主评论请求失败: {}", e.getMessage());
            return null;
        }
    }

    private CommonEntity<Comment> parseMain(String next, String result) {
        if (StringUtils.isBlank(result)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_ERROR).build();
        }
        String message = CommonParser.getJsonPathOne(result, "$.message");
        if (message.contains("已经被删除了")) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_DELETE).msg("帖子已删除").build();
        }
        if (message.contains("UP主已关闭评论区")) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_SUCCESS).msg("UP主已关闭评论区").build();
        }
        List<String> repliesList = CommonParser.getJsonPathMany(result, "$.data.replies");
        if (CollUtil.isEmpty(repliesList)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_FAIL).msg("响应无评论数据").build();
        }
        List<Comment> comments = new ArrayList<>();
        for (String replies : repliesList) {
            comments.add(Comment.buildFromBiliLocal(replies));
        }
        String cursorStr = CommonParser.getJsonPathOne(result, "$.data.cursor");
        boolean hasMore = "false".equals(CommonParser.getJsonPathOne(cursorStr, "$.is_end"));
        String nextOffset = CommonParser.getJsonPathOne(cursorStr, "$.pagination_reply.next_offset");
        if (StringUtils.isNotBlank(nextOffset) && nextOffset.contains("=")) {
            nextOffset = nextOffset.replace("=", "%3D");
        }
        // 从当前参数串继承 mode/oid/type，仅替换 pagination_str 的 offset
        String nextData = next.replaceFirst(
                "pagination_str=%7B%22offset%22%3A%22.*?%22%7D",
                "pagination_str=%7B%22offset%22%3A%22" + nextOffset + "%22%7D");
        return CommonEntity.<Comment>builder()
                .haseMore(hasMore)
                .nextUrl(nextData)
                .status(CommonStatusEnum.STATUS_SUCCESS)
                .dataList(comments)
                .build();
    }

    /**
     * 子评论采集：reply 接口页码翻页（无需 wbi/cookie），nextUrl 用 {@code replypage=N} 形态回传
     */
    private CommonEntity<Comment> fetchReplies(String oid, String type, String commentId, int page) {
        CommonTools.sleep(RandomUtil.randomInt(500, 1000));
        String apiUrl = "https://api.bilibili.com/x/v2/reply/reply?oid=" + oid + "&type=" + type
                + "&root=" + commentId + "&ps=10&pn=" + page + "&web_location=333.1368";
        String result;
        try {
            result = httpUtil.getString(apiUrl);
        } catch (Exception e) {
            log.warn("[bili-local] 子评论请求失败: {}", e.getMessage());
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_ERROR).build();
        }
        if (StringUtils.isBlank(result)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_ERROR).build();
        }
        String message = CommonParser.getJsonPathOne(result, "$.message");
        if (message.contains("已经被删除了")) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_DELETE).msg("帖子已删除").build();
        }
        List<String> repliesList = CommonParser.getJsonPathMany(result, "$.data.replies");
        if (CollUtil.isEmpty(repliesList)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_SUCCESS).msg("无更多子评论").build();
        }
        List<Comment> comments = new ArrayList<>();
        for (String replies : repliesList) {
            comments.add(Comment.buildFromBiliLocal(replies));
        }
        Integer totalCount = CommonTools.stringToInteger(CommonParser.getJsonPathOne(result, "$.data.page.count"));
        Integer totalPage = CommonTools.totalPage(totalCount, 10);
        boolean hasMore = page < totalPage;
        return CommonEntity.<Comment>builder()
                .haseMore(hasMore)
                .nextUrl(hasMore ? REPLY_CURSOR_PREFIX + (page + 1) : null)
                .status(CommonStatusEnum.STATUS_SUCCESS)
                .dataList(comments)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 链接 → oid/type 解析（对照 BiliVideoInfoCrawler.getCommentId）
    // ──────────────────────────────────────────────────────────────────────

    /** 从游标参数串提取 oid（Referer 用） */
    private static String extractOidFromNext(String next) {
        int idx = next.indexOf("oid=");
        if (idx < 0) {
            return "0";
        }
        String rest = next.substring(idx + 4);
        int end = rest.indexOf('&');
        return end > 0 ? rest.substring(0, end) : rest;
    }

    private static int parseReplyPage(String cursor, String pageStr) {
        if (StringUtils.startsWith(cursor, REPLY_CURSOR_PREFIX)) {
            Integer page = CommonTools.stringToInteger(cursor.substring(REPLY_CURSOR_PREFIX.length()));
            if (page != null && page > 1) {
                return page;
            }
        }
        Integer page = CommonTools.stringToInteger(pageStr);
        return page != null && page > 0 ? page : 1;
    }

    /** 显式 mid 优先，其次链接末段（BV 号等），供调用方已解析的场景复用 */
    private static String resolveOid(String explicitMid, String url, String targetId) {
        if (StringUtils.isNotBlank(explicitMid)) {
            return explicitMid;
        }
        if (StringUtils.isNotBlank(targetId)) {
            return targetId;
        }
        if (StringUtils.isBlank(url)) {
            return null;
        }
        String clean = url.split("\\?")[0].replace("#ocr", "");
        if (clean.contains("video/av")) {
            return clean.split("video/av")[1].replace("/", "");
        }
        if (clean.contains("video/BV")) {
            return clean.split("video/")[1].replace("/", "");
        }
        if (clean.contains("/opus/")) {
            return clean.split("/opus/")[1];
        }
        if (clean.contains("t.bilibili.com")) {
            String[] parts = clean.split("com/");
            return parts.length > 1 ? parts[1] : null;
        }
        return null;
    }

    /**
     * 从链接解析评论区 oid/type：av 链接直接取；BV/专栏/动态先取 mid，
     * 再经 view / dynamic-detail 接口换算（对照 BiliVideoInfoCrawler.getCommentId）
     */
    private Map<String, String> resolveTargetFromUrl(String url, String cookie) {
        Map<String, String> result = new HashMap<>();
        if (StringUtils.isBlank(url)) {
            return result;
        }
        String clean = url.split("\\?")[0].replace("#ocr", "");
        if (clean.contains("video/av")) {
            result.put("oid", clean.split("video/av")[1].replace("/", ""));
            result.put("type", "1");
            return result;
        }
        String mid = resolveOid(null, url, null);
        if (StringUtils.isBlank(mid)) {
            return result;
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        if (StringUtils.isNotBlank(cookie)) {
            headers.put("cookie", cookie);
        }
        try {
            String body;
            if (mid.contains("BV")) {
                body = httpUtil.getString("https://api.bilibili.com/x/web-interface/view?bvid=" + mid, headers);
                if (StringUtils.isNotBlank(body) && body.contains("aid")) {
                    result.put("oid", CommonParser.getJsonPathOne(body, "$.data.aid"));
                    result.put("type", "1");
                }
            } else {
                headers.put("origin", "https://t.bilibili.com");
                headers.put("referer", "https://t.bilibili.com/1170006394898743336");
                body = httpUtil.getString("https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?platform=web"
                        + "&gaia_source=main_web&id=" + mid, headers);
                if (StringUtils.isNotBlank(body) && body.contains("comment_id_str")) {
                    result.put("oid", CommonParser.getJsonPathOne(body, "$.data.item.basic.comment_id_str"));
                    result.put("type", CommonParser.getJsonPathOne(body, "$.data.item.basic.comment_type"));
                }
            }
        } catch (Exception e) {
            log.warn("[bili-local] 链接解析 oid 失败: url={}, {}", url, e.getMessage());
        }
        return result;
    }
}
