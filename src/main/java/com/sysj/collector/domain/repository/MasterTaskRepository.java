package com.sysj.collector.domain.repository;

import com.sysj.collector.domain.document.MasterTask;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 主任务 Repository。
 */
@Repository
public interface MasterTaskRepository extends MongoRepository<MasterTask, String> {

    /** 按用户ID查询 */
    List<MasterTask> findByUserId(String userId);

    /** 按状态查询 */
    List<MasterTask> findByStatus(String status);
}
