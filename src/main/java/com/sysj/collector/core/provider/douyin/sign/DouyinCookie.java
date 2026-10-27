package com.sysj.collector.core.provider.douyin.sign;

import org.apache.commons.lang3.StringUtils;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抖音网页版的 Cookie 视图：解析、归一化、补默认值、生成请求头串。
 *
 * <p>只做<b>纯函数</b>处理，不发任何请求：调用方把浏览器里复制的 {@code document.cookie}
 * 原样塞进 {@code extra.cookie} 即可。
 *
 * <h3>评论接口真正必需的 Cookie</h3>
 * <ul>
 *   <li>{@code msToken} —— 缺失时补一个伪造 token（抖音多数 web 接口接受，见
 *       {@link #fakeMsToken()}；失效时调用方换真 token 重试）</li>
 *   <li>{@code ttwid} —— 设备指纹，缺失通常直接 403，不做补造</li>
 *   <li>{@code webid} —— 缺失时随机 19 位数字补齐（只影响风控画像，不影响鉴权）</li>
 *   <li>{@code s_v_web_id} / {@code verifyFp} —— 缺失时随机生成，同时写进 {@code verifyFp} 与 {@code fp} 两个查询参数</li>
 *   <li>{@code sessionid}/{@code sid_tt} —— 登录态；缺了只能读公开评论（风控更严），仍然放行</li>
 *   <li>{@code uifid} —— websign 用；评论接口不校验，但带上更贴近真实流量</li>
 * </ul>
 *
 * <p>{@link #toString()} 里含 sessionid 等凭据，日志中禁止直接打印实例（只打印
 * {@link #maskedSummary()}）。
 */
public final class DouyinCookie {

    /** 伪造 msToken 的字母表（与平台一致：base64url 变体 + {@code +}、{@code -}） */
    public static final String MS_TOKEN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-";

    /** 伪造 msToken 的长度（平台真实 token 为 120 或 128） */
    public static final int FAKE_MS_TOKEN_LENGTH = 126;

    /** uifid 的多套 Cookie 名（大小写与后缀都出现过，首个非空生效） */
    private static final String[] UIFID_NAMES = {"UIFID", "uifid", "UIFID_TEMP", "uifid_temp", "UIFIDTEMP"};

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, String> values;
    private final String header;

    private DouyinCookie(Map<String, String> values) {
        this.values = values;
        this.header = join(values);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 解析
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 解析 {@code document.cookie} 形态的 Cookie 串。
     *
     * <p>按 {@code ;} 切分、每段只按<b>第一个</b> {@code =} 分割（抖音的值里会出现 {@code =}、
     * {@code /}、{@code %}、中文）；同名键保留第一次出现的值。空串返回空视图而不是 {@code null}，
     * 便于调用方统一走 {@link #isBlank()} 判断。
     */
    public static DouyinCookie parse(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(raw)) {
            for (String part : raw.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = part.substring(0, eq).trim();
                if (name.isEmpty() || values.containsKey(name)) {
                    continue;
                }
                values.put(name, part.substring(eq + 1).trim());
            }
        }
        return new DouyinCookie(values);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 取值
    // ──────────────────────────────────────────────────────────────────────

    /** 按名字取值：先精确匹配，再忽略大小写匹配（Cookie 名大小写不统一）。 */
    public String value(String name) {
        String direct = values.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 是否存在该 Cookie 且非空白。 */
    public boolean has(String name) {
        return StringUtils.isNotBlank(value(name));
    }

    /** 全部 Cookie（只读视图，顺序即 Cookie 串顺序）。 */
    public Map<String, String> values() {
        return java.util.Collections.unmodifiableMap(values);
    }

    /** 已解析的 Cookie 数量。 */
    public int size() {
        return values.size();
    }

    /** 一个 Cookie 都没有（调用方基本可以直接判参数错误）。 */
    public boolean isBlank() {
        return values.isEmpty();
    }

    /** msToken：缺失时可补伪造 token。 */
    public String msToken() {
        return value("msToken");
    }

    /** ttwid：设备指纹，缺失一般直接 403。 */
    public String ttwid() {
        return value("ttwid");
    }

    /** webid：网页端设备 id，缺失时随机补。 */
    public String webid() {
        return value("webid");
    }

    /** 登录会话：Cookie 里出现过的任意一个会话名非空即视为有登录态。 */
    public String sessionId() {
        for (String name : new String[]{"sessionid", "sessionid_ss", "sid_tt"}) {
            String session = value(name);
            if (StringUtils.isNotBlank(session)) {
                return session;
            }
        }
        return null;
    }

    /** 是否有登录态（只影响风控强度，不作为硬性拦截）。 */
    public boolean hasLoginSession() {
        return StringUtils.isNotBlank(sessionId());
    }

    /** uifid：websign 用，评论接口不强制。 */
    public String uifid() {
        for (String name : UIFID_NAMES) {
            String uifid = value(name);
            if (StringUtils.isNotBlank(uifid)) {
                return uifid;
            }
        }
        return null;
    }

    /** {@code s_v_web_id}：verifyFp/fp 两个查询参数的来源。 */
    public String verifyFpCookie() {
        return value("s_v_web_id");
    }

    /** 请求头 {@code cookie} 的完整串。 */
    public String header() {
        return header;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 补默认值
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 补齐可安全伪造的 Cookie：{@code msToken}（126 字符 + {@code ==}）、{@code webid}（19 位数字）、
     * {@code s_v_web_id}（36 字符 verify id）。
     *
     * <p>刻意<b>不</b>伪造 {@code ttwid}/{@code sessionid}：这两者伪造只会换来更严格的校验，
     * 让真实的 403 变成难以定位的假数据。
     */
    public DouyinCookie withDefaults() {
        Map<String, String> filled = new LinkedHashMap<>(values);
        if (StringUtils.isBlank(value("msToken"))) {
            filled.put("msToken", fakeMsToken());
        }
        if (StringUtils.isBlank(value("webid"))) {
            filled.put("webid", randomWebId());
        }
        if (StringUtils.isBlank(value("s_v_web_id"))) {
            filled.put("s_v_web_id", randomVerifyId());
        }
        return new DouyinCookie(filled);
    }

    /** 日志安全的摘要：只暴露键名与登录态/ttwid 是否存在，绝不输出值。 */
    public String maskedSummary() {
        return "cookies=" + values.size()
                + ", msToken=" + (has("msToken") ? "present" : "absent")
                + ", ttwid=" + (has("ttwid") ? "present" : "absent")
                + ", webid=" + (has("webid") ? "present" : "absent")
                + ", uifid=" + (StringUtils.isNotBlank(uifid()) ? "present" : "absent")
                + ", login=" + (hasLoginSession() ? "yes" : "no");
    }

    /** {@inheritDoc} 含凭据，禁止写日志。 */
    @Override
    public String toString() {
        return "DouyinCookie(" + maskedSummary() + ")";
    }

    // ──────────────────────────────────────────────────────────────────────
    // 伪造值
    // ──────────────────────────────────────────────────────────────────────

    /** 伪造 msToken：126 个随机字符 + {@code ==}（共 128 字符，落在平台真实长度区间内）。 */
    public static String fakeMsToken() {
        StringBuilder builder = new StringBuilder(FAKE_MS_TOKEN_LENGTH + 2);
        for (int i = 0; i < FAKE_MS_TOKEN_LENGTH; i++) {
            builder.append(MS_TOKEN_ALPHABET.charAt(RANDOM.nextInt(MS_TOKEN_ALPHABET.length())));
        }
        return builder.append("==").toString();
    }

    /** 随机 19 位数字 webid（平台 webid 即 19 位十进制串）。 */
    public static String randomWebId() {
        StringBuilder builder = new StringBuilder(19);
        builder.append(1 + RANDOM.nextInt(9));
        for (int i = 1; i < 19; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }

    /**
     * 随机 verify id：36 字符 UUID v4 骨架（{@code 8-4-4-4-12}），字符集 {@code 0-9A-Za-z}。
     *
     * <p>平台校验的是长度与结构，不校验字符集之外的东西；{@code version=4}、{@code variant∈[89ab]}
     * 与浏览器生成形态保持一致。
     */
    public static String randomVerifyId() {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        char[] chars = new char[36];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = alphabet.charAt(RANDOM.nextInt(alphabet.length()));
        }
        chars[8] = '-';
        chars[13] = '-';
        chars[18] = '-';
        chars[23] = '-';
        chars[14] = '4';
        chars[19] = "89ab".charAt(RANDOM.nextInt(4));
        return new String(chars);
    }

    private static String join(Map<String, String> values) {
        List<String> parts = new ArrayList<>(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join("; ", parts);
    }
}
