package com.sysj.collector.core.provider.toutiao;

import com.sysj.collector.core.provider.support.AccountHistoryKeys;
import com.sysj.collector.model.AccountHistoryCollectRequest;
import com.sysj.collector.model.AccountHistoryItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 今日头条账号历史供应商的纯解析判据（不发 HTTP）。
 *
 * <p>覆盖三个最容易出错的点：账号标识解析（token/链接混用）、作品解析（字段回退与脏元素丢弃）、
 * 翻页游标（next 优先、末条发布时间兜底）。
 */
class TouTiaoAccountHistoryProviderTest {

    // ── 账号标识解析 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("裸 token 原样返回（调用方已自行解析的情况）")
    void bareTokenReturnedAsIs() {
        assertEquals("MS4wLjABAAAAxyz", TouTiaoAccountHistoryProvider.extractUserToken("MS4wLjABAAAAxyz"));
    }

    @Test
    @DisplayName("主页链接：/c/user/token/{token}/ 形态")
    void tokenFromProfileUrl() {
        assertEquals("MS4wLjABAAAA-_.9",
                TouTiaoAccountHistoryProvider.extractUserToken(
                        "https://www.toutiao.com/c/user/token/MS4wLjABAAAA-_.9/?from=timeline"));
    }

    @Test
    @DisplayName("主页链接：/user/token/{token} 形态")
    void tokenFromShortProfileUrl() {
        assertEquals("ABC123xyz",
                TouTiaoAccountHistoryProvider.extractUserToken(
                        "https://www.toutiao.com/user/token/ABC123xyz"));
    }

    @Test
    @DisplayName("主页链接：?token= 查询参数形态")
    void tokenFromQueryParameter() {
        assertEquals("Tok_42",
                TouTiaoAccountHistoryProvider.extractUserToken(
                        "https://www.toutiao.com/c/user/anything?token=Tok_42&utm=x"));
    }

    @Test
    @DisplayName("主页链接：纯数字用户 id 形态")
    void tokenFromNumericUserId() {
        assertEquals("1234567890",
                TouTiaoAccountHistoryProvider.extractUserToken(
                        "https://www.toutiao.com/user/1234567890"));
    }

    @Test
    @DisplayName("无法识别的链接与空值返回 null")
    void unparseableReturnsNull() {
        assertNull(TouTiaoAccountHistoryProvider.extractUserToken(""));
        assertNull(TouTiaoAccountHistoryProvider.extractUserToken("   "));
        assertNull(TouTiaoAccountHistoryProvider.extractUserToken("https://www.toutiao.com/"));
    }

    @Test
    @DisplayName("resolveToken：accountId 优先 → extra.accountId → fromUrl")
    void resolveTokenPrefersExplicitFields() {
        AccountHistoryCollectRequest fromUrlOnly = AccountHistoryCollectRequest.builder()
                .fromUrl("https://www.toutiao.com/c/user/token/FROM_URL/")
                .build();
        assertEquals("FROM_URL", TouTiaoAccountHistoryProvider.resolveToken(fromUrlOnly));

        AccountHistoryCollectRequest extraWins = AccountHistoryCollectRequest.builder()
                .fromUrl("https://www.toutiao.com/c/user/token/FROM_URL/")
                .extra(Map.of(AccountHistoryKeys.ACCOUNT_ID, "FROM_EXTRA"))
                .build();
        assertEquals("FROM_EXTRA", TouTiaoAccountHistoryProvider.resolveToken(extraWins));

        AccountHistoryCollectRequest explicitWins = AccountHistoryCollectRequest.builder()
                .accountId("EXPLICIT")
                .extra(Map.of(AccountHistoryKeys.ACCOUNT_ID, "FROM_EXTRA"))
                .build();
        assertEquals("EXPLICIT", TouTiaoAccountHistoryProvider.resolveToken(explicitWins));
    }

