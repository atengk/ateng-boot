# ateng-boot-redis 分布式协同与缓存增强套件

> 提供 Spring Data Redis (Lettuce) 与 Redisson 双核融合底座、无 `@class` 污染纯净安全 JSON 序列化、全能 `RedisUtil` 操作门面、声明式四阶分布式锁、双模轻量延时队列、Redis 5.0+ Stream 流式消息、非 Web 分布式防重幂等、受管分布式信号量、原子 Lua 脚本执行器、Pub/Sub 广播引擎、两级缓存、工业级缓存三防模板及连接池治理。

---

## 0. 特性与公共 API 全景速查矩阵

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **基础运维** | 自动装配与主配置 | `RedisProperties` / `AtengRedisAutoConfiguration` | `ateng.boot.redis.enabled`<br>`ateng.boot.redis.key-prefix` | 统管双核装配开关与全局统一 Key 命名前缀隔离 | `AtengRedisAutoConfigurationTest` |
| **基础运维** | Redisson 连接保活与连接池治理 | `RedissonClient` / `AtengRedisAutoConfiguration` | `ateng.boot.redis.redisson.ping-connection-interval`<br>`ateng.boot.redis.redisson.connection-pool-size`<br>`ateng.boot.redis.redisson.connection-minimum-idle-size`<br>`ateng.boot.redis.redisson.retry-attempts`<br>`ateng.boot.redis.redisson.retry-interval`<br>`ateng.boot.redis.redisson.threads`<br>`ateng.boot.redis.redisson.netty-threads`<br>`ateng.boot.redis.redisson.config` | TCP 心跳 PING 保活机制（默认 30s）防御云原生 NAT 网关静默切断连接；单机/集群/哨兵连接池容量与超时重试统管 | `AtengRedisAutoConfigurationTest` |
| **数据操作** | 全能操作门面 | `RedisUtil` | `ateng.boot.redis.key-prefix` | `hasKey(key)` / `expire(key, duration)` / `scanKeys(pattern, count)` / `get(key, clazz)` / `set(key, val, duration)` / `buildKey(key)` / `hScan` / `executePipelined`：纯游标分批拉取彻底杜绝 `keys *` 阻塞 | `RedisUtilTest`<br>`SampleRedisIntegrationTest` |
| **序列化** | 纯净安全序列化工厂 | `RedisSerializerFactory` | - | `createSafeObjectMapper()` / `createJsonSerializer()` / `stringSerializer()`：无 `@class` 多态 RCE 漏洞，内置 JSR-310 与 Long 精度保护 | `RedisSerializerFactoryTest` |
| **分布式锁** | 声明式分布式锁 | `@DistributedLock` / `DistributedLockAspect` | `ateng.boot.redis.lock.enabled`<br>`ateng.boot.redis.lock.prefix`<br>`ateng.boot.redis.lock.wait-time`<br>`ateng.boot.redis.lock.lease-time`<br>`ateng.boot.redis.lock.time-unit`<br>`ateng.boot.redis.lock.slow-threshold` | `@DistributedLock(key, keys, mode, waitTime, leaseTime, timeUnit, message)`：支持 SpEL、四阶锁模式、看门狗自动续期、防误删释放与慢锁审计 | `DistributedLockAspectTest`<br>`SampleRedisIntegrationTest` |
| **分布式锁** | 编程式锁受管模板 | `DistributedLockTemplate` | `ateng.boot.redis.lock.wait-time`<br>`ateng.boot.redis.lock.lease-time`<br>`ateng.boot.redis.lock.slow-threshold` | `execute(key, supplier)` / `execute(key, supplier, fallback)` / `execute(keys, mode, wait, lease, unit, supplier, fallback)` / `tryExecute(key, action)`：闭包内业务异常无损透传 | `DistributedLockTemplateTest`<br>`SampleRedisIntegrationTest` |
| **分布式锁** | 静态锁操作门面 | `RedisLockUtil` | `ateng.boot.redis.lock.*` | `RedisLockUtil.execute(key, supplier)` / `RedisLockUtil.execute(key, supplier, fallback)` / `RedisLockUtil.tryExecute(key, action)`：静态上下文无需注入 Spring Bean 一键安全加锁 | `RedisLockUtilTest`<br>`SampleRedisIntegrationTest` |
| **分布式锁** | 锁模式枚举与闭包 | `LockMode` / `LockSupplier<T, E>` / `LockAction<E>` | - | `REENTRANT`, `FAIR`, `READ`, `WRITE`；支持受检与非受检异常穿透声明 | `DistributedLockTemplateTest`<br>`SampleRedisIntegrationTest` |
| **延时队列** | 编程式延时任务 | `RedisDelayedQueue` | `ateng.boot.redis.delay-queue.enabled`<br>`ateng.boot.redis.delay-queue.dead-letter-enabled`<br>`ateng.boot.redis.delay-queue.dead-letter-suffix` | `offer(queue, msg, delay)` / `poll(queue, clazz)` / `take(queue, clazz)` / `remove(queue, msg)` / `destroy()`<br>DLQ 运维：`peekDeadLetter` / `pollDeadLetter` / `getDeadLetterSize` / `clearDeadLetter` / `sendToDeadLetter` | `RedisDelayedQueueTest`<br>`SampleRedisIntegrationTest` |
| **延时队列** | 声明式消费监听 | `@DelayedQueueListener` / `DelayedQueueBootstrap` | `ateng.boot.redis.delay-queue.retry-times`<br>`ateng.boot.redis.delay-queue.retry-delay` | `@DelayedQueueListener(queue, concurrency)`：受管 `ThreadPoolTaskExecutor` 并发驱动、`ContextCopyingTaskDecorator` 上下文透传、重试退避与平滑优雅停机 | `DelayedQueueBootstrapTest`<br>`SampleRedisIntegrationTest` |
| **延时队列** | 死信流转与失败 SPI | `DeadLetterMessage<T>` / `DelayedQueueFailureHandler` | `ateng.boot.redis.delay-queue.dead-letter-enabled`<br>`ateng.boot.redis.delay-queue.dead-letter-suffix` | `onFailed(deadLetterMessage, cause)`：重试耗尽后封装完整因果堆栈转入死信队列，默认 `LoggingDelayedQueueFailureHandler` 告警，支持自定义 SPI 无缝接管 | `DelayedQueueBootstrapTest`<br>`AtengRedisAutoConfigurationTest` |
| **流式消息** | Redis 5.0+ Stream 消息流引擎 | `RedisStreamTemplate` | `ateng.boot.redis.stream.enabled`<br>`ateng.boot.redis.stream.poll-timeout`<br>`ateng.boot.redis.stream.batch-size`<br>`ateng.boot.redis.stream.retry-times`<br>`ateng.boot.redis.stream.dlq-suffix`<br>`ateng.boot.redis.stream.instance-id` | `send(stream, payload)` / `createGroup(stream, group)` / `readGroup(stream, group, consumer, count, timeout)` / `ack(stream, group, recordIds)` / `getPendingMessages(stream, group, count)` / `claim(stream, group, newConsumer, minIdle, recordIds)` / `size(stream)` / `trim(stream, count)` / `trim(stream, count, approximate)`：支持可靠流生产、消费组运维、PEL 待决积压排查与孤儿消息认领 | `RedisStreamTemplateTest`<br>`SampleRedisIntegrationTest` |
| **流式消息** | 声明式 Stream 消费组监听 | `@RedisStreamListener` / `StreamBootstrap` | `ateng.boot.redis.stream.*` | `@RedisStreamListener(stream, group, concurrency, autoAck, autoCreateGroup)`：受管 `ThreadPoolTaskExecutor` 异步消费、自动创建消费组、自动/手动 ACK、MDC `traceId` 透传与优雅停机 | `StreamBootstrapTest`<br>`SampleRedisIntegrationTest` |
| **流式消息** | Stream 失败补偿与死信流 SPI | `StreamDeadLetterMessage<T>` / `StreamFailureHandler` | `ateng.boot.redis.stream.retry-times`<br>`ateng.boot.redis.stream.dlq-suffix` | `onFailed(deadLetterMessage, cause)`：重试耗尽后封装完整因果链转入死信流 `${stream}:dlq`，默认 `LoggingStreamFailureHandler` 告警，支持自定义 SPI 业务告警与持久化 | `StreamBootstrapTest`<br>`AtengRedisAutoConfigurationTest` |
| **业务防重** | 声明式业务防重幂等 | `@RedisIdempotent` / `RedisIdempotentAspect` | `ateng.boot.redis.idempotent.enabled`<br>`ateng.boot.redis.idempotent.prefix`<br>`ateng.boot.redis.idempotent.default-expire`<br>`ateng.boot.redis.idempotent.delete-on-failure` | `@RedisIdempotent(key, expire, message, deleteOnFailure)`：支持 SpEL 动态解析业务唯一键、防重复提交/重复消费、业务异常时由 `deleteOnFailure=true` 自动释放凭据以支持上游重试 | `RedisIdempotentAspectTest`<br>`SampleRedisIntegrationTest` |
| **业务防重** | 编程式防重受管模板 | `RedisIdempotentTemplate` | `ateng.boot.redis.idempotent.*` | `execute(key, supplier)` / `execute(key, timeout, supplier)` / `execute(key, timeout, deleteOnFailure, message, supplier)`：函数式闭包受管防重，防重失败阻断并抛出 `BusinessException` | `RedisIdempotentTemplateTest`<br>`SampleRedisIntegrationTest` |
| **业务防重** | 静态防重操作门面 | `RedisIdempotentUtil` | `ateng.boot.redis.idempotent.*` | `RedisIdempotentUtil.execute(key, supplier)` / `RedisIdempotentUtil.execute(key, timeout, supplier)`：静态上下文无需注入 Spring Bean 一键安全执行防重逻辑 | `RedisIdempotentUtilTest`<br>`SampleRedisIntegrationTest` |
| **并发治理** | 分布式受管信号量模板 | `RedisSemaphoreTemplate` | `ateng.boot.redis.key-prefix` | `execute(key, totalPermits, acquirePermits, waitTime, supplier, fallback)` / `tryAcquire(key, permits, timeout)` / `release(key, permits)` / `availablePermits(key)` / `resetPermits(key, newPermits)`：集群全局并发硬阈值控制、自动初始化配额、争锁超时优雅降级与动态扩缩容 | `RedisSemaphoreTemplateTest`<br>`SampleRedisIntegrationTest` |
| **并发治理** | 静态信号量操作门面 | `RedisSemaphoreUtil` | `ateng.boot.redis.key-prefix` | `RedisSemaphoreUtil.execute(key, totalPermits, waitTime, supplier, fallback)` / `RedisSemaphoreUtil.resetPermits(key, newPermits)`：静态上下文轻量并发度治理 | `RedisSemaphoreUtilTest`<br>`SampleRedisIntegrationTest` |
| **脚本执行** | 受管原子 Lua 脚本执行器 | `RedisScriptExecutor` | - | `execute(script, resultType, keys, args)` / `executeClasspath(path, resultType, keys, args)`：自动 SHA1 预编译缓存、EVALSHA 优先执行、底层脚本加载异常安全转译 | `RedisScriptExecutorTest`<br>`SampleRedisIntegrationTest` |
| **广播订阅** | 主题广播发布者 | `RedisMessagePublisher` | `ateng.boot.redis.pub-sub.enabled` | `publish(channel, message)` / `subscribe(listener, channel)` / `unsubscribe(listener, channel)`：跨节点广播通知与动态订阅 | `RedisMessagePublisherTest` |
| **广播订阅** | 声明式频道监听 | `@ChannelTopicListener` / `ChannelTopicBootstrap` | `ateng.boot.redis.pub-sub.enabled` | `@ChannelTopicListener(topics, patterns, concurrency)`：支持 `${...}` 占位符、精确主题与通配模式，支持单参 POJO/String/byte[]/Message、双参及三参多态安全反序列化，受管 `ThreadPoolTaskExecutor` 并发驱动与 MDC `traceId` 透传 | `ChannelTopicBootstrapTest`<br>`SampleRedisIntegrationTest` |
| **布隆过滤** | 分布式布隆过滤器 | `RedisBloomFilter` | `ateng.boot.redis.key-prefix`<br>`ateng.boot.redis.bloom-filters.<name>.expected-insertions`<br>`ateng.boot.redis.bloom-filters.<name>.false-probability` | `tryInit(name, expected, prob)` / `add(name, val)` / `addAll(name, coll)` / `contains(name, val)` / `containsAll(name, coll)` / `count(name)` / `getUsageRate(name)` / `isOverCapacity(name, threshold)` / `delete(name)`：支持依赖注入操作范式、80% 容量安全预警与全局 KeyPrefix 隔离 | `RedisBloomFilterTest`<br>`SampleRedisIntegrationTest` |
| **布隆过滤** | 声明式自动预热调度器 | `BloomFilterBootstrap` | `ateng.boot.redis.bloom-filters.*` | 容器就绪后自动遍历 YAML 中声明的规则，原子幂等调用 `tryInit` 完成预热 | `RedisBloomFilterTest`<br>`AtengRedisAutoConfigurationTest` |
| **布隆过滤** | 静态布隆过滤门面 | `RedisBloomUtil` | `ateng.boot.redis.key-prefix`<br>`ateng.boot.redis.bloom-filters.*` | `tryInit(name, expected, prob)` / `add(name, val)` / `addAll(name, coll)` / `contains(name, val)` / `containsAll(name, coll)` / `count(name)` / `getUsageRate(name)` / `isOverCapacity(name, threshold)` / `delete(name)`：静态上下文开箱即用门面，底层统一委托受管单例 `RedisBloomFilter` | `RedisBloomFilterTest`<br>`SampleRedisIntegrationTest` |
| **原子发号** | 结构化发号与自增 | `RedisAtomicUtil` | `ateng.boot.redis.key-prefix` | `generateDailySeq(bizPrefix, length)`：跨天自重置且带 48h TTL 防泄露流水号；`incrWithExpire(key, delta, duration)` / `compareAndSet(key, expect, update)` / `getAndSet(key, update)` | `RedisAtomicUtilTest`<br>`SampleRedisIntegrationTest` |
| **并发流控** | 滑动窗口严格限流 | `RedisAtomicUtil` | - | `isAllowedBySlidingWindow(key, maxLimit, window)`：基于 ZSet 毫秒级时间戳实现精准滑动窗口防突刺 | `RedisAtomicUtilTest`<br>`SampleRedisIntegrationTest` |
| **并发流控** | 分布式令牌桶流控 | `RedisRateLimiterClient` | - | `tryAcquire(key, rate, interval)` / `tryAcquire(key, rate, interval, permits, timeout)` / `acquire` / `delete`：基于 Redisson 令牌桶平滑流控 | `RedisRateLimiterClientTest`<br>`SampleRedisIntegrationTest` |
| **缓存增强** | 多级 TTL 缓存管理 | `RedisCacheManager` / `AtengRedisAutoConfiguration` | `ateng.boot.redis.cache.enabled`<br>`ateng.boot.redis.cache.default-ttl`<br>`ateng.boot.redis.cache.expires` | 个性化为不同 `@Cacheable(value = "...")` 声明独立 TTL；支持空值防穿透 | `AtengRedisAutoConfigurationTest` |
| **缓存防卫** | 统一缓存执行模板 | `RedisCacheTemplate` | `ateng.boot.redis.cache.enabled`<br>`ateng.boot.redis.cache.default-ttl`<br>`ateng.boot.redis.cache.null-ttl`<br>`ateng.boot.redis.cache.max-jitter`<br>`ateng.boot.redis.cache.jitter-enabled`<br>`ateng.boot.redis.cache.cache-null-values`<br>`ateng.boot.redis.cache.lock-wait-time` | `getOrLoad(key, clazz, loader)` / `getOrLoad(key, typeRef, loader)`：单键 DCL + 互斥锁防击穿 + 短 TTL 空哨兵防穿透 + 随机抖动防雪崩 + 争锁超时降级<br>`batchGetOrLoad(keys, mapper, clazz/typeRef, batchLoader)`：批量 MGET 探测 + 字典序排序锁去重 + 降级自愈回填 + Pipeline 回写彻底杜绝 N+1<br>`get` / `put` / `evict` / `batchEvict` / `hasKey`：对称运维门面 | `RedisCacheTemplateTest`<br>`SampleRedisIntegrationTest` |
| **两级缓存** | 两级缓存核心门面 | `TwoLevelCache` / `DefaultTwoLevelCache` | `ateng.boot.redis.two-level.*`<br>`ateng.boot.redis.key-prefix` | `getName()` / `get(key)` / `get(key, clazz)` / `get(key, loader)` / `put(key, val, ttl)` / `putIfAbsent` / `evict(key)` / `evictLocal(key)` / `clear()` / `clearLocal()`：纳秒级 L1 本地命中、分布式 L2 兜底、空哨兵防穿透与游标安全 scan 清理 | `TwoLevelCacheTest`<br>`SampleRedisIntegrationTest` |
| **两级缓存** | Spring Cache 适配器 | `TwoLevelCacheManager` | `ateng.boot.redis.two-level.enabled`<br>`ateng.boot.redis.two-level.topic`<br>`ateng.boot.redis.two-level.l1.*`<br>`ateng.boot.redis.two-level.l2.*` | `@Cacheable` / `@CacheEvict` 无缝注解驱动、动态按需创建缓存空间、`instanceId` 唯一标识防环、`ateng:cache:evict:topic` 跨节点集中广播协同失效 | `TwoLevelCacheManagerTest`<br>`SampleRedisIntegrationTest` |
| **两级缓存** | L1 内存缓存与退化工厂 | `L1MemoryCacheFactory` / `L1MemoryCache` / `ConcurrentLruMemoryCache` | `ateng.boot.redis.two-level.l1.default-max-size`<br>`ateng.boot.redis.two-level.l1.default-ttl` | 自动探测 Caffeine，存在时启用 W-TinyLFU 高性能缓存，缺失时自动退化为内置 LRU 内存结构并受 `default-max-size` 严格限制防 OOM | `ConcurrentLruMemoryCacheTest`<br>`L1MemoryCacheFactoryTest` |
| **两级缓存** | 跨节点广播失效载荷 | `CacheEvictMessage` | `ateng.boot.redis.two-level.topic` | `cacheName` / `key` / `instanceId` / `clearAll`：跨实例失效广播数据契约 | `TwoLevelCacheTest`<br>`TwoLevelCacheManagerTest` |
| **缓存增强** | 随机抖动防雪崩 | `JitterTtlFunction` | `ateng.boot.redis.cache.jitter-enabled`<br>`ateng.boot.redis.cache.max-jitter` | 实现 `RedisCacheWriter.TtlFunction`，在基准 TTL 上叠加 0 ~ `maxJitter` 动态偏移量打散失效时刻 | `JitterTtlFunctionTest`<br>`AtengRedisAutoConfigurationTest` |

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
│                          业务应用 Service / Component 层                │
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
│ • RedisStreamTemplate│                           │ • RedisSemaphore     │
│ • RedisCacheManager  │                           │ • RedisIdempotent    │
│ • RedisScriptExecutor│                           │ • RRateLimiter 流控  │
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

