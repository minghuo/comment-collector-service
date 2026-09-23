package com.sysj.collector.controller;

import com.sysj.collector.exception.CollectorException;
import com.sysj.collector.exception.QueueFullException;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常 → HTTP 状态码映射（**全项目唯一的 {@code @RestControllerAdvice}**）。
 *
 * <p>此前有两处 advice：本类与 {@code AppConfig} 里的内部类。Spring 的
 * {@code ExceptionHandlerExceptionResolver} 按 advice 顺序查找，**先命中的那个 advice 赢**，
 * 于是 {@code AppConfig} 里的 {@code @ExceptionHandler(Exception.class)} 把所有更具体的处理器都盖住了 ——
 * 实测表现为"过载拒绝返回 500 而不是 503"。因此这里合并为唯一一处，并删除了 {@code AppConfig} 中的重复实现。
 *
 * <p>响应体保留 {@code error} 键（历史字段），同时补齐 {@code success/code/message/timestamp}，
 * 避免已有调用方读取 {@code error} 时取不到值。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 过载：异步队列名额不足 → 503，让调用方稍后重试而不是无限堆积。 */
    @ExceptionHandler(QueueFullException.class)
    public ResponseEntity<Map<String, Object>> handleQueueFull(QueueFullException e) {
        log.warn("过载拒绝: queueSize={} capacity={} remaining={}",
                e.getQueueSize(), e.getQueueCapacity(), e.getRemaining());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body("SERVICE_BUSY", e.getMessage(), Map.of(
                        "queueSize", e.getQueueSize(),
                        "queueCapacity", e.getQueueCapacity(),
                        "queueRemaining", e.getRemaining())));
    }

    /** 业务参数/执行异常 → 400。 */
    @ExceptionHandler(CollectorException.class)
    public ResponseEntity<Map<String, Object>> handleCollector(CollectorException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ResponseEntity.badRequest().body(body("BAD_REQUEST", e.getMessage(), null));
    }

    /** 请求体无法反序列化 → 400（此前是无信息的 500）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(body("MALFORMED_BODY", "请求体格式错误，无法解析。" + hintOf(e), null));
    }

    /**
     * 兜底。
     *
     * <p><b>框架自身的语义异常必须保留原状态码</b>：{@code HttpRequestMethodNotSupportedException}（405）、
     * {@code HttpMediaTypeNotSupportedException}（415）等都实现了 Spring 的 {@link ErrorResponse}，
     * 若一律吞成 500，调用方会把"用错方法/用错 Content-Type"误判为服务端故障。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        if (e instanceof ErrorResponse er) {
            HttpStatus status = HttpStatus.resolve(er.getStatusCode().value());
            log.warn("请求异常: status={} type={} message={}",
                    er.getStatusCode().value(), e.getClass().getSimpleName(), e.getMessage());
            return ResponseEntity.status(er.getStatusCode())
                    .body(body("REQUEST_ERROR", e.getMessage(), null));
        }
        // 真正的服务端错误：必须留日志，否则线上 500 无从排查
        log.error("未处理异常: type={} message={}", e.getClass().getName(), e.getMessage(), e);
        return ResponseEntity.internalServerError()
                .body(body("INTERNAL_ERROR", "内部错误: " + e.getMessage(), null));
    }

    /**
     * 针对最容易踩的坑给出直接提示。
     *
     * <p>{@code TaskSubmitRequest.requestParams} 是 **JSON 字符串**字段，
     * 传成 JSON 对象会反序列化失败，而 Jackson 的报错信息本身**不含字段名**
     * （字段路径在 {@code MismatchedInputException#getPath()} 里，不在 {@code getMessage()} 里），
     * 所以必须从 path 取，否则提示永远不会出现。
     */
    private String hintOf(HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        String field = null;
        if (cause instanceof MismatchedInputException mie && mie.getPath() != null) {
            field = mie.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(java.util.Objects::nonNull)
                    .reduce((a, b) -> b)
                    .orElse(null);
        }
        if (field == null) {
            String raw = e.getMessage() == null ? "" : e.getMessage();
            if (raw.contains("requestParams")) {
                field = "requestParams";
            }
        }
        if ("requestParams".equals(field)) {
            return " 提示：requestParams 必须是 JSON 字符串，"
                    + "例如 \"{\\\"url\\\":\\\"https://...\\\"}\"，不能直接传对象。";
        }
        return field != null ? " 出错字段：" + field : "";
    }

    private Map<String, Object> body(String code, String message, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", message);
        body.put("error", message);           // 兼容历史字段
        body.put("timestamp", Instant.now().toString());
        if (extra != null) {
            body.putAll(extra);
        }
        return body;
    }
}