    // ── 作品解析 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("解析一屏作品：字段归一 + 丢弃无 itemId 的脏元素")
    void parseItemsNormalizesAndDropsJunk() {
        String body = """
                {"data":[
                  {"group_id":"7001","title":"文章A","article_url":"https://www.toutiao.com/article/7001/",
                   "publish_time":1700000000,"read_count":88,"comment_count":9,"digg_count":7,
                   "media_user":{"screen_name":"张三"}},
                  {"title":"没有 id 的元素"}
                ],"has_more":true,"next":{"max_behot_time":"1699999999"}}
                """;

        List<AccountHistoryItem> items = TouTiaoAccountHistoryProvider.parseItems(body);

        assertEquals(1, items.size());
        AccountHistoryItem item = items.get(0);
        assertEquals("7001", item.getItemId());
        assertEquals("文章A", item.getTitle());
        assertEquals("https://www.toutiao.com/article/7001/", item.getUrl());
        assertEquals("article", item.getItemType());
        assertEquals("张三", item.getAccountName());
        // 秒级时间戳统一归一成毫秒
        assertEquals(1700000000000L, item.getPublishTime());
        assertEquals(88L, item.getReadCount());
        assertEquals(9L, item.getCommentCount());
        assertEquals(7L, item.getDiggCount());
    }

    @Test
    @DisplayName("多包一层 data.data 的入口也能解析")
    void parseItemsSupportsWrappedData() {
        String body = """
                {"data":{"data":[{"item_id":"8001","cell_type":"video","video_duration":12,
                  "publish_time":1700000001}]},"has_more":false}
                """;

        List<AccountHistoryItem> items = TouTiaoAccountHistoryProvider.parseItems(body);

        assertEquals(1, items.size());
        assertEquals("8001", items.get(0).getItemId());
        assertEquals("video", items.get(0).getItemType());
    }

    @Test
    @DisplayName("空响应/无 data 数组返回空列表")
    void parseItemsOnEmptyBody() {
        assertTrue(TouTiaoAccountHistoryProvider.parseItems("{}").isEmpty());
        assertTrue(TouTiaoAccountHistoryProvider.parseItems("").isEmpty());
    }

    // ── 分页 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("has_more 为 true / false 的判定")
    void hasMoreFlag() {
        assertTrue(TouTiaoAccountHistoryProvider.hasMore("{\"has_more\":true}"));
        assertTrue(TouTiaoAccountHistoryProvider.hasMore("{\"has_more\":\"true\"}"));
        assertTrue(!TouTiaoAccountHistoryProvider.hasMore("{\"has_more\":false}"));
        assertTrue(!TouTiaoAccountHistoryProvider.hasMore("{}"));
    }

    @Test
    @DisplayName("游标：优先 next.max_behot_time")
    void nextCursorFromNextBlock() {
        String body = "{\"next\":{\"max_behot_time\":\"1699999999\"}}";
        assertEquals("1699999999", TouTiaoAccountHistoryProvider.nextCursor(body, List.of()));
    }

    @Test
    @DisplayName("游标：回退 next.max_behot_time_tmp")
    void nextCursorFromTmpField() {
        String body = "{\"next\":{\"max_behot_time_tmp\":\"1699999998\"}}";
        assertEquals("1699999998", TouTiaoAccountHistoryProvider.nextCursor(body, List.of()));
    }

    @Test
    @DisplayName("游标：next 缺失时用末条作品发布时间（秒）兜底")
    void nextCursorFallsBackToLastItem() {
        List<AccountHistoryItem> items = List.of(
                AccountHistoryItem.builder().itemId("1").publishTime(1700000000000L).build());
        assertEquals("1700000000", TouTiaoAccountHistoryProvider.nextCursor("{}", items));
    }

    @Test
    @DisplayName("游标：无 next 且无发布时间时返回 null（不造一个假游标）")
    void nextCursorAbsent() {
        assertNull(TouTiaoAccountHistoryProvider.nextCursor("{}", List.of()));
    }
}
