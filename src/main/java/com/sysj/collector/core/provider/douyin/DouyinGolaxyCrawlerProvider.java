package com.sysj.collector.core.provider.douyin;


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
 * 抖音 - 中科天玑接口供应商。
 *
 * <p>Bean 名称 = "douyin_golaxy"。
 * 频次由 DB 配置驱动（示例：1.0 req/s）。
 */
@Slf4j
@Component("douyin_golaxy")
public class DouyinGolaxyCrawlerProvider implements CommentProvider {

    private static final HttpUtil httpUtil = new HttpUtil.Builder().build();

    @Override
    public String providerKey() {
        return "douyin_golaxy";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        log.info("[DY-Golaxy] 采集开始: targetId={}", request.getTargetId());
        Map<String, String> extra = request.getExtra();
        String mid = extra.get("mid");
        String commentId = extra.get("commentId");
        Integer page = CommonTools.stringToInteger(extra.get("page"));
        if(StringUtils.isNotBlank(commentId)){
            return getCommentChild(mid, commentId, page);
        }
        return getComment(mid, page);
    }


    private static CommonEntity<Comment> getComment(String mid, int page) {
        String apiUrl = String.format("%s/douyin?apiKey=%s&video_id=%s&page=%s",
                GolaxyConstants.COMMENT_BASE_URL, GolaxyConstants.COMMENT_API_KEY, mid, page);

        boolean haseMore = false;
        try{
            String body = httpUtil.getString(apiUrl, false);
            if(StringUtils.isNotBlank(body) && body.contains("comments")){
                int totalPage = CommonTools.totalPage(CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.total")), 20);
                List<Comment> comments = CollUtil.newArrayList();
                haseMore = "1".equals(CommonParser.getJsonPathOne(body, "$.has_more"));
                List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.comments");
                if(CollUtil.isNotEmpty(commentStrList)){
                    for(String commentStr : commentStrList){
                        comments.add(Comment.buildFromGolaxy(commentStr));
                    }
                    return CommonEntity.<Comment>builder().haseMore(haseMore).totalPage(totalPage).status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
                }
            }
        }catch (Exception e){
            log.error("dy getComment error", e);
        }
        return CommonEntity.<Comment>builder().haseMore(haseMore).status(CommonStatusEnum.STATUS_ERROR).build();
    }


    private static CommonEntity<Comment> getCommentChild(String mid, String commentId,int page) {
        String apiUrl = String.format("%s/reply/douyin?apiKey=%s&video_id=%s&comment_id=%s&page=%s",
                GolaxyConstants.COMMENT_BASE_URL, GolaxyConstants.COMMENT_API_KEY, mid, commentId, page);

        boolean haseMore = false;
        try{
            String body = httpUtil.getString(apiUrl, false);
            if(StringUtils.isNotBlank(body) && body.contains("comments")){
                int totalPage = CommonTools.totalPage(CommonTools.stringToInteger(CommonParser.getJsonPathOne(body, "$.total")), 20);
                List<Comment> comments = CollUtil.newArrayList();
                haseMore = "1".equals(CommonParser.getJsonPathOne(body, "$.has_more"));
                List<String> commentStrList = CommonParser.getJsonPathMany(body, "$.comments");
                if(CollUtil.isNotEmpty(commentStrList)){
                    for(String commentStr : commentStrList){
                        comments.add(Comment.buildFromGolaxy(commentStr));
                    }
                }
                return CommonEntity.<Comment>builder().haseMore(haseMore).totalPage(totalPage).status(CommonStatusEnum.STATUS_SUCCESS).dataList(comments).build();
            }
        }catch (Exception e){
            log.error("dy getComment error", e);
        }
        return CommonEntity.<Comment>builder().haseMore(haseMore).status(CommonStatusEnum.STATUS_ERROR).build();
    }
}
