package com.sysj.collector.domain.service;

import com.sysj.collector.domain.dao.SystemConfigDao;
import com.sysj.collector.domain.document.SystemConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 系统运行参数配置服务：DB（{@code system_config}）覆盖 {@code application.properties} 默认值。
 *
 * <h3>读取模式</h3>
 * 各组件保留原有的 {@code @Value} 注入值作为**默认**，运行时经本服务的
 * {@code getXxx(key, defaultValue)} 读取 —— DB 有该键用 DB 值，没有用 properties 默认。
 * 整表走 Caffeine 缓存（TTL 60s，见 {@code AppConfig}），每次读取是内存 Map 查找，
 * 熔断/限流这类高频路径也可安全调用。
 *
 * <h3>生效方式</h3>
 * <ul>
 *   <li>改 DB 后最迟 60s 生效（缓存 TTL）；</li>
 *   <li>调 {@code PUT /api/config/{key}} 或 {@code DELETE /api/config/{key}} 立即淘汰缓存，秒级生效。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private final SystemConfigDao systemConfigDao;

    /** 整表缓存（单 key "all"，避免逐键缓存条目）；DB 读失败时返回空表，全部退回默认值。 */
    @Cacheable(value = "systemConfig", key = "'all'")
    public Map<String, String> loadAll() {
        try {
            return Map.copyOf(systemConfigDao.loadAll());
        } catch (Exception e) {
            log.warn("system_config 读取失败，本次全部退回 properties 默认值: {}", e.getMessage());
            return Map.of();
        }
    }

    // ── 类型化读取（key 不存在或解析失败时一律退回 defaultValue） ────────────

    public String getString(String key, String defaultValue) {
        String value = loadAll().get(key);
        return StringUtils.isBlank(value) ? defaultValue : value.trim();
    }

    public boolean getBool(String key, boolean defaultValue) {
        return asBool(loadAll().get(key), defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        return asLong(loadAll().get(key), defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return asInt(loadAll().get(key), defaultValue);
    }

    public double getDouble(String key, double defaultValue) {
        return asDouble(loadAll().get(key), defaultValue);
    }

    // ── 静态解析（便于单测） ────────────────────────────────────────────────

    public static boolean asBool(String raw, boolean defaultValue) {
        if (StringUtils.isBlank(raw)) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    public static long asLong(String raw, long defaultValue) {
        if (StringUtils.isBlank(raw)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static int asInt(String raw, int defaultValue) {
        return (int) asLong(raw, defaultValue);
    }

    public static double asDouble(String raw, double defaultValue) {
        if (StringUtils.isBlank(raw)) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ── 管理操作（必须由外部 Bean 调用才会触发 @CacheEvict） ─────────────────

    /** 全部配置文档（管理接口展示）。 */
    public List<SystemConfig> list() {
        return systemConfigDao.findAll();
    }

    /** 写入配置并立即淘汰缓存（秒级生效）。 */
    @CacheEvict(value = "systemConfig", allEntries = true)
    public void save(String key, String value, String description) {
        systemConfigDao.upsert(key, value, description);
        log.info("系统配置已更新: key={} value={}", key, value);
    }

    /** 删除配置（回退 properties 默认）并立即淘汰缓存。 */
    @CacheEvict(value = "systemConfig", allEntries = true)
    public boolean delete(String key) {
        boolean removed = systemConfigDao.delete(key);
        if (removed) {
            log.info("系统配置已删除（回退默认值）: key={}", key);
        }
        return removed;
    }
}
