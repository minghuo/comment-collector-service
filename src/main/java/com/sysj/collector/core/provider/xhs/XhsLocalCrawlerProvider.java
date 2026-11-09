package com.sysj.collector.core.provider.xhs;

import com.bewilder.parser.CommonParser;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
import com.sysj.collector.core.provider.support.HttpUtilProvider;
import com.sysj.collector.core.provider.xhs.sign.XhsCookie;
import com.sysj.collector.core.provider.xhs.sign.XhsSign;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;
import com.sysj.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小红书评论采集（本地纯算签名，无第三方签名服务）。
 *
 * <p>接口：{@code GET https://edith.xiaohongshu.com/api/sns/web/v2/comment/page}，
 * 翻页参数 {@code cursor}（响应 {@code $.data.cursor} 原样回传）。
 *
 * <p>签名走 {@link XhsSign}：默认 {@code XYW_}（AES），{@code extra.signFormat=xys} 可切
 * 传统 {@code XYS_}。{@code x-s-common} 依赖浏览器 localStorage 的 b1，未提供时不发，
 * 由服务端按游客态处理。
 *
 * <p>失败语义与抖音本地供应商一致：缺参 → {@code STATUS_PARAM}；验证码/登录失效/风控 →
 * {@code STATUS_URL_ERROR}（不可重试，换 cookie 或换供应商）；网络/空响应 → {@code STATUS_ERROR}。
 */
@ProviderCapability({Capability.COMMENT, Capability.CURSOR_PAGING,
        Capability.LOGIN_STATE, Capability.PROXY, Capability.SYNC_SUPPORTED})
@ProviderMeta(platform = "xhs", feature = "comment", name = "小红书-本地爬虫")
@Slf4j
@Component("xhs_local")
public class XhsLocalCrawlerProvider implements CommentProvider {

    static final String PROVIDER_KEY = "xhs_local";
    static final String COMMENT_API = "https://edith.xiaohongshu.com/api/sns/web/v2/comment/page";
    static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    static final String DEFAULT_IMAGE_FORMATS = "jpg,webp,avif";

    private static final Pattern NOTE_ID_IN_URL = Pattern.compile("/(?:explore|discovery/item)/([0-9a-fA-F]{16,32})");
    private static final Pattern XSEC_TOKEN_IN_URL = Pattern.compile("[?&]xsec_token=([^&]+)");

    private static final HttpUtil httpUtil = HttpUtilProvider.localClient();

    @Override
    public String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();

        String rawCookie = extra.get("cookie");
        if (StringUtils.isBlank(rawCookie)) {
            return failed(CommonStatusEnum.STATUS_PARAM, "缺少 Cookie（extra.cookie）");
        }
        XhsCookie cookie = XhsCookie.parse(rawCookie);
        if (StringUtils.isBlank(cookie.a1())) {
            return failed(CommonStatusEnum.STATUS_PARAM, "Cookie 缺少 a1");
        }
        String noteId = resolveNoteId(request, extra);
        if (StringUtils.isBlank(noteId)) {
            return failed(CommonStatusEnum.STATUS_PARAM, "缺少笔记 id（extra.noteId / targetId / fromUrl）");
        }
        String xsecToken = StringUtils.defaultIfBlank(extra.get("xsecToken"), xsecTokenFromUrl(request.getFromUrl()));

        String cursor = StringUtils.defaultString(extra.get("cursor"));
        String topCommentId = StringUtils.defaultString(extra.get("topCommentId"));
        String imageFormats = StringUtils.defaultIfBlank(extra.get("imageFormats"), DEFAULT_IMAGE_FORMATS);

        Map<String, String> params = buildParams(noteId, cursor, topCommentId, imageFormats, xsecToken);

        long ts = System.currentTimeMillis() / 1000L;
        boolean useXys = "xys".equalsIgnoreCase(extra.getOrDefault("signFormat", "xyw"));
        String xs = useXys
                ? XhsSign.signXs("/api/sns/web/v2/comment/page", params, cookie.a1(), ts)
                : XhsSign.signXyw("/api/sns/web/v2/comment/page", params, cookie.a1(), ts);
        String url = COMMENT_API + "?" + XhsSign.buildQuery(params);
        Map<String, String> headers = buildHeaders(cookie, extra.get("b1"), useXys, xs, ts, noteId);

        log.info("[xhs-local] 评论采集: note={} cursor={} format={} cookie[{}]",
                noteId, StringUtils.defaultIfBlank(cursor, "-"), useXys ? "xys" : "xyw",
                cookie.maskedSummary());

        try {
            String body = httpUtil.getString(url, headers);
            if (StringUtils.isBlank(body)) {
                return failed(CommonStatusEnum.STATUS_ERROR, "响应为空");
            }
            String trimmed = body.trim();
            if (!trimmed.startsWith("{")) {
                return failed(CommonStatusEnum.STATUS_URL_ERROR,
                        "响应不是 JSON（登录/验证码页）：" + StringUtils.abbreviate(trimmed, 120));
            }
            String success = CommonParser.getJsonPathOne(trimmed, "$.success");
            if (!"true".equalsIgnoreCase(success)) {
                return failed(CommonStatusEnum.STATUS_URL_ERROR, "接口拒绝：code="
                        + CommonParser.getJsonPathOne(trimmed, "$.code") + " "
                        + StringUtils.defaultString(CommonParser.getJsonPathOne(trimmed, "$.msg")));
            }

            List<String> rawComments = CommonParser.getJsonPathMany(trimmed, "$.data.comments");
            List<Comment> comments = rawComments.stream()
                    .map(raw -> Comment.buildFromXhsLocal(raw, noteId, null))
                    .filter(comment -> StringUtils.isNotBlank(comment.getCommentId()))
                    .toList();

            boolean hasMore = "true".equalsIgnoreCase(CommonParser.getJsonPathOne(trimmed, "$.data.has_more"));
            String nextCursor = CommonParser.getJsonPathOne(trimmed, "$.data.cursor");

            return CommonEntity.<Comment>builder()
                    .haseMore(hasMore)
                    .nextUrl(hasMore ? StringUtils.defaultIfBlank(nextCursor, null) : null)
                    .dataList(comments)
                    .status(CommonStatusEnum.STATUS_SUCCESS)
                    .build();
        } catch (Exception e) {
            log.warn("[xhs-local] 评论采集异常: note={} cursor={}", noteId, cursor, e);
            return failed(CommonStatusEnum.STATUS_ERROR, "采集异常：" + e.getMessage());
        }
    }

    private Map<String, String> buildHeaders(XhsCookie cookie, String extraB1, boolean useXys,
                                             String xs, long ts, String noteId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json, text/plain, */*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("origin", "https://www.xiaohongshu.com");
        headers.put("priority", "u=1, i");
        headers.put("referer", "https://www.xiaohongshu.com/explore/" + noteId);
        headers.put("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-site");
        headers.put("user-agent", DEFAULT_USER_AGENT);
        headers.put("cookie", cookie.header());
        headers.put("x-s", xs);
        headers.put("x-t", XhsSign.xt(ts));
        headers.put("x-mns", "unload");
        headers.put("x-b3-traceid", randomHex(16));
        headers.put("x-xray-traceid", randomHex(32));
        // 未切 XYS 时 XYW 不需要 x-s-common；提供 b1 才发（缺 b1 的常见报错见类注释）
        String b1 = StringUtils.defaultIfBlank(extraB1, cookie.b1());
        if (StringUtils.isNotBlank(b1) && !useXys) {
            headers.put("x-s-common", XhsSign.signXsCommon(cookie.a1(), cookie.webBuild(), b1));
        }
        return headers;
    }

    /**
     * 评论 page 的参数表（顺序即签名串顺序）。{@code xsec_token} 为空时不带该参数；
     * 值里已含 {@code %3D} 的 token 由 {@link XhsSign#buildQuery} 原样保留。
     */
    static Map<String, String> buildParams(String noteId, String cursor, String topCommentId,
                                           String imageFormats, String xsecToken) {
        Map<String, String> params = XhsSign.params();
        params.put("note_id", noteId);
        params.put("cursor", cursor);
        params.put("top_comment_id", topCommentId);
        params.put("image_formats", imageFormats);
        if (StringUtils.isNotBlank(xsecToken)) {
            params.put("xsec_token", xsecToken);
        }
        return params;
    }

    static String resolveNoteId(CommentCollectRequest request, Map<String, String> extra) {
        String noteId = StringUtils.defaultIfBlank(extra.get("noteId"), request.getTargetId());
        if (StringUtils.isNotBlank(noteId)) {
            return noteId.trim();
        }
        if (request.getFromUrl() != null) {
            Matcher matcher = NOTE_ID_IN_URL.matcher(request.getFromUrl());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    static String xsecTokenFromUrl(String fromUrl) {
        if (StringUtils.isBlank(fromUrl)) {
            return null;
        }
        Matcher matcher = XSEC_TOKEN_IN_URL.matcher(fromUrl);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(Character.forDigit(ThreadLocalRandom.current().nextInt(16), 16));
        }
        return sb.toString();
    }

    private static CommonEntity<Comment> failed(CommonStatusEnum status, String message) {
        return CommonEntity.<Comment>builder()
                .haseMore(false)
                .status(status)
                .msg(StringUtils.abbreviate(message, 200))
                .build();
    }
}
