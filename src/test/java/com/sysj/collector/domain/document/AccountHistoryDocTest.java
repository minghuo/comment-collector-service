package com.sysj.collector.domain.document;

import com.sysj.collector.model.AccountHistoryItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号历史落库文档的主键与字段映射判据。
 *
 * <p>主键决定"同一条作品重复采集会不会堆数据"，因此单独断言：有 itemId 时按 itemId 去重，
 * 缺 itemId 时退化为稳定摘要（同账号+标题+时间 → 同主键）。
 */
class AccountHistoryDocTest {

    @Test
    @DisplayName("有 itemId：主键 = taskId-accountId-itemId")
    void buildIdWithItemId() {
        assertEquals("MT1-acc1-7001",
                AccountHistoryDoc.buildId("MT1", "acc1", "7001", "标题", 1700000000000L));
    }

    @Test
    @DisplayName("缺 itemId：同账号+标题+时间得到同一主键（重复采集不堆数据）")
    void buildIdFallbackIsStable() {
        String first = AccountHistoryDoc.buildId("MT1", "acc1", null, "标题", 1700000000000L);
        String second = AccountHistoryDoc.buildId("MT1", "acc1", "  ", "标题", 1700000000000L);
        assertEquals(first, second);
        assertTrue(first.startsWith("MT1-acc1-noid-"));
    }

    @Test
    @DisplayName("缺 itemId：标题不同则是不同作品（不会误合并）")
    void buildIdFallbackDistinguishesDifferentItems() {
        assertNotEquals(
                AccountHistoryDoc.buildId("MT1", "acc1", null, "标题A", 1700000000000L),
                AccountHistoryDoc.buildId("MT1", "acc1", null, "标题B", 1700000000000L));
    }

    @Test
    @DisplayName("缺账号：主键用 unknown 占位而不是 null/空段")
    void buildIdWithMissingAccount() {
        assertEquals("MT1-unknown-7001",
                AccountHistoryDoc.buildId("MT1", null, "7001", "标题", null));
    }

    @Test
    @DisplayName("from()：条目字段逐个映射，insertTime 兜底为当前时间")
    void fromMapsItemFields() {
        AccountHistoryItem item = AccountHistoryItem.builder()
                .accountId("acc1")
                .accountName("张三")
                .itemId("7001")
                .itemType("article")
                .title("标题")
                .summary("摘要")
                .url("https://www.toutiao.com/article/7001/")
                .coverUrl("https://img/cover.jpg")
                .publishTime(1700000000000L)
                .readCount(88L)
                .commentCount(9L)
                .diggCount(7L)
                .repostCount(3L)
                .build();

        AccountHistoryDoc doc = AccountHistoryDoc.from("toutiao", "toutiao_account_history",
                "MT1", "ST1", "https://www.toutiao.com/c/user/token/acc1/", item);

        assertEquals("MT1-acc1-7001", doc.getId());
        assertEquals("MT1", doc.getTaskId());
        assertEquals("ST1", doc.getSubTaskId());
        assertEquals("toutiao", doc.getPlatformCode());
        assertEquals("toutiao_account_history", doc.getProviderKey());
        assertEquals("acc1", doc.getAccountId());
        assertEquals("张三", doc.getAccountName());
        assertEquals("7001", doc.getItemId());
        assertEquals("article", doc.getItemType());
        assertEquals("标题", doc.getTitle());
        assertEquals("摘要", doc.getSummary());
        assertEquals("https://www.toutiao.com/article/7001/", doc.getUrl());
        assertEquals("https://img/cover.jpg", doc.getCoverUrl());
        assertEquals(1700000000000L, doc.getPublishTime());
        assertEquals(88L, doc.getReadCount());
        assertEquals(9L, doc.getCommentCount());
        assertEquals(7L, doc.getDiggCount());
        assertEquals(3L, doc.getRepostCount());
        assertEquals("https://www.toutiao.com/c/user/token/acc1/", doc.getFromUrl());
        assertTrue(doc.getInsertTime() > 0L);
    }
}
