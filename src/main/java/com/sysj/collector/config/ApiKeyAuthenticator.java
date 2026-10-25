package com.sysj.collector.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API-Key 凭据注册表与校验（HTTP 拦截器与业务控制器共用的唯一凭据入口）。
 *
 * <h3>为什么单独抽一个组件</h3>
 * <p>凭据需要被三处使用：① {@link ApiKeyInterceptor} 的 HTTP 层放行判定；
 * ② 任务查询接口的**归属校验**（把密钥映射到调用方身份）；③ 配置写接口的**管理员判定**。
 * 若三处各自 {@code @Value} 注入再各自解析，就会出现"同一份密钥在不同地方解析出不同结果"
 * 的漂移 —— 这正是本项目 {@code C-12}（同一事实两条写入路径）那类缺陷的成因，故收敛到本类。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code collector.auth.enabled}（默认 false）：总开关关闭时 {@link #authenticate} 直接放行；</li>
 *   <li>{@code collector.auth.api-keys}（逗号分隔）：合法业务密钥；</li>
 *   <li>{@code collector.auth.api-key-owners}（{@code key:owner} 逗号分隔）：密钥 → 调用方身份映射，
 *       供任务归属校验使用；未配置则无法识别身份，归属校验会拒绝（fail closed）；</li>
 *   <li>{@code collector.auth.admin-keys}（逗号分隔）：配置写接口的管理员密钥，
 *       **独立于总开关** —— 总开关关闭（内部直连）时写保护仍可单独启用；</li>
 *   <li>{@code collector.auth.header-name}（默认 {@code X-API-Key}）：凭据请求头名。</li>
 * </ul>
 *
 * <h3>安全性质</h3>
 * <ul>
 *   <li>密钥比对使用 {@link MessageDigest#isEqual} 且**遍历全部候选、不做提前退出**
 *       （{@code Set.contains} 会在命中时提前返回，比对耗时随命中位置变化，可被计时侧信道利用）；</li>
 *   <li>日志不打印密钥内容，只打印是否携带；</li>
 *   <li>凭据是安全敏感项，只留在 properties/环境变量，**不进 system_config**。</li>
 * </ul>
 */
@Slf4j
@Component
public class ApiKeyAuthenticator {

    /** 鉴权总开关（默认关闭，保持存量调用方行为）。 */
    @Value("${collector.auth.enabled:false}")
    private boolean enabled;

    /** 合法业务密钥（逗号分隔）；enabled=true 且为空 = 全部拒绝。 */
    @Value("${collector.auth.api-keys:}")
    private String apiKeys;

    /** 业务密钥 → 调用方身份映射，形如 {@code key1:userA,key2:userB}。 */
    @Value("${collector.auth.api-key-owners:}")
    private String apiKeyOwners;

    /** 配置写接口的管理员密钥（逗号分隔）。 */
    @Value("${collector.auth.admin-keys:}")
    private String adminKeys;

    /** 凭据请求头名。 */
    @Value("${collector.auth.header-name:X-API-Key}")
    private String headerName;

    public boolean enabled() {
        return enabled;
    }

    public String headerName() {
        return headerName;
    }

    /** 业务密钥校验（总开关关闭时一律放行）。 */
    public boolean authenticate(String presented) {
        if (!enabled) {
            return true;
        }
        if (StringUtils.isBlank(presented)) {
            return false;
        }
        Set<String> valid = parseKeys(apiKeys);
        if (valid.isEmpty()) {
            log.warn("collector.auth.enabled=true 但 collector.auth.api-keys 为空，所有业务请求都会被拒绝");
            return false;
        }
        return constantTimeContains(valid, presented.trim());
    }

    /**
     * 管理员密钥校验。
     *
     * <p><b>不受 {@code collector.auth.enabled} 影响</b>：配置写接口的保护是独立控制项，
     * 内部直连（总开关关闭）时也应能单独启用。
     * <p>未配置 {@code collector.auth.admin-keys} 时**一律返回 false**（fail closed）——
     * 开启了写保护却没配管理员密钥，应当写不进去，而不是静默放行。
     */
    public boolean isAdmin(String presented) {
        if (StringUtils.isBlank(presented)) {
            return false;
        }
        Set<String> admins = parseKeys(adminKeys);
        if (admins.isEmpty()) {
            log.warn("配置写保护已开启，但 collector.auth.admin-keys 为空 ⇒ 所有写操作都会被拒绝");
            return false;
        }
        return constantTimeContains(admins, presented.trim());
    }

    /**
     * 解析调用方身份标识（来自 {@code collector.auth.api-key-owners} 映射）。
     *
     * @return 身份标识；密钥为空、未配置映射或无匹配时返回 {@code null}
     */
    public String ownerOf(String presented) {
        if (StringUtils.isBlank(presented)) {
            return null;
        }
        return parseOwners(apiKeyOwners).get(presented.trim());
    }

    // ── 内部工具 ───────────────────────────────────────────────────────────

    /**
     * 常量时间成员判定：**遍历全部候选、不做提前退出**。
     *
     * <p>{@code MessageDigest.isEqual} 对等长输入是常量时间比对；长度差异仍会提前返回，
     * 但密钥长度本身不是秘密（配置项长度固定），残留泄露可忽略。
     */
    private static boolean constantTimeContains(Set<String> candidates, String presented) {
        byte[] probe = presented.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String candidate : candidates) {
            // 刻意用 |= 而非 break：让比对次数与输入无关
            matched |= MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), probe);
        }
        return matched;
    }

    private static Set<String> parseKeys(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 解析 {@code key:owner} 映射；格式非法的条目静默跳过（不让一处笔误拖垮整个鉴权）。 */
    private static Map<String, String> parseOwners(String raw) {
        Map<String, String> owners = new LinkedHashMap<>();
        if (StringUtils.isBlank(raw)) {
            return owners;
        }
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx <= 0 || idx == trimmed.length() - 1) {
                continue;
            }
            String key = trimmed.substring(0, idx).trim();
            String owner = trimmed.substring(idx + 1).trim();
            if (!key.isEmpty() && !owner.isEmpty()) {
                owners.put(key, owner);
            }
        }
        return owners;
    }
}
