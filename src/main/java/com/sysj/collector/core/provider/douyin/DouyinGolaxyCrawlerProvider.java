package com.sysj.collector.core.provider.douyin;

import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.tools.CommonTools;
import com.sysj.collector.constants.GolaxyConstants;
import com.sysj.collector.core.provider.support.ProviderUrls;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;
import com.sysj.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 抖音 - 中科天玑接口供应商。
 *
 * <p>Bean 名称 = {@code douyin_golaxy}。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code DyCrawler}：
 * 内部失败重试 3 次（每次间隔 1s），页码型翻页，字段解析走 {@link Comment#buildFromGolaxy(String)}。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code mid} —— 视频 id；为空时回退 {@code request.targetId}（必填）</li>
 *   <li>{@code page} —— 页码，从 1 开始（默认 1）</li>
 *   <li>{@code commentId} —— 父评论 id，传了则采子评论</li>
 * </ul>
 */
@Slf4j
@Component("douyin_golaxy")
public class DouyinGolaxyCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().build();

    private static final int RETRY = 3;

    @Override
    public String providerKey() {
        return "douyin_golaxy";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String mid = ProviderUrls.resolveMid(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        String commentId = extra.get("commentId");
        int page = Math.max(1, CommonTools.stringToInteger(extra.get("page")));
        log.info("[dy-golaxy] 采集开始: mid={} commentId={} page={}", mid, commentId, page);

        if (StringUtils.isBlank(mid)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .status(CommonStatusEnum.STATUS_PARAM).build();
        }
        String apiUrl;
        if (StringUtils.isNotBlank(commentId)) {
            apiUrl = String.format("%s/reply/douyin?apiKey=%s&video_id=%s&comment_id=%s&page=%s",
                    GolaxyConstants.COMMENT_BASE_URL, GolaxyConstants.COMMENT_API_KEY, mid, commentId, page);
        } else {
            apiUrl = String.format("%s/douyin?apiKey=%s&video_id=%s&page=%s",
                    GolaxyConstants.COMMENT_BASE_URL, GolaxyConstants.COMMENT_API_KEY, mid, page);
        }
        return fetch(apiUrl, page);
    }

    /**
     * 统一的取数 + 解析逻辑（主评论与子评论共用，仅 URL 不同）。
     */
    private CommonEntity<Comment> fetch(String apiUrl, int page) {
        for (int i = 0; i < RETRY; i++) {
            try {
                String body = httpUtil.getString(apiUrl, false);
                if (StringUtils.isNotBlank(body) && body.contains("comments")) {
                    // 上游用 code != 200 表达失败（如 {"code":500,"message":"获取评论信息失败, 请重试"}），
                    // 此时不能当成"成功但无数据"，否则会被误判为 SUCCESS，既不重试也不切换供应商。
                    String upstreamCodeStr = CommonParser.getJsonPathOne(body, "$.code");
                    if (StringUtils.isNotBlank(upstreamCodeStr)) {
                        Integer upstreamCode = CommonTools.stringToInteger(upstreamCodeStr);
                        if (upstreamCode != null && upstreamCode != 200) {
                            log.warn("{} 上游返回失败: code={} message={}", providerKey(), upstreamCode,
                                    CommonParser.getJsonPathOne(body, "$.message"));
                            CommonTools.sleep(1000);
                            continue;
                        }
                    }
                    boolean hasMore = "1".equals(CommonParser.getJsonPathOne(body, "$.has_more"));
                    Integer totalPage = CommonTools.totalPage(
                            CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.total")), 20);
                    List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.comments");
                    List<Comment> comments = new ArrayList<>();
                    if (CollUtil.isNotEmpty(commentStrList)) {
                        for (String commentStr : commentStrList) {
                            comments.add(Comment.buildFromGolaxy(commentStr));
                        }
                    }
                    return CommonEntity.<Comment>builder()
                            .haseMore(hasMore)
                            .nextUrl(hasMore ? String.valueOf(page + 1) : null)
                            .totalPage(totalPage)
                            .status(CommonStatusEnum.STATUS_SUCCESS)
                            .dataList(comments)
                            .build();
                }
            } catch (Exception e) {
                log.warn("[dy-golaxy] 第 {}/{} 次请求失败: {}", i + 1, RETRY, e.getMessage());
            }
            CommonTools.sleep(1000);
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }
}
