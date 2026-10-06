package com.sysj.collector.domain.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * 系统运行参数配置（集合 {@code system_config}）。
 *
 * <p>{@code _id} 即配置键（如 {@code collector.circuit.open-seconds}），键名与
 * {@code application.properties} 的属性路径完全一致，便于对照。
 *
 * <p><b>覆盖语义</b>：DB 有值 → 覆盖 properties 默认；DB 无该键 → 用 properties 默认。
 * 种子数据由 {@code db/init-comment-collector.js} 用 {@code $setOnInsert} 写入
 * （只在键不存在时插入，人工改过的值不会被脚本重置）。
 *
 * <p><b>生效方式</b>：{@code SystemConfigService} 走 Caffeine 缓存（TTL 60s），
 * 改 DB 后最迟 60s 生效，或调 {@code DELETE /api/config/{key}} 立即刷新。
 *
 * <p><b>不进 DB 的配置</b>：结构性参数（队列容量、消费者线程数、扫描周期等决定线程/内存结构的项，
 * 启动时定死）与安全敏感项（鉴权 API-Key、回调签名 secret）仍留在 properties。
 */
@Data
@Document(collection = "system_config")
public class SystemConfig {

    /** 配置键（即文档 _id），与 application.properties 属性路径同名 */
    @Id
    private String id;

    /** 配置值（字符串，由读取方按类型解析） */
    private String value;

    /** 说明（给运维看） */
    private String description;

    /** 更新时间 */
    private Instant updateTime;
}
