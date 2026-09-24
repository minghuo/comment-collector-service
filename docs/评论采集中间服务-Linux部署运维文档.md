# 评论采集中间服务 · Linux 部署运维文档

> 版本：v1.1 ｜ 日期：2026-09-24
> 适用对象：`comment-collector-service`（Spring Boot 单体，单实例部署）
> 接口说明见 `评论采集中间服务-接口使用文档.md`；设计说明见 `评论采集中间服务-总体设计文档.md`

---

## 1. 运行环境

### 1.1 版本要求与实测情况

| 组件 | 要求 | 本机实测 | 说明 |
|---|---|---|---|
| JDK | 17+（推荐 21） | **21.0.2** | `pom.xml` 的 `maven.compiler.plugin` 当前写 `source/target 17`，属性区写 21；生产按 21 部署即可 |
| MongoDB | 本机实测 8.0.6 | **8.0.6** | 驱动 `mongodb-driver-sync 5.2.1`；更低版本未验证 |
| Redis | **非必需** | 本机 6379 在运行 | 当前代码**未实际使用 Redis**（配置热缓存走 Caffeine 本地缓存）；Redis 不可用不影响启动与采集 |
| 内存 | 建议 ≥ 2GB 可用 | — | JVM 默认堆 + 6 万并发队列；小内存机器需按 §6.3 调 `-Xmx` |
| 磁盘 | 建议 ≥ 20GB | — | 采集结果全部落 MongoDB，本地只占日志 |

> **不需要**在本机安装 MongoDB/Redis（可以是远端实例），只需要网络可达。

### 1.2 端口

| 端口 | 用途 | 是否需要对外 |
|---|---|---|
| `8075` | 服务 HTTP 端口 | 对内网/网关开放 |
| `27017` | MongoDB（若同机部署） | 仅本机或内网 |
| `6379` | Redis（若同机部署） | 仅本机或内网 |

### 1.3 依赖的外部服务（出网要求）

服务需要访问以下外网地址，**部署机器必须能出网或配好代理**：

| 地址 | 用途 |
|---|---|
| `dservice.golaxy.cn:18080` | 中科天玑评论接口（B站 / 抖音 / 小红书 / 微博） |
| `auto.sysjdata.com:443` | 系统接口（公众号 / 视频号评论） |
| `weibo.com:443` | 微博网页版评论与转发（需要 Cookie） |
| `www.toutiao.com:443` | 今日头条评论 |
| `h5.video.weibo.com:443` | 微博视频号信息 |

---

## 2. 目录规划

```
/opt/comment-collector-service/
├── app/
│   └── comment-collector-service-0.0.1-SNAPSHOT.jar   # 可执行 jar
├── config/
│   └── application-pro.properties                      # 外置配置（覆盖 jar 内配置）
├── db/
│   └── init-comment-collector.js                       # MongoDB 初始化脚本
├── logs/                                                # 预留：文件日志目录
└── run.sh                                               # 手工启动脚本（可选）
```

```bash
sudo mkdir -p /opt/comment-collector-service/{app,config,db,logs}
sudo chown -R appuser:appuser /opt/comment-collector-service
```

---

## 3. 构建产物

### 3.1 在开发机构建后上传（推荐）

```bash
# 开发机（Windows/Linux 均可）
mvn clean package -DskipTests
# 产物：target/comment-collector-service-0.0.1-SNAPSHOT.jar （约 62 MB，已内嵌 Tomcat 与全部依赖）

scp target/comment-collector-service-0.0.1-SNAPSHOT.jar \
    appuser@<server>:/opt/comment-collector-service/app/
scp -r db appuser@<server>:/opt/comment-collector-service/
```

### 3.2 在服务器上构建

```bash
sudo yum install -y java-21-openjdk-devel maven   # 或 apt install openjdk-21-jdk maven
cd /opt/comment-collector-service
git clone <repo> src && cd src
mvn clean package -DskipTests
cp target/comment-collector-service-0.0.1-SNAPSHOT.jar ../app/
```

> 服务器构建需要能访问 Maven 中央仓库；内网环境请配置 `~/.m2/settings.xml` 的 mirror。

---

## 4. 配置

### 4.1 配置优先级

外部 `config/` 目录 > jar 内 `application.properties` > 代码默认值。

Spring Boot 会自动加载 `./config/` 下的配置文件，因此**生产配置放外部、不改 jar**：

