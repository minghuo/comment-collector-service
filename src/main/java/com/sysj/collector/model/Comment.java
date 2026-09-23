package com.sysj.collector.model;


import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class Comment {
    private String id;
    private String commentId;
    private String content;
    private String text;
    private String authorId;
    private String uid;
    private String authorName;
    private String userName;
    private Instant publishTime;
    private String time;
    private long likeCount;
    private int replyCount;
    private String parentCommentId;
    private String ipLocation;
    private long insertTime;

    /**
     * 从Golaxy（中科天玑）接口返回的JSON字符串中构建Comment。
     * @param jsonStr JSON字符串
     * @return Comment实例
     */
    public static Comment buildFromGolaxy(String jsonStr) {
        return Comment.builder()
                .id(jsonStr)
                .content(jsonStr)
                .build();
    }
}
