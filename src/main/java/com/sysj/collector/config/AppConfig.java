package com.sysj.collector.config;


import com.sysj.collector.exception.CollectorException;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
@EnableAsync
public class AppConfig {

    /**
     * Caffeine 缓存配置。
     *
     * <p>TTL = 60s：DB 配置低频变更，60s 内的短暂不一致可接受。
     * 修改 DB 后调用运维接口立即淘汰缓存，可缩短生效延迟至秒级。
     *
     * <p>maximumSize = 1000：平台×功能×等级组合数量有限，1000 条足够。
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                "featureConfig", "userTierConfig");
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(1000)
                .recordStats());
        return manager;
    }

    // ── 全局异常处理 ──────────────────────────────────────────────────────

    @RestControllerAdvice
    static class GlobalExceptionHandler {

        @ExceptionHandler(CollectorException.class)
        public ResponseEntity<Map<String, String>> handleCollector(CollectorException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<Map<String, String>> handleGeneral(Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "内部错误: " + e.getMessage()));
        }
    }
}