```bash
java -jar app/xxx.jar --spring.config.additional-location=file:/opt/comment-collector-service/config/
```

### 4.2 必须配置的项

`/opt/comment-collector-service/config/application.properties`：

```properties
# ===== 激活 profile =====
spring.profiles.active=pro

# ===== 端口与路径 =====
server.port=8075
server.servlet.context-path=/comment-collector

# ===== MongoDB（必填）=====
# 注意 key 必须是 spring.data.mongodb.uri
spring.data.mongodb.uri=mongodb://<user>:<password>@<host>:27017/comment_task?authSource=admin&authMechanism=SCRAM-SHA-1

# ===== 字段命名与索引 =====
# 字段命名策略：必须保留，否则读写字段名不匹配（落库为下划线式）
spring.data.mongodb.field-naming-strategy=org.springframework.data.mapping.model.SnakeCaseFieldNamingStrategy
# 索引由 db/init-comment-collector.js 建立，不靠启动时隐式创建
spring.data.mongodb.auto-index-creation=false

# ===== 防饥饿调度（可选，有默认值）=====
collector.task.aging-interval-seconds=60
collector.task.aging-priority-boost=10
collector.task.max-wait-minutes=30
collector.task.fair-quota-ratio=0.3
collector.task.fair-quota-threshold=0.7
collector.task.fair-quota-high-priority-bound=500
collector.task.fair-quota-batch-size=20

# ===== 供应商熔断（可选，有默认值）=====
# 健康真相源是 MongoDB 的 supplier_state.circuit_state，由服务自动读写
collector.circuit.enabled=true
collector.circuit.failure-rate-threshold=50
collector.circuit.slow-call-ms=10000
collector.circuit.slow-call-rate-threshold=80
collector.circuit.sliding-window-size=20
collector.circuit.minimum-calls=5
collector.circuit.open-seconds=60
collector.circuit.half-open-calls=3
collector.circuit.persist-interval-seconds=30

# ===== 过载保护（可选，有默认值）=====
# 异步队列容量上限。名额不足时新的 POST /api/tasks 直接返回 503，不做无界排队。
collector.task.queue-capacity=10000
# 消费者线程数；<=0 表示按 CPU 自动（availableProcessors × 2）
collector.task.consumer-threads=0

# ===== 断点续采（可选，有默认值）=====
# 遇到问题版本时可先置 enabled=false，阻止"每次重启都重灌一批任务"放大故障
collector.recovery.enabled=true
collector.recovery.scan-on-startup=true

# ===== 自适应限速 + 流控效果（可选，有默认值）=====
# 有效速率 = min(rate_per_second, 1000 / adaptive_delay_ms)；上游变慢时自动降速，失败只降不升
collector.ratelimit.adaptive-enabled=true
# REJECT / WARM_UP 取令牌的最长等待；超时即跳过该供应商
collector.ratelimit.reject-timeout-ms=500
# THROTTLE_QUEUE 的默认最长排队等待（供应商的 max_queue_wait_ms 可覆盖）
collector.ratelimit.max-queue-wait-ms=2000
# 冷启动/熔断恢复后的预热时长
collector.ratelimit.warm-up-seconds=30
# effective_qps / adaptive_delay_ms 写回 supplier_state 的节流间隔
collector.ratelimit.persist-interval-seconds=30

# ===== 供应商调用管线（可选，有默认值）=====
# 装配顺序：Bulkhead → CircuitBreaker → RateLimit → Retry → Timeout
# 总开关；置 false 时所有阶段直连供应商（压测/排障用）
collector.pipeline.enabled=true
# 单次调用超时（毫秒）；供应商配置 providers[].timeout_ms 优先，0 = 不限时。
# ⚠️ 不要大于 http-client-utils 的 OkHttp callTimeout（默认 60000ms），
#    否则"超时返回"只是调用方放弃等待，底层请求仍在跑。
collector.pipeline.timeout-ms=15000
# 默认并发上限（Bulkhead）；供应商配置 providers[].max_concurrency 优先，0 = 不限。
# 满了立即失败（不排队），由门面切换到下一个候选。
collector.pipeline.default-max-concurrency=4
# 限时工作线程数上限；<=0 = 按 CPU 自动（max(64, availableProcessors × 8)）
collector.pipeline.timeout-threads=0
# 重试退避基数（毫秒）：第 n 次重试等待 base × 2^(n-1)
collector.pipeline.retry-backoff-base-ms=500
# 分阶段开关（压测逐项隔离）
collector.pipeline.bulkhead-enabled=true
collector.pipeline.timeout-enabled=true

# ===== 日志 =====
logging.level.root=info
logging.level.com.sysj.collector=info
```

