package com.sysj.collector.core.provider.xhs;

import com.sysj.collector.model.AccountHistoryCollectRequest;
import com.sysj.collector.model.AccountHistoryItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 小红书账号历史供应商的纯逻辑单测（不发 HTTP）。
 *
 * <p>覆盖：bean key、用户 id 解析、参数顺序、{@link AccountHistoryItem#buildFromXhs}
 * 的字段映射（note_card 优先、顶层回退）与 xsec_token 透传。
 */
@DisplayName("小红书-账号历史供应商")
class XhsAccountHistoryProviderTest {

    private static final String USER_ID = "5f992c3d0000000001007b5b";
    private static final String NOTE_ID = "6aeaf8fc000000000e00e2c4";
    private static final String XSEC_TOKEN = "ABzviJSfq7-FVu3LZvHJGdeKcC9tdNv4BVV-KmNcyPpsA%3D";

    private static final String SAMPLE_NOTE = """
            {
              "id": "%s",
              "xsec_token": "%s",
              "note_card": {
                "type": "normal",
                "display_title": "周末去哪儿",
                "desc": "一条笔记正文",
                "time": 1725600000,
                "user": {"user_id": "%s", "nickname": "小明"},
                "cover": {"url_default": "https://sns-img.example/cover.jpg"},
                "interact_info": {"comment_count": "5", "liked_count": "10", "share_count": "2"}
              }
            }
            """.formatted(NOTE_ID, XSEC_TOKEN, USER_ID);

    private static final String SAMPLE_NOTE_FLAT = """
            {
              "note_id": "%s",
              "type": "video",
              "title": "平铺标题",
              "desc": "平铺正文",
              "user": {"user_id": "%s", "nickname": "小红"},
              "cover": {"url": "https://sns-img.example/flat.jpg"},
              "time": 1725600000000
            }
            """.formatted(NOTE_ID, USER_ID);

    @Test
    @DisplayName("providerKey 与 Bean 名一致")
    void providerKey() {
        assertEquals("xhs_account_history", new XhsAccountHistoryProvider().providerKey());
    }

    @Test
    @DisplayName("用户 id：accountId 优先，其次 extra，最后从主页链接提取")
    void resolveUserId() {
        assertEquals(USER_ID, XhsAccountHistoryProvider.resolveUserId(
                AccountHistoryCollectRequest.builder().accountId(USER_ID).build(), Map.of()));

        assertEquals(USER_ID, XhsAccountHistoryProvider.resolveUserId(
                AccountHistoryCollectRequest.builder().build(), Map.of("accountId", USER_ID)));

        assertEquals(USER_ID, XhsAccountHistoryProvider.resolveUserId(
                AccountHistoryCollectRequest.builder()
                        .fromUrl("https://www.xiaohongshu.com/user/profile/" + USER_ID
                                + "?xsec_token=" + XSEC_TOKEN + "&xsec_source=pc_search")
                        .build(), Map.of()));

        assertNull(XhsAccountHistoryProvider.resolveUserId(
                AccountHistoryCollectRequest.builder().fromUrl("https://www.xiaohongshu.com/user/profile/tooshort")
                        .build(), Map.of()));
        assertNull(XhsAccountHistoryProvider.resolveUserId(
                AccountHistoryCollectRequest.builder().build(), Map.of()));
    }

    @Test
    @DisplayName("参数顺序：num/cursor/user_id/image_formats/xsec_token/xsec_source")
    void paramsOrder() {
        Map<String, String> params = XhsAccountHistoryProvider.buildParams(
                30, "cursor-1", USER_ID, "jpg,webp,avif", XSEC_TOKEN, "pc_user");

        assertEquals(List.of("num", "cursor", "user_id", "image_formats", "xsec_token", "xsec_source"),
                new ArrayList<>(params.keySet()));
        assertEquals("30", params.get("num"));
        assertEquals(USER_ID, params.get("user_id"));
        assertEquals("pc_user", params.get("xsec_source"));
    }

    @Test
    @DisplayName("作品映射：note_card 优先、封面/互动数/秒级时间/视频类型")
    void noteMapping() {
        AccountHistoryItem item = AccountHistoryItem.buildFromXhs(SAMPLE_NOTE);

        assertEquals(NOTE_ID, item.getItemId());
        assertEquals(USER_ID, item.getAccountId());
        assertEquals("小明", item.getAccountName());
        assertEquals("周末去哪儿", item.getTitle());
        assertEquals("一条笔记正文", item.getSummary());
        assertEquals("https://www.xiaohongshu.com/explore/" + NOTE_ID, item.getUrl());
        assertEquals("https://sns-img.example/cover.jpg", item.getCoverUrl());
        assertEquals("note", item.getItemType());
        assertEquals(1725600000000L, item.getPublishTime(), "秒级时间转毫秒");
        assertEquals(5L, item.getCommentCount());
        assertEquals(10L, item.getDiggCount());
        assertEquals(2L, item.getRepostCount());
        assertEquals(XSEC_TOKEN, item.getExtra().get("xsecToken"));
    }

    @Test
    @DisplayName("平铺字段回退与视频类型")
    void flatNoteMapping() {
        AccountHistoryItem item = AccountHistoryItem.buildFromXhs(SAMPLE_NOTE_FLAT);

        assertEquals(NOTE_ID, item.getItemId());
        assertEquals("小红", item.getAccountName());
        assertEquals("平铺标题", item.getTitle());
        assertEquals("平铺正文", item.getSummary());
        assertEquals("https://sns-img.example/flat.jpg", item.getCoverUrl());
        assertEquals("video", item.getItemType());
        assertEquals(1725600000000L, item.getPublishTime(), "毫秒时间原样");
        assertNull(item.getExtra(), "无 xsec_token 时不造 extra");
    }
}
