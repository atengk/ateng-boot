# ateng-boot-sample-redis 分布式协同自验工程

> 专注于 Redis / Redisson 分布式锁、接口限流防刷、幂等防重、双模延时队列与死信保障、Pub/Sub 广播订阅、分布式布隆过滤器、缓存防卫模板（防击穿/防穿透/防雪崩/防 N+1）以及生产级两级缓存体系。

---

## 1. 场景痛点与功能定位

在高并发分布式业务场景下，防超卖、限流防刷、防重复提交、延时关单、热点缓存防击穿穿透与多实例数据一致性是核心底座需求。然而传统方案往往存在样板代码繁琐、序列化 `@class` CVE 风险、野线程泄露、缺乏看门狗导致锁提前失效、延时队列死信丢失、以及两级缓存自重入死循环等顽疾。

`ateng-boot-sample-redis` 作为 1:1 对齐 `ateng-boot-redis` 的独立场景沙箱，具备以下工业级特性：
- **零数据库依赖（Zero DB Dependency）**：无需任何 MySQL 或 H2 数据库，纯粹内存与 Redis 驱动，极简轻装上阵；
- **声明式与编程式分布式锁**：`@DistributedLock`、`DistributedLockTemplate` 与 `RedisLockUtil`，支持可重入性、看门狗自动续期（`leaseTime = -1`）、降级 fallback 闭包与安全防误删释放；
- **Redisson 双模可靠延时队列与死信保障**：`RedisDelayedQueue` 编程式精准投递与 `@DelayedQueueListener` 声明式并发消费，支持受管线程池驱动、TraceId 透传、重试耗尽自动流转死信队列（`{queue}:dlq`）与空安全拉取；
- **声明式广播订阅与通配匹配**：`@ChannelTopicListener` 声明式监听与 `RedisMessagePublisher` 广播发布，支持精确频道与通配模式（`patterns = "sample:system:*"`）、强类型 POJO 智能反序列化；
- **分布式布隆过滤器**：`RedisBloomFilter` 支持 YAML 声明式自动预热与动态操作双轨、80% 容量预警与全局 KeyPrefix 隔离；
- **工业级全景缓存防卫模板**：`RedisCacheTemplate` 单键 DCL 互斥锁防击穿、短 TTL 空哨兵（`__ATENG_CACHE_NULL__`）防穿透、随机抖动防雪崩、争锁超时降级、以及批量 `batchGetOrLoad`（MGET + 字典序排序锁 + Pipeline 批量回写）彻底消除 N+1 查询；
- **生产级内存+Redis 两级缓存体系**：`TwoLevelCache` 与 `TwoLevelCacheManager`（Spring Cache `@Primary` 适配器），支持 L1 Caffeine（无依赖时平滑退化为内置 LRU 严格防 OOM）+ L2 Redis 立体架构，基于集中频道 `ateng:cache:evict:topic` 广播协同失效并比对 `instanceId` 彻底消除自重入死循环；
- **分布式限流防刷与幂等防重**：Web 模块自适应检测 `RedissonClient` 并自动晋升为 Tier 1 分布式存储引擎（`RedissonRateLimiterStore` / `RedissonIdempotentKeyStore`）；
- **全能工具与原子操作**：`RedisUtil.scanKeys` 安全游标扫描防阻塞，`RedisAtomicUtil` 跨天自重置原子流水号发号与 ZSet 毫秒级滑动窗口频控。

---

## 2. 演示场景清单

