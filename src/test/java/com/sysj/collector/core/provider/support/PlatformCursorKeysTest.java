package com.sysj.collector.core.provider.support;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlatformCursorKeys} 单元测试：平台翻页游标回传契约。
 */
class PlatformCursorKeysTest {

    @Test
    void cursorPlatformsMapToCorrectExtraKeys() {
        assertEquals("maxId", PlatformCursorKeys.of("weibo", "comment").orElseThrow().key());
        assertEquals("nextId", PlatformCursorKeys.of("weibo", "repost").orElseThrow().key());
        assertEquals("cursor", PlatformCursorKeys.of("bilibili", "comment").orElseThrow().key());
        assertEquals("cursor", PlatformCursorKeys.of("xhs", "comment").orElseThrow().key());
        assertEquals("buffer", PlatformCursorKeys.of("wechat", "comment").orElseThrow().key());
        assertEquals("buffer", PlatformCursorKeys.of("wechat_video", "comment").orElseThrow().key());
    }

    @Test
    void pagePlatformsUseIncrement() {
        Optional<PlatformCursorKeys.PagingMode> douyin = PlatformCursorKeys.of("douyin", "comment");
        assertEquals(PlatformCursorKeys.Type.PAGE_INCREMENT, douyin.orElseThrow().type());
        assertEquals("page", douyin.get().key());
        assertEquals(PlatformCursorKeys.Type.PAGE_INCREMENT,
                PlatformCursorKeys.of("toutiao", "comment").orElseThrow().type());
    }

    @Test
    void unknownPlatformIsEmptyAndCaseInsensitive() {
        assertTrue(PlatformCursorKeys.of("facebook", "comment").isEmpty(), "未登记平台不支持自动翻页");
        assertTrue(PlatformCursorKeys.of("", "comment").isEmpty());
        assertEquals("maxId", PlatformCursorKeys.of("Weibo", "Comment").orElseThrow().key());
    }

    @Test
    void communityPlatformsRegistered() {
        assertEquals("page", PlatformCursorKeys.of("tieba", "comment").orElseThrow().key(),
                "贴吧/荣耀/华为/OPPO 为页码分页");
        assertEquals(PlatformCursorKeys.Type.PAGE_INCREMENT,
                PlatformCursorKeys.of("honor_bbs", "comment").orElseThrow().type());
        assertEquals("lastId", PlatformCursorKeys.of("vivo_bbs", "comment").orElseThrow().key(),
                "vivo 为 lastId 键集翻页");
        assertEquals("cursor", PlatformCursorKeys.of("xiaomi_bbs", "comment").orElseThrow().key(),
                "小米为 after 游标");
    }
}
