package com.sysj.collector.controller;

import com.sysj.collector.model.DispatchErrorCodes;
import com.sysj.collector.model.LinkDispatchResult;
import org.springframework.http.HttpStatus;

/**
 * 自动分派结果 → HTTP 状态码的映射（{@code POST /api/collect/dispatch}）。
 *
 * <p>拆成独立类是为了可单测：状态码语义一旦写错，调用方要么重试一个永远不会成功的请求，
 * 要么放弃一个只是过载的正确请求。
 *
 * <ul>
 *   <li>有链接建成任务 → <b>200</b>（部分成功也返回 200，逐条结果见 {@code linkResults}）</li>
 *   <li>一条都没成，且原因为非法链接 → <b>400</b>（请求问题，改链接再来）</li>
 *   <li>一条都没成，原因为平台未接入/链接形态不支持/功能未开启 → <b>422</b></li>
 *   <li>一条都没成，原因是异步队列已满 → <b>503</b>（服务端过载，可稍后重试）</li>
 * </ul>
 */
public final class LinkDispatchStatus {

    /** 解析响应状态码。 */
    public static HttpStatus resolve(LinkDispatchResult result) {
        if (result == null) {
            return HttpStatus.BAD_REQUEST;
        }
        if (result.isSuccess()) {
            return HttpStatus.OK;
        }
        String code = result.getErrorCode();
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (code) {
            case DispatchErrorCodes.QUEUE_FULL -> HttpStatus.SERVICE_UNAVAILABLE;
            case DispatchErrorCodes.INVALID_LINK -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private LinkDispatchStatus() {
    }
}
