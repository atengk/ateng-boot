# ateng-boot-redis 分布式协同与缓存增强套件

> 提供 Spring Data Redis (Lettuce) 与 Redisson 双核融合底座、无 `@class` 污染纯净安全 JSON 序列化、全能 `RedisUtil` 操作门面、声明式四阶分布式锁、双模轻量延时队列、Pub/Sub 广播引擎、业务并发流控、原子流水号发号与带随机抖动防雪崩的工业级 `RedisCacheManager`。

---

## 0. 特性与公共 API 全景速查矩阵

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **基础运维** | 自动装配与主配置 | `RedisProperties` / `RedisAutoConfiguration` | `ateng.boot.redis.enabled`<br>`ateng.boot.redis.key-prefix` | 统管双核装配开关与全局统一 Key 命名前缀隔离 | `RedisAutoConfigurationTest` |
| **数据操作** | 全能操作门面 | `RedisUtil` | `ateng.boot.redis.key-prefix` | `hasKey(key)` / `expire(key, duration)` / `scanKeys(pattern, count)` / `get(key, clazz)` / `set(key, val, duration)` / `hScan` / `executePipelined` | `RedisUtilTest` |
| **分布式锁** | 声明式分布式锁 | `@DistributedLock` / `DistributedLockAspect` | `ateng.boot.redis.lock.enabled`<br>`ateng.boot.redis.lock.prefix`<br>`ateng.boot.redis.lock.wait-time`<br>`ateng.boot.redis.lock.lease-time`<br>`ateng.boot.redis.lock.slow-threshold` | `@DistributedLock(key, keys, mode, waitTime, leaseTime, timeUnit, message)`：支持 SpEL、四阶锁模式、看门狗自动续期、防误删释放与慢锁审计 | `DistributedLockAspectTest`<br>`SampleRedisIntegrationTest` |
| **分布式锁** | 编程式锁受管模板 | `DistributedLockTemplate` | `ateng.boot.redis.lock.wait-time`<br>`ateng.boot.redis.lock.lease-time`<br>`ateng.boot.redis.lock.slow-threshold` | `execute(key, supplier)` / `execute(keys, mode, wait, lease, unit, supplier, fallback)` / `tryExecute(key, action)`：闭包内业务异常无损透传 | `DistributedLockTemplateTest` |
| **分布式锁** | 静态锁操作门面 | `RedisLockUtil` | `ateng.boot.redis.lock.*` | `RedisLockUtil.execute(key, supplier)` / `RedisLockUtil.tryExecute(key, action)`：静态上下文无需注入 Spring Bean 一键安全加锁 | `RedisLockUtilTest`<br>`SampleRedisIntegrationTest` |
| **分布式锁** | 锁模式枚举与闭包 | `LockMode` / `LockSupplier<T, E>` / `LockAction<E>` | - | `REENTRANT`, `FAIR`, `READ`, `WRITE`；支持受检与非受检异常穿透声明 | `DistributedLockTemplateTest` |
| **延时队列** | 编程式延时任务 | `RedisDelayedQueue` | `ateng.boot.redis.delay-queue.enabled` | `offer(queue, msg, delay)` / `poll(queue, clazz)` / `take(queue, clazz)` / `remove(queue, msg)` / `destroy()`：基于 Redisson 原生轻量延迟队列封装 | `RedisDelayedQueueTest` |
| **延时队列** | 声明式消费监听 | `@DelayedQueueListener` / `DelayedQueueBootstrap` | `ateng.boot.redis.delay-queue.retry-times`<br>`ateng.boot.redis.delay-queue.retry-delay` | `@DelayedQueueListener(queue, concurrency)`：自动注册消费线程、指数/固定退避重试、上下文隔离与平滑优雅停机 | `DelayedQueueBootstrapTest` |
| **广播订阅** | 主题广播发布者 | `RedisMessagePublisher` | `ateng.boot.redis.pub-sub.enabled` | `publish(channel, message)` / `subscribe(listener, channel)` / `unsubscribe(listener, channel)`：跨节点广播通知与动态订阅 | `RedisMessagePublisherTest` |
| **广播订阅** | 声明式频道监听 | `@ChannelTopicListener` | `ateng.boot.redis.pub-sub.enabled` | `@ChannelTopicListener(topic)`：自动装配 Spring Data Redis `MessageListener`，反序列化载荷推送至目标方法 | `RedisMessagePublisherTest` |
| **原子发号** | 结构化发号与自增 | `RedisAtomicUtil` | `ateng.boot.redis.key-prefix` | `generateDailySeq(bizPrefix, length)`：跨天自重置且带 48h TTL 防泄露流水号；`incrWithExpire(key, delta, duration)` | `RedisAtomicUtilTest` |
| **并发流控** | 滑动窗口严格限流 | `RedisAtomicUtil` | - | `isAllowedBySlidingWindow(key, maxLimit, window)`：基于 ZSet 毫秒级时间戳实现精准滑动窗口防突刺 | `RedisAtomicUtilTest` |
| **并发流控** | 分布式令牌桶流控 | `RedisRateLimiterClient` | - | `tryAcquire(key, rate, interval)` / `tryAcquire(key, rate, interval, permits, timeout)` / `acquire` / `delete`：基于 Redisson 令牌桶平滑流控 | `RedisRateLimiterClientTest` |
| **缓存增强** | 多级 TTL 缓存管理 | `RedisCacheManager` / `RedisCacheAutoConfiguration` | `ateng.boot.redis.cache.enabled`<br>`ateng.boot.redis.cache.default-ttl`<br>`ateng.boot.redis.cache.expires` | 个性化为不同 `@Cacheable(value = "...")` 声明独立 TTL；支持空值防穿透 | `RedisCacheAutoConfigurationTest` |
| **缓存增强** | 随机抖动防雪崩 | `JitterTtlFunction` | `ateng.boot.redis.cache.jitter-enabled`<br>`ateng.boot.redis.cache.max-jitter` | 实现 `RedisCacheWriter.TtlFunction`，在基准 TTL 上叠加 0 ~ `maxJitter` 动态偏移量打散失效时刻 | `RedisCacheAutoConfigurationTest` |
| **序列化** | 纯净安全序列化工厂 | `RedisSerializerFactory` | - | `createSafeObjectMapper()` / `jsonSerializer(clazz)` / `stringSerializer()`：无 `@class` 多态 RCE 漏洞，内置 JSR-310 与 Long 精度保护 | `RedisSerializerFactoryTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-redis` 坚持双核融合架构，摒弃“Spring Data Redis 与 Redisson 二选一”的传统割裂思维，同时规避了官方 `redisson-spring-boot-starter` 强行覆盖官方 `RedisConnectionFactory` 带来的隐式冲突与序列化漂移。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-redis</artifactId>
</dependency>
```

---

## 2. 双核架构与安全序列化不变量

### 场景痛点
默认 Spring Data Redis 序列化方案要么使用 JDK 原生二进制（无法跨语言排查、性能低下），要么使用 GenericJackson2JsonRedisSerializer / DefaultTyping 自动写入 `@class` 类全限定名属性，极易引发严重的多态反序列化 RCE 安全漏洞，且在重构类路径时导致历史缓存全部反序列化崩溃。

### 双核架构拓扑
```
┌────────────────────────────────────────────────────────────────────────┐
│                          业务应用与 Controller / Service 层             │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │
           ┌─────────────────────────┴─────────────────────────┐
           ▼                                                   ▼
