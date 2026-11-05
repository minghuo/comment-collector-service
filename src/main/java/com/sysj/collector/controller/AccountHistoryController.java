package com.sysj.collector.controller;

import com.sysj.collector.config.ApiKeyAuthenticator;
import com.sysj.collector.core.provider.support.AccountHistoryKeys;
import com.sysj.collector.domain.dao.AccountHistoryDao;
import com.sysj.collector.domain.dao.MasterTaskDao;
import com.sysj.collector.domain.dao.SubTaskDao;
import com.sysj.collector.domain.document.AccountHistoryDoc;
import com.sysj.collector.domain.document.MasterTask;
import com.sysj.collector.domain.document.SubTask;
import com.sysj.collector.domain.service.TaskManagementService;
import com.sysj.collector.facade.AccountHistoryFacade;
import com.sysj.collector.model.AccountHistoryCollectRequest;
import com.sysj.collector.model.AccountHistoryCollectResult;
import com.sysj.collector.model.TaskCreateCommand;
import com.sysj.collector.model.TaskQueryResponse;
import com.sysj.collector.model.TaskSubmitRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号历史接口。
 *
 * <p>与评论采集（{@link CommentController}）共用任务与队列基础设施，但对外自成一组路径，
 * 避免调用方为了取"账号发过什么"而去拼 {@code platform=xx&function=comment} 这种不自洽的组合。
 *
 * <p><b>后续平台如何接入</b>：抖音/小红书/微博/B站只需 ①实现
 * {@link com.sysj.collector.core.provider.AccountHistoryProvider}（Bean 名 = providerKey）；
 * ②在 {@code platform_feature_config} 里挂上 {@code feature_code=account_history} 的供应商。
 * 本控制器、{@link AccountHistoryFacade}、任务与队列链路都不用改。
 *
 * <p><b>异步链路</b>：{@code POST /tasks} 复用 {@code TaskManagementService}（master_task/sub_task、
 * 重试、翻页续采、回调），{@code requestParams} 里放 {@code accountId} 与可选的 {@code maxCount}、
 * {@code maxBehotTime} 等平台参数；消费侧按 {@code featureCode=account_history} 分流。
 */
@Slf4j
@RestController
@RequestMapping("/api/account-history")
@RequiredArgsConstructor
@Tag(name = "账号历史接口", description = "按账号拉取历史作品列表；先支持今日头条，其余平台按同一契约扩展")
public class AccountHistoryController {

    private static final String DEFAULT_PLATFORM = "toutiao";

    private final AccountHistoryFacade accountHistoryFacade;
    private final TaskManagementService taskManagementService;
    private final MasterTaskDao masterTaskDao;
    private final SubTaskDao subTaskDao;
    private final AccountHistoryDao accountHistoryDao;

    /** 凭据注册表：归属校验需要把 API-Key 映射为调用方身份。 */
    private final ApiKeyAuthenticator apiKeyAuthenticator;

    /** 任务归属校验开关，缺省关闭 = 保持内网直连的既有行为。 */
    @Value("${collector.auth.task-isolation-enabled:false}")
    private boolean taskIsolationEnabled;

    // ── 同步采集 ───────────────────────────────────────────────────────────

    /**
     * 同步拉取一页账号历史。
     *
     * <p>定位账号三选一：{@code accountId}、{@code extra.accountId}、{@code fromUrl}
     * （头条从 {@code https://www.toutiao.com/c/user/token/xxx/} 里取 token）。
     */
    @PostMapping("/collect")
    @Operation(summary = "同步采集账号历史", description = "即时返回一页历史作品；翻页用 nextCursor/maxBehotTime 继续")
    public ResponseEntity<AccountHistoryCollectResult> collectSync(
            @RequestBody AccountHistoryCollectRequest request) {
        normalize(request);
        if (StringUtils.isBlank(request.resolveAccountId())) {
            return ResponseEntity.badRequest().body(AccountHistoryCollectResult.builder()
                    .success(false)
                    .errorMessage("accountId 与 fromUrl 至少提供一个")
                    .build());
        }
        log.info("同步账号历史请求: platform={} accountId={} userId={}",
                request.getPlatformCode(), request.resolveAccountId(), request.getUserId());
        return ResponseEntity.ok(accountHistoryFacade.collectSync(request));
    }

    // ── 异步任务 ───────────────────────────────────────────────────────────

