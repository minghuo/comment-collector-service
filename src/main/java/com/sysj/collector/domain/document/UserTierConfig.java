package com.sysj.collector.domain.document;


import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * 用户等级配置文档。
 *
 * <p>集合：{@code user_tier_config}
 *
 * <p>一个文档 = 一个用户等级，内嵌该等级在各平台/功能下的供应商偏好配置。
 *
 * <h3>等级继承（避免跨等级重复配置）</h3>
 * 通过 {@link #parentTierCode} 形成单父继承链（如 ENTERPRISE → VIP → NORMAL）：
 * <ul>
 *   <li>本等级未声明某功能的偏好时，**沿父链向上查找**，命中即用（整条继承，含阈值）；</li>
 *   <li>本等级声明的功能项**覆盖**父链同名功能项（整条覆盖，不支持字段级合并）；</li>
 *   <li>因此新增平台/功能只需配置到基础等级（无父等级的 tier），所有高等级自动获得；
 *       高等级只在"要与低等级不同"时才写覆盖项。</li>
 * </ul>
 * 解析逻辑见 {@code TierFeatureResolver}；注册表同步只向基础等级补缺失功能项。
 *
 * <pre>
 * {
 *   "_id": ObjectId("..."),
 *   "tierCode": "VIP",
 *   "parentTierCode": "NORMAL",
 *   "priority": 10,
 *   "description": "VIP用户",
 *   "featureConfigs": [   // 只写与 NORMAL 不同的覆盖项；未写的继承 NORMAL
 *     {
 *       "platformCode": "weibo",
 *       "featureCode": "comment",
 *       "providerOrder": ["company_a", "local_crawler", "weibo_official"],
 *       "activationThreshold": 5
 *     }
 *   ]
 * }
 * </pre>
 */
@Data
@Document(collection = "user_tier_config")
public class UserTierConfig {

    @Id
    private String id;

    /**
     * 等级编码，如 ENTERPRISE / VIP / NORMAL。
     * 全局唯一。
     */
    @Indexed(unique = true)
    private String tierCode;

    /**
     * 父等级编码（可空）。
     *
     * <p>非空时本等级**继承**父等级的全部功能偏好：本等级未声明的功能沿父链向上查找，
     * 命中即用；声明了的功能项整条覆盖父链同名项。null/空 = 基础等级（配置全集挂在这里，
     * 注册表同步也只向基础等级补缺失功能项）。必须无环。
     */
    private String parentTierCode;

    /**
     * 调度优先级，越小越优先处理。
     * 用于多用户并发时的任务队列排序。
     */
    private int priority;

    /** 描述 */
    private String description;

    /**
     * 该等级在各功能下的供应商偏好配置列表。
     * <b>只需声明与父等级不同的覆盖项</b>：未配置的功能沿 {@link #parentTierCode} 继承，
     * 整条继承链都未配置时回退到全局 priority 排序。
     */
    private List<FeatureProviderConfig> featureConfigs;

    // ── 嵌套文档：功能级供应商偏好 ────────────────────────────────────────

    @Data
    public static class FeatureProviderConfig {

        /** 平台编码 */
        private String platformCode;

        /** 功能编码 */
        private String featureCode;

        /**
         * 该等级用户在此功能下的供应商起始顺序（provider key 列表）。
         *
         * <p>路由器将按此顺序作为起始偏好，
         * 将列表中的供应商排在全局供应商列表的前面。
         * 列表中未涵盖的供应商按全局 priority 追加在后面兜底。
         *
         * <p>示例：
         * <ul>
         *   <li>ENTERPRISE: ["weibo_official", "company_a", "local_crawler"] —— 官方优先</li>
         *   <li>VIP:        ["company_a", "local_crawler", "weibo_official"] —— A公司优先</li>
         *   <li>NORMAL:     ["local_crawler", "company_a", "weibo_official"] —— 本地爬虫优先</li>
         * </ul>
         */
        private List<String> providerOrder;

        /**
         * 激活阈值：当该功能的待处理任务数 {@code >=} 此值时，
         * 才允许启用偏好列表中第二个及以后的供应商。
         * 0 = 始终全部可用。
         */
        private int activationThreshold;
    }
}
