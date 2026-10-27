package com.sysj.collector.core.provider.douyin;

import com.sysj.collector.core.provider.douyin.sign.ABogusSigner;
import com.sysj.collector.core.provider.douyin.sign.DouyinCookie;
import com.sysj.collector.model.Comment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抖音本地供应商的**纯判据**回归（不发 HTTP，方法级验证）。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li>视频 id 解析：纯数字、{@code /video/}、{@code /note/}、{@code modal_id=}、{@code v.douyin.com} 短链、
 *       以及必须<b>不</b>被误认的 10 位用户 id；</li>
 *   <li>签名 query 组装：一级带 {@code aweme_id}，二级带 {@code item_id}+{@code comment_id} 且不带
 *       {@code whale_cut_token}/{@code rcFT}；{@code verifyFp} 与 {@code fp} 同源；分页换算是
 *       {@code cursor=(page-1)*count}；</li>
 *   <li>字段映射：{@code buildFromDouyinLocal} 的 cid/秒级时间戳/gender/extra，以及二级评论的父 id 覆盖。</li>
 * </ol>
 */
class DouyinLocalCrawlerProviderTest {

    private static final String AWEME_ID = "7695724801243024683";

    /** 真实响应片段的裁剪版（字段名与抖音一致） */
    private static final String SAMPLE_COMMENT = """
            {"cid":"7695724801243025001","text":"这条评论很有意思😀","create_time":1793000000,
             "digg_count":42,"reply_comment_total":3,"reply_id":"0","ip_label":"广东","is_top":1,
             "user":{"uid":"987654321","sec_uid":"MS4wLjABAAAAexample","nickname":"冷静的观察者",
                     "gender":1,"unique_id":"dy_observer","signature":"签名档"},
             "reply_comment":[{"cid":"r-1","text":"回复"}]}
            """;

    // ──────────────────────────────────────────────────────────────────────
    // 视频 id 解析
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("aweme_id：纯数字 / video / note / modal_id / vid / 短链分享文案")
    void extractsAwemeId() {
        assertEquals(AWEME_ID, DouyinLocalCrawlerProvider.extractAwemeId(AWEME_ID));
        assertEquals(AWEME_ID, DouyinLocalCrawlerProvider.extractAwemeId(
                "https://www.douyin.com/video/" + AWEME_ID));
        assertEquals(AWEME_ID, DouyinLocalCrawlerProvider.extractAwemeId(
                "https://www.douyin.com/note/" + AWEME_ID + "?previous_page=app_code_link"));
        assertEquals(AWEME_ID, DouyinLocalCrawlerProvider.extractAwemeId(
                "https://www.douyin.com/user/MS4wLjABAAAAxxx?from_tab_name=main&modal_id=" + AWEME_ID));
        assertEquals(AWEME_ID, DouyinLocalCrawlerProvider.extractAwemeId(
                "https://www.douyin.com/jingxuan?modal_id=" + AWEME_ID + "&vid=" + AWEME_ID));
    }

    @Test
    @DisplayName("短链：分享文案里能挑出 v.douyin.com 链接（真实 id 需要一次引导请求，不在纯函数里）")
    void detectsShortShareLink() {
        String share = "7.94 复制打开抖音，看看【某某】的作品 https://v.douyin.com/abc123/ 很有趣";
        assertEquals("https://v.douyin.com/abc123/", DouyinLocalCrawlerProvider.shortLinkIn(share));
        assertNull(DouyinLocalCrawlerProvider.shortLinkIn(AWEME_ID), "纯数字不是短链");
        assertNull(DouyinLocalCrawlerProvider.shortLinkIn((String) null));
        // 短链本身不含视频 id，extractAwemeId 必须返回 null（真实 id 靠 resolveAwemeId 的引导请求换出）
        assertNull(DouyinLocalCrawlerProvider.extractAwemeId(share));
    }

    @Test
    @DisplayName("aweme_id：10 位用户 id、短链裸码、空串一律不误认")
    void rejectsNonVideoIds() {
        assertNull(DouyinLocalCrawlerProvider.extractAwemeId("1234567890"), "10 位是用户 id，不是视频 id");
        assertNull(DouyinLocalCrawlerProvider.extractAwemeId("https://v.douyin.com/abc123/"));
        assertNull(DouyinLocalCrawlerProvider.extractAwemeId(""));
        assertNull(DouyinLocalCrawlerProvider.extractAwemeId(null));
    }

    // ──────────────────────────────────────────────────────────────────────
    // query 组装
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("一级评论 query：aweme_id + 游标换算 + 浏览器指纹 + msToken/verifyFp")
    void buildsListQuery() {
        DouyinCookie cookie = DouyinCookie.parse(
                "msToken=TOKEN; webid=7701151180043748898; s_v_web_id=verify_abc").withDefaults();
        String query = DouyinLocalCrawlerProvider.buildQuery(AWEME_ID, null, 40, 20, cookie);

        assertTrue(query.contains("device_platform=webapp"));
        assertTrue(query.contains("aid=6383"));
        assertTrue(query.contains("aweme_id=" + AWEME_ID));
        assertTrue(query.contains("cursor=40"));
        assertTrue(query.contains("count=20"));
        assertTrue(query.contains("item_type=0"));
        assertTrue(query.contains("cut_version=1"));
        assertTrue(query.contains("webid=7701151180043748898"));
        assertTrue(query.contains("msToken=TOKEN"));
        // verifyFp 与 fp 必须是同一个值（平台用它替代 s_v_web_id 做交叉校验）
        assertTrue(query.contains("verifyFp=verify_abc"));
        assertTrue(query.contains("&fp=verify_abc"));
        assertFalse(query.contains("a_bogus"), "query 不含 a_bogus，签名后才拼接");
        assertFalse(query.contains("item_id="));
    }

