package com.sysj.collector.domain.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * 子任务文档。
 *
 * <p>集合：{@code sub_task}
 *
 * <p>每个子任务对应主任务中的一个采集链接，由具体供应商执行。
 * 支持重试、供应商切换、优先级动态提升（防饥饿）。
 */
@Data
@Document(collection = "sub_task")
public class SubTask {

    @Id
    private String id;

    /** 所属主任务ID */
    @Indexed
    private String masterTaskId;

    /** 采集链接 */
    private String link;

    /** 当前供应商Key */
    private String providerKey;

    /** 子任务状态：PENDING / RUNNING / RETRYING / SUCCESS / FAILED */
    private String status;

    /** 任务优先级（继承用户等级配置，越小越优先） */
    private int priority;

    /** 优先级提升计数（防饥饿：等待超时后递增） */
    private int agingCount;

    /** 当前重试次数 */
    private int retryCount;

    /** 最大重试次数（从供应商配置继承） */
    private int maxRetry;

    /** 已尝试过的供应商列表（JSON数组字符串），用于供应商切换 */
    private String attemptedProviders;

    /** 执行结果（JSON字符串） */
    private String result;

    /** 错误信息 */
    private String errorMessage;

    /** 下次执行时间（重退避后设置） */
    private Instant nextExecuteTime;

    /** 创建时间 */
    private Instant createTime;

    /** 开始时间 */
    private Instant startTime;

    /** 完成时间 */
    private Instant completeTime;

    /** 更新时间 */
    private Instant updateTime;

    /**
     * 获取有效优先级（基础优先级 + 等待提升）。
     */
    public int getEffectivePriority() {
        return this.priority - (this.agingCount * 10); // 提升数值 = 等待次数×10，数值越小优先级越高
    }
}