> ⚠️ **必须保留 `field-naming-strategy`**：服务落库字段是下划线式（`platform_code` / `data_type`），
> 去掉这行会导致读写字段名不一致、查不到数据。

**熔断参数调优提示**：`minimum-calls` 是冷启动防误判的护栏 —— 调用量很小的供应商
（例如一天只采几次）很容易在几次失败后就达到 100% 失败率。这类供应商应把 `minimum-calls` 调大、
或把 `sliding-window-size` 调小以让窗口更快滑出历史失败。
`open-seconds` 决定故障供应商被隔离多久，恢复靠 `HALF_OPEN` 探测自动完成，无需人工干预。

**管线参数调优提示（2026-09-24 新增）**：

| 现象 | 调整 |
|---|---|
| 上游正常但偶发超过 15s | 调大 `collector.pipeline.timeout-ms`（或该供应商的 `providers[].timeout_ms`），**上限别超过 60000** |
| 单供应商被并发压垮（上游 429/超时） | 调小 `providers[].max_concurrency`；它是"在途上限"，比速率限制更直接 |
| 一个供应商失败时整个请求变慢 | 检查 `providers[].max_retry`：本项目 9 个供应商都用 `status` 表达失败，**确定性失败也会重试 `max_retry` 次**；确定性错误的供应商建议配 0 或 1 |
| 大量 `限时线程池已满` | 调大 `collector.pipeline.timeout-threads`（或置 0 让它按 CPU 自动），并检查是否有供应商长时间挂住 |

### 4.3 用环境变量覆盖（推荐用于密码）

```properties
spring.data.mongodb.uri=${MONGODB_URI}
```

systemd 中通过 `Environment=` 或 `EnvironmentFile=` 注入，避免密码进配置文件：

```bash
# /opt/comment-collector-service/config/env  （权限 600，属主 appuser）
MONGODB_URI=mongodb://user:password@host:27017/comment_task?authSource=admin&authMechanism=SCRAM-SHA-1
```

### 4.4 历史遗留配置说明

| 配置项 | 现状 | 处理建议 |
|---|---|---|
| `file.upload.dir.permission` / `file.upload.dir.path` / `interact.apiUrl` | 被 `CommonConfig` 读取，但**本服务并不使用**（无文件上传、无互动指标上报） | 可保留占位或后续清理 |
| `spring.data.redis.*` / `spring.cache.redis.*` | 无效配置：缓存实际用 Caffeine（`AppConfig#cacheManager` 显式定义了 `CacheManager`，Spring Boot 的 Redis 缓存自动配置会退让） | 可保留，不影响运行 |
| `log4j2.xml` | **未生效**：项目用 Logback（`spring-boot-starter-logging`），该文件不会被加载 | 见 §7 日志方案 |

---

## 5. 部署步骤

### 5.1 首次部署

```bash
# 1) 依赖检查
java -version           # 期望 17+
mongosh --version       # 用于初始化脚本（可选，见 5.2）

# 2) 放置产物与配置
sudo -u appuser cp comment-collector-service-0.0.1-SNAPSHOT.jar /opt/comment-collector-service/app/
sudo -u appuser cp -r db/* /opt/comment-collector-service/db/
# 编辑 /opt/comment-collector-service/config/application.properties

# 3) 初始化数据库（幂等，可重复执行）
mongosh "mongodb://<user>:<password>@<host>:27017/comment_task?authSource=admin" \
        --file /opt/comment-collector-service/db/init-comment-collector.js

# 4) 安装 systemd 服务
sudo cp comment-collector.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now comment-collector

# 5) 验证
curl -s http://127.0.0.1:8075/comment-collector/api/health
```

### 5.2 数据库初始化（`db/init-comment-collector.js`）

**幂等**（按 `_id` upsert），可重复执行；**不会清空已有数据**。写入内容：

