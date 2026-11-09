package com.sysj.collector.core.provider.xhs.sign;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 小红书 Cookie 解析。
 *
 * <p>签名只硬依赖 {@code a1}（52 位），{@code webBuild} 决定 x-s-common 的 x4，
 * 完整 cookie 串原样回传（少一个字段都可能被判游客态）。
 */
public class XhsCookie {

    private final String raw;
    private final Map<String, String> values;

    private XhsCookie(String raw, Map<String, String> values) {
        this.raw = raw;
        this.values = values;
    }

    /** 解析 {@code k=v; k=v} 形式的 Cookie；空串返回 {@link #empty()}。 */
    public static XhsCookie parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return empty();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            values.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
        }
        return new XhsCookie(raw.trim(), values);
    }

    public static XhsCookie empty() {
        return new XhsCookie("", Map.of());
    }

    public boolean isBlank() {
        return StringUtils.isBlank(raw);
    }

    /** a1 是 x-s / x-s-common 的必填项。 */
    public String a1() {
        return values.get("a1");
    }

    /** webBuild 随版本变化，缺省时签名用 6.56.3（与 xhshow 一致）。 */
    public String webBuild() {
        return values.get("webBuild");
    }

    /** 浏览器 localStorage 的 b1 指纹；Cookie 里一般没有，允许调用方单独传。 */
    public String b1() {
        return values.get("b1");
    }

    /** 原样回传（不重排、不补字段）。 */
    public String header() {
        return raw;
    }

    /** 日志脱敏：只留长度与关键字段是否存在，避免把会话写进日志。 */
    public String maskedSummary() {
        return "a1=" + (StringUtils.isBlank(a1()) ? "缺失" : abbreviate(a1()))
                + ", web_session=" + (StringUtils.isBlank(values.get("web_session")) ? "缺失" : "***")
                + ", webBuild=" + StringUtils.defaultIfBlank(webBuild(), "-");
    }

    private static String abbreviate(String value) {
        return value.length() <= 8 ? "***" : value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }
}
