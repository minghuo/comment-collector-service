package com.sysj.collector.core.provider.support;

import com.sysj.collector.constants.SyConstants;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 供应商 HTTP 请求头 / 参数提取工具。
 *
 * <p>对应 auto-task-web 的 {@code utils.CommonHeadUtils} 与 {@code utils.CommonExtractor}，
 * 只保留本项目用到的部分。
 */
public final class ProviderHeaders {

    private ProviderHeaders() {
    }

    /**
     * 系统接口（auto.sysjdata.com，微信/视频号评论）请求头。
     */
    public static Map<String, String> syHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", SyConstants.TOKEN);
        return headers;
    }

    /**
     * 微博网页接口请求头。
     *
     * @param cookie 微博登录 cookie（含 XSRF-TOKEN）
     */
    public static Map<String, String> weiboHeaders(String cookie) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", cookie);
        headers.put("referer", "https://weibo.com");
        String xsrf = cookieValue(cookie, "XSRF-TOKEN");
        if (StringUtils.isNotBlank(xsrf)) {
            headers.put("x-xsrf-token", xsrf);
        }
        return headers;
    }

    /**
     * 微博视频号（h5.video.weibo.com）请求头。
     */
    public static Map<String, String> weiboVideoHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("referer", "https://h5.video.weibo.com");
        return headers;
    }

    /**
     * 从 cookie 字符串中提取指定 key 的值（对应 auto-task-web 的
     * {@code CommonExtractor.extractorCookieValue}）。
     */
    public static String cookieValue(String cookieStr, String key) {
        if (StringUtils.isBlank(cookieStr) || StringUtils.isBlank(key)) {
            return null;
        }
        for (String cookie : cookieStr.split(";")) {
            cookie = cookie.trim();
            int idx = cookie.indexOf('=');
            if (idx > 0 && cookie.substring(0, idx).trim().equals(key)) {
                return cookie.substring(idx + 1).trim();
            }
        }
        return null;
    }
}
