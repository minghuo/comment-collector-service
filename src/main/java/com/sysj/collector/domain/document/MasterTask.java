package com.sysj.collector.domain.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * 主任务文档。
 *
 * <p>集合：{@code master_task}
 *
 * <p>用户每次调用创建任务接口生成一个主任务，
 * 一个主任务包含一个或多个采集链接，每个链接对应一个子任务。
 */
@Data
@Document(collection = "master_task")
public class MasterTask {

    @Id
    private String id;

    /** 用户ID */
    @Indexed
    private String userId;

    /** 用户等级编码 */
    private String userTierCode;

    /** 平台编码 */
    @Indexed
    private String platformCode;

    /** 功能编码 */
    private String featureCode;

    /** 任务模式：SYNC / ASYNC */
    private String mode;

    /** 任务状态：PENDING / ACTIVE / COMPLETED / FAILED */
    private String status;

    /** 采集链接列表 */
    private List<String> links;

    /** 请求参数（JSON字符串） */
    private String requestParams;

    /** 供应商约束（可选，指定供应商key） */
    private String supplierConstraint;

    /**
     * 提交幂等键（可空）。
     *
     * <p>调用方为同一次业务提交传入相同 key 时，重复提交直接返回已建成的主任务而不会重复建任务；
     * 并发同 key 提交由唯一部分索引 {@code idx_idempotency_key} 兜底（见 {@code db/init-comment-collector.js}）。
     */
    @Indexed(unique = true, sparse = true)
    private String idempotencyKey;

    /** 回调URL（异步任务完成后回调通知） */
    private String callbackUrl;

    /**
     * 自动分派批次 ID（可空）。
     *
     * <p>混合平台链接经 {@code POST /api/collect/dispatch} 自动识别平台后，
     * 同一批拆分出的各平台主任务共享同一 {@code dispatchId}，
     * 可用 {@code GET /api/dispatch/{dispatchId}} 聚合查询批次进度。
     */
    @Indexed
    private String dispatchId;

    /**
     * 本任务所有子任务**要求具备**的供应商能力（名称见 {@code core/provider/Capability}），可空。
     *
     * <p>由自动分派按链接特征推导（如微博链接带 cookie → 要求 {@code LOGIN_STATE}），
     * 建任务时落库，提交子任务时透传给路由。
     */
    private List<String> requiredCapabilities;

    /**
     * 本任务所有子任务**要求不具备**的供应商能力，可空（语义见 {@link #requiredCapabilities}）。
     */
    private List<String> excludedCapabilities;

    /**
     * 服务端自动翻页（可空）。
     *
     * <p>true：子任务采到 hasMore=true 时自动生成带游标的续采子任务
     * （深度受 {@code collector.task.auto-paging.max-pages} 限制，续采子任务同样计入本任务）；
     * false：一任务一页，翻页由调用方驱动；
     * null：取全局默认 {@code collector.task.auto-paging.enabled}（默认 false）。
     */
    private Boolean autoPage;

    /** 总链接数 */
    private int totalLinks;

    /** 成功链接数 */
    private int successLinks;

    /** 失败链接数 */
    private int failedLinks;

    /** 错误信息 */
    private String errorMessage;

    /** 创建时间 */
    private Instant createTime;

    /** 开始时间 */
    private Instant startTime;

    /** 完成时间 */
    private Instant completeTime;

    /** 更新时间 */
    private Instant updateTime;
}
