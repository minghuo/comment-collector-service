package com.sysj.collector.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条链接的问题描述（自动分派的 {@code invalidUrls} / {@code unsupportedLinks} 元素）。
 *
 * <p>存在的意义：旧响应只有一个 {@code unsupportedUrls: ["..."]} 字符串数组，
 * 调用方无法区分"平台没接入"和"链接形态不支持"，只能把原因写死在注释里。
 * 现在每条问题链接都带稳定错误码，可据此决定是换链接、换平台还是补 cookie。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkIssue {

    /** 原始链接（已去首尾空白与 # 注释；非法链接保留调用方原样输入） */
    private String url;

    /** 错误码，见 {@link DispatchErrorCodes} */
    private String errorCode;

    /** 可直接展示给用户的原因 */
    private String reason;
}