### 核心代码示例（逆向自 `ScriptExecutionScenarioService`）
```java
@Service
@RequiredArgsConstructor
public class ScriptExecutionScenarioService {

    private final RedisAtomicUtil redisAtomicUtil;

    /**
     * 基于 RedisUtil 安全游标扫描匹配的键集合（底层 SCAN 游标分批拉取，杜绝阻塞 Redis）
     */
    public Set<String> scanKeys(String pattern, long count) {
        Set<String> keys = RedisUtil.scanKeys(pattern, count);
        log.info("[ScriptExecution] 游标扫描完成: pattern={}, count={}, matchedSize={}", pattern, count, keys.size());
        return keys;
    }
}
```

### 预期控制台日志呈现
```text
2026-10-27 16:55:12.158 INFO  [main] [ea52411c7e479cd39e04bbf3f0f9317e] i.g.a.b.s.r.s.ScriptExecutionScenarioService : 游标扫描完成: pattern=sample:user:*, count=50, matchedSize=2
```

---

## 4. 双模一体化分布式锁 (`@DistributedLock` & `RedisLockUtil` & `DistributedLockTemplate`)

### 场景痛点
手动调用分布式锁容易发生死锁、异常时未在 `finally` 块释放、锁重入计数紊乱、锁超时后误删其他线程持有的锁，以及并发大事务锁持有时间过长拖垮数据库。

