# comment-collector-service · 评论采集中间服务

多平台评论采集的**中间服务**：上游业务系统（auto-task-web 等）提交采集任务，本服务负责拆链接、选供应商、按 QPS/并发约束调用、落库并回调，屏蔽各平台接口差异与风控细节。

- 技术栈：Spring Boot 3.4.5 / Java 21 / MongoDB（`comment_task`）/ Redis（db5）
- 监听：`8075`，context-path `/comment-collector`，健康检查 `GET /comment-collector/api/health`
- 权威设计文档：[docs/评论采集中间服务-总体设计文档.md](docs/评论采集中间服务-总体设计文档.md)（当前 v3.6）

---

## 一、业务流程图

从「上游提交任务」到「评论可被上游拉取」的业务视角。异步提交立即返回 `masterTaskId`，采集在后台按队列与优先级推进；上游通过 `GET /api/tasks/{taskId}` 轮询终态、再分页拉 `GET /api/tasks/{taskId}/comments`。

```mermaid
flowchart TD
    A["上游业务系统<br/>auto-task-web / 手工调用"] -->|"POST /api/tasks<br/>platform + function + links + requestParams"| B{"参数与幂等校验"}
    B -->|"不合法"| B1["返回 400<br/>不落库"]
    B -->|"合法 / 命中 idempotencyKey"| C["落库 master_task<br/>status = PENDING"]
    C -->|"入队：high / low 双层队列"| D["优先级队列<br/>按 userTier 与任务类型排序"]
    D --> E["消费者取任务<br/>master_task -> RUNNING"]
    E --> F["按链接拆子任务 sub_task<br/>识别平台与目标 id"]
    F --> G{"链接是否可识别"}
    G -->|"否"| G1["该链接标记 FAILED<br/>记录 error_message"]
    G -->|"是"| H["按 platform + function 路由供应商<br/>候选按 priority 排序"]
    H --> I["调用管线执行采集<br/>限流 / 熔断 / 重试 / 超时 / 并发舱"]
    I --> J{"供应商返回"}
    J -->|"成功，还有下一页"| K["按游标翻页继续采<br/>累计 comments"]
    K --> I
    J -->|"成功且无下一页"| L["评论写入 comment 集合<br/>sub_task -> SUCCESS"]
    J -->|"失败且还有候选"| H
    J -->|"失败且候选用尽"| M["sub_task -> FAILED<br/>按 max_retry 重投"]
    M -->|"重投次数未耗尽"| N["延迟后重新入队"]
    N --> H
    L --> O{"全部子任务终态"}
    G1 --> O
    M -->|"重投已耗尽"| O
    O -->|"否"| E
    O -->|"是"| P["汇总 master_task<br/>COMPLETED / PARTIAL / FAILED"]
    P --> Q["回调上游 callbackUrl<br/>或由上游轮询读取"]
    Q --> R["上游分页拉取评论<br/>GET /api/tasks/{taskId}/comments"]
```

### 关键业务规则

| 规则 | 说明 |
|---|---|
| 终态语义 | `COMPLETED` 全部链接成功；`PARTIAL` 部分链接成功；`FAILED` 全部失败。上游按 `totalLinks / successLinks / failedLinks` 判断 |
| 供应商约束 | 请求可带 `supplierConstraint`（如 `weibo_local`）。**硬匹配**：找不到对应 Bean 直接失败，不做模糊匹配 |
| 限流 | 每供应商独立令牌桶（Redis），`rate_per_second` 来自 `platform_feature_config`；`flow_effect=THROTTLE_QUEUE` 时超额排队等令牌，`REJECT` 时直接失败 |
| 重试 | 子任务级 `max_retry` + 退避；供应商实现只发一次请求，失败以 `status != SUCCESS` 表达 |
| 续采 | 上游按 `total - processedCount` 增量拉取；服务端游标翻页保证同一 `sub_task` 不重复产出 |
| 缓存 | 配置读取走本地缓存（TTL 约 60s），改库后需 `POST /api/admin/cache/evict?name=featureConfig` |

### 一次异步采集的时序

```mermaid
sequenceDiagram
    autonumber
    participant U as 上游业务系统
    participant C as CommentController
    participant T as TaskManagementService
    participant Q as Redis 优先级队列
    participant W as AsyncTaskConsumer
    participant F as CommentCollectionFacade
    participant R as DynamicProviderRouter
    participant P as 调用管线
    participant V as CommentProvider
    participant D as MongoDB

    U->>C: POST /api/tasks
    C->>T: 校验 + 幂等检查
    T->>D: 写 master_task (PENDING)
    T->>Q: 入队
    T-->>U: 200 {masterTaskId}
    Q->>W: 出队
    W->>D: master_task -> RUNNING
    W->>F: collect(links, 约束)
    F->>R: 选供应商
    R-->>F: 候选列表
    F->>P: 调用一次
    P->>V: fetchComments
    V-->>P: 评论 + 游标
    P-->>F: 结果
    F->>D: 写 comment / 更新 sub_task
    F-->>W: 子任务结果
    W->>D: 汇总 master_task 终态
    U->>C: GET /api/tasks/{id}
    C-->>U: 状态 + 统计
    U->>C: GET /api/tasks/{id}/comments
    C-->>U: 分页评论
```

