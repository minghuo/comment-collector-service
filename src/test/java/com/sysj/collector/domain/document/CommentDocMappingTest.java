package com.sysj.collector.domain.document;

import com.sysj.collector.model.Comment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 评论落库映射单元测试：验证新增社区平台（贴吧/荣耀/华为/OPPO/vivo/小米）的
 * {@link Comment} 能正确映射为 {@code comment} 集合的 {@link CommentDoc}——
 * dataType 判别值、业务主键（taskId-{内容id}-{commentId}）、关键字段不丢。
 */
class CommentDocMappingTest {

    private static final String TASK_ID = "MT1696";
    private static final String FROM_URL = "https://example.com/post";

    private CommentDoc doc(String platform, Comment c) {
        return CommentDoc.from(CommentDataType.of(platform, "comment"), platform,
                platform + "_local", TASK_ID, "ST1", FROM_URL, c);
    }

    @Test
    void tiebaCommentMapsToDoc() {
        String item = "{\"id\":\"110521473150011\",\"author_id\":\"u9\",\"time\":1695000000,"
                + "\"content\":[{\"type\":0,\"text\":\"楼主说得对\"},{\"type\":4,\"text\":\"image_emoticon1\"}],"
                + "\"agree\":{\"agree_num\":\"12\"},\"sub_post_number\":\"3\"}";
        String author = "{\"id\":\"u9\",\"name\":\"贴吧用户\",\"name_show\":\"吧友甲\",\"ip_address\":\"山东\"}";

        Comment c = Comment.buildFromTieba(item, author, null);
        c.setMid("11052147315");
        CommentDoc d = doc("tieba", c);

        assertEquals(CommentDataType.TIEBA_COMMENT, d.getDataType());
        assertEquals(TASK_ID + "-11052147315-110521473150011", d.getId(), "业务主键 = taskId-kz-commentId");
        assertEquals("11052147315", d.getMid());
        assertEquals("吧友甲", d.getUserName());
        assertEquals("山东", d.getIpLocation());
        assertEquals("楼主说得对,image_emoticon1", d.getText(), "content 数组逗号拼接");
        assertEquals(12, d.getLikeCount());
        assertEquals(3, d.getReplyCount());
    }

    @Test
    void huaweiCommentMapsToDoc() {
        String json = "{\"commentId\":\"c100\",\"authorInfo\":{\"userId\":\"u1\",\"nickName\":\"花粉乙\"},"
                + "\"createTime\":1759600000000,\"content\":\"好文\",\"likes\":\"5\",\"replies\":\"2\","
                + "\"ipLocation\":\"广东\"}";
        Comment c = Comment.buildFromHuaweiBbs(json);
        c.setMid("1000000000006064503");
        CommentDoc d = doc("huawei_bbs", c);

        assertEquals(CommentDataType.HUAWEI_BBS_COMMENT, d.getDataType());
        assertEquals(TASK_ID + "-1000000000006064503-c100", d.getId());
        assertNotNull(d.getTime(), "createTime 毫秒时间戳应格式化");
        assertEquals(5, d.getLikeCount());
        assertEquals(2, d.getReplyCount());
        assertEquals("广东", d.getIpLocation());
    }

    @Test
    void oppoFloorAndInlineMapToDoc() {
        // 楼层行（无 fusername）：commentId=$.pid
        Comment floor = Comment.buildFromOppoBbs(
                "{\"pid\":\"9001\",\"uid\":\"u2\",\"author\":{\"nickname\":\"O粉丙\"},\"dateline\":\"09-18 19:58\","
                        + "\"content\":\"顶\",\"praise\":\"7\",\"commentTotal\":\"4\",\"ipLocation\":\"浙江\",\"source\":\"PC\"}");
        floor.setMid("402994749");
        CommentDoc floorDoc = doc("oppo_bbs", floor);
        assertEquals(CommentDataType.OPPO_BBS_COMMENT, floorDoc.getDataType());
        assertEquals(TASK_ID + "-402994749-9001", floorDoc.getId(), "楼层 commentId = pid");
        assertEquals(4, floorDoc.getReplyCount());
        assertNull(floorDoc.getParentCommentId());

        // 楼中楼（含 fusername）：commentId=$.id、parentCommentId=$.pid
        Comment inline = Comment.buildFromOppoBbs(
                "{\"id\":\"9502\",\"pid\":\"9001\",\"fuid\":\"u3\",\"fusername\":\"O粉丁\",\"dateline\":\"09-18 20:01\","
                        + "\"content\":\"+1\",\"commentPraiseCount\":\"1\"}");
        inline.setMid("402994749");
        CommentDoc inlineDoc = doc("oppo_bbs", inline);
        assertEquals("9001", inlineDoc.getParentCommentId(), "楼中楼父评论为所属楼层");
        assertEquals(TASK_ID + "-402994749-9502", inlineDoc.getId());
    }

