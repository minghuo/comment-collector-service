package com.sysj.collector.domain.repository;

import com.sysj.collector.domain.document.SubTask;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 子任务 Repository。
 */
@Repository
public interface SubTaskRepository extends MongoRepository<SubTask, String> {

    /** 按主任务ID查询所有子任务 */
    List<SubTask> findByMasterTaskId(String masterTaskId);

    /** 按状态查询 */
    List<SubTask> findByStatus(String status);

    /** 按状态列表批量查询（用于断点续采） */
    List<SubTask> findByStatusIn(List<String> statuses);
}
