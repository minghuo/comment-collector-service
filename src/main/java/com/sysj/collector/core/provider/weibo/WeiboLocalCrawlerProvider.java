package com.sysj.collector.core.provider.weibo;

import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
import com.sysj.collector.core.provider.support.ProviderHeaders;
import com.sysj.collector.exception.CollectorException;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;
import com.sysj.collector.core.provider.support.ProviderUrls;
import com.sysj.collector.core.provider.support.HttpUtilProvider;
import com.sysj.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 微博 - 网页版评论本地爬虫。
 *
 * <p>Bean 名称 = {@code weibo_local}（与 {@code platform_feature_config} 中的 providerKey 一致）。
 * 旧名 {@code local_crawler} 是首个本地供应商遗留的通用名，接入抖音/头条等本地供应商后已按
 * 「平台_形态」统一命名。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code WeiboCrawler.getComment}：
 * 调用 {@code weibo.com/ajax/statuses/buildComments}，以返回的 {@code max_id} 作为翻页游标，
 * 内部失败重试 3 次（与框架层的"供应商切换级重试"职责区分开）。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code mid} —— 微博 id；为空时回退 {@code request.targetId}（必填）</li>
 *   <li>{@code uid} —— 微博作者 uid（接口要求，可空）</li>
 *   <li>{@code cookie} —— 微博登录 Cookie，<b>必填</b>（同时用于提取 XSRF-TOKEN）</li>
 *   <li>{@code sort} —— 排序：{@code hot}（默认）/ {@code time}</li>
 *   <li>{@code maxId} —— 上一页返回的游标，首次不传</li>
 *   <li>{@code replay} —— {@code 1}/{@code true} 时采二级评论（作为父评论 id 归属）</li>
 * </ul>
 *
 * <h3>链接形态（2026-10-26 增补，由 {@link WeiboUrlParser} 分派）</h3>
 * <ul>
 *   <li>普通状态链接（weibo.com/{uid}/{b62}、m.weibo.cn/status|detail/{mid|b62}）——直接解析 mid/uid</li>
 *   <li>视频链接（tv/show/{oid}、video.weibo.com?fid=、m.weibo.cn/video/{oid}）——
 *       经 h5.video.weibo.com 组件接口把 oid 换算为关联微博的 mid/uid（对照 auto-task-web getWeiboVideoInfo）</li>
 *   <li>头条文章链接（ttarticle/p/show?id=、ttarticle?p=、card.weibo.com/article/…/id/…）——
 *       文章页 HTML 内嵌评论区容器配置（window.createApp 挂 "#commonts_container" 容器，config 含 uid 与 mid），
 *       从中提取关联微博的 mid/uid；文章未开通评论/无关联微博时按"帖子级终态"处理</li>
 * </ul>
 *
 * <h3>返回</h3>
 * {@code haseMore} 表示还有下一页，{@code nextUrl} 为下一页 {@code maxId}（调用方回填到 {@code extra.maxId} 继续翻页）。
 */
