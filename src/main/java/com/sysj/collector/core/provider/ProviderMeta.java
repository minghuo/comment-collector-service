package com.sysj.collector.core.provider;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 供应商注册元数据（**代码即配置**）。
 *
 * <p>标在 {@link CommentProvider} 实现类上，启动/巡检时由
 * {@code SupplierRegistrySyncService} 扫描并自动写入 {@code platform_feature_config}：
 * <ol>
 *   <li>功能文档（platform:feature）不存在 → 创建并放入本供应商；</li>
 *   <li>文档存在但缺本供应商 → 追加（使用注解里的默认参数）；</li>
 *   <li>供应商已存在 → 只把 {@code capabilities} 对齐为代码声明（{@link ProviderCapability}），
 *       运维调过的速率/优先级/健康开关等参数<b>一律不动</b>。</li>
 * </ol>
 * 之后由既有链路自动派生：{@code supplier_state} 补条目、各等级功能项追加（基础等级）。
 * <b>新增供应商 = 写实现类 + 打两个注解（@Component("key") + @ProviderMeta），无需任何 DB 配置。</b>
 *
 * <p>注解参数只是**新条目的初值**：入库后以 DB 为准（改参数请改 DB 或 init 脚本，
 * 注解不会再覆盖已存在条目的这些字段）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProviderMeta {

    /** 平台编码，如 weibo / tieba / vivo_bbs（必填）。 */
    String platform();

    /** 功能编码，默认 comment。 */
    String feature() default "comment";

    /** 供应商名称（展示用，必填）。 */
    String name();

    /** 功能中文名；功能文档不存在需创建时使用，空则生成 "{platform}/{feature}"。 */
    String featureName() default "";

    /** 新条目的令牌桶速率（permits/秒）初值。 */
    double ratePerSecond() default 0.5;

    /** 新条目的最大重试次数初值。 */
    int maxRetry() default 2;

    /** 新条目的路由优先级初值（越小越优先；50 = 排在运维调优过的供应商之后）。 */
    int priority() default 50;

    /** 新条目的 Bulkhead 并发上限初值。 */
    int maxConcurrency() default 2;

    /** 新条目的单次调用超时初值（毫秒）。 */
    long timeoutMs() default 20000;

    /**
     * 新条目的流控效果初值：THROTTLE_QUEUE（默认）/ REJECT / WARM_UP。
     *
     * <p>默认排队而不是拒绝：{@code REJECT} 会让并发冲上限的请求直接失败并叠加重试，
     * 最终整条链接采集失败（2026-10-27 头条并发联调实证）。
     *
     * <p><b>单候选与多候选一律排队</b>（2026-10-27 定稿）：多候选平台里"候选"未必真的可用——
     * 有的候选参与条件判断后实际仍是单供应商，有的会临时下线/熔断；按 QPS 匀速发请求时
     * 正确行为就是排队等令牌，而不是把请求丢掉再叠加重试。排队超时（{@link #maxQueueWaitMs()}）
     * 仍会让 facade 切到下一个候选，failover 语义不受影响。
     *
     * <p>只影响新插入条目；存量条目的取值以 {@code platform_feature_config} 为准
     * （同步服务不会用注解覆盖已有条目的 rate/flow）。
     */
    String flowEffect() default "THROTTLE_QUEUE";

    /** 新条目的 THROTTLE_QUEUE 最长排队等待初值（毫秒），仅 {@code THROTTLE_QUEUE} 生效。 */
    long maxQueueWaitMs() default 30000;
}
