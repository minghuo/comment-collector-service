package com.sysj.collector.tools;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次性数据库迁移（纯 Java，无需 Python / mongosh）：把运行中的 MongoDB 对齐到
 * {@code db/init-comment-collector.js} 的 v3.3 形态。
 *
 * <p>做四件幂等的事：
 * <ol>
 *   <li>{@code platform_feature_config}（weibo/comment）里本地供应商
 *       {@code provider_key: local_crawler} 改名为 {@code weibo_local}（Bean 名同步改了）；</li>
 *   <li>{@code platform_feature_config}（douyin/comment）补上 {@code douyin_local} 及其能力声明；</li>
 *   <li>{@code user_tier_config.feature_configs[].provider_order} 里的 {@code local_crawler}
 *       换成 {@code weibo_local}（否则等级配置会指向不存在的供应商）；</li>
 *   <li>{@code supplier_state} 的同一行改名，避免熔断/限速状态被孤立。</li>
 * </ol>
 *
 * <p>默认不执行（否则每次 {@code mvn test} 都会写库）。显式运行：
 * <pre>{@code
 * mvn -o test -Dtest=DouyinLocalMigrationTool -Dcollector.db.migrate=true -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 * 可用 {@code -Dcollector.db.uri=...} 指向别的实例，默认取 {@code application-dev.properties} 里的开发库。
 *
 * <p>迁移只改配置，不触碰 {@code comment}/{@code master_task}/{@code sub_task} 业务数据。
 */
class DouyinLocalMigrationTool {

    private static final String DEFAULT_URI =
            "mongodb://127.0.0.1:27017/comment_task?Mechanism=SCRAM-SHA-1";

    private static final List<String> DOUYIN_LOCAL_CAPABILITIES = List.of(
            "COMMENT", "SUB_COMMENT", "PAGE_PAGING", "LOGIN_STATE", "PROXY", "SYNC_SUPPORTED");

    @Test
    @DisplayName("把运行库迁移到 v3.3：weibo_local 改名 + douyin_local 新增（幂等）")
    @EnabledIfSystemProperty(named = "collector.db.migrate", matches = "true")
    void migrate() {
        String uri = System.getProperty("collector.db.uri", DEFAULT_URI);
        try (MongoClient client = MongoClients.create(uri)) {
            MongoDatabase db = client.getDatabase(database(uri));
            MongoCollection<Document> features = db.getCollection("platform_feature_config");
            MongoCollection<Document> tiers = db.getCollection("user_tier_config");
            MongoCollection<Document> states = db.getCollection("supplier_state");

            long renamed = features.updateOne(
                    Filters.and(
                            Filters.eq("platform_code", "weibo"),
                            Filters.eq("feature_code", "comment"),
                            Filters.eq("providers.provider_key", "local_crawler")),
                    Updates.set("providers.$.provider_key", "weibo_local")).getModifiedCount();

            long inserted = features.updateOne(
                    Filters.and(
                            Filters.eq("platform_code", "douyin"),
                            Filters.eq("feature_code", "comment"),
                            Filters.ne("providers.provider_key", "douyin_local")),
                    Updates.push("providers", douyinLocalProvider())).getModifiedCount();

            int tiersUpdated = 0;
            for (Document tier : tiers.find(Filters.eq("feature_configs.provider_order", "local_crawler"))) {
                List<Document> featureConfigs = new ArrayList<>();
                for (Document fc : tier.getList("feature_configs", Document.class)) {
                    List<String> order = fc.getList("provider_order", String.class);
                    if (order != null && order.contains("local_crawler")) {
                        List<String> replaced = new ArrayList<>(order.size());
                        for (String key : order) {
                            replaced.add("local_crawler".equals(key) ? "weibo_local" : key);
                        }
                        fc.put("provider_order", replaced);
                    }
                    featureConfigs.add(fc);
                }
                tiers.updateOne(Filters.eq("_id", tier.get("_id")),
                        Updates.set("feature_configs", featureConfigs));
                tiersUpdated++;
            }

            long stateRenamed = 0;
            if (states.countDocuments(Filters.eq("provider_key", "weibo_local")) == 0) {
                stateRenamed = states.updateOne(
                        Filters.eq("provider_key", "local_crawler"),
                        Updates.set("provider_key", "weibo_local")).getModifiedCount();
            }

            System.out.printf("[db-migrate] weibo provider_key renamed=%d, douyin_local inserted=%d, "
                            + "tier docs updated=%d, supplier_state renamed=%d%n",
                    renamed, inserted, tiersUpdated, stateRenamed);

            // 迁移后自检：再跑一次应为 0/0/0/0，且配置形态符合 v3.3
            Document weibo = features.find(Filters.and(
                    Filters.eq("platform_code", "weibo"), Filters.eq("feature_code", "comment"))).first();
            Document douyin = features.find(Filters.and(
                    Filters.eq("platform_code", "douyin"), Filters.eq("feature_code", "comment"))).first();
            assertNotNull(weibo);
            assertNotNull(douyin);

            List<String> weiboKeys = providerKeys(weibo);
            List<String> douyinKeys = providerKeys(douyin);
            assertTrue(weiboKeys.contains("weibo_local"), "weibo 应有 weibo_local: " + weiboKeys);
            assertFalse(weiboKeys.contains("local_crawler"), "weibo 不应再有 local_crawler: " + weiboKeys);
            assertTrue(douyinKeys.contains("douyin_local"), "douyin 应有 douyin_local: " + douyinKeys);

            Document douyinLocal = douyin.getList("providers", Document.class).stream()
                    .filter(p -> "douyin_local".equals(p.getString("provider_key")))
                    .findFirst().orElseThrow();
            assertEquals(DOUYIN_LOCAL_CAPABILITIES, douyinLocal.getList("capabilities", String.class));
            assertEquals(20, douyinLocal.getInteger("priority"));
            assertEquals(3, douyinLocal.getInteger("max_retry"));
        }
    }

    private static List<String> providerKeys(Document featureConfig) {
        List<String> keys = new ArrayList<>();
        for (Document provider : featureConfig.getList("providers", Document.class)) {
            keys.add(provider.getString("provider_key"));
        }
        return keys;
    }

    private static Document douyinLocalProvider() {
        return new Document("provider_key", "douyin_local")
                .append("name", "抖音-本地爬虫")
                .append("rate_per_second", 0.5)
                .append("max_retry", 3)
                .append("priority", 20)
                .append("is_healthy", true)
                .append("max_concurrency", 2)
                .append("timeout_ms", 20000)
                .append("flow_effect", "THROTTLE_QUEUE")
                .append("max_queue_wait_ms", 30000)
                .append("capabilities", DOUYIN_LOCAL_CAPABILITIES);
    }

    private static String database(String uri) {
        int slash = uri.lastIndexOf('/');
        int query = uri.indexOf('?', slash);
        String tail = query < 0 ? uri.substring(slash + 1) : uri.substring(slash + 1, query);
        return tail.isBlank() ? "comment_task" : tail;
    }
}
