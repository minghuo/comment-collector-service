package com.sysj.collector.model;

import com.sysj.collector.core.pipeline.PipelineRequest;
import com.sysj.collector.core.provider.support.AccountHistoryKeys;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 账号历史采集请求。
 *
 * <p><b>为什么单独建模而不是复用 {@link CommentCollectRequest}</b>：两者字段大部分重合，
 * 但语义不同 —— 评论请求的 {@code targetId}/{@code fromUrl} 指向**一条作品**，
 * 账号历史指向**一个账号**；且后续各平台会在账号维度带自己的定位参数（头条是 user token，
 * 抖音是 sec_uid，小红书是 user_id，B站是 mid）。独立类型避免把"作品 ID"与"账号 ID"
 * 混成同一个字段，也避免评论链路被迫理解账号历史参数。
 *
 * <p><b>异步链路如何到达这里</b>：任务表、Redis Stream、{@code AsyncTaskConsumer} 全部沿用评论链路
 * 的既有协议（{@code QueuedTask} 是扁平存储契约，不认新类型）。因此账号历史的异步请求以
 * {@code featureCode=account_history} 的 {@link CommentCollectRequest} 入队，
 * 消费侧再用 {@link #from(CommentCollectRequest)} 转成本类型 —— 队列协议零改动。
 *
 * <p><b>extra 中的约定键</b>（见 {@code AccountHistoryKeys}）：
 * <ul>
 *   <li>{@code accountId} —— 账号标识（异步链路的权威来源，避免被 targetId 语义漂移影响）；</li>
 *   <li>{@code maxCount} —— 本子任务最多取多少条（队列不持久化 maxCount，只能放 extra）；</li>
 *   <li>{@code maxBehotTime} —— 头条翻页游标（{@code PlatformCursorKeys} 登记为 CURSOR）。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountHistoryCollectRequest implements PipelineRequest {

    /** 平台码（toutiao / douyin / xhs / weibo / bilibili）。 */
    private String platformCode;

    /** 功能码，账号历史固定为 {@code account_history}。 */
    private String featureCode;

    /** 账号标识（各平台口径不同，由供应商解释）。 */
    private String accountId;

    /** 账号主页链接（可选；供应商可在 accountId 缺失时从中解析）。 */
    private String fromUrl;

    /** 本子任务最多采集多少条；<=0 表示用供应商默认值。 */
    private int maxCount;

    private String userId;
    private String userTierCode;
    private String specifiedProviderKey;
    private String taskId;
    private String subTaskId;
    private List<String> requiredCapabilities;
    private List<String> excludedCapabilities;
    private Map<String, String> extra;

    /** 账号标识解析：显式字段 → extra → 链接 → targetId（异步链路把账号放在 targetId 也可用）。 */
    public String resolveAccountId() {
        if (StringUtils.isNotBlank(accountId)) {
            return accountId.trim();
        }
        String fromExtra = extra == null ? null : extra.get(AccountHistoryKeys.ACCOUNT_ID);
        if (StringUtils.isNotBlank(fromExtra)) {
            return fromExtra.trim();
        }
        return StringUtils.trimToNull(fromUrl);
    }

    /** 读取 extra 中的字符串参数，{@code null} 安全。 */
    public String extraValue(String key) {
        return extra == null ? null : StringUtils.trimToNull(extra.get(key));
    }

    /** 本次请求的最大条数：优先 extra（异步链路），其次字段，最后调用方给的默认值。 */
    public int resolveMaxCount(int defaultValue) {
        String raw = extraValue(AccountHistoryKeys.MAX_COUNT);
        if (raw != null) {
            try {
                int parsed = Integer.parseInt(raw);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // 非法值按缺省处理，不让一个脏参数打断整条任务
            }
        }
        return maxCount > 0 ? maxCount : defaultValue;
    }

    /**
     * 从评论链路的通用请求转换（异步消费侧唯一入口）。
     *
     * <p>字段全部透传，保证"任务表/队列怎么写的，供应商就看到什么"；
     * {@code accountId} 取 extra 中的约定键，缺失时回落 {@code targetId}
     * （{@code TaskManagementService} 用链接回填 targetId）。
     */
    public static AccountHistoryCollectRequest from(CommentCollectRequest request) {
        if (request == null) {
            return null;
        }
        String accountId = request.getExtra() == null ? null
                : StringUtils.trimToNull(request.getExtra().get(AccountHistoryKeys.ACCOUNT_ID));
        if (accountId == null) {
            accountId = StringUtils.trimToNull(request.getTargetId());
        }
        return AccountHistoryCollectRequest.builder()
                .platformCode(request.getPlatformCode())
                .featureCode(request.getFeatureCode())
                .accountId(accountId)
                .fromUrl(request.getFromUrl())
                .maxCount(parseInt(request.getExtra() == null ? null
                        : request.getExtra().get(AccountHistoryKeys.MAX_COUNT)))
                .userId(request.getUserId())
                .userTierCode(request.getUserTierCode())
                .specifiedProviderKey(request.getSpecifiedProviderKey())
                .taskId(request.getTaskId())
                .subTaskId(request.getSubTaskId())
                .requiredCapabilities(request.getRequiredCapabilities())
                .excludedCapabilities(request.getExcludedCapabilities())
                .extra(request.getExtra())
                .build();
    }

    private static int parseInt(String raw) {
        if (StringUtils.isBlank(raw)) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
