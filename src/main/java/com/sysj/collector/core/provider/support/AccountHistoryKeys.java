package com.sysj.collector.core.provider.support;

/**
 * 账号历史链路的共享常量与 extra 约定键。
 *
 * <p>这些字符串同时出现在三处：任务参数（{@code request_params}）、队列消息的
 * {@code extra}、供应商实现读取翻页参数的地方。集中在这里是为了避免三处各写一份字面量后
 * 悄悄漂移（评论链路已有 {@link PlatformCursorKeys} 承担同类职责）。
 */
public final class AccountHistoryKeys {

    /** 账号历史的功能码（{@code platform_feature_config.feature_code}）。 */
    public static final String FEATURE_CODE = "account_history";

    /** extra：账号标识。异步链路用它承载"采哪个账号"，不依赖 targetId 的语义。 */
    public static final String ACCOUNT_ID = "accountId";

    /** extra：本子任务最多取多少条（队列不持久化 maxCount，只能走 extra）。 */
    public static final String MAX_COUNT = "maxCount";

    /** extra：头条翻页游标（上一页 {@code next.max_behot_time}）。 */
    public static final String TOUTIAO_MAX_BEHOT_TIME = "maxBehotTime";

    /** 头条账号主页链接中的 token 段（{@code /c/user/token/xxxx/}）。 */
    public static final String TOUTIAO_USER_TOKEN = "userToken";

    public static boolean isAccountHistory(String featureCode) {
        return FEATURE_CODE.equalsIgnoreCase(featureCode == null ? null : featureCode.trim());
    }

    private AccountHistoryKeys() {
    }
}
