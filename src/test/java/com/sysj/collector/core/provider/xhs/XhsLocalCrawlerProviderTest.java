package com.sysj.collector.core.provider.xhs;

import com.bewilder.parser.TimeParser;
import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小红书本地评论供应商的纯逻辑单测（不发 HTTP）。
 *
 * <p>覆盖：bean key、笔记 id / xsec_token 解析、参数顺序（签名串顺序必须稳定）、
 * 以及 {@link Comment#buildFromXhsLocal} 的字段映射。
 */
@DisplayName("小红书-本地评论供应商")
class XhsLocalCrawlerProviderTest {

    private static final String NOTE_ID = "6aeaf8fc000000000e00e2c4";
    private static final String XSEC_TOKEN = "ABrTwl6trOin-6jripTiNrZ0cBOcSHzU68Au8C77rgx3w%3D";

    private static final String SAMPLE_COMMENT = """
            {
              "id": "66b0c0c0000000001e03d60b",
              "note_id": "6aeaf8fc000000000e00e2c4",
              "content": "这个角度好看",
              "create_time": 1725600000000,
              "like_count": "12",
              "sub_comment_count": "3",
              "ip_location": "上海",
              "target_comment": {"id": "66b0c0c0000000001e03d600"},
              "user_info": {"user_id": "5f992c3d0000000001007b5b", "nickname": "小明"}
            }
            """;

    @Test
    @DisplayName("providerKey 与 Bean 名一致")
    void providerKey() {
        assertEquals("xhs_local", new XhsLocalCrawlerProvider().providerKey());
    }

    @Test
    @DisplayName("笔记 id：extra 优先，其次 targetId，最后从链接提取")
    void resolveNoteId() {
        assertEquals(NOTE_ID, XhsLocalCrawlerProvider.resolveNoteId(
                CommentCollectRequest.builder().build(), Map.of("noteId", NOTE_ID)));

        assertEquals(NOTE_ID, XhsLocalCrawlerProvider.resolveNoteId(
                CommentCollectRequest.builder().targetId(NOTE_ID).build(), Map.of()));

        assertEquals(NOTE_ID, XhsLocalCrawlerProvider.resolveNoteId(
                CommentCollectRequest.builder()
                        .fromUrl("https://www.xiaohongshu.com/explore/" + NOTE_ID + "?xsec_token=" + XSEC_TOKEN)
                        .build(), Map.of()));

        assertEquals(NOTE_ID, XhsLocalCrawlerProvider.resolveNoteId(
                CommentCollectRequest.builder()
                        .fromUrl("https://www.xiaohongshu.com/discovery/item/" + NOTE_ID)
                        .build(), Map.of()));

        assertNull(XhsLocalCrawlerProvider.resolveNoteId(
                CommentCollectRequest.builder().fromUrl("https://www.xiaohongshu.com/explore/too-short").build(),
                Map.of()));
    }

    @Test
    @DisplayName("xsec_token 从链接 query 提取")
    void xsecTokenFromUrl() {
        assertEquals(XSEC_TOKEN, XhsLocalCrawlerProvider.xsecTokenFromUrl(
                "https://www.xiaohongshu.com/explore/" + NOTE_ID + "?xsec_token=" + XSEC_TOKEN + "&xsec_source=pc_feed"));
        assertEquals(XSEC_TOKEN, XhsLocalCrawlerProvider.xsecTokenFromUrl(
                "https://www.xiaohongshu.com/explore/" + NOTE_ID + "?a=1&xsec_token=" + XSEC_TOKEN));
        assertNull(XhsLocalCrawlerProvider.xsecTokenFromUrl("https://www.xiaohongshu.com/explore/" + NOTE_ID));
        assertNull(XhsLocalCrawlerProvider.xsecTokenFromUrl(null));
    }

    @Test
    @DisplayName("参数顺序：note_id/cursor/top_comment_id/image_formats/xsec_token")
    void paramsOrder() {
        Map<String, String> params = XhsLocalCrawlerProvider.buildParams(
                NOTE_ID, "cursor-1", "", "jpg,webp,avif", XSEC_TOKEN);

        assertEquals(List.of("note_id", "cursor", "top_comment_id", "image_formats", "xsec_token"),
                new ArrayList<>(params.keySet()));
        assertEquals(NOTE_ID, params.get("note_id"));
        assertEquals("cursor-1", params.get("cursor"));
        assertEquals(XSEC_TOKEN, params.get("xsec_token"));

        Map<String, String> withoutToken = XhsLocalCrawlerProvider.buildParams(
                NOTE_ID, "", "", "jpg,webp,avif", " ");
        assertEquals(List.of("note_id", "cursor", "top_comment_id", "image_formats"),
                new ArrayList<>(withoutToken.keySet()), "空 token 不参与签名");
    }

    @Test
    @DisplayName("评论字段映射：user_info / 毫秒时间 / 二级评论数")
    void commentMapping() {
        Comment comment = Comment.buildFromXhsLocal(SAMPLE_COMMENT, NOTE_ID, null);

        assertEquals("66b0c0c0000000001e03d60b", comment.getCommentId());
        assertEquals("66b0c0c0000000001e03d600", comment.getParentCommentId(), "target_comment 回退");
        assertEquals(NOTE_ID, comment.getMid());
        assertEquals("5f992c3d0000000001007b5b", comment.getUid());
        assertEquals("小明", comment.getUserName());
        assertEquals("这个角度好看", comment.getText());
        assertEquals(TimeParser.dateFormatString(new Date(1725600000000L)), comment.getTime());
        assertEquals(12, comment.getLikeCount());
        assertEquals(3, comment.getReplyCount());
        assertEquals("上海", comment.getIpLocation());
        assertEquals(NOTE_ID, comment.getExtra().get("note_id"));
        assertEquals(3, comment.getExtra().get("sub_comment_count"));

        Comment withParent = Comment.buildFromXhsLocal(SAMPLE_COMMENT, NOTE_ID, "parent-from-context");
        assertEquals("parent-from-context", withParent.getParentCommentId(), "调用方父 id 优先");
    }

    @Test
    @DisplayName("图片格式默认值参与签名")
    void defaultImageFormats() {
        Map<String, String> params = XhsLocalCrawlerProvider.buildParams(
                NOTE_ID, "", "", XhsLocalCrawlerProvider.DEFAULT_IMAGE_FORMATS, null);
        assertEquals("jpg,webp,avif", params.get("image_formats"));
        assertTrue(new LinkedHashMap<>(params).keySet().contains("image_formats"));
    }
}
