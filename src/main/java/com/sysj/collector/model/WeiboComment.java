package com.sysj.collector.model;


import cn.idev.excel.annotation.ExcelIgnore;
import cn.idev.excel.annotation.ExcelProperty;
import com.bewilder.parser.CommonParser;
import com.bewilder.parser.TimeParser;
import com.bewilder.tools.CommonTools;
import com.bewilder.weibo.WeiboTool;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.util.Date;

@Data
@ToString
@SuperBuilder
@NoArgsConstructor
@Document(collection = "weibo_comment")
public class WeiboComment {

    @ExcelIgnore
    private String id;

    @ExcelIgnore
    @Indexed
    private String taskId; //主任务id
    @ExcelIgnore
    @Indexed
    private long insertTime; //插入时间
    @ExcelIgnore
    private String commentId; //评论id
    @ExcelProperty(value = "原微博地址")
    private String fromUrl;//原微博地址
    @ExcelProperty(value = "评论时间")
    private String time;//评论时间
    @ExcelProperty(value = "评论内容")
    private String text;//评论内容
    @ExcelProperty(value = "评论来源")
    private String source; //评论来源
    @ExcelProperty(value = "点赞数")
    private Integer likeCount;//点赞数
    @ExcelProperty(value = "回复数")
    private Integer replyCount;//回复数
    @ExcelProperty(value = "评论用户的id")
    private String uid; //评论用户的id
    @ExcelProperty(value = "评论用户的昵称")
    private String userName;//评论用户的昵称
    @ExcelProperty(value = "关注数")
    private long followCount;//关注数
    @ExcelProperty(value = "粉丝数")
    private long fansCount;//粉丝数
    @ExcelProperty(value = "发文数")
    private int statusCount; //微博发文数
    @ExcelProperty(value = "性别")
    private String gender;//性别
    @ExcelProperty(value = "用户位置")
    private String location;//用户位置
    @ExcelProperty(value = "用户描述")
    private String description;//用户描述
    @ExcelProperty(value = "用户认证类型")
    private String verifyType;//用户认证类型
    @ExcelProperty(value = "用户认证信息")
    private String verifyInfo;//用户认证信息
    @ExcelProperty(value = "是否铁粉")
    private String bigfans; //是否铁粉
    @ExcelProperty(value = "父评论id")
    private String parentCommentId;//父评论id


    public static WeiboComment builderLocal(String dataStr, String parentCommentId) {
        Integer verifyType = CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.user.verified_type"));
        Integer verifyTypeExt = CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.user.verified_type_ext"));
        String fansName = CommonParser.getJsonPathOne(dataStr, "$.user.fansIcon.name");
        return WeiboComment.builder()
                .commentId(CommonParser.getJsonPathOne(dataStr, "$.id"))
                .uid(CommonParser.getJsonPathOne(dataStr, "$.user.id"))
                .userName(CommonParser.getJsonPathOne(dataStr, "$.user.screen_name"))
                .followCount(CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.user.friends_count")))
                .fansCount(CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.user.followers_count")))
                .statusCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.user.statuses_count")))
                .gender(WeiboTool.changeSex(CommonParser.getJsonPathOne(dataStr, "$.user.gender")))
                .location(CommonParser.getJsonPathOne(dataStr, "$.user.location"))
                .description(CommonParser.getJsonPathOne(dataStr, "$.user.description"))
                .verifyType(WeiboTool.authorType(verifyType, verifyTypeExt))
                .verifyInfo(CommonParser.getJsonPathOne(dataStr, "$.user.verified_reason"))
                .bigfans(StringUtils.isNotBlank(fansName) && fansName.contains("loyal_fans") ? "1" : "0")
                .text(CommonParser.getJsonPathOne(dataStr, "$.text_raw"))
                .time(TimeParser.dateFormatString(new Date(CommonParser.getJsonPathOne(dataStr, "$.created_at"))))
                .source(CommonParser.getJsonPathOne(dataStr, "$.source"))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.total_number")))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.like_counts")))
                .parentCommentId(parentCommentId)
                .insertTime(System.currentTimeMillis())
                .build();
    }


    public static WeiboComment builderFromGolaxy(String dataStr) {
        long timeLong = CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.publish_time"));
        Integer verifyType = CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.verified_type"));
        Integer verifyTypeExt = CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.verified_type_ext"));
        return WeiboComment.builder()
                .commentId(CommonParser.getJsonPathOne(dataStr, "$.comment_id"))
                .parentCommentId(CommonParser.getJsonPathOne(dataStr, "$.root_comment_id"))
                .uid(CommonParser.getJsonPathOne(dataStr, "$.author_id"))
                .userName(CommonParser.getJsonPathOne(dataStr, "$.author_name"))
                .followCount(CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.friends_count")))
                .fansCount(CommonTools.stringToLong(CommonParser.getJsonPathOne(dataStr, "$.followers_count")))
                .statusCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.statuses_count")))
                .gender(WeiboTool.changeSex(CommonParser.getJsonPathOne(dataStr, "$.gender")))
                .location(CommonParser.getJsonPathOne(dataStr, "$.location"))
                .description(CommonParser.getJsonPathOne(dataStr, "$.description"))
                .verifyType(WeiboTool.changeVtype(verifyType))
                .verifyInfo(CommonParser.getJsonPathOne(dataStr, "$.verified_reason"))
                .text(CommonParser.getJsonPathOne(dataStr, "$.content"))
                .time(TimeParser.dateFormatString(new Date(timeLong)))
                .source(CommonParser.getJsonPathOne(dataStr, "$.post_location"))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.comments_count")))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(dataStr, "$.likes_count")))
                .insertTime(System.currentTimeMillis())
                .build();
    }

}