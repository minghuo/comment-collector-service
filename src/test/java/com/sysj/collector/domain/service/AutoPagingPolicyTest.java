package com.sysj.collector.domain.service;

import com.sysj.collector.core.provider.support.PlatformCursorKeys;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutoPagingPolicy} 单元测试：续采判定（深度上限、hasMore、游标前进防死循环）。
 */
class AutoPagingPolicyTest {

    private static final PlatformCursorKeys.PagingMode CURSOR =
            new PlatformCursorKeys.PagingMode(PlatformCursorKeys.Type.CURSOR, "cursor");
    private static final PlatformCursorKeys.PagingMode PAGE =
            new PlatformCursorKeys.PagingMode(PlatformCursorKeys.Type.PAGE_INCREMENT, "page");

    @Test
    void cursorPlatformContinuesWithUpstreamCursor() {
        assertEquals(Optional.of("next-page-cursor"),
                AutoPagingPolicy.nextCursor(CURSOR, 1, 10, true, "next-page-cursor", null, 1));
        assertEquals(Optional.of("c2"),
                AutoPagingPolicy.nextCursor(CURSOR, 2, 10, true, "c2", "c1", 1));
    }

    @Test
    void stopsWhenNoMoreOrNoCursor() {
        assertTrue(AutoPagingPolicy.nextCursor(CURSOR, 1, 10, false, "c2", null, 1).isEmpty(),
                "hasMore=false 不续采");
        assertTrue(AutoPagingPolicy.nextCursor(CURSOR, 1, 10, true, null, null, 1).isEmpty(),
                "无下一页游标不续采");
        assertTrue(AutoPagingPolicy.nextCursor(CURSOR, 1, 10, true, " ", null, 1).isEmpty());
    }

    @Test
    void stopsWhenCursorDoesNotAdvance() {
        // 上游返回的 cursor 与本次请求所用相同：继续采只会重复同一页
        assertTrue(AutoPagingPolicy.nextCursor(CURSOR, 2, 10, true, "c1", "c1", 1).isEmpty());
    }

    @Test
    void stopsAtMaxPages() {
        assertTrue(AutoPagingPolicy.nextCursor(CURSOR, 10, 10, true, "c11", "c10", 1).isEmpty(),
                "达到深度上限不再续采");
        assertEquals(Optional.of("10"),
                AutoPagingPolicy.nextCursor(PAGE, 9, 10, true, null, "9", 9),
                "深度 9 < 上限 10：允许采第 10 页");
        assertTrue(AutoPagingPolicy.nextCursor(PAGE, 10, 10, true, null, "10", 10).isEmpty(),
                "第 10 页之后（深度已达上限）不再续采");
    }

    @Test
    void pagePlatformIncrements() {
        assertEquals(Optional.of("2"), AutoPagingPolicy.nextCursor(PAGE, 1, 10, true, null, null, 1));
        assertEquals(Optional.of("6"), AutoPagingPolicy.nextCursor(PAGE, 2, 10, true, null, "5", 5),
                "页码 = 上一页页码 + 1");
    }

    @Test
    void nullModeMeansPlatformUnsupported() {
        assertTrue(AutoPagingPolicy.nextCursor(null, 1, 10, true, "c2", null, 1).isEmpty());
    }
}
