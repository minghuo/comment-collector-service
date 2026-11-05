package com.sysj.collector.core.provider.toutiao;

import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.sysj.collector.core.provider.AccountHistoryProvider;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
import com.sysj.collector.core.provider.support.AccountHistoryKeys;
import com.sysj.collector.core.provider.support.HttpUtilProvider;
import com.sysj.collector.core.provider.support.ProviderUrls;
import com.sysj.collector.model.AccountHistoryCollectRequest;
import com.sysj.collector.model.AccountHistoryItem;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;
import com.sysj.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 今日头条 - 账号历史（用户作品流）供应商。
 *
 * <p>Bean 名称 = {@code toutiao_account_history}，功能码 {@code account_history}。
 *
 * <h3>接口</h3>
 * <pre>
 * GET https://www.toutiao.com/api/pc/list/user/feed
 *     ?category=pc_profile_ugc&amp;token={userToken}&amp;max_behot_time={cursor}
 *     &amp;count={count}&amp;aid=24&amp;app_name=toutiao_web
 * </pre>
 * <p>返回体：{@code data} 为作品数组，{@code has_more} 表达是否还有下一页，
 * {@code next.max_behot_time} 是下一页游标（取不到时回退为最后一条作品的 {@code behot_time}）。
 *
 * <h3>入参（{@link AccountHistoryCollectRequest}）</h3>
 * <ul>
 *   <li>{@code accountId} —— 头条 user token（形如 {@code MS4wLjABAAAA...}）；
 *       也可直接传账号主页链接，由 {@link #extractUserToken(String)} 解析；</li>
 *   <li>{@code extra.maxBehotTime} —— 翻页游标；空表示从头取；</li>
 *   <li>{@code extra.count} —— 每页条数（默认 {@value #DEFAULT_PAGE_SIZE}，上限 50）；</li>
 *   <li>{@code extra.category} —— 作品分类，默认 {@code pc_profile_ugc}（全部作品）。</li>
 * </ul>
 *
 * <p><b>签名说明</b>：该接口在本服务现有实现中按"无 {@code _signature} 的公开用户流"访问
 * （与 {@code ToutiaoLocalCrawlerProvider} 调评论接口同一策略）。若线上返回空数据且
 * {@code message} 提示校验失败，需要补 {@code _signature} 生成（属于独立议题，
 * 不在本供应商内偷偷降级 —— 失败会如实以 {@code STATUS_ERROR} 上报，由管线记熔断）。
 */
@ProviderCapability({ Capability.PAGE_PAGING, Capability.PROXY })
@ProviderMeta(platform = "toutiao", feature = "account_history", name = "今日头条-账号历史",
        flowEffect = "THROTTLE_QUEUE", maxQueueWaitMs = 30000, ratePerSecond = 0.3)
@Slf4j
@Component("toutiao_account_history")
public class TouTiaoAccountHistoryProvider implements AccountHistoryProvider {

    public static final String PROVIDER_KEY = "toutiao_account_history";

    private static final HttpUtil httpUtil = HttpUtilProvider.localClient();

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final String FEED_API = "https://www.toutiao.com/api/pc/list/user/feed";

    /** 默认每页条数（接口实测 10/20 均可用，20 是翻页效率与风控的折中）。 */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** 服务端允许的最大每页条数。 */
    static final int MAX_PAGE_SIZE = 50;

    /** 主页链接里 token 的常见形态，按优先级尝试。 */
    private static final List<Pattern> TOKEN_PATTERNS = List.of(
            Pattern.compile("/c/user/token/([A-Za-z0-9_.\\-]+)"),
            Pattern.compile("/user/token/([A-Za-z0-9_.\\-]+)"),
            Pattern.compile("[?&]token=([A-Za-z0-9_.\\-]+)"),
            Pattern.compile("/c/user/([A-Za-z0-9_.\\-]+)"),
            Pattern.compile("/user/([0-9]{5,})")
    );

    @Override
    public String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public CommonEntity<AccountHistoryItem> fetchHistory(AccountHistoryCollectRequest request) {
        String token = resolveToken(request);
        if (StringUtils.isBlank(token)) {
            log.warn("[toutiao-account-history] 账号标识为空: taskId={} fromUrl={}",
                    request.getTaskId(), request.getFromUrl());
            return CommonEntity.<AccountHistoryItem>builder()
                    .haseMore(false)
                    .status(CommonStatusEnum.STATUS_PARAM)
                    .msg("缺少头条账号标识（user token）")
                    .build();
        }
        int count = clampCount(request);
        String cursor = request.extraValue(AccountHistoryKeys.TOUTIAO_MAX_BEHOT_TIME);
        String category = StringUtils.defaultIfBlank(request.extraValue("category"), "pc_profile_ugc");
        log.info("[toutiao-account-history] 采集开始: token={} cursor={} count={} taskId={}",
                token, cursor, count, request.getTaskId());
        return requestPage(token, cursor, count, category);
    }

    /** 取一页作品流。 */
    CommonEntity<AccountHistoryItem> requestPage(String token, String cursor, int count,
                                                 String category) {
        StringBuilder url = new StringBuilder(FEED_API)
                .append("?category=").append(ProviderUrls.encodeParam(category))
                .append("&token=").append(ProviderUrls.encodeParam(token))
                .append("&max_behot_time=").append(ProviderUrls.encodeParam(StringUtils.defaultString(cursor)))
                .append("&count=").append(count)
                .append("&aid=24&app_name=toutiao_web");
        Map<String, String> headMap = new HashMap<>();
        headMap.put("User-Agent", UA);
        headMap.put("Referer", "https://www.toutiao.com/c/user/token/" + token + "/");
        try {
            String body = httpUtil.getString(url.toString(), headMap);
            if (StringUtils.isBlank(body)) {
                log.warn("[toutiao-account-history] 空响应: token={} cursor={}", token, cursor);
                return CommonEntity.<AccountHistoryItem>builder()
                        .haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
            }
            List<AccountHistoryItem> items = parseItems(body);
            boolean hasMore = hasMore(body);
            String nextCursor = nextCursor(body, items);
            log.info("[toutiao-account-history] 采集完成: token={} 本页={} hasMore={} nextCursor={}",
                    token, items.size(), hasMore, nextCursor);
            return CommonEntity.<AccountHistoryItem>builder()
                    .haseMore(hasMore)
                    .nextUrl(hasMore ? nextCursor : null)
                    .status(CommonStatusEnum.STATUS_SUCCESS)
                    .dataList(items)
                    .build();
        } catch (Exception e) {
            log.warn("[toutiao-account-history] 采集出错: token={} cursor={} error={}",
                    token, cursor, e.getMessage());
        }
        return CommonEntity.<AccountHistoryItem>builder()
                .haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    /** 解析一屏作品；只丢弃连 itemId 都没有的脏元素。 */
    static List<AccountHistoryItem> parseItems(String body) {
        List<AccountHistoryItem> items = new ArrayList<>();
        // 少数入口把数组多包了一层（$.data 是对象、$.data.data 才是作品数组）。
        // 先按包裹形态取，取不到再按常规形态取：对象形态下 $.data 会返回整个对象，
        // 若先取 $.data 会拿到非作品对象而把这一屏全丢掉。
        List<String> rawList = CommonParser.getJsonPathMany(body, "$.data.data");
        if (CollUtil.isEmpty(rawList)) {
            rawList = CommonParser.getJsonPathMany(body, "$.data");
        }
        if (CollUtil.isEmpty(rawList)) {
            return items;
        }
        for (String raw : rawList) {
            AccountHistoryItem item = AccountHistoryItem.buildFromToutiao(raw);
            if (StringUtils.isNotBlank(item.getItemId())) {
                items.add(item);
            }
        }
        return items;
    }

    static boolean hasMore(String body) {
        return "true".equalsIgnoreCase(CommonParser.getJsonPathOne(body, "$.has_more"));
    }

    /**
     * 下一页游标：优先 {@code next.max_behot_time}，缺失时取最后一条作品的 {@code behot_time}
     * （头条的 behot_time 正是下一页的 max_behot_time，用它兜底可避免"有下一页但没游标"而卡死翻页）。
     */
    static String nextCursor(String body, List<AccountHistoryItem> items) {
        String fromNext = CommonParser.getJsonPathOne(body, "$.next.max_behot_time");
        if (StringUtils.isNotBlank(fromNext)) {
            return fromNext;
        }
        String tmp = CommonParser.getJsonPathOne(body, "$.next.max_behot_time_tmp");
        if (StringUtils.isNotBlank(tmp)) {
            return tmp;
        }
        if (items != null && !items.isEmpty()) {
            AccountHistoryItem last = items.get(items.size() - 1);
            if (last.getPublishTime() != null) {
                return String.valueOf(last.getPublishTime() / 1000L);
            }
        }
        return null;
    }

    /**
     * 解析账号标识：优先 {@code accountId}，其次 {@code extra.accountId}，最后从主页链接提取；
     * 已经是裸 token（无斜杠、无协议）时原样返回。
     */
    static String resolveToken(AccountHistoryCollectRequest request) {
        String explicit = StringUtils.trimToNull(request.getAccountId());
        if (explicit == null) {
            explicit = request.extraValue(AccountHistoryKeys.ACCOUNT_ID);
        }
        if (StringUtils.isNotBlank(explicit)) {
            String parsed = extractUserToken(explicit);
            return parsed != null ? parsed : explicit;
        }
        return extractUserToken(request.getFromUrl());
    }

    /**
     * 从主页链接（或"链接/token 混合体"）中提取 user token。
     *
     * @return token；无法识别时返回 {@code null}
     */
    public static String extractUserToken(String urlOrToken) {
        if (StringUtils.isBlank(urlOrToken)) {
            return null;
        }
        String value = urlOrToken.trim();
        if (!value.contains("/") && !value.contains("?")) {
            return value;   // 已经是裸 token
        }
        for (Pattern pattern : TOKEN_PATTERNS) {
            Matcher matcher = pattern.matcher(value);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static int clampCount(AccountHistoryCollectRequest request) {
        int count = request.resolveMaxCount(DEFAULT_PAGE_SIZE);
        return Math.min(Math.max(count, 1), MAX_PAGE_SIZE);
    }
}