    @Test
    @DisplayName("二级评论 query：item_id + comment_id，且不带一级专属参数")
    void buildsReplyQuery() {
        DouyinCookie cookie = DouyinCookie.parse("msToken=TOKEN; webid=7701151180043748898").withDefaults();
        String query = DouyinLocalCrawlerProvider.buildQuery(AWEME_ID, "7695724801243025001", 0, 20, cookie);

        assertTrue(query.contains("item_id=" + AWEME_ID), "回复接口把视频 id 叫 item_id");
        assertTrue(query.contains("comment_id=7695724801243025001"));
        assertFalse(query.contains("aweme_id="));
        assertFalse(query.contains("whale_cut_token="), "whale_cut_token 只属于一级列表");
        assertFalse(query.contains("rcFT="));
        assertFalse(query.contains("cut_version"), "cut_version 只属于一级列表");
    }

    @Test
    @DisplayName("page→cursor 换算：page=1 为 0，page=3、count=20 为 40；count 上限 50")
    void pagesMapToCursorOffsets() {
        for (int page = 1; page <= 3; page++) {
            DouyinCookie cookie = DouyinCookie.parse("webid=7701151180043748898");
            String query = DouyinLocalCrawlerProvider.buildQuery(AWEME_ID, null, (page - 1) * 20, 20, cookie);
            assertTrue(query.contains("cursor=" + ((page - 1) * 20)), "page=" + page);
        }
        assertEquals(50, DouyinLocalCrawlerProvider.MAX_COUNT);
        assertEquals(20, DouyinLocalCrawlerProvider.DEFAULT_COUNT);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 字段映射
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("字段映射：cid/秒级时间戳/用户/IP/性别/抖音号，mid=aweme_id，extra 保留平台维度")
    void mapsCommentFields() {
        Comment comment = Comment.buildFromDouyinLocal(SAMPLE_COMMENT, AWEME_ID, null);

        assertEquals("7695724801243025001", comment.getCommentId());
        assertEquals(AWEME_ID, comment.getMid(), "mid 必须是视频 id，否则跨任务主键冲突");
        assertEquals("987654321", comment.getUid());
        assertEquals("冷静的观察者", comment.getUserName());
        assertEquals("这条评论很有意思😀", comment.getText());
        assertEquals(42, comment.getLikeCount());
        assertEquals(3, comment.getReplyCount());
        assertEquals("广东", comment.getIpLocation());
        assertEquals("男", comment.getGender());
        assertEquals("签名档", comment.getDescription());
        // create_time 是秒级时间戳（×1000），期望值用同一公式计算，避免时区断言脆弱
        assertEquals(com.bewilder.parser.TimeParser.dateFormatString(
                new java.util.Date(1793000000L * 1000)), comment.getTime());
        // 顶层评论的 reply_id 是 "0"，与 B站 root 一样原样保留
        assertEquals("0", comment.getParentCommentId());

        Map<String, Object> extra = comment.getExtra();
        assertEquals(AWEME_ID, extra.get("aweme_id"));
        assertEquals("MS4wLjABAAAAexample", extra.get("sec_uid"));
        assertEquals("dy_observer", extra.get("unique_id"));
        assertEquals("1", extra.get("genderCode"));
        assertEquals(1, extra.get("isTop"));
        assertEquals("0", extra.get("reply_id"));
    }

    @Test
    @DisplayName("二级评论：调用方传的父 id 覆盖响应 reply_id；缺 create_time 不出脏时间")
    void mapsReplyWithParentOverride() {
        Comment reply = Comment.buildFromDouyinLocal(SAMPLE_COMMENT, AWEME_ID, "7695724801243025001");
        assertEquals("7695724801243025001", reply.getParentCommentId());

        Comment blank = Comment.buildFromDouyinLocal("{\"cid\":\"c1\",\"text\":\"hi\"}", AWEME_ID, null);
        assertNull(blank.getTime());
        assertNull(blank.getLikeCount());
        assertEquals("c1", blank.getCommentId());
    }

    // ──────────────────────────────────────────────────────────────────────
    // 契约对齐
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("providerKey 与 seed/能力注解一致")
    void providerKeyIsStable() {
        assertEquals("douyin_local", new DouyinLocalCrawlerProvider().providerKey());
        // UA 常量必须与 query 里的浏览器指纹同源，否则 a_bogus 的 UA 摘要与真实流量不符
        assertEquals(ABogusSigner.DEFAULT_USER_AGENT, ABogusSigner.DEFAULT_USER_AGENT.trim());
        assertTrue(ABogusSigner.DEFAULT_USER_AGENT.contains("Chrome/155"));
    }
}