┌──────────────────────┐                           ┌──────────────────────┐
│  Spring Data Redis   │                           │       Redisson       │
│  (Lettuce 高性能驱动) │                           │   (原生分布式协调内核) │
├──────────────────────┤                           ├──────────────────────┤
│ • RedisTemplate      │                           │ • RedissonClient     │
│ • RedisUtil (全能门面)│                           │ • @DistributedLock  │
│ • RedisAtomicUtil    │                           │ • RedisDelayedQueue  │
│ • RedisCacheManager  │                           │ • RRateLimiter 流控  │
└──────────┬───────────┘                           └──────────┬───────────┘
           │                                                   │
           └─────────────────────────┬─────────────────────────┘
                                     ▼
                ┌────────────────────────────────────────┐
                │ 统一纯净安全 JSON 序列化器                │
                │ (零 @class 污染 / JSR-310 / Long精度保护)│
                └────────────────────────────────────────┘
```

### 统一序列化规范
- **Key 序列化**：统一采用 `StringRedisSerializer`，保证所有键名在 Redis 控制台原生可读；
- **Value 序列化**：统一采用无 `@class` 污染的纯净 `Jackson2JsonRedisSerializer`，集成 Java 8 日期模块（JSR-310，默认格式 `yyyy-MM-dd HH:mm:ss`）、统一东八区时区，并将超出 JavaScript 安全整数范围的 Long/BigInteger 序列化为 String 防前端精度丢失。

---

## 3. 全能 RedisUtil 操作门面与安全游标扫描

### 场景痛点
生产环境严禁直接调用 `KEYS *`、`HKEYS` 或 `SMEMBERS`，当集合包含百万级大 Key 时会导致单线程 Redis 发生秒级阻塞停机。`RedisUtil` 彻底封装了游标分批扫描算法，对外暴露安全流式 API。

### 核心代码示例
```java
@Service
public class ProductCacheService {

