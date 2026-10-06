package com.sysj.collector.controller;

import com.sysj.collector.domain.document.SystemConfig;
import com.sysj.collector.domain.service.SystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统运行参数配置接口。
 *
 * <p>运行参数（熔断阈值、限流参数、管线超时、重试退避、回调开关、自动翻页等）已下沉到
 * MongoDB {@code system_config} 集合，DB 值覆盖 {@code application.properties} 默认，
 * 缓存 TTL 60s。本接口提供**查看 / 修改 / 删除**三个运维入口，改后立即生效（淘汰缓存）；
 * 也可以直接改 DB，60s 内生效。
 *
 * <p>键不存在时所有组件退回 properties 默认值，因此删除 = 恢复默认。
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@Tag(name = "系统配置接口", description = "运行参数动态配置：查看/修改/删除（DB 覆盖 properties 默认）")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    /** 全部配置。 */
    @GetMapping
    @Operation(summary = "查询全部运行参数配置")
    public ResponseEntity<List<SystemConfig>> list() {
        return ResponseEntity.ok(systemConfigService.list());
    }

    /** 读取单个配置。 */
    @GetMapping("/{key}")
    @Operation(summary = "查询单个配置", description = "返回 404 表示该键未在 DB 配置，运行时取 properties 默认值")
    public ResponseEntity<SystemConfig> get(@PathVariable String key) {
        return systemConfigService.list().stream()
                .filter(c -> key.equals(c.getId()))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 写入配置（upsert），立即生效。
     *
     * <p>body：{"value": "60", "description": "熔断时长（秒）"} —— value 必填。
     */
    @PutMapping("/{key}")
    @Operation(summary = "写入配置（upsert，立即生效）",
            description = "键名与 application.properties 属性路径同名，如 collector.circuit.open-seconds")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String key,
                                                      @RequestBody Map<String, String> body) {
        String value = body == null ? null : body.get("value");
        if (StringUtils.isBlank(value)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "value 不能为空"));
        }
        systemConfigService.save(key, value.trim(), body.get("description"));
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("key", key);
        resp.put("value", value.trim());
        resp.put("message", "已写入并立即生效");
        return ResponseEntity.ok(resp);
    }

    /** 删除配置（回退 properties 默认），立即生效。 */
    @DeleteMapping("/{key}")
    @Operation(summary = "删除配置（回退 properties 默认值）")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String key) {
        boolean removed = systemConfigService.delete(key);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", removed);
        resp.put("key", key);
        resp.put("message", removed ? "已删除，回退默认值" : "该键不存在（本就在用默认值）");
        return ResponseEntity.ok(resp);
    }
}
