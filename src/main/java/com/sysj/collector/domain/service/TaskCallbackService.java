package com.sysj.collector.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysj.collector.domain.document.MasterTask;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 主任务完成回调：主任务进入终态（COMPLETED / FAILED）后向 {@code master_task.callbackUrl}
 * POST 结果摘要。此前 {@code callbackUrl} 只落库从不消费（死字段）。
 *
 * <h3>投递语义</h3>
 * <ul>
 *   <li>回调是**尽力而为**：失败按次数退避重试后放弃并记日志，不影响采集主流程与任务终态；</li>
 *   <li>body 为结果摘要 JSON（不含评论数据，评论按 {@code GET /api/tasks/{id}/comments} 拉取）；
 *       调用方应把收到回调当作"任务已结束"的**通知**而非**唯一真相**，以任务查询接口为准；</li>
 *   <li>{@code collector.callback.secret} 非空时附加 {@code X-Collector-Signature} =
 *       HMAC-SHA256(secret, body) 的十六进制串，供调用方验签防伪造；</li>
 *   <li>回调在独立守护线程池执行，与采集消费者互不占用。</li>
 * </ul>
 */
@Slf4j
@Component
public class TaskCallbackService {

    @Value("${collector.callback.enabled:true}")
    private boolean enabledDefault;

    @Value("${collector.callback.timeout-ms:5000}")
    private long timeoutMsDefault;

    @Value("${collector.callback.max-attempts:3}")
    private int maxAttemptsDefault;

    /** HMAC 签名密钥；为空则不带签名头。 */
    @Value("${collector.callback.secret:}")
    private String secret;

    private final ObjectMapper objectMapper;
    private final SystemConfigService systemConfigService;
    private final ExecutorService callbackExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "collector-callback");
        t.setDaemon(true);
        return t;
    });

    /** {@code @Value} 注入完成后才能按配置的超时构建（构造阶段字段尚未赋值）。 */
    private HttpClient httpClient;

    public TaskCallbackService(ObjectMapper objectMapper, SystemConfigService systemConfigService) {
        this.objectMapper = objectMapper;
        this.systemConfigService = systemConfigService;
    }

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMsDefault)))
                .build();
        if (enabled()) {
            log.info("任务回调已启用: timeout={}ms maxAttempts={} 签名={}",
                    timeoutMs(), maxAttempts(), secret == null || secret.isBlank() ? "关闭" : "HMAC-SHA256");
        }
    }

    // ── 动态配置读取（system_config 覆盖 properties 默认，60s 缓存；secret 属敏感项留 properties） ──

    private boolean enabled() {
        return SystemConfigService.asBool(load("collector.callback.enabled"), enabledDefault);
    }

    private long timeoutMs() {
        return SystemConfigService.asLong(load("collector.callback.timeout-ms"), timeoutMsDefault);
    }

    private int maxAttempts() {
        return SystemConfigService.asInt(load("collector.callback.max-attempts"), maxAttemptsDefault);
    }

    private String load(String key) {
        return systemConfigService.getString(key, null);
    }

    /** 主任务进入终态后调用；未配置 callbackUrl 或总开关关闭时静默跳过。 */
    public void notifyIfConfigured(MasterTask masterTask) {
        if (!enabled() || masterTask == null
                || masterTask.getCallbackUrl() == null || masterTask.getCallbackUrl().isBlank()) {
            return;
        }
        String url = masterTask.getCallbackUrl();
        callbackExecutor.submit(() -> deliver(masterTask.getId(), url, buildBody(masterTask)));
    }

    /** 构造回调 body：任务 ID、终态、计数与完成时间（不含评论数据）。 */
    private String buildBody(MasterTask masterTask) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", masterTask.getId());
        body.put("platformCode", masterTask.getPlatformCode());
        body.put("featureCode", masterTask.getFeatureCode());
        body.put("status", masterTask.getStatus());
        body.put("totalLinks", masterTask.getTotalLinks());
        body.put("successLinks", masterTask.getSuccessLinks());
        body.put("failedLinks", masterTask.getFailedLinks());
        body.put("completeTime", masterTask.getCompleteTime());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            // 字段都是简单类型，理论上到不了这里；兜底手拼最小 JSON
            log.warn("回调 body 序列化失败，退化为最小 JSON: taskId={}", masterTask.getId(), e);
            return "{\"taskId\":\"" + masterTask.getId() + "\",\"status\":\"" + masterTask.getStatus() + "\"}";
        }
    }

    /** 带 2^n 秒退避的投递循环；每次失败记 warn，最终放弃记 error。 */
    private void deliver(String taskId, String url, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (secret != null && !secret.isBlank()) {
            builder.header("X-Collector-Signature", hmacSha256Hex(secret, body));
        }
        HttpRequest request = builder.build();

        for (int attempt = 1; attempt <= Math.max(1, maxAttempts()); attempt++) {
            try {
                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    log.info("任务回调成功: taskId={} url={} attempt={} status={}",
                            taskId, url, attempt, resp.statusCode());
                    return;
                }
                log.warn("任务回调非 2xx: taskId={} url={} attempt={} status={}",
                        taskId, url, attempt, resp.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("任务回调被中断: taskId={} url={}", taskId, url);
                return;
            } catch (Exception e) {
                log.warn("任务回调失败: taskId={} url={} attempt={} error={}", taskId, url, attempt, e.getMessage());
            }
            if (attempt < maxAttempts()) {
                try {
                    TimeUnit.SECONDS.sleep(1L << (attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("任务回调最终失败（已重试{}次）: taskId={} url={} —— 回调仅为通知，任务终态以查询接口为准",
                maxAttempts(), taskId, url);
    }

    private String hmacSha256Hex(String secretKey, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            log.warn("HMAC 签名失败（跳过签名头）: {}", e.getMessage());
            return "";
        }
    }

    @PreDestroy
    public void shutdown() {
        callbackExecutor.shutdown();
        try {
            if (!callbackExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                callbackExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callbackExecutor.shutdownNow();
        }
    }
}