    @Autowired
    private RedisUtil redisUtil;

    public void demoOperations() {
        // 1. 基础 String 操作与过期时间
        redisUtil.set("product:1001", new ProductVO(1001L, "手机"), Duration.ofMinutes(30));
        ProductVO product = redisUtil.get("product:1001", ProductVO.class);

        // 2. Hash 结构与安全游标迭代
        redisUtil.hSet("cart:user:888", "item:1001", 2);
        redisUtil.hScan("cart:user:888", "*", 100, entry -> {
            log.info("商品: {}, 数量: {}", entry.getKey(), entry.getValue());
        });

        // 3. 安全扫描指定前缀的 Key（底层 SCAN 游标分批拉取，杜绝阻塞 Redis）
        Set<String> matchedKeys = redisUtil.scanKeys("order:20261016:*", 500);

        // 4. 高效管道流水线批量执行 (Pipeline)
        List<Object> results = redisUtil.executePipelined(connection -> {
            for (int i = 0; i < 100; i++) {
                connection.stringCommands().incr(("counter:" + i).getBytes());
            }
        });
    }
}
```

### 预期控制台与 Redis 存储呈现
```json
// Redis 中实际存储的 Value（无 @class 冗余字段）:
{
  "id": "1001",
  "name": "手机",
  "createTime": "2026-10-16 18:00:00"
}
```

---

## 4. 双模一体化分布式锁 (`@DistributedLock` & `RedisLockUtil` & `DistributedLockTemplate`)

### 场景痛点
手动调用分布式锁容易发生死锁、异常时未在 `finally` 块释放、锁重入计数紊乱、锁超时后误删其他线程持有的锁，以及并发大事务锁持有时间过长拖垮数据库。此外，注解加锁强制要求抽取独立 Spring Bean 方法，对于类内部小代码块、定时任务、异步多线程或静态工具类场景，直接调用原生 Redisson 极易写错 `try-finally` 造成锁泄露，且缺乏全局锁前缀隔离与慢锁可观测审计。

### 特性矩阵
- **双模同构内核**：声明式切面与编程式门面均全量委托 `DistributedLockTemplate`，保证锁前缀拼接、看门狗自动续期、安全持锁释放与慢锁审计 100% 规则自洽；
- **极简静态门面 (`RedisLockUtil`)**：无需注入 Spring Bean，在任意上下文一行代码安全执行闭包加锁；
- **无损异常穿透**：定制 `LockSupplier<T>` 与 `LockAction` 声明 `throws Throwable`，闭包内自由抛出业务异常，模板安全释放锁后原样透传，杜绝多余的 `try-catch` 包装；
- **弹性容灾机制**：支持争锁超时降级闭包 `fallback` 以及默认 0 等待即时非阻塞试探 `tryExecute`（支持自定义等待时长）；
- **四阶锁模式与多键联锁**：支持可重入锁 (`REENTRANT`)、公平锁 (`FAIR`)、共享读锁 (`READ`)、排他写锁 (`WRITE`) 以及跨资源原子联锁 (`MultiLock`，自动键去重)；
- **智能租期守护**：缺省 `leaseTime = -1` 自动启用 Redisson 看门狗 (Watchdog) 30s 自动续期（长事务生产推荐）；指定具体租期则强制超时熔断防死锁（注意：显式指定具体租期将禁用看门狗自动延期）；
- **慢锁告警网关**：锁持有耗时超过阈值（默认 3 秒）自动输出 WARN 级别慢锁审计日志。

### 核心代码示例

#### 范式 A：声明式注解加锁 (`@DistributedLock`)
```java
@Service
public class OrderLockService {

