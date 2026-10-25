package com.sysj.collector.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * API-Key 鉴权拦截器（阶段1）。
 *
 * <h3>语义</h3>
 * <ul>
 *   <li>{@code collector.auth.enabled=false}（默认）时**全部放行** —— 保持存量调用方行为不变，
 *       开关属于部署决策；</li>
 *   <li>enabled=true 时校验请求头（默认 {@code X-API-Key}）是否命中
 *       {@code collector.auth.api-keys}（逗号分隔多 key）；不命中返回 401 JSON；</li>
 *   <li>{@code GET /api/health} 与 {@code /actuator/health} 豁免（存活探针不能带业务凭据）；
 *       OPTIONS 预检放行；</li>
 *   <li>key 是安全敏感信息，**留在 properties/环境变量**，不进 system_config ——
 *       DB 配置面向运维可编辑，凭据的可见面应尽量小。</li>
 * </ul>
 *
 * <p>本类只负责"HTTP 层拦不拦"；凭据解析与常量时间比对在 {@link ApiKeyAuthenticator}，
 * 后者同时被需要识别调用方身份的控制器复用（任务归属校验、配置写保护），
 * 避免同一份凭据被多处各自解析而漂移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;
    private final ApiKeyAuthenticator authenticator;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!authenticator.enabled()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String headerName = authenticator.headerName();
        String presented = request.getHeader(headerName);
        if (authenticator.authenticate(presented)) {
            return true;
        }
        log.warn("请求未通过鉴权: uri={} ip={} 原因={}", request.getRequestURI(),
                request.getRemoteAddr(), StringUtils.isBlank(presented) ? "缺少请求头" : "key 不匹配");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "success", false,
                "code", "UNAUTHORIZED",
                "message", "缺少或无效的 " + headerName + " 请求头")));
        return false;
    }
}