| 集合 | 条数 | 说明 |
|---|---|---|
| `platform_feature_config` | 8 | `weibo/comment`、`weibo/repost`、`wechat/comment`、`wechat_video/comment`、`bilibili`、`douyin`、`xhs`、`toutiao` |
| `user_tier_config` | 3 | `ENTERPRISE`(1) / `VIP`(10) / `NORMAL`(100) |
| `supplier_state` | 9 | 各供应商运行时状态，初始 `health_status=UP` |
| `master_task` / `sub_task` | 1 / 3 | 演示任务 `MT-DEMO-0000000000000001`（可选，正式环境可注释掉该段） |
| 索引 | 11 | 含 `comment` 集合的 3 个索引 |

> 服务器无 `mongosh` 时，可用任意已装 MongoDB Shell 的机器执行，或把脚本内容贴进 MongoDB Compass 的
> `mongosh` 面板逐段执行（脚本使用了 `await`，Compass 支持）。
>
> **正式环境建议**：先注释掉脚本第 4 段（演示任务），避免生产库里出现 `MT-DEMO-...` 数据。

### 5.3 systemd 服务文件

`/etc/systemd/system/comment-collector.service`：

```ini
[Unit]
Description=Comment Collector Service
Documentation=file:/opt/comment-collector-service/docs/
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=appuser
Group=appuser
WorkingDirectory=/opt/comment-collector-service

EnvironmentFile=-/opt/comment-collector-service/config/env
Environment="JAVA_OPTS=-Xms512m -Xmx1024m -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
Environment="SPRING_CONFIG_ADDITIONAL_LOCATION=file:/opt/comment-collector-service/config/"

ExecStart=/bin/bash -lc 'exec java $JAVA_OPTS -jar /opt/comment-collector-service/app/comment-collector-service-0.0.1-SNAPSHOT.jar'
SuccessExitStatus=143

# 优雅停机：先发 SIGTERM，给在途采集任务 60s 收尾
KillSignal=SIGTERM
TimeoutStopSec=60

Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=comment-collector

# 安全加固
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

> `-Duser.timezone=Asia/Shanghai`：采集时间字段由各平台时间戳格式化而来，
> 统一时区可避免日志与结果时间错乱。

### 5.4 常用运维命令

```bash
sudo systemctl start   comment-collector     # 启动
sudo systemctl stop    comment-collector     # 停止（SIGTERM，等待在途任务）
sudo systemctl restart comment-collector     # 重启
sudo systemctl status  comment-collector     # 状态
sudo journalctl -u comment-collector -f      # 实时日志
sudo journalctl -u comment-collector --since "10 min ago" | grep -E "ERROR|WARN"
```

---

## 6. 运行与调优

### 6.1 启动成功判据

日志中应出现（顺序出现即成功）：

```
Tomcat started on port 8075 (http) with context path '/comment-collector-service'
防饥饿调度参数: agingInterval=60s boost=10 maxWait=30min | 公平配额 ratio=0.3 threshold=0.7 highPriorityBound=500 batchSize=20
断点续采扫描开始 / 断点续采: 无需恢复的任务
Started CommentCollectorServiceApplication in X seconds
```

> 若无 `防饥饿调度参数` 这行，说明 `CommentCollectionFacade` 的 `@PostConstruct` 没执行（容器启动失败），
> 此时接口会 404。

### 6.2 端口与防火墙

```bash
# firewalld（CentOS/RHEL）
sudo firewall-cmd --permanent --add-port=8075/tcp && sudo firewall-cmd --reload
# ufw（Ubuntu）
sudo ufw allow 8075/tcp

