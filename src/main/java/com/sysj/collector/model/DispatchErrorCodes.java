package com.sysj.collector.model;

/**
 * 自动分派（{@code POST /api/collect/dispatch}）的逐链接/逐分组错误码。
 *
 * <p>与 HTTP 状态码的分工：状态码回答"这一批整体能不能用"（400/422/503/200），
 * 错误码回答"具体哪条链接、因为什么"。调用方通常按链接重试，所以错误码必须稳定、可枚举，
 * 不要用自然语言当条件分支。
 */
public final class DispatchErrorCodes {

    /** 链接本身不是合法的 http/https 链接（空串、"abc"、ftp:// 等）。HTTP 400。 */
    public static final String INVALID_LINK = "INVALID_LINK";

    /** 链接所属平台未接入本服务（规则表里没有该域名）。HTTP 422。 */
    public static final String PLATFORM_UNSUPPORTED = "PLATFORM_UNSUPPORTED";

    /**
     * 平台已接入，但该链接形态不被支持（例：微博视频链接未提供 cookie —— 视频只有本地采集支持）。
     * HTTP 422。
     */
    public static final String LINK_UNSUPPORTED = "LINK_UNSUPPORTED";

    /** 平台已识别，但 {@code platform_feature_config} 里该 platform:feature 不存在或被禁用。HTTP 422。 */
    public static final String FEATURE_DISABLED = "FEATURE_DISABLED";

    /** 异步队列已满，本分组被拒（其余分组照常执行）。HTTP 503。 */
    public static final String QUEUE_FULL = "QUEUE_FULL";

    private DispatchErrorCodes() {
    }
}