### 特性矩阵
- **双模同构内核**：声明式切面与编程式门面均全量委托 `DistributedLockTemplate`，保证锁前缀拼接、看门狗自动续期、安全持锁释放与慢锁审计 100% 规则自洽；
- **极简静态门面 (`RedisLockUtil`)**：无需注入 Spring Bean，在任意上下文一行代码安全执行闭包加锁；
- **无损异常穿透**：定制 `LockSupplier<T, E>` 与 `LockAction<E>` 声明 `throws Throwable`，闭包内自由抛出业务异常，模板安全释放锁后原样透传；
- **弹性容灾机制**：支持争锁超时降级闭包 `fallback` 以及默认 0 等待即时非阻塞试探 `tryExecute`；
- **四阶锁模式与多键联锁**：支持可重入锁 (`REENTRANT`)、公平锁 (`FAIR`)、共享读锁 (`READ`)、排他写锁 (`WRITE`) 以及跨资源原子联锁 (`MultiLock`，自动键去重)；
- **智能租期守护**：缺省 `leaseTime = -1` 自动启用 Redisson 看门狗 (Watchdog) 30s 自动续期；指定具体租期则强制超时熔断防死锁；
- **慢锁告警网关**：锁持有耗时超过阈值（默认 3 秒）自动输出 WARN 级别慢锁审计日志。

