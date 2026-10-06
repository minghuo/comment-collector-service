package com.sysj.collector.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API-Key 鉴权拦截器（阶段1）。
 *
 * <h3>语义</h3>
 * <ul>
 *   <li>{@code collector.auth.enabled=false}（默认）时**全部放行** —— 保持存量调用方行为不变，
 *       开关属于部署决策；</li>
 *   <li>enabled=true 时校验请求头（默认 {@code X-API-Key}）是否命中
 *       {@code collector.auth.api-keys}（逗号分隔多 key）；不命中返回 401 JSON；</li>
 *   <li>{@code GET /api/health} 豁免（存活探针不能带业务凭据）；OPTIONS 预检放行；</li>
 *   <li>key 是安全敏感信息，**留在 properties/环境变量**，不进 system_config ——
 *       DB 配置面向运维可编辑，凭据的可见面应尽量小。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyInterceptor implements HandlerInterceptor {

    /** 鉴权开关。 */
    @Value("${collector.auth.enabled:false}")
    private boolean enabled;

    /** 合法 API-Key（逗号分隔）；空列表 + enabled=true = 全部拒绝。 */
    @Value("${collector.auth.api-keys:}")
    private String apiKeys;

    /** API-Key 请求头名称。 */
    @Value("${collector.auth.header-name:X-API-Key}")
    private String headerName;

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!enabled) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Set<String> validKeys = parseKeys(apiKeys);
        String presented = request.getHeader(headerName);
        if (presented != null && !presented.isBlank() && validKeys.contains(presented.trim())) {
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

    private static Set<String> parseKeys(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
    }
}
