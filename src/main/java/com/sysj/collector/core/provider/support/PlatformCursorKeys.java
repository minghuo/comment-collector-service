package com.sysj.collector.core.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.Optional;

/**
 * 各平台翻页游标的回传契约（服务端自动翻页用）。
 *
 * <p>供应商返回的 {@code nextCursor} 是"下一页从哪继续"，但各平台读取下一页的
 * {@code extra} 键不同（见接口文档 §5.3 的回传表）。本类是这张表的**唯一书写处**，
 * 供 {@code TaskManagementService} 生成续采子任务时把游标放回正确的键。
 *
 * <p>两类平台：
 * <ul>
 *   <li>{@link Type#CURSOR} —— 平台返回 opaque 游标，原样回填到 {@link #key()}；</li>
 *   <li>{@link Type#PAGE_INCREMENT} —— 平台按页码翻页，游标键固定为 {@code page}，
 *       续采页码 = 上一页页码 + 1（平台返回的 cursor 字段仅作展示，不参与回传）。</li>
 * </ul>
 *
 * <p>未登记的平台不支持服务端自动翻页（如需支持，先确认供应商实现的 extra 读取键，再补表）。
 */
public final class PlatformCursorKeys {

    public enum Type { CURSOR, PAGE_INCREMENT }

    /**
     * @param type 翻页方式
     * @param key  下一页参数在 {@code extra} 中的键（CURSOR 平台为游标键，PAGE_INCREMENT 平台为 {@code page}）
     */
    public record PagingMode(Type type, String key) {
    }

    private static final PagingMode CURSOR_MAX_ID = new PagingMode(Type.CURSOR, "maxId");
    private static final PagingMode CURSOR_NEXT_ID = new PagingMode(Type.CURSOR, "nextId");
    private static final PagingMode CURSOR_CURSOR = new PagingMode(Type.CURSOR, "cursor");
    private static final PagingMode CURSOR_BUFFER = new PagingMode(Type.CURSOR, "buffer");
    private static final PagingMode CURSOR_LAST_ID = new PagingMode(Type.CURSOR, "lastId");
    private static final PagingMode PAGE = new PagingMode(Type.PAGE_INCREMENT, "page");

    /** 键小写化，兼容配置里 weibo/WEibo 等写法。 */
    private static final Map<String, PagingMode> MODES = Map.ofEntries(
            Map.entry("weibo:comment", CURSOR_MAX_ID),
            Map.entry("weibo:repost", CURSOR_NEXT_ID),
            Map.entry("bilibili:comment", CURSOR_CURSOR),
            Map.entry("xhs:comment", CURSOR_CURSOR),
            Map.entry("wechat:comment", CURSOR_BUFFER),
            Map.entry("wechat_video:comment", CURSOR_BUFFER),
            Map.entry("douyin:comment", PAGE),
            Map.entry("toutiao:comment", PAGE),
            // 社区类平台（阶段：多平台补充）
            Map.entry("tieba:comment", PAGE),              // 页码分页（主列表/楼中楼均页码）
            Map.entry("honor_bbs:comment", PAGE),          // 页码分页（Discuz 超页钳制，无子回复接口）
            Map.entry("huawei_bbs:comment", PAGE),         // 页码分页（totalNum）
            Map.entry("oppo_bbs:comment", PAGE),           // 页码分页（data.total）
            Map.entry("vivo_bbs:comment", CURSOR_LAST_ID), // 键集翻页（最后一条顶层/回复评论 id）
            Map.entry("xiaomi_bbs:comment", CURSOR_CURSOR) // after 游标（主列表=偏移量，回复=末条回复 id）
    );

    /**
     * 查询平台功能的翻页模式；未登记（不支持自动翻页）返回 empty。
     */
    public static Optional<PagingMode> of(String platformCode, String featureCode) {
        if (StringUtils.isBlank(platformCode) || StringUtils.isBlank(featureCode)) {
            return Optional.empty();
        }
        return Optional.ofNullable(MODES.get(
                platformCode.trim().toLowerCase() + ":" + featureCode.trim().toLowerCase()));
    }

    private PlatformCursorKeys() {
    }
}
