package com.sysj.collector.facade;

import com.sysj.collector.domain.service.SystemConfigService;
import com.sysj.collector.model.QueuedTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis Stream 外置队列网关（阶段2）—— 队列相关 Redis 操作的**唯一入口**。
 *
 * <h3>拓扑</h3>
 * <ul>
 *   <li>两条流：HIGH / LOW（用户等级优先级两级，见 {@link QueueTiers}）；
 *       流内 FIFO，跨流优先级由消费者按公平配额选择读哪条；</li>
 *   <li>一个消费者组：所有实例共用，组内竞争消费（多实例天然分摊工作）；</li>
 *   <li>确认语义：处理完成 {@code XACK + XDEL}（确认并清除条目）——
 *       未确认的消息留在 PEL，进程崩溃后由维护任务 XCLAIM 接管重投（**至少一次**投递）。</li>
 * </ul>
 *
 * <h3>容量与背压</h3>
 * XADD 前在 **Lua 脚本内**原子检查 {@code XLEN(HIGH)+XLEN(LOW) < capacity}（单条命令无法
 * "查+加"原子完成）——队列总长严格不超过容量，不会超卖；入口整批预检
 * （{@link #hasCapacityFor}）只是提前 fail-fast 的快照检查，最终以 XADD 脚本为准。
 * XLEN 含已投递未确认的在途消息，容量语义与原内存队列一致；确认即 XDEL，
 * 流里只留未完成工作，XLEN 就是真实积压量。
 */
@Slf4j
@Component
public class RedisTaskStream {

    public static final String FIELD_PAYLOAD = "payload";

    @Value("${collector.queue.stream-high:collector:tasks:high}")
    private String streamHigh;

    @Value("${collector.queue.stream-low:collector:tasks:low}")
    private String streamLow;

    @Value("${collector.queue.group:collector-consumers}")
    private String group;

    /** 队列容量（与 collector.task.queue-capacity 同义，落在 Lua 容量检查里）。 */
    @Value("${collector.task.queue-capacity:10000}")
    private int capacityDefault;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SystemConfigService systemConfigService;
    private final RedisHealthMonitor healthMonitor;

    /** 消费组是否已就绪（Redis 故障启动/重启后由探活回调重建）。 */
    private volatile boolean groupsReady = false;

    /**
     * XADD 前原子容量检查：KEYS=[目标流,另一条流]，ARGV=[payload, capacity]。
     * 容量未满 → XADD 并返回消息 ID；已满 → 返回空串（调用方转 QueueFullException）。
     */
    private static final DefaultRedisScript<String> ENQUEUE_SCRIPT = new DefaultRedisScript<>("""
            local len = redis.call('XLEN', KEYS[1]) + redis.call('XLEN', KEYS[2])
            if len + 1 > tonumber(ARGV[2]) then
              return ''
            end
            return redis.call('XADD', KEYS[1], '*', 'payload', ARGV[1])
            """, String.class);

    /** 整批预检快照：KEYS=[HIGH, LOW]，ARGV=[needed, capacity]。 */
    private static final DefaultRedisScript<Long> CAPACITY_SCRIPT = new DefaultRedisScript<>("""
            local len = redis.call('XLEN', KEYS[1]) + redis.call('XLEN', KEYS[2])
            return len + tonumber(ARGV[1]) <= tonumber(ARGV[2]) and 1 or 0
            """, Long.class);

    public RedisTaskStream(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                           SystemConfigService systemConfigService, RedisHealthMonitor healthMonitor) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.systemConfigService = systemConfigService;
        this.healthMonitor = healthMonitor;
    }

    /** 当前生效容量（system_config 覆盖 properties 默认；XADD 脚本按本次值校验，调小立即生效）。 */
    public int capacity() {
        return systemConfigService.getInt("collector.task.queue-capacity", capacityDefault);
    }

    @PostConstruct
    public void init() {
        // 启动时消费组不可用**不再阻断应用启动**（阶段2.5 降级兜底）：
        // 标记降级后服务照常运行（任务落本地兜底队列），探活恢复后由 ensureGroups 重建消费组
        ensureGroups();
    }

    /**
     * 幂等创建两条流的消费者组（MKSTREAM 顺带建流）；全部成功返回 true。
     * Redis 不可用时记入健康监测进入降级模式，由探活任务周期性重试。
     */
    public boolean ensureGroups() {
        if (groupsReady) {
            return true;
        }
        boolean allOk = true;
        for (String key : List.of(streamHigh, streamLow)) {
            // 1. 先查组是否存在（XINFO GROUPS 命中 = 流与组都在，完全不发 XGROUP CREATE，
            //    避免对已存在组的重复创建报 BUSYGROUP）
            try {
                if (groupExists(key)) {
                    log.debug("Redis Stream 消费者组已存在: stream={} group={}", key, group);
                    continue;
                }
            } catch (Exception e) {
                // 流不存在（XINFO 报 no such key）等：落到下面的 createGroup（MKSTREAM 建流）
                log.debug("消费组存在性查询未命中，尝试直接创建: stream={} ({})", key, rootMessage(e));
            }

            // 2. 创建组；组已存在（并发竞争/上一次创建成功但响应丢失）按成功处理
            try {
                ensureStreamKeyExists(key);
                redisTemplate.opsForStream().createGroup(key, ReadOffset.from("0"), group);
                log.info("Redis Stream 消费者组已创建: stream={} group={}", key, group);
            } catch (Exception e) {
                if (isBusyGroup(e)) {
                    // BUSYGROUP 的细节在异常链里（Spring 6 起 message 不再拼接 cause），
                    // 必须沿 cause 链找，只看顶层 message 会把"组已存在"误判成失败
                    log.info("Redis Stream 消费者组已存在（并发创建竞争）: stream={} group={}", key, group);
                    continue;
                }
                allOk = false;
                log.error("Redis Stream 消费者组创建失败: stream={}", key, e);
                healthMonitor.markFailure("ensureGroups(" + key + ")", e);
            }
        }
        groupsReady = allOk;
        if (allOk) {
            log.info("Redis Stream 外置队列就绪: high={} low={} group={} capacity={}",
                    streamHigh, streamLow, group, capacity());
        } else {
            log.error("Redis Stream 消费者组尚未就绪，服务以降级模式运行（任务落本地兜底队列），恢复后自动重建");
        }
        return allOk;
    }

    /** 该流上是否已存在同名消费组（XINFO GROUPS；流不存在时抛异常由调用方处理）。 */
    private boolean groupExists(String key) {
        for (org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup g
                : redisTemplate.opsForStream().groups(key)) {
            if (group.equals(g.groupName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 确保流 key 存在：XGROUP CREATE 的 MKSTREAM 行为依赖客户端版本，
     * 这里显式兜底——流不存在时先 XADD 一条引导消息建流再 XDEL 删掉
     * （空流的组语义正常，消费者不会看到引导消息）。
     */
    private void ensureStreamKeyExists(String key) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return;
        }
        RecordId bootstrap = redisTemplate.opsForStream().add(key, Map.of(FIELD_PAYLOAD, "{}"));
        redisTemplate.opsForStream().delete(key, bootstrap);
        log.info("Redis Stream 已创建: stream={}（引导消息已清理）", key);
    }

    /** 沿异常 cause 链查找 BUSYGROUP（Spring 6 起 getMessage() 不再拼接 cause 内容）。
     *  用身份集合防环：异常链可能被人为构造/被框架包装成环，裸 while 会死循环。 */
    static boolean isBusyGroup(Throwable e) {
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (e != null && visited.add(e)) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    /** 异常链最底层的可读消息（诊断日志用；身份防环）。 */
    private static String rootMessage(Throwable e) {
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable cur = e;
        while (cur != null && visited.add(cur)) {
            if (cur.getCause() == null || visited.contains(cur.getCause())) {
                break;
            }
            cur = cur.getCause();
        }
        return cur == null ? null : cur.getMessage();
    }

    /** 消费组是否已就绪。 */
    public boolean isGroupsReady() {
        return groupsReady;
    }

    /**
     * 周期性重试消费组创建：Redis 故障期间启动/重启的实例，探活恢复后在此补建消费组
     * （与本进程内的探活任务同周期，幂等）。
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${collector.queue.redis-probe-ms:10000}")
    public void reEnsureGroups() {
        if (!groupsReady && healthMonitor.isAvailable()) {
            ensureGroups();
        }
    }

    // ── 生产 ───────────────────────────────────────────────────────────────

    /**
     * 入队一条任务（Lua 内原子容量检查，绝不超卖）。
     *
     * @return 消息 ID
     * @throws com.sysj.collector.exception.QueueFullException 容量已满
     */
    public String enqueue(QueuedTask task) {
        String target = keyOf(task.getTier());
        String other = QueueTiers.HIGH.equals(task.getTier()) ? streamLow : streamHigh;
        String payload;
        try {
            payload = objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException("队列载荷序列化失败: subTaskId=" + task.getSubTaskId(), e);
        }
        String id = redisTemplate.execute(ENQUEUE_SCRIPT, List.of(target, other), payload,
                String.valueOf(capacity()));
        if (id == null || id.isEmpty()) {
            long size = totalLength();
            throw new com.sysj.collector.exception.QueueFullException((int) size, capacity(),
                    (int) Math.max(0, capacity() - size));
        }
        return id;
    }

    /** 入口整批预检（快照语义，最终以 enqueue 的原子检查为准）。 */
    public boolean hasCapacityFor(int needed) {
        Long ok = redisTemplate.execute(CAPACITY_SCRIPT, List.of(streamHigh, streamLow),
                String.valueOf(needed), String.valueOf(capacity()));
        return ok != null && ok == 1;
    }

    // ── 消费 ───────────────────────────────────────────────────────────────

    /** 读新消息（{@code >}），可阻塞。 */
    public List<MapRecord<String, Object, Object>> readNew(String tier, String consumerName,
                                                           long blockMs, int count) {
        StreamReadOptions options = StreamReadOptions.empty().count(count)
                .block(Duration.ofMillis(Math.max(0, blockMs)));
        List<MapRecord<String, Object, Object>> records = streamOps().read(
                Consumer.from(group, consumerName), options,
                StreamOffset.create(keyOf(tier), ReadOffset.lastConsumed()));
        return records == null ? List.of() : records;
    }

    /** 读本消费者名下已投递未确认的消息（{@code 0}），非阻塞——重启接管与处理失败重投的入口。 */
    public List<MapRecord<String, Object, Object>> readPending(String tier, String consumerName) {
        List<MapRecord<String, Object, Object>> records = streamOps().read(
                Consumer.from(group, consumerName), StreamReadOptions.empty().count(20),
                StreamOffset.create(keyOf(tier), ReadOffset.from("0")));
        return records == null ? List.of() : records;
    }

    /** 确认并删除条目：XACK + XDEL。删除让流里只留未完成工作，XLEN 即真实积压量。 */
    public void ackAndDelete(String tier, RecordId id) {
        streamOps().acknowledge(keyOf(tier), group, id);
        streamOps().delete(keyOf(tier), id);
    }

    // ── 维护（XCLAIM 接管 + deadline 迁移扫描） ─────────────────────────────

    /** 流内按 ID 正序读最旧的 N 条（XRANGE + COUNT，deadline 保障扫描用，不全量加载）。 */
    public List<MapRecord<String, Object, Object>> readOldest(String tier, int count) {
        List<MapRecord<String, Object, Object>> records = streamOps()
                .range(keyOf(tier), Range.unbounded(), Limit.limit().count(Math.max(1, count)));
        return records == null ? List.of() : records;
    }

    /** 删除条目（deadline 迁移：HIGH 侧重新入队后删 LOW 侧原条目）。 */
    public void delete(String tier, RecordId id) {
        streamOps().delete(keyOf(tier), id);
    }

    /** 组内 pending 概览（含各条目空闲时长），XCLAIM 接管的候选来源。 */
    public List<PendingMessage> pending(String tier, int count) {
        PendingMessages messages = streamOps().pending(keyOf(tier), group, Range.unbounded(), count);
        List<PendingMessage> result = new ArrayList<>();
        messages.forEach(result::add);
        return result;
    }

    /** 把指定条目接管到 {@code newConsumer} 名下（XCLAIM，空闲超过 minIdleTime 才会被扫出来）。 */
    public void claim(String tier, String newConsumer, long minIdleMs, List<RecordId> ids) {
        if (ids.isEmpty()) {
            return;
        }
        streamOps().claim(keyOf(tier), group, newConsumer, Duration.ofMillis(minIdleMs),
                ids.toArray(RecordId[]::new));
    }

    // ── 状态 ───────────────────────────────────────────────────────────────

    /** 未完成任务总数（含在途未确认；确认即 XDEL，所以这就是真实积压量）。 */
    public long totalLength() {
        return lengthOf(QueueTiers.HIGH) + lengthOf(QueueTiers.LOW);
    }

    /** 单条流的未确认消息数（XLEN）。 */
    public long lengthOf(String tier) {
        Long size = redisTemplate.opsForStream().size(keyOf(tier));
        return size == null ? 0 : size;
    }


    public String keyOf(String tier) {
        return QueueTiers.HIGH.equals(tier) ? streamHigh : streamLow;
    }

    private StreamOperations<String, Object, Object> streamOps() {
        return redisTemplate.opsForStream();
    }

    /**
     * 解码载荷；解析失败视为毒消息，抛出由调用方记录并丢弃。
     */
    public QueuedTask decode(MapRecord<String, Object, Object> record) {
        Object payload = record.getValue().get(FIELD_PAYLOAD);
        if (payload == null) {
            throw new IllegalStateException("消息缺少 payload 字段: id=" + record.getId());
        }
        try {
            return objectMapper.readValue(payload.toString(), QueuedTask.class);
        } catch (Exception e) {
            throw new IllegalStateException("消息载荷解析失败: id=" + record.getId(), e);
        }
    }
}
