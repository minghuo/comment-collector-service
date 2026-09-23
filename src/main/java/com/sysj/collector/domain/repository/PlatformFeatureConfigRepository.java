package com.sysj.collector.domain.repository;


import com.sysj.collector.domain.document.PlatformFeatureConfig;

import com.sysj.collector.domain.document.UserTierConfig;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlatformFeatureConfigRepository
        extends MongoRepository<PlatformFeatureConfig, String> {

    /**
     * 按平台编码 + 功能编码查询，走复合唯一索引，O(1)。
     */
    Optional<PlatformFeatureConfig> findByPlatformCodeAndFeatureCode(
            String platformCode, String featureCode);
}
