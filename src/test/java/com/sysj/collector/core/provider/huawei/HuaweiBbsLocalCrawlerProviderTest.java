package com.sysj.collector.core.provider.huawei;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 华为社区本地爬虫的**纯判据**回归用例（不发 HTTP）。
 *
 * <p>覆盖两件容易悄悄改坏的事：
 * <ol>
 *   <li>{@code tid} 解析：链接形态多（dynamic/article/video/news/question + 纯数字 mid），
 *       解析失败会退化成 {@code STATUS_URL_ERROR} 而不是采集；</li>
 *   <li>"帖子级终态"识别：只有 {@code errcode=100120026}（不存在/已删除/审核中）走
 *       **不重试 + 0 条成功**；其余非零错误码必须继续当失败，否则接口变更/风控会被静默吞成"没评论"。
 * </ol>
 */
class HuaweiBbsLocalCrawlerProviderTest {

    /** 线上真实响应（帖子已删除，2026-10-10 实测于 id_1000000000006073969）。 */
    private static final String GONE_BODY = """
            {"errcode":100120026,"errmsg":"抱歉，指定的主题不存在或已被删除或正在被审核","totalNum":0,"data":[]}
            """;

    @Test
    @DisplayName("errcode=100120026（数字型）识别为帖子级终态")
    void detectsTopicGoneAsNumber() {
        assertTrue(HuaweiBbsLocalCrawlerProvider.isTopicGone(GONE_BODY));
    }

    @Test
    @DisplayName("errcode=100120026（字符串型）同样识别")
    void detectsTopicGoneAsString() {
        assertTrue(HuaweiBbsLocalCrawlerProvider.isTopicGone("{\"errcode\":\"100120026\",\"errmsg\":\"已删除\"}"));
    }

    @Test
    @DisplayName("errcode=0（有数据）不是终态")
    void successBodyIsNotGone() {
        assertFalse(HuaweiBbsLocalCrawlerProvider.isTopicGone(
                "{\"errcode\":0,\"errmsg\":\"ok\",\"totalNum\":7,\"data\":[{}]}"));
    }

    @Test
    @DisplayName("errcode=0 但 totalNum=0（帖子在、没评论）不是终态")
    void emptyButAliveIsNotGone() {
        assertFalse(HuaweiBbsLocalCrawlerProvider.isTopicGone(
                "{\"errcode\":0,\"errmsg\":\"ok\",\"totalNum\":0,\"data\":[]}"));
    }

    @Test
    @DisplayName("其余非零 errcode 必须继续按失败处理（不能被静默吞成 0 条）")
    void otherErrorCodesAreNotGone() {
        assertFalse(HuaweiBbsLocalCrawlerProvider.isTopicGone("{\"errcode\":1001,\"errmsg\":\"风控\"}"));
        assertFalse(HuaweiBbsLocalCrawlerProvider.isTopicGone("{\"errcode\":100120027,\"errmsg\":\"别的终态\"}"));
        assertFalse(HuaweiBbsLocalCrawlerProvider.isTopicGone("{\"errcode\":\"\",\"errmsg\":\"空码\"}"));
    }

    @Test
    @DisplayName("空响应 / 无 errcode 字段都不是终态")
    void blankOrMissingIsNotGone() {
        assertFalse(HuaweiBbsLocalCrawlerProvider.isTopicGone(null));
        assertFalse(HuaweiBbsLocalCrawlerProvider.isTopicGone(""));
        assertFalse(HuaweiBbsLocalCrawlerProvider.isTopicGone("   "));
        assertFalse(HuaweiBbsLocalCrawlerProvider.isTopicGone("<html>502 Bad Gateway</html>"));
        assertFalse(HuaweiBbsLocalCrawlerProvider.isTopicGone("{\"errmsg\":\"只有 msg\"}"));
    }

    @Test
    @DisplayName("tid 解析：链接取 id_{tid}，纯数字直接用，取不到返回 null")
    void resolvesTid() {
        assertEquals("1000000000006073969", HuaweiBbsLocalCrawlerProvider.resolveTid(null,
                "https://cn.club.vmall.com/mhw/consumer/cn/community/mhwnews/dynamic/id_1000000000006073969/", null));
        assertEquals("123", HuaweiBbsLocalCrawlerProvider.resolveTid(null,
                "https://cn.club.vmall.com/mhw/consumer/cn/community/mhwnews/article/id_123", null));
        assertEquals("456", HuaweiBbsLocalCrawlerProvider.resolveTid("456", null, null));
        assertNull(HuaweiBbsLocalCrawlerProvider.resolveTid(null,
                "https://cn.club.vmall.com/mhw/consumer/cn/community/index", null));
    }
}
