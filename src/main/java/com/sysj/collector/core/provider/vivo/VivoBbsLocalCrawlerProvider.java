package com.sysj.collector.core.provider.vivo;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSONObject;
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
 * vivo 社区 - 本地爬虫供应商（Bean 名称 = {@code vivo_bbs_local}）。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code VivoBbsCrawler}：bbs.vivo.com.cn 官方接口
 * POST JSON，{@code nonce} 签名与社区前端一致（md5(timestamp + 随机数 + "1")，32 位小写）。
 * 顶层评论 {@code queryComment}（返回"顶层 + topReplyDtos 预览回复"扁平结构，随主列表展平）、
 * 回复 {@code queryReply}（纯回复列表）。
 *
 * <p><b>键集翻页（lastId）</b>：服务端忽略 pageNum，翻页游标为"最后一条顶层/回复评论 id"
 * （经 {@code nextUrl} 承载）；终止以 {@code data.hasNext} 为准——data.total 含已删除/被过滤评论，
 * 只增不减，不能据此判断采完。<b>预览回复 id 不能作游标</b>（实测返回空页静默截断），
 * 必须用 nextUrl 回填。字段解析统一收敛到 {@link Comment#buildFromVivoBbs(String)}。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code lastId} —— 翻页游标（上一页 nextUrl 回填；首次传空）</li>
 *   <li>{@code commentId} —— 评论 id，传了则采该评论的回复列表</li>
 * </ul>
 * <p>游标翻页：{@code nextUrl} = 最后一条顶层/回复评论 id（自动翻页续采回填 {@code extra.lastId}）。
 */
@ProviderCapability({ Capability.COMMENT, Capability.SUB_COMMENT, Capability.CURSOR_PAGING, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "vivo_bbs", feature = "comment", name = "vivo社区-本地爬虫",
            ratePerSecond = 2.0, maxConcurrency = 4, flowEffect = "THROTTLE_QUEUE", maxQueueWaitMs = 30000)
@Slf4j
@Component("vivo_bbs_local")
public class VivoBbsLocalCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = HttpUtilProvider.localClient();

    private static final int PAGE_SIZE = 20;

    /** 帖子链接：bbs.vivo.com.cn/newbbs/thread/{tid} */
    private static final Pattern TID_PATTERN = Pattern.compile("bbs\\.vivo\\.com\\.cn/newbbs/thread/(\\d+)");

    @Override
    public String providerKey() {
        return "vivo_bbs_local";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String tid = resolveTid(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        if (StringUtils.isBlank(tid)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .msg("无法从链接解析vivo社区帖子 tid").status(CommonStatusEnum.STATUS_URL_ERROR).build();
        }
        String commentId = extra.get("commentId");
        String lastId = extra.get("lastId");
        int page = Math.max(1, CommonTools.stringToInteger(StringUtils.defaultIfBlank(extra.get("page"), "1")));
        log.info("[vivo-bbs-local] 采集开始: tid={} commentId={} lastId={}", tid, commentId, lastId);

        return StringUtils.isBlank(commentId) ? getComment(tid, lastId, page) : getCommentChild(tid, commentId, lastId, page);
    }

    /** 从 extra.mid / fromUrl / targetId 解析帖子 tid。 */
    static String resolveTid(String mid, String fromUrl, String targetId) {
        for (String candidate : java.util.stream.Stream.of(mid, fromUrl, targetId)
                    .filter(Objects::nonNull).toList()) {
            if (StringUtils.isBlank(candidate)) {
                continue;
            }
            Matcher matcher = TID_PATTERN.matcher(candidate);
            if (matcher.find()) {
                return matcher.group(1);
            }
            if (candidate.matches("[0-9]+")) {
                return candidate;
            }
        }
        return null;
    }

    /** 顶层评论一页（含 topReplyDtos 预览回复展平）；游标 = 最后一条顶层评论 id。 */
    private CommonEntity<Comment> getComment(String tid, String lastId, int page) {
        try {
            JSONObject paramsJson = buildParams(tid, lastId, page);
            paramsJson.put("order", 1);
            paramsJson.put("pageSize", PAGE_SIZE);

            String body = httpUtil.postJsonString(
                    "https://bbs.vivo.com.cn/api/community/comment/queryComment", paramsJson.toString());
            if (StringUtils.isNotBlank(body) && body.contains("\"list\"")) {
                int totalPage = CommonTools.totalPage(CommonTools.stringToInteger(
                        CommonParser.getJsonPathOne(body, "$.data.total")), PAGE_SIZE);
                boolean hasMore = "true".equals(CommonParser.getJsonPathOne(body, "$.data.hasNext"));
                List<Comment> comments = new ArrayList<>();
                String lastTopId = lastId;
                List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.data.list");
                if (CollUtil.isNotEmpty(commentStrList)) {
                    for (String commentStr : commentStrList) {
                        for (Comment c : Comment.buildFromVivoBbsWithReplies(commentStr)) {
                            comments.add(c);
                            if (c.getParentCommentId() == null) {
                                lastTopId = c.getCommentId();
                            }
                        }
                    }
                    comments.forEach(c -> c.setMid(tid));
                    return CommonEntity.<Comment>builder().haseMore(hasMore).totalPage(totalPage)
                            .nextUrl(hasMore ? lastTopId : null)
                            .status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
                }
                return CommonEntity.<Comment>builder().haseMore(false).totalPage(totalPage)
                        .status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
            }
            log.warn("[vivo-bbs-local] 顶层评论响应异常: tid={} lastId={}", tid, lastId);
        } catch (Exception e) {
            log.warn("[vivo-bbs-local] 顶层评论采集出错: tid={} lastId={} error={}", tid, lastId, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    /** 指定评论的回复一页（纯回复列表）；游标 = 最后一条回复 id（键集翻页不重不漏）。 */
    private CommonEntity<Comment> getCommentChild(String tid, String commentId, String lastId, int page) {
        try {
            JSONObject paramsJson = buildParams(tid, lastId, page);
            paramsJson.put("commentId", commentId);
            paramsJson.put("order", 1);
            paramsJson.put("pageSize", PAGE_SIZE);

            String body = httpUtil.postJsonString(
                    "https://bbs.vivo.com.cn/api/community/reply/queryReply", paramsJson.toString());
            if (StringUtils.isNotBlank(body) && body.contains("\"list\"")) {
                int totalPage = CommonTools.totalPage(CommonTools.stringToInteger(
                        CommonParser.getJsonPathOne(body, "$.data.total")), PAGE_SIZE);
                boolean hasMore = "true".equals(CommonParser.getJsonPathOne(body, "$.data.hasNext"));
                List<Comment> comments = new ArrayList<>();
                List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.data.list");
                if (CollUtil.isNotEmpty(commentStrList)) {
                    for (String commentStr : commentStrList) {
                        comments.add(Comment.buildFromVivoBbs(commentStr));
                    }
                    comments.forEach(c -> c.setMid(tid));
                    return CommonEntity.<Comment>builder().haseMore(hasMore).totalPage(totalPage)
                            .nextUrl(hasMore ? comments.get(comments.size() - 1).getCommentId() : null)
                            .status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
                }
                return CommonEntity.<Comment>builder().haseMore(false).totalPage(totalPage)
                        .status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
            }
            log.warn("[vivo-bbs-local] 回复响应异常: tid={} commentId={} lastId={}", tid, commentId, lastId);
        } catch (Exception e) {
            log.warn("[vivo-bbs-local] 回复采集出错: tid={} commentId={} lastId={} error={}",
                    tid, commentId, lastId, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    /** 公共参数：nonce = md5(timestamp + 随机整数 + "1")，与社区前端请求拦截器一致。 */
    private static JSONObject buildParams(String tid, String lastId, int page) {
        long timestamp = System.currentTimeMillis();
        JSONObject paramsJson = new JSONObject();
        paramsJson.put("lastId", StringUtils.isNotBlank(lastId) ? lastId : "0");
        paramsJson.put("nonce", DigestUtil.md5Hex(timestamp + "" + (int) (10000000 * Math.random()) + "1"));
        paramsJson.put("pageNum", page);
        paramsJson.put("tid", tid);
        paramsJson.put("timestamp", timestamp);
        return paramsJson;
    }
}
