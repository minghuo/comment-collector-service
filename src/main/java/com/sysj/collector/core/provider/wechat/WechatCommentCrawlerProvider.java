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
import okhttp3.HttpUrl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 微信公众号 - 文章评论供应商（系统接口）。
 *
 * <p>Bean 名称 = {@code wechat_sy}。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code WechatSyCrawler.getArticleComment}：
 * 调用 {@code api_hub/api/v1/wechat-mp/article/comments}，用 {@code data.pagination.buffer} 作为翻页游标，
 * 失败重试 3 次（每次间隔 1s）。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code url} —— 文章永久链接；为空时回退 {@code request.targetId}（必填）</li>
 *   <li>{@code buffer} —— 上一页返回的分页游标，首次不传</li>
 *   <li>{@code commentId} —— 父评论 id，传了则采该评论的回复</li>
 * </ul>
 *
 * <p>注意：只接受<b>永久链接</b>（{@code mp.weixin.qq.com/s/xxx} 或带 32 位 {@code sn} 的
 * {@code mp.weixin.qq.com/s?__biz=...}），临时链接无法采到评论。
 */
@Slf4j
@Component("wechat_sy")
public class WechatCommentCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().build();

    private static final String COMMENT_URL = "api/v1/wechat-mp/article/comments";
    private static final int RETRY = 3;
    /** 单页条数（接口固定 20），用于判断是否还有下一页。 */
    private static final int PAGE_SIZE = 20;

    @Override
    public String providerKey() {
        return "wechat_sy";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String url = StringUtils.defaultIfBlank(extra.get("url"), request.getTargetId());
        String commentId = extra.get("commentId");
        String buffer = extra.get("buffer");

        if (StringUtils.isBlank(url)) {
            throw new CollectorException("微信评论采集缺少文章 url（extra.url 或 targetId 至少提供一个）");
        }
        if (!checkLongArticleUrl(url)) {
            log.warn("[wechat-sy] 非永久链接，无法采集评论: {}", url);
            return CommonEntity.<Comment>builder()
                    .haseMore(false)
                    .status(CommonStatusEnum.STATUS_URL_ERROR)
                    .build();
        }
        String normalized = url.startsWith("http://") ? url.replace("http://", "https://") : url;
        log.info("[wechat-sy] 文章评论采集: url={} commentId={} buffer={}", normalized, commentId, buffer);

        String base = SyConstants.BASE_URL + COMMENT_URL + "?url=" + URLCodeUtil.getURLEncode(normalized);
        for (int i = 0; i < RETRY; i++) {
            try {
                String apiUrl = base;
                if (StringUtils.isNotBlank(buffer)) {
                    apiUrl += "&buffer=" + buffer;
                }
                if (StringUtils.isNotBlank(commentId)) {
                    apiUrl += "&comment_id=" + commentId;
                }
                String body = httpUtil.getString(apiUrl, ProviderHeaders.syHeaders(), false);
                Integer code = CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.code"));
                if (code == 200) {
                    List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.data.elected_comments");
                    String nextBuffer = CommonParser.getJsonPathOne(body, "$.data.pagination.buffer");
                    List<Comment> comments = new ArrayList<>();
                    for (String commentStr : commentStrList) {
                        comments.add(Comment.buildFromWechat(commentStr, commentId));
                    }
                    boolean hasMore = comments.size() >= PAGE_SIZE && StringUtils.isNotBlank(nextBuffer);
                    return CommonEntity.<Comment>builder()
                            .haseMore(hasMore)
                            .nextUrl(nextBuffer)
                            .status(CommonStatusEnum.STATUS_SUCCESS)
                            .dataList(comments)
                            .build();
                }
            } catch (Exception e) {
                CommonTools.sleep(1000);
                log.warn("[wechat-sy] 第 {}/{} 次获取文章评论失败: url={} error={}", i + 1, RETRY, normalized, e.getMessage());
            }
        }
        return CommonEntity.<Comment>builder()
                .haseMore(false)
                .status(CommonStatusEnum.STATUS_ERROR)
                .build();
    }

    /**
     * 校验是否为永久链接（对照 auto-task-web 的 {@code WechatSyCrawler.checkLongArticleUrl}）。
     */
    private boolean checkLongArticleUrl(String url) {
        if (Objects.isNull(url) || url.length() > 500) {
            return false;
        }
        try {
            HttpUrl httpUrl = HttpUrl.get(url);
            String sn = httpUrl.queryParameter("sn");
            if (url.contains("mp.weixin.qq.com/s/")) {
                return true;
            }
            return url.contains("mp.weixin.qq.com/s?__biz=") && StringUtils.isNotBlank(sn) && sn.length() == 32;
        } catch (Exception e) {
            log.warn("[wechat-sy] 文章 URL 不合法: {}", url);
            return false;
        }
    }
}
