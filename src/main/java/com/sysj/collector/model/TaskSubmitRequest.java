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
}
