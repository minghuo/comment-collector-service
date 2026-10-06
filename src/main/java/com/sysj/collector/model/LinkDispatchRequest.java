package com.sysj.collector.model;

import lombok.Data;

import java.util.List;

/**
 * 混合平台链接自动分派请求（{@code POST /api/collect/dispatch}）。
 *
 * <p>调用方只提交链接列表，服务端按链接特征自动识别平台并拆分为各平台主任务；
 * 无需再按平台手工分组多次调用 {@code POST /api/tasks}。
 */
@Data
public class LinkDispatchRequest {

    /** 用户ID */
    private String userId;

    /** 用户等级编码（决定任务队列优先级与供应商偏好） */
    private String userTierCode;

    /** 采集链接列表（支持混合平台，自动去重） */
    private List<String> links;

    /**
     * 登录态 Cookie（可空）。
     *
     * <p>非空时，微博等"本地采集 vs Golaxy 渠道"双供应商平台会路由到本地采集（要求 {@code LOGIN_STATE} 能力）；
     * 为空时路由到 Golaxy 渠道（排除 {@code LOGIN_STATE} 供应商）。也可把 cookie 放在
     * {@link #requestParams} 的 {@code cookie} 键里（与 {@code POST /api/tasks} 一致），两者任一非空即视为提供。
     */
    private String cookie;

    /** 请求参数（JSON字符串），原样透传给各平台子任务（含 cookie、翻页参数等） */
    private String requestParams;

    /** 供应商约束（可选，指定供应商key） */
    private String supplierConstraint;

    /** 回调URL（可选，各平台子任务完成后回调通知） */
    private String callbackUrl;

    /**
     * 服务端自动翻页（可空）：true 各平台任务采完一页自动续采；false 一任务一页；
     * null 取全局默认 {@code collector.task.auto-paging.enabled}（默认 false）。
     */
    private Boolean autoPage;
}
