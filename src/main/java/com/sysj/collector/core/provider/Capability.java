package com.sysj.collector.core.provider;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 供应商能力矩阵 `[借鉴 B-26]`。
 *
 * <p>解决的问题：此前"某供应商能不能做某件事"只存在于注释和口头约定里 ——
 * 例如"要采二级评论"时无从判断哪些供应商支持，只能靠遍历试错（失败一次才知道不支持）；
 * FR-18 要求的 {@code supportsSync()} 更是**根本没有对应字段**，无法判断。
 *
 * <p>现在能力在**两处**声明，由 {@link ProviderCapabilityValidator} 在启动时做一致性校验：
 * <ul>
 *   <li>实现类上的 {@link ProviderCapability} 注解 —— 代码真实能力；</li>
 *   <li>{@code platform_feature_config.providers[].capabilities} —— 配置声明。</li>
 * </ul>
 *
 * <p>请求侧通过 {@code requiredCapabilities} 表达诉求（如"要采二级评论"→ {@link #SUB_COMMENT}），
 * 路由在健康/熔断过滤之后、激活阈值之前按"**必须全部满足**"过滤候选。
 */
public enum Capability {

    /** 能采一级评论（所有评论类供应商都应具备）。 */
    COMMENT,

    /** 能采二级评论 / 回复（支持 {@code extra.commentId}）。 */
    SUB_COMMENT,

    /** 游标翻页：可把上一页返回的游标原样回传继续取。 */
    CURSOR_PAGING,

    /** 页码翻页：以页码 / 偏移量翻页。 */
    PAGE_PAGING,

    /**
     * 支持**即时返回**（同步接口 `POST /api/collect` 可直接拿到数据）。
     *
     * <p>这是 FR-18 一直无法判断的那一项：若某供应商只能"提交作业 + 轮询结果"，
     * 则不声明本能力，同步请求就不会选到它。
     *
     * <p>当前 9 个供应商都是同步 HTTP 调用，所以**全部声明了本能力** ——
     * 也就是说这个过滤器今天不会过滤掉任何真实供应商；它的价值在于
     * (a) 未来接入异步型供应商时无需改代码，(b) 让"同步可用性"成为可查询的事实而非注释。
     */
    SYNC_SUPPORTED,

    /** 支持走代理（含"代理失败自动直连"）。 */
    PROXY,

    /** 需要登录态 / 凭据（如微博 Cookie）。 */
    LOGIN_STATE;

    /**
     * 宽松解析：忽略大小写与空白，未知值**丢弃**（不抛异常）。
     *
     * <p>丢弃而非报错：能力是"加分项"，配置里写错一个不该让整条采集链路失败；
     * 写错的项会由 {@link ProviderCapabilityValidator} 在启动时告警暴露出来。
     */
    public static Set<Capability> parse(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        EnumSet<Capability> result = EnumSet.noneOf(Capability.class);
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                result.add(valueOf(s.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // 未知能力名：丢弃，由启动校验告警
            }
        }
        return result;
    }

    /** 判断 {@code has} 是否覆盖 {@code required} 的全部项。 */
    public static boolean covers(Set<Capability> has, Set<Capability> required) {
        return required == null || required.isEmpty() || (has != null && has.containsAll(required));
    }
}
