package com.sysj.collector.core.router;

import com.sysj.collector.core.provider.Capability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LinkPlatformRouter} 单元测试：各平台链接路由与微博 cookie 分流
 * （local 采集 / Golaxy 渠道），链接样例与 auto-task-web 的 CommentUrlRouterTest 保持一致。
 */
class LinkPlatformRouterTest {

    private static final String WEIBO_STATUS = "https://weibo.com/1719304541/N2yB7AbCd";
    private static final String WEIBO_M_STATUS = "https://m.weibo.cn/status/4990000000000000";
    private static final String WEIBO_VIDEO = "https://weibo.com/tv/show/1034:4920798847107116?mid=4920805483613340";
    private static final String BILI_VIDEO = "https://www.bilibili.com/video/BV1GJ411x7h7";
    private static final String XHS_NOTE = "https://www.xiaohongshu.com/explore/644899200000000007038c22";
    private static final String DOUYIN_VIDEO = "https://www.douyin.com/video/7301234567890123456";
    private static final String TOUTIAO_POST = "https://www.toutiao.com/w/1770000000000001/";

    @Test
    void weiboWithCookieRequiresLoginState() {
        LinkPlatformRouter.Route route = LinkPlatformRouter.route(WEIBO_STATUS, true);
        assertEquals("weibo", route.platformCode());
        assertEquals("comment", route.featureCode());
        assertEquals("微博", route.platformName());
        assertEquals(List.of(Capability.LOGIN_STATE), route.requiredCapabilities());
        assertTrue(route.excludedCapabilities().isEmpty());
    }

    @Test
    void weiboWithoutCookieExcludesLoginState() {
        LinkPlatformRouter.Route route = LinkPlatformRouter.route(WEIBO_M_STATUS, false);
        assertEquals("weibo", route.platformCode());
        assertTrue(route.requiredCapabilities().isEmpty());
        assertEquals(List.of(Capability.LOGIN_STATE), route.excludedCapabilities());
    }

    @Test
    void weiboVideoWithCookieRoutesToLocal() {
        // 视频链接仅 local 采集支持（prepareUrl 调用视频信息接口解析 mid/uid）
        LinkPlatformRouter.Route route = LinkPlatformRouter.route(WEIBO_VIDEO, true);
        assertEquals("weibo", route.platformCode());
        assertEquals(List.of(Capability.LOGIN_STATE), route.requiredCapabilities());
    }

    @Test
    void weiboVideoWithoutCookieIsUnsupported() {
        // golaxy 渠道无法采集视频链接：无 cookie 时视为未识别，提示用户提供 cookie
        assertNull(LinkPlatformRouter.route(WEIBO_VIDEO, false));
    }

    @Test
    void singleVersionPlatformsIgnoreCookie() {
        assertRoute("bilibili", BILI_VIDEO, true);
        assertRoute("bilibili", BILI_VIDEO, false);
        assertRoute("xhs", XHS_NOTE, false);
        assertRoute("douyin", DOUYIN_VIDEO, false);
        assertRoute("toutiao", TOUTIAO_POST, true);
        assertRoute("wechat_video", "https://channels.weixin.qq.com/web/pages/feed?eid=EqVqHk", false);
        assertRoute("wechat", "https://mp.weixin.qq.com/s/AbC-123xyz", true);
    }

    @Test
    void communityPlatformsRoute() {
        // 链接样例与 auto-task-web 的 CommentUrlRouterTest 一致
        assertRoute("honor_bbs", "https://club.honor.com/cn/thread-30407664-1-1.html", false);
        assertRoute("huawei_bbs",
                "https://cn.club.vmall.com/mhw/consumer/cn/community/mhwnews/article/id_1000000000006064503", false);
        assertRoute("oppo_bbs", "https://www.oppo.cn/thread-402994749-1", false);
        assertRoute("vivo_bbs", "https://bbs.vivo.com.cn/newbbs/thread/39814022", false);
        assertRoute("tieba", "https://tieba.baidu.com/p/11052147315", false);
        assertRoute("tieba", "https://tiebac.baidu.com/p/11052147315?see_lz=1", true);
        assertRoute("xiaomi_bbs", "https://www.miui.com/thread-40571217-1-1.html", false);
        assertEquals("百度贴吧", LinkPlatformRouter.route("https://tieba.baidu.com/p/11052147315", true).platformName());
    }

    @Test
    void unsupportedOrBlankReturnsNull() {
        assertNull(LinkPlatformRouter.route("https://www.baidu.com/s?wd=test", true));
        // B站短链：仅接受 bilibili.com 域名
        assertNull(LinkPlatformRouter.route("https://b23.tv/abcXYZ", false));
        assertNull(LinkPlatformRouter.route("", false));
        assertNull(LinkPlatformRouter.route(null, false));
    }

    private void assertRoute(String platformCode, String url, boolean hasCookie) {
        LinkPlatformRouter.Route route = LinkPlatformRouter.route(url, hasCookie);
        assertEquals(platformCode, route.platformCode(), "url=" + url);
        assertEquals("comment", route.featureCode(), "url=" + url);
        assertTrue(route.unconstrained(), "普通平台不应携带能力约束: url=" + url);
    }
}
