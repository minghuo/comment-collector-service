package com.sysj.collector.core.pipeline;

import java.util.function.Supplier;

/**
 * 供应商调用管线的一个横切阶段（§9.2，借鉴 B-12/B-19）。
 *
 * <p>装配方式是经典的**装饰器链**：`stage[0](stage[1](…(stage[n](provider.fetchComments))))`。
 * 阶段按 {@link #order()} 升序由外到内装配 —— order 小的在最外层，
 * 因此 order 小的阶段"包住"order 大的阶段。
 *
 * <p>约定：
 * <ul>
 *   <li>阶段必须**无状态**（差异都在 {@link InvocationContext} 里），否则并发下会串味。</li>
 *   <li>阶段失败一律抛 {@code ProviderInvocationException} 的子类（RuntimeException）——
 *       这样 {@link #invoke} 不必声明受检异常，与 {@code CommentProvider#fetchComments} 的签名一致。</li>
 *   <li>凡是获取了资源（信号量、线程）的阶段，必须用 {@code try/finally} 保证释放。</li>
 *   <li>{@code next.get()} 抛出的异常**必须原样往上传**；只有本阶段自己要拒绝时才换成自己的异常。</li>
 * </ul>
 */
public interface ProviderInvocationStage {

    /**
     * 装配顺序：数值小的在外层（包住数值大的）。
     * 已占用的序号见 {@link Stages}。
     */
    int order();

    /** 阶段名，仅用于日志与诊断。 */
    String name();

    /**
     * 执行本阶段。
     *
     * @param ctx  本次调用的上下文
     * @param next 内层链（调用它才会继续往里走）
     * @return 内层链的结果
     */
    <T> T invoke(InvocationContext ctx, Supplier<T> next);

    /** 阶段顺序常量 —— 集中定义，避免各阶段各自写魔数导致装配顺序漂移。 */
    final class Stages {
        /** Bulkhead：并发隔离，最外层。 */
        public static final int BULKHEAD = 100;
        /** CircuitBreaker：快速失败。 */
        public static final int CIRCUIT_BREAKER = 200;
        /** RateLimiter：控速。 */
        public static final int RATE_LIMIT = 300;
        /** Retry：重试（必须在 Timeout 外层，理由见 §8.1）。 */
        public static final int RETRY = 400;
        /** Timeout：单次尝试限时，最内层。 */
        public static final int TIMEOUT = 500;

        private Stages() {
        }
    }
}
