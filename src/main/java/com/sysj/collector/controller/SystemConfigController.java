package com.sysj.collector.controller;

import com.sysj.collector.config.ApiKeyAuthenticator;
import com.sysj.collector.domain.document.SystemConfig;
import com.sysj.collector.domain.service.SupplierRegistrySyncService;
import com.sysj.collector.domain.service.SystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 系统运行参数配置接口。
 *
 * <p>运行参数（熔断阈值、限流参数、管线超时、重试退避、回调开关、自动翻页等）已下沉到
 * MongoDB {@code system_config} 集合，DB 值覆盖 {@code application.properties} 默认，
 * 缓存 TTL 60s。本接口提供**查看 / 修改 / 删除**三个运维入口，改后立即生效（淘汰缓存）；
 * 也可以直接改 DB，60s 内生效。
 *
 * <p>键不存在时所有组件退回 properties 默认值，因此删除 = 恢复默认。
 *
 * <h3>修 R5：本接口此前是完全开放的</h3>
 * <ul>
 *   <li><b>写保护</b>：{@code collector.config.protect-writes=true} 后，写操作
 *       （PUT/DELETE {@code /api/config/{key}}、POST {@code /api/config/sync-registry}）
 *       必须携带 {@code collector.auth.admin-keys} 里的管理员密钥，否则 403。
 *       缺省 false ⇒ 存量运维脚本行为不变。</li>
 *   <li><b>读脱敏</b>：键名疑似承载机密（secret/password/token/api-key/credential）时，
 *       值统一替换为 {@code ******}。原因是 GET 是本接口里最容易被匿名访问到的入口，
 *       而 {@code system_config} 可能存有回调签名密钥等敏感值。
 *       脱敏只影响本接口的展示，不影响组件实际读取的值（组件走 {@code SystemConfigService}）。</li>
 *   <li><b>占位符保护</b>：把 {@code ******} 原样写回会被拒绝，避免"读出来再写回去"
 *       的脚本把真值覆盖成占位符。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@Tag(name = "系统配置接口", description = "运行参数动态配置：查看/修改/删除（DB 覆盖 properties 默认）")
public class SystemConfigController {

    /** 值被脱敏后的占位符；写回时会被拒绝，避免脚本把占位符当真值覆盖进 DB。 */
    private static final String MASK = "******";

    /** 键名疑似承载机密时的匹配规则（回调签名 secret、各类 token 等）。 */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(secret|password|passwd|token|api[-_.]?key|credential).*");

    private final SystemConfigService systemConfigService;
    private final SupplierRegistrySyncService registrySyncService;
    private final ApiKeyAuthenticator apiKeyAuthenticator;

    /** 配置写保护开关（修 R5），缺省关闭。 */
    @Value("${collector.config.protect-writes:false}")
    private boolean protectWrites;

    /** 全部配置（机密键的值已脱敏）。 */
    @GetMapping
    @Operation(summary = "查询全部运行参数配置",
            description = "键名疑似机密的项（secret/token/api-key 等）值统一脱敏为 ******")
    public ResponseEntity<List<SystemConfig>> list() {
        List<SystemConfig> configs = systemConfigService.list();
        configs.forEach(SystemConfigController::maskIfSecretKey);
        return ResponseEntity.ok(configs);
    }

