package com.sysj.collector.core.provider.weibo;


import com.sysj.collector.core.provider.CommentProvider;

import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommonEntity;
import com.sysj.collector.model.CommonStatusEnum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 微博 - 微博官方开放平台接口供应商。
 *
 * <p>Bean 名称 = "weibo_official"。
 * 频次：2.0 req/s，由 DB 配置中的 ratePerSecond 驱动。
 */
@Slf4j
@Component("weibo_official")
public class WeiboGolaxyCrawlerProvider implements CommentProvider {

    @Override
    public String providerKey() {
        return "weibo_official";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        log.info("[WeiboOfficial] 采集开始: targetId={}", request.getTargetId());

        /*
         * 实际实现：
         * 1. OAuth 2.0 Token 获取（可缓存）
         * 2. 调用微博开放平台 /2/comments/show API
         * 3. 分页处理（result.get("max_id") 作为游标）
         * 4. 返回标准化 Comment 列表
         */

        return CommonEntity.<Comment>builder()
                .status(CommonStatusEnum.STATUS_SUCCESS)
                .dataList(List.of(Comment.builder()
                        .id(UUID.randomUUID().toString())
                        .content("【微博官方接口】微博评论内容示例")
                        .authorId("weibo_uid_003")
                        .authorName("微博用户C")
                        .publishTime(Instant.now())
                        .likeCount(99)
                        .build()))
                .build();
    }
}