---

## 二、开发设计代码流程图

### 分层与调用链

```mermaid
flowchart TD
    subgraph WEB["接入层 controller"]
        CC["CommentController<br/>/api/tasks · /api/collect<br/>/api/collect/dispatch"]
        AC["AdminCacheController<br/>/api/admin/cache/evict"]
        AH["ApiKeyInterceptor<br/>/api/** 鉴权，health 放行"]
    end

    subgraph APP["应用层 domain/service"]
        TM["TaskManagementService<br/>建任务 / 幂等 / 状态流转"]
        LD["LinkDispatchService<br/>三选一：按链接拆分"]
        AP["AutoPagingPolicy<br/>是否续采判据"]
        SRP["SubTaskRetryPolicy<br/>重投次数与退避"]
        SRS["SubTaskRetryScheduler<br/>延迟重投"]
        TR["TaskRecoveryService<br/>启动时恢复未终态任务"]
        CB["TaskCallbackService<br/>回调上游"]
        SRS2["SupplierRegistrySyncService<br/>注解 -> DB 播种"]
        PCS["ProviderConfigService<br/>配置读取 + 本地缓存"]
        TFR["TierFeatureResolver<br/>用户等级 -> 允许的供应商"]
    end

    subgraph CORE["核心层"]
        FAC["CommentCollectionFacade<br/>选供应商·候选切换·落库·组装"]
        ROUTER["DynamicProviderRouter<br/>platform + function -> 候选"]
        LPR["LinkPlatformRouter<br/>URL -> platform + 目标 id"]
        QUEUE["PriorityTaskQueue / RedisTaskStream<br/>QueueTiers low|high · FairQuotaPolicy"]
        PIPE["ProviderInvocationPipeline<br/>统一调用边界"]
    end

    subgraph PLUGIN["供应商插件层 core/provider"]
        PROV["CommentProvider 实现<br/>20 个 Bean：*_local / *_golaxy / *_official"]
        VAL["ProviderCapabilityValidator<br/>能力声明校验"]
    end

    subgraph DATA["数据层 domain/dao + document"]
        MDAO["MasterTaskDao · SubTaskDao<br/>CommentDao · PlatformFeatureConfigDao<br/>SupplierStateDao · UserTierConfigDao"]
        DOC["document 实体<br/>MasterTask / SubTask / CommentDoc ..."]
        MONGO[("MongoDB comment_task")]
        REDIS[("Redis db5<br/>队列 · 令牌桶 · 熔断状态")]
    end

    AH --> CC
    CC --> TM
    CC --> FAC
    TM --> MDAO
    TM --> QUEUE
    QUEUE --> FAC
    TM --> LD
    LD --> LPR
    LD --> FAC
    FAC --> TFR
    FAC --> ROUTER
    ROUTER --> PCS
    PCS --> MDAO
    FAC --> PIPE
    PIPE --> PROV
    VAL -.-> PROV
    FAC --> MDAO
    FAC --> AP
    SRP --> SRS
    SRS --> QUEUE
    MDAO --> DOC
    MDAO --> MONGO
    QUEUE --> REDIS
    PIPE --> REDIS
    CB --> MONGO
    SRS2 --> MDAO
    TR --> MDAO
```

### 调用管线内部顺序

固定装配顺序，编号即 order；任一层拒绝或抛错都会终止本次调用，由 facade 决定是否切下一个候选。

```mermaid
flowchart LR
    S["facade 发起调用"] --> B["BulkheadStage #100<br/>单供应商并发上限<br/>超限按 flow_effect 排队或拒绝"]
    B --> CB2["CircuitBreakerStage #200<br/>Redis 熔断状态<br/>OPEN 直接跳过"]
    CB2 --> R2["RetryStage #300<br/>max_retry + 退避<br/>仅可重试异常"]
    R2 --> RL["RateLimitStage #400<br/>Redis 令牌桶<br/>rate_per_second"]
    RL --> TO["TimeoutStage #500<br/>timeout_ms"]
    TO --> EX["provider.fetchComments<br/>只发一次请求"]
    EX --> RES["InvocationContext 结果<br/>评论 / 游标 / 错误码"]
    RES --> FAC2["facade 落库与结果组装"]
```

| 阶段 | 拒绝/失败时的行为 |
|---|---|
| Bulkhead | `THROTTLE_QUEUE` 且在 `max_queue_wait_ms` 内等到空位则继续；否则抛 `BulkheadFullException` |
| CircuitBreaker | 熔断 OPEN 抛错，facade 切下一候选 |
| Retry | 不可重试异常（如 4xx 语义）直接抛出，不消耗重试次数 |
| RateLimit | 未拿到令牌抛 `RateLimitedException`；排队超时同样如此 |
| Timeout | 超时抛 `ProviderTimeoutException`，计一次失败 |

