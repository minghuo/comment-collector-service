package com.sysj.collector.core.provider.toutiao;


import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.parser.TimeParser;
import com.bewilder.tools.CommonTools;
import com.sysj.collector.core.provider.CommentProvider;

import com.sysj.collector.model.Comment;

import com.sysj.collector.model.CommentCollectRequest;

import com.sysj.collector.model.CommonEntity;

import com.sysj.collector.model.CommonStatusEnum;

import com.sysj.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 今日头条 - 本地爬虫供应商。
 *
 * <p>Bean 名称 = "toutiao_local"。
 * 不同平台的同类供应商须使用不同的 providerKey 区分。
 */
@Slf4j
@Component("toutiao_local")
public class TouTiaoLocalCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().build();

    @Override
    public String providerKey() {
        return "toutiao_local";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        log.info("[TOUTIAPO-LOCAL] 采集开始: targetId={}", request.getTargetId());
        Map<String, String> extra = request.getExtra();
        String mid = extra.get("mid");
        String commentId = extra.get("commentId");
        Integer page = CommonTools.stringToInteger(extra.get("page"));
        if(StringUtils.isNotBlank(commentId)){
            return getCommentChild(commentId, page);
        }
        return getComment(mid, page);
    }

    /**
     * 获取今日头条评论
     * @param groupId 链接地址
     * @param page 页码
     * @return 评论列表
     */
    private CommonEntity<Comment> getComment(String groupId, int page) {

        String apiUrl = "https://www.toutiao.com/article/v2/tab_comments/?aid=24&app_name=toutiao_web&offset="
                + (page - 1) * 20 + "&count=20&_signature=&group_id=" + groupId + "&item_id=" + groupId;
        log.info("apiUrl is {}", apiUrl);
        Map<String, String> headMap = new HashMap<>();
        headMap.put("User-Agent", "News 7.7.3 rv:7.7.3.21 (iPhone; iOS 12.3.1; zh_CN) Cronet");
        try {
            List<Comment> comments = new ArrayList<>();
            boolean hasMore = false;
            String htmlBody = httpUtil.getString(apiUrl, headMap);
            if(StringUtils.isNotBlank(htmlBody) && htmlBody.contains("data")){
                List<String> commentsStrList = CommonParser.getJsonPathMany(htmlBody, "$.data.comment");
                if(CollUtil.isNotEmpty(commentsStrList)){
                    for(String commentStr : commentsStrList){
                        comments.add(parseComment(commentStr));
                    }
                }
                hasMore = "true".equals(CommonParser.getJsonPathOne(htmlBody, "$.has_more"));
            }
            return CommonEntity.<Comment>builder().haseMore(hasMore).dataList(comments).build();
        } catch (Exception e) {
            log.debug("今日头条评论采集出错", e);
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }


    /**
     * 获取今日头条评论回复
     * @param commentId 评论id
     * @param page 页码
     * @return 评论列表
     */
    private CommonEntity<Comment> getCommentChild(String commentId, int page) {
        String apiUrl = "https://www.toutiao.com/2/comment/v2/reply_list/?aid=24&app_name=toutiao_web&id=" + commentId + "&offset="+ (page-1)*20 + "&count=20&repost=0&_signature=_";;
        Map<String, String> headMap = new HashMap<>();
        headMap.put("User-Agent", "News 7.7.3 rv:7.7.3.21 (iPhone; iOS 12.3.1; zh_CN) Cronet");
        try {
            List<Comment> comments = new ArrayList<>();
            boolean hasMore = false;
            String htmlBody = httpUtil.getString(apiUrl, headMap);
            if(StringUtils.isNotBlank(htmlBody) && htmlBody.contains("data")){
                List<String> commentsStrList = CommonParser.getJsonPathMany(htmlBody, "$.data");
                for (String commentStr : commentsStrList){
                    Comment childComment = parseComment(commentStr);
                    childComment.setParentCommentId(commentId);
                    comments.add(childComment);
                }
                hasMore = "true".equals(CommonParser.getJsonPathOne(htmlBody, "$.data.has_more"));
            }
            return CommonEntity.<Comment>builder().haseMore(hasMore).dataList(comments).build();
        } catch (Exception e) {
            log.debug("今日头条评论采集出错", e);
        }
        return CommonEntity.<Comment>builder().haseMore(false).status(CommonStatusEnum.STATUS_ERROR).build();
    }


    private Comment parseComment(String commentStr) {
        long timeLong = CommonTools.stringToLong(CommonParser.getJsonPathOne(commentStr, "$.create_time")) * 1000L;
        return Comment.builder()
                .commentId(CommonParser.getJsonPathOne(commentStr, "$.id"))
                .uid(CommonParser.getJsonPathOne(commentStr, "$.user_id"))
                .userName(CommonParser.getJsonPathOne(commentStr, "$.user_name"))
                .time(TimeParser.dateFormatString(new Date(timeLong)))
                .text(CommonParser.getJsonPathOne(commentStr, "$.text"))
                .likeCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.digg_count")))
                .parentCommentId(CommonParser.getJsonPathOne(commentStr, "$.root"))
                .replyCount(CommonTools.stringToInteger(CommonParser.getJsonPathOne(commentStr, "$.reply_count|$.forward_count")))
                .ipLocation(CommonParser.getJsonPathOne(commentStr, "$.publish_loc_info"))
                .insertTime(System.currentTimeMillis())
                .build();
    }
}
