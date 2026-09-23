package com.sysj.collector.domain.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysj.collector.domain.document.MasterTask;

import com.sysj.collector.domain.document.SubTask;

import com.sysj.collector.domain.document.UserTierConfig;

import com.sysj.collector.domain.repository.MasterTaskRepository;

import com.sysj.collector.domain.repository.SubTaskRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 任务管理服务。
 *
 * <p>负责任务的创建、拆分、状态更新等核心逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskManagementService {

    private final MasterTaskRepository masterTaskRepository;
    private final SubTaskRepository subTaskRepository;
    private final ProviderConfigService configService;
    private final ObjectMapper objectMapper;

    /**
     * 创建主任务并拆分为子任务
     */
    public MasterTask createMasterTask(
            String userId,
            String userTierCode,
            String platformCode,
            String featureCode,
            List<String> links,
            String mode,
            String requestParams,
            String supplierConstraint,
            String callbackUrl) {

        // 1. 创建主任务
        MasterTask masterTask = new MasterTask();
        masterTask.setId(generateTaskId());
        masterTask.setUserId(userId);
        masterTask.setUserTierCode(userTierCode);
        masterTask.setPlatformCode(platformCode);
        masterTask.setFeatureCode(featureCode);
        masterTask.setMode(mode);
        masterTask.setStatus("PENDING");
        masterTask.setLinks(links);
        masterTask.setRequestParams(requestParams);
        masterTask.setSupplierConstraint(supplierConstraint);
        masterTask.setCallbackUrl(callbackUrl);
        masterTask.setCreateTime(Instant.now());
        masterTask.setUpdateTime(Instant.now());
        masterTask.setTotalLinks(links.size());
        masterTask.setSuccessLinks(0);
        masterTask.setFailedLinks(0);

        masterTaskRepository.save(masterTask);
        log.info("主任务创建成功: taskId={}, links={}", masterTask.getId(), links.size());

        // 2. 拆分为子任务
        splitIntoSubTasks(masterTask);

        return masterTask;
    }

    /**
     * 将主任务按链接拆分为子任务
     */
    private void splitIntoSubTasks(MasterTask masterTask) {
        int userPriority = resolveUserPriority(masterTask.getUserTierCode());
        List<SubTask> subTasks = new ArrayList<>();

        for (String link : masterTask.getLinks()) {
            SubTask subTask = new SubTask();
            subTask.setId(generateSubTaskId());
            subTask.setMasterTaskId(masterTask.getId());
            subTask.setLink(link);
            subTask.setStatus("PENDING");
            subTask.setPriority(userPriority);
            subTask.setRetryCount(0);
            subTask.setMaxRetry(3); // 默认重试3次，可从配置中获取
            subTask.setCreateTime(Instant.now());
            subTask.setUpdateTime(Instant.now());

            subTasks.add(subTask);
        }

        subTaskRepository.saveAll(subTasks);
        log.info("子任务拆分完成: masterTaskId={}, subTaskCount={}",
                masterTask.getId(), subTasks.size());
    }

    /**
     * 解析用户优先级
     */
    private int resolveUserPriority(String tierCode) {
        if (tierCode == null) {
            return 999; // 默认最低优先级
        }
        return configService.loadUserTierConfig(tierCode)
                .map(UserTierConfig::getPriority)
                .orElse(999);
    }

    /**
     * 更新主任务状态
     */
    public void updateMasterTaskStatus(String taskId, String status, String errorMessage) {
        MasterTask task = masterTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        task.setStatus(status);
        task.setUpdateTime(Instant.now());

        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            task.setCompleteTime(Instant.now());
        }

        if (errorMessage != null) {
            task.setErrorMessage(errorMessage);
        }

        masterTaskRepository.save(task);
        log.info("主任务状态更新: taskId={}, status={}", taskId, status);
    }

    /**
     * 更新子任务状态
     */
    public void updateSubTaskStatus(String subTaskId, String status, String result, String errorMessage) {
        SubTask subTask = subTaskRepository.findById(subTaskId)
                .orElseThrow(() -> new IllegalArgumentException("子任务不存在: " + subTaskId));

        subTask.setStatus(status);
        subTask.setUpdateTime(Instant.now());

        if (result != null) {
            subTask.setResult(result);
        }

        if (errorMessage != null) {
            subTask.setErrorMessage(errorMessage);
        }

        if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
            subTask.setCompleteTime(Instant.now());
        }

        subTaskRepository.save(subTask);
        log.debug("子任务状态更新: subTaskId={}, status={}", subTaskId, status);
    }

    /**
     * 增加重试计数
     */
    public void incrementRetryCount(String subTaskId, int maxRetry) {
        SubTask subTask = subTaskRepository.findById(subTaskId)
                .orElseThrow(() -> new IllegalArgumentException("子任务不存在: " + subTaskId));

        subTask.setRetryCount(subTask.getRetryCount() + 1);
        subTask.setMaxRetry(maxRetry);
        subTask.setStatus("RETRYING");

        // 计算下次执行时间（指数退避）
        long delayMs = 500L * (1L << subTask.getRetryCount());
        subTask.setNextExecuteTime(Instant.now().plusMillis(delayMs));
        subTask.setUpdateTime(Instant.now());

        subTaskRepository.save(subTask);
        log.info("子任务重试计数增加: subTaskId={}, retryCount={}",
                subTaskId, subTask.getRetryCount());
    }

    /**
     * 生成主任务ID
     */
    private String generateTaskId() {
        return "MT" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 生成子任务ID
     */
    private String generateSubTaskId() {
        return "ST" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 根据主任务ID查询主任务
     */
    public MasterTask findMasterTask(String taskId) {
        return masterTaskRepository.findById(taskId).orElse(null);
    }

    /**
     * 根据主任务ID查询所有子任务
     */
    public List<SubTask> findSubTasksByMasterTaskId(String masterTaskId) {
        return subTaskRepository.findByMasterTaskId(masterTaskId);
    }

    /**
     * 查询需要恢复的未完成子任务
     */
    public List<SubTask> findRecoverableSubTasks(List<String> statuses) {
        return subTaskRepository.findByStatusIn(statuses);
    }

    /**
     * 保存子任务
     */
    public void saveSubTask(SubTask subTask) {
        subTask.setUpdateTime(Instant.now());
        subTaskRepository.save(subTask);
    }
}
