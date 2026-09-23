package com.sysj.collector.domain.service;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysj.collector.domain.document.MasterTask;

import com.sysj.collector.domain.document.SubTask;

import com.sysj.collector.domain.document.UserTierConfig;

import com.sysj.collector.domain.dao.MasterTaskDao;

import com.sysj.collector.domain.dao.SubTaskDao;

import com.sysj.collector.facade.CommentCollectionFacade;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.CommentCollectResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务管理服务。
 *
 * <p>负责任务的创建、拆分、执行提交与状态收敛等核心逻辑。
 * 数据访问一律委托 Dao，本类不直接持有 {@code MongoTemplate}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskManagementService {

    /** 子任务状态常量（与设计文档 §5.6 的状态机一致）。 */
    private static final String ST_PENDING = "PENDING";
    private static final String ST_RUNNING = "RUNNING";
    private static final String ST_SUCCESS = "SUCCESS";
    private static final String ST_FAILED = "FAILED";

    private final MasterTaskDao masterTaskDao;
    private final SubTaskDao subTaskDao;
    private final ProviderConfigService configService;
    private final CommentCollectionFacade collectionFacade;
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

        masterTaskDao.save(masterTask);
        log.info("主任务创建成功: taskId={}, links={}", masterTask.getId(), links.size());

        // 2. 拆分为子任务并提交执行（结果会落库到 comment 集合，供 /api/tasks/{id}/comments 分页读取）
        List<SubTask> subTasks = splitIntoSubTasks(masterTask);

        // 3. 主任务进入执行中
        masterTask.setStatus("ACTIVE");
        masterTask.setStartTime(Instant.now());
        masterTask.setUpdateTime(Instant.now());
        masterTaskDao.save(masterTask);

        // 4. 逐个提交到采集门面（异步队列 + 优先级/防饥饿）
        for (SubTask subTask : subTasks) {
            submitSubTask(masterTask, subTask);
        }

        return masterTask;
    }

    /**
     * 将主任务按链接拆分为子任务
     *
     * @return 已落库的子任务列表
     */
    private List<SubTask> splitIntoSubTasks(MasterTask masterTask) {
        int userPriority = resolveUserPriority(masterTask.getUserTierCode());
        List<SubTask> subTasks = new ArrayList<>();

        for (String link : masterTask.getLinks()) {
            SubTask subTask = new SubTask();
            subTask.setId(generateSubTaskId());
            subTask.setMasterTaskId(masterTask.getId());
            subTask.setLink(link);
            subTask.setStatus(ST_PENDING);
            subTask.setPriority(userPriority);
            subTask.setRetryCount(0);
            subTask.setMaxRetry(3); // 默认重试3次，可从配置中获取
            subTask.setCreateTime(Instant.now());
            subTask.setUpdateTime(Instant.now());

            subTasks.add(subTask);
        }

        subTaskDao.saveAll(subTasks);
        log.info("子任务拆分完成: masterTaskId={}, subTaskCount={}",
                masterTask.getId(), subTasks.size());
        return subTasks;
    }

    /**
     * 提交单个子任务到采集门面，并在完成时回写子任务/主任务状态。
     *
     * <p>这是"异步任务真正被执行"的入口：此前只落库、没有任何消费者，子任务状态永远停在 PENDING。
     * 采集结果由门面在成功时写入 {@code comment} 集合（见 {@code CommentCollectionFacade#persist}）。
     */
    public void submitSubTask(MasterTask masterTask, SubTask subTask) {
        CommentCollectRequest collectRequest = CommentCollectRequest.builder()
                .platformCode(masterTask.getPlatformCode())
                .featureCode(masterTask.getFeatureCode())
                .targetId(subTask.getLink())
                .fromUrl(subTask.getLink())
                .taskId(masterTask.getId())
                .subTaskId(subTask.getId())
                .userId(masterTask.getUserId())
                .userTierCode(masterTask.getUserTierCode())
                .extra(parseExtra(masterTask.getRequestParams()))
                .build();

        subTask.setStatus(ST_RUNNING);
        subTask.setStartTime(Instant.now());
        subTask.setUpdateTime(Instant.now());
        subTaskDao.save(subTask);

        collectionFacade.collectAsync(collectRequest).whenComplete((result, ex) -> {
            try {
                applySubTaskResult(masterTask.getId(), subTask.getId(), result, ex);
            } catch (Exception e) {
                log.error("回写子任务结果失败: subTaskId={} error={}", subTask.getId(), e.getMessage(), e);
            }
        });
    }

    /**
     * 回写子任务结果并收敛主任务状态。
     */
    private void applySubTaskResult(String masterTaskId, String subTaskId,
                                    CommentCollectResult result, Throwable ex) {
        SubTask subTask = subTaskDao.findById(subTaskId).orElse(null);
        if (subTask == null) {
            log.warn("子任务已不存在，跳过回写: subTaskId={}", subTaskId);
            return;
        }
        if (ex != null) {
            subTask.setStatus(ST_FAILED);
            subTask.setErrorMessage("执行异常: " + ex.getMessage());
        } else if (result != null && result.isSuccess()) {
            subTask.setStatus(ST_SUCCESS);
            subTask.setProviderKey(result.getProviderUsed());
            subTask.setResult(buildResultSummary(result));
            subTask.setErrorMessage(null);
        } else {
            subTask.setStatus(ST_FAILED);
            subTask.setErrorMessage(result == null ? "无返回结果" : result.getErrorMessage());
        }
        subTask.setCompleteTime(Instant.now());
        subTask.setUpdateTime(Instant.now());
        subTaskDao.save(subTask);

        refreshMasterTaskStatus(masterTaskId);
    }

    /**
     * 依据子任务终态收敛主任务状态与计数。
     */
    private void refreshMasterTaskStatus(String masterTaskId) {
        List<SubTask> all = subTaskDao.findByMasterTaskId(masterTaskId);
        long success = all.stream().filter(s -> ST_SUCCESS.equals(s.getStatus())).count();
        long failed = all.stream().filter(s -> ST_FAILED.equals(s.getStatus())).count();
        long unfinished = all.stream()
                .filter(s -> !ST_SUCCESS.equals(s.getStatus()) && !ST_FAILED.equals(s.getStatus()))
                .count();

        masterTaskDao.findById(masterTaskId).ifPresent(master -> {
            master.setSuccessLinks((int) success);
            master.setFailedLinks((int) failed);
            master.setUpdateTime(Instant.now());
            if (unfinished == 0) {
                master.setStatus(failed > 0 && success == 0 ? "FAILED" : "COMPLETED");
                master.setCompleteTime(Instant.now());
            } else {
                master.setStatus("ACTIVE");
            }
            masterTaskDao.save(master);
            log.info("主任务状态收敛: taskId={} status={} success={} failed={} unfinished={}",
                    masterTaskId, master.getStatus(), success, failed, unfinished);
        });
    }

    /**
     * 子任务结果摘要（只存条数与游标，完整数据在 comment 集合里）。
     */
    private String buildResultSummary(CommentCollectResult result) {
        int count = result.getComments() == null ? 0 : result.getComments().size();
        return "{\"count\":" + count
                + ",\"hasMore\":" + result.isHasMore()
                + ",\"nextCursor\":" + (result.getNextCursor() == null ? "null" : "\"" + result.getNextCursor() + "\"")
                + "}";
    }

    /**
     * 解析任务参数为供应商 {@code extra}。
     *
     * <p>{@code requestParams} 是 JSON 字符串（如 {@code {"cookie":"...","sort":"hot","page":1}}），
     * 值统一转成字符串后交给供应商。
     */
    private Map<String, String> parseExtra(String requestParams) {
        if (requestParams == null || requestParams.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(requestParams, new TypeReference<Map<String, Object>>() {
            });
            Map<String, String> extra = new HashMap<>();
            raw.forEach((k, v) -> extra.put(k, v == null ? null : String.valueOf(v)));
            return extra;
        } catch (Exception e) {
            log.warn("requestParams 解析失败，按空 extra 处理: {}", requestParams);
            return Map.of();
        }
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
        MasterTask task = masterTaskDao.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        task.setStatus(status);
        task.setUpdateTime(Instant.now());

        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            task.setCompleteTime(Instant.now());
        }

        if (errorMessage != null) {
            task.setErrorMessage(errorMessage);
        }

        masterTaskDao.save(task);
        log.info("主任务状态更新: taskId={}, status={}", taskId, status);
    }

    /**
     * 更新子任务状态
     */
    public void updateSubTaskStatus(String subTaskId, String status, String result, String errorMessage) {
        SubTask subTask = subTaskDao.findById(subTaskId)
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

        subTaskDao.save(subTask);
        log.debug("子任务状态更新: subTaskId={}, status={}", subTaskId, status);
    }

    /**
     * 增加重试计数
     */
    public void incrementRetryCount(String subTaskId, int maxRetry) {
        SubTask subTask = subTaskDao.findById(subTaskId)
                .orElseThrow(() -> new IllegalArgumentException("子任务不存在: " + subTaskId));

        subTask.setRetryCount(subTask.getRetryCount() + 1);
        subTask.setMaxRetry(maxRetry);
        subTask.setStatus("RETRYING");

        // 计算下次执行时间（指数退避）
        long delayMs = 500L * (1L << subTask.getRetryCount());
        subTask.setNextExecuteTime(Instant.now().plusMillis(delayMs));
        subTask.setUpdateTime(Instant.now());

        subTaskDao.save(subTask);
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
        return masterTaskDao.findById(taskId).orElse(null);
    }

    /**
     * 根据主任务ID查询所有子任务
     */
    public List<SubTask> findSubTasksByMasterTaskId(String masterTaskId) {
        return subTaskDao.findByMasterTaskId(masterTaskId);
    }

    /**
     * 查询需要恢复的未完成子任务
     */
    public List<SubTask> findRecoverableSubTasks(List<String> statuses) {
        return subTaskDao.findByStatusIn(statuses);
    }

    /**
     * 保存子任务
     */
    public void saveSubTask(SubTask subTask) {
        subTask.setUpdateTime(Instant.now());
        subTaskDao.save(subTask);
    }
}
