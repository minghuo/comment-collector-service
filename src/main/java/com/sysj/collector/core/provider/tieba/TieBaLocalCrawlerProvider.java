package com.sysj.collector.core.provider.tieba;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.tools.CommonTools;
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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百度贴吧 - 本地爬虫供应商（Bean 名称 = {@code tieba_local}）。
 *
 * <p>实现对照 {@code auto-task-web} 的 {@code TieBaCrawler}（2026-10 实测无登录 cookie 可用）。
 * 主列表 {@code POST /c/f/pb/page_pc}：每页 15 楼，行内 sub_post_list 为楼中楼预览（随主列表展平）；
 * 楼中楼全量 {@code POST /c/f/pb/floor}：固定 30 条/页。两接口均表单 POST + sign
 * （参数按名升序拼 key=value 后加盐取 MD5 小写），tbs 传字面量 "null"。
 * 字段解析统一收敛到 {@link Comment#buildFromTieba(String, String, String)}。
 *
 * <h3>入参（{@code request.extra}）</h3>
 * <ul>
 *   <li>{@code page} —— 页码，从 1 开始（默认 1）；超页请求被接口钳制到末页、无 post_list 时自然终止</li>
 *   <li>{@code commentId} —— 楼层 pid，传了则采该楼层的全量楼中楼</li>
 * </ul>
 * <p>页码分页：{@code nextUrl} = 下一页页码（自动翻页续采回填 {@code extra.page}）。
 */
@ProviderCapability({ Capability.COMMENT, Capability.SUB_COMMENT, Capability.PAGE_PAGING, Capability.SYNC_SUPPORTED })
@Slf4j
@Component("tieba_local")
public class TieBaLocalCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().directFallbackOnProxyFailure(true).build();

    private static final String PAGE_API = "https://tieba.baidu.com/c/f/pb/page_pc";
    private static final String FLOOR_API = "https://tieba.baidu.com/c/f/pb/floor";

    /** floor 接口实测固定 30 条/页（rn 传其他值不生效） */
    private static final int FLOOR_PAGE_SIZE = 30;

    /** 网页版签名盐（与 auto-task-web/data-process-util 的 TieBaTools 同源） */
    private static final String SIGN_SALT = "36770b1f34c9bbf2e7d1a99d2b82fa9e";

    /** 无登录态的 tbs 取值 */
    private static final String TBS_NO_LOGIN = "null";

    /** 帖子链接：tieba.baidu.com/p/{kz}（tiebac 为分享短链域） */
    private static final Pattern KZ_PATTERN = Pattern.compile("(?:tieba|tiebac)\\.baidu\\.com/p/([0-9]+)");

    @Override
    public String providerKey() {
        return "tieba_local";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        Map<String, String> extra = request.getExtra() == null ? Map.of() : request.getExtra();
        String kz = resolveKz(extra.get("mid"), request.getFromUrl(), request.getTargetId());
        if (StringUtils.isBlank(kz)) {
            return CommonEntity.<Comment>builder().haseMore(false)
                    .msg("无法从链接解析贴吧帖子 kz").status(CommonStatusEnum.STATUS_URL_ERROR).build();
        }
        String commentId = extra.get("commentId");
        int page = Math.max(1, CommonTools.stringToInteger(extra.get("page")));
        log.info("[tieba-local] 采集开始: kz={} commentId={} page={}", kz, commentId, page);

        return StringUtils.isBlank(commentId) ? getComment(kz, page) : getCommentChild(kz, commentId, page);
    }

    /** 从 extra.mid / fromUrl / targetId 解析帖子 kz。 */
    static String resolveKz(String mid, String fromUrl, String targetId) {
        for (String candidate : List.of(mid, fromUrl, targetId)) {
            if (StringUtils.isBlank(candidate)) {
                continue;
            }
            Matcher matcher = KZ_PATTERN.matcher(candidate);
            if (matcher.find()) {
                return matcher.group(1);
            }
            if (candidate.matches("[0-9]+")) {
                return candidate;   // 直接传纯数字 kz
            }
        }
        return null;
    }

    /** 楼层主列表一页：楼层 + 楼中楼预览展平（楼层作者按 user_list 反查）。 */
    private CommonEntity<Comment> getComment(String kz, int page) {
        try {
            Map<String, String> params = baseParams(kz, page);
            String body = httpUtil.postFormString(PAGE_API, signedParams(params), requestHeaders());
            if (StringUtils.isNotBlank(body) && "0".equals(CommonParser.getJsonPathOne(body, "$.error_code"))) {
                List<Comment> comments = new ArrayList<>();
                Map<String, String> userMap = userMap(body);
                List<String> floorStrList = CommonParser.getJsonPathMany(body, "$.post_list");
                if (CollUtil.isNotEmpty(floorStrList)) {
                    for (String floorStr : floorStrList) {
                        String floorPid = CommonParser.getJsonPathOne(floorStr, "$.id");
                        comments.add(Comment.buildFromTieba(floorStr, userMap.get(
                                CommonParser.getJsonPathOne(floorStr, "$.author_id")), null));
                        List<String> subStrList = CommonParser.getJsonPathMany(floorStr, "$.sub_post_list.sub_post_list");
                        if (CollUtil.isNotEmpty(subStrList)) {
                            for (String subStr : subStrList) {
                                comments.add(Comment.buildFromTieba(subStr, userMap.get(
                                        CommonParser.getJsonPathOne(subStr, "$.author_id")), floorPid));
                            }
                        }
                    }
                }
                boolean hasMore = "1".equals(CommonParser.getJsonPathOne(body, "$.page.has_more"));
                return CommonEntity.<Comment>builder().haseMore(hasMore)
                        .nextUrl(hasMore ? String.valueOf(page + 1) : null)
                        .status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
            }
            log.warn("[tieba-local] 主列表响应异常: kz={} page={}", kz, page);
        } catch (Exception e) {
            log.warn("[tieba-local] 楼层列表采集出错: kz={} page={} error={}", kz, page, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    /** 楼中楼全量一页：floor 接口按 kz+pid 采集，回复内嵌作者信息。 */
    private CommonEntity<Comment> getCommentChild(String kz, String commentId, int page) {
        try {
            Map<String, String> params = baseParams(kz, page);
            params.put("pid", commentId);
            params.put("rn", String.valueOf(FLOOR_PAGE_SIZE));
            String body = httpUtil.postFormString(FLOOR_API, signedParams(params), requestHeaders());
            if (StringUtils.isNotBlank(body) && "0".equals(CommonParser.getJsonPathOne(body, "$.error_code"))) {
                List<Comment> comments = new ArrayList<>();
                List<String> subStrList = CommonParser.getJsonPathMany(body, "$.subpost_list");
                if (CollUtil.isNotEmpty(subStrList)) {
                    for (String subStr : subStrList) {
                        comments.add(Comment.buildFromTieba(subStr, null, commentId));
                    }
                }
                Integer totalCount = CommonTools.stringToInteger(
                        CommonParser.getJsonPathOne(body, "$.page.total_count"));
                // 空页（超页请求钳制到末页后 subpost_list 缺失）或已取满 total_count 即无下一页
                boolean hasMore = CollUtil.isNotEmpty(comments) && totalCount != null
                        && (long) page * FLOOR_PAGE_SIZE < totalCount;
                return CommonEntity.<Comment>builder().haseMore(hasMore)
                        .nextUrl(hasMore ? String.valueOf(page + 1) : null)
                        .status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
            }
            log.warn("[tieba-local] 楼中楼响应异常: kz={} pid={} page={}", kz, commentId, page);
        } catch (Exception e) {
            log.warn("[tieba-local] 楼中楼采集出错: kz={} pid={} page={} error={}", kz, commentId, page, e.getMessage());
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }

    /** 两接口公共参数。 */
    private static Map<String, String> baseParams(String kz, int page) {
        Map<String, String> params = new HashMap<>();
        params.put("pn", String.valueOf(page));
        params.put("lz", "0");
        params.put("r", "2");
        params.put("mark_type", "0");
        params.put("back", "0");
        params.put("fr", "frs");
        params.put("kz", kz);
        params.put("session_request_times", "1");
        params.put("tbs", TBS_NO_LOGIN);
        params.put("subapp_type", "pc");
        params.put("_client_type", "20");
        return params;
    }

    /** 表单参数 = 原参数 + sign：按参数名升序拼 key=value 后加盐 MD5 小写。 */
    private static Map<String, String> signedParams(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(params).forEach((key, value) -> sb.append(key).append("=").append(value));
        sb.append(SIGN_SALT);
        Map<String, String> form = new HashMap<>(params);
        form.put("sign", DigestUtil.md5Hex(sb.toString().toLowerCase()));
        return form;
    }

    /** user_list 项按用户 id 建索引（楼层作者反查）。 */
    private static Map<String, String> userMap(String body) {
        Map<String, String> userMap = new HashMap<>();
        List<String> userList = CommonParser.getJsonPathMany(body, "$.user_list");
        if (CollUtil.isNotEmpty(userList)) {
            for (String userStr : userList) {
                userMap.put(CommonParser.getJsonPathOne(userStr, "$.id"), userStr);
            }
        }
        return userMap;
    }

    private static Map<String, String> requestHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        headers.put("Content-Type", "application/x-javascript;charset=utf-8");
        return headers;
    }
}
