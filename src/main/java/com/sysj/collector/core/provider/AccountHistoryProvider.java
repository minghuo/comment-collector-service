package com.sysj.collector.core.provider;

import com.sysj.collector.model.AccountHistoryCollectRequest;
import com.sysj.collector.model.AccountHistoryItem;
import com.sysj.collector.model.CommonEntity;

/**
 * 账号历史供应商契约：给定"某个账号"，返回该账号发布过的作品列表（一页）。
 *
 * <p><b>为什么不复用 {@link CommentProvider}</b>：两者入参/出参语义不同（作品 vs 账号），
 * 若强行共用接口，所有评论实现都要被迫实现一个用不上的方法，且 {@code getBean(key, X.class)}
 * 的类型校验会失去意义。独立接口 + 独立 Bean 名（{@code {platform}_account_history}）后，
 * 平台内路由、限流、熔断、重试仍与评论走**同一条** {@code ProviderInvocationPipeline}，
 * 只是最后一步调用的方法不同。
 *
 * <p><b>新增一个平台的支持只需要</b>：
 * <ol>
 *   <li>实现本接口，标注 {@code @Component("xxx_account_history")}、{@link ProviderMeta}
 *       （{@code feature="account_history"}）与 {@link ProviderCapability}；</li>
 *   <li>无需改任务编排、队列、路由、落库 —— {@code SupplierRegistrySyncService} 会在启动时
 *       把该供应商写进 {@code platform_feature_config}（{@code xxx/account_history} 功能文档自动创建）。</li>
 * </ol>
 *
 * <p><b>翻页契约</b>：本方法只负责取一页。首页请求的游标为空；后续页由调用方把上一页的
 * {@link CommonEntity#getNextUrl()} 作为游标回传（键名见
 * {@link com.sysj.collector.core.provider.support.PlatformCursorKeys}）。
 * 供应商**不要**在内部循环翻完所有页 —— 那会绕过子任务的重试粒度与限速。
 */
public interface AccountHistoryProvider {

    /** 供应商 key，必须与 {@code platform_feature_config.providers[].provider_key} 完全一致。 */
    String providerKey();

    /**
     * 取该账号的一页历史作品。
     *
     * @param request 账号历史请求（账号标识、每页条数、翻页游标）
     * @return 统一结果壳：{@code dataList} 为条目，{@code haseMore}/{@code nextUrl} 表达分页；
     *         失败时返回 {@code status != STATUS_SUCCESS} 的壳，由管线统一判定
     */
    CommonEntity<AccountHistoryItem> fetchHistory(AccountHistoryCollectRequest request);
}
