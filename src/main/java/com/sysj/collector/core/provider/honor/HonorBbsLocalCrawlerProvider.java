package com.sysj.collector.core.provider.honor;

import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.parser.TimeParser;
import com.bewilder.tools.CommonTools;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 荣耀社区 - 本地爬虫供应商（Bean 名称 = {@code honor_bbs_local}）。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code HonorBbsCrawler}：club.honor.com（Discuz 架构）
 * 评论为服务端直出 HTML（无独立 JSON 接口），按页抓取 {@code /cn/thread-{tid}-{page}-1.html}，
 * XPath 解析楼层（{@code //table[starts-with(@id,'pid')]，跳过"楼主"正文层）与楼中楼
 * （{@code div.report-chi}，随主列表展平，parentCommentId 为所属楼层 pid）。
 * <b>全部回复均已内嵌页面，无单独子回复接口</b>——不声明 {@code SUB_COMMENT} 能力。
 *
 * <p>页码分页：Discuz 超页访问会钳制回末页，因此无下一页 = page ≥ totalPages
 * （totalPages 取自分页块最大页码），不能用空页判定。
 * 字段解析统一收敛到 {@link Comment#buildFromHonorBbs(String)}（输入为爬虫解析出的字段 JSON）。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code page} —— 页码，从 1 开始（默认 1）</li>
 * </ul>
 * <p>页码分页：{@code nextUrl} = 下一页页码（自动翻页续采回填 {@code extra.page}）。
 */
@ProviderCapability({ Capability.COMMENT, Capability.PAGE_PAGING, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "honor_bbs", feature = "comment", name = "荣耀社区-本地爬虫")
@Slf4j
@Component("honor_bbs_local")
public class HonorBbsLocalCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().directFallbackOnProxyFailure(true).build();

    private static final String THREAD_URL_FORMAT = "https://club.honor.com/cn/thread-%s-%d-1.html";

    /** 楼层内容单元格 id：postmessage_{pid}，用于从楼层片段中提取楼层 pid */
    private static final Pattern FLOOR_PID_PATTERN = Pattern.compile("postmessage_([0-9]+)");

    /** 楼中楼回复 id：comment_editid_{replyId} */
    private static final Pattern REPLY_ID_PATTERN = Pattern.compile("comment_editid_([0-9]+)");

    /** 用户主页链接：space-uid_{uid}.html */
    private static final Pattern SPACE_UID_PATTERN = Pattern.compile("space-uid-([0-9]+)");

    /** 当前帖分页链接：thread-{tid}-{page}-1.html */
    private static final Pattern PAGE_LINK_PATTERN = Pattern.compile("thread-([0-9]+)-([0-9]+)-1[.]html");

    /** 帖子链接：club.honor.com/cn/thread-{tid}-{page}-1.html */
    private static final Pattern TID_PATTERN = Pattern.compile("club\\.honor\\.com/cn/thread-([0-9]+)-[0-9]+-[0-9]+[.]html");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String providerKey() {
        return "honor_bbs_local";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String tid = resolveTid(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        if (StringUtils.isBlank(tid)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .msg("无法从链接解析荣耀社区帖子 tid").status(CommonStatusEnum.STATUS_URL_ERROR).build();
        }
        int page = Math.max(1, CommonTools.stringToInteger(extra.get("page")));
        log.info("[honor-bbs-local] 采集开始: tid={} page={}", tid, page);
        return getComment(tid, page);
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

    /** 抓取一页并解析楼层与楼中楼。 */
    private CommonEntity<Comment> getComment(String mid, int page) {
        try {
            String body = httpUtil.getString(String.format(THREAD_URL_FORMAT, mid, page));
            if (StringUtils.isNotBlank(body) && body.contains("postmessage_")) {
                List<String> floorList = CommonParser.getXpathMany(body, "//table[starts-with(@id,'pid')]");
                List<Comment> comments = new ArrayList<>();
                if (CollUtil.isNotEmpty(floorList)) {
                    for (String floor : floorList) {
                        // 跳过"楼主"正文层（其余楼层才是对帖子的评论）
                        if ("楼主".equals(StringUtils.trim(CommonParser.getXpathOne(floor, "//a[@class='floor-num']")))) {
                            continue;
                        }
                        Map<String, Object> floorJson = buildFloorJson(floor);
                        floorJson.put("replyCount", String.valueOf(countInlineReplies(floor)));
                        comments.add(Comment.buildFromHonorBbs(JSON.writeValueAsString(floorJson)));
                        // 楼中楼回复展平追加，parentCommentId 为所属楼层 pid
                        appendInlineReplies(floor, comments);
                    }
                }
                int totalPages = totalPages(body, mid);
                boolean hasMore = page < totalPages;
                comments.forEach(c -> c.setMid(mid));
                // 楼层渲染正常即成功（仅"楼主"层无回复的帖子为合法的空数据）
                return CommonEntity.<Comment>builder().haseMore(hasMore)
                        .nextUrl(hasMore ? String.valueOf(page + 1) : null)
                        .totalPage(totalPages)
                        .status(CollUtil.isNotEmpty(floorList)
                                ? CommonStatusEnum.STATUS_SUCCESS : CommonStatusEnum.STATUS_ERROR)
                        .dataList(comments).build();
            }
            log.warn("[honor-bbs-local] 页面响应异常: mid={} page={}", mid, page);
        } catch (Exception e) {
            log.warn("[honor-bbs-local] 评论采集出错: mid={} page={} error={}", mid, page, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    /** 楼层片段解析为评论字段 Map（时间保留原始相对格式，由解析方法经 TimeParser 归一化）。 */
    private static Map<String, Object> buildFloorJson(String floor) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("commentId", regexGroup(floor, FLOOR_PID_PATTERN));
        json.put("uid", regexGroup(floor, SPACE_UID_PATTERN));
        json.put("userName", CommonParser.getXpathOne(floor, "//a[@class='authi-name']"));
        json.put("time", TimeParser.stringFormatDate(
                CommonParser.getXpathOne(floor, "//em[starts-with(@id,'authorposton')]")));
        json.put("text", CommonParser.getXpathOne(floor, "//td[starts-with(@id,'postmessage_')]"));
        json.put("likeCount", CommonTools.stringToInteger(StringUtils.defaultIfBlank(
                CommonParser.getXpathOne(floor, "//span[starts-with(@id,'review_support_')]"), "0")));
        json.put("ipLocation", CommonParser.getXpathOne(floor, "//em[@class='fmty']"));
        List<String> fmtys = CommonParser.getXpathMany(floor, "//em[@class='fmty']");
        // 第二个 fmty 为"来自：{设备}"（getXpathMany 返回元素 HTML，需剥离标签）
        json.put("source", CollUtil.isNotEmpty(fmtys) && fmtys.size() > 1
                ? StringUtils.removeStart(fmtys.get(1).replaceAll("<[^>]+>", "").trim(), "来自：") : null);
        return json;
    }

    /** 追加楼层内嵌的楼中楼回复，parentCommentId 为所属楼层 pid。 */
    private static void appendInlineReplies(String floor, List<Comment> comments) {
        String floorPid = regexGroup(floor, FLOOR_PID_PATTERN);
        List<String> replyList = CommonParser.getXpathMany(floor, "//div[contains(@class,'report-chi')]");
        if (CollUtil.isEmpty(replyList)) {
            return;
        }
        Matcher idMatcher = REPLY_ID_PATTERN.matcher(floor);
        for (String reply : replyList) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("commentId", idMatcher.find() ? idMatcher.group(1) : null);
            json.put("uid", regexGroup(reply, SPACE_UID_PATTERN));
            json.put("userName", CommonParser.getXpathOne(reply, "//a[@class='report-name']"));
            // 回复正文 = psti 全文去掉尾部的"发表于 ... 属地"片段
            String pstiText = CommonParser.getXpathOne(reply, "//div[@class='psti vw-com']");
            json.put("text", pstiText == null ? null
                    : StringUtils.trim(pstiText.replaceFirst("发表于[\\s\\S]*$", "")));
            // 时间与属地混在 span 内："发表于 9-21 09:35:18 江苏"
            String raw = stripPostonPrefix(CommonParser.getXpathOne(reply, "//span"));
            String[] parts = StringUtils.isNotBlank(raw) ? raw.split("\\s+") : new String[0];
            // 末段为纯中文属地词（不含数字）且不止一段时拆出，其余归入时间（如"刚刚"无属地）
            if (parts.length > 1 && parts[parts.length - 1].matches("[\\u4e00-\\u9fa5]{2,}")) {
                json.put("ipLocation", parts[parts.length - 1]);
                json.put("time", String.join(" ", java.util.Arrays.copyOfRange(parts, 0, parts.length - 1)));
            } else {
                json.put("ipLocation", null);
                json.put("time", raw);
            }
            json.put("likeCount", "0");
            json.put("parentCommentId", floorPid);
            try {
                comments.add(Comment.buildFromHonorBbs(JSON.writeValueAsString(json)));
            } catch (Exception e) {
                log.warn("[honor-bbs-local] 楼中楼字段序列化失败，跳过该条: {}", e.getMessage());
            }
        }
    }

    /** 去掉时间串开头的"发表于"前缀。 */
    private static String stripPostonPrefix(String postonText) {
        return postonText == null ? null : StringUtils.trim(postonText.replaceFirst("^发表于", ""));
    }

    private static int countInlineReplies(String floor) {
        int count = 0;
        Matcher matcher = REPLY_ID_PATTERN.matcher(floor);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** 总页数：取当前页分页块内 thread-{tid}-{page}-1.html 链接的最大页码（无分页块的单页帖为 1）。 */
    private static int totalPages(String body, String mid) {
        int max = 1;
        Matcher matcher = PAGE_LINK_PATTERN.matcher(body);
        while (matcher.find()) {
            if (mid.equals(matcher.group(1))) {
                max = Math.max(max, Integer.parseInt(matcher.group(2)));
            }
        }
        return max;
    }

    private static String regexGroup(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }
}
