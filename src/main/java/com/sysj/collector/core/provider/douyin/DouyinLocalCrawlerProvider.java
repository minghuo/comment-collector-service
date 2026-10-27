package com.sysj.collector.core.provider.douyin;

import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.tools.CommonTools;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
import com.sysj.collector.core.provider.douyin.sign.ABogusSigner;
import com.sysj.collector.core.provider.douyin.sign.DouyinCookie;
import com.sysj.collector.core.provider.support.HttpUtilProvider;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;
import com.sysj.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音 - 本地爬虫供应商（网页版评论接口，Cookie + 纯算 a_bogus）。
 *
 * <p>Bean 名称 = {@code douyin_local}。对照中科天玑的 {@code douyin_golaxy}：本实现直接调用抖音
 * 网页端接口，不需要第三方 key，但需要调用方提供浏览器 Cookie。
 *
 * <h3>签名边界（为什么只有 a_bogus）</h3>
 * 抖音网页端的请求签名有三层：{@code a_bogus}、{@code x-secsdk-web-signature}（websign）、
 * {@code bd-ticket-guard-*}。其中 websign 只保护 14 条「详情/列表」路径
 * （{@code /aweme/v1/web/aweme/detail/}、{@code /aweme/post/} 等），
 * <b>评论接口 {@code /aweme/v1/web/comment/list/} 不在其列</b> —— 评论采集的最小签名集就是
 * {@code a_bogus} + 有效 Cookie。{@code bd-ticket-guard} 属于登录态校验层，算法带时间戳且无成熟
 * 纯算实现，因此这里不做纯算，而是：
 * <ul>
 *   <li>默认不发；实测多数账号的评论接口不带也能返回数据；</li>
 *   <li>调用方若在浏览器里抓到了 {@code bd-ticket-guard-client-data}，通过
 *       {@code extra.bdTicketGuardClientData} 透传，本供应商按抖音 v2 协议补齐其余 4 个头
 *       （{@code -ree-public-key} / {@code -version} / {@code -web-sign-type} / {@code -web-version}）。</li>
 * </ul>
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code mid} —— 视频 aweme_id；为空时回退 {@code targetId}，再回退 {@code fromUrl}
 *       （支持 {@code /video/{id}}、{@code /note/{id}}、{@code ?modal_id=}/{@code aweme_id=}/{@code vid=}、
 *       以及 {@code v.douyin.com} 短链：后者会额外发一次引导请求解析重定向）</li>
 *   <li>{@code cookie} —— <b>必填</b>，浏览器 {@code document.cookie} 原样串；缺失的 {@code msToken}/
 *       {@code webid}/{@code s_v_web_id} 会按 {@link DouyinCookie#withDefaults()} 补齐</li>
 *   <li>{@code page} —— 页码，从 1 开始（默认 1）；内部换算为接口游标
 *       {@code cursor = (page - 1) * count}（评论接口的 cursor 就是纯偏移）</li>
 *   <li>{@code count} —— 单页条数（默认 20，上限 50）</li>
 *   <li>{@code commentId} —— 父评论 id；传了则改调 {@code /comment/list/reply/} 采二级评论
 *       （该接口把视频 id 叫 {@code item_id}，是平台的历史拼写差异）</li>
 *   <li>{@code userAgent} —— 可选；<b>必须与 Cookie 来源浏览器一致</b>，因为 a_bogus 的 UA 摘要参与签名</li>
 *   <li>{@code bdTicketGuardClientData} 及其余 4 个 ticket-guard 头 —— 可选透传，见上文</li>
 * </ul>
 *
 * <h3>分页契约</h3>
 * {@code PlatformCursorKeys} 里 {@code douyin:comment} 是 {@code PAGE_INCREMENT}，所以本供应商只在
 * {@code has_more=1} 时回填 {@code nextUrl = page + 1}；调用方按页码续采（{@code AutoPagingPolicy}
 * 页码类忽略 {@code nextUrl}，两者语义因此保持一致）。
 *
 * <h3>失败语义（只发一次请求，重试归管线 {@code RetryStage}）</h3>
 * <ul>
 *   <li>缺参/缺 Cookie → {@code STATUS_PARAM}（不可重试）；</li>
 *   <li>{@code status_code != 0}、命中验证码页、响应不是 JSON → {@code STATUS_URL_ERROR}
 *       （不可重试：同一 Cookie 重试只会更糟，应换 Cookie 或换供应商）；</li>
 *   <li>网络异常/空响应 → {@code STATUS_ERROR}（可重试）。</li>
 * </ul>
 */
@ProviderCapability({ Capability.COMMENT, Capability.SUB_COMMENT, Capability.PAGE_PAGING,
        Capability.LOGIN_STATE, Capability.PROXY, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "douyin", feature = "comment", name = "抖音-本地爬虫")
@Slf4j
@Component("douyin_local")
public class DouyinLocalCrawlerProvider implements CommentProvider {

    /** 一级评论接口（视频 id 参数名 aweme_id） */
    static final String COMMENT_LIST_API = "https://www.douyin.com/aweme/v1/web/comment/list/";
    /** 二级评论接口（视频 id 参数名 item_id + comment_id） */
    static final String COMMENT_REPLY_API = "https://www.douyin.com/aweme/v1/web/comment/list/reply/";

    static final int DEFAULT_COUNT = 20;
    static final int MAX_COUNT = 50;

    /** aweme_id 是 19 位十进制串；放宽到 15–25 位以兼容历史视频 */
    private static final Pattern AWEME_ID_PATTERN = Pattern.compile("\\d{15,25}");
    private static final Pattern URL_AWEME_PATTERN =
            Pattern.compile("(?:/video/|/note/|[?&](?:modal_id|aweme_id|vid)=)(\\d{15,25})");
    /** 短链形如 {@code https://v.douyin.com/iRNBho6u/}（末尾斜杠可有可无，复制文案里常带中文标点） */
    private static final Pattern SHORT_LINK_PATTERN =
            Pattern.compile("https?://v\\.douyin\\.com/[A-Za-z0-9_\\-]+/?");

    /** 请求的指纹参数（固定值，与 a_bogus 里写的屏幕信息同源；改这里要同步 browserInfo） */
    static final int SCREEN_WIDTH = 1920;
    static final int SCREEN_HEIGHT = 1080;

    private static final HttpUtil httpUtil = HttpUtilProvider.localClient();

    @Override
    public String providerKey() {
        return "douyin_local";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();

        String awemeId = resolveAwemeId(extra.get("mid"), request.getTargetId(), request.getFromUrl());
        if (StringUtils.isBlank(awemeId)) {
            return failed(CommonStatusEnum.STATUS_URL_ERROR,
                    "无法解析抖音视频 id（支持 extra.mid / targetId / /video/{id} / v.douyin.com 短链）");
        }

        DouyinCookie cookie = DouyinCookie.parse(extra.get("cookie"));
        if (cookie.isBlank()) {
            return failed(CommonStatusEnum.STATUS_PARAM, "缺少 Cookie（extra.cookie）");
        }
        cookie = cookie.withDefaults();

        int page = Math.max(1, intValue(extra.get("page"), 1));
        int count = clamp(intValue(extra.get("count"), DEFAULT_COUNT), 1, MAX_COUNT);
        int cursor = (page - 1) * count;
        String commentId = StringUtils.trimToNull(extra.get("commentId"));
        boolean replyMode = commentId != null;

        String query = buildQuery(awemeId, replyMode ? commentId : null, cursor, count, cookie);
        String userAgent = StringUtils.defaultIfBlank(extra.get("userAgent"), ABogusSigner.DEFAULT_USER_AGENT);
        String aBogus = ABogusSigner.of(userAgent).sign(query);
        String url = (replyMode ? COMMENT_REPLY_API : COMMENT_LIST_API)
                + "?" + query + "&a_bogus=" + URLEncoder.encode(aBogus, StandardCharsets.UTF_8);

        log.info("[dy-local] 采集开始: awemeId={} cursor={} count={} reply={} cookie[{}]",
                awemeId, cursor, count, replyMode, cookie.maskedSummary());
        return fetch(url, buildHeaders(userAgent, cookie, awemeId, extra), page, count, awemeId,
                replyMode ? commentId : null);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 请求组装
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 组装参与签名的 query（顺序即 a_bogus 摘要顺序，服务端按收到的原串重算，故顺序本身不重要，
     * 但与真实流量对齐便于比对抓包）。
     */
    static String buildQuery(String awemeId, String commentId, int cursor, int count, DouyinCookie cookie) {
        StringBuilder query = new StringBuilder(512);
        append(query, "device_platform", "webapp");
        append(query, "aid", String.valueOf(ABogusSigner.AID));
        append(query, "channel", "channel_pc_web");
        if (commentId == null) {
            append(query, "aweme_id", awemeId);
        } else {
            // 回复接口沿用平台拼写：item_id / comment_id
            append(query, "item_id", awemeId);
            append(query, "comment_id", commentId);
        }
        append(query, "cursor", String.valueOf(cursor));
        append(query, "count", String.valueOf(count));
        append(query, "item_type", "0");
        if (commentId == null) {
            append(query, "insert_ids", "");
            append(query, "whale_cut_token", "");
            append(query, "cut_version", "1");
            append(query, "rcFT", "");
        }
        append(query, "update_version_code", "170400");
        append(query, "pc_client_type", "1");
        append(query, "pc_libra_divert", "Windows");
        append(query, "support_h265", "1");
        append(query, "support_dash", "1");
        append(query, "cpu_core_num", "16");
        append(query, "version_code", "170400");
        append(query, "version_name", "17.4.0");
        append(query, "cookie_enabled", "true");
        append(query, "screen_width", String.valueOf(SCREEN_WIDTH));
        append(query, "screen_height", String.valueOf(SCREEN_HEIGHT));
        append(query, "browser_language", "zh-CN");
        append(query, "browser_platform", "Win32");
        append(query, "browser_name", "Chrome");
        append(query, "browser_version", "155.0.0.0");
        append(query, "browser_online", "true");
        append(query, "engine_name", "Blink");
        append(query, "engine_version", "155.0.0.0");
        append(query, "os_name", "Windows");
        append(query, "os_version", "10");
        append(query, "device_memory", "32");
        append(query, "platform", "PC");
        append(query, "downlink", "10");
        append(query, "effective_type", "4g");
        append(query, "round_trip_time", "50");
        append(query, "webid", cookie.webid());
        // verifyFp 与 fp 在真实流量里是同一个值，来自 s_v_web_id
        String verifyFp = cookie.verifyFpCookie();
        append(query, "verifyFp", verifyFp);
        append(query, "fp", verifyFp);
        append(query, "msToken", cookie.msToken());
        return query.toString();
    }

    private static void append(StringBuilder query, String name, String value) {
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(name).append('=').append(value == null ? "" : value);
    }

    private static Map<String, String> buildHeaders(String userAgent, DouyinCookie cookie,
                                                    String awemeId, Map<String, String> extra) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json, text/plain, */*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("referer", "https://www.douyin.com/video/" + awemeId);
        headers.put("user-agent", userAgent);
        headers.put("cookie", cookie.header());
        if (StringUtils.isNotBlank(cookie.uifid())) {
            headers.put("uifid", cookie.uifid());
        }
        // bd-ticket-guard：登录态请求可能校验；调用方抓到什么就透传什么，缺失则不伪造
        String guardClientData = StringUtils.trimToNull(extra.get("bdTicketGuardClientData"));
        if (guardClientData != null) {
            headers.put("bd-ticket-guard-client-data", guardClientData);
            if (StringUtils.isNotBlank(extra.get("bdTicketGuardReePublicKey"))) {
                headers.put("bd-ticket-guard-ree-public-key", extra.get("bdTicketGuardReePublicKey").trim());
            }
            headers.put("bd-ticket-guard-version",
                    StringUtils.defaultIfBlank(extra.get("bdTicketGuardVersion"), "2"));
            headers.put("bd-ticket-guard-web-sign-type",
                    StringUtils.defaultIfBlank(extra.get("bdTicketGuardWebSignType"), "1"));
            headers.put("bd-ticket-guard-web-version",
                    StringUtils.defaultIfBlank(extra.get("bdTicketGuardWebVersion"), "2"));
        }
        return headers;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 取数 + 解析
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 统一的取数 + 解析（一级/二级共用）。
     *
     * <p><b>只发一次请求</b>：重试归管线 {@code RetryStage}，次数取平台功能配置的
     * {@code providers[].max_retry}，且每次重试重新过 {@code RateLimitStage} 领令牌。
     */
    private CommonEntity<Comment> fetch(String url, Map<String, String> headers,
                                        int page, int count, String awemeId, String parentCommentId) {
        try {
            String body = httpUtil.getString(url, headers);
            if (StringUtils.isBlank(body)) {
                return failed(CommonStatusEnum.STATUS_ERROR, "响应为空");
            }
            String trimmed = body.trim();
            if (!trimmed.startsWith("{")) {
                // 登录/验证码页、或网关 HTML：换同一个 Cookie 重试也不会变好
                return failed(CommonStatusEnum.STATUS_URL_ERROR,
                        "响应不是 JSON（登录/验证码页）：" + StringUtils.abbreviate(trimmed, 120));
            }
            String statusCode = CommonParser.getJsonPathOne(body, "$.status_code");
            if (StringUtils.isNotBlank(statusCode) && !"0".equals(statusCode.trim())) {
                log.warn("[dy-local] 抖音返回非 0: status_code={} awemeId={}", statusCode, awemeId);
                return failed(CommonStatusEnum.STATUS_URL_ERROR,
                        "抖音返回 status_code=" + statusCode + "（风控/登录态失效，请更换 Cookie 或降低频率）");
            }

            List<String> rawComments = CommonParser.getJsonPathMany(body, "$.comments");
            List<Comment> comments = new ArrayList<>();
            if (CollUtil.isNotEmpty(rawComments)) {
                for (String rawComment : rawComments) {
                    comments.add(Comment.buildFromDouyinLocal(rawComment, awemeId, parentCommentId));
                }
            }
            boolean hasMore = "1".equals(CommonParser.getJsonPathOne(body, "$.has_more"));
            Integer total = CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.total"));
            return CommonEntity.<Comment>builder()
                    .haseMore(hasMore)
                    .nextUrl(hasMore ? String.valueOf(page + 1) : null)
                    .totalPage(CommonTools.totalPage(total, count))
                    .dataList(comments)
                    .status(CommonStatusEnum.STATUS_SUCCESS)
                    .build();
        } catch (Exception e) {
            log.warn("[dy-local] 请求失败: awemeId={} {}", awemeId, e.getMessage());
            return failed(CommonStatusEnum.STATUS_ERROR, e.getMessage());
        }
    }

    /** 失败结果：由管线 {@code validate} 统一判定，再决定重试或切候选。 */
    private static CommonEntity<Comment> failed(CommonStatusEnum status, String message) {
        return CommonEntity.<Comment>builder()
                .haseMore(false)
                .status(status)
                .msg(StringUtils.abbreviate(message, 200))
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 视频 id 解析（种子解析；抖音的短链只能靠一次引导请求换出真实 id）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 解析 aweme_id：显式参数 > targetId > 链接；都解析不出且是 {@code v.douyin.com} 短链时，
     * 发一次引导请求跟随重定向，再从落地页里提取 id。
     */
    private String resolveAwemeId(String explicitMid, String targetId, String fromUrl) {
        String direct = firstAwemeId(explicitMid, targetId, fromUrl);
        if (direct != null) {
            return direct;
        }
        String shortLink = shortLinkIn(explicitMid, targetId, fromUrl);
        if (shortLink == null) {
            return null;
        }
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("user-agent", ABogusSigner.DEFAULT_USER_AGENT);
            String landing = httpUtil.getString(shortLink, headers);
            String resolved = extractAwemeId(landing);
            log.info("[dy-local] 短链解析: {} → awemeId={}", shortLink, resolved);
            return resolved;
        } catch (Exception e) {
            log.warn("[dy-local] 短链解析失败: {} {}", shortLink, e.getMessage());
            return null;
        }
    }

    private static String firstAwemeId(String... candidates) {
        for (String candidate : candidates) {
            String awemeId = extractAwemeId(candidate);
            if (awemeId != null) {
                return awemeId;
            }
        }
        return null;
    }

    /** 从候选串里挑出第一个 {@code v.douyin.com} 短链（分享文案里混着中文与标点）。 */
    static String shortLinkIn(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.isBlank(candidate)) {
                continue;
            }
            Matcher matcher = SHORT_LINK_PATTERN.matcher(candidate);
            if (matcher.find()) {
                return matcher.group();
            }
        }
        return null;
    }

    /**
     * 从「纯 id / 链接 / 落地页 HTML」里提取 aweme_id。
     *
     * <p>纯数字直接返回（要求整串都是数字，避免把 19 位用户 id 当视频 id）；
     * 其余先按链接形态匹配，再退化到在全串里找 {@code /video/{id}} 或 {@code modal_id=}。
     */
    static String extractAwemeId(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (AWEME_ID_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }
        Matcher matcher = URL_AWEME_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static Integer intValue(String text, int fallback) {
        Integer value = CommonTools.stringToInteger(text);
        return value == null ? fallback : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
