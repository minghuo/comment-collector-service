package com.sysj.collector.core.provider.xg;

import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.tools.CommonTools;
import com.sysj.collector.constants.XgConstants;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.provider.support.HttpUtilProvider;
import com.sysj.collector.core.provider.support.ProviderUrls;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;
import com.sysj.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 星光（XG）渠道供应商基类：Twitter/Facebook/YouTube 三平台共用同一外部接口形态
 * （{@code GET getComments{Platform}?token=&url=&ctime=}），无翻页、无子评论，
 * 一次返回全部评论，仅 endpoint 与平台编码不同。
 *
 * <p>实现对照 auto-task-web 的 {@code TwitterCrawler} / {@code FacebookCrawler} / {@code YoutubeCrawler}：
 * 内部失败重试 3 次（间隔 1s），上游用 {@code code != 0} 表达失败，
 * 字段解析走 {@link Comment#buildFromXg(String)}（三平台 JSON 同构，共用一个工厂）。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code ctime} —— 帖子发布时间（秒级时间戳，<b>必填</b>，星光接口按发布时间截取评论）</li>
 *   <li>{@code url} —— 帖子链接；缺省回退 {@code request.fromUrl}</li>
 * </ul>
 *
 * <p>认证：token 走 query 参数，配置 {@code collector.provider.xg.api-token}（环境变量 {@code XG_API_TOKEN}）。
 * token 未配置时直接返回参数错误，不发起外部请求。
 */
@Slf4j
public abstract class AbstractXgProvider implements CommentProvider {

    private static final HttpUtil httpUtil = HttpUtilProvider.apiClient();

    @Value("${collector.provider.xg.api-token:}")
    private String apiToken;

    /** 星光接口 endpoint，如 {@code getCommentsTwitter}。 */
    protected abstract String endpoint();

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String url = StringUtils.isNotBlank(extra.get("url")) ? extra.get("url") : request.getFromUrl();
        String ctime = extra.get("ctime");
        log.info("[xg] 采集开始: platform={} url={} ctime={}", endpoint(), url, ctime);

        if (StringUtils.isBlank(url)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_URL_ERROR)
                    .msg("缺少帖子链接（extra.url 或 fromUrl）").build();
        }
        if (StringUtils.isBlank(ctime)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_PARAM)
                    .msg("星光渠道必须提供 ctime（帖子发布时间，秒级时间戳）").build();
        }
        if (StringUtils.isBlank(apiToken)) {
            log.error("[xg] 未配置 collector.provider.xg.api-token，星光评论采集不可用");
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_ERROR)
                    .msg("星光 token 未配置").build();
        }
        String apiUrl = String.format("%s%s?token=%s&url=%s&ctime=%s",
                XgConstants.BASE_URL, endpoint(), ProviderUrls.encodeParam(apiToken),
                ProviderUrls.encodeParam(url), ProviderUrls.encodeParam(ctime));
        return fetch(apiUrl);
    }

    /**
     * 统一的取数 + 解析逻辑：{@code code != 0} 视为上游失败，成功时 {@code data.info} 一次取全。
     *
     * <p><b>只发一次请求</b>：重试归管线 {@code RetryStage}，次数取任务所用平台功能配置的
     * {@code providers[].max_retry}，且每次重试都会重新过 {@code RateLimitStage} 领取令牌 ——
     * 既尊重上游 DPS，也不会让一次 fetch 内的私有重试绕开令牌桶（原 {@code RETRY = 3} 已删除）。
     */
    private CommonEntity<Comment> fetch(String apiUrl) {
        try {
            String body = httpUtil.getString(apiUrl, false);
            if (StringUtils.isNotBlank(body)) {
                String upstreamCodeStr = CommonParser.getJsonPathOne(body, "$.code");
                Integer upstreamCode = CommonTools.stringToInteger(upstreamCodeStr);
                if (upstreamCode == null || upstreamCode != 0) {
                    log.warn("{} 上游返回失败: code={} body={}", providerKey(), upstreamCodeStr,
                            StringUtils.abbreviate(body, 200));
                    return failed("上游失败 code=" + upstreamCodeStr);
                }
                List<String> infoStrList = CommonParser.getJsonPathMany(body, "$.data.info");
                List<Comment> comments = new ArrayList<>();
                if (CollUtil.isNotEmpty(infoStrList)) {
                    for (String infoStr : infoStrList) {
                        comments.add(Comment.buildFromXg(infoStr));
                    }
                }
                return CommonEntity.<Comment>builder()
                        .haseMore(false)
                        .totalPage(1)
                        .status(CommonStatusEnum.STATUS_SUCCESS)
                        .dataList(comments)
                        .build();
            }
            return failed("空响应");
        } catch (Exception e) {
            log.warn("[xg] {} 请求失败: {}", endpoint(), e.getMessage());
            return failed(e.getMessage());
        }
    }

    /** 失败结果：由管线 {@code validate} 统一判定失败，再交给重试/候选切换。 */
    private static CommonEntity<Comment> failed(String message) {
        return CommonEntity.<Comment>builder()
                .haseMore(false)
                .status(CommonStatusEnum.STATUS_ERROR)
                .msg(message)
                .build();
    }
}