| 演示场景 | 核心门面 / 注解 / SPI | 业务类与端点 | 核心特性与行为 |
| :--- | :--- | :--- | :--- |
| **分布式排他锁** | `@DistributedLock` | `POST /api/redis-sample/deduct-stock` | SpEL 动态键防并发超卖，持锁超时保护与安全防误删释放 |
| **锁重入与看门狗** | `@DistributedLock`<br>`RedisLockUtil` | `RedisSampleService.executeReentrantBusiness`<br>`RedisSampleService.executeWithWatchdog` | 外层注解内层模板同一线程安全重入；看门狗 30s 自动续期 |
| **争锁超时降级** | `DistributedLockTemplate` | `RedisSampleService.executeWithFallback` | 争锁失败执行兜底 fallback 闭包，不抛出异常 |
| **双模延时队列** | `RedisDelayedQueue`<br>`@DelayedQueueListener` | `POST /api/redis-sample/delay-order`<br>`OrderDelayQueueService.onOrderCancel` | 编程式投递延迟关单任务，后台受管线程池声明式自动反序列化消费 |
| **延时队列死信保障** | `RedisDelayedQueue.sendToDeadLetter`<br>`RedisDelayedQueue.getDeadLetterSize`<br>`RedisDelayedQueue.pollDeadLetter` | `POST /api/redis-sample/delay-order/dead-letter/send`<br>`GET /api/redis-sample/delay-order/dead-letter/size`<br>`GET /api/redis-sample/delay-order/dead-letter/poll` | 模拟重试耗尽主动转入死信队列，支持死信数量监控、空安全拉取与真实流转出队 |
| **广播通知精确订阅** | `@ChannelTopicListener`<br>`RedisMessagePublisher` | `POST /api/redis-sample/broadcast-notice`<br>`GET /api/redis-sample/received-notices` | 跨实例广播系统公告，强类型 POJO 自动反序列化与多线程安全收集 |
| **广播事件通配订阅** | `@ChannelTopicListener(patterns = "...")`<br>`RedisMessagePublisher` | `POST /api/redis-sample/broadcast-system-event`<br>`GET /api/redis-sample/received-system-events` | 通配模式广播监听（`sample:system:*`），多参数双参/三参消费 |
| **分布式布隆过滤器** | `RedisBloomFilter`<br>`BloomFilterBootstrap` | `POST /api/redis-sample/bloom/blacklist/add`<br>`GET /api/redis-sample/bloom/blacklist/check`<br>`GET /api/redis-sample/bloom/blacklist/metrics` | 声明式预热配置映射、静态与 Bean 双重操作范式、80% 填充率容量告警 |
| **单商品安全缓存** | `RedisCacheTemplate.getOrLoad` | `GET /api/redis-sample/cache/product` | DCL + 互斥锁防击穿、短 TTL 空哨兵防穿透、随机抖动防雪崩、争锁超时降级 |
| **批量商品安全查询** | `RedisCacheTemplate.batchGetOrLoad` | `GET /api/redis-sample/cache/products` | 批量 MGET 探测 + 字典序排序锁 + 单次批量回源 + Pipeline 批量回填，消除 N+1 |
| **对称缓存驱逐** | `RedisCacheTemplate.evict` | `POST /api/redis-sample/cache/product/evict` | 主动驱逐单键缓存，对称运维操作 |
| **两级缓存极速查询** | `@Cacheable(cacheNames = "user-profile")` | `GET /api/redis-sample/cache/user-profile` | 首次查库回填 L1+L2，二次查询 0 次查库从 L1 纳秒级极速命中返回 |
| **两级缓存定向驱逐** | `@CacheEvict(cacheNames = "user-profile")` | `POST /api/redis-sample/cache/user-profile/evict` | 清除本地 L1 与 L2 Redis，广播协同其他节点清除本地 L1，比对 `instanceId` 防自重入 |
| **两级缓存全量清空** | `@CacheEvict(allEntries = true)` | `POST /api/redis-sample/cache/user-profile/clear` | 游标安全批量扫描清除 L2 Redis，清空本地 L1 并广播协同集群全量失效 |
| **接口限流防刷** | `@RateLimiter` | `GET /api/redis-sample/limited-api` | 纯净分布式令牌桶，超限自动返回 HTTP 200 + 429 契约 |
| **接口幂等防重** | `@Idempotent` | `POST /api/redis-sample/idempotent` | 分布式 Key 锁防表单短时间内重复提交 |
| **安全游标扫描** | `RedisUtil.scanKeys` | `GET /api/redis-sample/scan-keys` | 底层基于 SCAN 游标分批扫描匹配 Key，杜绝 `KEYS *` 阻塞生产 |
| **键名前缀隔离** | `RedisUtil.buildKey` | `GET /api/redis-sample/key/build` | 基于全局配置前缀 `ateng.boot.redis.key-prefix` 规范化拼接完整键名，杜绝模块间键名冲突 |
| **原子流水号发号** | `RedisAtomicUtil` | `GET /api/redis-sample/daily-seq` | 前缀 + yyyyMMdd + 6位递增流水号，跨天自重置且带 48h TTL 防泄露 |

---

## 3. 本地启动与端点自验

### 本地启动（需本机开启 Redis 端口 6379）
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-redis spring-boot:run
```

### 3.1 验证分布式锁并发扣减库存
```bash
curl -X POST "http://localhost:8080/api/redis-sample/deduct-stock?productId=1001&count=5"
```
**预期响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "扣减库存成功: 5",
  "success": true,
  "traceId": "376f3111d393d4810edd37bb0a2884ab"
}
```

