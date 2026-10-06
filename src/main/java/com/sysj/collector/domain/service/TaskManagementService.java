package com.sysj.collector.domain.service;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysj.collector.domain.document.MasterTask;
import com.sysj.collector.domain.document.SubTask;
import com.sysj.collector.domain.document.TaskStatuses;
import com.sysj.collector.domain.document.UserTierConfig;
import com.sysj.collector.domain.dao.MasterTaskDao;
import com.sysj.collector.domain.dao.SubTaskDao;
import com.sysj.collector.facade.CommentCollectionFacade;
import com.sysj.collector.facade.TaskResultListener;
import com.sysj.collector.exception.QueueFullException;
import com.sysj.collector.model.CommentCollectRequest;
import com.sysj.collector.model.TaskCreateCommand;
import com.sysj.collector.model.CommentCollectResult;
import com.mongodb.DuplicateKeyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;


/**
 * 任务管理服务。
 *
 * <p>负责任务的创建、拆分、执行提交与状态收敛等核心逻辑。
 * 数据访问一律委托 Dao，本类不直接持有 {@code MongoTemplate}。
 *
 * <h3>子任务终态收敛（原子计数，阶段1 改造）</h3>
 * 子任务到达终态（SUCCESS / FAILED）时通过
 * {@link MasterTaskDao#recordSubTaskOutcome} 对主任务做原子 {@code $inc}，
 * 再按 {@code successLinks + failedLinks >= totalLinks} 判断是否全部终态、
 * 由 {@link MasterTaskDao#transitionToTerminalIfFirst} 条件迁移。
 * 取代原先"每个子任务完成都全量拉取该主任务的所有子任务重算"的 O(N²) 收敛。
 *
 * <h3>子任务级重试闭环</h3>
 * 单次执行（含供应商内重试与候选切换）整体失败后，非确定性失败按
 * {@link SubTaskRetryPolicy} 指数退避置为 RETRYING，由
 * {@link SubTaskRetryScheduler} 扫描到期子任务重投队列（见 {@link #resubmitForRetry}）。
 * 重投期间的子任务不计入主任务计数，只有终态才计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskManagementService implements TaskResultListener {

    private static final String ST_PENDING = TaskStatuses.SUB_PENDING;
    private static final String ST_RUNNING = TaskStatuses.SUB_RUNNING;
    private static final String ST_RETRYING = TaskStatuses.SUB_RETRYING;
    private static final String ST_SUCCESS = TaskStatuses.SUB_SUCCESS;
    private static final String ST_FAILED = TaskStatuses.SUB_FAILED;

    private final MasterTaskDao masterTaskDao;
    private final SubTaskDao subTaskDao;
    private final ProviderConfigService configService;
    private final CommentCollectionFacade collectionFacade;
    private final TaskCallbackService taskCallbackService;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    /** 子任务级重投次数上限**默认值**（system_config 同名键可覆盖；管线内的供应商级重试次数另算）。 */
    @Value("${collector.task.sub-retry-max:2}")
    private int subRetryMaxDefault;

    /** 重投退避基数（毫秒）默认值：第 n 次重投等待 base × 2^(n-1)。 */
    @Value("${collector.task.sub-retry-base-delay-ms:2000}")
    private long subRetryBaseDelayMsDefault;

    /** 重投退避封顶（毫秒）默认值。 */
    @Value("${collector.task.sub-retry-max-delay-ms:600000}")
    private long subRetryMaxDelayMsDefault;

    /** 自动翻页全局默认（system_config 同名键可覆盖；任务级 autoPage 优先于全局）。 */
    @Value("${collector.task.auto-paging.enabled:false}")
    private boolean autoPagingEnabledDefault;

    /** 自动翻页最大深度默认值：子任务链最长采到第 N 页。 */
    @Value("${collector.task.auto-paging.max-pages:10}")
    private int autoPagingMaxPagesDefault;

    // ── 动态配置读取（system_config 覆盖 properties 默认，60s 缓存） ────────

    private int subRetryMax() {
        return (int) systemConfigService.getLong("collector.task.sub-retry-max", subRetryMaxDefault);
    }

    private long subRetryBaseDelayMs() {
        return systemConfigService.getLong("collector.task.sub-retry-base-delay-ms", subRetryBaseDelayMsDefault);
    }

    private long subRetryMaxDelayMs() {
        return systemConfigService.getLong("collector.task.sub-retry-max-delay-ms", subRetryMaxDelayMsDefault);
    }

    private int autoPagingMaxPages() {
        return (int) systemConfigService.getLong("collector.task.auto-paging.max-pages", autoPagingMaxPagesDefault);
    }

    /**
     * 创建主任务并拆分为子任务（命令对象形式）。
     *
     * <p>幂等：{@code cmd.idempotencyKey} 非空时，同 key 重复提交（含并发）返回已建成的主任务。
     * 过载保护：入口对全部链接**原子整批预留**队列名额，余量不足抛 503 且不留脏数据。
     */
    public MasterTask createMasterTask(TaskCreateCommand cmd) {
        String idempotencyKey = cmd.getIdempotencyKey();

        // -1. 提交幂等：同 key 已存在直接返回，不重复建任务、不重复占队列名额
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<MasterTask> existing = masterTaskDao.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("幂等命中，返回已存在主任务: taskId={} idempotencyKey={}",
                        existing.get().getId(), idempotencyKey);
                return existing.get();
            }
        }

        // 0. 过载保护 fail-fast：**原子预留**本次请求全部链接的队列名额。
        //    必须原子：并发突发下"先查容量再入队"会让所有请求都看到余量而全部放行（TOCTOU）。
        //    必须在落库之前：否则拒绝时会留下"主任务已建、子任务拆了一半"的脏数据。
        //    必须整批预留：部分受理对调用方毫无意义（既没拿到 503、也无法整批重试）。
        int slotsNeeded = Math.max(1, cmd.getLinks() == null ? 1 : cmd.getLinks().size());
        collectionFacade.reserveCapacity(slotsNeeded);
        int slotsUnused = slotsNeeded;
        try {
            MasterTask masterTask = doCreateMasterTask(cmd, slotsNeeded);
            slotsUnused -= masterTask.getTotalLinks();
            return masterTask;
        } catch (DuplicateKeyException dke) {
            // 并发同 key 提交被唯一索引兜底拦截：回查已建成的那条返回（幂等语义优先于报错）
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Optional<MasterTask> existing = masterTaskDao.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    log.info("幂等键并发提交，返回已建成主任务: taskId={} idempotencyKey={}",
                            existing.get().getId(), idempotencyKey);
                    return existing.get();
                }
            }
            throw dke;
        } finally {
            // 归还没被任何一个子任务消费掉的预留名额（links 去重减少的名额也在这里归还）
            collectionFacade.releaseCapacity(slotsUnused);
        }
    }

    /**
     * 真正执行建任务；调用前必须已通过 {@link CommentCollectionFacade#reserveCapacity(int)} 预留名额。
     *
     * @param reservedSlots 已预留的名额数，等于原始 links 数；每个子任务消费一个
     */
    private MasterTask doCreateMasterTask(TaskCreateCommand cmd, int reservedSlots) {

        // 0.5 链接去重（保序）：同一主任务内重复链接只建一个子任务，
        //     与 sub_task (master_task_id, link) 唯一索引对齐；多预留的名额由调用方 finally 归还
        List<String> dedupedLinks = new ArrayList<>(new LinkedHashSet<>(
                cmd.getLinks() == null ? List.of() : cmd.getLinks()));

        // 1. 创建主任务
        MasterTask masterTask = new MasterTask();
        masterTask.setId(generateTaskId());
        masterTask.setUserId(cmd.getUserId());
        masterTask.setUserTierCode(cmd.getUserTierCode());
        masterTask.setPlatformCode(cmd.getPlatformCode());
        masterTask.setFeatureCode(cmd.getFeatureCode());
        masterTask.setMode(cmd.getMode());
        masterTask.setStatus("PENDING");
        masterTask.setLinks(dedupedLinks);
        masterTask.setRequestParams(cmd.getRequestParams());
        masterTask.setSupplierConstraint(cmd.getSupplierConstraint());
        masterTask.setCallbackUrl(cmd.getCallbackUrl());
        masterTask.setDispatchId(cmd.getDispatchId());
        masterTask.setRequiredCapabilities(cmd.getRequiredCapabilities());
        masterTask.setExcludedCapabilities(cmd.getExcludedCapabilities());
        masterTask.setIdempotencyKey(cmd.getIdempotencyKey());
        masterTask.setAutoPage(cmd.getAutoPage());
        masterTask.setCreateTime(Instant.now());
        masterTask.setUpdateTime(Instant.now());
        masterTask.setTotalLinks(dedupedLinks.size());
        masterTask.setSuccessLinks(0);
        masterTask.setFailedLinks(0);

        if (dedupedLinks.size() < (cmd.getLinks() == null ? 0 : cmd.getLinks().size())) {
            log.info("提交链接存在重复，已去重: platform={} 原始={} 去重后={}",
                    cmd.getPlatformCode(), cmd.getLinks().size(), dedupedLinks.size());
        }

        masterTaskDao.save(masterTask);
        log.info("主任务创建成功: taskId={} links={} idempotencyKey={}",
                masterTask.getId(), dedupedLinks.size(), cmd.getIdempotencyKey());

        // 2. 拆分为子任务并提交执行（结果会落库到 comment 集合，供 /api/tasks/{id}/comments 分页读取）
        List<SubTask> subTasks = splitIntoSubTasks(masterTask);

        // 3. 主任务进入执行中
        masterTask.setStatus("ACTIVE");
        masterTask.setStartTime(Instant.now());
        masterTask.setUpdateTime(Instant.now());
        masterTaskDao.save(masterTask);

        // 4. 逐个提交到采集门面（异步队列 + 优先级/防饥饿）。名额已在入口一次性预留，此处不会再因容量失败
        for (SubTask subTask : subTasks) {
            submitSubTask(masterTask, subTask, true);
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
            subTask.setMaxRetry(subRetryMax());
            subTask.setPageCount(1);
            subTask.setCreateTime(Instant.now());
            subTask.setUpdateTime(Instant.now());

            subTasks.add(subTask);
        }

        subTaskDao.saveAll(subTasks);
        log.info("子任务拆分完成: masterTaskId={} subTaskCount={}",
                masterTask.getId(), subTasks.size());
        return subTasks;
    }

    /**
     * 提交单个子任务到采集门面（**未预留名额**：入口仍整批预检，XADD 逐条原子校验）。
     *
     * <p>用于恢复扫描、自动翻页续采等"不属于某个已预留批次"的场景。
     */
    public void submitSubTask(MasterTask masterTask, SubTask subTask) {
        submitSubTask(masterTask, subTask, false);
    }

    /**
     * 提交单个子任务：写入 Redis Stream 外置队列，由消费者组执行。
     *
     * <p>外置队列后提交与执行分离：本方法只做"置 RUNNING + 入队"，没有 Future 回调；
     * 结果回写由消费者完成后经 {@link TaskResultListener#onSubTaskOutcome} 回到本服务
     * （可能发生在任意一个消费实例上）。
     *
     * @param capacityReserved 历史参数：外置队列后容量在每条消息 XADD 时原子校验，
     *                         该参数不再影响行为，保留以兼容调用方
     */
    public void submitSubTask(MasterTask masterTask, SubTask subTask, boolean capacityReserved) {
        CommentCollectRequest collectRequest = buildCollectRequest(masterTask, subTask);

        subTask.setStatus(ST_RUNNING);
        subTask.setStartTime(Instant.now());
        subTask.setUpdateTime(Instant.now());
        subTaskDao.save(subTask);

        try {
            collectionFacade.enqueueAsync(collectRequest);
        } catch (QueueFullException qfe) {
            // 入队被容量硬上限拒绝：必须把该子任务收敛为终态，
            // 否则它会永远停在 RUNNING，主任务也永远不完成。
            log.warn("子任务入队被拒绝: subTaskId={} queueSize={}/{}",
                    subTask.getId(), qfe.getQueueSize(), qfe.getQueueCapacity());
            if (markSubTaskFailed(subTask, "服务繁忙：异步队列已满，请稍后重试")) {
                recordOutcomeAndFinish(masterTask.getId(), false);
            }
        }
    }

    /**
     * 重投一个到期的子任务（由 {@code SubTaskRetryScheduler} 扫描调用）。
     *
     * <p>与 {@link #submitSubTask} 的差别在队列满时的语义：正常提交被拒即判死，
     * 重投被拒**不判死** —— 保持 RETRYING 并把 nextExecuteTime 推迟
     * {@code queueRescheduleMs}，等下一次扫描再试。队列满说明系统繁忙，
     * 此时把重投任务也判死会让重试闭环在最需要它的时刻失效。
     */
    public void resubmitForRetry(MasterTask masterTask, SubTask subTask, long queueRescheduleMs) {
        CommentCollectRequest collectRequest = buildCollectRequest(masterTask, subTask);

        subTask.setStatus(ST_RUNNING);
        subTask.setStartTime(Instant.now());
        subTask.setUpdateTime(Instant.now());
        subTaskDao.save(subTask);

        try {
            collectionFacade.enqueueAsync(collectRequest);
        } catch (QueueFullException qfe) {
            log.info("重投被队列拒绝，推迟到下次扫描: subTaskId={} retryCount={} 推迟{}ms",
                    subTask.getId(), subTask.getRetryCount(), queueRescheduleMs);
            subTask.setStatus(ST_RETRYING);
            subTask.setNextExecuteTime(Instant.now().plusMillis(Math.max(1000, queueRescheduleMs)));
            subTask.setUpdateTime(Instant.now());
            subTaskDao.save(subTask);
        }
    }

    /**
     * 组装采集请求（首投、重投、续采共用；能力约束随主任务透传）。
     *
     * <p>续采子任务（{@code pageCount > 1}）把游标回填到平台对应的 extra 键
     * （契约见 {@link com.sysj.collector.core.provider.support.PlatformCursorKeys}）：
     * 游标类平台回填 {@code pageCursor} 里存的平台游标；页码类平台回填页码。
     */
    private CommentCollectRequest buildCollectRequest(MasterTask masterTask, SubTask subTask) {
        Map<String, String> extra = new HashMap<>(parseExtra(masterTask.getRequestParams()));
        if (subTask.getPageCount() > 1 && subTask.getPageCursor() != null) {
            com.sysj.collector.core.provider.support.PlatformCursorKeys.of(
                            masterTask.getPlatformCode(), masterTask.getFeatureCode())
                    .ifPresent(mode -> extra.put(mode.key(), subTask.getPageCursor()));
        }
        return CommentCollectRequest.builder()
                .platformCode(masterTask.getPlatformCode())
                .featureCode(masterTask.getFeatureCode())
                .targetId(subTask.getLink())
                .fromUrl(subTask.getLink())
                .taskId(masterTask.getId())
                .subTaskId(subTask.getId())
                .userId(masterTask.getUserId())
                .userTierCode(masterTask.getUserTierCode())
                .requiredCapabilities(masterTask.getRequiredCapabilities())
                .excludedCapabilities(masterTask.getExcludedCapabilities())
                .extra(extra)
                .build();
    }

    /**
     * 消费者回写执行结果（{@link TaskResultListener} 实现）。
     *
     * <p>外置队列是**至少一次**投递（崩溃后 XCLAIM 接管、deadline 迁移竞态都会重复投递），
     * 因此本方法对重复调用幂等：终态写入用条件更新（{@code SubTaskDao.markTerminalIfFirst}），
     * 只有"首次终态"才累加主任务计数、触发续采与回调 —— 重复投递不会计数翻倍。
     */
    @Override
    public void onSubTaskOutcome(CommentCollectRequest request, CommentCollectResult result, Throwable error) {
        applySubTaskResult(request.getTaskId(), request.getSubTaskId(), result, error);
    }

    /**
     * 回写子任务结果：成功落终态；失败先过 {@link SubTaskRetryPolicy}，
     * 可重投则置 RETRYING 等待扫描，否则落 FAILED。终态时原子收敛主任务计数。
     */
    private void applySubTaskResult(String masterTaskId, String subTaskId,
                                    CommentCollectResult result, Throwable ex) {
        SubTask subTask = subTaskDao.findById(subTaskId).orElse(null);
        if (subTask == null) {
            log.warn("子任务已不存在，跳过回写: subTaskId={}", subTaskId);
            return;
        }

        if (ex == null && result != null && result.isSuccess()) {
            boolean first = subTaskDao.markTerminalIfFirst(subTaskId, ST_SUCCESS,
                    result.getProviderUsed(), buildResultSummary(result), null);
            if (!first) {
                log.info("子任务已是终态，重复投递幂等跳过: subTaskId={}", subTaskId);
                return;
            }

            // 续采必须在终态计数之前：续采会原子扩张 totalLinks，
            // 若先计数，最后一个原始链接完成时可能提前把主任务判到终态、提前触发回调
            masterTaskDao.findById(masterTaskId).ifPresent(master ->
                    createContinuationIfNeeded(master, subTask, result));

            recordOutcomeAndFinish(masterTaskId, true);
            return;
        }

        String errorMessage = ex != null
                ? "执行异常: " + ex.getMessage()
                : (result == null ? "无返回结果" : result.getErrorMessage());

        if (SubTaskRetryPolicy.shouldRetry(subTask.getRetryCount(), subTask.getMaxRetry(), errorMessage)) {
            scheduleRetry(subTask, errorMessage);
            return;
        }

        if (markSubTaskFailed(subTask, errorMessage)) {
            recordOutcomeAndFinish(masterTaskId, false);
        } else {
            log.info("子任务已是终态，重复投递幂等跳过: subTaskId={}", subTaskId);
        }
    }

    /**
     * 服务端自动翻页：子任务成功且 {@code hasMore=true} 时生成带游标的续采子任务并直接入队。
     *
     * <p>开关优先级：任务级 {@code masterTask.autoPage} &gt; 全局 {@code collector.task.auto-paging.enabled}。
     * 续采子任务与普通子任务同优先级、同重试策略；先 {@code incTotalLinks(+1)} 再提交，
     * 保证"成功+失败 &ge; 总数"的终态判定不被续采破坏。
     */
    private void createContinuationIfNeeded(MasterTask masterTask, SubTask parent, CommentCollectResult result) {
        if (result == null || !result.isHasMore()) {
            return;
        }
        boolean autoPage = masterTask.getAutoPage() != null
                ? masterTask.getAutoPage()
                : systemConfigService.getBool("collector.task.auto-paging.enabled", autoPagingEnabledDefault);
        if (!autoPage) {
            return;
        }
        var mode = com.sysj.collector.core.provider.support.PlatformCursorKeys
                .of(masterTask.getPlatformCode(), masterTask.getFeatureCode()).orElse(null);
        int parentDepth = Math.max(1, parent.getPageCount());
        int parentPage = resolveParentPage(masterTask, parent);

        // 游标类平台：上一页请求所用游标（首次提交为 null），供"未前进"判定
        String parentCursor = parentDepth > 1 ? parent.getPageCursor() : null;
        var next = AutoPagingPolicy.nextCursor(mode, parentDepth, autoPagingMaxPages(),
                result.isHasMore(), result.getNextCursor(), parentCursor, parentPage);
        if (next.isEmpty()) {
            return;
        }

        SubTask continuation = new SubTask();
        continuation.setId(generateSubTaskId());
        continuation.setMasterTaskId(masterTask.getId());
        continuation.setLink(parent.getLink());
        continuation.setStatus(ST_PENDING);
        continuation.setPriority(parent.getPriority());
        continuation.setRetryCount(0);
        continuation.setMaxRetry(parent.getMaxRetry());
        continuation.setPageCount(parentDepth + 1);
        continuation.setPageCursor(next.get());
        continuation.setCreateTime(Instant.now());
        continuation.setUpdateTime(Instant.now());
        subTaskDao.save(continuation);

        masterTaskDao.incTotalLinks(masterTask.getId(), 1);
        log.info("自动翻页续采: parent={} → child={} pageCount={} cursor={}",
                parent.getId(), continuation.getId(), parentDepth + 1, next.get());

        submitSubTask(masterTask, continuation, false);
    }

    /** 上一页的有效页码：续采子任务存页码串；首次提交读主任务 requestParams 的 page（缺省 1）。 */
    private int resolveParentPage(MasterTask masterTask, SubTask parent) {
        if (parent.getPageCount() > 1 && parent.getPageCursor() != null) {
            try {
                return Integer.parseInt(parent.getPageCursor());
            } catch (NumberFormatException ignored) {
                // 页码串理论上不会坏；坏了按 1 起步重新翻
            }
        }
        return parseIntOrDefault(parseExtra(masterTask.getRequestParams()).get("page"), 1);
    }

    private static int parseIntOrDefault(String raw, int defaultValue) {
        try {
            return raw == null ? defaultValue : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 置为 RETRYING 并计算下次执行时间（指数退避）。不计入主任务失败数 ——
     * 只有终态（SUCCESS/FAILED）才计，否则重试路径会重复计数。
     */
    private void scheduleRetry(SubTask subTask, String errorMessage) {
        int nextRetry = subTask.getRetryCount() + 1;
        long delay = SubTaskRetryPolicy.backoffDelayMs(nextRetry, subRetryBaseDelayMs(), subRetryMaxDelayMs());
        subTask.setRetryCount(nextRetry);
        subTask.setStatus(ST_RETRYING);
        subTask.setErrorMessage(errorMessage);
        subTask.setNextExecuteTime(Instant.now().plusMillis(delay));
        subTask.setCompleteTime(null);
        subTask.setUpdateTime(Instant.now());
        subTaskDao.save(subTask);
        log.info("子任务失败待重投: subTaskId={} retry={}/{} delay={}ms reason={}",
                subTask.getId(), nextRetry, subTask.getMaxRetry(), delay, errorMessage);
    }

    /**
     * 条件落 FAILED 终态。
     *
     * @return true = 本次写入生效（首次终态，调用方应累加主任务计数）；
     *         false = 已是终态（重复投递跳过）
     */
    private boolean markSubTaskFailed(SubTask subTask, String errorMessage) {
        return subTaskDao.markTerminalIfFirst(subTask.getId(), ST_FAILED, null, null, errorMessage);
    }

    /**
     * 原子累加主任务计数；若已全部终态则条件迁移到 COMPLETED/FAILED，并在迁移成功时触发回调。
     */
    private void recordOutcomeAndFinish(String masterTaskId, boolean success) {
        masterTaskDao.recordSubTaskOutcome(masterTaskId, success).ifPresent(master -> {
            if (master.getSuccessLinks() + master.getFailedLinks() >= master.getTotalLinks()) {
                masterTaskDao.transitionToTerminalIfFirst(master.getId(),
                                master.getSuccessLinks(), master.getFailedLinks())
                        .ifPresent(finished -> {
                            log.info("主任务终态: taskId={} status={} success={} failed={}",
                                    finished.getId(), finished.getStatus(),
                                    finished.getSuccessLinks(), finished.getFailedLinks());
                            taskCallbackService.notifyIfConfigured(finished);
                        });
            }
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
}
