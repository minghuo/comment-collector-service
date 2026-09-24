package com.sysj.collector.core.pipeline;

import com.sysj.collector.core.circuit.CircuitState;
import com.sysj.collector.core.router.DynamicProviderRouter;
import com.sysj.collector.exception.ProviderInvocationException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 熔断闸门（§8.1，借鉴 B-15）。
 *
 * <p><b>为什么路由已经过滤过熔断，这里还要再判一次</b>：路由的过滤发生在"选候选"的瞬间（t0），
 * 而真正执行发生在 t1，两者之间可能隔着限流排队、甚至整个候选列表的遍历。
 * 期间熔断器完全可能刚从 CLOSED 跳到 OPEN。这是一处典型的 TOCTOU ——
 * 路由的过滤是**择优**（避免把明显不通的候选排进来），执行前的这一跳才是**闸门**（权威判定）。
 *
 * <p>副作用说明：{@code allowRequest} 在冷却期结束时会推进状态机（OPEN→HALF_OPEN）并占一个半开探测名额，
 * 因此它**不是**幂等的只读查询 —— 每个供应商调用链只应调用一次（本阶段正是如此）。
 */
@Slf4j
@Component
public class CircuitBreakerStage implements ProviderInvocationStage {

    private final DynamicProviderRouter router;

    public CircuitBreakerStage(DynamicProviderRouter router) {
        this.router = router;
    }

    @Override
    public int order() {
        return Stages.CIRCUIT_BREAKER;
    }

    @Override
    public String name() {
        return "CircuitBreaker";
    }

    @Override
    public <T> T invoke(InvocationContext ctx, Supplier<T> next) {
        if (!router.allowProvider(ctx.platformCode(), ctx.featureCode(), ctx.providerKey())) {
            CircuitState state = router.circuitStateOf(ctx.platformCode(), ctx.featureCode(), ctx.providerKey());
            log.warn("熔断闸门拒绝: key={} state={}", ctx.supplierKey(), state);
            throw new ProviderInvocationException(ctx.providerKey(),
                    "供应商 [" + ctx.providerKey() + "] 处于熔断状态（" + state + "），本次不发起调用");
        }
        return next.get();
    }
}