### 核心代码示例（1:1 逆向自 `StockDeductScenarioService`）
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDeductScenarioService {

    private final DistributedLockTemplate distributedLockTemplate;

    /**
     * 1. 声明式分布式锁扣减库存（防超卖）
     */
    @DistributedLock(key = "'product:' + #productId", waitTime = 3000, leaseTime = 5000, mode = LockMode.REENTRANT)
    public int deductStock(Long productId, int count) {
        log.info("[StockDeduct] 获取分布式锁成功，开始扣减库存: productId={}, count={}", productId, count);
        Integer currentStock = stockTable.get(productId);
        if (currentStock == null || currentStock < count) {
            throw new BusinessException("库存不足，当前库存: " + currentStock + ", 拟扣减: " + count);
        }
        int remaining = currentStock - count;
        stockTable.put(productId, remaining);
        return remaining;
    }

    /**
     * 2. 分布式锁可重入自验（外层注解加锁，内层通过 DistributedLockTemplate 同一线程重入同一锁）
     */
    @DistributedLock(key = "'reentrant:' + #orderId", mode = LockMode.REENTRANT)
    public String executeReentrantBusiness(String orderId) {
        log.info("[StockDeduct] 外层方法获取分布式锁成功: orderId={}", orderId);
        return distributedLockTemplate.execute("reentrant:" + orderId, () -> {
            log.info("[StockDeduct] 内层闭包同一线程重入同一锁成功: orderId={}", orderId);
            return "REENTRANT_SUCCESS_" + orderId;
        });
    }

    /**
     * 3. 分布式锁争锁超时降级演示（利用 fallback 闭包防御并发风暴）
     */
    public String executeWithFallback(String resourceId) {
        return distributedLockTemplate.execute(
                "resource:" + resourceId,
                () -> "NORMAL_SUCCESS_" + resourceId,
                () -> "FALLBACK_TRIGGERED_" + resourceId
        );
    }

    /**
     * 4. 看门狗自动续期长事务业务自验（leaseTime = -1）
     */
    @DistributedLock(key = "'watchdog:' + #taskId", leaseTime = -1)
    public String executeWithWatchdog(String taskId) {
        log.info("[StockDeduct] 激活 Redisson 看门狗持锁执行长事务: taskId={}", taskId);
        return "WATCHDOG_SUCCESS_" + taskId;
    }

    /**
     * 5. 编程式批量多锁执行 (MultiLock)
     */
    public String executeMultiLock(String orderId, Long productId) {
        return distributedLockTemplate.execute(
                List.of("lock:order:" + orderId, "lock:product:" + productId),
                LockMode.REENTRANT,
                3000L,
                5000L,
                TimeUnit.MILLISECONDS,
                () -> "MULTI_LOCK_SUCCESS_" + orderId + "_" + productId
        );
    }
}
```

### 预期控制台呈现
```text
2026-10-27 16:55:12.029 DEBUG [main] [NO-TRACE] i.g.a.b.r.l.t.DistributedLockTemplate    : [DistributedLock] 加锁成功: keys=[lock:product:1001], mode=REENTRANT
2026-10-27 16:55:12.031 INFO  [main] [NO-TRACE] i.g.a.b.s.r.s.StockDeductScenarioService : [StockDeduct] 获取分布式锁成功，开始扣减库存: productId=1001, count=2
2026-10-27 16:55:12.032 DEBUG [main] [NO-TRACE] i.g.a.b.r.l.t.DistributedLockTemplate    : [DistributedLock] 释放分布式锁成功: keys=[lock:product:1001]
```

---

## 5. 轻量可靠延时队列 (`RedisDelayedQueue` & `@DelayedQueueListener`)

### 场景痛点
在超时自动取消订单、订单未支付催付提醒等延时场景中，直接轮询数据库性能低下，而引入 RocketMQ/Kafka 延时队列又极大增加了运维复杂度。

### 核心代码示例（1:1 逆向自 `OrderDelayScenarioService`）
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDelayScenarioService {

    public static final String QUEUE_ORDER_CANCEL = "sample:order:cancel";

    private final RedisDelayedQueue redisDelayedQueue;

    // 1. 编程式投递订单延时取消任务
    public void sendOrderCancelTask(String orderId, Duration delay) {
        OrderDelayPayload payload = new OrderDelayPayload(orderId, "订单未支付超时自动取消", System.currentTimeMillis());
        log.info("[OrderDelay] 编程式投递延时取消任务: queue={}, orderId={}, delay={}", QUEUE_ORDER_CANCEL, orderId, delay);
        redisDelayedQueue.offer(QUEUE_ORDER_CANCEL, payload, delay);
    }

    // 2. 声明式监听延时队列到期消费（支持受管线程池并发驱动）
    @DelayedQueueListener(queue = QUEUE_ORDER_CANCEL, concurrency = 1)
    public void onOrderCancel(OrderDelayPayload payload) {
        log.info("[OrderDelay] 声明式监听到期消费: orderId={}, reason={}", payload.getOrderId(), payload.getReason());
    }

    // 3. 死信队列运维与排查拉取
    public DeadLetterMessage<OrderDelayPayload> pollCancelOrderDeadLetter() {
        return redisDelayedQueue.pollDeadLetter(QUEUE_ORDER_CANCEL, OrderDelayPayload.class);
    }
}
```

### 预期控制台呈现
```text
2026-10-27 16:55:12.499 INFO  [main] [NO-TRACE] i.g.a.b.s.r.s.OrderDelayScenarioService : [OrderDelay] 编程式投递延时取消任务: queue=sample:order:cancel, orderId=ORD_DELAY_999, delay=PT1S
2026-10-27 16:55:13.513 INFO  [:order:cancel-1] [cff82f2353553ebd092601e87767de84] i.g.a.b.s.r.s.OrderDelayScenarioService : [OrderDelay] 声明式监听到期消费: orderId=ORD_DELAY_999, reason=订单未支付超时自动取消
```

---

## 6. Redis 5.0+ Stream 可靠流式消息引擎 (`RedisStreamTemplate` & `@RedisStreamListener`)

### 场景痛点
Pub/Sub 模式虽然轻量，但不支持持久化、不支持 ACK 确认、无法追溯历史消费偏离且缺少消费组集群均衡模型。一旦订阅者短暂网络抖动断线，期间发布的所有通知全部静默丢失。

### 增强解决方案
- **消费组模型与自动创建**：支持分布式消费组（Consumer Group），通过 `@RedisStreamListener(autoCreateGroup = true)` 自动幂等创建目标消费组，多实例集群协同消费分摊负载；
- **显式与自动 ACK 治理**：支持 `autoAck = true`（消费成功由框架自动执行 XACK）以及手动 ACK 模式，保证消息 At-Least-Once 可靠投递；
- **PEL 积压排查与孤儿认领**：`RedisStreamTemplate` 原生提供 `getPendingMessages` 与 `claim` 接口，支持对超时挂起消息实施安全抢占补偿；
- **流长度自动裁剪 (XTRIM)**：支持 `trim(stream, maxLen)` 对流式队列进行物理定长裁剪，防止流历史无限膨胀占用过多内存；
- **死信流转 (DLQ)**：消费重试耗尽后封装 `StreamDeadLetterMessage<T>` 流转至目标死信流（默认 `${stream}:dlq`），同时触发 `StreamFailureHandler` SPI。

