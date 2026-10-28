package com.sysj.collector.domain.service;

import com.sysj.collector.domain.dao.PlatformFeatureConfigDao;
import com.sysj.collector.domain.dao.SystemConfigDao;
import com.sysj.collector.domain.dao.UserTierConfigDao;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.UserTierConfig;
import com.sysj.collector.domain.document.UserTierConfig.FeatureProviderConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 配置基线自举：**校验并补齐** {@code system_config} 与 {@code user_tier_config} 两个集合的默认内容。
 *
 * <h3>要解决的问题</h3>
 * 这两个集合的内容此前**只由 {@code db/init-comment-collector.js} 播种**。项目初次发布时，
 * 若运维漏了脚本（或只跑了一半、或换了新库、或从 dump 恢复时少带了这两个集合），运行期表现为：
 * <ul>
 *   <li>{@code system_config} 整表缺失 → {@link SystemConfigService#loadAll()} 返回空表，
 *       全部组件退回 {@code application.properties} 默认值。**功能可用，但运维看不到任何可调项**，
 *       且 GET {@code /api/config} 暴露的是"空配置"，容易被误判为接口故障；</li>
 *   <li>{@code user_tier_config} 整表缺失 → {@code TierFeatureResolver} 解析不到任何等级，
 *       所有任务静默退回全局 {@code priority} 排序：等级优先级（ENTERPRISE/VIP/NORMAL）
 *       与 {@code activation_threshold} 分级放量**全部失效且无任何报错**。</li>
 * </ul>
 * 两者都是"不报错、但语义悄悄丢"的静默降级，最容易被带到生产。
 *
 * <h3>本服务的做法</h3>
 * <ol>
 *   <li><b>校验</b>：启动时检查两个集合是否存在、基线条目是否齐全，结果记日志并经
 *       {@code GET /api/config/bootstrap-status} 暴露给运维；</li>
 *   <li><b>补种</b>：只**补缺**、绝不覆盖——{@code system_config} 用 {@code $setOnInsert}，
 *       {@code user_tier_config} 按 {@code tier_code} 判重后插入，人工调整过的值与等级配置原样保留；</li>
 *   <li><b>不阻断启动</b>：Mongo 抖动/权限不足只记 WARN 并把状态记为异常，采集服务照常起——
 *       沿用本服务"配置层故障不放大为应用故障"的口径。</li>
 * </ol>
 *
 * <h3>执行时机</h3>
 * 以 {@link ApplicationRunner}（最高优先级）在 **{@code ApplicationReadyEvent} 之前**执行，
 * 因此 {@link SupplierRegistrySyncService} 启动同步时已能看到补种出的等级文档并向基础等级
 * 追加功能项；播种时 {@code platform_feature_config} 可能还没被代码注册填充，
 * 此时等级只建骨架（{@code featureConfigs} 为空，靠父链继承/后续同步补齐）。
 *
 * <p>基线与 {@code db/init-comment-collector.js} 同源，改动任一侧需同步另一侧
 * （{@link #SYSTEM_CONFIG_SEEDS} / {@link #TIER_SEEDS}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigBootstrapService implements ApplicationRunner {

    /** 配置键基线：与 init 脚本 {@code SYSTEM_CONFIG_SEEDS} 一一对应。 */
    static final List<ConfigSeed> SYSTEM_CONFIG_SEEDS = List.of(
            // 熔断器
            new ConfigSeed("collector.circuit.enabled", "true", "熔断器总开关"),
            new ConfigSeed("collector.circuit.failure-rate-threshold", "50", "失败率熔断阈值（%）"),
            new ConfigSeed("collector.circuit.slow-call-ms", "10000", "慢调用判定线（毫秒），<=0 关闭"),
            new ConfigSeed("collector.circuit.slow-call-rate-threshold", "80", "慢调用比例熔断阈值（%）"),
            new ConfigSeed("collector.circuit.sliding-window-size", "20", "滑动窗口样本数"),
            new ConfigSeed("collector.circuit.minimum-calls", "5", "触发评估的最小调用数"),
            new ConfigSeed("collector.circuit.open-seconds", "60", "熔断冷却时长（秒），结束后进入半开"),
            new ConfigSeed("collector.circuit.half-open-calls", "3", "半开探测名额（全成功才恢复）"),
            new ConfigSeed("collector.circuit.max-ejection-percent", "100",
                    "驱逐比例上限（%）：再驱逐一个就超限时不驱逐；100=不限制"),
            new ConfigSeed("collector.circuit.panic-threshold-percent", "0",
                    "panic 阈值（%）：可用候选占比低于它则放行一个最不坏候选；0=关闭"),
            // 自适应限速
            new ConfigSeed("collector.ratelimit.adaptive-enabled", "true", "自适应限速总开关"),
            new ConfigSeed("collector.ratelimit.reject-timeout-ms", "500", "REJECT/WARM_UP 取令牌最长等待（毫秒）"),
            new ConfigSeed("collector.ratelimit.max-queue-wait-ms", "2000", "THROTTLE_QUEUE 默认最长排队（毫秒）"),
            new ConfigSeed("collector.ratelimit.warm-up-seconds", "30", "冷启动/熔断恢复后的预热时长（秒）"),
            new ConfigSeed("collector.ratelimit.persist-interval-seconds", "30", "派生指标写库节流（秒）"),
            // 调用管线
            new ConfigSeed("collector.pipeline.enabled", "true", "管线总开关（false 直连供应商，压测隔离用）"),
            new ConfigSeed("collector.pipeline.timeout-ms", "15000", "全局单次调用超时（毫秒），供应商 timeout_ms 优先"),
            new ConfigSeed("collector.pipeline.default-max-concurrency", "4", "全局默认并发上限（Bulkhead），0=不限"),
            new ConfigSeed("collector.pipeline.retry-backoff-base-ms", "500", "供应商内重试退避基数（毫秒）"),
            // 子任务级重投
            new ConfigSeed("collector.task.sub-retry-max", "2", "子任务重投次数上限（跨队列级）"),
            new ConfigSeed("collector.task.sub-retry-base-delay-ms", "2000", "重投退避基数（毫秒）"),
            new ConfigSeed("collector.task.sub-retry-max-delay-ms", "600000", "重投退避封顶（毫秒）"),
            new ConfigSeed("collector.task.sub-retry-queue-reschedule-ms", "30000", "重投被队列拒后的推迟时长（毫秒）"),
            new ConfigSeed("collector.task.sub-retry-scan-limit", "200", "重投扫描每轮最多处理条数"),
            // 队列与调度
            new ConfigSeed("collector.task.queue-capacity", "10000", "异步队列容量（HIGH+LOW 两流未确认消息总数上限）"),
            new ConfigSeed("collector.task.max-wait-minutes", "30", "LOW 流等待超过该时长迁移到 HIGH（分钟）"),
            new ConfigSeed("collector.task.fair-quota-ratio", "0.3", "公平配额：每轮留给低优先级流的比例"),
            new ConfigSeed("collector.task.fair-quota-threshold", "0.7", "公平配额：高优先级占比达到该值才启用"),
            new ConfigSeed("collector.task.fair-quota-high-priority-bound", "500",
                    "有效优先级小于该值进 HIGH 流（同时决定入队层级）"),
            new ConfigSeed("collector.task.fair-quota-batch-size", "20", "公平配额：一个消费轮次的名额数"),
            // 服务端自动翻页
            new ConfigSeed("collector.task.auto-paging.enabled", "false", "自动翻页全局默认（任务级 autoPage 优先）"),
            new ConfigSeed("collector.task.auto-paging.max-pages", "10", "单链接最大翻页深度"),
            // 完成回调
            new ConfigSeed("collector.callback.enabled", "true", "主任务完成回调开关"),
            new ConfigSeed("collector.callback.timeout-ms", "5000", "回调单次超时（毫秒）"),
            new ConfigSeed("collector.callback.max-attempts", "3", "回调重试次数"));

    /** 等级基线：与 init 脚本 {@code TIERS} 一一对应（priority 越小越优先）。 */
    static final List<TierSeed> TIER_SEEDS = List.of(
            new TierSeed("ENTERPRISE", "VIP", 1, 0, "企业用户（最高优先级）"),
            new TierSeed("VIP", "NORMAL", 10, 3, "VIP用户"),
            new TierSeed("NORMAL", null, 100, 10, "普通用户（基础等级，平台全集挂这里）"));

    private static final String SYSTEM_CONFIG = "system_config";
    private static final String USER_TIER_CONFIG = "user_tier_config";

    private final SystemConfigDao systemConfigDao;
    private final UserTierConfigDao userTierConfigDao;
    private final PlatformFeatureConfigDao platformFeatureConfigDao;

    /** 自举开关：默认开启；运维用 init 脚本统一管理配置时可关掉（只校验不写入）。 */
    @Value("${collector.bootstrap.enabled:true}")
    private boolean enabled;

    /** 最近一次自举报告（管理接口读取；启动时写入）。 */
    private volatile BootstrapReport lastReport;

    // ── 启动入口 ───────────────────────────────────────────────────────────

    /**
     * 启动早期执行：先于 {@code ApplicationReadyEvent}（注册表同步）完成补种。
     *
     * <p>任何异常都只记 WARN，绝不把配置层问题放大成启动失败。
     */
    @Override
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("配置基线自举已关闭（collector.bootstrap.enabled=false），仅跳过自动补种");
            lastReport = new BootstrapReport(Instant.now(), true, "collector.bootstrap.enabled=false", List.of());
            return;
        }
        try {
            bootstrap();
        } catch (Exception e) {
            log.warn("配置基线自举失败（不影响启动，请检查 MongoDB 连接/权限）: {}", e.getMessage());
            lastReport = new BootstrapReport(Instant.now(), false, "自举异常: " + e.getMessage(), List.of());
        }
    }

    /**
     * 执行一次校验 + 补种（幂等，可反复调用；管理接口手动触发也走这里）。
     *
     * @return 本次报告（含两个集合的存在性、基线缺口、实际补种条数）
     */
    public BootstrapReport bootstrap() {
        List<BaselineStatus> baselines = new ArrayList<>();
        baselines.add(ensureSystemConfig());
        baselines.add(ensureUserTierConfig());
        BootstrapReport report = new BootstrapReport(Instant.now(), false, null, List.copyOf(baselines));
        lastReport = report;
        logSummary(report);
        return report;
    }

    /** 最近一次报告（未执行过时返回 null）。 */
    public BootstrapReport lastReport() {
        return lastReport;
    }

    // ── system_config ──────────────────────────────────────────────────────

    private BaselineStatus ensureSystemConfig() {
        try {
            boolean exists = systemConfigDao.collectionExists();
            Set<String> present = systemConfigDao.loadAll().keySet();
            List<ConfigSeed> missing = SYSTEM_CONFIG_SEEDS.stream()
                    .filter(seed -> !present.contains(seed.key()))
                    .toList();
            int seeded = 0;
            for (ConfigSeed seed : missing) {
                if (systemConfigDao.insertIfAbsent(seed.key(), seed.value(), seed.description())) {
                    seeded++;
                }
            }
            long documentCount = present.size() + seeded;
            boolean healthy = missing.size() == seeded;
            String message = describeSystemConfig(exists, missing.size(), seeded, healthy);
            return new BaselineStatus(SYSTEM_CONFIG, exists, documentCount,
                    SYSTEM_CONFIG_SEEDS.size(), missing.size(), seeded, healthy, message);
        } catch (Exception e) {
            log.warn("system_config 基线校验失败（运行期回退 properties 默认值）: {}", e.getMessage());
            return new BaselineStatus(SYSTEM_CONFIG, false, -1, SYSTEM_CONFIG_SEEDS.size(),
                    SYSTEM_CONFIG_SEEDS.size(), 0, false, "校验失败: " + e.getMessage());
        }
    }

    private static String describeSystemConfig(boolean exists, int missing, int seeded, boolean healthy) {
        if (missing == 0) {
            return exists ? "键齐全，无需补种" : "集合不存在但键已齐全（异常状态，请检查）";
        }
        if (!healthy) {
            return "缺失 " + missing + " 键，仅补种成功 " + seeded + " 条（请检查写入权限/并发）";
        }
        return (exists ? "集合存在但缺 " : "集合不存在（空库/首次发布），共缺 ")
                + missing + " 键，已按默认值补种";
    }

    // ── user_tier_config ───────────────────────────────────────────────────

    private BaselineStatus ensureUserTierConfig() {
        try {
            boolean exists = userTierConfigDao.collectionExists();
            List<UserTierConfig> current = userTierConfigDao.findAllOrderByPriority();
            Set<String> codes = current.stream()
                    .map(UserTierConfig::getTierCode)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<PlatformFeatureConfig> featureConfigs = platformFeatureConfigDao.findAll();
            List<TierSeed> missing = TIER_SEEDS.stream()
                    .filter(seed -> !codes.contains(seed.tierCode()))
                    .toList();
            int seeded = 0;
            int featureEntries = 0;
            for (TierSeed seed : missing) {
                UserTierConfig doc = buildTier(seed, featureConfigs);
                if (userTierConfigDao.insertIfAbsent(doc)) {
                    seeded++;
                    featureEntries += doc.getFeatureConfigs() == null ? 0 : doc.getFeatureConfigs().size();
                    log.info("user_tier_config 补种等级: tierCode={} priority={} 阈值={} 功能项={}",
                            seed.tierCode(), seed.priority(), seed.activationThreshold(),
                            doc.getFeatureConfigs() == null ? 0 : doc.getFeatureConfigs().size());
                }
            }
            Set<String> codesAfter = new LinkedHashSet<>(codes);
            missing.forEach(seed -> codesAfter.add(seed.tierCode()));
            warnBrokenParentChain(codesAfter);
            long documentCount = current.size() + seeded;
            boolean healthy = missing.size() == seeded;
            String message = describeTierConfig(exists, missing, seeded, featureEntries, healthy);
            return new BaselineStatus(USER_TIER_CONFIG, exists, documentCount,
                    TIER_SEEDS.size(), missing.size(), seeded, healthy, message);
        } catch (Exception e) {
            log.warn("user_tier_config 基线校验失败（等级偏好降级为全局 priority）: {}", e.getMessage());
            return new BaselineStatus(USER_TIER_CONFIG, false, -1, TIER_SEEDS.size(),
                    TIER_SEEDS.size(), 0, false, "校验失败: " + e.getMessage());
        }
    }

    private static String describeTierConfig(boolean exists, List<TierSeed> missing, int seeded,
                                             int featureEntries, boolean healthy) {
        if (missing.isEmpty()) {
            return exists ? "等级齐全，无需补种" : "集合不存在但等级已齐全（异常状态，请检查）";
        }
        String names = missing.stream().map(TierSeed::tierCode).collect(Collectors.joining("/"));
        if (!healthy) {
            return "缺失等级 " + names + "，仅补种成功 " + seeded + " 个（请检查写入权限/并发）";
        }
        return (exists ? "集合存在但缺等级 " : "集合不存在（空库/首次发布），缺少等级 ")
                + names + "，已补种 " + seeded + " 个"
                + (featureEntries > 0 ? "（含 " + featureEntries + " 个功能项快照）" : "（功能项待注册表同步补齐）");
    }

    /** 父链自检：父等级不存在只告警（解析器会安全降级为无偏好，不阻断采集）。 */
    private void warnBrokenParentChain(Set<String> existingCodes) {
        for (TierSeed seed : TIER_SEEDS) {
            String parent = seed.parentTierCode();
            if (parent != null && !parent.isBlank() && !existingCodes.contains(parent)) {
                log.warn("等级父链不完整（该链继承在此截断，降级为全局 priority）: tier={} parentTierCode={}",
                        seed.tierCode(), parent);
            }
        }
    }

    /** 用基线与当前 {@code platform_feature_config} 快照构造等级文档（功能项可为空，后续同步补）。 */
    static UserTierConfig buildTier(TierSeed seed, List<PlatformFeatureConfig> featureConfigs) {
        UserTierConfig tier = new UserTierConfig();
        tier.setTierCode(seed.tierCode());
        tier.setParentTierCode(seed.parentTierCode());
        tier.setPriority(seed.priority());
        tier.setDescription(seed.description());
        tier.setFeatureConfigs(deriveFeatureConfigs(featureConfigs, seed.activationThreshold()));
        return tier;
    }

    /**
     * 由功能配置快照派生等级功能项：{@code providerOrder} = 该功能全部供应商（保持配置顺序），
     * 阈值取等级基线。与 init 脚本 {@code feature_configs} 的构造口径一致。
     */
    static List<FeatureProviderConfig> deriveFeatureConfigs(List<PlatformFeatureConfig> configs, int threshold) {
        List<FeatureProviderConfig> result = new ArrayList<>();
        if (configs == null) {
            return result;
        }
        for (PlatformFeatureConfig config : configs) {
            if (config == null || config.getProviders() == null || config.getProviders().isEmpty()) {
                continue;
            }
            FeatureProviderConfig entry = new FeatureProviderConfig();
            entry.setPlatformCode(config.getPlatformCode());
            entry.setFeatureCode(config.getFeatureCode());
            entry.setProviderOrder(config.getProviders().stream()
                    .map(PlatformFeatureConfig.ProviderConfig::getProviderKey)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));
            entry.setActivationThreshold(threshold);
            result.add(entry);
        }
        return result;
    }

    // ── 汇总日志 ───────────────────────────────────────────────────────────

    private void logSummary(BootstrapReport report) {
        if (report.baselines().isEmpty()) {
            return;
        }
        String detail = report.baselines().stream().map(BaselineStatus::toString)
                .collect(Collectors.joining("; "));
        if (report.healthy()) {
            log.info("配置基线校验通过: {}", detail);
            return;
        }
        log.warn("配置基线不完整（已尽量按默认值补齐，请核对上表）: {}", detail);
    }

    // ── 结构化报告（供管理接口 / 日志） ──────────────────────────────────────

    /** {@code system_config} 的一条基线键。 */
    public record ConfigSeed(String key, String value, String description) {
    }

    /** {@code user_tier_config} 的一个基线等级。 */
    public record TierSeed(String tierCode, String parentTierCode, int priority,
                           int activationThreshold, String description) {
    }

    /**
     * 单个集合的校验结果。
     *
     * @param collection      集合名
     * @param collectionExists 集合是否存在（false = 该库从未建过此集合）
     * @param documentCount   校验后的文档数（-1 = 校验失败未能统计）
     * @param expectedBaseline 基线条数（系统配置键数 / 基线等级数）
     * @param missingBefore   本次校验发现的基线缺口条数
     * @param seeded          本次实际补种成功的条数
     * @param healthy         基线是否完整（缺口是否全部补上）
     * @param message         人类可读说明
     */
    public record BaselineStatus(String collection, boolean collectionExists, long documentCount,
                                 int expectedBaseline, int missingBefore, int seeded,
                                 boolean healthy, String message) {

        @Override
        public String toString() {
            return collection + "=" + (collectionExists ? "存在" : "不存在")
                    + ", 文档数=" + documentCount
                    + ", 基线=" + (expectedBaseline - missingBefore) + "/" + expectedBaseline
                    + ", 补种=" + seeded
                    + (message == null ? "" : ", " + message);
        }
    }

    /** 一次自举的完整报告。 */
    public record BootstrapReport(Instant time, boolean skipped, String reason,
                                  List<BaselineStatus> baselines) {

        /** 两个集合都存在且基线齐全即健康；跳过自举不算健康（需运维自行确认）。 */
        public boolean healthy() {
            return !skipped && !baselines.isEmpty() && baselines.stream().allMatch(BaselineStatus::healthy);
        }

        @Override
        public String toString() {
            return "配置基线自举" + (skipped ? "(已跳过: " + reason + ")" : "")
                    + ": " + baselines.stream().map(BaselineStatus::toString)
                    .collect(Collectors.joining("; "));
        }
    }

    /** 便于单测/接口读取的键清单（不参与运行期逻辑）。 */
    public static Map<String, String> systemConfigDefaults() {
        return SYSTEM_CONFIG_SEEDS.stream()
                .collect(Collectors.toMap(ConfigSeed::key, ConfigSeed::value, (a, b) -> a,
                        java.util.LinkedHashMap::new));
    }
}
