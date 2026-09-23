package com.sysj.collector.domain.document;


import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * 供应商运行时状态文档。
 *
 * <p>集合：{@code supplier_state}
 *
 * <p>记录供应商的实时运行指标，用于路由决策和健康检查。
 *
 * <p><b>{@code circuitState} 是唯一的健康真相源</b>（修正 C-12）：
 * 由 {@code core/circuit/ProviderCircuitBreaker} 独占读写，路由据此过滤候选。
 * {@code platform_feature_config.providers[].is_healthy} 降级为"运维强制下线"开关，二者取与。
 * 旧的 {@code healthStatus}（UP/DOWN/DEGRADED）保留为 {@code circuitState} 的人读投影。
 */
@Data
@Document(collection = "supplier_state")
public class SupplierState {

    @Id
    private String id;

    /** 供应商Key，形如 {@code platform:feature:providerKey}，唯一。 */
    @Indexed(unique = true, name = "idx_supplier_key")
    private String supplierKey;

    /** 平台编码 */
    private String platformCode;

    /** 功能编码 */
    private String featureCode;

    /** 供应商 key（与 Spring Bean 名一致），便于按供应商聚合查询。 */
    private String providerKey;

    /** 熔断状态：CLOSED / OPEN / HALF_OPEN —— 健康真相源 */
    private String circuitState;

    /** 进入非 CLOSED 状态的时间；CLOSED 时为 null */
    private Instant circuitOpenedAt;

    /** 当前QPS */
    private Double currentQps;

    /**
     * 自适应限速的**有效速率**（permits/秒）= {@code min(rate_per_second, 1000 / adaptive_delay_ms)}。
     *
     * <p>由 {@code core/ratelimit/AdaptiveRateLimiter} 以 {@code $set} 定点写入，
     * 与熔断器的整档写入互不覆盖。
     *
     * <p><b>这两个字段必须在实体上声明</b>：{@code MongoTemplate} 的字段名映射依赖实体元数据，
     * 实体上没有的属性会被**原样写入**（驼峰），落库形态与其它下划线字段不一致，
     * 而且 {@code find} 回来也读不到（2026-09-23 实测踩过一次）。
     */
    private Double effectiveQps;

    /** 自适应限速的当前延迟（毫秒），预热/降速的直接依据。 */
    private Long adaptiveDelayMs;

    /** 待处理子任务数 */
    private Integer queueDepth;

    /** 最后心跳时间 */
    private Instant lastHeartbeat;

    /** 连续失败次数 */
    private Integer consecutiveFailures;

    /**
     * 健康状态：UP / DOWN / DEGRADED。
     * 由 {@code circuitState} 派生（CLOSED→UP / HALF_OPEN→DEGRADED / OPEN→DOWN），
     * 供只看人读字段的运维脚本使用，**不参与路由判定**。
     */
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