### 核心代码示例（1:1 逆向自 `StreamEventScenarioService`）
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamEventScenarioService {

    public static final String STREAM_ORDER_EVENTS = "stream:order:events";
    public static final String GROUP_ORDER_PROCESSOR = "group:order:processor";

    private final RedisStreamTemplate redisStreamTemplate;

    // 1. 发送订单 Stream 事件消息
    public RecordId sendOrderEvent(OrderStreamEvent event) {
        log.info("[StreamEvent] 投递订单流事件: stream={}, event={}", STREAM_ORDER_EVENTS, event);
        return redisStreamTemplate.send(STREAM_ORDER_EVENTS, event);
    }

    // 2. 声明式监听 Stream 消费组消费（自动 XACK 与上下文透传）
    @RedisStreamListener(stream = STREAM_ORDER_EVENTS, group = GROUP_ORDER_PROCESSOR, concurrency = 1, autoAck = true)
    public void onOrderEvent(OrderStreamEvent event) {
        log.info("[StreamEvent] 监听到消费组流事件: orderId={}, eventType={}, amount={}",
                event.getOrderId(), event.getEventType(), event.getAmount());
    }

    // 3. 裁剪 Stream 长度 (XTRIM)
    public Long trimStream(long maxLen) {
        return redisStreamTemplate.trim(STREAM_ORDER_EVENTS, maxLen);
    }
}
```

### 预期控制台呈现
```text
2026-10-27 16:55:12.710 INFO  [main] [NO-TRACE] i.g.a.b.s.r.s.StreamEventScenarioService : [StreamEvent] 投递订单流事件: stream=stream:order:events, event=OrderStreamEvent(orderId=ORD_STREAM_1001, eventType=ORDER_CREATED, amount=199.00)
2026-10-27 16:55:12.725 INFO  [stream:order:events-1] [f821b017b20349bcae2837bc901a88c2] i.g.a.b.s.r.s.StreamEventScenarioService : [StreamEvent] 监听到消费组流事件: orderId=ORD_STREAM_1001, eventType=ORDER_CREATED, amount=199.00
```

---

## 7. 通用业务级分布式防重幂等 (`@RedisIdempotent` & `RedisIdempotentTemplate` & `RedisIdempotentUtil`)

### 场景痛点
在非 Web 链路（MQ 消费者、定时任务批处理、分布式 RPC 调用）中，重复投递与重试是常态。若单纯依赖数据库唯一键，会引发大量的死锁或数据库回滚开销；若手写 Redis `SETNX`，极易发生业务执行失败时未删除防重 Key 导致上游永远无法再次重试的“假死锁”故障。

### 增强解决方案
- **三模一体化支持**：声明式切面 `@RedisIdempotent`、编程式模板 `RedisIdempotentTemplate` 与静态门面 `RedisIdempotentUtil`；
- **SpEL 动态业务键**：支持基于方法入参表达式动态拼装全局防重唯一标识；
- **`deleteOnFailure` 故障自愈机制**：业务成功执行后保留防重 Key 直至 TTL 超时；当业务方法抛出异常时，模板自动识别并**原子删除防重 Key**，确保上游重试机制能够平滑再次触发执行；
- **异常语义清晰**：并发防重冲突时抛出语义明确的 `BusinessException`。

### 核心代码示例（1:1 逆向自 `IdempotentScenarioService`）
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentScenarioService {

    private final RedisIdempotentTemplate redisIdempotentTemplate;

    /**
     * 1. 声明式防重处理订单（deleteOnFailure = true 支持异常自动释放凭据以供重试）
     */
    @RedisIdempotent(key = "'idemp:order:' + #orderId", expire = "30s", deleteOnFailure = true)
    public String processOrder(String orderId, boolean shouldFail) {
        log.info("[IdempotentScenario] 进入幂等业务执行: orderId={}, shouldFail={}", orderId, shouldFail);
        if (shouldFail) {
            throw new BusinessException("模拟业务处理失败，自动释放幂等凭据以供重试: orderId=" + orderId);
        }
        return "SUCCESS_" + orderId;
    }

    /**
     * 2. 编程式防重幂等模板执行
     */
    public <T, E extends Throwable> T executeProgrammatic(String key, Duration timeout, IdempotentSupplier<T, E> supplier) throws E {
        return redisIdempotentTemplate.execute(key, timeout, supplier);
    }

    /**
     * 3. 静态门面防重幂等执行
     */
    public <T, E extends Throwable> T executeStatic(String key, IdempotentSupplier<T, E> supplier) throws E {
        return RedisIdempotentUtil.execute(key, supplier);
    }
}
```

---

## 8. 分布式受管信号量与并发度治理 (`RedisSemaphoreTemplate` & `RedisSemaphoreUtil`)

### 场景痛点
当调用外部计费 API、高负载推理服务或有限并发配额的第三方接口时，传统的限流仅能限制单位时间内的 QPS，无法限制**同一时刻正在处理中的最大并发线程数（Concurrency In-Flight）**。一旦外部接口耗时变慢，请求在服务端持续堆积导致连接池迅速枯竭。

### 增强解决方案
- **受管闭包安全释放**：底层封装 Redisson `RSemaphore`，在 `finally` 块中通过状态判断保证许可证安全归还；
- **动态配额调配 (`resetPermits`)**：支持在运行时原子调整总许可证配额；
- **争抢超时优雅降级 (`fallback`)**：在等待超时后不抛出异常打断业务，平滑触发降级闭包返回兜底结果；
- **静态操作门面 (`RedisSemaphoreUtil`)**：免注入快速接入。

### 核心代码示例（1:1 逆向自 `SemaphoreThrottlingScenarioService`）
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class SemaphoreThrottlingScenarioService {

    private final RedisSemaphoreTemplate redisSemaphoreTemplate;

    /**
     * 1. 模拟高昂下游接口调用，带并发度治理与超时降级
     */
    public String callHeavyDownstream(String serviceName, int totalPermits, Duration timeout) {
        String semaphoreKey = "sem:heavy:" + serviceName;
        return redisSemaphoreTemplate.execute(
                semaphoreKey,
                totalPermits,
                1,
                timeout,
                () -> {
                    log.info("[Semaphore] 成功获取信号量许可证，执行高并发下游调用: service={}", serviceName);
                    return "SUCCESS_DOWNSTREAM_" + serviceName;
                },
                () -> {
                    log.warn("[Semaphore] 获取信号量许可证超时，触发兜底降级响应: service={}", serviceName);
                    return "FALLBACK_DOWNSTREAM_" + serviceName;
                }
        );
    }

    /**
     * 2. 动态设置/重置信号量总许可证配额
     */
    public boolean resetPermits(String serviceName, int newPermits) {
        String semaphoreKey = "sem:heavy:" + serviceName;
        return redisSemaphoreTemplate.resetPermits(semaphoreKey, newPermits);
    }
}
```

---

## 9. 受管原子 Lua 脚本执行器与对称原语 (`RedisScriptExecutor` & `RedisAtomicUtil`)

### 场景痛点
执行复杂复合原子逻辑（如 CAS 比较并替换、按日发号并绑定 TTL）时，分步执行会发生严重的数据并发竞争与脏读，而每次手动编写 `RedisScript` 存在重复加载、缺乏 SHA1 缓存以及异常未受管转译等问题。

### 增强解决方案
- **`RedisScriptExecutor` 脚本执行门面**：支持内联 Lua 源码与 Classpath 资源脚本加载，内置 SHA1 摘要自动计算与缓存，优先通过 `EVALSHA` 协议通信减少网络带宽占用；
- **对称 CAS 与发号原语 (`RedisAtomicUtil`)**：提供 `compareAndSet(key, expect, update)`、`getAndSet(key, update)` 以及带 48h 自动清理防孤儿 Key 的 `generateDailySeq(prefix, length)`。

### 核心代码示例（1:1 逆向自 `ScriptExecutionScenarioService`）
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptExecutionScenarioService {

    private final RedisScriptExecutor redisScriptExecutor;
    private final RedisAtomicUtil redisAtomicUtil;

    // 1. 执行受管 Classpath Lua 脚本
    public <T> T executeClasspathScript(String path, Class<T> resultType, List<String> keys, Object... args) {
        return redisScriptExecutor.executeClasspath(path, resultType, keys, args);
    }

    // 2. 基于 RedisAtomicUtil 执行 CAS 原子比较替换
    public boolean compareAndSet(String key, Long expect, Long update) {
        return redisAtomicUtil.compareAndSet(key, expect, update);
    }

    // 3. 基于 RedisAtomicUtil 执行原子获取并设置 (GetAndSet)
    public Long getAndSet(String key, Long update) {
        return redisAtomicUtil.getAndSet(key, update);
    }

    // 4. 生成按日自重置带日期前缀的原子流水号 (如 ORD20261027000001)
    public String generateDailyOrderNo(String prefix, int length) {
        return redisAtomicUtil.generateDailySeq(prefix, length);
    }
}
```

---

## 10. 广播发布与订阅引擎 (Pub/Sub)

### 场景痛点
在集群部署中，某个节点更新了本地 JVM 缓存或系统配置后，需要即时同步给所有集群节点刷新内存。传统手写 Spring Data Redis `MessageListener` 配置繁琐、缺乏统一类型反序列化，且在监听回调线程中直接执行阻塞业务容易导致监听连接假死。