# 确认端口监听
ss -lntp | grep 8075
```

**建议**：8075 只对内网/网关开放，公网由 Nginx 反代 + 鉴权（见 §8）。

### 6.3 JVM 调优参考

| 场景 | 建议 |
|---|---|
| 小内存机器（2GB） | `-Xms256m -Xmx512m` |
| 常规（4GB） | `-Xms512m -Xmx1024m` |
| 高并发提交 | 加大 `-Xmx` 的同时注意队列为内存队列，堆积会直接占堆 |

> ⚠️ 采集队列是**进程内内存队列**（`PriorityTaskQueue`），任务堆积会占用堆内存。
> 高并发场景需评估 `最大并发提交数 × 单任务占用`，必要时限制入口流量。

### 6.4 防饥饿参数调优

| 参数 | 默认 | 含义 | 调优提示 |
|---|---|---|---|
| `aging-interval-seconds` | 60 | 等待多久提升一次优先级 | 调小 → 低优任务更快被照顾；同时会削弱优先级区分度 |
| `aging-priority-boost` | 10 | 每次提升的优先级数值 | 等级差是 1 / 10 / 100，boost=10 时 NORMAL 约 10 分钟追平 VIP |
| `max-wait-minutes` | 30 | 超过即提到最高优先级 | 需求要求"低优 30 分钟内获执行"，保持 30 |
| `fair-quota-ratio` | 0.3 | 每轮给低优的名额比例 | 0.3 = 每 20 个名额留 6 个 |
| `fair-quota-threshold` | 0.7 | 高优占比达到多少才启用配额 | — |
| `fair-quota-high-priority-bound` | 500 | 有效优先级小于该值算高优 | 需 ≥ `NORMAL` 等级值（100），否则 NORMAL 会被当成低优 |

---

## 7. 日志

### 7.1 当前方案：控制台 + journald

项目使用 **Logback**（不是 Log4j2），且未配置文件 appender，日志只输出到 `stdout`，
由 systemd 收进 journald：

```bash
sudo journalctl -u comment-collector -f
sudo journalctl -u comment-collector --since today > /tmp/cc.log
```

> 仓库里的 `src/main/resources/log4j2.xml` **不会生效**（那是复制自另一个项目的文件，且当前用的是 Logback）。
> 若误删 `spring-boot-starter-logging` 或引入 log4j2 绑定，会出现"日志绑定冲突导致启动失败"，详见 §10。

### 7.2 需要文件日志时（可选）

新增 `src/main/resources/logback-spring.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <property name="LOG_HOME" value="${LOG_HOME:-/opt/comment-collector-service/logs}"/>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{40} - %msg%n</pattern>
      <charset>UTF-8</charset>
    </encoder>
  </appender>
  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${LOG_HOME}/comment-collector.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>${LOG_HOME}/comment-collector-%d{yyyy-MM-dd}-%i.log.gz</fileNamePattern>
      <maxFileSize>50MB</maxFileSize>
      <maxHistory>15</maxHistory>
      <totalSizeCap>5GB</totalSizeCap>
    </rollingPolicy>
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{40} - %msg%n</pattern>
      <charset>UTF-8</charset>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="FILE"/>
  </root>
