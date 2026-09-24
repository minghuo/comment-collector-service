package com.sysj.collector.core.pipeline;

import com.sysj.collector.core.ratelimit.FlowEffect;
import com.sysj.collector.core.router.DynamicProviderRouter;
import com.sysj.collector.exception.RateLimitedException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 限流取令牌（§8.2/§8.3，借鉴 B-11/B-14）。
 *
 * <p>有效速率 = {@code min(rate_per_second, 1000 / adaptive_delay_ms)}，
 * 由自适应限速按最近的响应耗时自动下调。取不到令牌时的行为由 {@code providers[].flow_effect} 决定：
 * {@code REJECT} / {@code WARM_UP} 等到限时就放弃（抛本阶段的异常），{@code THROTTLE_QUEUE} 排队等待。
 *
 * <p><b>令牌在"执行前一刻"领取</b>：这是 P2-1/P2-3 时刻意做出的设计决定 ——
 * 若把限流放进路由的 {@code select()}，那些最终没被执行的候选会白耗配额。
 *
 * <p><b>放在熔断闸门之内</b>：熔断是"要不要打"（质变，应当最省成本地先判），
 * 限流是"打多快"（量变，会阻塞等待）。顺序反过来会让一个已熔断的供应商先占着排队名额。
 * 代价是半开探测名额可能被限流拒绝白白消耗一次 —— 该泄漏已由
 * {@code ProviderCircuitBreaker} 的"探测名额超时回收"兜住（见 §8.5）。
 */
@Slf4j
@Component
public class RateLimitStage implements ProviderInvocationStage {

    private final DynamicProviderRouter router;

    public RateLimitStage(DynamicProviderRouter router) {
        this.router = router;
    }

    @Override
    public int order() {
        return Stages.RATE_LIMIT;
    }

    @Override
    public String name() {
        return "RateLimit";
    }

    @Override
    public <T> T invoke(InvocationContext ctx, Supplier<T> next) {
        if (!router.tryAcquireProvider(ctx.platformCode(), ctx.featureCode(), ctx.provider())) {
            FlowEffect effect = FlowEffect.of(ctx.provider().getFlowEffect());
            double rate = router.effectiveRateOf(ctx.platformCode(), ctx.featureCode(), ctx.provider());
            throw new RateLimitedException(ctx.providerKey(), effect.name(), rate);
        }
        return next.get();
    }
}
