package com.sysj.collector.core.provider.weibo;

import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.tools.CommonTools;
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
 * 微博 - 转发列表本地爬虫。
 *
 * <p>Bean 名称 = {@code weibo_repost_local}。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code WeiboCrawler.getRepostList} 与 {@code WeiboRepost.build}：
 * 调用 {@code weibo.com/ajax/statuses/repostTimeline}，用 {@code page} + {@code max_page} 判断是否有下一页，
 * 并把 {@code next_cursor} 作为下一页游标返回。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code mid} —— 微博 id；为空时回退 {@code request.targetId}（必填）</li>
 *   <li>{@code cookie} —— 微博登录 Cookie，<b>必填</b></li>
 *   <li>{@code page} —— 页码，从 1 开始（默认 1）</li>
 *   <li>{@code nextId} —— 上一页返回的 {@code next_cursor}（首次不传）</li>
 * </ul>
 */
@Slf4j
@Component("weibo_repost_local")
public class WeiboRepostCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().directFallbackOnProxyFailure(true).build();

    private static final String API_URL = "https://weibo.com/ajax/statuses/repostTimeline";
    private static final int RETRY = 3;

    @Override
    public String providerKey() {
        return "weibo_repost_local";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String mid = WeiboUrlParser.resolveMid(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        String cookie = extra.get("cookie");
        int page = Math.max(1, CommonTools.stringToInteger(extra.get("page")));
        String nextId = extra.get("nextId");

        if (StringUtils.isBlank(mid)) {
            throw new CollectorException("微博转发采集缺少 mid（extra.mid 或 targetId 至少提供一个）");
        }
        if (StringUtils.isBlank(cookie)) {
            throw new CollectorException("微博转发采集缺少 Cookie（extra.cookie），微博接口必须登录态");
        }
        log.info("[weibo-repost] 转发采集: mid={} page={} nextId={}", mid, page, nextId);

        String url = API_URL + "?id=" + mid + "&page=" + page + "&moduleID=feed&count=20";
        Map<String, String> headers = ProviderHeaders.weiboHeaders(cookie);

        List<Comment> reposts = new ArrayList<>();
        boolean hasMore = false;
        String nextCursor = nextId;

        for (int i = 0; i < RETRY; i++) {
            try {
                String body = httpUtil.getString(url, headers);
                if (StringUtils.isNotBlank(body) && body.contains("next_cursor")) {
                    nextCursor = CommonParser.getJsonPathOne(body, "$.next_cursor");
                    int maxPage = CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.max_page"));
                    if (maxPage == 0) {
                        return CommonEntity.<Comment>builder()
                                .haseMore(false).nextUrl(nextCursor)
                                .status(CommonStatusEnum.STATUS_SUCCESS)
                                .dataList(reposts)
                                .build();
                    }
                    hasMore = page < maxPage;
                    List<String> dataStrList = CommonParser.getJsonPathMany(body, "$.data");
                    if (CollUtil.isNotEmpty(dataStrList)) {
                        for (String dataStr : dataStrList) {
                            if (StringUtils.isNotBlank(dataStr)) {
                                reposts.add(Comment.buildFromWeiboRepost(dataStr));
                            }
                        }
                    }
                    return CommonEntity.<Comment>builder()
                            .haseMore(hasMore).nextUrl(nextCursor)
                            .status(CommonStatusEnum.STATUS_SUCCESS)
                            .dataList(reposts)
                            .build();
                }
            } catch (Exception e) {
                log.warn("[weibo-repost] 第 {}/{} 次请求失败: {}", i + 1, RETRY, e.getMessage());
            }
        }
        return CommonEntity.<Comment>builder()
                .haseMore(false).nextUrl(nextCursor)
                .status(CommonStatusEnum.STATUS_ERROR)
                .build();
    }
}