    @Test
    void vivoCommentWithRepliesMapsToDoc() {
        String main = "{\"id\":\"v1\",\"openId\":\"ou1\",\"userName\":\"V粉戊\",\"createTime\":1759600000000,"
                + "\"text\":\"不错\",\"likeNum\":\"9\",\"replyNum\":\"1\",\"ipLocation\":\"福建\",\"model\":\"X90\"}";
        Comment c = Comment.buildFromVivoBbs(main);
        c.setMid("39814022");
        CommentDoc d = doc("vivo_bbs", c);

        assertEquals(CommentDataType.VIVO_BBS_COMMENT, d.getDataType());
        assertEquals(TASK_ID + "-39814022-v1", d.getId());
        assertNull(d.getParentCommentId(), "顶层评论无父评论");
        assertEquals("X90", d.getSource(), "model → source");
    }

    @Test
    void honorCommentMapsToDoc() {
        // 输入为爬虫 XPath 解析后的字段 JSON（时间保留 Discuz 相对格式）
        String json = "{\"commentId\":\"3041\",\"uid\":\"66\",\"userName\":\"荣耀用户己\","
                + "\"time\":\"9-17 19:50:30\",\"text\":\"支持\",\"likeCount\":\"3\",\"replyCount\":\"0\","
                + "\"ipLocation\":\"四川\",\"source\":\"HONOR 90\"}";
        Comment c = Comment.buildFromHonorBbs(json);
        c.setMid("30407664");
        CommentDoc d = doc("honor_bbs", c);

        assertEquals(CommentDataType.HONOR_BBS_COMMENT, d.getDataType());
        assertEquals(TASK_ID + "-30407664-3041", d.getId());
        assertEquals("荣耀用户己", d.getUserName());
        assertEquals("四川", d.getIpLocation());
    }

    @Test
    void xiaomiCommentWithRepliesMapsToDoc() {
        String json = "{\"commentId\":\"x1\",\"createTime\":1759600000000,\"author\":{\"id\":\"mx1\",\"name\":\"米粉庚\"},"
                + "\"textContent\":\"好用\",\"ipRegion\":\"北京\",\"likeCnt\":\"20\",\"commentCnt\":\"1\","
                + "\"deviceType\":\"MI 14\",\"viewCount\":\"555\","
                + "\"reply\":[{\"commentId\":\"x2\",\"createTime\":1759600001000,\"author\":{\"id\":\"mx2\",\"name\":\"米粉辛\"},"
                + "\"textContent\":\"同感\",\"likeCnt\":\"1\",\"commentCnt\":\"0\",\"ipRegion\":\"天津\",\"deviceType\":\"K70\"}]}";

        List<Comment> comments = Comment.buildFromXiaomiBbsWithReplies(json, null);
        comments.forEach(c -> c.setMid("40571217"));
        assertEquals(2, comments.size(), "主评论 + 子回复展平");
        assertEquals("x1", comments.get(1).getParentCommentId(), "子回复 x2 的父评论为所属评论 x1");

        CommentDoc mainDoc = doc("xiaomi_bbs", comments.get(0));
        assertEquals(CommentDataType.XIAOMI_BBS_COMMENT, mainDoc.getDataType());
        assertEquals(TASK_ID + "-40571217-x1", mainDoc.getId());
        assertEquals(555, ((Number) mainDoc.getExtra().get("viewCount")).intValue(), "浏览量进 extra");
        assertEquals("MI 14", mainDoc.getSource());

        CommentDoc replyDoc = doc("xiaomi_bbs", comments.get(1));
        assertEquals(TASK_ID + "-40571217-x2", replyDoc.getId());
    }

    @Test
    void insertTimeFallsBackToNowWhenMissing() {
        Comment c = Comment.builder().commentId("x").build();
        CommentDoc d = doc("tieba", c);
        assertNotNull(d.getInsertTime(), "未携带入库时间时由 CommentDoc 补当前时间");
    }

    @Test
    void dataTypeOfCoversAllNewPlatforms() {
        assertEquals(CommentDataType.TIEBA_COMMENT, CommentDataType.of("tieba", "comment"));
        assertEquals(CommentDataType.HONOR_BBS_COMMENT, CommentDataType.of("honor_bbs", "comment"));
        assertEquals(CommentDataType.HUAWEI_BBS_COMMENT, CommentDataType.of("huawei_bbs", "comment"));
        assertEquals(CommentDataType.OPPO_BBS_COMMENT, CommentDataType.of("oppo_bbs", "comment"));
        assertEquals(CommentDataType.VIVO_BBS_COMMENT, CommentDataType.of("vivo_bbs", "comment"));
        assertEquals(CommentDataType.XIAOMI_BBS_COMMENT, CommentDataType.of("xiaomi_bbs", "comment"));
        assertEquals(CommentDataType.WEIBO_REPOST, CommentDataType.of("weibo", "repost"), "既有映射不回归");
    }
}
