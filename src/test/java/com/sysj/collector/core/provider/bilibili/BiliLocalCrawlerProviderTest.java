package com.sysj.collector.core.provider.bilibili;

import com.sysj.collector.model.Comment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * B站本地爬虫的**纯判据**回归用例（不发 HTTP）。
 *
 * <p>覆盖容易悄悄改坏的两件事：
 * <ol>
 *   <li>{@code buildFromBiliLocal} 字段映射：{@code ctime} 是秒级时间戳（×1000）、
 *       {@code mid} 必须取评论区 oid（业务主键 {@code taskId-{oid}-{rpid}}，
 *       缺 oid 时同一视频的 rpid 在不同任务间会互相覆盖）、oid/type 进 extra；</li>
 *   <li>providerKey 与 db/init-comment-collector.js 的 provider_key 对齐。</li>
 * </ol>
 */
class BiliLocalCrawlerProviderTest {

    /** 响应结构与 auto-task-web CommentsFactory.biliLocal 的输入一致 */
    private static final String SAMPLE = """
            {"rpid":281927421234,"oid":123456789,"type":1,"root":0,"ctime":1748371200,
             "like":"42","rcount":"3",
             "content":{"message":"这是一条B站评论"},
             "member":{"mid":"9988776","uname":"冷静的观察者","sex":"男"},
             "reply_control":{"location":"IP属地：广东"}}
            """;

    @Test
    @DisplayName("字段映射：rpid/oid/用户/内容/IP属地齐全，mid=oid")
    void mapsStandardFields() {
        Comment c = Comment.buildFromBiliLocal(SAMPLE);
        assertEquals("281927421234", c.getCommentId());
        assertEquals("123456789", c.getMid());
        assertEquals("9988776", c.getUid());
        assertEquals("冷静的观察者", c.getUserName());
        assertEquals("这是一条B站评论", c.getText());
        assertEquals(42, c.getLikeCount());
        assertEquals(3, c.getReplyCount());
        assertEquals("IP属地：广东", c.getIpLocation());
        assertEquals("男", c.getGender());
        // 与 auto-task-web 的 biliLocal 行为一致：顶层评论 root 解析为 "0" 原样保留
        assertEquals("0", c.getParentCommentId());
        // ctime 是秒级时间戳（×1000），期望值用同一公式计算，避免时区断言脆弱
        assertEquals(com.bewilder.parser.TimeParser.dateFormatString(
                new java.util.Date(1748371200L * 1000)), c.getTime());
        Map<String, Object> extra = c.getExtra();
        assertEquals("123456789", extra.get("oid"));
        assertEquals("1", extra.get("type"));
    }

    @Test
    @DisplayName("回复（root 非零）映射 parentCommentId；ctime 缺失不出脏时间")
    void mapsReplyAndToleratesMissing() {
        Comment reply = Comment.buildFromBiliLocal(
                SAMPLE.replace("\"root\":0", "\"root\":281927421234"));
        assertEquals("281927421234", reply.getParentCommentId());
        Comment blank = Comment.buildFromBiliLocal("{\"rpid\":\"r1\",\"content\":{\"message\":\"hi\"}}");
        assertNull(blank.getTime());
        assertNull(blank.getMid());
        assertNull(blank.getLikeCount());
    }

    @Test
    @DisplayName("providerKey 与 init 脚本的 provider_key 一致")
    void providerKeyIsStable() {
        assertEquals("bilibili_local", new BiliLocalCrawlerProvider().providerKey());
    }
}