</configuration>
```

### 7.3 关键日志关键字

| 关键字 | 含义 |
|---|---|
| `防饥饿调度参数` | 启动时打印，确认调度参数加载成功 |
| `能力校验通过` | 启动时打印，供应商的 `@ProviderCapability` 注解与 DB `capabilities` 完全一致 |
| `能力校验不一致` | **需要处理**：DB 少声明/多声明了能力，或含无法识别的能力名（日志会指明改哪边） |
| `能力校验：… 找不到对应 Bean` | **需要处理**：DB 的 `provider_key` 与 `@Component("...")` 名字不一致（对应 C-24） |
| `自适应限速参数` | 启动时打印，确认自适应限速参数加载成功 |
| `自适应延迟调整` | DEBUG 级；已把延迟从 X 调到 Y（用 `logging.level.com.sysj.collector=debug` 打开） |
| `熔断恢复，重置自适应预热` | 供应商从 OPEN 恢复，速率会从低位爬升（正常，防二次熔断） |
| `限流拒绝` / `供应商限流跳过` | 有效速率低于当前请求密度；`REJECT` 下会转下一个候选，`THROTTLE_QUEUE` 下会排队 |
| `过载保护与线程池` | 启动时打印，确认 `queueCapacity` 与 `consumerThreads` 加载成功 |
| `过载拒绝` | 队列名额不足，新提交被拒（HTTP 503）；持续出现说明容量或消费者线程数需要调整 |
| `子任务被队列拒绝` | 恢复扫描等未预留名额的路径被拒，该子任务已置 FAILED（不是卡住） |
| `断点续采已关闭` / `启动扫描已关闭` | `collector.recovery.*` 开关生效 |
| `主任务创建成功` / `子任务拆分完成` | 任务已受理 |
| `采集结果已落库` | 结果写库成功，含 `saved=x/y` |
| `供应商执行失败，切换` | 某供应商失败、正在切换下一个 |
| `所有供应商均不可用` | 该子任务最终失败，后面会带每个供应商的原因 |
| `Deadline保障触发` | 任务等待超 30 分钟被提权 |
| `限流未获取到令牌` | 供应商令牌桶已满 |
| `熔断器 OPEN` / `熔断器 HALF_OPEN` / `熔断器 CLOSED` | 供应商熔断状态迁移；`OPEN` 带原因（失败率/慢调用比例）与冷却时长 |
| `熔断器参数` | 启动时打印，确认熔断配置加载成功 |
| `半开探测超时未回收，重新发放探测名额` | 探测名额被领走但没回结果，已自动补发（属罕见告警，频繁出现说明候选常被限流跳过） |
| `从 DB 恢复熔断状态` | 重启后恢复了此前的 OPEN 状态（正常，说明状态持久化生效） |
| `insertAll 失败，退化为逐条 save` | 批量写入有 `_id` 冲突，已自动降级 |
| `供应商调用管线已装配` | **启动时打印**，确认阶段顺序与生效的 `timeout` / 默认并发上限（管线是供应商调用的唯一边界） |
| `Bulkhead 额度初始化: key=… maxConcurrency=…` | 该供应商的并发上限（来自 `providers[].max_concurrency`，缺省用全局默认） |
| `初始化限流器: key=… rate=…/s` | 该供应商的静态速率上限 |
| `并发已达上限（在途 N，上限 M），不再排队` | Bulkhead 快速失败（HTTP 503 `PROVIDER_UNAVAILABLE`）。**属预期保护**；持续出现说明该供应商并发配置偏小或上游变慢 |
| `熔断闸门拒绝: key=… state=OPEN` | 执行前闸门拦截，**没有发起这次调用**（与路由的择优过滤不同，这里是权威判定） |
| `未触达供应商，跳过运行时状态上报`（DEBUG） | 保护性拒绝（Bulkhead/限流/熔断闸门）**不计入熔断失败率** —— 这是正确行为（C-44），不要当成丢日志 |
| `单次调用超时（> Nms）` / `调用链总预算耗尽（Nms 内已尝试 M 次）` | 前者是单次尝试超时、后者是整条重试链预算用完；排障时优先看"单次" |
| `限时线程池已满` | 管线限时线程池打满，调用被立即拒绝（不排队）；调大 `collector.pipeline.timeout-threads` |
| `供应商不可用: provider=… type=… message=…` | 指定供应商路径以 503 `PROVIDER_UNAVAILABLE` 返回，`type` 是异常类名（定位用） |

---

## 8. 安全

| 项 | 现状 | 建议 |
|---|---|---|
| 接口鉴权 | ❌ 未实现 | **必须**在 Nginx/网关层加白名单或 Token 校验 |
| MongoDB 凭据 | 明文写在 `application-pro.properties`（仓库内） | 改用 `MONGODB_URI` 环境变量 + `EnvironmentFile`（权限 600）；**该密码已进版本库，建议轮换** |
| 第三方 token | `SyConstants.TOKEN` 硬编码在代码里 | 建议迁移到配置项并轮换 |
| Nginx 反代示例 | — | 见下 |

```nginx
location /comment-collector/ {
    allow 10.0.0.0/8;
    deny all;
    proxy_pass http://127.0.0.1:8075;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_read_timeout 120s;    # 同步采集会等上游，超时需放宽
}
```

---

## 9. 升级与回滚

```bash
# 升级
sudo systemctl stop comment-collector
cp /opt/comment-collector-service/app/comment-collector-service-0.0.1-SNAPSHOT.jar{,.bak}   # 备份旧包
cp new.jar /opt/comment-collector-service/app/comment-collector-service-0.0.1-SNAPSHOT.jar
mongosh "<uri>" --file /opt/comment-collector-service/db/init-comment-collector.js   # 幂等，新增配置项时执行
sudo systemctl start comment-collector
curl -s http://127.0.0.1:8075/comment-collector/api/health

# 回滚
sudo systemctl stop comment-collector
mv /opt/comment-collector-service/app/comment-collector-service-0.0.1-SNAPSHOT.jar.bak \
   /opt/comment-collector-service/app/comment-collector-service-0.0.1-SNAPSHOT.jar
