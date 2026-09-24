package com.sysj.collector.core.pipeline;

import com.sysj.collector.core.provider.CommentProvider;
import com.sysj.collector.core.router.DynamicProviderRouter;
import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;
import com.sysj.collector.exception.ProviderInvocationException;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 供应商调用管线（§9.2，借鉴 B-12/B-19）：把散落在门面里的横切关注点收敛成**显式装饰器链**。
 *
 * <pre>
 * Bulkhead → CircuitBreaker → RateLimit → Retry → Timeout → provider.fetchComments(request)
 * </pre>
 *
 * <p>装配顺序由各阶段的 {@link ProviderInvocationStage#order()} 决定（小的在外层），
 * Spring 注入全部阶段后在这里统一排序 —— 顺序是**代码里的常量**，不依赖 Bean 的注册顺序。
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>管线只负责"把一次供应商调用打完"，返回供应商的原始 {@code CommonEntity}；
 *       <b>落库与结果组装不在管线内</b>（由门面负责），否则一次 Mongo 慢写会被算成供应商失败。</li>
 *   <li>统一成败判定（null / {@code status != STATUS_SUCCESS}）在管线内做：它必须在
 *       {@code toResult} 之外，因为门面的 {@code toResult} 同时承担落库职责（§9.4）。</li>
 *   <li>结果上报（熔断 + 自适应限速）唯一入口仍是
 *       {@link DynamicProviderRouter#markProviderOutcome}，由本类在 {@code finally} 里调用 ——
 *       但**只有真正触达供应商的调用才上报**：成功、失败、超时、上游返回失败状态都算；
 *       Bulkhead / 限流 / 熔断闸门的拒绝是我们自己的保护动作，不算（C-44，理由见 {@link #invoke}）。</li>
 * </ul>
 */
@Slf4j
@Component
public class ProviderInvocationPipeline {

    /** 管线总开关；关闭时直接调用供应商（用于压测逐项隔离，见 §9.2）。 */
    @Value("${collector.pipeline.enabled:true}")
    private boolean enabled;

    /** 全局单次调用超时，毫秒；{@code providers[].timeout_ms} 优先。 */
    @Value("${collector.pipeline.timeout-ms:15000}")
    private long defaultTimeoutMs;

    /** 全局默认并发上限；{@code providers[].max_concurrency} 优先。0 = 不限。 */
    @Value("${collector.pipeline.default-max-concurrency:4}")
    private int defaultMaxConcurrency;

    private final DynamicProviderRouter router;
    private final List<ProviderInvocationStage> stages;

    public ProviderInvocationPipeline(DynamicProviderRouter router, List<ProviderInvocationStage> stages) {
        this.router = router;
        this.stages = stages == null ? List.of() : stages.stream()
                .sorted(Comparator.comparingInt(ProviderInvocationStage::order))
                .collect(Collectors.toList());
    }

    @PostConstruct
    public void init() {
        log.info("供应商调用管线已装配: {} → provider.fetchComments | enabled={} timeout={}ms 默认并发上限={}",
                describeChain(), enabled, defaultTimeoutMs,
                defaultMaxConcurrency <= 0 ? "不限" : String.valueOf(defaultMaxConcurrency));
    }

    /**
     * 执行一次供应商调用（含全部横切阶段）。
     *
     * <p><b>只有真正触达供应商的调用才上报运行时状态（C-44）</b>：熔断闸门、限流、Bulkhead
     * 拒绝的都是**我们自己的保护动作**，供应商那边什么都没发生。若把它们计成"供应商失败"，
     * 就会出现最荒唐的因果倒置 —— 并发压力一大，Bulkhead 拒绝率升高，熔断器的失败率随之抬高，
     * 于是把一个**完全健康**的供应商熔断掉；自适应限速也会跟着降速，让压力更集中。
     * 因此用 {@code attempted} 区分"调用了但失败"（要上报）与"根本没调用"（不上报）。
     *
     * @param request  采集请求
     * @param provider 该供应商的 DB 配置
     * @param impl     供应商实现
     * @return 供应商返回的原始结果（已通过统一成败判定）
     * @throws ProviderInvocationException 任一阶段拒绝或调用失败
     */
    public CommonEntity<Comment> invoke(CommentCollectRequest request, ProviderConfig provider, CommentProvider impl) {
        InvocationContext ctx = newContext(request, provider);
        long started = System.nanoTime();
        boolean success = false;
        // 供应商代码在限时阶段可能跑在别的线程上，因此这里必须是跨线程可见的
        AtomicBoolean attempted = new AtomicBoolean(false);
        try {
            CommonEntity<Comment> entity = enabled
                    ? runChain(ctx, () -> attempt(ctx, impl, request, attempted))
                    : attempt(ctx, impl, request, attempted);
            success = true;
            return entity;
        } finally {
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            if (attempted.get()) {
                router.markProviderOutcome(ctx.platformCode(), ctx.featureCode(), provider, latencyMs, success);
            } else {
                log.debug("未触达供应商，跳过运行时状态上报: key={} 原因={}", ctx.supplierKey(),
                        success ? "?" : "被管线前置阶段拒绝");
            }
        }
    }

    /**
     * 真正发起一次供应商调用，并做统一成败判定。
     *
     * <p><b>成败判定必须在重试环**之内**</b>（这是 P2-8 验证时抓到的缺陷）：本项目**所有**供应商
     * 都用"返回 {@code status != STATUS_SUCCESS}"表达调用失败（上游 HTTP 失败、业务错误码等），
     * 把它们放在重试环之外，{@code providers[].max_retry} 就形同虚设 —— 一次都没重试过。
     * 放在环内则"上游返回失败状态"与"抛异常"享有同一条重试策略。
     *
     * <p>代价：确定性的业务失败（例如"链接不支持"）也会被重试 {@code max_retry} 次。
     * 调参建议见文档 §9.5：对失败多为确定性的供应商，把 {@code max_retry} 配小或配 0。
     */
    private CommonEntity<Comment> attempt(InvocationContext ctx, CommentProvider impl,
                                          CommentCollectRequest request, AtomicBoolean attempted) {
        attempted.set(true);
        CommonEntity<Comment> entity = impl.fetchComments(request);
        validate(ctx, entity);
        return entity;
    }

    /**
     * 组装本次调用的上下文（配置缺省值在这里落地）。
     *
     * <p>解析口径与 §9.5 一致：{@code providers[].timeout_ms} 优先，缺失取全局；
     * {@code max_concurrency} 同理，且 {@code 0} 被当作"不限"而不是"默认值"（可显式关掉 Bulkhead）。
     */
    public InvocationContext newContext(CommentCollectRequest request, ProviderConfig provider) {
        Long configuredTimeout = provider.getTimeoutMs();
        long timeout = configuredTimeout != null ? configuredTimeout : defaultTimeoutMs;
        Integer configuredConcurrency = provider.getMaxConcurrency();
        int maxConcurrency = configuredConcurrency != null ? configuredConcurrency : defaultMaxConcurrency;
        return new InvocationContext(request, request.getPlatformCode(), request.getFeatureCode(),
                provider, timeout, maxConcurrency, Math.max(0, provider.getMaxRetry()));
    }

    /** 按 order 由外到内装配并执行。 */
    private <T> T runChain(InvocationContext ctx, Supplier<T> terminal) {
        Supplier<T> call = terminal;
        for (int i = stages.size() - 1; i >= 0; i--) {
            ProviderInvocationStage stage = stages.get(i);
            Supplier<T> next = call;
            call = () -> stage.invoke(ctx, next);
        }
        return call.get();
    }

    /**
     * 统一成败判定（§9.4）。
     *
     * <p>供应商有两种失败表达方式：抛异常、返回 {@code status != STATUS_SUCCESS}。
     * 二者都必须变成异常，否则会出现"不重试、不切换、还返回 success=true + 空列表"的错误行为。
     */
    private void validate(InvocationContext ctx, CommonEntity<Comment> entity) {
        if (entity == null) {
            throw new ProviderInvocationException(ctx.providerKey(),
                    "供应商 [" + ctx.providerKey() + "] 返回 null");
        }
        if (entity.getStatus() != CommonStatusEnum.STATUS_SUCCESS) {
            throw new ProviderInvocationException(ctx.providerKey(),
                    "供应商 [" + ctx.providerKey() + "] 返回失败状态: " + entity.getStatus()
                            + (entity.getMsg() == null ? "" : " / " + entity.getMsg()));
        }
    }

    /** 已装配的链，形如 {@code Bulkhead → CircuitBreaker → …}（供启动日志与诊断接口）。 */
    public String describeChain() {
        if (stages.isEmpty()) {
            return "(未装配任何阶段)";
        }
        return stages.stream().map(ProviderInvocationStage::name).collect(Collectors.joining(" → "));
    }

    /** 已装配的阶段名（只读）。 */
    public List<String> stageNames() {
        return stages.stream().map(ProviderInvocationStage::name).collect(Collectors.toList());
    }

    public boolean isEnabled() {
        return enabled;
    }
}