    /**
     * 下单扣减库存：排他可重入锁，等待 3 秒，最长持有 10 秒
     */
    @DistributedLock(
            key = "'order:create:' + #userId + ':' + #productId",
            mode = LockMode.REENTRANT,
            waitTime = 3,
            leaseTime = 10,
            timeUnit = TimeUnit.SECONDS,
            message = "当前排队人数较多，请稍后重试"
    )
    public String createOrder(Long userId, Long productId) {
        // 业务逻辑执行...
        return "SUCCESS";
    }

    /**
     * 数据读取：共享读锁，高并发读不互斥
     */
    @DistributedLock(key = "'doc:' + #docId", mode = LockMode.READ)
    public DocumentVO readDocument(Long docId) {
        return loadDoc(docId);
    }
}
```

#### 范式 B：编程式模板与静态门面加锁 (`RedisLockUtil`)
```java
@Service
public class OrderPaymentService {

    /**
     * 1. 最简调用：默认排他锁 + 看门狗自动续期 + 争锁超时快速熔断 BusinessException(429)
     */
    public void processPayment(String orderId) {
        RedisLockUtil.execute("order:pay:" + orderId, () -> {
            updatePayStatus(orderId);
        });
    }

    /**
     * 2. 带返回值与异常无损穿透：
     */
    public String deductStock(String skuId, int count) {
        return RedisLockUtil.execute("stock:" + skuId, () -> {
            return stockService.doDeduct(skuId, count);
        });
    }

    /**
     * 3. 弹性降级回调（争锁超时不抛异常，返回兜底内容）：
     */
    public String snatchCoupon(String couponId, Long userId) {
        return RedisLockUtil.execute("coupon:" + couponId,
                () -> couponService.doSnatch(couponId, userId),
                () -> "活动太火爆了，优惠券已被抢光或请稍后重试"
        );
    }

    /**
     * 4. 布尔试探模式（定时任务或幂等分发试跑）：
     */
    public void runJob() {
        boolean executed = RedisLockUtil.tryExecute("job:sync_daily", () -> {
            doSyncData();
        });
        if (!executed) {
            log.info("已有其他节点在执行同步任务，本次跳过");
        }
    }
}
```

### 预期日志呈现
```text
2026-10-16 18:20:10.123 DEBUG [http-nio-8080-exec-1] [X-Trace-Id: 104523...] i.g.a.b.r.l.t.DistributedLockTemplate : [DistributedLock] 加锁成功: keys=[lock:order:pay:ORD_888], mode=REENTRANT
2026-10-16 18:20:13.567 WARN  [http-nio-8080-exec-1] [X-Trace-Id: 104523...] i.g.a.b.r.l.t.DistributedLockTemplate : [DistributedLock] 检测到慢分布式锁持锁: keys=[lock:order:pay:ORD_888], mode=REENTRANT, holdCost=3444ms, slowThreshold=3000ms
2026-10-16 18:20:13.568 DEBUG [http-nio-8080-exec-1] [X-Trace-Id: 104523...] i.g.a.b.r.l.t.DistributedLockTemplate : [DistributedLock] 释放分布式锁成功: keys=[lock:order:pay:ORD_888]
```

---

## 5. 轻量可靠延时队列 (`RedisDelayedQueue`)

### 场景痛点
在超时自动取消订单、订单未支付催付提醒等延时场景中，直接轮询数据库性能低下，而为了几分钟的轻量延时任务专门引入 RocketMQ、RabbitMQ 或 Kafka 又极大增加了运维复杂度与架构成本。

### 双模驱动范式
1. **编程式发送**：调用 `RedisDelayedQueue.offer(queueName, payload, delay)` 发送延时消息；
2. **声明式消费**：在 Spring Bean 方法上标记 `@DelayedQueueListener(queueName)` 自动启动后台线程消费，支持并发线程数与类型自动反序列化。

### 核心代码示例
```java
@Service
public class OrderDelayService {

