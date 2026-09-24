package com.sysj.collector.exception;

/**
 * 供应商调用管线抛出的异常基类（§8.1）。
 *
 * <p><b>为什么不继承 {@link CollectorException}</b>：{@code CollectorException} 表示"调用方传参/业务用法有误"，
 * 由 {@code GlobalExceptionHandler} 映射为 <b>400</b>；而本类表示"供应商这一跳没打通"，
 * 属于服务端依赖故障，绝不能把调用方误导成参数错误。两者语义相反，必须分开。
 *
 * <p>正常情况下本类不会逃到 Controller —— 门面会捕获它、记录原因并切换到下一个候选供应商。
 * 只有"所有候选都失败"时才会被转换成 {@code CommentCollectResult.failed(...)} 返回给调用方。
 * 兜底的 HTTP 映射（503）只是防御性的。
 */
public class ProviderInvocationException extends RuntimeException {

    /** 出问题的供应商 key（Bean 名）。 */
    private final String providerKey;

    public ProviderInvocationException(String providerKey, String message) {
        super(message);
        this.providerKey = providerKey;
    }

    public ProviderInvocationException(String providerKey, String message, Throwable cause) {
        super(message, cause);
        this.providerKey = providerKey;
    }

    public String getProviderKey() {
        return providerKey;
    }
}