### 配置与供应商注册链路

```mermaid
flowchart TD
    ANNO["@ProviderMeta / @ProviderCapability<br/>Bean 声明：platform·feature·name<br/>rate_per_second·max_concurrency·max_retry<br/>flow_effect·max_queue_wait_ms"] --> SYNC["SupplierRegistrySyncService<br/>启动时播种 platform_feature_config"]
    SYNC -->|"仅新增，不覆盖已有条目"| DB2[("platform_feature_config")]
    DB2 --> CACHE["ProviderConfigService 本地缓存<br/>TTL 约 60s"]
    CACHE --> ROUTER2["DynamicProviderRouter<br/>候选排序与健康过滤"]
    ADMIN["POST /api/admin/cache/evict<br/>?name=featureConfig"] -->|"改库后强制刷新"| CACHE
    HEALTH["SupplierStateDao<br/>健康度 / 连续失败计数"] --> ROUTER2
```

> 存量条目的 `rate_per_second`、`flow_effect`、`max_retry` 以数据库为唯一事实来源；注解默认值只影响**新插入**的行。因此改注解后仍需用迁移工具改库并淘汰缓存。

### 关键类索引

| 关注点 | 入口 |
|---|---|
| 接口定义 | `controller/CommentController.java` |
| 任务生命周期 | `domain/service/TaskManagementService.java` |
| 链接拆分 | `domain/service/LinkDispatchService.java`、`core/router/LinkPlatformRouter.java` |
| 候选与切换 | `facade/CommentCollectionFacade.java`、`core/router/DynamicProviderRouter.java` |
| 调用边界 | `core/pipeline/ProviderInvocationPipeline.java` 及同目录 5 个 Stage |
| 限流 | `core/ratelimit/AdaptiveRateLimiter.java`、`FlowEffect.java`、`TokenBucketMath.java` |
| 熔断 | `core/circuit/ProviderCircuitBreaker.java` |
| 队列 | `core/scheduler/PriorityTaskQueue.java`、`facade/QueueTiers.java`、`facade/RedisTaskStream.java` |
| 供应商实现 | `core/provider/**/​*Provider.java`（20 个 Bean） |
| 抖音签名 | `core/provider/douyin/sign/`（`Sm3` / `ABogusSigner` / `DouyinCookie`） |
| 数据模型 | `domain/document/`、`domain/dao/` |
| 运维工具 | `src/test/java/com/sysj/collector/tools/`（迁移/巡检工具，默认跳过） |

---

## 三、本地运行

```bash
# 依赖：JDK 21、MongoDB 27017、Redis 6379
mvn -s .mvn-settings.xml spring-boot:run
# 或直接运行 CommentCollectorServiceApplication

curl http://127.0.0.1:8075/comment-collector/api/health
```

`/api/tasks`、`/api/collect` 等业务接口需要 API Key（`ApiKeyInterceptor`），`/api/health` 放行。

---

## 四、文档导航

| 文档 | 内容 |
|---|---|
| [总体设计文档](docs/评论采集中间服务-总体设计文档.md) | 唯一权威：需求、架构、数据模型、接口、缺陷登记、演进计划（v3.6） |
| [接口使用文档](docs/评论采集中间服务-接口使用文档.md) | 调用方接入说明与字段定义 |
| [Linux 部署运维文档](docs/评论采集中间服务-Linux部署运维文档.md) | 部署、配置、排障 |
| [压测与容量基线方案](docs/评论采集中间服务-压测与容量基线方案.md) | 容量评估与压测方法 |

---

## 附：历史文档归档说明（2026-09-23）

`docs/archive/` 存放**已被取代**的历史文档，仅作追溯用途，**请勿据此开发**。

| 文件 | 原路径 | 被取代原因 |
|---|---|---|
| `docs/archive/2026-09-23/多平台数据采集中间服务-需求文档-v1.1.md` | `docs/requirements/` | 内容已并入总设文档 §3 需求基线 |
| `docs/archive/2026-09-23/评论中间服务-需求文档-v1.1.md` | `docs/requirements/` | 同上 |
| `docs/archive/2026-09-23/2026-07-19-设计文档-v1.1.md` | `docs/superpowers/specs/` | 内容已并入总设文档 §4~§9 |
| `docs/archive/2026-09-23/2026-09-23-缺陷清单与修复计划.md` | `docs/plans/` | 内容已并入总设文档 §16 缺陷登记表 与 §17 演进计划 |

原始 Word 文档（`多平台数据采集中间服务开发需求文档.docx`、`评论中间服务.doc`）保留在仓库根目录未修改；后者实为纯 UTF-8 文本。
