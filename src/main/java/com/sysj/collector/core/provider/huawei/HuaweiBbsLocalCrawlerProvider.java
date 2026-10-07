package com.sysj.collector.core.provider.huawei;

import cn.hutool.core.collection.CollUtil;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 华为社区 - 本地爬虫供应商（Bean 名称 = {@code huawei_bbs_local}）。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code HuaiWeiBbsCrawler}：vmall 俱乐部评论经
 * 华为 SGW 网关（sgw-cn.c.huawei.com）POST JSON，网关强制校验 {@code SGW-APP-ID} 请求头
 * （不同服务不同 APP-ID，与社区前端一致），无签名/CSRF 要求。
 * 主评论 {@code topicComment} / 子回复 {@code subComment}，均为页码分页
 * （返回 totalNum，无下一页 = 空页或 pageIndex×pageSize ≥ totalNum）。
 * 字段解析统一收敛到 {@link Comment#buildFromHuaweiBbs(String)}。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code page} —— 页码，从 1 开始（默认 1）</li>
 *   <li>{@code commentId} —— 父评论 id，传了则采该评论的子回复</li>
 * </ul>
 * <p>页码分页：{@code nextUrl} = 下一页页码（自动翻页续采回填 {@code extra.page}）。
 */
@ProviderCapability({ Capability.COMMENT, Capability.SUB_COMMENT, Capability.PAGE_PAGING, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "huawei_bbs", feature = "comment", name = "华为社区-本地爬虫",
            ratePerSecond = 2.0, maxConcurrency = 4, flowEffect = "THROTTLE_QUEUE", maxQueueWaitMs = 30000)
@Slf4j
@Component("huawei_bbs_local")
public class HuaweiBbsLocalCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = HttpUtilProvider.localClient();

    private static final String SGW_BASE = "https://sgw-cn.c.huawei.com/forward/club/comment_h5/";

    /** 帖子内容服务 APP-ID（topicComment 使用） */
    private static final String APP_ID_CONTENT = "5881CD5912A8D0AA39AEC96F2EC2388A";

    /** 评论服务 APP-ID（subComment 使用） */
    private static final String APP_ID_COMMENT = "EDCF82D77A5AB59706CD5F2163F67427";

    private static final int PAGE_SIZE = 20;

    /** 帖子链接：cn.club.vmall.com/.../mhwnews/{dynamic|article|video|news|question}/id_{tid} */
    private static final Pattern TID_PATTERN = Pattern.compile("mhwnews/(dynamic|article|video|news|question)/id_(\\d+)");

    @Override
    public String providerKey() {
        return "huawei_bbs_local";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String tid = resolveTid(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        if (StringUtils.isBlank(tid)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .msg("无法从链接解析华为社区帖子 tid").status(CommonStatusEnum.STATUS_URL_ERROR).build();
        }
        String commentId = extra.get("commentId");
        int page = Math.max(1, CommonTools.stringToInteger(StringUtils.defaultIfBlank(extra.get("page"), "1")));
        log.info("[huawei-bbs-local] 采集开始: tid={} commentId={} page={}", tid, commentId, page);

        return StringUtils.isBlank(commentId) ? getComment(tid, page) : getCommentChild(tid, commentId, page);
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
                return matcher.group(2);
            }
            if (candidate.matches("[0-9]+")) {
                return candidate;
            }
        }
        return null;
    }

    /** 主评论一页。 */
    private CommonEntity<Comment> getComment(String tid, int page) {
        try {
            JSONObject paramsJson = new JSONObject();
            paramsJson.put("topicId", tid);
            paramsJson.put("pageIndex", page);
            paramsJson.put("pageSize", PAGE_SIZE);
            paramsJson.put("queryOrder", 1);

            String body = httpUtil.postJsonString(SGW_BASE + "topicComment/1",
                    paramsJson.toJSONString(), sgwHeaders(APP_ID_CONTENT));
            if (StringUtils.isNotBlank(body) && "0".equals(CommonParser.getJsonPathOne(body, "$.errcode"))) {
                return toPageResult(body, page, tid);
            }
            log.warn("[huawei-bbs-local] 主评论响应异常: tid={} page={}", tid, page);
        } catch (Exception e) {
            log.warn("[huawei-bbs-local] 主评论采集出错: tid={} page={} error={}", tid, page, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    /** 子回复一页。 */
    private CommonEntity<Comment> getCommentChild(String tid, String commentId, int page) {
        try {
            JSONObject paramsJson = new JSONObject();
            paramsJson.put("parentCommentId", commentId);
            paramsJson.put("topicId", tid);
            paramsJson.put("pageIndex", page);
            paramsJson.put("pageSize", PAGE_SIZE);

            String body = httpUtil.postJsonString(SGW_BASE + "subComment/1",
                    paramsJson.toJSONString(), sgwHeaders(APP_ID_COMMENT));
            if (StringUtils.isNotBlank(body) && "0".equals(CommonParser.getJsonPathOne(body, "$.errcode"))) {
                return toPageResult(body, page, tid);
            }
            log.warn("[huawei-bbs-local] 子回复响应异常: tid={} commentId={} page={}", tid, commentId, page);
        } catch (Exception e) {
            log.warn("[huawei-bbs-local] 子回复采集出错: tid={} commentId={} page={} error={}",
                    tid, commentId, page, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    /** topicComment/subComment 共用解析：data 数组 → Comment，空页或取满 totalNum 即无下一页。 */
    private static CommonEntity<Comment> toPageResult(String body, int page, String mid) {
        Integer totalNum = CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.totalNum"));
        List<Comment> comments = new ArrayList<>();
        List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.data");
        if (CollUtil.isNotEmpty(commentStrList)) {
            for (String commentStr : commentStrList) {
                comments.add(Comment.buildFromHuaweiBbs(commentStr));
            }
        }
        boolean hasMore = CollUtil.isNotEmpty(comments) && totalNum != null
                && (long) page * PAGE_SIZE < totalNum;
        comments.forEach(c -> c.setMid(mid));
        return CommonEntity.<Comment>builder().haseMore(hasMore)
                .nextUrl(hasMore ? String.valueOf(page + 1) : null)
                .totalPage(CommonTools.totalPage(totalNum, PAGE_SIZE))
                .status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
    }

    private static Map<String, String> sgwHeaders(String appId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("SGW-APP-ID", appId);
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("request-source", "H5");
        headers.put("site", "zh_CN");
        return headers;
    }
}