### 核心代码示例（1:1 逆向自 `NoticeBroadcastScenarioService`）
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeBroadcastScenarioService {

    public static final String TOPIC_NOTICE_BROADCAST = "sample:notice:broadcast";
    public static final String PATTERN_SYSTEM_EVENT = "sample:system:*";

    private final RedisMessagePublisher redisMessagePublisher;

    /**
     * 1. 广播发布系统通知
     */
    public Long publishNotice(NoticeBroadcastPayload notice) {
        log.info("[NoticeBroadcast] 广播发布系统通知: topic={}, noticeId={}", TOPIC_NOTICE_BROADCAST, notice.getNoticeId());
        return redisMessagePublisher.publish(TOPIC_NOTICE_BROADCAST, notice);
    }

    /**
     * 2. 声明式精确主题监听：自动反序列化为强类型 POJO
     */
    @ChannelTopicListener(topics = TOPIC_NOTICE_BROADCAST, concurrency = 1)
    public void onNoticeBroadcast(NoticeBroadcastPayload notice) {
        log.info("[NoticeBroadcast] 声明式接收系统公告广播: noticeId={}, title={}", notice.getNoticeId(), notice.getTitle());
    }

    /**
     * 3. 声明式通配模式监听：多参数接收 payload 与触发频道
     */
    @ChannelTopicListener(patterns = PATTERN_SYSTEM_EVENT, concurrency = 1)
    public void onSystemEvent(String eventData, String channel) {
        log.info("[NoticeBroadcast] 声明式通配监听到系统事件: channel={}, data={}", channel, eventData);
    }
}
```

---

## 11. 分布式布隆过滤器 (`RedisBloomFilter` & `RedisBloomUtil`)

### 场景痛点
海量用户判重、防爬虫 IP 过滤与防止非法 Key 穿透至数据库时，直接存入 Redis Set 占用巨大内存，且原生 Redisson 布隆过滤器缺乏启动预热机制与容量逼近 80% 安全告警。

### 核心代码示例（1:1 逆向自 `CacheScenarioService`）
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheScenarioService {

    private final RedisBloomFilter redisBloomFilter;

    // 1. 将元素添加至布隆过滤器（亦支持 RedisBloomUtil.add 静态门面）
    public boolean addBlacklist(String userId) {
        return redisBloomFilter.add("user-blacklist", userId);
    }

    // 2. 判定元素是否可能存在
    public boolean checkBlacklist(String userId) {
        return redisBloomFilter.contains("user-blacklist", userId);
    }

    // 3. 查询布隆过滤器健康指标（元素数、预估容量、使用率、误判率）
    public BloomFilterMetricsVO getBlacklistMetrics() {
        return BloomFilterMetricsVO.builder()
                .filterName("user-blacklist")
                .count(redisBloomFilter.count("user-blacklist"))
                .expectedInsertions(redisBloomFilter.getExpectedInsertions("user-blacklist"))
                .usageRate(redisBloomFilter.getUsageRate("user-blacklist"))
                .falseProbability(redisBloomFilter.getFalseProbability("user-blacklist"))
                .build();
    }
}
```

---

## 12. 工业级增强缓存管理器 (`RedisCacheManager`)

### 核心增强
1. **分 Cache 个性化 TTL**：通过 YAML 配置为每个 `@Cacheable(value = "...")` 指定专属过期时间；
2. **防穿透空对象缓存**：默认启用空值缓存，防止空 Key 重复穿透；
3. **原生随机抖动防雪崩 (`JitterTtlFunction`)**：在基准 TTL 基础上自适应随机叠加抖动偏移量，打散热点失效时间。

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

---

## 13. 全景缓存防卫模板 (`RedisCacheTemplate`)

### 核心能力
单键与批量防击穿（互斥锁 + DCL）、防穿透（短 TTL 空哨兵 `__ATENG_CACHE_NULL__`）、防雪崩（随机抖动 Jitter）、批量 MGET 探测 + 字典序排序锁去重，彻底杜绝 N+1 查询。

### 核心代码示例（1:1 逆向自 `CacheScenarioService`）
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheScenarioService {

    private final RedisCacheTemplate redisCacheTemplate;

    // 1. 单商品安全查询（防击穿 + 防穿透 + 防雪崩）
    public ProductVO getProduct(Long productId) {
        return redisCacheTemplate.getOrLoad(
                "product:" + productId,
                ProductVO.class,
                key -> mockProductDb.get(productId)
        );
    }

    // 2. 批量商品安全查询（MGET + 字典序排序锁 + Pipeline 批量回写，彻底杜绝 N+1）
    public Map<Long, ProductVO> batchGetProducts(List<Long> productIds) {
        return redisCacheTemplate.batchGetOrLoad(
                productIds,
                id -> "product:" + id,
                ProductVO.class,
                missingIds -> {
                    Map<Long, ProductVO> result = new LinkedHashMap<>();
                    for (Long id : missingIds) {
                        ProductVO p = mockProductDb.get(id);
                        if (p != null) {
                            result.put(id, p);
                        }
                    }
                    return result;
                }
        );
    }

    // 3. 对称缓存驱逐
    public void evictProduct(Long productId) {
        redisCacheTemplate.evict("product:" + productId);
    }
}
```

---

## 14. 生产级内存+Redis 两级缓存体系 (`TwoLevelCache` & `TwoLevelCacheManager`)

### 场景痛点
微服务单用 Redis 存在网络 I/O 往返延迟，单用本地 JVM 堆缓存存在多节点脏读；跨节点广播失效若不加防环会引发自重入死循环。

### 增强解决方案
- **纳秒级 L1 内存直读**：优先探测可选依赖 Caffeine（W-TinyLFU）；缺依赖时自动平滑退化为内置有界 `ConcurrentLruMemoryCache`，严格限制容量防 OOM；
- **集中式 Pub/Sub 广播失效**：淘汰时集中向 `ateng:cache:evict:topic` 广播携带 `instanceId` 的失效消息；
- **`instanceId` 防自重入防环机制**：各节点监听到广播时比对 `instanceId`，主动忽略自身发出的失效消息，杜绝自身二次淘汰与死循环。

### 核心代码示例（1:1 逆向自 `CacheScenarioService`）
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheScenarioService {

    // 1. Spring Cache 声明式两级缓存查询（首次查库回填 L1 与 L2，后续直接从本地 L1 纳秒级返回）
    @Cacheable(cacheNames = "user-profile", key = "#userId")
    public String getUserProfile(Long userId) {
        log.info("[CacheScenario] 两级缓存未命中，执行模拟数据库查询: userId={}", userId);
        return "Role_VIP_" + userId;
    }

    // 2. Spring Cache 声明式定向淘汰（清除本地 L1 与 L2，并广播协同淘汰集群其他节点 L1）
    @CacheEvict(cacheNames = "user-profile", key = "#userId")
    public void evictUserProfile(Long userId) {
        log.info("[CacheScenario] 淘汰用户档案两级缓存: userId={}", userId);
    }

    // 3. Spring Cache 声明式全量清空
    @CacheEvict(cacheNames = "user-profile", allEntries = true)
    public void clearUserProfileCache() {
        log.info("[CacheScenario] 清空用户档案全量两级缓存");
    }
}
```

---

