package com.sysj.collector.core.provider;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在供应商实现类上声明其真实能力 `[借鉴 B-30]`。
 *
 * <p>与 {@code platform_feature_config.providers[].capabilities} 是**同一份事实的两个来源**：
 * 注解代表代码实际能力（改代码才能改），DB 配置代表运维看到的声明。
 * {@link ProviderCapabilityValidator} 在启动时逐条比对，不一致就告警 ——
 * 这样"类名 / Bean 名 / 注释三者矛盾却无人发现"（C-24）会在启动日志里立刻暴露。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProviderCapability {

    /** 该实现真实具备的能力。 */
    Capability[] value();
}
