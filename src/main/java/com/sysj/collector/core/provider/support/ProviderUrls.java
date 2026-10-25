package com.sysj.collector.core.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 供应商通用的内容 ID 解析工具。
 *
 * <p>任务提交时调用方给的是「链接」，而各平台接口需要的是「内容 ID」（B站 BV 号、抖音视频 id、
 * 小红书笔记 id、头条 groupId 等）。本类提供统一的降级解析：显式传入 &gt; 链接最后一段 &gt; 原始值。
 *
 * <p>对照 {@code auto-task-web}：它对每个平台写了专门的 URL 解析（如 B 站要先调接口换 commentId）。
 * 本服务是中间件，先用"链接最后一段"这一通用规则兜底，需要更精确解析时由调用方通过
 * {@code extra.mid} 直接传入即可。
 */
public final class ProviderUrls {

    private ProviderUrls() {
    }

    /**
     * 解析内容 ID。
     *
     * @param explicitMid {@code extra.mid}（优先）
     * @param url         来源链接（{@code request.fromUrl}）
     * @param targetId    目标 id（{@code request.targetId}，通常就是链接本身）
     */
    public static String resolveMid(String explicitMid, String url, String targetId) {
        if (StringUtils.isNotBlank(explicitMid)) {
            return explicitMid;
        }
        String fromUrl = lastPathSegment(url);
        if (StringUtils.isNotBlank(fromUrl)) {
            return fromUrl;
        }
        String fromTarget = lastPathSegment(targetId);
        return StringUtils.defaultIfBlank(fromTarget, targetId);
    }

    /**
     * 取 URL 的最后一段路径（去掉 query 与 fragment）；无法解析时返回 null。
     *
     * <pre>
     * https://www.bilibili.com/video/BV1xx411c7mD?spm=1 → BV1xx411c7mD
     * https://www.xiaohongshu.com/explore/65f0a1b2      → 65f0a1b2
     * 1234567890                                        → 1234567890
     * </pre>
     */
    public static String lastPathSegment(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        String s = url.trim();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        int query = s.indexOf('?');
        if (query >= 0) {
            s = s.substring(0, query);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int slash = s.lastIndexOf('/');
        String seg = slash >= 0 ? s.substring(slash + 1) : s;
        return StringUtils.isBlank(seg) ? null : seg;
    }

    /**
     * 把查询参数值编码后拼进 URL（{@code application/x-www-form-urlencoded} 语义）。
     *
     * <p><b>修 R8：为什么所有 query 参数值都必须过这里</b>。这些值来自调用方
     * （{@code extra.mid}/{@code commentId}/{@code cursor}/{@code buffer}/{@code videoId}）
     * 或上游返回的游标，直接字符串拼接时：
     * <ul>
     *   <li>值里的 {@code &} 会被当成参数分隔符 —— 攻击者可借 {@code mid} 追加
     *       {@code &apiKey=...} 之类的参数（参数注入/凭据覆盖）；</li>
     *   <li>值里的 {@code #} 会把后面全部内容变成 fragment，请求打到别的路径/丢参数；</li>
     *   <li>值里的空格、中文、{@code %}会让请求行不合法，被上游 400。</li>
     * </ul>
     *
     * <p>已有先例：{@code WechatCommentCrawlerProvider} 对 {@code url} 参数用了
     * {@code URLCodeUtil.getURLEncode}，但对同一 URL 里的 {@code buffer}/{@code comment_id}
     * 没做 —— 同一处一半编码一半不编码，等于没编码。
     */
    public static String encodeParam(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
