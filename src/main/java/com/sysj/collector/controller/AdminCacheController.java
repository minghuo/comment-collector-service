package com.sysj.collector.controller;

import com.sysj.collector.config.ApiKeyAuthenticator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维接口：热配置缓存淘汰（修 G-20）。
 *
 * <h3>为什么需要这个端点</h3>
 * 配置热缓存走 Caffeine 本地缓存（见 {@link com.sysj.collector.config.AppConfig#cacheManager()}），
 * TTL 60 秒 —— 也就是说直接改 Mongo 里的 {@code platform_feature_config} /
 * {@code user_tier_config} / {@code system_config} 之后，最坏要等 60 秒才生效。
 * 文档与 {@code PlatformFeatureConfig} 的 javadoc 一直写着"或调用 /api/admin/cache/evict 立即生效"，
 * 但该端点此前**并不存在**（只有 {@code ProviderConfigService.evictAll()} 这个 Java 方法）。
 * 本类把它补上：改完配置调一次，下一次请求就读到新值。
 *
 * <h3>鉴权</h3>
 * 与配置写接口同门槛：{@code collector.config.protect-writes=true} 时要求
 * {@code collector.auth.admin-keys} 里的管理员密钥。此外若开了全局
 * {@code collector.auth.enabled=true}，{@link com.sysj.collector.config.ApiKeyInterceptor}
 * 会先拦截整条 {@code /api/**}（{@code /api/health} 豁免），无需在本类重复实现。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/cache")
@RequiredArgsConstructor
@Tag(name = "运维管理接口", description = "缓存淘汰等在线运维动作")
public class AdminCacheController {

    /** 热配置相关的缓存名（与 {@code AppConfig#cacheManager()} 注册的一致）。 */
    private static final List<String> HOT_CONFIG_CACHES =
            List.of("featureConfig", "userTierConfig", "systemConfig");

    private final CacheManager cacheManager;
    private final ApiKeyAuthenticator apiKeyAuthenticator;

    /** 与配置写接口同门槛：true 时需管理员密钥。 */
    @Value("${collector.config.protect-writes:false}")
    private boolean protectWrites;

    /**
     * 淘汰热配置缓存（立即生效）。
     *
     * <p>不带参数 = 三个热配置缓存全清；带 {@code ?name=featureConfig} = 只清指定缓存。
     *
     * @param name 可选缓存名：featureConfig / userTierConfig / systemConfig
     */
    @PostMapping("/evict")
    @Operation(summary = "淘汰热配置缓存（立即生效）",
            description = "不带 name 时清空 featureConfig/userTierConfig/systemConfig；"
                    + "protect-writes=true 时需管理员密钥")
    public ResponseEntity<Map<String, Object>> evict(@RequestParam(required = false) String name,
                                                     HttpServletRequest request) {
        if (writeDenied(request)) {
            return forbidden();
        }

        List<String> targets;
        if (StringUtils.isBlank(name)) {
            targets = HOT_CONFIG_CACHES;
        } else {
            String trimmed = name.trim();
            if (!HOT_CONFIG_CACHES.contains(trimmed)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "未知缓存名 " + trimmed + "，可选：" + HOT_CONFIG_CACHES));
            }
            targets = List.of(trimmed);
        }

        List<String> cleared = new ArrayList<>();
        for (String cacheName : targets) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                continue;
            }
            cache.clear();
            cleared.add(cacheName);
        }

        log.info("热配置缓存已淘汰: caches={} operatorIp={}", cleared, request.getRemoteAddr());

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("cleared", cleared);
        resp.put("message", "已淘汰，下一次请求即读到 DB 最新值");
        return ResponseEntity.ok(resp);
    }

    // ── 写保护（与 SystemConfigController 同语义） ─────────────────────────

    /**
     * 写操作闸门：{@code protect-writes=true} 时要求 {@code collector.auth.admin-keys} 里的管理员密钥。
     *
     * <p>刻意**不复用** {@code collector.auth.enabled}：内部直连场景（总开关关闭）下
     * 运维接口仍应能单独收口。与 {@code SystemConfigController} 的判据保持一致
     * （两处各自的实现都很短，且都不希望为了复用而互相耦合）。
     */
    private boolean writeDenied(HttpServletRequest request) {
        if (!protectWrites) {
            return false;
        }
        if (apiKeyAuthenticator.isAdmin(request.getHeader(apiKeyAuthenticator.headerName()))) {
            return false;
        }
        log.warn("缓存淘汰操作被拒绝: uri={} ip={} 原因=未携带有效管理员密钥（collector.auth.admin-keys）",
                request.getRequestURI(), request.getRemoteAddr());
        return true;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", false);
        resp.put("code", "FORBIDDEN");
        resp.put("message", "运维接口需要管理员密钥（请求头 " + apiKeyAuthenticator.headerName()
                + "，值取自 collector.auth.admin-keys）");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(resp);
    }
}
