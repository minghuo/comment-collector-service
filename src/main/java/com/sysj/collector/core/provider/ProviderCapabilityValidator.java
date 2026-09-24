package com.sysj.collector.core.provider;

import com.sysj.collector.domain.dao.PlatformFeatureConfigDao;
import com.sysj.collector.domain.document.PlatformFeatureConfig;
import com.sysj.collector.domain.document.PlatformFeatureConfig.ProviderConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 供应商能力一致性校验 `[借鉴 B-30]`。
 *
 * <p>启动时逐条比对**三个来源**，把不一致打到 WARN/ERROR 日志里：
 * <ol>
 *   <li>DB 配置 {@code providers[].provider_key} → 是否有对应 Bean（不存在则 ERROR）</li>
 *   <li>Bean 类上的 {@link ProviderCapability} 注解 → 代码声明了哪些能力</li>
 *   <li>DB 配置 {@code providers[].capabilities} → 配置声明了哪些能力</li>
 * </ol>
 *
 * <p>这直接解决 C-24：「类名 / Bean 名 / 注释三者矛盾却无人发现」——
 * 以前只有等某次采集失败才暴露，现在是**启动即告警**。
 *
 * <p>只告警、不阻断启动：能力缺一项不影响服务起来（采集时才需要），
 * 若在这里抛异常会让"配置里多个空格"直接导致服务不可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderCapabilityValidator {

    private final PlatformFeatureConfigDao platformFeatureConfigDao;
    private final ApplicationContext applicationContext;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        List<PlatformFeatureConfig> configs;
        try {
            configs = platformFeatureConfigDao.findAll();
        } catch (Exception e) {
            log.warn("能力校验跳过：读取 platform_feature_config 失败: {}", e.getMessage());
            return;
        }

        int checked = 0;
        int problems = 0;
        for (PlatformFeatureConfig config : configs) {
            if (config.getProviders() == null) {
                continue;
            }
            for (ProviderConfig p : config.getProviders()) {
                checked++;
                problems += validateOne(config, p);
            }
        }

        if (problems == 0) {
            log.info("能力校验通过: 共 {} 个供应商配置，注解与 DB 声明一致", checked);
        } else {
            log.warn("能力校验发现 {} 处不一致（不阻断启动，但请尽快修正，见上方明细）", problems);
        }
    }

    /** @return 发现的问题数 */
    private int validateOne(PlatformFeatureConfig config, ProviderConfig p) {
        String key = p.getProviderKey();
        String where = config.getPlatformCode() + ":" + config.getFeatureCode() + ":" + key;
        int problems = 0;

        // ① Bean 是否存在
        Object bean;
        try {
            bean = applicationContext.getBean(key);
        } catch (Exception e) {
            log.error("能力校验：providerKey={} 在容器中找不到对应 Bean —— "
                    + "DB 的 provider_key 必须与 @Component(\"...\") 的名字完全一致（C-24）", where);
            return 1;
        }

        // ② 注解声明的能力
        ProviderCapability annotation = bean.getClass().getAnnotation(ProviderCapability.class);
        if (annotation == null) {
            log.warn("能力校验：providerKey={} 的实现类 {} 未标注 @ProviderCapability，"
                    + "无法校验其能力声明；请在类上加注解", where, bean.getClass().getSimpleName());
            return 1;
        }
        Set<Capability> declared = EnumSet.noneOf(Capability.class);
        for (Capability c : annotation.value()) {
            declared.add(c);
        }

        // ③ DB 声明的能力（同时暴露无法识别的能力名）
        Set<Capability> configured = Capability.parse(p.getCapabilities());
        List<String> unknown = unknownNames(p.getCapabilities());
        if (!unknown.isEmpty()) {
            log.warn("能力校验：providerKey={} 的 capabilities 含无法识别的能力名 {}（已忽略），"
                    + "合法值见 {} ", where, unknown, java.util.Arrays.toString(Capability.values()));
            problems++;
        }

        // ④ 差异比对
        Set<Capability> onlyInDb = new TreeSet<>(configured);
        onlyInDb.removeAll(declared);
        Set<Capability> onlyInCode = new TreeSet<>(declared);
        onlyInCode.removeAll(configured);

        if (!onlyInDb.isEmpty()) {
            log.warn("能力校验不一致：providerKey={} DB 多声明了 {} —— 代码实现并未具备，"
                    + "路由会据此把请求导向该供应商并失败。请改 DB 或改代码。", where, onlyInDb);
            problems++;
        }
        if (!onlyInCode.isEmpty()) {
            log.warn("能力校验不一致：providerKey={} DB 少声明了 {} —— 代码具备但配置未启用，"
                    + "这类请求不会被路由到它。请补 DB 配置。", where, onlyInCode);
            problems++;
        }
        return problems;
    }

    /** 找出配置里无法识别为 {@link Capability} 的名字。 */
    private List<String> unknownNames(List<String> raw) {
        List<String> unknown = new ArrayList<>();
        if (raw == null) {
            return unknown;
        }
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                Capability.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                unknown.add(s);
            }
        }
        return unknown;
    }
}
