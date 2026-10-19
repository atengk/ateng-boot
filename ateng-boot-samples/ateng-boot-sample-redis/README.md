# ateng-boot-sample-redis 分布式协同自验工程

> 专一展示 Redis / Redisson 分布式锁、接口限流防刷、幂等防重、双模延时队列、游标模糊扫描与原子流水号发号。

---

## 1. 场景痛点与功能定位

在高并发业务场景下，防超卖、限流防刷、防重复提交与延时关单是不可或缺的保护手段。然而传统接入往往引入繁琐的样板代码、复杂的序列化 `@class` 污染，或者与数据库紧密耦合导致 Demo 极度臃肿。

`ateng-boot-sample-redis` 专注于轻量、纯粹的分布式协同场景：
- **零数据库依赖（Zero DB Dependency）**：无需任何 MySQL 或 H2 数据库，纯粹内存与 Redis 驱动，极简轻装上阵；
- **声明式与编程式分布式锁**：`@DistributedLock`、`DistributedLockTemplate` 与 `RedisLockUtil`，支持可重入性、看门狗自动续期（`leaseTime = -1`）与争锁超时降级闭包；
- **Redisson 双模可靠延时队列**：`RedisDelayedQueue` 编程式精准投递与 `@DelayedQueueListener` 声明式并发消费；
- **分布式限流防刷与幂等防重**：Web 模块自适应检测 `RedissonClient` 并自动晋升为 Tier 1 分布式引擎（`RedissonRateLimiterStore` / `RedissonIdempotentKeyStore`）；
- **全能工具与原子操作**：`RedisUtil.scanKeys` 安全游标扫描防阻塞，`RedisAtomicUtil` 跨天自重置原子流水号发号与 ZSet 毫秒级滑动窗口频控。

---

## 2. 演示场景清单

| 演示场景 | 核心门面 / 注解 | 业务类与端点 | 核心特性与行为 |
| :--- | :--- | :--- | :--- |
| **分布式排他锁** | `@DistributedLock` | `POST /api/redis-sample/deduct-stock` | SpEL 动态键防并发超卖，持锁超时保护与安全防误删释放 |
| **锁重入与看门狗** | `@DistributedLock`<br>`RedisLockUtil` | `RedisSampleService.executeReentrantBusiness`<br>`RedisSampleService.executeWithWatchdog` | 外层注解内层模板同一线程安全重入；看门狗 30s 自动续期 |
| **争锁超时降级** | `DistributedLockTemplate` | `RedisSampleService.executeWithFallback` | 争锁失败执行兜底 fallback 闭包，不抛出异常 |
| **双模延时队列** | `RedisDelayedQueue`<br>`@DelayedQueueListener` | `POST /api/redis-sample/delay-order`<br>`OrderDelayQueueService.onOrderCancel` | 编程式投递延迟关单任务，后台多线程声明式自动反序列化消费 |
| **接口限流防刷** | `@RateLimiter` | `GET /api/redis-sample/limited-api` | 纯净分布式令牌桶，超限自动返回 HTTP 200 + 429 契约 |
| **接口幂等防重** | `@Idempotent` | `POST /api/redis-sample/idempotent` | 分布式 Key 锁防表单短时间内重复提交 |
| **安全游标扫描** | `RedisUtil.scanKeys` | `GET /api/redis-sample/scan-keys` | 底层基于 SCAN 游标分批扫描匹配 Key，杜绝 `KEYS *` 阻塞生产 |
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

### 3.4 验证投递订单延时取消任务
```bash
curl -X POST "http://localhost:8080/api/redis-sample/delay-order?orderId=ORD_999&delaySeconds=10"
```
**预期响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "延时取消订单任务投递成功: ORD_999",
  "success": true,
  "traceId": "70014d6d496d89af2b1f019a47665443"
}
```
**后台到期消费日志**：
```text
2026-10-19 16:11:54.513 INFO [:order:cancel-1] [cff82f2353553ebd092601e87767de84] i.g.a.b.s.r.s.OrderDelayQueueService : 声明式监听到期消费: orderId=ORD_999, reason=订单未支付超时自动取消
```

### 3.5 验证游标模糊扫描与原子流水号发号
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

本工程集成测试通过内存级 `RLock` 与模拟队列实现 **100% 离线自洽运行**，无需启动外部物理 Redis 即可秒级验证全部功能：

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-redis -am -T 1C -nsu -B
```

测试集涵盖：
1. `@DistributedLock` 声明式切面拦截与 SpEL 动态解析
2. `DistributedLockTemplate` 与 `RedisLockUtil` 编程式模板加锁与静态门面加锁
3. 分布式锁同一线程跨方法可重入性（外层注解、内层闭包）
4. 分布式锁争锁超时降级闭包（`fallback`）
5. 分布式锁看门狗自动续期长事务执行（`leaseTime = -1`）
6. Redisson 双模延时队列投递与 `@DelayedQueueListener` 声明式异步消费
7. `RedisUtil` 游标分批模糊扫描（`scanKeys`）
8. `RedisAtomicUtil` 跨天原子自重置流水号发号与 ZSet 毫秒滑动窗口频控
9. Web 模块自适应检测 `RedissonClient` 自动切换 Tier 1 存储引擎（`RedissonRateLimiterStore` 与 `RedissonIdempotentKeyStore`）