@ProviderCapability({ Capability.COMMENT, Capability.CURSOR_PAGING, Capability.LOGIN_STATE, Capability.PROXY, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "weibo", feature = "comment", name = "微博-本地爬虫")
@Slf4j
@Component("weibo_local")
public class WeiboLocalCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = HttpUtilProvider.localClient();

    private static final String API_URL = "https://weibo.com/ajax/statuses/buildComments";
    /** 视频组件接口：tv/show 的 oid 换算为关联微博的 mid/uid（对照 auto-task-web 的 getWeiboVideoInfo） */
    private static final String VIDEO_COMPONENT_API = "https://h5.video.weibo.com/api/component";

    @Override
    public String providerKey() {
        return "weibo_local";
    }

    // ──────────────────────────────────────────────────────────────────────
    // 视频 / 文章链接 → 关联微博 mid/uid 换算
    // ──────────────────────────────────────────────────────────────────────

    /** 文章页评论区容器的 DOM id（微博源码的拼写即 commonts，非 comments） */
    private static final String ARTICLE_COMMENT_CONTAINER = "commonts_container";
    /** 文章页容器配置内嵌的 uid/mid（JS 对象字面量，键无引号：uid: "..." 与 mid: "..."） */
    private static final java.util.regex.Pattern ARTICLE_CFG_UID =
            java.util.regex.Pattern.compile("uid\\s*:\\s*\"(\\d{5,15})\"");
    private static final java.util.regex.Pattern ARTICLE_CFG_MID =
            java.util.regex.Pattern.compile("mid\\s*:\\s*\"(\\d{15,20})\"");

    /**
     * 头条文章链接 → 关联微博 mid/uid：请求文章页 HTML，提取
     * {@code window.createApp("#commonts_container", {config:{uid,mid}})} 配置。
     *
     * @return [mid, uid, error]：error 非空表示帖子级终态（文章不存在/未开通评论），mid/uid 为 null
     */
    private String[] resolveArticleTarget(String url) {
        String[] result = {null, null, null};
        String articleUrl = normalizeArticleUrl(url);
        if (StringUtils.isBlank(articleUrl)) {
            result[2] = "无法从文章链接提取文章 id";
            return result;
        }
        try {
            Map<String, String> headers = new java.util.HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
            String html = httpUtil.getString(articleUrl, headers);
            if (StringUtils.isBlank(html) || !html.contains(ARTICLE_COMMENT_CONTAINER)) {
                result[2] = "文章不存在或未开通评论区（无关联微博）";
                return result;
            }
            // 容器配置在容器 id 出现位置附近，截取后续片段再匹配，避免命中页面其它 uid/mid
            int idx = html.indexOf(ARTICLE_COMMENT_CONTAINER);
            String cfg = html.substring(idx, Math.min(html.length(), idx + 600));
            java.util.regex.Matcher uidMatcher = ARTICLE_CFG_UID.matcher(cfg);
            java.util.regex.Matcher midMatcher = ARTICLE_CFG_MID.matcher(cfg);
            if (uidMatcher.find() && midMatcher.find()) {
                result[0] = midMatcher.group(1);
                result[1] = uidMatcher.group(1);
                log.info("[weibo-local] 文章链接换算: articleUrl={} → mid={} uid={}", articleUrl, result[0], result[1]);
                return result;
            }
            result[2] = "文章页未内嵌评论区配置（可能未开通评论）";
        } catch (Exception e) {
            log.warn("[weibo-local] 文章页解析失败: url={}, {}", articleUrl, e.getMessage());
            result[2] = "文章页解析失败: " + e.getMessage();
        }
        return result;
    }

    /** 文章链接归一化为 PC 端 show 页（手机端 ttarticle?p= / card 页都能换成该形态） */
    private static String normalizeArticleUrl(String url) {
        String articleId = WeiboUrlParser.extractArticleId(url);
        return StringUtils.isBlank(articleId) ? null
                : "https://weibo.com/ttarticle/p/show?id=" + articleId;
    }

    /**
     * 视频链接 → 关联微博 mid/uid：POST h5.video.weibo.com 组件接口
     * （{@code data={"Component_Play_Playinfo":{"oid":"1034:xxx"}}}），
     * 失败语义对照 auto-task-web：无权限/已删除视为帖子级终态。
     *
     * @return [mid, uid, error]：error 非空表示帖子级终态或换算失败
     */
    private String[] resolveVideoTarget(String url) {
        String[] result = {null, null, null};
        String oid = WeiboUrlParser.extractVideoOid(url);
        if (StringUtils.isBlank(oid)) {
            result[2] = "无法从视频链接提取 oid";
            return result;
        }
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("data", "{\"Component_Play_Playinfo\":{\"oid\":\"" + oid + "\"}}");
            Map<String, String> headers = new java.util.HashMap<>();
            headers.put("referer", "https://h5.video.weibo.com");
            String body = httpUtil.postFormString(VIDEO_COMPONENT_API, params, headers);
            if (StringUtils.isNotBlank(body) && body.contains("Component_Play_Playinfo")) {
                if (body.contains("暂无查看权限") || body.contains("没有权限查看此微博") || !body.contains("created_at")) {
                    result[2] = "视频不存在或无查看权限";
                    return result;
                }
                String info = CommonParser.getJsonPathOne(body, "$.data.Component_Play_Playinfo");
                result[0] = CommonParser.getJsonPathOne(info, "$.mid");
                result[1] = CommonParser.getJsonPathOne(info, "$.user.id");
                if (StringUtils.isNotBlank(result[0])) {
                    log.info("[weibo-local] 视频链接换算: url={} oid={} → mid={} uid={}", url, oid, result[0], result[1]);
                    return result;
                }
            }
            result[2] = "视频信息换算失败（接口无有效返回）";
        } catch (Exception e) {
            log.warn("[weibo-local] 视频信息换算失败: url={}, {}", url, e.getMessage());
            result[2] = "视频信息换算失败: " + e.getMessage();
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String fromUrl = request.getFromUrl();
        // 链接类型分派：视频（tv/show 等）/ 文章（ttarticle 等）的 id 都不是微博 mid，
        // 须经对应接口换算；普通状态链接由 WeiboUrlParser 直接解析（详见各方法注释）
        String mid;
        String uid;
        if (StringUtils.isBlank(extra.get("mid")) && WeiboUrlParser.isArticleUrl(fromUrl)) {
            // 种子解析（文章页 → mid）：内层重试 + 退避，理由见 resolveSeedWithRetry
            String[] articleTarget = resolveSeedWithRetry("文章页", () -> resolveArticleTarget(fromUrl));
            mid = articleTarget[0];
            uid = articleTarget[1];
            String error = articleTarget[2];
            if (StringUtils.isNotBlank(error)) {
                return CommonEntity.<Comment>builder().haseMore(false)
                        .status(CommonStatusEnum.STATUS_DELETE).msg(error).build();
            }
        } else if (StringUtils.isBlank(extra.get("mid")) && WeiboUrlParser.isVideoUrl(fromUrl)) {
            // 种子解析（视频组件接口 → mid）：内层重试 + 退避，理由见 resolveSeedWithRetry
            String[] videoTarget = resolveSeedWithRetry("视频组件", () -> resolveVideoTarget(fromUrl));
            mid = videoTarget[0];
            uid = videoTarget[1];
            String error = videoTarget[2];
            if (StringUtils.isNotBlank(error)) {
                return CommonEntity.<Comment>builder().haseMore(false)
                        .status(CommonStatusEnum.STATUS_DELETE).msg(error).build();
            }
        } else {
            // mid 优先取显式传入，其次从微博链接解析（详见 WeiboUrlParser）
            mid = WeiboUrlParser.resolveMid(extra.get("mid"), fromUrl, request.getTargetId());
            uid = WeiboUrlParser.resolveUid(extra.get("uid"), fromUrl);
        }
        String cookie = extra.get("cookie");
        String sort = StringUtils.defaultIfBlank(extra.get("sort"), "hot");
        String maxId = extra.get("maxId");
        boolean replay = "1".equals(extra.get("replay")) || "true".equalsIgnoreCase(extra.get("replay"));

        if (StringUtils.isBlank(mid)) {
            throw new CollectorException("微博评论采集缺少 mid（extra.mid 或 targetId 至少提供一个）");
        }
        if (StringUtils.isBlank(cookie)) {
            throw new CollectorException("微博评论采集缺少 Cookie（extra.cookie），微博接口必须登录态");
        }
        log.info("[weibo-local] 评论采集: mid={} uid={} sort={} maxId={} replay={}", mid, uid, sort, maxId, replay);

        int isMix = replay ? 1 : 0;
        int fetchLevel = replay ? 1 : 0;
        String parentCommentId = replay ? mid : null;

        // 修 R8：sort/mid/uid/maxId 都来自调用方 extra，必须编码后拼 query
        // （isMix/fetchLevel 是本类算出的 int，无需编码）
        StringBuilder apiUrl = new StringBuilder(API_URL)
                .append("?flow=").append(ProviderUrls.encodeParam(sort))
                .append("&is_reload=1&id=").append(ProviderUrls.encodeParam(mid))
                .append("&is_show_bulletin=2&is_mix=").append(isMix)
                .append("&count=20&uid=").append(uid == null ? "" : ProviderUrls.encodeParam(uid))
                .append("&fetch_level=").append(fetchLevel)
                .append("&locale=zh-CN");
        if (StringUtils.isNotBlank(maxId)) {
            apiUrl.append("&max_id=").append(ProviderUrls.encodeParam(maxId));
        }

        String url = apiUrl.toString();
        Map<String, String> headers = ProviderHeaders.weiboHeaders(cookie);

        // 只发一次请求：重试归管线 RetryStage（providers[].max_retry 来自任务所用平台功能配置，
        // 且每次重试重新过 RateLimitStage 领令牌），原 RETRY=3 的内层重试会绕开令牌桶、白耗 DPS。
        try {
            String body = httpUtil.getString(url, headers);
            if (StringUtils.isNotBlank(body) && body.contains("max_id")) {
                String nextMaxId = CommonParser.getJsonPathOne(body, "$.max_id");
                boolean hasMore = StringUtils.isNotBlank(nextMaxId) && !"0".equals(nextMaxId);
                List<String> dataStrList = CommonParser.getJsonPathMany(body, "$.data");
                List<Comment> comments = new ArrayList<>();
                if (CollUtil.isNotEmpty(dataStrList)) {
                    for (String dataStr : dataStrList) {
                        comments.add(Comment.buildFromWeiboLocal(dataStr, parentCommentId));
                    }
                }
                return CommonEntity.<Comment>builder()
                        .haseMore(hasMore)
                        .nextUrl(nextMaxId)
                        .status(CommonStatusEnum.STATUS_SUCCESS)
                        .dataList(comments)
                        .build();
            }
            log.warn("[weibo-local] 响应不含 max_id: mid={}", mid);
            return CommonEntity.<Comment>builder()
                    .haseMore(false)
                    .status(CommonStatusEnum.STATUS_ERROR)
                    .msg("响应不含 max_id")
                    .build();
        } catch (Exception e) {
            log.warn("[weibo-local] 请求失败: {}", e.getMessage());
            return CommonEntity.<Comment>builder()
                    .haseMore(false)
                    .status(CommonStatusEnum.STATUS_ERROR)
                    .msg(e.getMessage())
                    .build();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 种子解析（外取 mid/uid）重试：此处刻意保留内层重试 + sleep
    // ──────────────────────────────────────────────────────────────────────
    //
    // 与"主采集请求只发一次、重试归管线 RetryStage"的约定**有意不同**：
    //   · 种子请求（文章页 HTML / 视频组件接口）只发生在一次采集的**首轮**，每轮最多 1~2 次；
    //   · 种子拿不到 ⇒ 整条帖子的评论直接采不到，用掉一次管线重试也未必能拿到，
    //     所以在这里就地重试 + 退避，成功率收益远大于 DPS 影响；
    //   · 主采集请求仍只走管线，不绕令牌桶，DPS 控制不受影响。

    /** 种子解析最大尝试次数（首试 + 2 次重试）。 */
    private static final int SEED_MAX_ATTEMPTS = 3;

    /** 种子解析首次重试前的等待毫秒数，之后每次翻倍（1s → 2s）。 */
    private static final long SEED_RETRY_BASE_MS = 1000L;

    /** 种子解析单次尝试的短暂停顿，避免连续两次请求贴得太近。 */
    private static final long SEED_REQUEST_GAP_MS = 300L;

    /**
     * 带重试的种子解析：{@code resolver} 返回 {@code [mid, uid, error]}。
     *
     * <p>终态错误（文章/视频不存在、已删除、未开通评论）**不重试** —— 重试只会白耗请求；
     * 只有"接口没返回 / 解析不出 / 网络异常"这类瞬时失败才会退避重试。
     *
     * @param what     日志用的描述，如 {@code "文章页"} / {@code "视频组件"}
     * @param resolver 实际解析逻辑
     * @return 最后一次尝试的结果；中途成功则直接返回成功结果
     */
    private String[] resolveSeedWithRetry(String what, java.util.function.Supplier<String[]> resolver) {
        String[] result = {null, null, null};
        for (int attempt = 1; attempt <= SEED_MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                long delay = SEED_RETRY_BASE_MS << (attempt - 2);
                log.warn("[weibo-local] {} 种子解析失败，{}ms 后重试（第 {}/{} 次）: {}",
                        what, delay, attempt, SEED_MAX_ATTEMPTS, result[2]);
                sleepQuietly(delay);
            }
            result = resolver.get();
            if (StringUtils.isNotBlank(result[0])) {
                if (attempt > 1) {
                    log.info("[weibo-local] {} 种子解析在第 {} 次尝试成功", what, attempt);
                }
                return result;
            }
            if (isTerminalSeedError(result[2])) {
                log.info("[weibo-local] {} 种子解析为终态错误，不重试: {}", what, result[2]);
                return result;
            }
            sleepQuietly(SEED_REQUEST_GAP_MS);
        }
        log.warn("[weibo-local] {} 种子解析 {} 次后仍失败: {}", what, SEED_MAX_ATTEMPTS, result[2]);
        return result;
    }

    /** 帖子级终态：重试不会改变结果。 */
    private static boolean isTerminalSeedError(String error) {
        if (StringUtils.isBlank(error)) {
            return false;
        }
        return error.contains("不存在") || error.contains("已删除") || error.contains("无查看权限")
                || error.contains("没有权限") || error.contains("未开通评论");
    }

    /** 静默 sleep：被中断时恢复中断标志直接返回，由调用方继续走失败分支。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
