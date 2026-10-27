package com.sysj.collector.exception;

/**
 * <b>确定性失败</b>的供应商调用异常：同样的输入再调一次必然是同样的结果。
 *
 * <p>典型场景：{@code STATUS_URL_ERROR}（链接不支持，如微信公众号非永久链接）、
 * {@code STATUS_PARAM}（参数缺失/内容已删除）。这类失败重试只会白耗供应商 DPS 配额，
 * 因此 {@code RetryStage} 收到本类会<b>立即上抛、不消耗重试次数</b>，由门面直接切换到下一个候选供应商。
 *
 * <p>与 {@link RateLimitedException} 的区别：后者是<b>本服务自己的保护动作</b>（令牌桶没额度），
 * 同样不该重试，但原因不在上游，而是当前限速配置已经饱和。
 */
public class NonRetryableProviderException extends ProviderInvocationException {

    public NonRetryableProviderException(String providerKey, String message) {
        super(providerKey, message);
    }
}
