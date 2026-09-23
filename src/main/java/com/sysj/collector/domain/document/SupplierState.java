package com.sysj.collector.domain.document;


import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * 供应商运行时状态文档。
 *
 * <p>集合：{@code supplier_state}
 *
 * <p>记录供应商的实时运行指标，用于路由决策和健康检查。
 */
@Data
@Document(collection = "supplier_state")
public class SupplierState {

    @Id
    private String id;

    /** 供应商Key */
    private String supplierKey;

    /** 平台编码 */
    private String platformCode;

    /** 功能编码 */
    private String featureCode;

    /** 当前QPS */
    private Double currentQps;

    /** 待处理子任务数 */
    private Integer queueDepth;

    /** 最后心跳时间 */
    private Instant lastHeartbeat;

    /** 连续失败次数 */
    private Integer consecutiveFailures;

    /** 健康状态：UP / DOWN / DEGRADED */
    private String healthStatus;

    /** 最后成功时间 */
    private Instant lastSuccessTime;

    /** 最后失败时间 */
    private Instant lastFailureTime;

    /** 累计成功次数 */
    private Long totalSuccess;

    /** 累计失败次数 */
    private Long totalFailure;

    /** 平均响应时间（毫秒） */
    private Double avgResponseTime;

    /** 更新时间 */
    private Instant updateTime;
}
