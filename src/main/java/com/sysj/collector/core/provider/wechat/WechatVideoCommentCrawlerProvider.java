package com.sysj.collector.core.provider.wechat;

import com.bewilder.parser.CommonParser;
import com.bewilder.tools.CommonTools;
import com.bewilder.tools.URLCodeUtil;
import com.sysj.collector.constants.SyConstants;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.provider.support.ProviderHeaders;
import com.sysj.collector.exception.CollectorException;
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
 * 微信视频号 - 视频评论供应商（系统接口）。
 *
 * <p>Bean 名称 = {@code wechat_video_sy}。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code WechatVideoSyCrawler.getArticleComment}：
 * 调用 {@code api_hub/api/v1/wechat-channels/video/comments}，用 {@code data.last_buffer} 作为翻页游标，
 * {@code data.has_more} 判断是否还有下一页，失败重试 3 次（每次间隔 1s）。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code videoId} —— 视频 id；为空时回退 {@code request.targetId}（必填）</li>
 *   <li>{@code buffer} —— 上一页返回的 {@code last_buffer}，首次不传</li>
 *   <li>{@code commentId} —— 父评论 id，传了则采该评论的回复</li>
 * </ul>
 */
@Slf4j
@Component("wechat_video_sy")
public class WechatVideoCommentCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().build();

    private static final String COMMENT_URL = "api/v1/wechat-channels/video/comments";
    private static final int RETRY = 3;
    private static final int PAGE_SIZE = 20;

    @Override
    public String providerKey() {
        return "wechat_video_sy";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String videoId = StringUtils.defaultIfBlank(extra.get("videoId"), request.getTargetId());
        String commentId = extra.get("commentId");
        String buffer = extra.get("buffer");

        if (StringUtils.isBlank(videoId)) {
            throw new CollectorException("视频号评论采集缺少 videoId（extra.videoId 或 targetId 至少提供一个）");
        }
        log.info("[wechat-video-sy] 视频评论采集: videoId={} commentId={} buffer={}", videoId, commentId, buffer);

        String base = SyConstants.BASE_URL + COMMENT_URL + "?video_id=" + videoId;
        for (int i = 0; i < RETRY; i++) {
            try {
                String apiUrl = base;
                if (StringUtils.isNotBlank(buffer)) {
                    apiUrl += "&last_buffer=" + URLCodeUtil.getURLEncode(buffer);
                }
                if (StringUtils.isNotBlank(commentId)) {
                    apiUrl += "&comment_id=" + commentId;
                }
                String body = httpUtil.getString(apiUrl, ProviderHeaders.syHeaders(), false);
                Integer code = CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.code"));
                if (code == 200) {
                    List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.data.comments");
                    String nextBuffer = CommonParser.getJsonPathOne(body, "$.data.last_buffer");
                    String hasMoreStr = CommonParser.getJsonPathOne(body, "$.data.has_more");
                    List<Comment> comments = new ArrayList<>();
                    for (String commentStr : commentStrList) {
                        comments.add(Comment.buildFromWechatVideo(commentStr, commentId));
                    }
                    boolean hasMore = "true".equals(hasMoreStr)
                            || (comments.size() >= PAGE_SIZE && StringUtils.isNotBlank(nextBuffer));
                    return CommonEntity.<Comment>builder()
                            .haseMore(hasMore)
                            .nextUrl(nextBuffer)
                            .status(CommonStatusEnum.STATUS_SUCCESS)
                            .dataList(comments)
                            .build();
                }
            } catch (Exception e) {
                CommonTools.sleep(1000);
                log.warn("[wechat-video-sy] 第 {}/{} 次获取视频评论失败: videoId={} error={}", i + 1, RETRY, videoId, e.getMessage());
            }
        }
        return CommonEntity.<Comment>builder()
                .haseMore(false)
                .status(CommonStatusEnum.STATUS_ERROR)
                .build();
    }
}
