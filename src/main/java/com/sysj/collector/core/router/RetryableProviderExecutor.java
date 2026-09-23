package com.sysj.collector.core.router;


import com.sysj.collector.core.provider.CommentProvider;

import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommentCollectRequest;

import lombok.extern.slf4j.Slf4j;
import java.util.List;

/**
 * 带指数退避的重试执行器。
 *
 * <p>重试次数来自 DB {@code provider.maxRetry}，0 = 不重试。
 * 退避策略：第 n 次重试等待 {@code 500ms × 2^(n-1)}。
 * 首次调用（attempt=0）不等待。
 */
@Slf4j
public class RetryableProviderExecutor {

    private RetryableProviderExecutor() {}

    public static CommonEntity<Comment> execute(
            CommentProvider provider,
            CommentCollectRequest request,
            int maxRetry) throws Exception {

        Exception lastEx = null;

        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            if (attempt > 0) {
                long delay = 500L * (1L << (attempt - 1)); // 500ms, 1000ms, 2000ms ...
                log.warn("重试 {}/{}: provider={} 等待{}ms", attempt, maxRetry, provider.providerKey(), delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }

            try {
                return provider.fetchComments(request);
            } catch (Exception e) {
                lastEx = e;
                log.warn("调用失败 attempt={}/{}: provider={} error={}",
                        attempt, maxRetry, provider.providerKey(), e.getMessage());
            }
        }

        throw new RuntimeException(
                "供应商 [" + provider.providerKey() + "] 重试 " + maxRetry + " 次后仍失败", lastEx);
    }
}
