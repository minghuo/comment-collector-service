package com.sysj.collector.domain.service;

import com.sysj.collector.core.provider.support.PlatformCursorKeys;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

/**
 * 自动翻页续采判定（纯计算，便于单测）。
 *
 * <p>输入"上一页的产出 + 上限"，输出"下一页游标值"（empty = 不续采）。
 * 由 {@code TaskManagementService} 在子任务成功落库后调用。
 *
 * <h3>防死循环</h3>
 * 游标类平台若上游返回的 cursor 与本次请求所用 cursor 相同（没前进），
 * 继续采只会无限重复同一页，判定为不续采。页码类平台页码恒 +1，天然前进。
 */
public final class AutoPagingPolicy {

    /**
     * 判定下一页。
     *
     * @param mode          平台翻页模式（空 = 平台不支持自动翻页）
     * @param parentDepth   上一页的翻页深度（首次提交为 1）
     * @param maxPages      允许的最大翻页深度
     * @param hasMore       上游是否声明还有下一页
     * @param nextCursor    上游返回的下一页游标（页码类平台可空）
     * @param parentCursor  上一页请求时使用的游标（首次提交为 null）
     * @param parentPage    上一页的页码（游标类平台忽略；首次提交为 1）
     * @return 下一页游标值：CURSOR 平台 = 平台返回的 cursor；PAGE_INCREMENT 平台 = 页码数字串；empty = 不续采
     */
    public static Optional<String> nextCursor(PlatformCursorKeys.PagingMode mode,
                                              int parentDepth, int maxPages,
                                              boolean hasMore, String nextCursor, String parentCursor,
                                              int parentPage) {
        if (mode == null || !hasMore) {
            return Optional.empty();
        }
        if (parentDepth >= maxPages) {
            return Optional.empty();
        }
        if (mode.type() == PlatformCursorKeys.Type.PAGE_INCREMENT) {
            return Optional.of(String.valueOf(Math.max(1, parentPage) + 1));
        }
        // CURSOR：游标必须存在且相对上一页有前进
        if (StringUtils.isBlank(nextCursor)) {
            return Optional.empty();
        }
        if (nextCursor.equals(parentCursor)) {
            return Optional.empty();
        }
        return Optional.of(nextCursor);
    }

    private AutoPagingPolicy() {
    }
}
