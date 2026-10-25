package com.sysj.collector.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 API-Key 鉴权拦截器。
 *
 * <p>拦截 {@code /api/**}、{@code /actuator/**}、{@code /swagger-ui/**}、{@code /v3/api-docs/**}；
 * 豁免 {@code /api/health} 与 {@code /actuator/health}（存活探针不带业务凭据）。
 *
 * <p><b>修 R5</b>：此前只拦 {@code /api/**}，而 Swagger 与 actuator 都不在 {@code /api/**} 下，
 * 于是 {@code collector.auth.enabled=true} 时它们成了绕过鉴权的旁路 ——
 * Swagger 还会把全部接口、参数与数据模型直接列出来。
 * 现在三条路径一并纳入拦截；生产环境另有 {@code application-pro.properties} 里关闭 Swagger 的开关，
 * 形成"鉴权 + 关闭"双重收口。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiKeyInterceptor apiKeyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/**", "/actuator/**", "/swagger-ui/**", "/swagger-ui.html",
                        "/v3/api-docs/**")
                .excludePathPatterns("/api/health", "/actuator/health");
    }
}
