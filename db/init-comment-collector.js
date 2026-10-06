/**
 * ============================================================================
 * 评论采集中间服务 —— MongoDB 初始化数据脚本
 * ============================================================================
 *
 * 数据库：comment_task（见 application-dev.properties 的 spring.data.mongodb.uri）
 *
 * 用法（二选一）：
 *   1) mongosh（推荐）：
 *        mongosh "mongodb://127.0.0.1:27017/comment_task" --file db/init-comment-collector.js
 *   2) 本机无 mongosh 时，用仓库自带的 Node 运行器（等价执行本文件）：
 *        node .tools/run-mongosh-script.js db/init-comment-collector.js
 *
 * 为什么每处 DB 调用都写了 await：
 *   mongosh 内部是同步语义（自动等待），而 Node 驱动返回 Promise。
 *   显式 await 让本文件在两种环境下行为一致（mongosh 支持顶层 await）。
 *
 * 特性：
 *   · 幂等：按 _id 做 upsert，可重复执行，不产生重复数据，也不清空已有数据
 *   · 只创建/更新本脚本负责的文档与索引，不 drop 集合
 *   · 若需彻底重建，见文末"重置"说明
 *
 * ⚠️ 字段命名（重要）
 *   application.properties 配置了
 *     spring.data.mongodb.field-naming-strategy=org.springframework.data.mapping.model.SnakeCaseFieldNamingStrategy
 *   因此 **所有落库字段名都是下划线式**（platform_code / provider_key / is_healthy …）。
 *   （已实测验证：MasterTask.platformCode → platform_code，SubTask.agingCount → aging_count）
 *
 * ⚠️ provider_key 必须与 Spring Bean 名称完全一致
 *   应用通过 ApplicationContext.getBean(providerKey, CommentProvider.class) 取实现类。
 *   当前已注册的 Bean 名称（来自 @Component 注解）：
 *     bilibili_golaxy / douyin_golaxy / xhs_golaxy / toutiao_local
 *     weibo_official / local_crawler / weibo_repost_local
 *     wechat_sy / wechat_video_sy
 *   写错会导致该供应商"Bean 未找到"而被静默跳过。
 */

// ============================================================================
// 0. 基础配置
// ============================================================================

/**
 * 平台功能配置表：一个条目 = 一个「平台 + 功能」。
 * providers 按 priority 升序书写（数值越小越优先）。
 *
 * provider 字段（见设计文档 §6）：
 *   provider_key / name / rate_per_second / max_retry / priority / is_healthy / capabilities
 *   max_concurrency —— Bulkhead 并发上限（§8.1，借鉴 B-20）；
 *                      经验值 ≈ rate_per_second × 预期响应秒数，再留一倍余量
 *   timeout_ms      —— **单次调用**超时（§9.5，借鉴 B-03），不是整条重试链；
 *                      整链预算 = timeout_ms × (max_retry + 1) + 退避总和
 */
