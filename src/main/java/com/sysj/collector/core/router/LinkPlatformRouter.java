package com.sysj.collector.core.router;

import com.bewilder.filter.CommonExtractor;
import com.bewilder.weibo.WeiboTool;
import com.sysj.collector.core.provider.Capability;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 评论采集链接自动路由：按链接特征识别所属平台，给出对应的 platformCode / featureCode
 * 与路由能力约束（移植自 auto-task-web 的 {@code CommentUrlRouter}，对接本服务的
 * {@code platform_feature_config} 编码体系）。
 *
 * <p><b>与本服务路由器的关系</b>：本类只做"链接 → 平台"这一步粗分，
 * 平台内选哪个供应商仍由 {@link DynamicProviderRouter} 按偏好/健康/熔断/阈值决定。
 * 平台内存在"本地采集 vs Golaxy 渠道"双版本时（当前仅微博 comment 功能），
 * 由请求是否携带 cookie 转成能力约束表达：
 * <ul>
 *   <li>有 cookie → 要求 {@link Capability#LOGIN_STATE}（依赖登录态的本地采集排到候选，Golaxy 不满足被过滤）；</li>
 *   <li>无 cookie → 排除 {@link Capability#LOGIN_STATE}（本地采集被剔除，直接走 Golaxy，
 *       而不是让它先失败再靠供应商切换兜底 —— 那会白白污染本地供应商的熔断统计）。</li>
 * </ul>
 * 排除语义生效的前提是 {@code platform_feature_config.providers[].capabilities} 如实声明了
 * {@code LOGIN_STATE}（启动时 {@code ProviderCapabilityValidator} 会比对注解与配置的一致性）。
 *
 * <p>规则表顺序敏感：更具体/更长域名的规则放在前面，避免域名包含关系误判（如 x.com、mi.com）；
 * 新增可自动分配的平台时同步维护 {@link #RULES}。
 * 只有本服务已接入的平台才会出现在规则表里；未接入平台的链接返回 null，
 * 由调用方归入"无法识别"并给出原因。
 */
public final class LinkPlatformRouter {

    /** 微博视频链接仅本地采集支持（Golaxy 对 tv/show 等视频链接会解析出乱码 mid），无 cookie 时视为无法识别。 */
    private static final Predicate<String> WEIBO_STATUS_URL =
            url -> StringUtils.isNotBlank(WeiboTool.urlToMid(url))
                    && StringUtils.isBlank(WeiboTool.videoUrlToMid(url));

    private static final Predicate<String> WEIBO_ANY_URL =
            url -> StringUtils.isNotBlank(WeiboTool.urlToMid(url))
                    || StringUtils.isNotBlank(WeiboTool.videoUrlToMid(url));

    private static final Predicate<String> BILI_URL = url -> url.contains("bilibili.com");

    private static final Predicate<String> WECHAT_ARTICLE_URL = url ->
            url.contains("mp.weixin.qq.com/s/") || url.contains("mp.weixin.qq.com/s?__biz=");

    private static final Predicate<String> WECHAT_VIDEO_URL = url ->
            url.contains("mp.weixin.qq.com/recweb/clientjump?feed_id")
                    || url.contains("channels.weixin.qq.com/web/pages/feed?eid")
                    || url.contains("channels.weixin.qq.com/mobile/commonFinderJsApi.html")
                    || url.contains("weixin.qq.com/sph/");

    private static final Predicate<String> XHS_URL =
            url -> StringUtils.isNotBlank(CommonExtractor.extractorXhsNoteId(url));

    private static final Predicate<String> DOUYIN_URL =
            url -> StringUtils.isNotBlank(CommonExtractor.extractorDyMid(url));

    private static final Predicate<String> TOUTIAO_URL =
            url -> StringUtils.isNotBlank(CommonExtractor.extractorToutiaoMid(url));

    // ── 社区类平台（auto-task-web 同款规则，顺序敏感：更具体的放前面） ──────

    private static final Pattern HONOR_TID_PATTERN =
            Pattern.compile("club\\.honor\\.com/cn/thread-([0-9]+)-[0-9]+-[0-9]+[.]html");
    private static final Pattern HUAWEI_TID_PATTERN =
            Pattern.compile("mhwnews/(dynamic|article|video|news|question)/id_(\\d+)");
    private static final Pattern OPPO_TID_PATTERN = Pattern.compile("oppo\\.cn/thread-([0-9]+)");
    private static final Pattern VIVO_TID_PATTERN = Pattern.compile("bbs\\.vivo\\.com\\.cn/newbbs/thread/(\\d+)");
    private static final Pattern TIEBA_KZ_PATTERN = Pattern.compile("(?:tieba|tiebac)\\.baidu\\.com/p/([0-9]+)");

    private static final Predicate<String> HONOR_URL = url -> HONOR_TID_PATTERN.matcher(url).find();
    private static final Predicate<String> HUAWEI_URL = url -> HUAWEI_TID_PATTERN.matcher(url).find();
    private static final Predicate<String> OPPO_URL = url -> OPPO_TID_PATTERN.matcher(url).find();
    private static final Predicate<String> VIVO_URL = url -> VIVO_TID_PATTERN.matcher(url).find();
    private static final Predicate<String> TIEBA_URL = url -> TIEBA_KZ_PATTERN.matcher(url).find();
    private static final Predicate<String> XIAOMI_URL = url ->
            url.contains("miui.com") || url.contains("mi.com");

    /** 单条路由规则：无能力约束的普通平台。 */
    private static RouteRule rule(String platformName, String platformCode, String featureCode,
                                  Predicate<String> matcher) {
        return new RouteRule(platformName, platformCode, featureCode, matcher);
    }

    /**
     * 路由规则表：顺序敏感，前一条命中即返回。
     *
     * <p>只有本服务已接入的平台（providerKey 见接口文档 §2.1）才会出现在这里；
     * auto-task-web 还覆盖荣耀/华为/OPPO/vivo/小米社区、贴吧与海外三平台，本服务暂未接入，不列出。
     */
    private static final List<RouteRule> RULES = List.of(
            rule("微信视频号", "wechat_video", "comment", WECHAT_VIDEO_URL),
            rule("微信", "wechat", "comment", WECHAT_ARTICLE_URL),
            rule("荣耀社区", "honor_bbs", "comment", HONOR_URL),
            rule("华为社区", "huawei_bbs", "comment", HUAWEI_URL),
            rule("OPPO社区", "oppo_bbs", "comment", OPPO_URL),
            rule("vivo社区", "vivo_bbs", "comment", VIVO_URL),
            rule("百度贴吧", "tieba", "comment", TIEBA_URL),
            rule("小米社区", "xiaomi_bbs", "comment", XIAOMI_URL),
            rule("小红书", "xhs", "comment", XHS_URL),
            rule("抖音", "douyin", "comment", DOUYIN_URL),
            rule("今日头条", "toutiao", "comment", TOUTIAO_URL),
            // 微博：平台内存在 local（local_crawler，依赖 cookie）与 golaxy（weibo_official）双供应商
            new RouteRule("微博", "weibo", "comment", WEIBO_ANY_URL,
                    Set.of(Capability.LOGIN_STATE), Set.of(),  // 有 cookie → 走本地采集
                    WEIBO_STATUS_URL,
                    Set.of(), Set.of(Capability.LOGIN_STATE)), // 无 cookie → 只走 Golaxy，且视频链接不支持
            rule("B站", "bilibili", "comment", BILI_URL)
    );

    /**
     * 单条路由规则。
     *
     * <p>微博这类双版本平台提供两组 matcher 与能力约束：
     * 有 cookie 时用 {@code cookieMatcher / cookieRequired / cookieExcluded}，
     * 无 cookie 时用 {@code plainMatcher / plainRequired / plainExcluded}；
     * matcher 必须与对应供应商实现真实可处理的链接范围一致（微博视频链接仅 local 支持即由此表达）。
     */
    private record RouteRule(String platformName, String platformCode, String featureCode,
                             Predicate<String> cookieMatcher,
                             Set<Capability> cookieRequired, Set<Capability> cookieExcluded,
                             Predicate<String> plainMatcher,
                             Set<Capability> plainRequired, Set<Capability> plainExcluded) {

        /** 无双版本平台的规则：单一 matcher，无能力约束。 */
        RouteRule(String platformName, String platformCode, String featureCode, Predicate<String> matcher) {
            this(platformName, platformCode, featureCode, matcher, Set.of(), Set.of(),
                    matcher, Set.of(), Set.of());
        }

        boolean matches(String url, boolean hasCookie) {
            return hasCookie ? cookieMatcher.test(url) : plainMatcher.test(url);
        }

        Route resolve(boolean hasCookie) {
            return new Route(platformName, platformCode, featureCode,
                    hasCookie ? List.copyOf(cookieRequired) : List.copyOf(plainRequired),
                    hasCookie ? List.copyOf(cookieExcluded) : List.copyOf(plainExcluded));
        }
    }

    private LinkPlatformRouter() {
    }

    /**
     * 识别链接所属平台并给出路由结果。
     *
     * @param url       待识别链接（应为 http/https 开头）
     * @param hasCookie 请求是否携带 cookie（决定微博路由到本地采集还是 Golaxy 渠道）
     * @return 路由结果；无法识别时返回 null（含"微博视频链接但未提供 cookie"这种明确不支持的情况）
     */
    public static Route route(String url, boolean hasCookie) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        for (RouteRule rule : RULES) {
            if (rule.matches(url, hasCookie)) {
                return rule.resolve(hasCookie);
            }
        }
        return null;
    }

    /**
     * 路由结果。
     *
     * @param platformName 平台中文名（用于任务命名与结果展示）
     * @param platformCode 平台编码（weibo / wechat / wechat_video / bilibili / douyin / xhs / toutiao）
     * @param featureCode  功能编码（当前全部为 comment；微博 repost 需显式建任务，不走自动分派）
     * @param requiredCapabilities 要求供应商全部具备的能力（空列表不限制）
     * @param excludedCapabilities 要求供应商不具备的能力（空列表不限制）
     */
    public record Route(String platformName, String platformCode, String featureCode,
                        List<Capability> requiredCapabilities, List<Capability> excludedCapabilities) {

        /** 能力约束是否为空（无任何能力要求的普通平台）。 */
        public boolean unconstrained() {
            return requiredCapabilities.isEmpty() && excludedCapabilities.isEmpty();
        }
    }
}