### 3.2 验证接口限流防刷
连续高频发送 6 次请求（第 6 次超限）：
```bash
curl -i "http://localhost:8080/api/redis-sample/limited-api?userId=USER_1001"
```
**超限触发预期响应**：
```json
{
  "code": 429,
  "msg": "访问过于频繁，请稍后重试",
  "data": null,
  "success": false,
  "traceId": "9b1d0e2f4a3b7f8b"
}
```

### 3.3 验证接口幂等防重
```bash
curl -X POST "http://localhost:8080/api/redis-sample/idempotent?token=TOKEN_IDEM_888"
```
**预期响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "幂等提交成功: token=TOKEN_IDEM_888",
  "success": true,
  "traceId": "4a2c1f0d3e5b7a9c"
}
```

### 3.4 验证投递订单延时取消任务与死信状态
```bash
# 1. 投递延时订单
curl -X POST "http://localhost:8080/api/redis-sample/delay-order?orderId=ORD_999&delaySeconds=10"

# 2. 模拟重试耗尽流转至死信队列
curl -X POST "http://localhost:8080/api/redis-sample/delay-order/dead-letter/send?orderId=ORD_DEAD_999&retryCount=3&errorMessage=重试3次超时失败"

# 3. 查询死信队列消息数
curl "http://localhost:8080/api/redis-sample/delay-order/dead-letter/size"

# 4. 拉取死信消息（消费出队）
curl "http://localhost:8080/api/redis-sample/delay-order/dead-letter/poll"
```
**后台到期消费日志**：
```text
INFO [:order:cancel-1] [cff82f2353553ebd092601e87767de84] i.g.a.b.s.r.s.OrderDelayQueueService : 声明式监听到期消费: orderId=ORD_999, reason=订单未支付超时自动取消
```

### 3.5 验证广播通知与通配事件发布订阅
```bash
# 1. 精确主题广播通知
curl -X POST "http://localhost:8080/api/redis-sample/broadcast-notice?noticeId=NOTICE_20261019&title=系统升级维护公告&content=今晚24点全服停机维护"

# 2. 查询已接收通知
curl "http://localhost:8080/api/redis-sample/received-notices"

# 3. 通配主题广播系统事件
curl -X POST "http://localhost:8080/api/redis-sample/broadcast-system-event?eventType=config_reload&data=cluster_mode=true"

# 4. 查询已接收系统事件
curl "http://localhost:8080/api/redis-sample/received-system-events"
```
**预期响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": [
    {
      "noticeId": "NOTICE_20261019",
      "title": "系统升级维护公告",
      "content": "今晚24点全服停机维护"
    }
  ],
  "success": true
}
```

### 3.6 验证分布式布隆过滤器黑名单与监控
```bash
# 1. 添加用户至黑名单
curl -X POST "http://localhost:8080/api/redis-sample/bloom/blacklist/add?userId=SPAMMER_888"

# 2. 检查黑名单存在性
curl "http://localhost:8080/api/redis-sample/bloom/blacklist/check?userId=SPAMMER_888"

# 3. 查询布隆过滤器容量指标
curl "http://localhost:8080/api/redis-sample/bloom/blacklist/metrics"
```
**指标预期响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "filterName": "user-blacklist",
    "count": "1",
    "expectedInsertions": "50000",
    "falseProbability": 0.01,
    "usageRate": 0.00002
  },
  "success": true
}
```

### 3.7 验证全景缓存防卫模板（单查、批量防 N+1 与主动驱逐）
```bash
# 1. 单商品安全查询（首次回源查库，二次查询直接命中缓存）
curl "http://localhost:8080/api/redis-sample/cache/product?productId=1001"

# 2. 批量商品查询（MGET 探测 + 排序分布式锁 + 单次批量回源 + Pipeline 批量回填）
curl "http://localhost:8080/api/redis-sample/cache/products?productIds=1001,1002,9999"

# 3. 主动驱逐商品缓存
curl -X POST "http://localhost:8080/api/redis-sample/cache/product/evict?productId=1001"
```
**批量查询预期响应**（非存在商品 9999 自动回填短 TTL 空哨兵防穿透）：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "1001": {
      "productId": "1001",
      "name": "iPhone 16 Pro",
      "price": 7999.00,
      "stock": 50
    },
    "1002": {
      "productId": "1002",
      "name": "MacBook Pro M4",
      "price": 12999.00,
      "stock": 20
    }
  },
  "success": true
}
```

