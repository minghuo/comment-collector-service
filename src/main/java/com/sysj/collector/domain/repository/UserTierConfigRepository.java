package com.sysj.collector.domain.repository;


import com.sysj.collector.domain.document.UserTierConfig;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserTierConfigRepository
        extends MongoRepository<UserTierConfig, String> {

    Optional<UserTierConfig> findByTierCode(String tierCode);
}