    /** 读取单个配置（机密键的值已脱敏）。 */
    @GetMapping("/{key}")
    @Operation(summary = "查询单个配置", description = "返回 404 表示该键未在 DB 配置，运行时取 properties 默认值")
    public ResponseEntity<SystemConfig> get(@PathVariable String key) {
        return systemConfigService.list().stream()
                .filter(c -> key.equals(c.getId()))
                .findFirst()
                .map(c -> {
                    maskIfSecretKey(c);
                    return ResponseEntity.ok(c);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 写入配置（upsert），立即生效。
     *
     * <p>body：{"value": "60", "description": "熔断时长（秒）"} —— value 必填。
     * <p>{@code collector.config.protect-writes=true} 时需管理员密钥。
     */
    @PutMapping("/{key}")
    @Operation(summary = "写入配置（upsert，立即生效）",
            description = "键名与 application.properties 属性路径同名，如 collector.circuit.open-seconds；"
                    + "protect-writes=true 时需管理员密钥")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String key,
                                                      @RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        if (writeDenied(request)) {
            return forbidden();
        }
        String value = body == null ? null : body.get("value");
        if (StringUtils.isBlank(value)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "value 不能为空"));
        }
        if (MASK.equals(value.trim())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "value 是脱敏占位符 " + MASK + "，不能写回；请传真实值或直接改 DB"));
        }
        systemConfigService.save(key, value.trim(), body.get("description"));
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("key", key);
        resp.put("value", value.trim());
        resp.put("message", "已写入并立即生效");
        return ResponseEntity.ok(resp);
    }

    /**
     * 删除配置（回退 properties 默认），立即生效。
     *
     * <p>{@code collector.config.protect-writes=true} 时需管理员密钥。
     */
    @DeleteMapping("/{key}")
    @Operation(summary = "删除配置（回退 properties 默认值）")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String key, HttpServletRequest request) {
        if (writeDenied(request)) {
            return forbidden();
        }
        boolean removed = systemConfigService.delete(key);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", removed);
        resp.put("key", key);
        resp.put("message", removed ? "已删除，回退默认值" : "该键不存在（本就在用默认值）");
        return ResponseEntity.ok(resp);
    }

    /**
     * 手动触发供应商注册表同步：按 {@code platform_feature_config} 现状补齐
     * {@code supplier_state} 条目并给各等级追加缺失的功能项（幂等，在线状态不受影响）。
     *
     * <p>会改 DB，故与写操作同门槛：{@code collector.config.protect-writes=true} 时需管理员密钥。
     */
    @PostMapping("/sync-registry")
    @Operation(summary = "手动触发供应商注册表同步",
            description = "按 platform_feature_config 补齐 supplier_state 与 user_tier_config 功能项；新增平台/供应商后可立即执行，无需等定时巡检")
    public ResponseEntity<Map<String, Object>> syncRegistry(HttpServletRequest request) {
        if (writeDenied(request)) {
            return forbidden();
        }
        SupplierRegistrySyncService.SyncReport report = registrySyncService.sync();
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("providersRegistered", report.providersRegistered());
        resp.put("providersAligned", report.providersAligned());
        resp.put("configuredSuppliers", report.configuredSuppliers());
        resp.put("supplierStatesAdded", report.supplierStatesAdded());
        resp.put("tierFeatureEntriesAdded", report.tierFeatureEntriesAdded());
        resp.put("staleKeys", report.staleKeys());
        resp.put("message", report.toString());
        return ResponseEntity.ok(resp);
    }

    // ── 写保护与脱敏（修 R5） ──────────────────────────────────────────────

    /**
     * 写操作闸门：{@code protect-writes=true} 时要求 {@code collector.auth.admin-keys} 里的管理员密钥。
     *
     * <p>刻意**不复用** {@code collector.auth.enabled}：内部直连场景（总开关关闭）下
     * 配置写接口仍应能单独收口，否则只能二选一（要么全部接口要鉴权，要么配置裸奔）。
     */
    private boolean writeDenied(HttpServletRequest request) {
        if (!protectWrites) {
            return false;
        }
        if (apiKeyAuthenticator.isAdmin(request.getHeader(apiKeyAuthenticator.headerName()))) {
            return false;
        }
        log.warn("配置写操作被拒绝: uri={} ip={} 原因=未携带有效管理员密钥（collector.auth.admin-keys）",
                request.getRequestURI(), request.getRemoteAddr());
        return true;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", false);
        resp.put("code", "FORBIDDEN");
        resp.put("message", "配置写接口需要管理员密钥（请求头 " + apiKeyAuthenticator.headerName()
                + "，值取自 collector.auth.admin-keys）");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(resp);
    }

    /**
     * 键名疑似机密时把值替换为占位符。
     *
     * <p>可以直接改对象而不必拷贝：{@code SystemConfigService.list()} 每次走
     * {@code SystemConfigDao.findAll()} 从 DB 现查（Caffeine 缓存只缓存
     * {@code loadAll()} 的 Map，不缓存这些文档），不存在"改了缓存实例"的风险。
     */
    private static void maskIfSecretKey(SystemConfig config) {
        if (config == null || config.getId() == null) {
            return;
        }
        if (SECRET_KEY.matcher(config.getId()).find()) {
            config.setValue(MASK);
        }
    }
}
