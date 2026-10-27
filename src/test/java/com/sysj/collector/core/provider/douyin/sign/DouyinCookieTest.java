package com.sysj.collector.core.provider.douyin.sign;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抖音 Cookie 视图的判据回归（纯函数，不发请求）。
 *
 * <p>重点锁三件容易悄悄改坏的事：<b>值里带 {@code =}/{@code %}/{@code /}</b> 时不能截断；
 * 缺 {@code msToken}/{@code webid}/{@code s_v_web_id} 时要能安全补齐；日志摘要绝不能泄漏
 * {@code sessionid}。
 */
class DouyinCookieTest {

    /** 真实抓包片段的子集（含值里带 {@code =} 与 {@code %} 的项，以及抖音特有的非法段 {@code douyin.com}） */
    private static final String RAW = "ttwid=1%7CAib6wCD_yqQW7CNMcfQiTGcN3W7_uTGP4Wb59G5IgAs%7C1793064002%7C2ab04f19; "
            + "msToken=7MD16RLp8J2P6liLbaGCDH_JUvz5oKigd6IQwJbrmtIT34dODwQarjtIh6rNcuZpOF4TBFGm6eJLOwGZTDq0PO=="
            + "; UIFID=ce97285be22ce9307f0362f1de57126fbd12610234088c2dab7be7f4f864c4d32cdcdf6cf2b0094e1a643c36cdde5b2"
            + "; s_v_web_id=verify_mvq00boe_XSwerVWV_LEsK_4omn_B0Xo_tzuhgw3vlRxr"
            + "; sessionid=5bf15eb8db1c061e02ca59fc59c31d88; sid_tt=5bf15eb8db1c061e02ca59fc59c31d88"
            + "; webid=7701151180043748898; douyin.com; empty=; =orphan";

    @Test
    @DisplayName("解析：值里的 % / / / = 原样保留，非法段跳过，名字忽略大小写")
    void parsesRealisticCookie() {
        DouyinCookie cookie = DouyinCookie.parse(RAW);
        assertEquals(8, cookie.size(), "7 个正常项 + 1 个空值项；无 = 的 douyin.com 与 =orphan 应跳过");
        assertTrue(cookie.has("ttwid"));
        assertTrue(cookie.ttwid().startsWith("1%7CAib6wCD_"));
        assertEquals("7701151180043748898", cookie.webid());
        assertTrue(cookie.msToken().endsWith("=="), "msToken 值不能被 = 截断");
        assertEquals(cookie.uifid(), cookie.value("uifid"), "UIFID 应能被任意大小写查到");
        assertEquals("verify_mvq00boe_XSwerVWV_LEsK_4omn_B0Xo_tzuhgw3vlRxr", cookie.verifyFpCookie());
        assertTrue(cookie.hasLoginSession());
        assertFalse(cookie.isBlank());
        // 值原样写回 header，不重新编码
        assertTrue(cookie.header().contains("ttwid=1%7CAib6wCD_yqQW7CNMcfQiTGcN3W7_uTGP4Wb59G5IgAs%7C1793064002%7C2ab04f19"));
    }

    @Test
    @DisplayName("空串 / null 得到空视图而不是 NPE")
    void parsesBlank() {
        assertTrue(DouyinCookie.parse(null).isBlank());
        assertTrue(DouyinCookie.parse("  ").isBlank());
        assertEquals(0, DouyinCookie.parse("").size());
        assertNull(DouyinCookie.parse("").webid());
    }

    @Test
    @DisplayName("withDefaults：补齐 msToken/webid/s_v_web_id，已有的不覆盖")
    void fillsDefaults() {
        DouyinCookie cookie = DouyinCookie.parse("ttwid=abc; webid=7701151180043748898").withDefaults();
        assertEquals("7701151180043748898", cookie.webid(), "已有 webid 不应被替换");
        assertEquals(128, cookie.msToken().length());
        assertTrue(cookie.msToken().endsWith("=="));
        assertEquals(36, cookie.verifyFpCookie().length());
        assertTrue(cookie.header().contains("msToken="));
        // ttwid/sessionid 不伪造
        assertTrue(cookie.has("ttwid"));
        assertFalse(cookie.hasLoginSession());
    }

    @Test
    @DisplayName("伪造值形态：msToken 字母表与长度、webid 19 位、verifyId 的 UUID v4 骨架")
    void fakeValuesLookReal() {
        String token = DouyinCookie.fakeMsToken();
        assertEquals(128, token.length());
        assertTrue(token.endsWith("=="));
        for (int i = 0; i < token.length() - 2; i++) {
            assertTrue(DouyinCookie.MS_TOKEN_ALPHABET.indexOf(token.charAt(i)) >= 0,
                    "非法 msToken 字符: " + token.charAt(i));
        }
        assertNotEquals(token, DouyinCookie.fakeMsToken(), "每次伪造都应不同");

        String webId = DouyinCookie.randomWebId();
        assertEquals(19, webId.length());
        assertTrue(webId.matches("\\d{19}"));
        assertNotEquals('0', webId.charAt(0));

        String verifyId = DouyinCookie.randomVerifyId();
        assertEquals(36, verifyId.length());
        assertEquals('-', verifyId.charAt(8));
        assertEquals('-', verifyId.charAt(13));
        assertEquals('-', verifyId.charAt(18));
        assertEquals('-', verifyId.charAt(23));
        assertEquals('4', verifyId.charAt(14), "UUID v4 的版本位");
        assertTrue("89ab".indexOf(verifyId.charAt(19)) >= 0, "UUID v4 的 variant 位");
    }

    @Test
    @DisplayName("日志摘要只给存在性，绝不泄漏 sessionid")
    void maskedSummaryHidesSecrets() {
        String summary = DouyinCookie.parse(RAW).maskedSummary();
        assertFalse(summary.contains("5bf15eb8db1c061e02ca59fc59c31d88"));
        assertTrue(summary.contains("login=yes"));
        assertTrue(summary.contains("msToken=present"));
        assertTrue(DouyinCookie.parse(RAW).toString().startsWith("DouyinCookie("));
    }
}
