package com.sysj.collector.model;


import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 任务提交请求DTO
 */
@Data
public class TaskSubmitRequest {

    /** 用户ID */
    private String userId;

    /** 用户等级编码 */
    private String userTierCode;

    /** 平台编码 */
    private String platform;

    /** 功能编码 */
    private String function;

    /** 采集链接列表 */
    private List<String> links;

    /** 模式：SYNC / ASYNC */
    private String mode;

    /** 请求参数（JSON字符串） */
    private String requestParams;

    /** 供应商约束 */
    private String supplierConstraint;

    /** 回调URL */
    private String callbackUrl;

    /**
     * 提交幂等键（可空）。
     *
     * <p>调用方为同一次业务提交传入相同 key 时，重复提交返回已建成的主任务（taskId 不变），
     * 不会重复建任务。建议取"业务侧单据号"等天然唯一值，而不是随机数。
     */
    private String idempotencyKey;

    /**
     * 服务端自动翻页（可空）：true 时采完一页自动续采下一页（受 max-pages 限制）；
     * false 一任务一页；null 取全局默认 {@code collector.task.auto-paging.enabled}（默认 false）。
     */
    private Boolean autoPage;
}
