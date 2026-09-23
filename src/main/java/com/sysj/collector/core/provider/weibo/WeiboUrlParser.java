package com.sysj.collector.core.provider.weibo;

import com.bewilder.weibo.WeiboTool;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微博链接解析工具（三个微博供应商共用）。
 *
 * <p>对照 {@code auto-task-web} 的 {@code WeiboCommentProcessor}：
 * mid 由 {@code WeiboTool.urlToMid} / {@code WeiboTool.videoUrlToMid} 解析，
 * uid 由正则从链接中提取（兼容 {@code /u/12345/xxx} 与 {@code /12345/xxx} 两种形式）。
 */
final class WeiboUrlParser {

    private WeiboUrlParser() {
    }

    /** 匹配 https://weibo.com/u/12345/xxx 与 https://weibo.com/12345/xxx。 */
    private static final Pattern UID_PATTERN =
            Pattern.compile("(?:weibo\\.com|weibo\\.cn)/(?:u/)?(\\d+)");

    /**
     * 解析微博 mid。
     *
     * <p>优先级：显式传入的 mid &gt; 普通微博链接 &gt; 视频号（tv/show）链接 &gt; 原始 targetId。
     */
    static String resolveMid(String explicitMid, String url, String targetId) {
        if (StringUtils.isNotBlank(explicitMid)) {
            return explicitMid;
        }
        if (StringUtils.isNotBlank(url)) {
            String mid = WeiboTool.urlToMid(url);
            if (StringUtils.isBlank(mid)) {
                mid = WeiboTool.videoUrlToMid(url);
            }
            if (StringUtils.isNotBlank(mid)) {
                return mid;
            }
        }
        return targetId;
    }

    /**
     * 从微博链接中提取作者 uid；无法识别时返回 null。
     */
    static String resolveUid(String explicitUid, String url) {
        if (StringUtils.isNotBlank(explicitUid)) {
            return explicitUid;
        }
        if (StringUtils.isBlank(url)) {
            return null;
        }
        Matcher matcher = UID_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }
}
