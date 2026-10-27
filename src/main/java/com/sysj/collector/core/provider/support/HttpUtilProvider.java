package com.sysj.collector.core.provider.support;

import com.sysj.http.HttpUtil;

/**
 * {@link HttpUtil} 共享实例提供者 —— 全项目统一初始化与调度，替代每个 Provider 各自
 * {@code new HttpUtil.Builder()}（移植 auto-task-web 的 HttpUtilProvider 方案；
 * 合并前本项目 15 个 Provider 各建一个实例）。
 *
 * <h3>为什么统一</h3>
 * <ol>
 *   <li><b>日志噪音</b>：每个实例构造都会打 1 条 INFO（{@code HttpUtil 初始化完成}）
 *       + 1 条 WARN（{@code sslUnsafe=true}），15 个实例每次启动 30+ 行；</li>
 *   <li><b>连接复用</b>：每个实例持有独立的 {@code OkHttpClient} 与连接池，
 *       多个采集器访问同一站点（如微博本地/转发都打 weibo.com）时各建各的连接与 TLS 会话，
 *       socket 总量被放大；</li>
 *   <li><b>配置漂移</b>：超时/重试口径改动要改 N 个文件，新增采集器容易漏配；</li>
 *   <li><b>全局副作用重复</b>：{@code sslUnsafe} 默认 true，每个实例都会执行一次 JVM 全局的
 *       {@code Security.setProperty("jdk.certpath.disabledAlgorithms", "")}。</li>
 * </ol>
 *
 * <h3>实例按"口径"划分（共 2 个，懒加载 Holder 惯用法）</h3>
 * <ul>
 *   <li>{@link #apiClient()} —— 直连 API 型供应商（中科天玑/系统接口），代理失败不降级；</li>
 *   <li>{@link #localClient()} —— 本地爬虫型供应商（官方接口直爬/网页解析），
 *       {@code directFallbackOnProxyFailure(true)}：走代理失败自动降级直连。</li>
 * </ul>
 *
 * <h3>共享是否安全</h3>
 * <ul>
 *   <li>库内所有请求为同步 {@code Call.execute()}，不存在 async 调用，共享不受
 *       {@code maxRequests/maxRequestsPerHost} 限制影响；</li>
 *   <li>项目内未使用 {@code cookieJar(true)}（全部 NO_COOKIES），共享不会串 Cookie；</li>
 *   <li>代理池与代理并发槽位在库内本就是进程级单例，实例合并不改变并发语义。</li>
 * </ul>
 *
 * <p>新增 Provider 时<b>不要再 new HttpUtil</b>：按口径取 {@code HttpUtilProvider.apiClient()}
 * 或 {@code localClient()}；确需第三种口径时先在这里加一个 Holder 实例再使用。
 */
public final class HttpUtilProvider {

    /**
     * HTTP 层最大尝试次数（含首次）。
     *
     * <p><b>为什么显式收敛为 1（= 不重试）</b>：库默认 {@code maxRetry=3}，而本项目现在只有
     * <b>唯一一处、更懂语义</b>的重试点：管线 {@code RetryStage}。
     * <ol>
     *   <li>供应商实现：<b>只发一次请求</b>（原内层 {@code RETRY = 3} 已删除），失败统一以
     *       {@code status != STATUS_SUCCESS} 或异常上抛；</li>
     *   <li>管线 {@code RetryStage}：按任务所用平台功能配置的 {@code maxRetry} 重试，
     *       这是 DB/接口可调的业务旋钮，且每次重试都重新过 {@code RateLimitStage} 领令牌；</li>
     *   <li>HTTP 层：本常量 = 1，不重试。</li>
     * </ol>
     * 三层若各自重试 3 次，最坏情况下<b>单个子任务的一次尝试会向上游发出 27 次 HTTP 请求</b>（3×3×3）。
     * 上游看到的是"同一来源短时间猛打"，极易触发封禁/限频 —— 这是"重试乘数叠加"缺陷的成因。
     * 现在只有管线一层可重试：最坏 maxRetry+1 次，且每次都占用一次限流令牌，供应商 DPS 始终可控。
     *
     * <p>改这个值前先确认上层重试仍覆盖瞬时故障。{@code maxRetry(1)} 合法：
     * 库内 {@code RetryPolicy} 取 {@code Math.max(1, maxAttempts)}。
     *
     * <p>超时仍采用库默认值（connect/read/write 30s、call 60s）：<b>60s 的 callTimeout 是网络层
     * 真正的截止线</b> —— 管线的 {@code TimeoutStage} 用 {@code future.cancel(true)} 无法中断
     * OkHttp 的阻塞读，故不要把 callTimeout 调得比预期上游响应时间还大。
     */
    private static final int HTTP_MAX_ATTEMPTS = 1;

    private HttpUtilProvider() {
    }

    /** 直连 API 型（中科天玑 / 系统接口等）：代理失败不降级直连。 */
    public static HttpUtil apiClient() {
        return ApiClientHolder.INSTANCE;
    }

    /** 本地爬虫型（官方接口直爬 / 网页解析）：代理失败自动降级直连。 */
    public static HttpUtil localClient() {
        return LocalClientHolder.INSTANCE;
    }

    private static final class ApiClientHolder {
        private static final HttpUtil INSTANCE = new HttpUtil.Builder()
                .maxRetry(HTTP_MAX_ATTEMPTS)
                .build();
    }

    private static final class LocalClientHolder {
        private static final HttpUtil INSTANCE = new HttpUtil.Builder()
                .maxRetry(HTTP_MAX_ATTEMPTS)
                .directFallbackOnProxyFailure(true)
                .build();
    }
}