sudo systemctl start comment-collector
```

> 数据库结构变更（如新增索引/配置项）通过初始化脚本幂等补齐；**该脚本不做破坏性操作**，
> 回滚应用版本不需要回滚数据。

---

## 10. 故障排查

| 现象 | 排查 | 处理 |
|---|---|---|
| 启动即退出，日志有 `log4j-slf4j-impl cannot be present with log4j-to-slf4j` | 依赖冲突（Logback 与 Log4j2 绑定共存） | 排除 `log4j-slf4j-impl` / `log4j-core` / `log4j-1.2-api`，或整体切到 Log4j2 |
| 启动报 `Could not resolve placeholder 'mark.uri'` | 用了带多数据源配置的旧包/旧配置文件 | 本项目为**单数据源**，`MongoConfig` 只负责关闭 `_class`；不要配 `mark.uri` 等键 |
| 端口占用 `Address already in use` | `ss -lntp \| grep 8075` | 停掉占用进程或改 `server.port` |
| 接口 404 | 是否漏了 context-path | 完整路径是 `/comment-collector/api/...` |
| 接口 404 且日志无 `防饥饿调度参数` | 容器没起来（Bean 创建失败） | 看启动日志首个 `APPLICATION FAILED TO START` 段 |
| 查任务返回 `total=0` 但任务 `COMPLETED` | 该页确实没有评论，或供应商返回空 | 看 `sub_task.error_message`；公众号非永久链接会返回 `STATUS_URL_ERROR` |
| 采集全部失败 `STATUS_ERROR` | 上游业务码非 200（如中科天玑试用接口返回 `{"code":500,...}`） | 属上游故障/额度问题，联系上游；服务已按失败处理并切换供应商 |
| 微博采集报 `缺少 Cookie` | 未传 `extra.cookie` | 在 `requestParams` 里带 `cookie`；Cookie 会过期，需定期更新 |
| 落库为 0 条 | 任务无 `taskId`（同步接口不落库） | 同步接口只返回内存结果；需要落库请用 `/api/tasks` |
| MongoDB 连不上 | `mongosh "<uri>"` 手工验证；检查 `authSource` 与防火墙 | 修正 URI / 放通 27017 |
| Redis 连不上 | 不影响启动与采集 | 可忽略；如需彻底去掉，移除 `spring-boot-starter-data-redis` 与 `RedisConfig` |
| 内存持续上涨 | 队列堆积 | 见 §6.3；限制入口并发 |
| 低优任务迟迟不执行 | 检查 aging 参数与实际等待时长 | 见 §6.4；日志中 `Deadline保障触发` 表示已提权 |
| 某供应商突然不再被使用 | 查 `supplier_state` 的 `circuit_state`：`OPEN` 表示已被熔断隔离 | 正常自愈：冷却后自动半开探测恢复。若上游已修复想立即恢复，把该文档的 `circuit_state` 改为 `CLOSED` 并清空 `circuit_opened_at`，或重启服务（重启后仍会按 DB 状态恢复） |
| 采集变慢但没报错 | 自适应限速在工作：查 `supplier_state.effective_qps` 与 `adaptive_delay_ms` | 这是**预期行为**（上游慢 → 自动降速）。持续偏低说明上游确实慢，或 `max_delay_ms` 偏低；排障时可用 `collector.ratelimit.adaptive-enabled=false` 临时隔离本机制 |
| 某供应商被 `限流拒绝` 频繁跳过 | 有效速率已降到很低 | 若该供应商其实健康，检查是否单次耗时被内部重试放大（`latency` 是整条重试链的时长）；可调 `target_concurrency` 或 `max_delay_ms` |
| 想"宁可排队也不要跳过" | 该供应商配 `flow_effect=THROTTLE_QUEUE` + `max_queue_wait_ms` | 适用于"慢但稳、且没有备用供应商"的场景；注意排队会占用消费者线程 |
| 请求报 `无候选满足所需能力 [X]` | 调用方带了 `requiredCapabilities`，但没有候选全部具备 | 按日志里列出的"实际候选能力"补 DB `capabilities`，或让调用方去掉/放宽该要求。**不是故障** |
| 启动日志有 `能力校验不一致` | DB 的 `capabilities` 与实现类注解不一致 | 按日志提示改 DB 或改代码；**只是告警不阻断启动**，但会导致路由选到不具备该能力的供应商 |
| 单个任务报 `当前无可用供应商，请稍后重试` | 该功能下**全部**候选都被熔断或运维下线 | 查 `platform_feature_config.providers[].is_healthy` 与各供应商的 `circuit_state` |
| 供应商频繁在 OPEN/CLOSED 间抖动 | 调用量小，`minimum-calls` 太低导致几次失败就触发 | 调大 `collector.circuit.minimum-calls`，或调小 `sliding-window-size` 让窗口更快滑出历史失败 |
| 大量 `POST /api/tasks` 返回 **503** | 过载保护生效：`GET /api/queue/status` 看 `queueRemaining` | 正常行为（宁可拒绝也不 OOM）。持续发生则调大 `collector.task.queue-capacity` / `collector.task.consumer-threads`，或在上游限流 |
| `queue/status` 显示 `queueSize=0` 但仍报 503 | **不是 bug**：消费者取走任务后才开始执行，在途任务同样占名额 | 看 `queueRemaining` 而非 `queueSize` |
| 重启后任务被重复灌入，想先排查 | 问题版本可能导致每重启一次就重灌一批任务 | 先设 `collector.recovery.enabled=false` 重启，排查完再打开 |
| 返回 405 / 415 且带 `REQUEST_ERROR` | HTTP 方法或 `Content-Type` 用错（框架语义已保留，不再吞成 500） | 按提示改正请求 |
| `POST /api/collect` 指定供应商时返回 **503** `PROVIDER_UNAVAILABLE` | 看响应体的 `providerKey` 与 `reason`（`BulkheadFullException` / `ProviderTimeoutException` / `RateLimitedException` / `ProviderInvocationException`） | **可重试**。这是有意设计：候选列表路径失败是 `200+success=false`（中间件已尽力切换），点名供应商失败是 503（重试才有意义） |
| `reason=BulkheadFullException` 频繁出现 | 该供应商在途调用已达 `providers[].max_concurrency`（默认 4） | 调大 `providers[].max_concurrency`，或降低调用方并发；**不要指望它排队** —— 排队只会把"下游慢"变成"队列长" |
| 报 `单次调用超时（> 15000ms）` | 上游响应慢于 `collector.pipeline.timeout-ms` | 调大超时（**上限 60000**，见 §4.2 的 OkHttp `callTimeout` 说明）；注意调大后并发占用时间同步变长 |
| 报 `调用链总预算耗尽` | `timeout × (max_retry+1) + 退避总和` 用完 | 上游持续超时时重试只会更慢；优先排查上游，或把该供应商的 `max_retry` 调小 |
| 一个供应商失败会拖慢整个请求 | 每个候选都会走完整条重试链（默认 `max_retry` 2~3） | 确定性失败的供应商把 `providers[].max_retry` 配 0/1；`collector.pipeline.retry-backoff-base-ms` 可整体缩短退避 |
| 报 `限时线程池已满` | `collector.pipeline.timeout-threads` 打满（0 = 按 CPU 自动，`max(64, CPU×8)`） | 调大该值；同时排查是否有供应商长时间挂住（超时后底层 OkHttp 请求仍会继续跑，直到其 `callTimeout` 60s） |
| 想看某供应商到底被调用了没 | grep `熔断闸门拒绝` / `未触达供应商，跳过运行时状态上报`（DEBUG） | 前者=闸门拦截未调用；后者=保护性拒绝未计入熔断（正常） |

---

## 11. 部署检查清单

- [ ] JDK 17+ 已安装，`java -version` 正常
- [ ] MongoDB 可达，`comment_task` 库已用 `db/init-comment-collector.js` 初始化（8 条平台配置 / 9 条供应商状态）
- [ ] `application.properties` 中 `spring.data.mongodb.uri` 正确，且**保留** `field-naming-strategy`
- [ ] 凭据通过 `EnvironmentFile` 注入，文件权限 600
- [ ] systemd 服务已 `enable`，`Restart=on-failure`
- [ ] 8075 已放通，`/api/health` 返回 `UP`
- [ ] 启动日志出现 `防饥饿调度参数` 与 `Started CommentCollectorServiceApplication`
- [ ] 出网可达 5 个外部地址（§1.3）
- [ ] Nginx/网关层已加鉴权（应用自身无鉴权）
- [ ] 用 §4.1 的微信文章做一次真实采集，`/api/tasks/{id}/comments` 能查到数据
- [ ] 日志采集（journald 或 logback 文件）已接入
