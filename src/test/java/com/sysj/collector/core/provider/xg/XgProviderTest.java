package com.sysj.collector.core.provider.xg;

import com.bewilder.parser.TimeParser;
import com.sysj.collector.constants.XgConstants;
import com.sysj.collector.model.Comment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 星光渠道供应商的**纯判据**回归用例（不发 HTTP）。
 *
 * <p>覆盖容易悄悄改坏的三件事：
 * <ol>
 *   <li>{@code buildFromXg} 字段映射：{@code ctime} 是秒级时间戳（×1000）、
 *       {@code pid} 不能映射到 parentCommentId（顶层评论也带原帖 ID，映射错会把全部评论判成回复）、
 *       转发四件套与用户扩展字段进 extra；</li>
 *   <li>三个平台的 endpoint / providerKey 对应关系；</li>
 *   <li>Bean 名 = platform_feature_config.providers[].provider_key 的对齐（providerKey() 即 Bean 名）。</li>
 * </ol>
 */
class XgProviderTest {

    /** 响应结构与 auto-task-web CommentsFactory.xg 的输入一致（字段名保持星光原样）。 */
    private static final String SAMPLE = """
            {"mid":"1886043381941328123","pid":"1886043381941000000","ctime":1748371200,
             "content":"这条评论带转发","like_count":"12","reply_count":"3","repost_count":"1",
             "visit_count":"456","collection_count":"7","lang_code":"zh","wtype":"2",
             "user":{"uid":"u10001","name":"elonm","nickname":"马斯克观察","profile_img_url":"https://pbs.example/a.jpg",
                     "friends_count":"300","followers_count":"12000000","statuses_count":"58000",
                     "description":" SpaceX","location":[" California"," USA"],"verified":"1"},
             "retweeted":{"url":"https://x.com/a/status/1","mid":"1","user":{"uid":"u2","name":"jack"}}}
            """;

    @Test
    @DisplayName("字段映射：标准字段 + extra 扩展字段齐全")
    void mapsStandardAndExtraFields() {
        Comment c = Comment.buildFromXg(SAMPLE);
        assertEquals("1886043381941328123", c.getCommentId());
        assertEquals("u10001", c.getUid());
        assertEquals("elonm", c.getUserName());
        assertEquals("这条评论带转发", c.getText());
        assertEquals(12, c.getLikeCount());
        assertEquals(3, c.getReplyCount());
        assertEquals(1, c.getRepostCount());
        // ctime 是秒级时间戳（×1000）；期望值用同一公式计算，避免时区断言脆弱
        String expectedTime = TimeParser.dateFormatString(new Date(1748371200L * 1000));
        assertEquals(expectedTime, c.getTime());
        assertTrue(c.getTime().startsWith("2025-"), "秒级时间戳 ×1000 后应落在 2025 年: " + c.getTime());
        // 用户扩展收敛到固定字段（与微博共用语义）
        assertEquals(300L, c.getFollowCount());
        assertEquals(12000000L, c.getFansCount());
        assertEquals(58000, c.getStatusCount());
        assertEquals(" SpaceX", c.getDescription());
        // 星光特有字段进 extra
        Map<String, Object> extra = c.getExtra();
        assertEquals("1886043381941000000", extra.get("pid"));
        assertEquals(456, extra.get("visitCount"));
        assertEquals(7, extra.get("collectionCount"));
        assertEquals("zh", extra.get("langCode"));
        assertEquals(2, extra.get("wtype"));
        assertEquals(" California, USA", extra.get("userLocation"));
        assertEquals("马斯克观察", extra.get("nickname"));
        assertEquals("https://pbs.example/a.jpg", extra.get("profileImgUrl"));
        assertEquals(1, extra.get("verified"));
    }

    @Test
    @DisplayName("pid 不能映射到 parentCommentId（否则顶层评论被误判为回复）")
    void pidIsNotParentCommentId() {
        Comment c = Comment.buildFromXg(SAMPLE);
        assertNull(c.getParentCommentId());
        assertEquals("1886043381941000000", c.getExtra().get("pid"));
    }

    @Test
    @DisplayName("转发四件套：retweeted 存在时进 extra")
    void mapsRetweeted() {
        Map<String, Object> extra = Comment.buildFromXg(SAMPLE).getExtra();
        assertEquals("https://x.com/a/status/1", extra.get("retweetUrl"));
        assertEquals("1", extra.get("retweetMid"));
        assertEquals("u2", extra.get("retweetUid"));
        assertEquals("jack", extra.get("retweetName"));
    }

    @Test
    @DisplayName("无转发/无用户字段的评论不产生脏 extra 与脏时间")
    void toleratesMissingSections() {
        Comment c = Comment.buildFromXg("{\"mid\":\"m1\",\"ctime\":\"\",\"content\":\"hi\"}");
        assertNull(c.getUid());
        assertNull(c.getTime());
        assertNull(c.getExtra().get("retweetUrl"));
        assertNull(c.getExtra().get("userLocation"));
    }

    @Test
    @DisplayName("三平台 endpoint 与 providerKey 对应关系固定")
    void mapsEndpointsAndProviderKeys() {
        XgTwitterProvider twitter = new XgTwitterProvider();
        XgFacebookProvider facebook = new XgFacebookProvider();
        XgYoutubeProvider youtube = new XgYoutubeProvider();
        assertEquals(XgConstants.TWITTER_ENDPOINT, twitter.endpoint());
        assertEquals(XgConstants.FACEBOOK_ENDPOINT, facebook.endpoint());
        assertEquals(XgConstants.YOUTUBE_ENDPOINT, youtube.endpoint());
        assertEquals("xg_twitter", twitter.providerKey());
        assertEquals("xg_facebook", facebook.providerKey());
        assertEquals("xg_youtube", youtube.providerKey());
        // 与 db/init-comment-collector.js 的 provider_key 保持一致（Bean 名 = 配置键）
        assertTrue(List.of("xg_twitter", "xg_facebook", "xg_youtube")
                .containsAll(List.of(twitter.providerKey(), facebook.providerKey(), youtube.providerKey())));
    }
}
