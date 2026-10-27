package com.sysj.collector.controller;

import com.sysj.collector.model.DispatchErrorCodes;
import com.sysj.collector.model.LinkDispatchResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LinkDispatchStatus} 单元测试：分派结果 → HTTP 状态码。
 *
 * <p>覆盖四类状态码语义，避免调用方把"服务端过载（503，可重试）"和"链接不支持（422，重试无用）"
 * 混为一谈。
 */
class LinkDispatchStatusTest {

    @Test
    void anyDispatchedLinkYields200EvenWhenPartiallySkipped() {
        // 部分成功仍是 200：失败的分组在 skipped，失败链接在 linkResults 里
        assertEquals(HttpStatus.OK, LinkDispatchStatus.resolve(
                result(true, null, 2, 1)));
    }

    @Test
    void queueFullYields503() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, LinkDispatchStatus.resolve(
                result(false, DispatchErrorCodes.QUEUE_FULL, 0, 3)));
    }

    @Test
    void invalidLinkYields400() {
        assertEquals(HttpStatus.BAD_REQUEST, LinkDispatchStatus.resolve(
                result(false, DispatchErrorCodes.INVALID_LINK, 0, 0)));
    }

    @Test
    void unsupportedPlatformOrFeatureDisabledYields422() {
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, LinkDispatchStatus.resolve(
                result(false, DispatchErrorCodes.PLATFORM_UNSUPPORTED, 0, 0)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, LinkDispatchStatus.resolve(
                result(false, DispatchErrorCodes.LINK_UNSUPPORTED, 0, 0)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, LinkDispatchStatus.resolve(
                result(false, DispatchErrorCodes.FEATURE_DISABLED, 0, 0)));
    }

    @Test
    void missingResultOrErrorCodeFallsBackTo400() {
        assertEquals(HttpStatus.BAD_REQUEST, LinkDispatchStatus.resolve(null));
        // 失败但没给错误码：属于服务端漏填，按请求问题处理而不是 5xx
        assertEquals(HttpStatus.BAD_REQUEST, LinkDispatchStatus.resolve(
                result(false, null, 0, 0)));
    }

    private LinkDispatchResult result(boolean success, String errorCode, int dispatched, int total) {
        return LinkDispatchResult.builder()
                .success(success)
                .errorCode(errorCode)
                .dispatchedUrls(dispatched)
                .totalUrls(total)
                .build();
    }
}
