package com.sysj.collector.tools;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次性运行库修复（纯 Java，无需 mongosh）：给**单供应商**平台补上排队语义。
 *
 * <p>背景：{@code flow_effect} 缺省时按 {@code REJECT} 处理（见 {@code FlowEffect.of}），
 * 供应商 {@code max_concurrency} / {@code rate_per_second} 被打满时调用直接失败，
 * 子任务重试耗尽后永久 {@code FAILED}——单供应商平台没有候选兜底，等于丢数据。
 * 本地爬虫家族（weibo_local / bilibili_local / douyin_local / 贴吧 / 华为 / 荣耀 / OPPO / vivo / 小米）
 * 都声明了 {@code THROTTLE_QUEUE}，{@code toutiao_local} 漏了。
 *
 * <p>注意：{@code SupplierRegistrySyncService} 的 upsert 只在“新增条目/能力变化”时写库，
 * 不会用注解覆盖已有条目的 rate/flow，所以存量环境必须显式改库（本工具）并同步补注解。
 *
 * <p>默认不执行（否则每次 {@code mvn test} 都会写库）。显式运行：
 * <pre>{@code
 * mvn -o test -Dtest=FlowEffectFixTool -Dcollector.db.flowfix=true \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 * 目标格式 {@code platform:feature:providerKey}，逗号分隔，默认 {@code toutiao:comment:toutiao_local}；
 * 可用 {@code -Dcollector.db.flowfix.targets=...} 覆盖，{@code -Dcollector.db.flowfix.wait=30000} 调整排队上限，
 * {@code -Dcollector.db.uri=...} 指向别的实例。
 */
class FlowEffectFixTool {

    private static final String DEFAULT_URI =
            "mongodb://127.0.0.1:27017/comment_task?Mechanism=SCRAM-SHA-1";

    private static final String DEFAULT_TARGETS = "toutiao:comment:toutiao_local";

    private static final String TARGET_FLOW_EFFECT = "THROTTLE_QUEUE";

    @Test
    @DisplayName("给指定平台的供应商补齐 flow_effect=THROTTLE_QUEUE / max_queue_wait_ms（幂等）")
    @EnabledIfSystemProperty(named = "collector.db.flowfix", matches = "true")
    void fixFlowEffect() {
        String uri = System.getProperty("collector.db.uri", DEFAULT_URI);
        String rawTargets = System.getProperty("collector.db.flowfix.targets", DEFAULT_TARGETS);
        long waitMs = Long.parseLong(System.getProperty("collector.db.flowfix.wait", "30000"));

        List<String[]> targets = parseTargets(rawTargets);
        List<String> report = new ArrayList<>();

        try (MongoClient client = MongoClients.create(uri)) {
            MongoDatabase db = client.getDatabase(dbName(uri));
            MongoCollection<Document> collection = db.getCollection("platform_feature_config");

            for (String[] target : targets) {
                String featureId = target[0] + ":" + target[1];
                String providerKey = target[2];
                report.add(applyOne(collection, featureId, providerKey, waitMs));
            }
        }

        System.out.println("=== flow_effect 修复结果 ===");
        report.forEach(line -> System.out.println("  " + line));
        assertTrue(report.stream().anyMatch(line -> line.contains("RESULT=UPDATED") || line.contains("RESULT=NOOP")),
                "至少应处理一个目标");
    }

    private static String applyOne(MongoCollection<Document> collection, String featureId,
                                   String providerKey, long waitMs) {
        Document before = collection.find(Filters.eq("_id", featureId)).first();
        assertNotNull(before, "配置不存在: " + featureId);

        Document provider = before.getList("providers", Document.class).stream()
                .filter(p -> providerKey.equals(p.getString("provider_key")))
                .findFirst()
                .orElse(null);
        assertNotNull(provider, "供应商不存在: " + featureId + " / " + providerKey);

        String beforeFlow = provider.getString("flow_effect");
        Long beforeWait = provider.getLong("max_queue_wait_ms");
        String beforeLine = String.format("%s/%s BEFORE flow=%s wait=%s", featureId, providerKey,
                beforeFlow, beforeWait);

        boolean alreadyFixed = TARGET_FLOW_EFFECT.equalsIgnoreCase(beforeFlow)
                && beforeWait != null && beforeWait == waitMs;
        if (alreadyFixed) {
            return beforeLine + " → RESULT=NOOP";
        }

        UpdateResult result = collection.updateOne(
                Filters.and(Filters.eq("_id", featureId),
                        Filters.eq("providers.provider_key", providerKey)),
                Updates.combine(
                        Updates.set("providers.$.flow_effect", TARGET_FLOW_EFFECT),
                        Updates.set("providers.$.max_queue_wait_ms", waitMs)));
        assertEquals(1L, result.getModifiedCount(), "更新行数异常: " + featureId + "/" + providerKey);

        Document after = collection.find(Filters.eq("_id", featureId)).first();
        Document afterProvider = after.getList("providers", Document.class).stream()
                .filter(p -> providerKey.equals(p.getString("provider_key")))
                .findFirst()
                .orElseThrow();
        String afterFlow = afterProvider.getString("flow_effect");
        Long afterWait = afterProvider.getLong("max_queue_wait_ms");
        assertEquals(TARGET_FLOW_EFFECT, afterFlow, "回读 flow_effect 不一致");
        assertEquals(waitMs, afterWait, "回读 max_queue_wait_ms 不一致");

        return beforeLine + String.format(" → AFTER flow=%s wait=%s → RESULT=UPDATED", afterFlow, afterWait);
    }

    private static List<String[]> parseTargets(String raw) {
        List<String[]> targets = new ArrayList<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":", 3);
            assertEquals(3, parts.length, "目标格式应为 platform:feature:providerKey，实际: " + trimmed);
            targets.add(parts);
        }
        assertTrue(!targets.isEmpty(), "未解析到修复目标: " + raw);
        return targets;
    }

    /** 从 URI 取数据库名（驱动不像 mongosh 那样按连接串自动切库）。 */
    private static String dbName(String uri) {
        int at = uri.indexOf("://");
        int start = at < 0 ? 0 : at + 3;
        int slash = uri.indexOf('/', start);
        if (slash < 0) {
            return "comment_task";
        }
        int question = uri.indexOf('?', slash);
        return question < 0 ? uri.substring(slash + 1) : uri.substring(slash + 1, question);
    }
}