    @Autowired
    private RedisDelayedQueue delayedQueue;

    // 1. 发送 30 分钟延时消息
    public void scheduleOrderCancel(Long orderId) {
        OrderDelayPayload payload = new OrderDelayPayload(orderId, System.currentTimeMillis());
        delayedQueue.offer("order_cancel_queue", payload, Duration.ofMinutes(30));
    }

    // 2. 声明式监听延时队列到期消费
    @DelayedQueueListener(queue = "order_cancel_queue", concurrency = 2)
    public void handleOrderTimeout(OrderDelayPayload payload) {
        log.info("收到超时订单取消通知: orderId={}", payload.getOrderId());
        // 执行关单退库存逻辑...
    }
}
```

### 预期控制台呈现
```text
2026-10-16 18:00:00.000 INFO [main] OrderDelayService : 发送订单延时取消任务: orderId=1001, 延迟 30m
... (30 分钟后) ...
2026-10-16 18:30:00.012 INFO [delayed-queue-order_cancel_queue-1] OrderDelayService : 收到超时订单取消通知: orderId=1001
```

---

## 6. 广播发布与订阅引擎 (Pub/Sub)

### 场景痛点
在单体集群部署中，某个节点更新了本地 JVM 缓存或配置后，需要即时同步给所有其他集群节点刷新内存，避免数据不一致。

### 核心代码示例
```java
@Service
public class SystemConfigPublisher {

    @Autowired
    private RedisMessagePublisher messagePublisher;

    public void notifyConfigReload(String configKey) {
        messagePublisher.publish("sys_config_channel", new ConfigReloadEvent(configKey));
    }
}

@Component
public class SystemConfigSubscriber {

    @ChannelTopicListener(topic = "sys_config_channel")
    public void onConfigReload(ConfigReloadEvent event) {
        log.info("收到配置广播刷新通知: key={}", event.getConfigKey());
        LocalCache.invalidate(event.getConfigKey());
    }
}
```

---

## 7. 业务并发流控与原子发号器

### 场景痛点
- 手写递增 Key 生成订单号时，缺少日期跨天自重置导致流水号无限增长膨胀；
- 秒杀接口高频刷接口时，传统计数器窗口存在临界突刺击穿问题。

### 核心代码示例
```java
@Service
public class OrderNumberService {

    @Autowired
    private RedisAtomicUtil redisAtomicUtil;

    @Autowired
    private RedisRateLimiterClient rateLimiterClient;

    /**
     * 1. 生成按日自重置的 18 位结构化流水号 (前缀 + yyyyMMdd + 6位递增流水号)
     * 底层通过原子 Lua 脚本绑定自增与 48 小时 TTL 过期时间，杜绝孤儿 Key 永久泄漏
     * 例如: ORD20261016000001
     */
    public String generateDailyOrderNo() {
        return redisAtomicUtil.generateDailySeq("ORD", 6);
    }

