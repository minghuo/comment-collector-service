package com.sysj.collector.core.provider.toutiao;

import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.tools.CommonTools;
import com.sysj.collector.core.provider.support.ProviderUrls;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
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

/**
 * 今日头条 - 本地爬虫供应商。
 *
 * <p>Bean 名称 = {@code toutiao_local}。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code ToutiaoCrawler} 与 {@code TtComment.build}。
 * 字段解析统一收敛到 {@link Comment#buildFromToutiao(String)}，
 * 其中修复了原实现用非标准 JSONPath {@code "$.reply_count|$.forward_count"} 导致回复数恒为空的问题。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code mid} —— 文章 groupId；为空时回退 {@code request.targetId}（必填）</li>
 *   <li>{@code page} —— 页码，从 1 开始（默认 1）</li>
 *   <li>{@code commentId} —— 父评论 id，传了则采该评论的回复</li>
 * </ul>
 */
@ProviderCapability({ Capability.COMMENT, Capability.SUB_COMMENT, Capability.PAGE_PAGING, Capability.PROXY, Capability.SYNC_SUPPORTED })
@Slf4j
@Component("toutiao_local")
public class TouTiaoLocalCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().directFallbackOnProxyFailure(true).build();

    private static final String UA = "News 7.7.3 rv:7.7.3.21 (iPhone; iOS 12.3.1; zh_CN) Cronet";
    private static final int PAGE_SIZE = 20;

    @Override
    public String providerKey() {
        return "toutiao_local";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String mid = ProviderUrls.resolveMid(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        String commentId = extra.get("commentId");
        int page = Math.max(1, CommonTools.stringToInteger(extra.get("page")));
        log.info("[toutiao-local] 采集开始: mid={} commentId={} page={}", mid, commentId, page);

        if (StringUtils.isBlank(commentId)) {
            return getComment(mid, page);
        }
        return getCommentChild(commentId, page);
    }

    /**
     * 获取今日头条文章评论。
     */
    private CommonEntity<Comment> getComment(String groupId, int page) {
        String apiUrl = "https://www.toutiao.com/article/v2/tab_comments/?aid=24&app_name=toutiao_web&offset="
                + (page - 1) * PAGE_SIZE + "&count=" + PAGE_SIZE
                + "&_signature=&group_id=" + groupId + "&item_id=" + groupId;
        Map<String, String> headMap = new HashMap<>();
        headMap.put("User-Agent", UA);
        try {
            List<Comment> comments = new ArrayList<>();
            boolean hasMore = false;
            String body = httpUtil.getString(apiUrl, headMap);
            if (StringUtils.isNotBlank(body) && body.contains("data")) {
                List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.data.comment");
                if (CollUtil.isNotEmpty(commentStrList)) {
                    for (String commentStr : commentStrList) {
                        comments.add(Comment.buildFromToutiao(commentStr));
                    }
                }
                hasMore = "true".equals(CommonParser.getJsonPathOne(body, "$.has_more"));
            }
            return CommonEntity.<Comment>builder()
                    .haseMore(hasMore)
                    .nextUrl(hasMore ? String.valueOf(page + 1) : null)
                    .status(CommonStatusEnum.STATUS_SUCCESS)
                    .dataList(comments)
                    .build();
        } catch (Exception e) {
            log.warn("[toutiao-local] 文章评论采集出错: groupId={} page={} error={}", groupId, page, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    /**
     * 获取今日头条评论的回复。
     */
    private CommonEntity<Comment> getCommentChild(String commentId, int page) {
        String apiUrl = "https://www.toutiao.com/2/comment/v2/reply_list/?aid=24&app_name=toutiao_web&id="
                + commentId + "&offset=" + (page - 1) * PAGE_SIZE + "&count=" + PAGE_SIZE + "&repost=0&_signature=_";
        Map<String, String> headMap = new HashMap<>();
        headMap.put("User-Agent", UA);
        try {
            List<Comment> comments = new ArrayList<>();
            boolean hasMore = false;
            String body = httpUtil.getString(apiUrl, headMap);
            if (StringUtils.isNotBlank(body) && body.contains("data")) {
                List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.data");
                for (String commentStr : commentStrList) {
                    Comment childComment = Comment.buildFromToutiao(commentStr);
                    childComment.setParentCommentId(commentId);
                    comments.add(childComment);
                }
                hasMore = "true".equals(CommonParser.getJsonPathOne(body, "$.data.has_more"));
            }
            return CommonEntity.<Comment>builder()
                    .haseMore(hasMore)
                    .nextUrl(hasMore ? String.valueOf(page + 1) : null)
                    .status(CommonStatusEnum.STATUS_SUCCESS)
                    .dataList(comments)
                    .build();
        } catch (Exception e) {
            log.warn("[toutiao-local] 评论回复采集出错: commentId={} page={} error={}", commentId, page, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }
}
