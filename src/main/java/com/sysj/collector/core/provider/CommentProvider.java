package com.sysj.collector.core.provider;


import com.sysj.collector.model.Comment;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommonEntity;

import java.util.List;

/**
 * 评论供应商统一接口。
 *
 * <p>每个实现类代表一个具体供应商，Bean 名称须与
 * MongoDB {@code platform_feature_config.providers[].providerKey} 完全一致，
 * 路由器通过 ApplicationContext.getBean(providerKey) 获取实例。
 *
 * <p>实现类只关注业务逻辑（如何调用接口、解析返回值），
 * 限流、重试、优先级均由框架层处理。
 */
public interface CommentProvider {

    /**
     * 供应商唯一标识，与 Spring Bean 名称一致。
     */
    String providerKey();

    /**
     * 执行评论采集。
     * 失败时直接抛出异常，由上层执行器捕获并计入重试次数。
     */
    CommonEntity<Comment> fetchComments(CommentCollectRequest request);
}