### 3.8 验证两级缓存极速查询、定向失效与广播协同
```bash
# 1. 初次查询用户档案（冷启动查库，回填 L1 本地内存与 L2 Redis）
curl "http://localhost:8080/api/redis-sample/cache/user-profile?userId=2001"

# 2. 二次查询用户档案（0 次 DB 访问，纳秒级自 L1 堆内存极速返回）
curl "http://localhost:8080/api/redis-sample/cache/user-profile?userId=2001"

# 3. 定向失效缓存（清除本地 L1 与 L2 Redis，并通过广播失效其他节点 L1）
curl -X POST "http://localhost:8080/api/redis-sample/cache/user-profile/evict?userId=2001"

# 4. 全量清空两级缓存
curl -X POST "http://localhost:8080/api/redis-sample/cache/user-profile/clear"
```

### 3.9 验证游标模糊扫描与原子流水号发号
```bash
# 游标扫描
curl "http://localhost:8080/api/redis-sample/scan-keys?pattern=sample:user:*&count=50"

# 跨天原子流水号发号
curl "http://localhost:8080/api/redis-sample/daily-seq?prefix=ORD&length=6"
```
**流水号预期响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "ORD20261019000001",
  "success": true,
  "traceId": "662381e4dae094972045a1b71d07bf64"
}
```

---

## 4. 定向集成测试

本工程集成测试通过纯内存级 Mock 连接工厂、`RLock` 与模拟队列实现 **100% 离线自洽运行**，无需启动外部物理 Redis 即可秒级验证全部功能：

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-redis -am -T 1C -nsu -B
```

**实测测试用例集（28 项端到端全场景 100% 覆盖）**：
1. `@DistributedLock` 声明式切面拦截与 SpEL 动态解析
2. 通过 Controller 端点调用扣减库存接口，验证分布式排他锁生效
3. `DistributedLockTemplate` 与 `RedisLockUtil` 编程式模板加锁与静态门面加锁
4. 分布式锁同一线程跨方法可重入性（外层注解加锁，内层模板重入加锁）
5. 分布式锁争锁超时降级闭包（`fallback` 兜底闭包顺利执行）
6. 分布式锁看门狗自动续期长事务执行（`leaseTime = -1`）
7. Redisson 双模延时队列投递与 `@DelayedQueueListener` 声明式异步消费
8. 延时队列死信队列（DLQ）状态查询、空安全拉取与 Controller 端点闭环
9. 延时队列死信队列（DLQ）真实流转、积压监控与拉取消费出队
10. `RedisUtil` 游标分批模糊扫描（`scanKeys`）与非阻塞迭代
11. `RedisAtomicUtil` 跨天原子自重置流水号发号
12. `RedisAtomicUtil` 基于 ZSet 毫秒滑动窗口高频请求限流
13. `RedisSampleController` 接口限流 `@RateLimiter` 正常放行与统一契约
14. `RedisSampleController` 接口幂等 `@Idempotent` 正常放行与统一契约
15. `RedisSampleController` 延时订单接口投递
16. `RedisSampleController` 游标扫描与流水号接口
17. `RedisUtil` 与 `RedisAtomicUtil` 门面 Bean 成功注入 Spring 上下文
18. Web 模块自适应检测到 `RedissonClient` 自动切换 Tier 1 存储引擎（`RedissonRateLimiterStore` 与 `RedissonIdempotentKeyStore`）
19. 声明式 `@ChannelTopicListener` 主题监听与强类型反序列化
20. 声明式 `@ChannelTopicListener` 通配模式监听（`patterns = "sample:system:*"`）与多参数消费
21. 分布式布隆过滤器声明式预热、黑名单判定与强类型容量指标查询（`BloomFilterMetricsVO`）
22. `RedisCacheTemplate` 统一装配与上下文契约验证
23. 单商品查询冷启动回源、二次命中缓存不查库与主动驱逐
24. 非存在商品查询回填短 TTL 空值哨兵，杜绝缓存穿透
25. 批量商品查询 MGET 探测 + 字典序排序分布式锁 + 单次批量回源 + Pipeline 批量回填，彻底消除 N+1
26. Spring Cache `@Cacheable` 两级缓存冷启动查库、二次极速命中 L1（0 次 DB 访问）与 `@CacheEvict` 定向与全量失效
27. `TwoLevelCacheManager` 自动装配、Primary 契约与编程式门面调用
28. 全局 KeyPrefix 隔离与 `RedisUtil.buildKey` 辅助门面构建
