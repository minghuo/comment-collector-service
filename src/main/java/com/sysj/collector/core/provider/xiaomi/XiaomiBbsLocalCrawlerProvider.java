package com.sysj.collector.core.provider.xiaomi;

import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.tools.CommonTools;
import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;
import com.sysj.collector.core.provider.support.HttpUtilProvider;
import com.sysj.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小米社区 - 本地爬虫供应商（Bean 名称 = {@code xiaomi_bbs_local}）。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code XiaoMiBbsCrawler}：api.vip.miui.com 官方接口
 * GET（无需签名）。主评论 {@code comments}（after 为偏移量，50 条/页，行内 $.reply 为子回复，
 * 随主列表展平）、回复 {@code commentReply}（after 为最后一条回复 id，键集翻页）。
 * 无下一页 = {@code lastPage} 为 "false" 取反……即返回体 {@code lastPage=="true"} 表示末页。
 * 字段解析统一收敛到 {@link Comment#buildFromXiaomiBbs(String, String)}。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code cursor} —— 续采游标：主列表为偏移量（0,50,100…），回复为最后一条回复 id；首次传空</li>
 *   <li>{@code commentId} —— 评论 id，传了则采该评论的回复</li>
 * </ul>
 * <p>游标翻页：{@code nextUrl} = 下一轮 after 值（自动翻页续采回填 {@code extra.cursor}）。
 */
@ProviderCapability({ Capability.COMMENT, Capability.SUB_COMMENT, Capability.CURSOR_PAGING, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "xiaomi_bbs", feature = "comment", name = "小米社区-本地爬虫",
            ratePerSecond = 2.0, maxConcurrency = 4, flowEffect = "THROTTLE_QUEUE", maxQueueWaitMs = 30000)
@Slf4j
@Component("xiaomi_bbs_local")
public class XiaomiBbsLocalCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = HttpUtilProvider.localClient();

    private static final String API_BASE =
            "https://api.vip.miui.com/mtop/planet/vip/content/";

    private static final int PAGE_SIZE = 50;

    /** postId 可在链接查询串（postId=xxx）或路径（post-xxx / thread-xxx / post_xxx）中 */
    private static final Pattern POST_ID_PARAM = Pattern.compile("postId=([0-9]+)");
    private static final Pattern POST_ID_PATH = Pattern.compile("(?:post|thread)[-_/](\\d+)");

    @Override
    public String providerKey() {
        return "xiaomi_bbs_local";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String postId = resolvePostId(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        if (StringUtils.isBlank(postId)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .msg("无法从链接解析小米社区帖子 postId").status(CommonStatusEnum.STATUS_URL_ERROR).build();
        }
        String commentId = extra.get("commentId");
        String cursor = extra.get("cursor");
        log.info("[xiaomi-bbs-local] 采集开始: postId={} commentId={} cursor={}", postId, commentId, cursor);

        return StringUtils.isBlank(commentId) ? getComment(postId, cursor) : getCommentChild(postId, commentId, cursor);
    }

    /** 从 extra.mid / fromUrl / targetId 解析帖子 postId。 */
    static String resolvePostId(String mid, String fromUrl, String targetId) {
        for (String candidate : java.util.stream.Stream.of(mid, fromUrl, targetId)
                    .filter(Objects::nonNull).toList()) {
            if (StringUtils.isBlank(candidate)) {
                continue;
            }
            Matcher param = POST_ID_PARAM.matcher(candidate);
            if (param.find()) {
                return param.group(1);
            }
            Matcher path = POST_ID_PATH.matcher(candidate);
            if (path.find()) {
                return path.group(1);
            }
            if (candidate.matches("[0-9]+")) {
                return candidate;
            }
        }
        return null;
    }

    /** 主评论一页：after 为偏移量（0,50,100…），nextUrl = after + 50。 */
    private CommonEntity<Comment> getComment(String postId, String cursor) {
        int after = parseCursor(cursor);
        try {
            String body = httpUtil.getString(commentsUrl(postId, after));
            if (StringUtils.isNotBlank(body) && body.contains("records")) {
                boolean hasMore = "false".equals(CommonParser.getJsonPathOne(body, "$.lastPage"));
                List<Comment> comments = new ArrayList<>();
                List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.entity.records");
                if (CollUtil.isNotEmpty(commentStrList)) {
                    for (String commentStr : commentStrList) {
                        comments.addAll(Comment.buildFromXiaomiBbsWithReplies(commentStr, null));
                    }
                }
                comments.forEach(c -> c.setMid(postId));
                        return CommonEntity.<Comment>builder()
                        .haseMore(hasMore)
                        .nextUrl(hasMore ? String.valueOf(after + PAGE_SIZE) : null)
                        .totalPage(CommonTools.totalPage(CommonTools.stringToInteger(
                                CommonParser.getJsonPathOne(body, "$.entity.total")), PAGE_SIZE))
                        .status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
            }
            log.warn("[xiaomi-bbs-local] 主评论响应异常: postId={} after={}", postId, after);
        } catch (Exception e) {
            log.warn("[xiaomi-bbs-local] 主评论采集出错: postId={} after={} error={}", postId, after, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    /** 指定评论的回复一页：after 为最后一条回复 id（键集翻页），nextUrl = 末条回复 id。 */
    private CommonEntity<Comment> getCommentChild(String postId, String commentId, String cursor) {
        String after = StringUtils.isNotBlank(cursor) ? cursor : "0";
        try {
            String body = httpUtil.getString(replyUrl(postId, commentId, after));
            if (StringUtils.isNotBlank(body) && body.contains("records")) {
                boolean hasMore = "false".equals(CommonParser.getJsonPathOne(body, "$.lastPage"));
                List<Comment> comments = new ArrayList<>();
                List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.entity.records");
                if (CollUtil.isNotEmpty(commentStrList)) {
                    for (String commentStr : commentStrList) {
                        comments.addAll(Comment.buildFromXiaomiBbsWithReplies(commentStr, commentId));
                    }
                }
                String next = hasMore && !comments.isEmpty()
                        ? comments.get(comments.size() - 1).getCommentId() : null;
                comments.forEach(c -> c.setMid(postId));
                        return CommonEntity.<Comment>builder()
                        .haseMore(hasMore)
                        .nextUrl(next)
                        .totalPage(CommonTools.totalPage(CommonTools.stringToInteger(
                                CommonParser.getJsonPathOne(body, "$.entity.total")), PAGE_SIZE))
                        .status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
            }
            log.warn("[xiaomi-bbs-local] 回复响应异常: postId={} commentId={} after={}", postId, commentId, after);
        } catch (Exception e) {
            log.warn("[xiaomi-bbs-local] 回复采集出错: postId={} commentId={} after={} error={}",
                    postId, commentId, after, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    private static String commentsUrl(String postId, int after) {
        return API_BASE + "comments?ref=&pathname=%2Fmio%2Fdetail&version=dev.230112&postId="
                + postId + "&after=" + after + "&limit=" + PAGE_SIZE + "&sortType=2";
    }

    private static String replyUrl(String postId, String commentId, String after) {
        return API_BASE + "commentReply?ref=&pathname=%2Fmio%2Fdetail&version=dev.230112&after="
                + after + "&postId=" + postId + "&commentId=" + commentId + "&limit=" + PAGE_SIZE + "&isFirstReq=1";
    }

    private static int parseCursor(String cursor) {
        if (StringUtils.isBlank(cursor)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
