package com.sysj.collector.core.provider.xhs;


import cn.hutool.core.collection.CollUtil;
import com.bewilder.parser.CommonParser;
import com.bewilder.tools.CommonTools;
import com.sysj.collector.constants.GolaxyConstants;

import com.sysj.collector.core.provider.CommentProvider;

import com.sysj.collector.model.Comment;

import com.sysj.collector.model.CommentCollectRequest;

import com.sysj.collector.model.CommonEntity;

import com.sysj.collector.model.CommonStatusEnum;

import com.sysj.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * 小红书 - 中科天玑供应商。
 *
 * <p>Bean 名称 = "xhs_golaxy"。
 * 不同平台的同类供应商须使用不同的 providerKey 区分。
 */
@Slf4j
@Component("xhs_golaxy")
public class XhsGolaxyCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().build();

    @Override
    public String providerKey() {
        return "xhs_golaxy";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        log.info("[XHS-Golaxy] 采集开始: targetId={}", request.getTargetId());
        Map<String, String> extra = request.getExtra();
        String mid = extra.get("mid");
        String commentId = extra.get("commentId");
        String cursor = extra.get("cursor");
        if(StringUtils.isNotBlank(commentId)){
            return getCommentChild(mid, commentId, cursor);
        }
        return getComment(mid, cursor);
    }

    private static CommonEntity<Comment> getComment(String mid, String cursor) {
        String apiUrl = String.format("%s/xiaohongshu/v2?apiKey=%s&video_id=%s&cursor=%s",
                GolaxyConstants.COMMENT_BASE_URL, GolaxyConstants.COMMENT_API_KEY, mid, cursor);
        log.info("xhs getComment apiUrl:" + apiUrl);
        boolean haseMore = false;
        try{
            String body = httpUtil.getString(apiUrl, false);
            if(StringUtils.isNotBlank(body) && body.contains("comments")){
                int totalPage = CommonTools.totalPage(CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.total")), 20);
                List<Comment> comments = CollUtil.newArrayList();
                haseMore = "1".equals(CommonParser.getJsonPathOne(body, "$.has_more"));
                cursor = CommonParser.getJsonPathOne(body, "$.cursor");
                List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.comments");
                if(CollUtil.isNotEmpty(commentStrList)){
                    for(String commentStr : commentStrList){
                        comments.add(Comment.buildFromGolaxy(commentStr));
                    }
                    return CommonEntity.<Comment>builder().haseMore(haseMore).totalPage(totalPage).nextUrl(cursor).status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
                }
            }
        }catch (Exception e){
            log.error("xhs getComment error", e);
        }
        return CommonEntity.<Comment>builder().haseMore(haseMore).status(CommonStatusEnum.STATUS_ERROR).build();
    }


    private static CommonEntity<Comment> getCommentChild(String mid, String commentId, String cursor) {
        String apiUrl = String.format("%s/reply/xiaohongshu/v2?apiKey=%s&video_id=%s&comment_id=%s&cursor=%s",
                GolaxyConstants.COMMENT_BASE_URL, GolaxyConstants.COMMENT_API_KEY, mid, commentId, cursor);


        boolean haseMore = false;
        try{
            String body = httpUtil.getString(apiUrl, false);
            if(StringUtils.isNotBlank(body) && body.contains("comments")){
                int totalPage = CommonTools.totalPage(CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.total")), 20);
                List<Comment> comments = CollUtil.newArrayList();
                haseMore = "1".equals(CommonParser.getJsonPathOne(body, "$.has_more"));
                cursor = CommonParser.getJsonPathOne(body, "$.cursor");
                List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.comments");
                if(CollUtil.isNotEmpty(commentStrList)){
                    for(String commentStr : commentStrList){
                        comments.add(Comment.buildFromGolaxy(commentStr));
                    }
                }
                return CommonEntity.<Comment>builder().haseMore(haseMore).nextUrl(cursor).totalPage(totalPage).status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
            }
        }catch (Exception e){
            log.error("xhs child getComment error", e);
        }
        return CommonEntity.<Comment>builder().haseMore(haseMore).status(CommonStatusEnum.STATUS_ERROR).build();
    }
}
