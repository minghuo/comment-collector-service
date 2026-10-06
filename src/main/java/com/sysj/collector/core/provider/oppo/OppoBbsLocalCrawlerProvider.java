package com.sysj.collector.core.provider.oppo;

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
import com.sysj.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OPPO 社区 - 本地爬虫供应商（Bean 名称 = {@code oppo_bbs_local}）。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code OppoBbsCrawler}：www.oppo.cn GET JSON 接口
 * （无需签名/CSRF）。主评论 {@code post/list}（行内 commentList 为楼中楼预览，随主列表展平）、
 * 子评论 {@code comment/list}（按楼层 pid 全量补全），页码分页（返回 data.total，
 * 空页或 page×pageSize ≥ total 即无下一页）。字段解析统一收敛到
 * {@link Comment#buildFromOppoBbs(String)}。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code page} —— 页码，从 1 开始（默认 1）</li>
 *   <li>{@code commentId} —— 楼层 pid，传了则采该楼层的全量楼中楼</li>
 * </ul>
 * <p>页码分页：{@code nextUrl} = 下一页页码（自动翻页续采回填 {@code extra.page}）。
 */
@ProviderCapability({ Capability.COMMENT, Capability.SUB_COMMENT, Capability.PAGE_PAGING, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "oppo_bbs", feature = "comment", name = "OPPO社区-本地爬虫")
@Slf4j
@Component("oppo_bbs_local")
public class OppoBbsLocalCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().directFallbackOnProxyFailure(true).build();

    private static final String API_BASE = "https://www.oppo.cn/java/ugc/frontend/";

    private static final int PAGE_SIZE = 20;

    /** 帖子链接：www.oppo.cn/thread-{tid} */
    private static final Pattern TID_PATTERN = Pattern.compile("oppo\\.cn/thread-([0-9]+)");

    @Override
    public String providerKey() {
        return "oppo_bbs_local";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String tid = resolveTid(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        if (StringUtils.isBlank(tid)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .msg("无法从链接解析OPPO社区帖子 tid").status(CommonStatusEnum.STATUS_URL_ERROR).build();
        }
        String commentId = extra.get("commentId");
        int page = Math.max(1, CommonTools.stringToInteger(extra.get("page")));
        log.info("[oppo-bbs-local] 采集开始: tid={} commentId={} page={}", tid, commentId, page);

        return StringUtils.isBlank(commentId) ? getComment(tid, page) : getCommentChild(tid, commentId, page);
    }

    /** 从 extra.mid / fromUrl / targetId 解析帖子 tid。 */
    static String resolveTid(String mid, String fromUrl, String targetId) {
        for (String candidate : List.of(mid, fromUrl, targetId)) {
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

    /** 主评论一页：楼层 + 楼中楼预览展平。 */
    private CommonEntity<Comment> getComment(String tid, int page) {
        try {
            String body = httpUtil.getString(pageUrl("post/list", "tid", tid, page), requestHeaders());
            if (StringUtils.isNotBlank(body) && "200".equals(CommonParser.getJsonPathOne(body, "$.code"))) {
                Integer total = CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.data.total"));
                List<Comment> comments = new ArrayList<>();
                List<String> floorStrList = CommonParser.getJsonPathMany(body, "$.data.rows");
                if (CollUtil.isNotEmpty(floorStrList)) {
                    for (String floorStr : floorStrList) {
                        comments.add(Comment.buildFromOppoBbs(floorStr));
                    }
                }
                // 楼中楼预览展平，行内评论自带 pid（所属楼层 id）
                List<String> inlineStrList = CommonParser.getJsonPathMany(body, "$.data.rows[*].commentList[*]");
                if (CollUtil.isNotEmpty(inlineStrList)) {
                    for (String inlineStr : inlineStrList) {
                        comments.add(Comment.buildFromOppoBbs(inlineStr));
                    }
                }
                boolean hasMore = CollUtil.isNotEmpty(floorStrList) && total != null
                        && (long) page * PAGE_SIZE < total;
                comments.forEach(c -> c.setMid(tid));
                return CommonEntity.<Comment>builder().haseMore(hasMore)
                        .nextUrl(hasMore ? String.valueOf(page + 1) : null)
                        .totalPage(CommonTools.totalPage(total, PAGE_SIZE))
                        .status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
            }
            log.warn("[oppo-bbs-local] 主评论响应异常: tid={} page={}", tid, page);
        } catch (Exception e) {
            log.warn("[oppo-bbs-local] 主评论采集出错: tid={} page={} error={}", tid, page, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    /** 楼中楼全量一页。 */
    private CommonEntity<Comment> getCommentChild(String tid, String commentId, int page) {
        try {
            String body = httpUtil.getString(pageUrl("comment/list", "pid", commentId, page), requestHeaders());
            if (StringUtils.isNotBlank(body) && "200".equals(CommonParser.getJsonPathOne(body, "$.code"))) {
                Integer total = CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.data.total"));
                List<Comment> comments = new ArrayList<>();
                List<String> replyStrList = CommonParser.getJsonPathMany(body, "$.data.rows");
                if (CollUtil.isNotEmpty(replyStrList)) {
                    for (String replyStr : replyStrList) {
                        comments.add(Comment.buildFromOppoBbs(replyStr));
                    }
                }
                boolean hasMore = CollUtil.isNotEmpty(comments) && total != null
                        && (long) page * PAGE_SIZE < total;
                comments.forEach(c -> c.setMid(tid));
                return CommonEntity.<Comment>builder().haseMore(hasMore)
                        .nextUrl(hasMore ? String.valueOf(page + 1) : null)
                        .totalPage(CommonTools.totalPage(total, PAGE_SIZE))
                        .status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
            }
            log.warn("[oppo-bbs-local] 楼中楼响应异常: pid={} page={}", commentId, page);
        } catch (Exception e) {
            log.warn("[oppo-bbs-local] 楼中楼采集出错: pid={} page={} error={}", commentId, page, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    private static String pageUrl(String path, String idKey, String idValue, int page) {
        return API_BASE + path + "?" + idKey + "=" + idValue + "&limit=" + PAGE_SIZE + "&page=" + page;
    }

    private static Map<String, String> requestHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.oppo.cn/");
        headers.put("X-Requested-With", "XMLHttpRequest");
        return headers;
    }
}
