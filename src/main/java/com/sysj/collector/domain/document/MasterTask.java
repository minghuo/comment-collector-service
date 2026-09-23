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

    /** 回调URL（异步任务完成后回调通知） */
    private String callbackUrl;

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
