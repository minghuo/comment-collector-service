package com.sysj.collector.core.provider.weibo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微博链接类型分派的纯判据回归（不发 HTTP）。
 *
 * <p>覆盖三类链接形态（2026-10-26 增补）：普通状态（含手机端）、视频（tv/show、fid、
 * m.weibo.cn/video）、头条文章（ttarticle、card 页）。核心风险：文章 id 会被
 * {@code WeiboTool.urlToMid} 误当 mid（22 位长 id 直接进 buildComments 会 400），
 * 文章判定必须优先于 mid 解析。
 */
class WeiboUrlParserTest {

    // ── 文章链接 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("文章链接识别与 id 提取（PC/手机端/card 三形态）")
    void articleUrls() {
        assertTrue(WeiboUrlParser.isArticleUrl("https://weibo.com/ttarticle/p/show?id=2309405357502438441012"));
        assertTrue(WeiboUrlParser.isArticleUrl("https://m.weibo.cn/ttarticle?p=2309405357502438441012"));
        assertTrue(WeiboUrlParser.isArticleUrl("https://card.weibo.com/article/m/show/id/2309405357502438441012"));
        assertEquals("2309405357502438441012",
                WeiboUrlParser.extractArticleId("https://weibo.com/ttarticle/p/show?id=2309405357502438441012&from=pc"));
        assertEquals("2309405357502438441012",
                WeiboUrlParser.extractArticleId("https://m.weibo.cn/ttarticle?p=2309405357502438441012"));
        assertEquals("2309405357502438441012",
                WeiboUrlParser.extractArticleId("https://card.weibo.com/article/m/show/id/2309405357502438441012"));
        // 非文章链接不误判
        assertFalse(WeiboUrlParser.isArticleUrl("https://weibo.com/2803301701/RolEb8T2m"));
        assertNull(WeiboUrlParser.extractArticleId("https://weibo.com/2803301701/RolEb8T2m"));
    }

    @Test
    @DisplayName("文章 id 不会被误当 mid：resolveMid 对文章链接不做 urlToMid 解析")
    void articleIdNotTreatedAsMid() {
        // card 页形态下 WeiboTool.urlToMid 会把 22 位文章 id 当 mid 返回（探针实测），
        // resolveMid 必须先排除文章链接，避免错误 mid 进入 buildComments
        String url = "https://card.weibo.com/article/m/show/id/2309405357502438441012";
        assertNull(WeiboUrlParser.resolveMid(null, url, null));
    }

    // ── 视频链接 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("视频链接识别与 oid 提取（tv/show、video.weibo.com fid、m.weibo.cn/video）")
    void videoUrls() {
        assertTrue(WeiboUrlParser.isVideoUrl("https://weibo.com/tv/show/1034:5358078141595704?from=old_pc_videoshow"));
        assertTrue(WeiboUrlParser.isVideoUrl("https://video.weibo.com/show?fid=1034:5358078141595704"));
        assertTrue(WeiboUrlParser.isVideoUrl("https://m.weibo.cn/video/1034:5358078141595704"));
        assertEquals("1034:5358078141595704",
                WeiboUrlParser.extractVideoOid("https://weibo.com/tv/show/1034:5358078141595704?from=old_pc_videoshow"));
        assertEquals("1034:5358078141595704",
                WeiboUrlParser.extractVideoOid("https://video.weibo.com/show?fid=1034:5358078141595704"));
        assertEquals("1034:5358078141595704",
                WeiboUrlParser.extractVideoOid("https://m.weibo.cn/video/1034:5358078141595704"));
        // 状态页挂视频 fid 的手机端链接本质是普通状态微博（urlToMid 可解析），不按视频处理
        assertFalse(WeiboUrlParser.isVideoUrl("https://m.weibo.cn/status/5358030872118190?fid=1034:5358078141595704"));
        // 视频链接不走 urlToMid（oid 不是 mid）
        assertNull(WeiboUrlParser.resolveMid(null, "https://weibo.com/tv/show/1034:5358078141595704", null));
    }

    // ── 普通状态链接（含手机端） ──────────────────────────────────────────

    @Test
    @DisplayName("普通状态链接 mid 解析（PC 与手机端 status/detail 形态）")
    void statusUrls() {
        assertEquals("5358030872118190",
                WeiboUrlParser.resolveMid(null, "https://weibo.com/2803301701/RolEb8T2m", null));
        assertEquals("5358030872118190",
                WeiboUrlParser.resolveMid(null, "https://m.weibo.cn/status/5358030872118190", null));
        assertEquals("5358030872118190",
                WeiboUrlParser.resolveMid(null, "https://m.weibo.cn/detail/5358030872118190", null));
        // m.weibo.cn/status/{b62} 解析出 b62 短 id：buildComments 的 id 参数同样接受 b62（实测），直接使用
        assertEquals("RolEb8T2m",
                WeiboUrlParser.resolveMid(null, "https://m.weibo.cn/status/RolEb8T2m", null));
        // uid 提取
        assertEquals("2803301701", WeiboUrlParser.resolveUid(null, "https://weibo.com/u/2803301701/RolEb8T2m"));
        assertEquals("2803301701", WeiboUrlParser.resolveUid(null, "https://weibo.com/2803301701/RolEb8T2m"));
    }
}
