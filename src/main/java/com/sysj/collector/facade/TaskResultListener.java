package com.sysj.collector.facade;

import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommentCollectResult;

/**
 * 子任务执行结果监听器 —— 消费者与任务管理服务的解耦点。
 *
 * <p>外置队列（Redis Stream）后，"采集结果回写子任务/主任务状态"不再发生在
 * 提交方的 {@code CompletableFuture} 回调里（提交与执行已分离，可能跨实例），
 * 而是由消费者执行完成后回调本接口。
 *
 * <p>依赖方向：{@code AsyncTaskConsumer → 本接口(=TaskManagementService) → Facade}，
 * 消费者同时依赖 Facade 与本接口，不会形成循环依赖。
 *
 * <h3>投递语义</h3>
 * 实现方必须对重复投递幂等（至少一次投递）：同一子任务可能因
 * 进程崩溃后消息被 XCLAIM 接管、或 deadline 迁移竞态被执行多次；
 * 终态写入用条件更新保证主任务计数只累加一次。
 */
public interface TaskResultListener {

    /**
     * 一次采集执行的最终结果。
     *
     * @param request 队列消息里的采集请求
     * @param result  采集结果；执行抛异常时为 null
     * @param error   执行异常；成功时为 null
     */
    void onSubTaskOutcome(CommentCollectRequest request, CommentCollectResult result, Throwable error);
}
