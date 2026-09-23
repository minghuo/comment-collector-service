package com.sysj.collector.domain.document;


import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * 平台功能配置文档。
 *
 * <p>集合：{@code platform_feature_config}
 *
 * <p>一个文档 = 一个平台下的一个功能，内嵌该功能下所有供应商的完整配置。
 * 供应商列表按 priority 升序存储（写入时保证），查询后直接使用，无需再排序。
 *
 * <p>健康状态（{@code isHealthy}）由运维直接修改 MongoDB 文档，
 * 不做程序探测；修改后通过缓存 TTL（60s）或手动调用刷新接口生效。
 *
 * <p><b>字段命名</b>：应用启用了 {@code SnakeCaseFieldNamingStrategy}，
 * 因此下列 Java 字段在 MongoDB 中<b>以下划线式存储</b>；注释中的 JSON 示例即落库形态。
 *
 * <pre>
 * {
 *   "_id": "ObjectId(...)",
 *   "platform_code": "weibo",
 *   "feature_code": "comment",
 *   "feature_name": "微博评论采集",
 *   "status": true,
 *   "providers": [
 *     {
 *       "provider_key": "local_crawler",
 *       "name": "本地爬虫",
 *       "rate_per_second": 0.5,
 *       "max_retry": 1,
 *       "priority": 10,
 *       "is_healthy": true
 *     }
 *   ]
 * }
 * </pre>
 */
@Data
@Document(collection = "platform_feature_config")
@CompoundIndexes({
        // def 中的字段名不会经过命名策略，必须写落库名（下划线式）
        @CompoundIndex(name = "idx_platform_feature", def = "{'platform_code': 1, 'feature_code': 1}", unique = true)
})
public class PlatformFeatureConfig {

    @Id
    private String id;

    /** 平台编码，如 weibo / douyin / wechat / xiaohongshu */
    private String platformCode;

    /** 功能编码，如 comment / like / repost */
    private String featureCode;

    /** 功能名称，展示用 */
    private String featureName;

    /** 是否启用，false 时整个功能不可用 */
    private boolean status = true;

    /**
     * 该功能下的供应商列表，按 priority ASC 存储。
     * 路由器直接遍历，无需重新排序。
     */
    private List<ProviderConfig> providers;

    // ── 嵌套文档：供应商配置 ──────────────────────────────────────────────

    @Data
    public static class ProviderConfig {

        /**
         * 供应商唯一 Key，须与 Spring Bean 名称完全一致，
         * 路由器通过此 key 从 ApplicationContext 查找实现类。
         */
        private String providerKey;

        /** 供应商名称，展示用 */
        private String name;

        /**
         * 接口调用频次（令牌桶速率，permits/秒）。
         * 直接来自供应商提供的频次限制，不做任何换算。
         * 示例：本地爬虫 0.5（1次/2秒），A公司 3.0，微博官方 2.0
         */
        private double ratePerSecond;

        /**
         * 最大重试次数（含首次调用不算重试）。
         * 0 = 不重试，直接失败。
         */
        private int maxRetry;

        /**
         * 路由优先级，越小越优先。
         * 同一功能下，路由器按此字段升序遍历供应商。
         */
        private int priority;

        /**
         * 健康状态。
         * true = 可用，false = 停用。
         * 由运维直接修改 MongoDB 文档控制，程序不做自动探测。
         * 修改后缓存 TTL 内（默认 60s）自动刷新，或调用 /api/admin/cache/evict 立即生效。
         */
        private boolean isHealthy = true;

        // ── 自适应限速参数（§8.2）`[借鉴 B-11]` ─────────────────────────────
        // 全部可选：文档里没有这些字段时用代码默认值，因此老配置无需改动。

        /** 目标并发，用于把响应时间换算成目标延迟；默认 1.0。 */
        private Double targetConcurrency;

        /** 延迟下限（同时也是初始值），毫秒；默认 500。 */
        private Long startDelayMs;

        /** 延迟上限，毫秒；默认 60000。 */
        private Long maxDelayMs;

        /**
         * 流控效果（§8.3）：`REJECT`（默认）/ `WARM_UP` / `THROTTLE_QUEUE`。
         * 留空按 `REJECT` 处理。
         */
        private String flowEffect;

        /**
         * `THROTTLE_QUEUE` 下的最长排队等待，毫秒；留空用全局默认
         * （`collector.ratelimit.max-queue-wait-ms`）。
         */
        private Long maxQueueWaitMs;

        /** 取延迟下限与默认值。 */
        public long startDelayOrDefault() {
            return startDelayMs == null || startDelayMs <= 0 ? 500L : startDelayMs;
        }

        /** 取延迟上限，并保证不小于下限。 */
        public long maxDelayOrDefault() {
            long start = startDelayOrDefault();
            return maxDelayMs == null || maxDelayMs < start ? Math.max(start, 60_000L) : maxDelayMs;
        }

        /** 取目标并发，非法值回退 1.0。 */
        public double targetConcurrencyOrDefault() {
            return targetConcurrency == null || targetConcurrency <= 0 ? 1.0 : targetConcurrency;
        }
    }

    /**
     * 构建供应商状态查询 key：platformCode:featureCode:providerKey。
     */
    public static String buildStateKey(String platformCode, String featureCode, String providerKey) {
        return platformCode + ":" + featureCode + ":" + providerKey;
    }
}