    /**
     * 2. 基于 Redisson 令牌桶平滑流控（1 秒最多放行 10 个请求）
     */
    public void processPayment() {
        boolean acquired = rateLimiterClient.tryAcquire("rate:payment", 10, Duration.ofSeconds(1), Duration.ofMillis(200));
        if (!acquired) {
            throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS, "支付排队人数较多，请稍候");
        }
        // 执行支付动作...
    }

    /**
     * 3. 基于 ZSet 毫秒滑动窗口严格限流（最近 60 秒内同一用户最多发送 5 条短信）
     */
    public boolean checkSmsLimit(String phone) {
        return redisAtomicUtil.isAllowedBySlidingWindow("sms:limit:" + phone, 5, Duration.ofSeconds(60));
    }
}
```

---

## 8. 工业级增强缓存管理器 (`RedisCacheManager`)

### 场景痛点
- 默认 Spring Cache 对所有缓存统配相同过期时间，无法针对基础字典（长缓存）与动态状态（短缓存）差异化配置；
- 查询数据库不存在的数据时不缓存，恶意请求引发缓存穿透击穿数据库；
- 大量数据批量写入缓存时设置了相同 TTL，在同一时刻集中过期引发严重的缓存雪崩。

### 增强解决方案
1. **分 Cache 个性化 TTL**：通过 YAML 配置即可为每个 `@Cacheable(value = "...")` 指定专属过期时间；
2. **防穿透空对象缓存**：默认启用空值缓存，防止空 Key 重复穿透；
3. **原生随机抖动防雪崩 (`JitterTtlFunction`)**：实现 Spring Data Redis 3.3.x 原生 `RedisCacheWriter.TtlFunction`，在配置的基准 TTL 基础上自适应随机叠加抖动偏移量，彻底打散热点失效时间。

### 核心配置与使用示例
```yaml
ateng:
  boot:
    redis:
      cache:
        enabled: true
        default-ttl: 30m
        jitter-enabled: true
        max-jitter: 5m
        cache-null-values: true
        expires:
          sys_dict: 24h
          user_profile: 2h
          sms_code: 5m
```

```java
@Service
public class DictService {

    // 自动应用 24h + 0~5m 随机抖动失效时间，杜绝雪崩与穿透
    @Cacheable(value = "sys_dict", key = "#dictCode")
    public List<DictItemVO> getDict(String dictCode) {
        return queryFromDatabase(dictCode);
    }
}
```

---

## 9. 完整 YAML 配置属性清单

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password: ""
      database: 0
      timeout: 3000ms

ateng:
  boot:
    redis:
      enabled: true                     # 是否启用 Redis 增强基础设施 (默认 true)
      key-prefix: ""                    # 全局 Redis 键统一命名前缀 (默认空，不加前缀)
      lock:
        enabled: true                   # 是否启用声明式分布式锁切面 (默认 true)
        prefix: "lock:"                 # 分布式锁 Key 统配全局前缀 (默认 "lock:")
        wait-time: 3s                   # 默认获取锁最大阻塞等待时长 (默认 3s，支持 3s、3000ms 等标准 Duration 格式)
        lease-time: -1ms                # 默认持锁租约超时时长 (默认 -1ms 表示启用看门狗自动续期，亦可指定 10s 等固定时长)
        time-unit: SECONDS              # 默认时间单位 (默认 SECONDS，向后兼容保留)
        slow-threshold: 3s              # 慢锁监控告警阈值 (默认 3s，支持 3s、3000ms 等 Duration 格式，亦兼容 slow-threshold-ms 与 slow-lock-threshold)
      delay-queue:
        enabled: true                   # 是否启用延时队列支持 (默认 true)
        retry-times: 3                  # 延时消费失败最大重试次数 (默认 3)
        retry-delay: 3s                 # 延时消费失败重试间隔 (默认 3s)
      pub-sub:
        enabled: true                   # 是否启用 Pub/Sub 广播引擎 (默认 true)
      cache:
        enabled: true                   # 是否启用增强缓存管理器 (默认 true)
        default-ttl: 30m                # 缓存默认生存时间 (默认 30m)
        jitter-enabled: true            # 是否启用随机抖动防雪崩 (默认 true)
        max-jitter: 60s                 # 随机抖动最大时间跨度 (默认 60s)
        cache-null-values: true         # 是否缓存 null 防穿透 (默认 true)
        null-ttl: 5m                    # 空对象防穿透独立短过期时间 (默认 5m)
        expires:                        # 针对不同 CacheName 的个性化 TTL (`Map<String, Duration>`)
          short_cache: 10m
          long_cache: 24h
      redisson:
        config: ""                      # 可选自定义 Redisson 外部配置文件路径 (如 classpath:redisson.yaml)
        threads: 0                      # Redisson 通用线程池大小 (默认 0，表示采用 Redisson 核心数*2 默认值)
        netty-threads: 0                # Redisson Netty 线程池大小 (默认 0，表示采用 Redisson 默认值)
```

