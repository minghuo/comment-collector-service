package com.sysj.collector.core.provider.xhs;

import com.bewilder.parser.CommonParser;
import com.sysj.collector.core.provider.AccountHistoryProvider;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
import com.sysj.collector.core.provider.support.AccountHistoryKeys;
import com.sysj.collector.core.provider.support.HttpUtilProvider;
import com.sysj.collector.core.provider.xhs.sign.XhsCookie;
import com.sysj.collector.core.provider.xhs.sign.XhsSign;
import com.sysj.collector.model.AccountHistoryCollectRequest;
import com.sysj.collector.model.AccountHistoryItem;
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
 * 小红书账号历史（用户作品流）采集。
 *
 * <p>接口：{@code GET https://edith.xiaohongshu.com/api/sns/web/v1/user_posted}，
 * 参数 {@code num/cursor/user_id/image_formats/xsec_token/xsec_source}，
 * 翻页游标来自响应 {@code $.data.cursor}。
 *
 * <p><b>签名必须用 {@code XYW_}</b>：2026 年 3 月起 xhshow 记录的行为是数据类接口
 * （{@code user_posted}、{@code otherinfo}）以 HTTP 406 拒绝传统 {@code XYS_}，
 * 本类固定走 {@link XhsSign#signXyw}，不提供切换（切了也过不了）。
 *
 * <p>该接口的 {@code xsec_token} 通常是必需参数，取 {@code extra.xsecToken} 或用户主页
 * 链接里的 query；没有 token 时接口常返回空 {@code notes} 而非报错，调用方需从主页链接取全。
 */
@ProviderCapability({Capability.CURSOR_PAGING, Capability.LOGIN_STATE,
        Capability.PROXY, Capability.SYNC_SUPPORTED})
@ProviderMeta(platform = "xhs", feature = "account_history", name = "小红书-账号历史",
        ratePerSecond = 0.5, maxQueueWaitMs = 30000)
@Slf4j
@Component("xhs_account_history")
public class XhsAccountHistoryProvider implements AccountHistoryProvider {

    static final String PROVIDER_KEY = "xhs_account_history";
    static final String USER_POSTED_API = "https://edith.xiaohongshu.com/api/sns/web/v1/user_posted";
    static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    static final int DEFAULT_PAGE_SIZE = 30;
    static final int MAX_PAGE_SIZE = 30;

    private static final Pattern USER_ID_IN_URL = Pattern.compile("/user/profile/([0-9a-fA-F]{24})");
    private static final Pattern XSEC_TOKEN_IN_URL = Pattern.compile("[?&]xsec_token=([^&]+)");

    private static final HttpUtil httpUtil = HttpUtilProvider.localClient();

    @Override
    public String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public CommonEntity<AccountHistoryItem> fetchHistory(AccountHistoryCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();

        String rawCookie = extra.get("cookie");
        if (StringUtils.isBlank(rawCookie)) {
            return failed(CommonStatusEnum.STATUS_PARAM, "缺少 Cookie（extra.cookie）");
        }
        XhsCookie cookie = XhsCookie.parse(rawCookie);
        if (StringUtils.isBlank(cookie.a1())) {
            return failed(CommonStatusEnum.STATUS_PARAM, "Cookie 缺少 a1");
        }
        String userId = resolveUserId(request, extra);
        if (StringUtils.isBlank(userId)) {
            return failed(CommonStatusEnum.STATUS_PARAM, "缺少用户 id（accountId / extra.accountId / fromUrl）");
        }
        String xsecToken = StringUtils.defaultIfBlank(extra.get(AccountHistoryKeys.XHS_XSEC_TOKEN),
                xsecTokenFromUrl(request.getFromUrl()));

        int num = Math.min(Math.max(request.resolveMaxCount(DEFAULT_PAGE_SIZE), 1), MAX_PAGE_SIZE);
        String cursor = StringUtils.defaultString(extra.get(AccountHistoryKeys.XHS_CURSOR));
        String xsecSource = StringUtils.defaultIfBlank(extra.get(AccountHistoryKeys.XHS_XSEC_SOURCE), "pc_user");

        Map<String, String> params = buildParams(num, cursor, userId, "jpg,webp,avif", xsecToken, xsecSource);

        long ts = System.currentTimeMillis() / 1000L;
        String xs = XhsSign.signXyw("/api/sns/web/v1/user_posted", params, cookie.a1(), ts);
        String url = USER_POSTED_API + "?" + XhsSign.buildQuery(params);
        Map<String, String> headers = buildHeaders(cookie, extra.get("b1"), xs, ts, userId);

        log.info("[xhs-history] 采集: user={} cursor={} num={} cookie[{}]",
                userId, StringUtils.defaultIfBlank(cursor, "-"), num, cookie.maskedSummary());

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

            List<String> rawNotes = CommonParser.getJsonPathMany(trimmed, "$.data.notes");
            List<AccountHistoryItem> items = rawNotes.stream()
                    .map(AccountHistoryItem::buildFromXhs)
                    .filter(item -> StringUtils.isNotBlank(item.getItemId()))
                    .toList();

            boolean hasMore = "true".equalsIgnoreCase(CommonParser.getJsonPathOne(trimmed, "$.data.has_more"));
            String nextCursor = CommonParser.getJsonPathOne(trimmed, "$.data.cursor");

            return CommonEntity.<AccountHistoryItem>builder()
                    .haseMore(hasMore)
                    .nextUrl(hasMore ? StringUtils.defaultIfBlank(nextCursor, null) : null)
                    .dataList(items)
                    .status(CommonStatusEnum.STATUS_SUCCESS)
                    .build();
        } catch (Exception e) {
            log.warn("[xhs-history] 采集异常: user={} cursor={}", userId, cursor, e);
            return failed(CommonStatusEnum.STATUS_ERROR, "采集异常：" + e.getMessage());
        }
    }

    private Map<String, String> buildHeaders(XhsCookie cookie, String extraB1, String xs,
                                             long ts, String userId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json, text/plain, */*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("origin", "https://www.xiaohongshu.com");
        headers.put("priority", "u=1, i");
        headers.put("referer", "https://www.xiaohongshu.com/user/profile/" + userId);
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
        String b1 = StringUtils.defaultIfBlank(extraB1, cookie.b1());
        if (StringUtils.isNotBlank(b1)) {
            headers.put("x-s-common", XhsSign.signXsCommon(cookie.a1(), cookie.webBuild(), b1));
        }
        return headers;
    }

    /** user_posted 参数表（顺序即签名串顺序）；xsec_token 为空时不带。 */
    static Map<String, String> buildParams(int num, String cursor, String userId,
                                           String imageFormats, String xsecToken, String xsecSource) {
        Map<String, String> params = XhsSign.params();
        params.put("num", String.valueOf(num));
        params.put("cursor", cursor);
        params.put("user_id", userId);
        params.put("image_formats", imageFormats);
        if (StringUtils.isNotBlank(xsecToken)) {
            params.put("xsec_token", xsecToken);
        }
        params.put("xsec_source", xsecSource);
        return params;
    }

    static String resolveUserId(AccountHistoryCollectRequest request, Map<String, String> extra) {
        String userId = StringUtils.defaultIfBlank(extra.get(AccountHistoryKeys.ACCOUNT_ID),
                request.resolveAccountId());
        if (StringUtils.isBlank(userId)) {
            return null;
        }
        String trimmed = userId.trim();
        if (!trimmed.contains("/") && !trimmed.contains("?")) {
            return trimmed;
        }
        Matcher matcher = USER_ID_IN_URL.matcher(trimmed);
        return matcher.find() ? matcher.group(1) : null;
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

    private static CommonEntity<AccountHistoryItem> failed(CommonStatusEnum status, String message) {
        return CommonEntity.<AccountHistoryItem>builder()
                .haseMore(false)
                .status(status)
                .msg(StringUtils.abbreviate(message, 200))
                .build();
    }
}
