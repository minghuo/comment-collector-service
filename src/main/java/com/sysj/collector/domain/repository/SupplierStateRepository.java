package com.sysj.collector.domain.repository;

import com.sysj.collector.domain.document.SupplierState;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 供应商状态 Repository。
 */
@Repository
public interface SupplierStateRepository extends MongoRepository<SupplierState, String> {

    /** 按供应商Key查询 */
    Optional<SupplierState> findBySupplierKey(String supplierKey);
}