---

## 10. 生产扩展与高阶定制 (Customization & Extensibility)

`ateng-boot-redis` 坚持 `@ConditionalOnMissingBean` 缺省守卫铁律，支持企业级生产场景深度定制与平滑无缝接管：

### 10.1 自定义 RedissonClient 接管
当现有自动化配置无法满足集群特异性参数（如特定 SSL 证书、自定义编解码管道或监控指标导出）时，下游应用仅需声明同类型 Bean 即可 100% 接管 Redisson 装配：

```java
@Configuration(proxyBeanMethods = false)
public class CustomRedissonConfiguration {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setConnectionMinimumIdleSize(10)
                .setConnectionPoolSize(64);
        return Redisson.create(config);
    }
}
```

### 10.2 编程式延时队列平滑接管
`RedisDelayedQueue` 既支持作为 Spring 托管单例由容器注入，也支持在离线测试或轻量客户端中通过构造器原生初始化：

```java
RedisDelayedQueue delayedQueue = new RedisDelayedQueue(redissonClient, objectMapper);
delayedQueue.offer("custom_queue", new TaskPayload(1001L), Duration.ofMinutes(10));
```

---

## 11. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 11.1 分布式锁防御性释放与防误删不变量
- **状态判断释放范式**：在 `DistributedLockTemplate.safeUnlockAndAudit` 释放锁前，强制通过 `rLock.isHeldByCurrentThread()` 校验当前线程是否真正持有锁；若因业务耗时严重超出 `leaseTime` 导致锁已被 Redis 自动过期并由其他节点的线程获取，当前线程会主动跳过 `unlock()` 并记录 WARN 日志，**绝对杜绝误删其他线程持有的锁造成级联并发穿透**。
- **次生异常彻底隔离**：锁释放逻辑在 `finally` 块中受到全面保护并捕获底层网络断开等偶发异常，确保业务方法本身抛出的原始业务异常（如业务断言失败、校验未通过等）不被次生异常冲刷掩盖。

### 11.2 看门狗自动续期与固定租期选择策略
- **`leaseTime <= 0`（推荐）**：默认 `leaseTime = -1ms`，此时 Redisson 会自动激活看门狗（Watchdog，默认每隔 10s 自动续期至 30s）。适合绝大多数长事务与复杂业务逻辑，只要业务节点存活即可确保持锁不中断，进程宕机后 30s 自动失效。
- **`leaseTime > 0`（显式固定时长）**：一旦显式指定具体租期（例如 `leaseTime = 10`），Redisson 将**彻底禁用看门狗自动延期**。若业务执行耗时超出指定租期，锁将强制自动释放；请确保设置的租期显著大于业务最大可能耗时。

### 11.3 延时队列生命周期消费与重试策略
- **弹性重试与错误隔离**：`DelayedQueueBootstrap` 内置异常受控重试（默认最多重试 3 次，间隔 3s）；若达到重试上限仍未成功，将打印 ERROR 日志并受控丢弃，杜绝因单条毒药消息死循环阻塞整个队列消费。
- **平滑优雅停机**：监听容器实现了 `DisposableBean`，在 JVM 或 Spring 容器销毁时首先停止拉取新消息，等待在途任务执行完成并注销内部阻塞循环，保障零消息悬挂。

