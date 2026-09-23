package com.sysj.collector.config;


import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

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

    // 注意：本类原先还有一个内部 @RestControllerAdvice（含 @ExceptionHandler(Exception.class)）。
    // 它与 controller 包的 GlobalExceptionHandler 同时存在，且因 advice 顺序靠前而"先命中即赢"，
    // 把所有更具体的处理器（QueueFullException→503 等）全部盖成 500。
    // 现已合并到 com.sysj.collector.controller.GlobalExceptionHandler，此处删除以免复发。
}
