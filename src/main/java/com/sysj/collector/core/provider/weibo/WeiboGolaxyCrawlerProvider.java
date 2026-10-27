package com.sysj.collector.core.provider.weibo;

import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.tools.CommonTools;
import com.sysj.collector.constants.GolaxyConstants;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
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
 * 微博 - 中科天玑（Golaxy）评论接口供应商。
 *
 * <p>Bean 名称 = {@code weibo_official}（沿用既有配置，勿改，否则需同步修改 DB 中的 providerKey）。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code WeiboGolaxyCrawler}：
 * 内部失败重试 3 次（每次间隔 1s），游标型翻页，
 * 字段解析走 {@link Comment#buildFromWeiboGolaxy(String)}（比通用 Golaxy 版多出用户扩展字段）。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code mid} —— 微博 id；为空时回退 {@code request.targetId}（必填）</li>
 *   <li>{@code cursor} —— 上一页返回的游标，首次不传</li>
 *   <li>{@code commentId} —— 父评论 id，传了则采子评论</li>
 * </ul>
 */
@ProviderCapability({ Capability.COMMENT, Capability.SUB_COMMENT, Capability.CURSOR_PAGING, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "weibo", feature = "comment", name = "微博-中科天玑接口")
@Slf4j
@Component("weibo_official")
public class WeiboGolaxyCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = HttpUtilProvider.apiClient();

    @Override
    public String providerKey() {
        return "weibo_official";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String mid = WeiboUrlParser.resolveMid(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        String commentId = extra.get("commentId");
        String cursor = extra.get("cursor");
        log.info("[weibo-golaxy] 采集开始: mid={} commentId={} cursor={}", mid, commentId, cursor);

        if (StringUtils.isBlank(mid)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_PARAM).build();
        }
        // 修 R8：mid/commentId/cursor 来自调用方 extra 或上游游标，必须 URL 编码后再拼 query
        String apiUrl;
        if (StringUtils.isNotBlank(commentId)) {
            apiUrl = String.format("%s/reply/weibo/v2?apiKey=%s&video_id=%s&comment_id=%s",
                    GolaxyConstants.COMMENT_BASE_URL, GolaxyConstants.COMMENT_API_KEY,
                    ProviderUrls.encodeParam(mid), ProviderUrls.encodeParam(commentId));
        } else {
            apiUrl = String.format("%s/weibo?apiKey=%s&video_id=%s",
                    GolaxyConstants.COMMENT_BASE_URL, GolaxyConstants.COMMENT_API_KEY,
                    ProviderUrls.encodeParam(mid));
        }
        if (StringUtils.isNotBlank(cursor)) {
            apiUrl = apiUrl + "&cursor=" + ProviderUrls.encodeParam(cursor);
        }
        return fetch(apiUrl);
    }

    /**
     * 统一的取数 + 解析逻辑（主评论与子评论共用，仅 URL 不同）。
     *
     * <p><b>只发一次请求</b>：重试归管线 {@code RetryStage}，次数取任务所用平台功能配置的
     * {@code providers[].max_retry}，且每次重试都会重新过 {@code RateLimitStage} 领取令牌 ——
     * 既尊重上游 DPS，也不会让一次 fetch 内的私有重试绕开令牌桶（原 {@code RETRY = 3} 已删除）。
     */
    private CommonEntity<Comment> fetch(String apiUrl) {
        try {
            String body = httpUtil.getString(apiUrl, false);
            if (StringUtils.isNotBlank(body) && body.contains("comments")) {
                // 上游用 code != 200 表达失败（如 {"code":500,"message":"获取评论信息失败, 请重试"}），
                // 此时不能当成"成功但无数据"，否则会被误判为 SUCCESS，既不重试也不切换供应商。
                String upstreamCodeStr = CommonParser.getJsonPathOne(body, "$.code");
                if (StringUtils.isNotBlank(upstreamCodeStr)) {
                    Integer upstreamCode = CommonTools.stringToInteger(upstreamCodeStr);
                    if (upstreamCode != null && upstreamCode != 200) {
                        String message = CommonParser.getJsonPathOne(body, "$.message");
                        log.warn("{} 上游返回失败: code={} message={}", providerKey(), upstreamCode, message);
                        return failed("上游失败 code=" + upstreamCode);
                    }
                }
                String nextCursor = CommonParser.getJsonPathOne(body, "$.cursor");
                boolean hasMore = "1".equals(CommonParser.getJsonPathOne(body, "$.has_more"));
                Integer totalPage = CommonTools.totalPage(
                        CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.total")), 20);
                List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.comments");
                List<Comment> comments = new ArrayList<>();
                if (CollUtil.isNotEmpty(commentStrList)) {
                    for (String commentStr : commentStrList) {
                        comments.add(Comment.buildFromWeiboGolaxy(commentStr));
                    }
                }
                return CommonEntity.<Comment>builder()
                        .haseMore(hasMore)
                        .nextUrl(nextCursor)
                        .totalPage(totalPage)
                        .status(CommonStatusEnum.STATUS_SUCCESS)
                        .dataList(comments)
                        .build();
            }
            return failed("响应不含 comments 字段");
        } catch (Exception e) {
            log.warn("[weibo-golaxy] 请求失败: {}", e.getMessage());
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
