package com.sysj.collector.core.provider.weibo;

import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
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
 * 微博 - 网页版评论本地爬虫。
 *
 * <p>Bean 名称 = {@code local_crawler}（与 {@code platform_feature_config} 中的 providerKey 一致）。
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
 * <h3>返回</h3>
 * {@code haseMore} 表示还有下一页，{@code nextUrl} 为下一页 {@code maxId}（调用方回填到 {@code extra.maxId} 继续翻页）。
 */
@Slf4j
@Component("local_crawler")
public class WeiboLocalCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().directFallbackOnProxyFailure(true).build();

    private static final String API_URL = "https://weibo.com/ajax/statuses/buildComments";
    private static final int RETRY = 3;

    @Override
    public String providerKey() {
        return "local_crawler";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        // mid 优先取显式传入，其次从微博链接解析（详见 WeiboUrlParser）
        String mid = WeiboUrlParser.resolveMid(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        String uid = WeiboUrlParser.resolveUid(extra.get("uid"), request.getFromUrl());
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

        StringBuilder apiUrl = new StringBuilder(API_URL)
                .append("?flow=").append(sort)
                .append("&is_reload=1&id=").append(mid)
                .append("&is_show_bulletin=2&is_mix=").append(isMix)
                .append("&count=20&uid=").append(uid == null ? "" : uid)
                .append("&fetch_level=").append(fetchLevel)
                .append("&locale=zh-CN");
        if (StringUtils.isNotBlank(maxId)) {
            apiUrl.append("&max_id=").append(maxId);
        }

        String url = apiUrl.toString();
        Map<String, String> headers = ProviderHeaders.weiboHeaders(cookie);

        for (int i = 0; i < RETRY; i++) {
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
            } catch (Exception e) {
                log.warn("[weibo-local] 第 {}/{} 次请求失败: {}", i + 1, RETRY, e.getMessage());
            }
        }
        return CommonEntity.<Comment>builder()
                .haseMore(false)
                .status(CommonStatusEnum.STATUS_ERROR)
                .build();
    }
}