    /**
     * 创建账号历史异步任务（每个 link 一个子任务，支持自动翻页与重试）。
     *
     * <p>{@code function} 会被强制为 {@code account_history}；{@code platform} 缺省 {@code toutiao}。
     * 账号维度的参数放在 {@code requestParams}（JSON 字符串）里，例如：
     * <pre>{"accountId":"1234567890","maxCount":"200"}</pre>
     */
    @PostMapping("/tasks")
    @Operation(summary = "创建账号历史异步任务",
            description = "复用主/子任务与队列；requestParams 放 accountId、maxCount 等账号维度参数")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody TaskSubmitRequest request) {
        List<String> links = request.getLinks();
        if (links == null || links.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "links不能为空"));
        }
        String platform = StringUtils.isBlank(request.getPlatform()) ? DEFAULT_PLATFORM : request.getPlatform();
        log.info("创建账号历史任务: userId={} platform={} links={}",
                request.getUserId(), platform, links.size());

        MasterTask masterTask = taskManagementService.createMasterTask(TaskCreateCommand.builder()
                .userId(request.getUserId())
                .userTierCode(request.getUserTierCode())
                .platformCode(platform)
                .featureCode(AccountHistoryKeys.FEATURE_CODE)
                .links(links)
                .mode("ASYNC")
                .requestParams(request.getRequestParams())
                .supplierConstraint(request.getSupplierConstraint())
                .callbackUrl(request.getCallbackUrl())
                .idempotencyKey(request.getIdempotencyKey())
                .autoPage(request.getAutoPage())
                .build());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("taskId", masterTask.getId());
        response.put("platform", masterTask.getPlatformCode());
        response.put("feature", masterTask.getFeatureCode());
        response.put("totalLinks", masterTask.getTotalLinks());
        return ResponseEntity.ok(response);
    }

    // ── 任务查询 ───────────────────────────────────────────────────────────

    /** 查询账号历史任务状态与各链接（账号）子任务结果。 */
    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询账号历史任务状态")
    public ResponseEntity<TaskQueryResponse> queryTask(@PathVariable String taskId,
                                                       HttpServletRequest request) {
        MasterTask masterTask = masterTaskDao.findById(taskId).orElse(null);
        if (masterTask == null || !isAccountHistoryTask(masterTask)) {
            return ResponseEntity.notFound().build();
        }
        if (!ownershipAllowed(masterTask, request)) {
            log.warn("账号历史任务查询被拒绝（归属不匹配或无法识别调用方）: taskId={} ip={}",
                    taskId, request.getRemoteAddr());
            return ResponseEntity.notFound().build();
        }

        List<SubTask> subTasks = subTaskDao.findByMasterTaskId(taskId);
        List<TaskQueryResponse.LinkStatus> linkStatuses = new ArrayList<>(subTasks.size());
        for (SubTask st : subTasks) {
            linkStatuses.add(TaskQueryResponse.LinkStatus.builder()
                    .link(st.getLink())
                    .status(st.getStatus())
                    .supplier(st.getProviderKey())
                    .subTaskId(st.getId())
                    .errorMessage(st.getErrorMessage())
                    .build());
        }

        TaskQueryResponse response = TaskQueryResponse.builder()
                .masterTaskId(masterTask.getId())
                .status(masterTask.getStatus())
                .links(linkStatuses)
                .createTime(masterTask.getCreateTime())
                .updateTime(masterTask.getUpdateTime())
                .totalLinks(masterTask.getTotalLinks())
                .successLinks(masterTask.getSuccessLinks())
                .failedLinks(masterTask.getFailedLinks())
                .build();
        return ResponseEntity.ok(response);
    }

    // ── 结果分页查询 ───────────────────────────────────────────────────────

    /**
     * 分页读取已落库的账号历史条目。
     *
     * @param taskId    主任务 ID
     * @param page      页码，从 1 开始
     * @param size      每页条数（上限 1000）
     * @param accountId 可选，按账号过滤（一个任务挂多个账号链接时用）
     */
    @GetMapping("/tasks/{taskId}/items")
    @Operation(summary = "分页查询账号历史结果", description = "按任务读取已落库的历史作品条目")
    public ResponseEntity<Map<String, Object>> queryItems(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String accountId,
            HttpServletRequest request) {

        if (taskIsolationEnabled) {
            MasterTask masterTask = masterTaskDao.findById(taskId).orElse(null);
            if (masterTask == null || !ownershipAllowed(masterTask, request)) {
                log.warn("账号历史结果查询被拒绝: taskId={} ip={}", taskId, request.getRemoteAddr());
                return ResponseEntity.notFound().build();
            }
        }

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 1000);
        List<AccountHistoryDoc> rows = accountHistoryDao.pageByTaskId(taskId, accountId, safePage, safeSize);
        long total = accountHistoryDao.countByTaskId(taskId, accountId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("taskId", taskId);
        body.put("accountId", accountId);
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("total", total);
        body.put("data", rows);
        return ResponseEntity.ok(body);
    }

    // ── 内部工具 ───────────────────────────────────────────────────────────

    /** 端点语义固定：未显式给 feature 时补 {@code account_history}，平台缺省头条。 */
    private static void normalize(AccountHistoryCollectRequest request) {
        if (StringUtils.isBlank(request.getFeatureCode())) {
            request.setFeatureCode(AccountHistoryKeys.FEATURE_CODE);
        }
        if (StringUtils.isBlank(request.getPlatformCode())) {
            request.setPlatformCode(DEFAULT_PLATFORM);
        }
    }

    private static boolean isAccountHistoryTask(MasterTask task) {
        return AccountHistoryKeys.isAccountHistory(task.getFeatureCode());
    }

    /** 归属校验：开关关闭恒放行；无法识别身份或无归属方一律拒绝（fail closed），统一 404。 */
    private boolean ownershipAllowed(MasterTask task, HttpServletRequest request) {
        if (!taskIsolationEnabled) {
            return true;
        }
        String owner = apiKeyAuthenticator.ownerOf(request.getHeader(apiKeyAuthenticator.headerName()));
        String taskOwner = task.getUserId();
        if (StringUtils.isBlank(owner) || StringUtils.isBlank(taskOwner)) {
            return false;
        }
        return MessageDigest.isEqual(owner.getBytes(StandardCharsets.UTF_8),
                taskOwner.getBytes(StandardCharsets.UTF_8));
    }
}
