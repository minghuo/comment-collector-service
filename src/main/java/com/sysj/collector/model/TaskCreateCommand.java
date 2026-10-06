package com.sysj.collector.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 建任务命令对象（{@code TaskManagementService.createMasterTask} 的参数收敛）。
 *
 * <p>此前 13 个平铺参数每加一项都要改全部调用方（手工建任务 ×2、自动分派 ×1），
 * 收敛为命令对象后新增字段（如 {@link #autoPage}）只影响真正关心的调用方。
 */
@Data
@Builder
public class TaskCreateCommand {

    /** 用户ID */
    private String userId;

    /** 用户等级编码（决定调度优先级与供应商偏好） */
    private String userTierCode;

    /** 平台编码 */
    private String platformCode;

    /** 功能编码 */
    private String featureCode;

    /** 采集链接列表（服务端自动去重） */
    private List<String> links;

    /** 任务模式：SYNC / ASYNC */
    private String mode;

    /** 请求参数（JSON字符串），透传给供应商 */
    private String requestParams;

    /** 供应商约束（可选，指定供应商key） */
    private String supplierConstraint;

    /** 回调URL（可空；主任务终态后 POST 结果摘要，见接口文档 §4.8） */
    private String callbackUrl;

    /** 自动分派批次 ID（手工建任务传 null） */
    private String dispatchId;

    /** 要求供应商全部具备的能力，可空 */
    private List<String> requiredCapabilities;

    /** 要求供应商不具备的能力，可空 */
    private List<String> excludedCapabilities;

    /** 提交幂等键，可空；同 key 重复提交返回已建成的主任务 */
    private String idempotencyKey;

    /**
     * 服务端自动翻页（可空）：true 采完一页自动续采下一页；false 一任务一页；
     * null 取全局默认 {@code collector.task.auto-paging.enabled}。
     */
    private Boolean autoPage;
}