## 15. 完整 YAML 配置属性清单

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
      redisson:
        config: ""                      # 可选自定义 Redisson 外部配置文件路径 (如 classpath:redisson.yaml)
        threads: 0                      # Redisson 通用线程池大小 (默认 0 表示 CPU*2)
        netty-threads: 0                # Redisson Netty 线程池大小 (默认 0 表示 CPU*2)
        ping-connection-interval: 30s   # TCP 连接心跳保活检测间隔 (默认 30s，防御云原生 NAT 网关静默切断连接)
        connection-pool-size: 64        # 主连接池最大连接数 (默认 64)
        connection-minimum-idle-size: 16 # 主连接池最小空闲连接数 (默认 16)
        retry-attempts: 3               # 命令重试次数 (默认 3)
        retry-interval: 1500ms          # 命令重试间隔时间 (默认 1500ms)
      lock:
        enabled: true                   # 是否启用声明式分布式锁切面 (默认 true)
        prefix: "lock:"                 # 分布式锁 Key 统配全局前缀 (默认 "lock:")
        wait-time: 3s                   # 默认获取锁最大阻塞等待时长 (默认 3s)
        lease-time: -1ms                # 默认持锁租约超时时长 (-1ms 启用看门狗自动续期)
        time-unit: SECONDS              # 默认时间单位 (默认 SECONDS)
        slow-threshold: 3s              # 慢锁监控告警阈值 (默认 3s，超出记录 WARN 日志)
      delay-queue:
        enabled: true                   # 是否启用延时队列支持 (默认 true)
        retry-times: 3                  # 延时消费失败最大重试次数 (默认 3)
        retry-delay: 3s                 # 延时消费失败重试间隔 (默认 3s)
        dead-letter-enabled: true       # 是否启用消费重试耗尽转入死信队列 (默认 true)
        dead-letter-suffix: ":dlq"      # 死信队列统配名称后缀 (默认 ":dlq")
      stream:
        enabled: true                   # 是否启用 Redis Stream 流式消息支持 (默认 true)
        retry-times: 3                  # 消费失败最大重试次数 (默认 3)
        poll-timeout: 2s                # 消费拉取阻塞超时时长 (默认 2s)
        batch-size: 10                  # 单次拉取最大消息条数 (默认 10)
        dlq-suffix: ":dlq"              # 死信流统一后缀 (默认 ":dlq")
        instance-id: ""                 # 消费者实例标识 (留空则自动生成)
      idempotent:
        enabled: true                   # 是否启用分布式防重幂等切面与模板 (默认 true)
        prefix: "idempotent:"           # 防重幂等键统一前缀 (默认 "idempotent:")
        default-expire: 10m             # 默认幂等凭证保留时长 (默认 10m)
        delete-on-failure: true         # 业务异常时是否默认自动删除防重 Key 以便重试 (默认 true)
      pub-sub:
        enabled: true                   # 是否启用 Pub/Sub 广播引擎 (默认 true)
      bloom-filters:                    # 分布式布隆过滤器声明式预热配置映射 (`Map<String, BloomFilterConfig>`)
        user-blacklist:
          expected-insertions: 50000    # 预估元素插入总量 (默认 100,000)
          false-probability: 0.01       # 期望误判率 (默认 0.01 即 1%)
      cache:
        enabled: true                   # 是否启用增强单级 Redis 缓存基础设施 (默认 true)
        default-ttl: 30m                # 缓存默认生存时间 (默认 30m)
        jitter-enabled: true            # 是否启用随机抖动防雪崩 (默认 true)
        max-jitter: 60s                 # 随机抖动最大时间跨度 (默认 60s)
        cache-null-values: true         # 是否缓存 null 防穿透 (默认 true)
        null-ttl: 5m                    # 空对象防穿透独立短过期时间 (默认 5m)
        lock-wait-time: 3s              # 缓存防击穿互斥锁最大等待时长 (默认 3s)
        expires:                        # 针对不同 CacheName 的个性化 TTL (`Map<String, Duration>`)
          short_cache: 10m
          long_cache: 24h
      two-level:
        enabled: false                  # 是否启用生产级两级缓存 (L1 JVM + L2 Redis，默认 false)
        topic: "ateng:cache:evict:topic"# 跨节点两级缓存失效广播主题 (默认 "ateng:cache:evict:topic")
        l1:
          default-ttl: 5m               # L1 堆内存本地缓存默认过期时间 (默认 5m)
          default-max-size: 10000       # L1 堆内存最大元素容量，严格防 OOM (默认 10000)
          expires:                      # 针对特定 CacheName 的 L1 独立 TTL
            user-profile: 2m
        l2:
          default-ttl: 30m              # L2 集中式 Redis 缓存默认过期时间 (默认 30m)
          jitter-enabled: true          # L2 写入是否启用随机抖动防雪崩 (默认 true)
          max-jitter: 60s               # L2 随机抖动最大时间跨度 (默认 60s)
          cache-null-values: true       # L2 是否缓存空哨兵防穿透 (默认 true)
          null-ttl: 5m                  # L2 空对象防穿透独立短过期时间 (默认 5m)
          expires:                      # 针对特定 CacheName 的 L2 独立 TTL
            user-profile: 1h
```

---

## 16. 生产扩展与高阶定制 (Customization & Extensibility)

`ateng-boot-redis` 坚持 `@ConditionalOnMissingBean` 缺省守卫铁律，支持企业级生产场景深度定制与平滑无缝接管：

### 16.1 自定义 RedissonClient 接管
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

### 16.2 编程式延时队列平滑接管
`RedisDelayedQueue` 既支持作为 Spring 托管单例由容器注入，也支持在离线测试或轻量客户端中通过构造器原生初始化：

```java
RedisDelayedQueue delayedQueue = new RedisDelayedQueue(redissonClient, objectMapper, redisProperties);
delayedQueue.offer("custom_queue", new TaskPayload(1001L), Duration.ofMinutes(10));
```

### 16.3 自定义死信失败处理 SPI 接管 (`DelayedQueueFailureHandler`)
框架默认注入 `LoggingDelayedQueueFailureHandler` 打印 ERROR 堆栈日志。当下游需要对接企业微信/钉钉报警、流转告警中心或落库持久化时，仅需声明同类型 Bean 即可一键接管：

```java
@Component
public class AlertDelayedQueueFailureHandler implements DelayedQueueFailureHandler {

    @Override
    public void onFailed(DeadLetterMessage<?> deadLetterMessage, Throwable cause) {
        log.error("延时队列死信告警触发: queue={}, retryTimes={}", deadLetterMessage.originalQueue(), deadLetterMessage.retryTimes(), cause);
        // 发送钉钉机器人告警通知...
    }
}
```

### 16.4 自定义 Stream 失败处理 SPI 接管 (`StreamFailureHandler`)
Stream 消费组重试耗尽后默认由 `LoggingStreamFailureHandler` 打印错误堆栈。若需要将失败事件存入审计数据库或发送告警卡片，只需声明实现类 Bean：

```java
@Component
public class CustomStreamFailureHandler implements StreamFailureHandler {

    @Override
    public void onFailed(StreamDeadLetterMessage<?> deadLetterMessage, Throwable cause) {
        log.error("Stream 消费组死信触发: stream={}, group={}, recordId={}",
                deadLetterMessage.originalStream(), deadLetterMessage.group(), deadLetterMessage.recordId(), cause);
        // 执行持久化或告警...
    }
}
```

### 16.5 编程式动态布隆过滤器与原生 Redisson 句柄扩展
除了在 YAML 中静态声明，`RedisBloomFilter` 亦完全支持在动态业务运行时按需初始化与底层句柄扩展：

```java
// 1. 动态按需原子初始化
boolean initialized = redisBloomFilter.tryInit("dynamic_promo_users", 200_000L, 0.005);