const FEATURE_CONFIGS = [
  {
    platform_code: 'weibo',
    feature_code: 'comment',
    feature_name: '微博评论采集',
    providers: [
      // 中科天玑（Golaxy）微博评论接口（无需 Cookie）
      { provider_key: 'weibo_official', name: '微博-中科天玑接口', rate_per_second: 1.0, max_retry: 3, priority: 10, is_healthy: true, max_concurrency: 4, timeout_ms: 15000, capabilities: ['COMMENT','SUB_COMMENT','CURSOR_PAGING','SYNC_SUPPORTED'] },
      // 微博网页版本地爬虫（需要在 requestParams 里传 cookie）
      { provider_key: 'local_crawler', name: '微博-本地爬虫', rate_per_second: 0.5, max_retry: 2, priority: 20, is_healthy: true, max_concurrency: 2, timeout_ms: 20000, capabilities: ['COMMENT','CURSOR_PAGING','LOGIN_STATE','PROXY','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'weibo',
    feature_code: 'repost',
    feature_name: '微博转发采集',
    providers: [
      { provider_key: 'weibo_repost_local', name: '微博-转发本地爬虫', rate_per_second: 0.5, max_retry: 2, priority: 10, is_healthy: true, max_concurrency: 2, timeout_ms: 20000, capabilities: ['COMMENT','PAGE_PAGING','CURSOR_PAGING','LOGIN_STATE','PROXY','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'wechat',
    feature_code: 'comment',
    feature_name: '微信公众号评论采集',
    providers: [
      { provider_key: 'wechat_sy', name: '公众号-系统接口', rate_per_second: 1.0, max_retry: 3, priority: 10, is_healthy: true, max_concurrency: 4, timeout_ms: 15000, capabilities: ['COMMENT','SUB_COMMENT','PAGE_PAGING','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'wechat_video',
    feature_code: 'comment',
    feature_name: '微信视频号评论采集',
    providers: [
      { provider_key: 'wechat_video_sy', name: '视频号-系统接口', rate_per_second: 1.0, max_retry: 3, priority: 10, is_healthy: true, max_concurrency: 4, timeout_ms: 15000, capabilities: ['COMMENT','SUB_COMMENT','PAGE_PAGING','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'bilibili',
    feature_code: 'comment',
    feature_name: 'B站评论采集',
    providers: [
      { provider_key: 'bilibili_golaxy', name: 'B站-中科天玑接口', rate_per_second: 1.0, max_retry: 3, priority: 10, is_healthy: true, max_concurrency: 4, timeout_ms: 15000, capabilities: ['COMMENT','SUB_COMMENT','CURSOR_PAGING','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'douyin',
    feature_code: 'comment',
    feature_name: '抖音评论采集',
    providers: [
      { provider_key: 'douyin_golaxy', name: '抖音-中科天玑接口', rate_per_second: 1.0, max_retry: 3, priority: 10, is_healthy: true, max_concurrency: 4, timeout_ms: 15000, capabilities: ['COMMENT','SUB_COMMENT','PAGE_PAGING','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'xhs',
    feature_code: 'comment',
    feature_name: '小红书评论采集',
    providers: [
      { provider_key: 'xhs_golaxy', name: '小红书-中科天玑接口', rate_per_second: 1.0, max_retry: 3, priority: 10, is_healthy: true, max_concurrency: 4, timeout_ms: 15000, capabilities: ['COMMENT','SUB_COMMENT','CURSOR_PAGING','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'toutiao',
    feature_code: 'comment',
    feature_name: '今日头条评论采集',
    providers: [
      { provider_key: 'toutiao_local', name: '今日头条-本地爬虫', rate_per_second: 0.5, max_retry: 2, priority: 10, is_healthy: true, max_concurrency: 2, timeout_ms: 20000, capabilities: ['COMMENT','SUB_COMMENT','PAGE_PAGING','PROXY','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'tieba',
    feature_code: 'comment',
    feature_name: '百度贴吧评论采集',
    providers: [
      // 贴吧官方接口，无登录 cookie 可用；主列表 15 楼/页，楼中楼 30 条/页
      { provider_key: 'tieba_local', name: '百度贴吧-本地爬虫', rate_per_second: 0.5, max_retry: 2, priority: 10, is_healthy: true, max_concurrency: 2, timeout_ms: 20000, capabilities: ['COMMENT','SUB_COMMENT','PAGE_PAGING','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'honor_bbs',
    feature_code: 'comment',
    feature_name: '荣耀社区评论采集',
    providers: [
      // Discuz 页面直出 HTML + XPath 解析；回复全部内嵌页面，无子回复接口（不声明 SUB_COMMENT）
      { provider_key: 'honor_bbs_local', name: '荣耀社区-本地爬虫', rate_per_second: 0.5, max_retry: 2, priority: 10, is_healthy: true, max_concurrency: 2, timeout_ms: 20000, capabilities: ['COMMENT','PAGE_PAGING','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'huawei_bbs',
    feature_code: 'comment',
    feature_name: '华为社区评论采集',
    providers: [
      // vmall 俱乐部 SGW 网关接口（SGW-APP-ID 内置于实现）；页码分页
      { provider_key: 'huawei_bbs_local', name: '华为社区-本地爬虫', rate_per_second: 0.5, max_retry: 2, priority: 10, is_healthy: true, max_concurrency: 2, timeout_ms: 20000, capabilities: ['COMMENT','SUB_COMMENT','PAGE_PAGING','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'oppo_bbs',
    feature_code: 'comment',
    feature_name: 'OPPO社区评论采集',
    providers: [
      // www.oppo.cn 官方 UGC 接口（GET JSON，无需签名）；页码分页
      { provider_key: 'oppo_bbs_local', name: 'OPPO社区-本地爬虫', rate_per_second: 0.5, max_retry: 2, priority: 10, is_healthy: true, max_concurrency: 2, timeout_ms: 20000, capabilities: ['COMMENT','SUB_COMMENT','PAGE_PAGING','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'vivo_bbs',
    feature_code: 'comment',
    feature_name: 'vivo社区评论采集',
    providers: [
      // bbs.vivo.com.cn 官方接口（nonce 签名内置于实现）；lastId 键集翻页
      { provider_key: 'vivo_bbs_local', name: 'vivo社区-本地爬虫', rate_per_second: 0.5, max_retry: 2, priority: 10, is_healthy: true, max_concurrency: 2, timeout_ms: 20000, capabilities: ['COMMENT','SUB_COMMENT','CURSOR_PAGING','SYNC_SUPPORTED'] },
    ],
  },
  {
    platform_code: 'xiaomi_bbs',
    feature_code: 'comment',
    feature_name: '小米社区评论采集',
    providers: [
      // api.vip.miui.com 官方接口（GET，无需签名）；after 游标（主列表=偏移量，回复=末条回复 id）
      { provider_key: 'xiaomi_bbs_local', name: '小米社区-本地爬虫', rate_per_second: 0.5, max_retry: 2, priority: 10, is_healthy: true, max_concurrency: 2, timeout_ms: 20000, capabilities: ['COMMENT','SUB_COMMENT','CURSOR_PAGING','SYNC_SUPPORTED'] },
    ],
  },
];

/**
 * 用户等级：priority 越小越优先；activationThreshold 见 user_tier_config 注释。
 *
 * parentTierCode 形成继承链 ENTERPRISE → VIP → NORMAL（NORMAL 为基础等级）：
 * 高等级默认继承低等级的全部功能偏好；seed 为了保留各等级原有的 activation_threshold
 * 差异仍为每个等级写全量 feature_configs —— 运维可按需把高等级裁剪成"只留覆盖项"，
 * 此后新增平台只配到 NORMAL 即可被全部等级继承（注册表同步只补基础等级）。
 */
const TIERS = [
  { tier_code: 'ENTERPRISE', parentTierCode: 'VIP', priority: 1, description: '企业用户（最高优先级）', activationThreshold: 0 },
  { tier_code: 'VIP', parentTierCode: 'NORMAL', priority: 10, description: 'VIP用户', activationThreshold: 3 },
  { tier_code: 'NORMAL', parentTierCode: null, priority: 100, description: '普通用户（基础等级，平台全集挂这里）', activationThreshold: 10 },
];

const now = new Date();

// ============================================================================
// 1. platform_feature_config —— 平台功能配置（供应商内嵌）
// ============================================================================

let pfCount = 0;
for (const fc of FEATURE_CONFIGS) {
  const doc = {
    _id: fc.platform_code + ':' + fc.feature_code,
    platform_code: fc.platform_code,
    feature_code: fc.feature_code,
    feature_name: fc.feature_name,
    status: true,
    providers: fc.providers.map((p) => ({ ...p })),
  };
  await db.platform_feature_config.replaceOne({ _id: doc._id }, doc, { upsert: true });
  pfCount++;
}
print('[1/5] platform_feature_config 写入 ' + pfCount + ' 条（'
  + FEATURE_CONFIGS.map((f) => f.platform_code + '/' + f.feature_code).join(', ') + '）');

// ============================================================================
// 2. user_tier_config —— 用户等级配置（决定调度优先级与起始供应商顺序）
// ============================================================================

let tierCount = 0;
for (const tier of TIERS) {
  const featureConfigs = FEATURE_CONFIGS.map((fc) => ({
    platform_code: fc.platform_code,
    feature_code: fc.feature_code,
    // provider_order：该等级在此功能下的起始供应商顺序；未列出的供应商按全局 priority 追加在后
    provider_order: fc.providers.map((p) => p.provider_key),
    // activation_threshold：待处理任务数 >= 该值时，才允许启用第 2 个及以后的供应商；0 = 始终全部可用
    activation_threshold: tier.activationThreshold,
  }));

  const doc = {
    _id: 'tier:' + tier.tier_code,
    tier_code: tier.tier_code,
    // 父等级（单父继承链）：本等级未声明某功能的偏好时沿父链向上继承整条配置，
    // 声明了的整条覆盖。新增平台/功能只需配置到基础等级（无 parent_tier_code 的等级），
    // 高等级自动继承 —— 注册表同步也只向基础等级补缺失功能项。
    parent_tier_code: tier.parentTierCode || null,
    priority: tier.priority,
    description: tier.description,
    feature_configs: featureConfigs,
  };
  await db.user_tier_config.replaceOne({ _id: doc._id }, doc, { upsert: true });
  tierCount++;
}
print('[2/5] user_tier_config 写入 ' + tierCount + ' 条（' + TIERS.map((t) => t.tier_code).join(', ') + '）');

// ============================================================================
// 3. supplier_state —— 供应商运行时状态（初始化为健康、零计数）
// ============================================================================

let stateCount = 0;
for (const fc of FEATURE_CONFIGS) {
  for (const p of fc.providers) {
    // supplier_key 与 PlatformFeatureConfig.buildStateKey() 保持一致：platform:feature:providerKey
    const supplierKey = fc.platform_code + ':' + fc.feature_code + ':' + p.provider_key;
    // 注意：supplier_state 是**运行时状态**（熔断器独占读写），升级时重跑本脚本不能把在线状态抹掉。
    // 因此计数/熔断字段一律放 $setOnInsert：只在文档不存在时写入初值，已存在则保持不动。
    await db.supplier_state.updateOne(
      { _id: supplierKey },
      {
        $set: {
          supplier_key: supplierKey,
          platform_code: fc.platform_code,
          feature_code: fc.feature_code,
          provider_key: p.provider_key,
        },
        $setOnInsert: {
          circuit_state: 'CLOSED',
          circuit_opened_at: null,
          current_qps: 0.0,
          queue_depth: 0,
          consecutive_failures: 0,
          health_status: 'UP',
          last_success_time: null,
          last_failure_time: null,
          last_heartbeat: now,
          total_success: 0,
          total_failure: 0,
          avg_response_time: 0.0,
          update_time: now,
        },
      },
      { upsert: true }
    );
    stateCount++;
  }
}
print('[3/5] supplier_state 写入 ' + stateCount + ' 条（已存在的运行时状态保持不变）');

// ============================================================================
// 4. 演示任务数据（master_task / sub_task）
//    用途：让 GET /api/tasks/{id} 与 /api/tasks/{id}/comments 一启动就有数据可查。
//    不需要时，把本段整体注释掉，或执行文末的清理语句。
// ============================================================================

const DEMO_MASTER_ID = 'MT-DEMO-0000000000000001';
const demoCreateTime = new Date(Date.now() - 10 * 60 * 1000);

const demoMaster = {
  _id: DEMO_MASTER_ID,
  user_id: 'demo_user',
  user_tier_code: 'VIP',
  platform_code: 'bilibili',
  feature_code: 'comment',
  mode: 'ASYNC',
  status: 'ACTIVE',
  links: [
    'https://www.bilibili.com/video/BV1xx411c7mD',
    'https://www.bilibili.com/video/BV1yy411c7mE',
    'https://www.bilibili.com/video/BV1zz411c7mF',
  ],
  request_params: '{"maxCount":20}',
  supplier_constraint: null,
  callback_url: null,
  total_links: 3,
  success_links: 1,
  failed_links: 1,
  error_message: null,
  create_time: demoCreateTime,
  start_time: new Date(demoCreateTime.getTime() + 2000),
  complete_time: null,
  update_time: now,
};
await db.master_task.replaceOne({ _id: demoMaster._id }, demoMaster, { upsert: true });

const demoSubTasks = [
  {
    _id: 'ST-DEMO-0000000000000001',
    master_task_id: DEMO_MASTER_ID,
    link: demoMaster.links[0],
    provider_key: 'bilibili_golaxy',
    status: 'SUCCESS',
    priority: 10,
    aging_count: 0,
    retry_count: 0,
    max_retry: 3,
    attempted_providers: null,
    result: '{"commentCount":20}',
    error_message: null,
    next_execute_time: null,
    create_time: demoCreateTime,
    start_time: new Date(demoCreateTime.getTime() + 2000),
    complete_time: new Date(demoCreateTime.getTime() + 15000),
    update_time: now,
  },
  {
    _id: 'ST-DEMO-0000000000000002',
    master_task_id: DEMO_MASTER_ID,
    link: demoMaster.links[1],
    provider_key: 'bilibili_golaxy',
    status: 'PENDING',
    priority: 10,
    aging_count: 2,
    retry_count: 0,
    max_retry: 3,
    attempted_providers: null,
    result: null,
    error_message: null,
    next_execute_time: now,
    create_time: demoCreateTime,
    start_time: null,
    complete_time: null,
    update_time: now,
  },
  {
    _id: 'ST-DEMO-0000000000000003',
    master_task_id: DEMO_MASTER_ID,
    link: demoMaster.links[2],
    provider_key: 'bilibili_golaxy',
    status: 'FAILED',
    priority: 10,
    aging_count: 0,
    retry_count: 3,
    max_retry: 3,
    attempted_providers: '["bilibili_golaxy"]',
    result: null,
    error_message: '供应商 [bilibili_golaxy] 重试 3 次后仍失败',
    next_execute_time: null,
    create_time: demoCreateTime,
    start_time: new Date(demoCreateTime.getTime() + 2000),
    complete_time: new Date(demoCreateTime.getTime() + 20000),
    update_time: now,
  },
];
for (const st of demoSubTasks) {
  await db.sub_task.replaceOne({ _id: st._id }, st, { upsert: true });
}
print('[4/5] 演示任务写入 1 个主任务 + ' + demoSubTasks.length + ' 个子任务（taskId=' + DEMO_MASTER_ID + '）');

// ============================================================================
// 5. 索引
// ============================================================================

await db.platform_feature_config.createIndex(
  { platform_code: 1, feature_code: 1 },
  { unique: true, name: 'idx_platform_feature' }
);

await db.user_tier_config.createIndex({ tier_code: 1 }, { unique: true, name: 'idx_tier_code' });

await db.supplier_state.createIndex({ supplier_key: 1 }, { unique: true, name: 'idx_supplier_key' });

await db.master_task.createIndex({ status: 1, create_time: 1 }, { name: 'idx_status_create_time' });
await db.master_task.createIndex({ user_id: 1, create_time: -1 }, { name: 'idx_user_create_time' });
// 提交幂等键唯一索引（阶段1 幂等改造）：partial 过滤表达式只约束 string 类型的键，
// 未传幂等键的任务（无该字段 / null）不受唯一性约束。
// 前置条件：TaskManagementService 已支持并发同 key 时捕获 DuplicateKeyException 并回查返回。
try {
  await db.master_task.dropIndex('idx_idempotency_key');
} catch (e) { /* 首次执行不存在，正常 */ }
await db.master_task.createIndex(
  { idempotency_key: 1 },
  { unique: true, name: 'idx_idempotency_key', partialFilterExpression: { idempotency_key: { $type: 'string' } } }
);

await db.sub_task.createIndex({ master_task_id: 1, status: 1 }, { name: 'idx_master_status' });
await db.sub_task.createIndex({ status: 1, create_time: 1 }, { name: 'idx_status_create_time' });
// (master_task_id, link) 唯一索引（阶段1 幂等改造）：同一主任务下链接只允许一个子任务。
// 建任务入口已先去重（TaskManagementService.doCreateMasterTask 用 LinkedHashSet 去重后再拆分），
// 因此正常路径不会触发唯一冲突，索引是并发/数据修复场景的兜底。
// 存量库若已有重复数据需先去重，否则建索引失败：
//   db.sub_task.aggregate([{$group:{_id:{m:'$master_task_id',l:'$link'},n:{$sum:1},ids:{$push:'$_id'}}},{$match:{n:{$gt:1}}}])
//   —— 每组保留 createTime 最早的一条，其余删除后再执行本脚本。
try {
  await db.sub_task.dropIndex('idx_master_link');
  await db.sub_task.dropIndex('idx_master_link_unique');
} catch (e) { /* 首次执行不存在，正常 */ }
await db.sub_task.createIndex(
  { master_task_id: 1, link: 1 },
  { unique: true, name: 'idx_master_link_unique' }
);
// 子任务重投扫描的部分索引（阶段1 重试闭环）：只索引 RETRYING 文档，
// SubTaskDao.findDueRetries 的扫描开销与待重投规模成正比
await db.sub_task.createIndex(
  { next_execute_time: 1 },
  { name: 'idx_retry_scan', partialFilterExpression: { status: 'RETRYING' } }
);

// —— 采集结果集合 comment（单集合 + data_type 判别）——
// 分页查询的排序键是 insert_time，因此把 insert_time 放进复合索引，
// 否则「按 data_type 过滤 + 按时间倒序分页」会退化为阻塞式内存排序（实测见设计文档 §5.5.1）。
await db.comment.createIndex({ task_id: 1, insert_time: -1 }, { name: 'idx_task_insert' });
await db.comment.createIndex(
  { task_id: 1, data_type: 1, insert_time: -1 },
  { name: 'idx_task_datatype_insert' }
);
await db.comment.createIndex({ sub_task_id: 1 }, { name: 'idx_subtask' });
// 旧索引 {task_id, data_type} 是新索引的前缀，已被完全覆盖，存在即删除（幂等）
try {
  await db.comment.dropIndex('idx_task_datatype');
  print('    已删除冗余索引 comment.idx_task_datatype');
} catch (e) {
  // 首次执行时该索引不存在，属正常
}

// —— 系统运行参数配置 system_config（_id 即配置键）——
// DB 值覆盖 application.properties 的同名默认，Caffeine 缓存 60s；
// 用 $setOnInsert 播种：只在键不存在时写入，人工改过的值不会被本脚本重置。
// 删除某个键 = 回退 properties 默认（或调 DELETE /api/config/{key}）。
const SYSTEM_CONFIG_SEEDS = [
  // 熔断器
  { _id: 'collector.circuit.enabled', value: 'true', description: '熔断器总开关' },
  { _id: 'collector.circuit.failure-rate-threshold', value: '50', description: '失败率熔断阈值（%）' },
  { _id: 'collector.circuit.slow-call-ms', value: '10000', description: '慢调用判定线（毫秒），<=0 关闭' },
  { _id: 'collector.circuit.slow-call-rate-threshold', value: '80', description: '慢调用比例熔断阈值（%）' },
  { _id: 'collector.circuit.sliding-window-size', value: '20', description: '滑动窗口样本数' },
  { _id: 'collector.circuit.minimum-calls', value: '5', description: '触发评估的最小调用数' },
  { _id: 'collector.circuit.open-seconds', value: '60', description: '熔断冷却时长（秒），结束后进入半开' },
  { _id: 'collector.circuit.half-open-calls', value: '3', description: '半开探测名额（全成功才恢复）' },
  // 自适应限速
  { _id: 'collector.ratelimit.adaptive-enabled', value: 'true', description: '自适应限速总开关' },
  { _id: 'collector.ratelimit.reject-timeout-ms', value: '500', description: 'REJECT/WARM_UP 取令牌最长等待（毫秒）' },
  { _id: 'collector.ratelimit.max-queue-wait-ms', value: '2000', description: 'THROTTLE_QUEUE 默认最长排队（毫秒）' },
  { _id: 'collector.ratelimit.warm-up-seconds', value: '30', description: '冷启动/熔断恢复后的预热时长（秒）' },
  { _id: 'collector.ratelimit.persist-interval-seconds', value: '30', description: '派生指标写库节流（秒）' },
  // 调用管线
  { _id: 'collector.pipeline.enabled', value: 'true', description: '管线总开关（false 直连供应商，压测隔离用）' },
  { _id: 'collector.pipeline.timeout-ms', value: '15000', description: '全局单次调用超时（毫秒），供应商 timeout_ms 优先' },
  { _id: 'collector.pipeline.default-max-concurrency', value: '4', description: '全局默认并发上限（Bulkhead），0=不限' },
  { _id: 'collector.pipeline.retry-backoff-base-ms', value: '500', description: '供应商内重试退避基数（毫秒）' },
  // 子任务级重投
  { _id: 'collector.task.sub-retry-max', value: '2', description: '子任务重投次数上限（跨队列级）' },
  { _id: 'collector.task.sub-retry-base-delay-ms', value: '2000', description: '重投退避基数（毫秒）' },
  { _id: 'collector.task.sub-retry-max-delay-ms', value: '600000', description: '重投退避封顶（毫秒）' },
  { _id: 'collector.task.sub-retry-queue-reschedule-ms', value: '30000', description: '重投被队列拒后的推迟时长（毫秒）' },
  { _id: 'collector.task.sub-retry-scan-limit', value: '200', description: '重投扫描每轮最多处理条数' },
  // 队列与调度（外置队列阶段2：容量在 XADD Lua 内按本值原子校验；公平配额由消费者逐轮读取）
  { _id: 'collector.task.queue-capacity', value: '10000', description: '异步队列容量（HIGH+LOW 两流未确认消息总数上限）' },
  { _id: 'collector.task.max-wait-minutes', value: '30', description: 'LOW 流等待超过该时长迁移到 HIGH（deadline 保障，分钟）' },
  { _id: 'collector.task.fair-quota-ratio', value: '0.3', description: '公平配额：每轮留给低优先级流的比例' },
  { _id: 'collector.task.fair-quota-threshold', value: '0.7', description: '公平配额：高优先级占比达到该值才启用' },
  { _id: 'collector.task.fair-quota-high-priority-bound', value: '500', description: '有效优先级小于该值进 HIGH 流（同时决定入队层级）' },
  { _id: 'collector.task.fair-quota-batch-size', value: '20', description: '公平配额：一个消费轮次的名额数' },
  // 服务端自动翻页
  { _id: 'collector.task.auto-paging.enabled', value: 'false', description: '自动翻页全局默认（任务级 autoPage 优先）' },
  { _id: 'collector.task.auto-paging.max-pages', value: '10', description: '单链接最大翻页深度' },
  // 完成回调
  { _id: 'collector.callback.enabled', value: 'true', description: '主任务完成回调开关' },
  { _id: 'collector.callback.timeout-ms', value: '5000', description: '回调单次超时（毫秒）' },
  { _id: 'collector.callback.max-attempts', value: '3', description: '回调重试次数' }
];
for (const seed of SYSTEM_CONFIG_SEEDS) {
  await db.system_config.updateOne(
    { _id: seed._id },
    { $setOnInsert: { value: seed.value, description: seed.description, update_time: new Date() } },
    { upsert: true }
  );
}
print('    system_config 已播种 ' + SYSTEM_CONFIG_SEEDS.length + ' 个运行参数（$setOnInsert，不覆盖人工修改）');

print('[5/5] 索引创建完成');

// ============================================================================
// 6. 结果核对
// ============================================================================

print('---- 数据核对 ----');
print('platform_feature_config = ' + (await db.platform_feature_config.countDocuments()));
print('user_tier_config        = ' + (await db.user_tier_config.countDocuments()));
print('supplier_state          = ' + (await db.supplier_state.countDocuments()));
print('master_task             = ' + (await db.master_task.countDocuments()));
print('sub_task                = ' + (await db.sub_task.countDocuments()));
print('comment                 = ' + (await db.comment.countDocuments()));
print('初始化完成。');

// ============================================================================
// 附：重置 / 清理（默认不执行，按需手动运行）
// ============================================================================
//
// 仅删除本脚本写入的演示任务与结果：
//   await db.master_task.deleteOne({ _id: 'MT-DEMO-0000000000000001' });
//   await db.sub_task.deleteMany({ master_task_id: 'MT-DEMO-0000000000000001' });
//   await db.comment.deleteMany({ task_id: 'MT-DEMO-0000000000000001' });
//
// 彻底重建（危险，会清空全部业务数据）：
//   await db.dropDatabase();
//   然后重新执行本脚本。
