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
 * 微博 - 本地爬虫供应商。
 *
 * <p>Bean 名称 = "local_crawler"，与 MongoDB 配置中的 providerKey 一致。
 * 频次：0.5 req/s（1次/2秒），由 DB 配置驱动，此处无需任何限流代码。
 */
@Slf4j
@Component("local_crawler")
public class WeiboLocalCrawlerProvider implements CommentProvider {

    @Override
    public String providerKey() {
        return "local_crawler";
    }

    @Override
    public CommonEntity<Comment> fetchComments(CommentCollectRequest request) {
        log.info("[WeiboLocalCrawler] 采集开始: targetId={}", request.getTargetId());

        /*
         * 实际实现：
         * 1. 构建爬虫请求（Cookie、UA 等）
         * 2. HTTP 请求微博页面
         * 3. 解析 HTML / JSON 提取评论
         * 4. 转换为 Comment 列表返回
         */

        return CommonEntity.<Comment>builder()
                .status(CommonStatusEnum.STATUS_SUCCESS)
                .dataList(List.of(Comment.builder()
                        .id(UUID.randomUUID().toString())
                        .content("【本地爬虫】微博评论内容示例")
                        .authorId("weibo_uid_001")
                        .authorName("微博用户A")
                        .publishTime(Instant.now())
                        .likeCount(42)
                        .build()))
                .build();
    }
}