// 2. 获取原生 Redisson 句柄以进行异步操作或高级监听
RBloomFilter<String> nativeFilter = redisBloomFilter.getFilter("dynamic_promo_users");
RFuture<Boolean> future = nativeFilter.containsAsync("user_10086");
```

### 16.6 两级缓存 L1 内存引擎平滑退化与 SPI 适配
`ateng-boot-redis` 在设计两级缓存 L1 抽象时恪守零臃肿与底座优先原则：
- **编译期可选依赖 (Optional Dependency)**：`com.github.ben-manes.caffeine:caffeine` 标配 `<optional>true</optional>`，下游可按需引入享受 Caffeine 的 W-TinyLFU 高命中淘汰算法；
- **纯原生 JVM 零依赖平滑退化**：若下游未引入 Caffeine，`L1MemoryCacheFactory` 在类加载期通过 `ClassUtils.isPresent` 与私有静态内部类完成隔离探测，自动退化为内置的 `ConcurrentLruMemoryCache`，基于原生 `LinkedHashMap`（写锁防护、`accessOrder = true`）与 `l1.default-max-size` 严格容量控制，保障生产环境无外部重依赖下平稳运行且绝不 OOM。

### 16.7 受管 Lua 脚本原子执行器与自定义加载扩展
`RedisScriptExecutor` 允许将常用脚本收敛至 `resources/scripts/*.lua`，避免代码中硬编码字符串：

```java
@Autowired
private RedisScriptExecutor redisScriptExecutor;

public Long executeCustomLua(String key, Long delta) {
    return redisScriptExecutor.executeClasspath("scripts/custom_counter.lua", Long.class, List.of(key), delta);
}
```

### 16.8 分布式信号量配额治理与动态扩缩容
针对外部突发流量与服务升降级，可随时通过模板动态调配许可证总数：

```java
@Autowired
private RedisSemaphoreTemplate redisSemaphoreTemplate;

public void adjustCapacity(String serviceKey, int newQuota) {
    redisSemaphoreTemplate.resetPermits(serviceKey, newQuota);
}
```

---

## 17. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 17.1 分布式锁防御性释放与防误删不变量
- **状态判断释放范式**：在 `DistributedLockTemplate.safeUnlockAndAudit` 释放锁前，强制通过 `rLock.isHeldByCurrentThread()` 校验当前线程是否真正持有锁；若因业务耗时严重超出 `leaseTime` 导致锁已被 Redis 自动过期并由其他节点的线程获取，当前线程会主动跳过 `unlock()` 并记录 WARN 日志，**绝对杜绝误删其他线程持有的锁造成级联并发穿透**。
- **次生异常彻底隔离**：锁释放逻辑在 `finally` 块中受到全面保护并捕获底层网络断开等偶发异常，确保业务方法本身抛出的原始业务异常（如业务断言失败、校验未通过等）不被次生异常冲刷掩盖。

### 17.2 看门狗自动续期与固定租期选择策略
- **`leaseTime <= 0`（推荐）**：默认 `leaseTime = -1ms`，此时 Redisson 会自动激活看门狗（Watchdog，默认每隔 10s 自动续期至 30s）。适合绝大多数长事务与复杂业务逻辑，只要业务节点存活即可确保持锁不中断，进程宕机后 30s 自动失效。
- **`leaseTime > 0`（显式固定时长）**：一旦显式指定具体租期（例如 `leaseTime = 10`），Redisson 将**彻底禁用看门狗自动延期**。若业务执行耗时超出指定租期，锁将强制自动释放；请确保设置的租期显著大于业务最大可能耗时。

### 17.3 延时队列生命周期消费、受管线程池与死信流转
- **受管线程池与四维上下文透传**：`DelayedQueueBootstrap` 消费轮询彻底淘汰裸 `Executors`，接入 Spring 容器受管的 `ThreadPoolTaskExecutor` 并挂载 `ContextCopyingTaskDecorator`，确保 TraceId、TenantId 等上下文在延时队列消费线程中合规生效；
- **死信流转与错误隔离**：内置异常受控重试；重试耗尽后若 `deadLetterEnabled=true` 则将包装完整的 `DeadLetterMessage<T>` 投递至 `${queueName}:dlq` 并触发 `DelayedQueueFailureHandler` SPI，杜绝毒药消息阻塞队列并保障可追溯审计；
- **平滑优雅停机**：监听容器实现了 `DisposableBean`，在 JVM 或 Spring 容器销毁时首先停止拉取新消息，等待在途任务执行完成并注销内部阻塞循环与受管线程池，保障零消息悬挂。

### 17.4 布隆过滤器容量 80% 安全预警与生命周期清理
- **元素填充率实时预警**：布隆过滤器的误判率由预估容量与已插入元素数决定。当实际元素数超过预估容量的 80%（`getUsageRate() >= 0.8`）时，`RedisBloomFilter` 在添加操作后自动记录 WARN 日志告警，提示运维及时扩容或重建双过滤器平滑迁移；
- **对称物理销毁**：通过 `RedisBloomFilter.delete(filterName)` 可一键原子销毁底层 Redis 位图，释放内存占用。

### 17.5 RedisCacheTemplate 争锁超时容灾与死锁防御
- **争锁超时平滑降级（Resilience Pass-Through）**：当极端高并发争抢热点 Key 时，若等待互斥锁超时（超出 `lockWaitTime`，默认 3s），模板记录 WARN 日志后平滑降级穿透查库，并将查库结果异步尝试回填 Redis，严禁向外抛出异常阻断正常业务流；
- **批量缺失键字典序排序加锁**：在 `batchGetOrLoad` 处理并发缺失多键时，所有物理锁 Key 严格执行字典序升序排序（`Collections.sort(lockKeys)`）后再申请 MultiLock，从数学机制上杜绝并发请求中不同顺序加锁引发的死锁悬挂隐患。

### 17.6 两级缓存广播失效、反自重入与雪崩/穿透防御语义
- **全局唯一实例 ID 与自重入防环机制**：`TwoLevelCacheManager` 在初始化时分配全局递增无锁 `instanceId`。当业务线程执行 `@CacheEvict` 或 `cache.evict(...)` 时，本地优先清除 L1 与 L2 并向 Redis 集中频道（默认 `ateng:cache:evict:topic`）广播 `CacheEvictMessage`。各实例的消息监听器收到消息后比对 `instanceId`，若等于当前实例 ID 则立即判定为本地自身触发的广播并主动丢弃，仅对其他实例的本地 L1 触发清除，**从拓扑机制上 100% 杜绝自身二次淘汰、广播回环与自重入死锁**；
- **防穿透空哨兵机制**：当查库为空时，`DefaultTwoLevelCache` 向 Redis 写入专用空哨兵标识符 `__ATENG_CACHE_NULL__`（带有独立短过期时间 `l2.null-ttl`，默认 5m），并发请求命中空哨兵时立即返回 `null`，严防恶意不存在 Key 击穿底层存储；
- **防雪崩随机抖动 (Jitter)**：回填 L2 Redis 缓存时，系统自动在基准 TTL 基础上叠加 0 ~ `max-jitter` 的随机动态偏移，打散相同批次热点数据的集中过期时间。

### 17.7 Stream ACK 确认、PEL 挂起排查与孤儿消息认领机制
- **At-Least-Once 可靠交付保证**：消费者从消费组拉取消息后，消息自动进入该消费者的挂起列表（Pending Entries List, PEL）。仅当业务消费方法成功返回且通过 `redisStreamTemplate.ack(stream, group, recordId)` 确认后，消息才从 PEL 中移除；
- **孤儿挂起消息自动/手动抢占 (Claim)**：若某个消费者节点在消费中途崩溃宕机，未 ACK 的消息将长期滞留在 PEL 中。通过 `RedisStreamTemplate.claim`，健康的存活节点可指定最小空闲时间（`minIdle`）将孤儿消息转移至自身名下重新处理，防止消息永久悬挂。

### 17.8 分布式信号量持有人防误还与状态判断归还保底
- **槽位释放防御**：`RedisSemaphoreTemplate` 统一管理信号量许可证的申请与归还；业务代码执行完毕或异常中断时，在 `finally` 块中严格确保仅当本线程成功获取了相应数量许可证时才调用 `release(permits)`，严禁未持锁或超额释放导致许可证虚假膨胀。

### 17.9 分布式防重幂等 deleteOnFailure 故障自愈机制与业务隔离
- **业务失败自动回滚凭据**：传统 SETNX 防重若在业务异常后不释放，会导致上游重试机制因防重拦截而失败。`@RedisIdempotent` 标配 `deleteOnFailure = true`，当捕获到业务抛出异常时立即安全清理防重键，允许外部系统（如消息队列、支付网关）重新发起补偿调用；
- **成功持久留存**：仅当方法正常无异常返回时，防重键才会在 Redis 中留存完整的 `expire` 时长，抵御后续重复提交。

### 17.10 Redisson TCP 心跳保活与云原生 NAT 静默切断弹性防御机制
- **PING 心跳保活机制**：在云原生容器网络、跨可用区专线或公有云 NAT 网关环境下，长时间无数据通信的空闲 TCP 连接常被防火墙或网关静默切断（Silent Drop），导致应用在下一次发起 Redis 命令时因使用失效连接出现 `Connection reset by peer` 错误。`AtengRedisAutoConfiguration` 统一配置 `pingConnectionInterval`（默认 30s），由 Redisson 客户端后台定期向 Redis 服务端发送 PING 命令检测并维系物理链路活跃，彻底解决长连接偶发断开难题。
