package com.sysj.collector.model;

import com.sysj.collector.core.provider.support.AccountHistoryKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号历史请求/结果的转换判据。
 *
 * <p>异步链路把 {@link CommentCollectRequest} 当"信封"入队，消费侧靠 {@code from()} 还原账本维度参数；
 * 这里断言"信封怎么写的，供应商就看到什么"，避免账号标识在转换中被悄悄丢掉。
 */
class AccountHistoryCollectRequestTest {

    @Test
    @DisplayName("from()：账号取 extra.accountId，其余字段全透传")
    void fromCarriesExtraAccountId() {
        CommentCollectRequest envelope = CommentCollectRequest.builder()
                .platformCode("toutiao")
                .featureCode(AccountHistoryKeys.FEATURE_CODE)
                .targetId("https://www.toutiao.com/c/user/token/acc1/")
                .fromUrl("https://www.toutiao.com/c/user/token/acc1/")
                .userId("u1")
                .userTierCode("VIP")
                .specifiedProviderKey("toutiao_account_history")
                .taskId("MT1")
                .subTaskId("ST1")
                .requiredCapabilities(List.of("PAGE_PAGING"))
                .excludedCapabilities(List.of("PROXY"))
                .extra(Map.of(AccountHistoryKeys.ACCOUNT_ID, "acc1",
                        AccountHistoryKeys.MAX_COUNT, "200"))
                .build();

        AccountHistoryCollectRequest converted = AccountHistoryCollectRequest.from(envelope);

        assertEquals("toutiao", converted.getPlatformCode());
        assertEquals(AccountHistoryKeys.FEATURE_CODE, converted.getFeatureCode());
        assertEquals("acc1", converted.getAccountId());
        assertEquals("acc1", converted.resolveAccountId());
        assertEquals("https://www.toutiao.com/c/user/token/acc1/", converted.getFromUrl());
        assertEquals(200, converted.resolveMaxCount(20));
        assertEquals("u1", converted.getUserId());
        assertEquals("VIP", converted.getUserTierCode());
        assertEquals("toutiao_account_history", converted.getSpecifiedProviderKey());
        assertEquals("MT1", converted.getTaskId());
        assertEquals("ST1", converted.getSubTaskId());
        assertEquals(List.of("PAGE_PAGING"), converted.getRequiredCapabilities());
        assertEquals(List.of("PROXY"), converted.getExcludedCapabilities());
    }

    @Test
    @DisplayName("from()：extra 无 accountId 时回落 targetId（任务用链接回填 targetId）")
    void fromFallsBackToTargetId() {
        CommentCollectRequest envelope = CommentCollectRequest.builder()
                .platformCode("toutiao")
                .featureCode(AccountHistoryKeys.FEATURE_CODE)
                .targetId("acc-from-target")
                .build();

        assertEquals("acc-from-target", AccountHistoryCollectRequest.from(envelope).resolveAccountId());
    }

    @Test
    @DisplayName("from(null) 返回 null，不构造半成品请求")
    void fromNullIsNull() {
        assertNull(AccountHistoryCollectRequest.from(null));
    }

    @Test
    @DisplayName("resolveMaxCount：extra 优先于字段，非法值退回默认")
    void resolveMaxCountPrecedence() {
        AccountHistoryCollectRequest withExtra = AccountHistoryCollectRequest.builder()
                .maxCount(50)
                .extra(Map.of(AccountHistoryKeys.MAX_COUNT, "200"))
                .build();
        assertEquals(200, withExtra.resolveMaxCount(20));

        AccountHistoryCollectRequest badExtra = AccountHistoryCollectRequest.builder()
                .maxCount(50)
                .extra(Map.of(AccountHistoryKeys.MAX_COUNT, "abc"))
                .build();
        assertEquals(50, badExtra.resolveMaxCount(20));

        AccountHistoryCollectRequest nothingSet = AccountHistoryCollectRequest.builder().build();
        assertEquals(20, nothingSet.resolveMaxCount(20));
    }

    @Test
    @DisplayName("resolveAccountId：显式字段 → extra → fromUrl 的顺序")
    void resolveAccountIdOrder() {
        AccountHistoryCollectRequest all = AccountHistoryCollectRequest.builder()
                .accountId("explicit")
                .fromUrl("https://www.toutiao.com/c/user/token/from-url/")
                .extra(Map.of(AccountHistoryKeys.ACCOUNT_ID, "from-extra"))
                .build();
        assertEquals("explicit", all.resolveAccountId());

        AccountHistoryCollectRequest noExplicit = AccountHistoryCollectRequest.builder()
                .fromUrl("https://www.toutiao.com/c/user/token/from-url/")
                .extra(Map.of(AccountHistoryKeys.ACCOUNT_ID, "from-extra"))
                .build();
        assertEquals("from-extra", noExplicit.resolveAccountId());

        AccountHistoryCollectRequest urlOnly = AccountHistoryCollectRequest.builder()
                .fromUrl("https://www.toutiao.com/c/user/token/from-url/")
                .build();
        assertEquals("https://www.toutiao.com/c/user/token/from-url/", urlOnly.resolveAccountId());
    }

    @Test
    @DisplayName("toCommentCollectResult()：形状转换保留成功/分页语义，comments 恒为空")
    void toCommentCollectResultKeepsShape() {
        AccountHistoryCollectResult result = AccountHistoryCollectResult.builder()
                .success(true)
                .providerUsed("toutiao_account_history")
                .taskId("MT1")
                .items(List.of(AccountHistoryItem.builder().itemId("7001").build()))
                .hasMore(true)
                .nextCursor("1699999999")
                .build();

        CommentCollectResult converted = result.toCommentCollectResult();

        assertTrue(converted.isSuccess());
        assertEquals("toutiao_account_history", converted.getProviderUsed());
        assertEquals("MT1", converted.getTaskId());
        assertTrue(converted.isHasMore());
        assertEquals("1699999999", converted.getNextCursor());
        assertTrue(converted.getComments().isEmpty());
    }

    @Test
    @DisplayName("toCommentCollectResult()：失败结果带错误信息（子任务据此判定重试）")
    void failedResultCarriesMessage() {
        CommentCollectResult converted = AccountHistoryCollectResult.failed("该功能未配置供应商")
                .toCommentCollectResult();

        assertFalse(converted.isSuccess());
        assertEquals("该功能未配置供应商", converted.getErrorMessage());
        assertTrue(converted.getComments().isEmpty());
    }
}
