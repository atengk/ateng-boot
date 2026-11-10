# ateng-boot-redis 分布式协同与缓存增强套件

> 提供 Spring Data Redis (Lettuce) 与 Redisson 双核融合底座、无 `@class` 污染纯净安全 JSON 序列化、全能 `RedisUtil` 操作门面、声明式四阶分布式锁、双模轻量延时队列、Redis 5.0+ Stream 流式消息、非 Web 分布式防重幂等、受管分布式信号量、原子 Lua 脚本执行器、Pub/Sub 广播引擎、两级缓存、工业级缓存三防模板及连接池治理。

---

## 0. 特性与公共 API 全景速查矩阵

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **基础运维** | 自动装配与主配置 | `RedisProperties` / `AtengRedisAutoConfiguration` | `ateng.boot.redis.enabled`<br>`ateng.boot.redis.key-prefix` | 统管双核装配开关与全局统一 Key 命名前缀隔离 | `AtengRedisAutoConfigurationTest` |
| **基础运维** | Redisson 连接保活与连接池治理 | `RedissonClient` / `AtengRedisAutoConfiguration` | `ateng.boot.redis.redisson.ping-connection-interval`<br>`ateng.boot.redis.redisson.connection-pool-size`<br>`ateng.boot.redis.redisson.connection-minimum-idle-size`<br>`ateng.boot.redis.redisson.retry-attempts`<br>`ateng.boot.redis.redisson.retry-interval`<br>`ateng.boot.redis.redisson.threads`<br>`ateng.boot.redis.redisson.netty-threads`<br>`ateng.boot.redis.redisson.config` | TCP 心跳 PING 保活机制（默认 30s）防御云原生 NAT 网关静默切断连接；单机/集群/哨兵连接池容量与超时重试统管 | `AtengRedisAutoConfigurationTest` |
| **数据操作** | 全能操作门面 | `RedisUtil` | `ateng.boot.redis.key-prefix` | `hasKey(key)` / `expire(key, duration)` / `scanKeys(pattern, count)` / `get(key, clazz)` / `set(key, val, duration)` / `buildKey(key)` / `hGet` / `hSet` / `hDelete` / `hGetAll` / `hIncr` / `zAdd` / `reverseRangeWithScores` / `executePipelined`：纯游标分批拉取彻底杜绝 `keys *` 阻塞，丰富数据结构与单 RTT 批处理 | `RedisUtilTest`<br>`RedisOpsAndScriptController`<br>`SampleRedisIntegrationTest` |
| **序列化** | 纯净安全序列化工厂 | `RedisSerializerFactory` | - | `createSafeObjectMapper()` / `createJsonSerializer()` / `stringSerializer()`：无 `@class` 多态 RCE 漏洞，内置 JSR-310 与 Long 精度保护 | `RedisSerializerFactoryTest` |
| **分布式锁** | 声明式分布式锁 | `@DistributedLock` / `DistributedLockAspect` | `ateng.boot.redis.lock.enabled`<br>`ateng.boot.redis.lock.prefix`<br>`ateng.boot.redis.lock.wait-time`<br>`ateng.boot.redis.lock.lease-time`<br>`ateng.boot.redis.lock.time-unit`<br>`ateng.boot.redis.lock.slow-threshold` | `@DistributedLock(key, keys, mode, waitTime, leaseTime, timeUnit, message)`：支持 SpEL、四阶锁模式、看门狗自动续期、防误删释放与慢锁审计 | `DistributedLockAspectTest`<br>`SampleRedisIntegrationTest` |
| **分布式锁** | 编程式锁受管模板 | `DistributedLockTemplate` | `ateng.boot.redis.lock.wait-time`<br>`ateng.boot.redis.lock.lease-time`<br>`ateng.boot.redis.lock.slow-threshold` | `execute(key, supplier)` / `execute(key, supplier, fallback)` / `execute(keys, mode, wait, lease, unit, supplier, fallback)` / `tryExecute(key, action)`：闭包内业务异常无损透传 | `DistributedLockTemplateTest`<br>`SampleRedisIntegrationTest` |
| **分布式锁** | 静态锁操作门面 | `RedisLockUtil` | `ateng.boot.redis.lock.*` | `RedisLockUtil.execute(key, supplier)` / `RedisLockUtil.execute(key, supplier, fallback)` / `RedisLockUtil.tryExecute(key, action)`：静态上下文无需注入 Spring Bean 一键安全加锁 | `RedisLockUtilTest`<br>`SampleRedisIntegrationTest` |
| **分布式锁** | 锁模式枚举与闭包 | `LockMode` / `LockSupplier<T, E>` / `LockAction<E>` | - | `REENTRANT`, `FAIR`, `READ`, `WRITE`；支持受检与非受检异常穿透声明 | `DistributedLockTemplateTest`<br>`SampleRedisIntegrationTest` |
| **延时队列** | 编程式延时任务与静态门面 | `RedisDelayedQueue` / `RedisDelayedQueueUtil` | `ateng.boot.redis.delay-queue.enabled`<br>`ateng.boot.redis.delay-queue.dead-letter-enabled`<br>`ateng.boot.redis.delay-queue.dead-letter-suffix`<br>`ateng.boot.redis.delay-queue.max-replay-attempts` | `offer(queue, msg, delay)` / `poll(queue, clazz)` / `take(queue, clazz)` / `remove(queue, msg)` / `destroy()`<br>DLQ 运维：`peekDeadLetter` / `peekDeadLetters` / `pollDeadLetter` / `replayDeadLetter` / `replayDeadLetters` / `getDeadLetterSize` / `clearDeadLetterQueue` / `sendToDeadLetterQueue`，支持重放计数与超限熔断 | `RedisDelayedQueueTest`<br>`SampleRedisIntegrationTest` |
| **延时队列** | 声明式消费监听 | `@DelayedQueueListener` / `DelayedQueueBootstrap` | `ateng.boot.redis.delay-queue.retry-times`<br>`ateng.boot.redis.delay-queue.retry-delay` | `@DelayedQueueListener(queue, concurrency)`：受管 `ThreadPoolTaskExecutor` 并发驱动、`ContextCopyingTaskDecorator` 上下文透传、重试退避与平滑优雅停机 | `DelayedQueueBootstrapTest`<br>`SampleRedisIntegrationTest` |
| **延时队列** | 死信流转与失败 SPI | `DeadLetterMessage<T>` / `DelayedQueueFailureHandler` | `ateng.boot.redis.delay-queue.dead-letter-enabled`<br>`ateng.boot.redis.delay-queue.dead-letter-suffix` | `onFailed(deadLetterMessage, cause)`：重试耗尽后封装完整因果堆栈转入死信队列，默认 `LoggingDelayedQueueFailureHandler` 告警，支持自定义 SPI 无缝接管 | `DelayedQueueBootstrapTest`<br>`AtengRedisAutoConfigurationTest` |
| **流式消息** | Redis 5.0+ Stream 消息流引擎 | `RedisStreamTemplate` / `RedisStreamUtil` | `ateng.boot.redis.stream.enabled`<br>`ateng.boot.redis.stream.poll-timeout`<br>`ateng.boot.redis.stream.batch-size`<br>`ateng.boot.redis.stream.retry-times`<br>`ateng.boot.redis.stream.dlq-suffix`<br>`ateng.boot.redis.stream.instance-id`<br>`ateng.boot.redis.stream.max-replay-attempts`<br>`ateng.boot.redis.stream.claim-idle-time`<br>`ateng.boot.redis.stream.claim-batch-size` | `send(stream, payload)`（自动透传四维上下文）/ `createGroup(stream, group)` / `readGroup(stream, group, consumer, count, timeout)` / `ack(stream, group, recordIds)` / `getPendingMessages(stream, group, count)` / `claimPendingMessages(stream, group, consumer, minIdle, count)` / `claim(stream, group, newConsumer, minIdle, recordIds)` / `peekDeadLetters(stream, count)` / `replayDeadLetter(stream, recordId)` / `replayDeadLetters(stream, count)` / `deadLetterSize(stream)` / `clearDeadLetters(stream)` / `size(stream)` / `trim(stream, count)` | `RedisStreamTemplateTest`<br>`RedisStreamUtilTest`<br>`SampleRedisIntegrationTest` |
| **流式消息** | 声明式 Stream 消费组监听 | `@RedisStreamListener` / `StreamBootstrap` | `ateng.boot.redis.stream.*` | `@RedisStreamListener(stream, group, concurrency, autoAck, autoCreateGroup)`：`SmartLifecycle` 受管异步消费、自动创建消费组、自动/手动 ACK、四维上下文透传与清理、PEL 孤儿消息周期自动认领自愈与死信隔离 | `StreamBootstrapTest`<br>`SampleRedisIntegrationTest` |
| **流式消息** | Stream 失败补偿与死信流 SPI | `StreamDeadLetterMessage<T>` / `StreamFailureHandler` | `ateng.boot.redis.stream.retry-times`<br>`ateng.boot.redis.stream.dlq-suffix` | `onFailed(deadLetterMessage, cause)`：重试耗尽后封装完整因果链转入死信流 `${stream}:dlq`，默认 `LoggingStreamFailureHandler` 告警，支持自定义 SPI 业务告警与持久化 | `StreamBootstrapTest`<br>`AtengRedisAutoConfigurationTest` |
| **业务防重** | 声明式业务防重幂等 | `@RedisIdempotent` / `RedisIdempotentAspect` | `ateng.boot.redis.idempotent.enabled`<br>`ateng.boot.redis.idempotent.prefix`<br>`ateng.boot.redis.idempotent.default-expire`<br>`ateng.boot.redis.idempotent.delete-on-failure` | `@RedisIdempotent(key, expire, message, deleteOnFailure)`：支持 SpEL 动态解析业务唯一键、防重复提交/重复消费、业务异常时由 `deleteOnFailure=true` 自动释放凭据以支持上游重试 | `RedisIdempotentAspectTest`<br>`SampleRedisIntegrationTest` |
| **业务防重** | 编程式防重受管模板 | `RedisIdempotentTemplate` | `ateng.boot.redis.idempotent.*` | `execute(key, supplier)` / `execute(key, timeout, supplier)` / `execute(key, timeout, deleteOnFailure, message, supplier)`：函数式闭包受管防重，防重失败阻断并抛出 `BusinessException` | `RedisIdempotentTemplateTest`<br>`SampleRedisIntegrationTest` |
| **业务防重** | 静态防重操作门面 | `RedisIdempotentUtil` | `ateng.boot.redis.idempotent.*` | `RedisIdempotentUtil.execute(key, supplier)` / `RedisIdempotentUtil.execute(key, timeout, supplier)`：静态上下文无需注入 Spring Bean 一键安全执行防重逻辑 | `RedisIdempotentUtilTest`<br>`SampleRedisIntegrationTest` |
| **并发治理** | 分布式受管信号量模板 | `RedisSemaphoreTemplate` | `ateng.boot.redis.key-prefix` | `execute(key, totalPermits, acquirePermits, waitTime, supplier, fallback)` / `tryAcquire(key, permits, timeout)` / `release(key, permits)` / `availablePermits(key)` / `resetPermits(key, newPermits)`：集群全局并发硬阈值控制、自动初始化配额、争锁超时优雅降级与动态扩缩容 | `RedisSemaphoreTemplateTest`<br>`SampleRedisIntegrationTest` |
| **并发治理** | 静态信号量操作门面 | `RedisSemaphoreUtil` | `ateng.boot.redis.key-prefix` | `RedisSemaphoreUtil.execute(key, totalPermits, waitTime, supplier, fallback)` / `RedisSemaphoreUtil.resetPermits(key, newPermits)`：静态上下文轻量并发度治理 | `RedisSemaphoreUtilTest`<br>`SampleRedisIntegrationTest` |
| **脚本执行** | 受管原子 Lua 脚本执行器 | `RedisScriptExecutor` | - | `execute(script, resultType, keys, args)` / `executeClasspath(path, resultType, keys, args)`：自动 SHA1 预编译缓存、EVALSHA 优先执行、底层脚本加载异常安全转译 | `RedisScriptExecutorTest`<br>`RedisOpsAndScriptController`<br>`SampleRedisIntegrationTest` |
| **广播订阅** | 主题广播发布者 | `RedisMessagePublisher` | `ateng.boot.redis.pub-sub.enabled` | `publish(channel, message)` / `subscribe(listener, channel)` / `unsubscribe(listener, channel)`：跨节点广播通知与动态订阅 | `RedisMessagePublisherTest` |
| **广播订阅** | 声明式频道监听 | `@ChannelTopicListener` / `ChannelTopicBootstrap` | `ateng.boot.redis.pub-sub.enabled` | `@ChannelTopicListener(topics, patterns, concurrency)`：支持 `${...}` 占位符、精确主题与通配模式，支持单参 POJO/String/byte[]/Message、双参及三参多态安全反序列化，受管 `ThreadPoolTaskExecutor` 并发驱动与 MDC `traceId` 透传 | `ChannelTopicBootstrapTest`<br>`SampleRedisIntegrationTest` |
| **布隆过滤** | 分布式布隆过滤器 | `RedisBloomFilter` | `ateng.boot.redis.key-prefix`<br>`ateng.boot.redis.bloom-filters.<name>.expected-insertions`<br>`ateng.boot.redis.bloom-filters.<name>.false-probability` | `tryInit(name, expected, prob)` / `add(name, val)` / `addAll(name, coll)` / `contains(name, val)` / `containsAll(name, coll)` / `count(name)` / `getUsageRate(name)` / `isOverCapacity(name, threshold)` / `delete(name)`：支持依赖注入操作范式、80% 容量安全预警与全局 KeyPrefix 隔离 | `RedisBloomFilterTest`<br>`SampleRedisIntegrationTest` |
| **布隆过滤** | 声明式自动预热调度器 | `BloomFilterBootstrap` | `ateng.boot.redis.bloom-filters.*` | 容器就绪后自动遍历 YAML 中声明的规则，原子幂等调用 `tryInit` 完成预热 | `RedisBloomFilterTest`<br>`AtengRedisAutoConfigurationTest` |
| **布隆过滤** | 静态布隆过滤门面 | `RedisBloomUtil` | `ateng.boot.redis.key-prefix`<br>`ateng.boot.redis.bloom-filters.*` | `tryInit(name, expected, prob)` / `add(name, val)` / `addAll(name, coll)` / `contains(name, val)` / `containsAll(name, coll)` / `count(name)` / `getUsageRate(name)` / `isOverCapacity(name, threshold)` / `delete(name)`：静态上下文开箱即用门面，底层统一委托受管单例 `RedisBloomFilter` | `RedisBloomFilterTest`<br>`SampleRedisIntegrationTest` |
| **原子发号** | 结构化发号与自增 | `RedisAtomicUtil` | `ateng.boot.redis.key-prefix` | `generateDailySeq(bizPrefix, length)`：跨天自重置且带 48h TTL 防泄露流水号；`incrWithExpire(key, delta, duration)` / `compareAndSet(key, expect, update)` / `getAndSet(key, update)` | `RedisAtomicUtilTest`<br>`RedisOpsAndScriptController`<br>`SampleRedisIntegrationTest` |
| **并发流控** | 滑动窗口严格限流 | `RedisAtomicUtil` | - | `isAllowedBySlidingWindow(key, maxLimit, window)`：基于 ZSet 毫秒级时间戳实现精准滑动窗口防突刺 | `RedisAtomicUtilTest`<br>`SampleRedisIntegrationTest` |
| **并发流控** | 分布式令牌桶流控 | `RedisRateLimiterClient` | `ateng.boot.redis.key-prefix` | `tryAcquire(key, rate, interval)` / `tryAcquire(key, rate, interval, permits, timeout)` / `acquire(key, permits)` / `delete(key)` / `getAvailablePermits(key)`：基于 Redisson 令牌桶平滑流控，自动挂载全局键前缀 | `RedisRateLimiterClientTest`<br>`SampleRedisIntegrationTest` |
| **缓存增强** | 多级 TTL 缓存管理 | `RedisCacheManager` / `AtengRedisAutoConfiguration` | `ateng.boot.redis.enabled`<br>`ateng.boot.redis.key-prefix`<br>`ateng.boot.redis.cache.enabled`<br>`ateng.boot.redis.cache.default-ttl`<br>`ateng.boot.redis.cache.expires` | 适配 Spring Cache 注解，个性化为不同 `@Cacheable(value = "...")` 声明独立 TTL；配置全局前缀时统一挂载，空前缀时保持原生 `cacheName::key` | `AtengRedisAutoConfigurationTest` |
| **缓存防卫** | 统一缓存执行模板 | `RedisCacheTemplate` | `ateng.boot.redis.cache.enabled`<br>`ateng.boot.redis.cache.default-ttl`<br>`ateng.boot.redis.cache.null-ttl`<br>`ateng.boot.redis.cache.max-jitter`<br>`ateng.boot.redis.cache.jitter-enabled`<br>`ateng.boot.redis.cache.cache-null-values`<br>`ateng.boot.redis.cache.lock-wait-time` | `getOrLoad(key, clazz, loader)` / `getOrLoad(key, typeRef, loader)`：单键 DCL + 互斥锁防击穿 + 短 TTL 空哨兵防穿透 + 随机抖动防雪崩 + 争锁超时降级<br>`batchGetOrLoad(keys, mapper, clazz/typeRef, batchLoader)`：批量 MGET 探测 + 字典序排序锁去重 + 降级自愈回填 + Pipeline 回写彻底杜绝 N+1<br>`get` / `put` / `evict` / `batchEvict` / `hasKey`：对称运维门面 | `RedisCacheTemplateTest`<br>`SampleRedisIntegrationTest` |
| **两级缓存** | 两级缓存核心门面 | `TwoLevelCache` / `DefaultTwoLevelCache` | `ateng.boot.redis.two-level.*`<br>`ateng.boot.redis.key-prefix` | `getName()` / `get(key)` / `get(key, clazz)` / `get(key, loader)` / `put(key, val, ttl)` / `putIfAbsent` / `evict(key)` / `evictLocal(key)` / `clear()` / `clearLocal()`：纳秒级 L1 本地命中、分布式 L2 兜底、空哨兵防穿透与游标安全 scan 清理 | `TwoLevelCacheTest`<br>`SampleRedisIntegrationTest` |
| **两级缓存** | Spring Cache 适配器 | `TwoLevelCacheManager` | `ateng.boot.redis.two-level.enabled`<br>`ateng.boot.redis.two-level.topic`<br>`ateng.boot.redis.two-level.l1.*`<br>`ateng.boot.redis.two-level.l2.*` | `@Cacheable` / `@CacheEvict` 无缝注解驱动、动态按需创建缓存空间、`instanceId` 唯一标识防环、`ateng:cache:evict:topic` 跨节点集中广播协同失效 | `TwoLevelCacheManagerTest`<br>`SampleRedisIntegrationTest` |
| **两级缓存** | L1 内存缓存与退化工厂 | `L1MemoryCacheFactory` / `L1MemoryCache` / `ConcurrentLruMemoryCache` | `ateng.boot.redis.two-level.l1.default-max-size`<br>`ateng.boot.redis.two-level.l1.default-ttl` | 自动探测 Caffeine，存在时启用 W-TinyLFU 高性能缓存，缺失时自动退化为内置 LRU 内存结构并受 `default-max-size` 严格限制防 OOM | `ConcurrentLruMemoryCacheTest`<br>`L1MemoryCacheFactoryTest` |
| **两级缓存** | 跨节点广播失效载荷 | `CacheEvictMessage` | `ateng.boot.redis.two-level.topic` | `cacheName` / `key` / `instanceId` / `clearAll`：跨实例失效广播数据契约 | `TwoLevelCacheTest`<br>`TwoLevelCacheManagerTest` |
| **缓存增强** | 随机抖动防雪崩 | `JitterTtlFunction` | `ateng.boot.redis.cache.jitter-enabled`<br>`ateng.boot.redis.cache.max-jitter` | 实现 `RedisCacheWriter.TtlFunction`，在基准 TTL 上叠加 0 ~ `maxJitter` 动态偏移量打散失效时刻 | `JitterTtlFunctionTest`<br>`AtengRedisAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-redis` 坚持双核融合架构，摒弃“Spring Data Redis 与 Redisson 二选一”的传统割裂思维，同时规避了官方 `redisson-spring-boot-starter` 强行覆盖官方 `RedisConnectionFactory` 带来的隐式冲突与序列化漂移。

- **JDK 基线**：JDK 17 LTS（字节码 17，兼容 21+ 运行）
- **Spring Boot 基线**：`3.3.13`
- **核心依赖**：Spring Data Redis (`Lettuce` 高性能异步驱动) + Redisson `3.38.1`（原生分布式协调内核）

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

### 全局 Key 前缀隔离与各组件生效规则

在多环境共享 Redis 集群或微服务多租户隔离场景中，前缀边界治理是防止键冲突与误清理的核心防线。`ateng-boot-redis` 提供统一全局配置项 `ateng.boot.redis.key-prefix`，各组件遵循以下规范自洽响应：

| 组件 / 门面 / 注解 | 前缀生效机制与行为 | 典型配置项 (`ateng.boot.redis.*`) | 典型键结构示例 (`key-prefix: "sample:"`) |
| :--- | :--- | :--- | :--- |
| **`RedisUtil` (通用数据门面)** | 静态门面保留底层 key 灵活度；开发者可通过 `RedisUtil.buildKey(key)` 显式包装拼接全局前缀 | `key-prefix: "sample:"` | `RedisUtil.buildKey("cart:1001")` -> `sample:cart:1001` |
| **`RedisAtomicUtil` (流水号与CAS)** | `generateDailySeq(bizPrefix, length)` 结构化发号自动注入全局前缀；原子增减与 CAS 支持自动/显式前缀 | `key-prefix: "sample:"` | `ORD20261108000001` (底层 Redis 存储键为 `sample:order:seq:20261108`) |
| **`RedisBloomFilter` / `RedisBloomUtil`** | 分布式布隆过滤器名称在初始化与访问时自动拼接全局前缀 | `key-prefix: "sample:"` | 逻辑过滤器 `user-blacklist` -> 底层存储为 `sample:user-blacklist` |
| **`RedisRateLimiterClient`** | 令牌桶限流器键名自动统一拼接全局前缀隔离 | `key-prefix: "sample:"` | 逻辑限流键 `api:sms:13800000000` -> 底层存储为 `sample:api:sms:13800000000` |
| **`@DistributedLock` / `DistributedLockTemplate` / `RedisLockUtil`** | 锁前缀优先使用专有配置 `lock.prefix`（默认 `lock:`），叠加在业务 Key 之前，防止与业务缓存数据混淆 | `lock.prefix: "lock:"` | 业务 Key `order:ORD_1001` -> 加锁键为 `lock:order:ORD_1001` |
| **`RedisDelayedQueue` / `@DelayedQueueListener`** | 业务延时队列名保持定义字面量；重试耗尽死信队列自动追加 `:dlq`（可配置）后缀 | `delay-queue.dead-letter-suffix: ":dlq"` | 业务队列 `sample:order:cancel`，对应死信队列 `sample:order:cancel:dlq` |
| **`RedisStreamTemplate` / `@RedisStreamListener`** | 流名称（Stream Key）保持定义字面量；死信流自动追加 `:dlq`（可配置）后缀 | `stream.dlq-suffix: ":dlq"` | 业务流 `stream:order:events`，对应死信流 `stream:order:events:dlq` |
| **`RedisCacheManager` (单级 Spring Cache)** | 当配置全局非空前缀时自动挂载；前缀为空时保持 Spring Data Redis 原生 `cacheName::key` 规范 | `key-prefix: "sample:"` | 注解 `@Cacheable(value = "user", key = "#id")` -> 缓存键为 `sample:user::1001` |
| **`TwoLevelCacheManager` (两级缓存)** | 本地 L1 内存 + Redis L2 存储；跨节点广播失效主题集中解耦；L2 缓存名按 `cacheName:key` 隔离 | `two-level.topic: "ateng:cache:evict:topic"` | 广播主题 `ateng:cache:evict:topic`，L2 Redis 键 `user-profile:2001` |

### RedissonClient 生产自定义接管与缺省守卫
当现有自动化配置无法满足集群特异性参数（如特定 SSL 证书、自定义编解码管道或监控指标导出）时，下游应用仅需声明同类型 Bean 即可 100% 接管 Redisson 装配（恪守 `@ConditionalOnMissingBean` 守卫铁律）：

```java
@Configuration(proxyBeanMethods = false)
public class CustomRedissonConfiguration {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setConnectionMinimumIdleSize(16)
                .setConnectionPoolSize(64)
                .setPingConnectionInterval(30000);
        return Redisson.create(config);
    }
}
```

### Redisson TCP 心跳保活与云原生 NAT 静默切断弹性防御机制
- **PING 心跳保活机制**：在云原生容器网络、跨可用区专线或公有云 NAT 网关环境下，长时间无数据通信的空闲 TCP 连接常被防火墙或网关静默切断（Silent Drop），导致应用在下一次发起 Redis 命令时因使用失效连接出现 `Connection reset by peer` 错误。`AtengRedisAutoConfiguration` 统一配置 `pingConnectionInterval`（默认 30s），由 Redisson 客户端后台定期向 Redis 服务端发送 PING 命令检测并维系物理链路活跃，彻底解决长连接偶发断开难题；
- **弹性重试与连接池治理**：标配生产合理默认值（主连接池 64，最小空闲 16，重试 3 次，重试间隔 1500ms），保障生产突发流量洪峰下平稳过渡。

---

## 3. 通用数据操作门面、原子 Lua 脚本与跨天分布式流水号

### 场景痛点与工业级防卫
1. **单线程阻塞与大 Key 隐患**：生产环境绝对严禁调用 `KEYS *`、`HKEYS` 或 `SMEMBERS`，当集合包含百万级大 Key 时会导致单线程 Redis 发生秒级阻塞停机。`RedisUtil.scanKeys` 与 `hScan` 封装了游标分批拉取机制（基于 `SCAN` / `HSCAN` 原语），对外暴露纯流式安全 API，单次拉取数量受控，杜绝 CPU 突刺与连接超时。
2. **多往返网络风暴与并发扣减超卖**：高频优惠券/库存核销场景若采用“GET 查询 -> 校验资格 -> SET 扣减 -> SADD 记录”多步网络交互，既产生多次 RTT 网络延迟，更在并发场景下引发严重超卖。`RedisScriptExecutor` 采用受管 Lua 脚本引擎，自动执行 SHA1 预编译缓存与 `EVALSHA` 优先执行策略，保证核销判定与扣减在单原子事务内完成。
3. **流水号跨天未归零与内存无界泄露**：传统分布式发号要么强依赖数据库自增主键（并发性能低、事务开销大），要么使用简单的 Redis 自增 key（跨天无法平滑重置，或永久保留导致键空间持续膨胀泄露）。`RedisAtomicUtil.generateDailySeq` 采用跨天自然日结构化 Key，原子自增并内置 **48 小时安全 TTL**，既能实现跨天自然归零与补零对齐，又彻底防止历史废弃键永久残留泄露。

### YAML 配置闭环

```yaml
ateng:
  boot:
    redis:
      enabled: true
      key-prefix: "sample:"
```

### 真实业务实战代码（1:1 提取自 `ateng-boot-sample-redis`）

以下代码 1:1 提取自场景样例工程中的业务控制器 [`RedisOpsAndScriptController`](../../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/controller/RedisOpsAndScriptController.java) 与核销脚本 [`coupon_deduct.lua`](../../ateng-boot-samples/ateng-boot-sample-redis/src/main/resources/scripts/coupon_deduct.lua)：

```java
@Slf4j
@RestController
@RequestMapping("/sample/redis/ops")
@RequiredArgsConstructor
public class RedisOpsAndScriptController {

    private final RedisScriptExecutor redisScriptExecutor;

    // ==========================================
    // 1. 购物车管理：RedisUtil 散列 Hash 结构
    // ==========================================

    @PostMapping("/cart/items")
    public Result<Boolean> addCartItem(@Valid @RequestBody CartItemRequest request) {
        String cartKey = RedisUtil.buildKey("cart:" + request.getUserId());
        String skuId = request.getSkuId();

        // 检查商品是否已在购物车中：存在则增量累计，不存在则全量写入并设置 7 天滑动过期
        CartItemVO existing = RedisUtil.hGet(cartKey, skuId, CartItemVO.class);
        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + request.getQuantity());
            existing.setPrice(request.getPrice());
            RedisUtil.hSet(cartKey, skuId, existing);
        } else {
            CartItemVO item = CartItemVO.builder()
                    .skuId(skuId)
                    .skuName(request.getSkuName())
                    .quantity(request.getQuantity())
                    .price(request.getPrice())
                    .build();
            RedisUtil.hSet(cartKey, skuId, item);
        }
        RedisUtil.expire(cartKey, Duration.ofDays(7));
        return Result.success(true);
    }

    @GetMapping("/cart/{userId}")
    public Result<CartVO> getCart(@PathVariable String userId) {
        String cartKey = RedisUtil.buildKey("cart:" + userId);
        Map<Object, Object> entries = RedisUtil.hGetAll(cartKey);
        // 组装返回购物车明细列表与总金额
        List<CartItemVO> items = entries.values().stream()
                .map(val -> JsonUtil.toObject(JsonUtil.toJson(val), CartItemVO.class))
                .toList();
        return Result.success(new CartVO(userId, items));
    }

    // ==========================================
    // 2. 热销榜单：RedisUtil 有序集合 ZSet 结构
    // ==========================================

    @PostMapping("/rank/score")
    public Result<Double> addProductScore(@Valid @RequestBody ProductScoreRequest request) {
        String rankKey = RedisUtil.buildKey("rank:daily_hot");
        // 为商品累加销售热度积分
        Double newScore = RedisUtil.zIncrScore(rankKey, request.getProductId(), request.getScoreDelta());
        return Result.success(newScore);
    }

    @GetMapping("/rank/top/{limit}")
    public Result<List<ProductRankVO>> getTopRank(@PathVariable int limit) {
        String rankKey = RedisUtil.buildKey("rank:daily_hot");
        // 倒序拉取 Top N 热销商品与热度值
        Set<ZSetOperations.TypedTuple<Object>> tuples = RedisUtil.reverseRangeWithScores(rankKey, 0, limit - 1);
        List<ProductRankVO> ranks = tuples.stream()
                .map(t -> new ProductRankVO(String.valueOf(t.getValue()), t.getScore()))
                .toList();
        return Result.success(ranks);
    }

    // ==========================================
    // 3. 安全游标扫描：RedisUtil 纯 SCAN 分批拉取
    // ==========================================

    @GetMapping("/keys/scan")
    public Result<ScanResultVO> scanKeys(@RequestParam String pattern, @RequestParam(defaultValue = "100") long count) {
        // 底层封装 SCAN 游标分批分段遍历，彻底杜绝 KEYS * 阻塞单线程
        Set<String> matchedKeys = RedisUtil.scanKeys(pattern, count);
        return Result.success(new ScanResultVO(pattern, matchedKeys.size(), matchedKeys));
    }

    // ==========================================
    // 4. 原子核销优惠券：受管 Lua 脚本执行器
    // ==========================================

    @PostMapping("/coupon/deduct")
    public Result<CouponDeductVO> deductCoupon(@Valid @RequestBody CouponDeductRequest request) {
        String stockKey = RedisUtil.buildKey("coupon:" + request.getCouponId() + ":stock");
        String usersKey = RedisUtil.buildKey("coupon:" + request.getCouponId() + ":users");

        // 一次性原子判定：库存检查 -> 防重复领取检查 -> 扣减库存 -> 用户入集合记录
        Long code = redisScriptExecutor.executeClasspath(
                "scripts/coupon_deduct.lua",
                Long.class,
                List.of(stockKey, usersKey),
                request.getUserId()
        );

        // 返回码语义：1-成功扣减，-1-已领过，-2-库存不足，-3-优惠券活动未初始化
        boolean success = (code != null && code == 1L);
        return Result.success(new CouponDeductVO(request.getCouponId(), request.getUserId(), success, code));
    }

    // ==========================================
    // 5. 跨天自重置流水号与 CAS 水位：RedisAtomicUtil
    // ==========================================

    @PostMapping("/sequence/daily")
    public Result<SequenceVO> generateDailySequence(@RequestParam(defaultValue = "order") String bizPrefix,
                                                   @RequestParam(defaultValue = "6") int length) {
        // 自动按当天日期 formatKey 生成类似 ORD20261108000001 的序列号，内置 48h TTL
        String seq = RedisAtomicUtil.generateDailySeq(bizPrefix, length);
        return Result.success(new SequenceVO(bizPrefix, seq));
    }

    @PostMapping("/watermark/cas")
    public Result<WatermarkUpdateVO> updateWatermark(@Valid @RequestBody WatermarkCasRequest request) {
        String key = RedisUtil.buildKey("watermark:" + request.getResourceId());
        // CAS 原子比较置换：仅当当前值等于 expectedValue 时更新为 newValue
        boolean updated = RedisAtomicUtil.compareAndSet(key, request.getExpectedValue(), request.getNewValue());
        return Result.success(new WatermarkUpdateVO(request.getResourceId(), updated));
    }
}
```

配套原子优惠券核销 Lua 脚本 [`scripts/coupon_deduct.lua`](../../ateng-boot-samples/ateng-boot-sample-redis/src/main/resources/scripts/coupon_deduct.lua)：
```lua
-- KEYS[1]: 优惠券库存键 (String)
-- KEYS[2]: 已领券用户集合键 (Set)
-- ARGV[1]: 当前用户 ID

local stock_key = KEYS[1]
local users_key = KEYS[2]
local user_id = ARGV[1]

-- 1. 校验优惠券库存键是否存在
if redis.call('EXISTS', stock_key) == 0 then
    return -3 -- 优惠券不存在或未初始化
end

-- 2. 校验用户是否已领取过该券（防重领）
if redis.call('SISMEMBER', users_key, user_id) == 1 then
    return -1 -- 用户已领取过
end

-- 3. 校验并扣减库存
local current_stock = tonumber(redis.call('GET', stock_key) or '0')
if current_stock <= 0 then
    return -2 -- 库存不足
end

-- 4. 原子扣减库存并记录用户领券集合
redis.call('DECR', stock_key)
redis.call('SADD', users_key, user_id)
return 1 -- 领取成功
```

### 预期控制台呈现

```text
2026-11-08 11:46:57.202 DEBUG [main] [5aed6f6373722bd1337d215175b9fbe1] m.ApiVersionRequestMappingHandlerMapping : Mapped to io.github.atengk.boot.sample.redis.controller.RedisOpsAndScriptController#addCartItem(CartItemRequest)
2026-11-08 11:47:05.150 DEBUG [main] [8a1290bb4ce5a7186bcebb716f410214] i.g.a.b.r.s.RedisScriptExecutor             : [RedisScript] 优先通过 EVALSHA 执行 Lua 脚本成功: sha=4ad20bf1832d73
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

### 专属 YAML 配置片段

```yaml
ateng:
  boot:
    redis:
      lock:
        enabled: true                         # 是否启用分布式锁功能 (默认 true)
        key-prefix: "lock:"                   # 锁键统一定义前缀 (默认 lock:)
        default-wait-time: 3000               # 默认等待锁超时时间 (毫秒，默认 3000ms)
        default-lease-time: -1                # 默认锁持有租期 (-1 表示激活看门狗 30s 自动续期)
        slow-lock-threshold: 3000             # 慢锁审计日志打印阈值 (毫秒，默认 3000ms)
```

### 核心代码示例（1:1 逆向自 `StockDeductScenarioService`、`OrderSettlementService` 与 `RedisLockController`）

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDeductScenarioService {

    private final DistributedLockTemplate distributedLockTemplate;
    private final Map<Long, Integer> stockTable = new ConcurrentHashMap<>();
    private final Map<String, PromotionRuleVO> promoRuleTable = new ConcurrentHashMap<>();
    private final AtomicInteger fairQueueCounter = new AtomicInteger(0);

    /**
     * 1. 声明式可重入锁扣减秒杀库存（防超卖，LockMode.REENTRANT）
     */
    @DistributedLock(key = "'product:' + #request.productId", waitTime = 3000, leaseTime = 5000, mode = LockMode.REENTRANT)
    public StockDeductResultVO deductStock(StockDeductRequest request) {
        Long productId = request.getProductId();
        int count = request.getCount();
        log.info("[StockDeduct] 获取分布式锁成功，开始扣减库存: productId={}, count={}", productId, count);

        Integer currentStock = stockTable.get(productId);
        if (currentStock == null || currentStock < count) {
            throw new BusinessException("库存不足，当前库存: " + currentStock + ", 拟扣减: " + count);
        }
        int remaining = currentStock - count;
        stockTable.put(productId, remaining);
        return StockDeductResultVO.builder()
                .productId(productId)
                .remainingStock(remaining)
                .success(true)
                .message("库存扣减成功")
                .build();
    }

    /**
     * 2. 促销规则写锁更新（排他写锁 LockMode.WRITE，阻塞所有读写操作）
     */
    public PromotionRuleVO updatePromotionRule(PromotionRuleUpdateRequest request) {
        String lockKey = "promo:rule:" + request.getRuleId();
        return distributedLockTemplate.execute(
                lockKey,
                LockMode.WRITE,
                3000L,
                5000L,
                TimeUnit.MILLISECONDS,
                () -> {
                    log.info("[StockDeduct] 获取排他写锁成功，更新促销规则: ruleId={}", request.getRuleId());
                    PromotionRuleVO vo = PromotionRuleVO.builder()
                            .ruleId(request.getRuleId())
                            .ruleName(request.getRuleName())
                            .discountRate(request.getDiscountRate())
                            .updateTime(System.currentTimeMillis())
                            .build();
                    promoRuleTable.put(request.getRuleId(), vo);
                    return vo;
                }
        );
    }

    /**
     * 3. 促销规则读锁查询（共享读锁 LockMode.READ，允许多线程并发读取，阻塞写操作）
     */
    public PromotionRuleVO getPromotionRule(String ruleId) {
        String lockKey = "promo:rule:" + ruleId;
        return distributedLockTemplate.execute(
                lockKey,
                LockMode.READ,
                3000L,
                5000L,
                TimeUnit.MILLISECONDS,
                () -> {
                    PromotionRuleVO rule = promoRuleTable.get(ruleId);
                    if (rule == null) {
                        throw new BusinessException("促销规则不存在: " + ruleId);
                    }
                    return rule;
                }
        );
    }

    /**
     * 4. 稀缺名额排队获取（公平锁 LockMode.FAIR，严格遵循 FIFO 排队）
     */
    @DistributedLock(key = "'fair:activity:' + #request.activityId", waitTime = 3000, leaseTime = 5000, mode = LockMode.FAIR)
    public FairQuotaVO acquireFairQuota(FairQuotaRequest request) {
        int queueNo = fairQueueCounter.incrementAndGet();
        return FairQuotaVO.builder()
                .activityId(request.getActivityId())
                .userId(request.getUserId())
                .queueNumber(queueNo)
                .acquired(true)
                .message("成功排队获取稀缺活动名额")
                .build();
    }

    /**
     * 5. 组合套餐多 SKU 批量原子联锁 (MultiLock，自动去重与跨资源加锁)
     */
    public MultiSkuDeductVO deductMultiSku(MultiSkuDeductRequest request) {
        List<String> lockKeys = request.getProductIds().stream()
                .map(id -> "product:" + id)
                .toList();

        return distributedLockTemplate.execute(
                lockKeys,
                LockMode.REENTRANT,
                3000L,
                5000L,
                TimeUnit.MILLISECONDS,
                () -> {
                    log.info("[StockDeduct] 批量联锁获取成功，扣减组合套餐: comboId={}", request.getComboId());
                    for (Long productId : request.getProductIds()) {
                        Integer current = stockTable.get(productId);
                        if (current == null || current < request.getQuantity()) {
                            throw new BusinessException("商品库存不足，扣减终止: productId=" + productId);
                        }
                    }
                    for (Long productId : request.getProductIds()) {
                        stockTable.compute(productId, (k, v) -> v - request.getQuantity());
                    }
                    return MultiSkuDeductVO.builder()
                            .comboId(request.getComboId())
                            .deductedCount(request.getQuantity())
                            .success(true)
                            .message("组合套餐联锁扣减成功")
                            .build();
                }
        );
    }

    /**
     * 6. 争锁超时降级排队（fallback 闭包引导）
     */
    public LockFallbackVO executeWithFallback(LockFallbackRequest request) {
        String resourceId = request.getResourceId();
        return distributedLockTemplate.execute(
                "resource:" + resourceId,
                () -> LockFallbackVO.builder().resourceId(resourceId).fallbackTriggered(false).result("NORMAL_SUCCESS").build(),
                () -> LockFallbackVO.builder().resourceId(resourceId).fallbackTriggered(true).result("FALLBACK_TRIGGERED").build()
        );
    }

    /**
     * 7. 静态门面 RedisLockUtil 加锁（免注入 Spring Bean）
     */
    public StaticLockVO executeStaticLock(StaticLockRequest request) {
        String key = "static:" + request.getResourceId();
        return RedisLockUtil.execute(key, () -> StaticLockVO.builder()
                .resourceId(request.getResourceId())
                .locked(true)
                .message("静态门面锁执行成功")
                .build());
    }
}
```

```java
/**
 * 跨 Bean 服务嵌套重入锁演示
 */
@Service
@RequiredArgsConstructor
public class OrderSettlementService {

    private final StockDeductScenarioService stockDeductScenarioService;

    @DistributedLock(key = "'product:' + #request.productId", waitTime = 3000, leaseTime = 5000, mode = LockMode.REENTRANT)
    public OrderSettleVO settleOrder(OrderSettleRequest request) {
        // 同一线程内跨 Service 调用挂载同一锁键的方法，自动重入持锁，不发生死锁
        StockDeductResultVO deductResult = stockDeductScenarioService.deductStock(
                StockDeductRequest.builder()
                        .productId(request.getProductId())
                        .count(request.getQuantity())
                        .build()
        );
        return OrderSettleVO.builder()
                .orderId(request.getOrderId())
                .productId(request.getProductId())
                .remainingStock(deductResult.getRemainingStock())
                .status("SETTLED")
                .build();
    }
}
```

### 核心生产机制与异常边界
1. **状态判断释放范式**：模板在 `finally` 块中严格检查 `lock.isHeldByCurrentThread()`，仅当当前线程真实持有锁时才执行 `unlock()`，彻底消除锁超时后误删其他线程所持新锁引发的严重次生异常；
2. **看门狗与租期互斥**：当 `leaseTime = -1` 时，由 Redisson 看门狗每隔 10 秒自动续期至 30 秒，直至业务线程主动退出；一旦显式指定 `leaseTime > 0`，看门狗自动失效，到达指定租期硬熔断防死锁；
3. **慢锁审计告警**：业务执行耗时若超出 `slow-lock-threshold`（默认 3 秒），模板自动打印 WARN 级审计日志，并携带锁键、模式与精确毫秒耗时；
4. **MultiLock 键排序去重**：传递给 MultiLock 的键列表会自动执行去重，确保底层以固定顺序一次性尝试获取所有联锁，规避循环等待引起的死锁。

---

## 5. 轻量可靠延时队列 (`RedisDelayedQueue` & `@DelayedQueueListener`)

### 场景痛点与工业级防卫
在超时自动关单、未支付催付提醒、工单超时自动流转等延时场景中，直接定时轮询数据库会对存储带来巨大的读写压力；而引入 RocketMQ/Kafka 延时队列会显著提高系统的部署与运维门槛。
`ateng-boot-redis` 基于 Redisson `RDelayedQueue` + `RBlockingQueue` 构建高可靠双模延时队列底座：
- **双模一体化消费**：既支持编程式按需投递与拉取（`RedisDelayedQueue`），也支持 Spring 容器托管的声明式注解消费（`@DelayedQueueListener`）；
- **全受管线程池与四维上下文透传**：`DelayedQueueBootstrap` 调度容器严禁私自创建野线程，完全接入 Spring 容器受管的 `ThreadPoolTaskExecutor`，并通过 `ContextCopyingTaskDecorator` 完整透传 $\langle\text{MDC traceId}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$；
- **真实退避重试与自动封送死信 (DLQ)**：当消费逻辑抛出未捕获异常时，框架根据配置的 `retry-times` 与 `retry-delay` 实施退避重试；重试耗尽后自动将包装完整的 `DeadLetterMessage<T>` 封送至 `{queue}:dlq` 死信队列，并回调 `DelayedQueueFailureHandler` SPI；
- **平滑优雅停机**：监听容器实现 `DisposableBean`，在 JVM 停止时平滑退出阻塞循环，杜绝正在处理的延时任务发生句柄悬挂或丢单。

### 特性矩阵
| 特性能力 | 核心类 / 注解 / SPI | 关键行为与配置 |
| :--- | :--- | :--- |
| **编程式延时投递** | `RedisDelayedQueue.offer(queue, payload, delay)` | 精确延时投递，底层基于 Redis ZSet 时间戳分值调度 |
| **静态门面操作** | `RedisDelayedQueueUtil.*` | 静态上下文快速调用 `offer`、`poll`、`size`、`peekDeadLetters` 与 `replayDeadLetter` |
| **声明式监听消费** | `@DelayedQueueListener(queue, concurrency)` | 自动扫描注册守护线程，多并发受管线程池驱动 |
| **死信自动封送** | `redisDelayedQueue.sendToDeadLetterQueue` | 重试耗尽自动封送 `{queue}:dlq`，包含重试次数与异常堆栈 |
| **死信受控重放** | `replayDeadLetter` / `replayDeadLetters` | 弹出死信校验 `replayCount < maxReplayAttempts`，未超限递增并重新投递，超限阻断熔断 |
| **死信非破坏检视** | `peekDeadLetters` / `pollDeadLetter` / `deadLetterSize` | 运维端点批量检视、拉取、统计与清空 |
| **失败处理 SPI** | `DelayedQueueFailureHandler` | 重试耗尽终态告警回调，支持外部通知（钉钉/飞书）与审计落库 |

### 专属 YAML 配置片段
```yaml
ateng:
  boot:
    redis:
      delay-queue:
        enabled: true                 # 开启轻量可靠延时队列 (默认 false)
        retry-times: 3                # 消费异常最大重试次数 (默认 3)
        retry-delay: 200ms            # 重试退避间隔时长 (默认 3s)
        dead-letter-enabled: true     # 重试耗尽是否流转至死信队列 (默认 true)
        dead-letter-suffix: ":dlq"    # 死信队列名称后缀 (默认 :dlq)
        max-replay-attempts: 3        # 死信最大受控重放次数，超限熔断防二次死信雪崩 (默认 3)
```

### 真实业务实战代码（1:1 提取自 `ateng-boot-sample-redis`）
```java
// 1. 业务场景服务：编程式投递、声明式监听与毒丸演练
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDelayScenarioService {

    public static final String QUEUE_ORDER_CANCEL = "sample:order:cancel";

    private final RedisDelayedQueue redisDelayedQueue;
    private final SampleDelayedQueueFailureHandler failureHandler;
    private final List<OrderDelayPayload> consumedOrders = new CopyOnWriteArrayList<>();

    // 编程式投递订单延时取消任务
    public void sendOrderCancelTask(String orderId, Duration delay, String reason) {
        OrderDelayPayload payload = new OrderDelayPayload(orderId, reason, System.currentTimeMillis());
        log.info("[OrderDelay] 编程式投递延时取消任务: queue={}, orderId={}, delay={}", QUEUE_ORDER_CANCEL, orderId, delay);
        redisDelayedQueue.offer(QUEUE_ORDER_CANCEL, payload, delay);
    }

    // 声明式监听延时队列到期消费（遇到毒丸订单抛出异常触发重试与死信流转）
    @DelayedQueueListener(queue = QUEUE_ORDER_CANCEL, concurrency = 1)
    public void onOrderCancel(OrderDelayPayload payload) {
        if (payload != null && payload.getOrderId() != null && payload.getOrderId().contains("POISON")) {
            log.error("[OrderDelay] 声明式监听捕获毒丸订单，抛出异常触发重试: orderId={}", payload.getOrderId());
            throw new BusinessException("毒丸订单触发模拟异常: orderId=" + payload.getOrderId());
        }
        log.info("[OrderDelay] 声明式监听到期消费: orderId={}, reason={}", payload.getOrderId(), payload.getReason());
        consumedOrders.add(payload);
    }

    // 运维探针：死信拉取、批量检视、受控重放与清空
    public DeadLetterMessage<OrderDelayPayload> pollCancelOrderDeadLetter() {
        return redisDelayedQueue.pollDeadLetter(QUEUE_ORDER_CANCEL, OrderDelayPayload.class);
    }

    public List<DeadLetterMessage<OrderDelayPayload>> peekCancelOrderDeadLetters(int limit) {
        return redisDelayedQueue.peekDeadLetters(QUEUE_ORDER_CANCEL, limit, OrderDelayPayload.class);
    }

    public int replayCancelOrderDeadLetters(int count, Duration delay) {
        return redisDelayedQueue.replayDeadLetters(QUEUE_ORDER_CANCEL, count, delay);
    }

    public void clearCancelOrderDeadLetter() {
        redisDelayedQueue.clearDeadLetterQueue(QUEUE_ORDER_CANCEL);
    }
}

// 2. 生产扩展与 SPI 接管：有界环形缓冲告警处理器
@Slf4j
@Component
public class SampleDelayedQueueFailureHandler implements DelayedQueueFailureHandler {

    private static final int MAX_CAPACITY = 50;
    private final Queue<DeadLetterMessage<?>> recentAlerts = new ConcurrentLinkedQueue<>();

    @Override
    public void onFailed(DeadLetterMessage<?> deadLetterMessage, Throwable cause) {
        log.warn("[DelayedQueueAlert] 捕获延时任务重试耗尽告警: queue={}, retryCount={}, error={}",
                deadLetterMessage.originalQueue(), deadLetterMessage.retryTimes(), deadLetterMessage.errorMessage(), cause);
        while (recentAlerts.size() >= MAX_CAPACITY) {
            recentAlerts.poll();
        }
        recentAlerts.offer(deadLetterMessage);
    }

    public List<DeadLetterMessage<?>> getRecentAlerts() {
        return Collections.unmodifiableList(new ArrayList<>(recentAlerts));
    }
}

// 3. Web 接入层控制器：9 大 RESTful 端点
@Validated
@RestController
@RequestMapping("/sample/redis/delay-queue")
@RequiredArgsConstructor
public class RedisDelayQueueController {

    private final OrderDelayScenarioService orderDelayScenarioService;

    @PostMapping("/order/cancel/send")
    public Result<OrderDelayVO> sendOrderCancel(@Valid @RequestBody OrderDelaySendRequest request) {
        orderDelayScenarioService.sendOrderCancelTask(
                request.getOrderId(), Duration.ofSeconds(request.getDelaySeconds()), request.getReason());
        return Result.success(OrderDelayVO.builder().orderId(request.getOrderId()).status("SCHEDULED").build());
    }

    @GetMapping("/order/cancel/consumed")
    public Result<List<OrderDelayPayload>> getConsumedOrders() {
        return Result.success(orderDelayScenarioService.getConsumedOrders());
    }

    @PostMapping("/order/cancel/send-poison")
    public Result<String> sendPoisonOrder(@RequestParam(value = "orderId", required = false) String orderId) {
        String effectiveOrderId = StringUtils.hasText(orderId) ? orderId : "POISON-ORD-" + IdUtil.fastSimpleUUID();
        orderDelayScenarioService.sendPoisonOrder(effectiveOrderId);
        return Result.success("毒丸演练订单投递成功: orderId=" + effectiveOrderId);
    }

    @GetMapping("/dead-letter/count")
    public Result<DeadLetterSummaryVO> getDeadLetterCount() {
        int count = orderDelayScenarioService.getCancelOrderDeadLetterSize();
        return Result.success(DeadLetterSummaryVO.builder().deadLetterCount(count).build());
    }

    @GetMapping("/dead-letter/poll")
    public Result<DeadLetterMessage<OrderDelayPayload>> pollDeadLetter() {
        return Result.success(orderDelayScenarioService.pollCancelOrderDeadLetter());
    }

    @GetMapping({"/dlq/peek", "/dead-letter/peek"})
    public Result<List<DeadLetterMessage<OrderDelayPayload>>> peekDeadLetters(@RequestParam(defaultValue = "10") int limit) {
        return Result.success(orderDelayScenarioService.peekCancelOrderDeadLetters(limit));
    }

    @PostMapping({"/dlq/replay", "/dead-letter/replay"})
    public Result<Integer> replayDeadLetters(@RequestParam(defaultValue = "1") int count,
                                             @RequestParam(defaultValue = "0") long delaySeconds) {
        return Result.success(orderDelayScenarioService.replayCancelOrderDeadLetters(count, Duration.ofSeconds(delaySeconds)));
    }

    @DeleteMapping("/dead-letter/clear")
    public Result<Boolean> clearDeadLetter() {
        orderDelayScenarioService.clearCancelOrderDeadLetter();
        return Result.success(true);
    }

    @GetMapping("/alerts")
    public Result<List<DeadLetterMessage<?>>> getAlerts() {
        return Result.success(orderDelayScenarioService.getRecentAlerts());
    }
}
```

### 核心生产机制与异常边界
1. **受管线程池与四维上下文透传**：`DelayedQueueBootstrap` 消费轮询彻底淘汰裸 `Executors`，接入 Spring 容器受管的 `ThreadPoolTaskExecutor` 并挂载 `ContextCopyingTaskDecorator`，确保 `traceId` 等上下文在延时队列消费线程中合规生效；
2. **死信流转与错误隔离**：内置异常受控重试；重试耗尽后若 `deadLetterEnabled=true` 则将包装完整的 `DeadLetterMessage<T>` 投递至 `${queueName}:dlq` 并触发 `DelayedQueueFailureHandler` SPI，杜绝毒丸消息阻塞队列并保障可追溯审计；
3. **死信受控重放与防雪崩守卫**：死信消息具备 `replayCount` 累计重放计数与不可变属性；执行 `replayDeadLetter` 重放时自动校验当前重放次数是否达到 `ateng.boot.redis.delay-queue.max-replay-attempts`（默认 3 次），达到或超过上限时拒绝重放并记录告警，阻断因毒丸消息引发的无限重试与二次死信风暴；
4. **平滑优雅停机**：监听容器实现了 `DisposableBean`，在 JVM 或 Spring 容器销毁时首先停止拉取新消息，等待在途任务执行完成并注销内部阻塞循环与受管线程池，保障零消息悬挂。

---

## 6. Redis 5.0+ Stream 可靠流式消息引擎 (`RedisStreamTemplate` & `RedisStreamUtil` & `@RedisStreamListener`)

### 场景痛点与工业级防卫
传统 Redis Pub/Sub 广播虽然轻量，但不支持消息持久化、不支持 ACK 确认、无法追溯历史消费偏离且缺少消费组集群均衡模型。一旦订阅节点在瞬态发布期间短暂重启或网络抖动断线，期间发布的所有通知全部静默丢失。
`ateng-boot-redis` 深度封装 Redis 5.0+ 原生 Stream 原语，提供全套生产级可靠流引擎底座：
- **消费组模型与自动创建**：支持分布式消费组（Consumer Group），通过 `@RedisStreamListener(autoCreateGroup = true)` 自动幂等创建目标消费组，多实例集群协同消费分摊负载；
- **显式与自动 ACK 治理**：支持 `autoAck = true`（消费成功由框架自动执行 XACK）以及手动 ACK 模式，保证消息 At-Least-Once 可靠投递；
- **PEL 待决排查与孤儿认领 (XCLAIM)**：`RedisStreamTemplate` 与 `RedisStreamUtil` 原生提供 `pendingSummary`、`pending` 与 `claim` 接口，支持对超时挂起消息实施安全探查与孤儿抢占补偿；
- **流长度定长裁剪 (XTRIM)**：支持 `trim(stream, maxLen)` 对流式队列进行物理定长裁剪，防止流历史无限膨胀占用过多内存；
- **死信流转 (DLQ)**：消费重试耗尽后封装 `StreamDeadLetterMessage` 流转至目标死信流（默认 `${stream}:dlq`），同时触发 `StreamFailureHandler` SPI。

### 特性矩阵
| 特性能力 | 核心类 / 注解 / SPI | 关键行为与配置 |
| :--- | :--- | :--- |
| **流消息投递** | `RedisStreamTemplate.send` / `RedisStreamUtil.send` | 模板与静态门面双路径投递，自动注入 MDC `traceId`、租户与用户四维上下文 |
| **声明式组消费** | `@RedisStreamListener(stream, group, concurrency, autoAck)` | `SmartLifecycle` 受管线程池并发驱动，四维上下文安全恢复与清理，业务入参自动过滤内部元数据 |
| **PEL 待决探查** | `pendingSummary` / `getPendingMessages` | XPENDING 概要统计与明细拉取，实时掌握未确认积压情况 |
| **孤儿消息自愈认领** | `claimPendingMessages` / `claim` | 后台主循环周期自愈巡检或手动 XCLAIM 孤儿消息转移至健康消费者，防止消息永久悬挂 |
| **死信受控重放** | `replayDeadLetter` / `replayDeadLetters` | 校验累计重放次数未超 `max-replay-attempts`，重新入流投递，防死信死循环雪崩 |
| **死信非破坏检视** | `peekDeadLetters` / `deadLetterSize` / `clearDeadLetters` | 运维探针批量查看死信流历史，支持统计大小与清理 |
| **定长修剪** | `trim(stream, maxLen)` | XTRIM 精确/近似定长裁剪，控制流内存物理占用 |
| **死信与失败 SPI** | `transferToDeadLetter` / `StreamFailureHandler` | 重试耗尽转移至 `${stream}:dlq`，触发 SPI 告警，成功后安全 ACK 原消息 |

### 专属 YAML 配置片段
```yaml
ateng:
  boot:
    redis:
      stream:
        enabled: true                 # 开启 Redis 5.0+ Stream 流消息引擎 (默认 false)
        poll-timeout: 2s              # 轮询阻塞等待超时时间 (默认 2s)
        batch-size: 10                # 单次批量拉取消息最大条数 (默认 10)
        retry-times: 3                # 消费重试次数上限 (默认 3)
        dlq-suffix: ":dlq"            # 死信流名称后缀 (默认 :dlq)
        max-replay-attempts: 3        # 死信最大受控重放次数，超限熔断防二次雪崩 (默认 3)
        claim-idle-time: 1m           # PEL 孤儿待决消息自动认领最小闲置超时时长 (默认 1m)
        claim-batch-size: 20          # PEL 孤儿待决消息自动认领单批最大拉取条数 (默认 20)
```

### 真实业务实战代码（1:1 提取自 `ateng-boot-sample-redis`）
```java
// 1. 业务场景服务：模板与静态门面投递、声明式消费、PEL 探查、XCLAIM 认领与死信受控重放
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamEventScenarioService {

    public static final String STREAM_ORDER_EVENTS = "stream:order:events";
    public static final String GROUP_ORDER_PROCESSOR = "group:order:processor";

    private final RedisStreamTemplate redisStreamTemplate;
    private final SampleStreamFailureHandler failureHandler;
    private final List<OrderStreamEvent> receivedEvents = new CopyOnWriteArrayList<>();

    // 模板门面投递（自动透明注入四维上下文）
    public RecordId sendOrderEvent(OrderStreamEvent event) {
        return redisStreamTemplate.send(STREAM_ORDER_EVENTS, event);
    }

    // 静态工具门面投递
    public RecordId sendOrderEventStatic(OrderStreamEvent event) {
        return RedisStreamUtil.send(STREAM_ORDER_EVENTS, event);
    }

    // 声明式监听 Stream 消费组消费（自动 XACK 与四维上下文透传，自动剥离 __ateng_* 系统元数据）
    @RedisStreamListener(stream = STREAM_ORDER_EVENTS, group = GROUP_ORDER_PROCESSOR, concurrency = 1, autoAck = true)
    public void onOrderEvent(OrderStreamEvent event) {
        if (event != null && event.getOrderId() != null && event.getOrderId().contains("POISON")) {
            log.error("[StreamEvent] 监听到毒丸流事件，抛出异常触发重试: orderId={}", event.getOrderId());
            throw new BusinessException("Stream 流事件毒丸订单触发模拟异常: orderId=" + event.getOrderId());
        }
        log.info("[StreamEvent] 监听到消费组流事件: orderId={}, eventType={}, amount={}",
                event.getOrderId(), event.getEventType(), event.getAmount());
        receivedEvents.add(event);
    }

    // PEL 待决概要探查
    public PendingMessagesSummary getPendingSummary() {
        return redisStreamTemplate.pendingSummary(STREAM_ORDER_EVENTS, GROUP_ORDER_PROCESSOR);
    }

    // 自动认领 PEL 孤儿待决消息
    public List<MapRecord<String, Object, Object>> claimPendingMessages(String newConsumer, Duration minIdleTime, int count) {
        return redisStreamTemplate.claimPendingMessages(STREAM_ORDER_EVENTS, GROUP_ORDER_PROCESSOR, newConsumer, minIdleTime, count);
    }

    // 显式 XCLAIM 孤儿消息认领
    public List<MapRecord<String, Object, Object>> claimOrphan(String newConsumer, Duration minIdleTime, String... recordIds) {
        RecordId[] ids = Arrays.stream(recordIds).map(RecordId::of).toArray(RecordId[]::new);
        return redisStreamTemplate.claim(STREAM_ORDER_EVENTS, GROUP_ORDER_PROCESSOR, newConsumer, minIdleTime, ids);
    }

    // 运维探针：死信检视与受控重放
    public List<Map<String, Object>> getDeadLetterRecords(long count) {
        return redisStreamTemplate.peekDeadLetters(STREAM_ORDER_EVENTS, count);
    }

    public boolean replayDeadLetter(String recordId) {
        return redisStreamTemplate.replayDeadLetter(STREAM_ORDER_EVENTS, recordId);
    }

    public int replayDeadLetters(int count) {
        return redisStreamTemplate.replayDeadLetters(STREAM_ORDER_EVENTS, count);
    }

    // XTRIM 定长修剪
    public Long trimStream(long maxLen) {
        return redisStreamTemplate.trim(STREAM_ORDER_EVENTS, maxLen);
    }
}

// 2. 生产扩展与 SPI 接管：有界环形缓冲告警处理器
@Slf4j
@Component
public class SampleStreamFailureHandler implements StreamFailureHandler {

    private static final int MAX_CAPACITY = 50;
    private final Queue<StreamDeadLetterMessage> recentAlerts = new ConcurrentLinkedQueue<>();

    @Override
    public void onFailure(StreamDeadLetterMessage deadLetterMessage) {
        log.warn("[StreamAlert] 捕获 Stream 消费重试耗尽告警: stream={}, group={}, consumer={}, recordId={}, error={}",
                deadLetterMessage.getStream(), deadLetterMessage.getGroup(), deadLetterMessage.getConsumer(),
                deadLetterMessage.getRecordId(), deadLetterMessage.getExceptionMessage());
        while (recentAlerts.size() >= MAX_CAPACITY) {
            recentAlerts.poll();
        }
        recentAlerts.offer(deadLetterMessage);
    }

    public List<StreamDeadLetterMessage> getRecentAlerts() {
        return Collections.unmodifiableList(new ArrayList<>(recentAlerts));
    }
}

// 3. Web 接入层控制器：11 大 RESTful 端点
@Validated
@RestController
@RequestMapping("/sample/redis/stream")
@RequiredArgsConstructor
public class RedisStreamController {

    private final StreamEventScenarioService streamEventScenarioService;

    @PostMapping("/events/template")
    public Result<StreamEventVO> sendEventByTemplate(@Valid @RequestBody OrderStreamSendRequest request) { ... }

    @PostMapping("/events/static")
    public Result<StreamEventVO> sendEventByStatic(@Valid @RequestBody OrderStreamSendRequest request) { ... }

    @GetMapping("/consumed")
    public Result<List<OrderStreamEvent>> getConsumedEvents() { ... }

    @GetMapping("/pending/summary")
    public Result<StreamPendingSummaryVO> getPendingSummary() { ... }

    @GetMapping("/pending/messages")
    public Result<List<PendingMessageVO>> getPendingMessages(@RequestParam(value = "count", defaultValue = "10") long count) { ... }

    @PostMapping({"/pending/claim", "/claim"})
    public Result<List<String>> claimOrphans(@RequestBody(required = false) StreamClaimRequest request,
                                             @RequestParam(required = false) String newConsumer,
                                             @RequestParam(defaultValue = "0") long minIdleSeconds,
                                             @RequestParam(defaultValue = "10") int count) { ... }

    @PostMapping("/trim")
    public Result<Long> trimStream(@Valid @RequestBody StreamTrimRequest request) { ... }

    @PostMapping("/events/poison")
    public Result<String> sendPoisonEvent(@RequestParam(value = "orderId", required = false) String orderId) { ... }

    @GetMapping({"/dead-letter", "/dlq/peek", "/dead-letter/peek"})
    public Result<List<Map<String, Object>>> getDeadLetterRecords(@RequestParam(defaultValue = "10") long count) { ... }

    @PostMapping({"/dlq/replay", "/dead-letter/replay"})
    public Result<Integer> replayDeadLetters(@RequestParam(defaultValue = "1") int count,
                                             @RequestParam(required = false) String recordId) { ... }

    @GetMapping("/alerts")
    public Result<List<StreamDeadLetterMessage>> getAlerts() { ... }
}
```

### 核心生产机制与异常边界
1. **At-Least-Once 可靠交付与四维上下文透传**：发送消息时自动采集 MDC `traceId`、租户与用户上下文快照，消费侧由 `StreamBootstrap` 恢复并执行消费，在 `finally` 块中严格清理上下文杜绝污染；将消息转换为方法参数前自动剔除内部 `__ateng_*` 系统键，业务契约纯净解耦；仅当方法成功消费后才确认 XACK；
2. **孤儿挂起消息自动巡检与故障自愈 (PEL Claim)**：`StreamBootstrap` 升级实现 `SmartLifecycle` 规范生命周期契约；消费主循环内置周期性（每 5 秒）PEL 巡检机制，自动调用 `claimPendingMessages` 抢占超过最小闲置时长（`claim-idle-time`，默认 1m）未 ACK 的孤儿消息，实现消费者崩溃后的无感故障转移；同时提供编程式接口支持显式认领；
3. **死信流转、受控重放与防雪崩守卫**：毒丸消息重试耗尽后，`StreamBootstrap` 先将消息转移至 `${stream}:dlq` 死信流，并触发 `StreamFailureHandler` SPI 回调，确认死信入队成功后对原消息执行 XACK 释放（杜绝双重冗余 ACK）；重放死信时严格检查累计重放次数未超 `max-replay-attempts`（默认 3 次），超过上限立即抛出 `BusinessException` 熔断，坚决阻断二次死信雪崩。

---

## 7. 通用业务级分布式防重幂等 (`@RedisIdempotent` & `RedisIdempotentTemplate` & `RedisIdempotentUtil`)

### 场景痛点
在非 Web 链路（第三方支付回调通知、MQ 消费者、定时任务批处理、分布式 RPC 调用）中，重复投递与网络重试是常态。若单纯依赖数据库唯一键，会引发大量的死锁或数据库回滚开销；若手写 Redis `SETNX`，极易发生业务执行失败时未删除防重 Key 导致上游永远无法再次重试的“假死锁”故障。

### 特性矩阵
- **三模一体化支持**：声明式切面 `@RedisIdempotent`、编程式模板 `RedisIdempotentTemplate` 与静态门面 `RedisIdempotentUtil`；
- **SpEL 动态业务键**：支持基于方法入参表达式动态拼装全局防重唯一标识；
- **`deleteOnFailure` 故障自愈机制**：业务成功执行后保留防重 Key 直至 TTL 超时；当业务方法抛出异常时，模板自动识别并**原子删除防重 Key**，确保上游重试机制能够平滑再次触发执行；
- **异常语义清晰**：并发防重冲突时抛出语义明确的 `BusinessException` 并携带自定义错误提示。

### 专属 YAML 配置片段

```yaml
ateng:
  boot:
    redis:
      idempotent:
        enabled: true                         # 是否启用分布式防重幂等功能 (默认 true)
        key-prefix: "idemp:"                   # 防重键统一定义前缀 (默认 idemp:)
        default-expire: 60s                   # 默认防重凭据有效租期 (默认 60s)
```

### 核心代码示例（1:1 逆向自 `IdempotentScenarioService` 与 `RedisIdempotentController`）

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentScenarioService {

    private final RedisIdempotentTemplate redisIdempotentTemplate;

    /**
     * 1. 声明式防重处理第三方支付回调（deleteOnFailure = false，成功入账后锁死防重凭据直至 TTL 超时）
     */
    @RedisIdempotent(key = "'idemp:pay:' + #request.paymentNo", expire = "120s", deleteOnFailure = false, message = "支付流水正在处理中，请勿重复回调")
    public PaymentCallbackVO handlePaymentCallback(PaymentCallbackRequest request) {
        log.info("[IdempotentScenario] 处理第三方支付异步通知: paymentNo={}, amount={}",
                request.getPaymentNo(), request.getAmount());
        return PaymentCallbackVO.builder()
                .paymentNo(request.getPaymentNo())
                .orderId(request.getOrderId())
                .processed(true)
                .message("支付回调成功处理并入账")
                .build();
    }

    /**
     * 2. 模拟账务瞬时异常验证 deleteOnFailure = true 自动释放凭据并二次重试入账自愈
     */
    @RedisIdempotent(key = "'idemp:retry:' + #request.paymentNo", expire = "60s", deleteOnFailure = true, message = "支付流水处理中，请勿重复提交")
    public PaymentCallbackVO simulateFailureAndRetry(PaymentFailureRetryRequest request) {
        log.info("[IdempotentScenario] 处理瞬时故障模拟流水: paymentNo={}, shouldFail={}",
                request.getPaymentNo(), request.getShouldFail());

        if (Boolean.TRUE.equals(request.getShouldFail())) {
            throw new BusinessException("模拟业务处理失败，自动回滚释放幂等凭据以供重试: paymentNo=" + request.getPaymentNo());
        }

        return PaymentCallbackVO.builder()
                .paymentNo(request.getPaymentNo())
                .orderId("ORD_" + request.getPaymentNo())
                .processed(true)
                .message("重试入账成功")
                .build();
    }

    /**
     * 3. 编程式防重核销退款单（RedisIdempotentTemplate）
     */
    public RefundVO executeRefundTemplate(RefundRequest request) {
        String key = "idemp:refund:tpl:" + request.getRefundNo();
        return redisIdempotentTemplate.execute(key, Duration.ofMinutes(1), () -> {
            log.info("[IdempotentScenario] 编程式模板防重处理退款: refundNo={}, amount={}",
                    request.getRefundNo(), request.getRefundAmount());
            return RefundVO.builder()
                    .refundNo(request.getRefundNo())
                    .refundAmount(request.getRefundAmount())
                    .success(true)
                    .message("编程式模板退款核销成功")
                    .build();
        });
    }

    /**
     * 4. 静态门面防重核销退款单（RedisIdempotentUtil）
     */
    public RefundVO executeRefundStatic(RefundRequest request) {
        String key = "idemp:refund:sta:" + request.getRefundNo();
        return RedisIdempotentUtil.execute(key, () -> {
            log.info("[IdempotentScenario] 静态门面防重处理退款: refundNo={}, amount={}",
                    request.getRefundNo(), request.getRefundAmount());
            return RefundVO.builder()
                    .refundNo(request.getRefundNo())
                    .refundAmount(request.getRefundAmount())
                    .success(true)
                    .message("静态门面退款核销成功")
                    .build();
        });
    }
}
```

### 核心生产机制与异常边界
1. **失败自愈与防死锁设计**：当 `deleteOnFailure = true` 时，业务闭包若发生非预期受控或未受控异常，切面在捕获异常后立即执行原子 `redisTemplate.delete(key)`，保证由于网络闪断、第三方接口瞬时超时等软故障时，上游重试机制能够无缝进入重新核销；
2. **正常成功凭据锁死**：业务成功执行退出后，防重凭据严格驻留至设定的 TTL 超时（如 60s 或 2h），后续重复回调直接在切面层被拒绝并抛出 `BusinessException`，彻底阻断下游重复入账与资金双花风险；
3. **SpEL 安全解析**：若 SpEL 表达式计算结果为 null，自动回退抛出显式防御异常，严禁因表达式拼写错误导致裸空键生效。

---

## 8. 分布式流控与并发度治理 (`RedisSemaphoreTemplate` & `RedisRateLimiterClient` & `RedisAtomicUtil`)

### 场景痛点
传统的 QPS 限流仅能控制单位时间内的吞吐量，当调用外部计费征信 API、高昂 AI 大模型推理服务或资源有限的内部重型报表计算时，一旦外部服务响应缓慢，海量请求在服务端持续堆积导致线程池与数据库连接池迅速被耗尽。此外，开放网关需要高平滑度的令牌桶限流，短信/邮件发送则需要毫秒级精确滑动窗口防刷。

### 特性矩阵
- **集群全局在途并发管控 (`RedisSemaphoreTemplate`)**：限制同一时刻集群全局正在处理中的最大线程数（In-Flight Concurrency），底层安全释放许可证；
- **争抢超时优雅降级 (`fallback`)**：槽位占满等待超时后不抛出粗暴异常，而是优雅执行降级回调返回友好响应；
- **动态配额调配 (`resetPermits`)**：支持在不重启应用的前提下运行时动态扩缩容信号量总许可证；
- **分布式令牌桶流控 (`RedisRateLimiterClient`)**：基于 Redisson `RRateLimiter` 算法，支持毫秒至天级窗口的平滑令牌桶限流、实时可用令牌查询与动态重置；
- **毫秒级滑动窗口防刷 (`RedisAtomicUtil.slidingWindowCheck`)**：基于 Redis ZSet 与原子 Lua 脚本，毫秒级清理历史窗口记录并完成当前访问计数与超限拦截。

### 专属 YAML 配置片段

```yaml
ateng:
  boot:
    redis:
      semaphore:
        enabled: true                         # 是否启用分布式信号量模板 (默认 true)
      key-prefix: "sample:"                   # 全局键前缀自动挂载至 RateLimiter
```

### 核心代码示例（1:1 逆向自 `SemaphoreThrottlingScenarioService` 与 `RedisTrafficController`）

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class SemaphoreThrottlingScenarioService {

    private final RedisSemaphoreTemplate redisSemaphoreTemplate;

    /**
     * 1. 外部征信评估在途并发槽位管控（编程式模板带超时降级）
     */
    public CreditCheckVO checkCredit(CreditCheckRequest request) {
        String semaphoreKey = "sem:credit:eval";
        String masked = maskIdCard(request.getIdCard());
        return redisSemaphoreTemplate.execute(
                semaphoreKey,
                10,
                1,
                Duration.ofMillis(500),
                () -> {
                    log.info("[SemaphoreThrottling] 获取槽位成功，执行征信核验: name={}, idCard={}",
                            request.getCustomerName(), masked);
                    return CreditCheckVO.builder()
                            .idCard(masked)
                            .customerName(request.getCustomerName())
                            .creditScore(750)
                            .fallback(false)
                            .message("征信评估成功，信用等级极佳")
                            .build();
                },
                () -> {
                    log.warn("[SemaphoreThrottling] 获取槽位超时，触发排队降级: name={}", request.getCustomerName());
                    return CreditCheckVO.builder()
                            .idCard(masked)
                            .customerName(request.getCustomerName())
                            .creditScore(null)
                            .fallback(true)
                            .message("征信下游通道在途并发饱和，已触发弹性降级保护")
                            .build();
                }
        );
    }

    /**
     * 2. 静态门面征信评估（RedisSemaphoreUtil）
     */
    public CreditCheckVO checkCreditStatic(CreditCheckRequest request) {
        String semaphoreKey = "sem:static:eval";
        String masked = maskIdCard(request.getIdCard());
        return RedisSemaphoreUtil.execute(
                semaphoreKey,
                10,
                Duration.ofMillis(500),
                () -> CreditCheckVO.builder()
                        .idCard(masked)
                        .customerName(request.getCustomerName())
                        .creditScore(780)
                        .fallback(false)
                        .message("静态门面征信评估成功")
                        .build(),
                () -> CreditCheckVO.builder()
                        .idCard(masked)
                        .customerName(request.getCustomerName())
                        .creditScore(null)
                        .fallback(true)
                        .message("静态门面并发槽位繁忙，已触发弹性降级")
                        .build()
        );
    }

    /**
     * 3. 运行时动态重置信号量总许可证配额
     */
    public boolean resetPermits(String serviceName, int newPermits) {
        String semaphoreKey = serviceName.startsWith("sem:") ? serviceName : "sem:heavy:" + serviceName;
        return redisSemaphoreTemplate.resetPermits(semaphoreKey, newPermits);
    }

    /**
     * 4. 实时查询当前可用许可证配额
     */
    public int getAvailablePermits(String serviceName) {
        String semaphoreKey = serviceName.startsWith("sem:") ? serviceName : "sem:heavy:" + serviceName;
        return redisSemaphoreTemplate.availablePermits(semaphoreKey);
    }
}
```

```java
/**
 * 令牌桶与滑动窗口多维流控演示
 */
@RestController
@RequestMapping("/sample/redis/traffic")
@RequiredArgsConstructor
public class RedisTrafficController {

    private final RedisRateLimiterClient redisRateLimiterClient;
    private final RedisAtomicUtil redisAtomicUtil;

    /**
     * 5. 分布式令牌桶流控获取与剩余查询
     */
    @PostMapping("/rate-limit/token-bucket")
    public Result<RateLimitResultVO> tokenBucketRateLimit(@Valid @RequestBody TokenBucketRateLimitRequest request) {
        boolean allowed = redisRateLimiterClient.tryAcquire(
                request.getApiKey(),
                request.getRate(),
                Duration.ofSeconds(request.getIntervalSeconds())
        );
        long remaining = redisRateLimiterClient.getAvailablePermits(request.getApiKey());
        return Result.success(RateLimitResultVO.builder()
                .key(request.getApiKey())
                .allowed(allowed)
                .remainingPermits(remaining)
                .build());
    }

    /**
     * 6. 短信发送滑动窗口频控校验（毫秒级精确 ZSet 窗口防刷）
     */
    @PostMapping("/rate-limit/sms-sliding-window")
    public Result<RateLimitResultVO> smsSlidingWindowLimit(@Valid @RequestBody SmsRateLimitRequest request) {
        String key = "rate:sms:" + request.getPhoneNumber();
        boolean allowed = redisAtomicUtil.slidingWindowCheck(
                key,
                request.getMaxAttempts(),
                Duration.ofSeconds(request.getWindowSeconds())
        );
        return Result.success(RateLimitResultVO.builder()
                .key(key)
                .allowed(allowed)
                .remainingPermits(null)
                .build());
    }
}
```

### 核心生产机制与异常边界
1. **在途并发 vs QPS 限流**：在途并发管控的是系统同时承载的“存量”负载，而 QPS 管控的是单位时间到达的“增量”速率。当外部接口发生卡顿时，QPS 限流仍会放行新请求造成服务端堆积崩溃，而信号量则能在在途请求达到上限时坚决阻断新请求；
2. **许可证零泄漏防线**：信号量模板在 `finally` 块中严格依托当前线程获取标记，仅在真实成功 `tryAcquire` 到许可证后才执行释放归还，严禁在获取超时或发生异常时误调用释放导致许可证数量虚增；
3. **令牌桶与滑动窗口互补**：API 网关级别推行令牌桶（平滑应对突发流量），短信与验证码等敏感接口推行滑动窗口（严格防范任意微小时间窗口内的集中暴力破解攻击）。

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

## 10. 广播发布与订阅引擎 (Pub/Sub: `RedisMessagePublisher` & `@ChannelTopicListener`)

### 场景痛点与工业级防卫
在多节点集群部署中，某个节点更新了系统字典、网关黑名单或配置项后，需要即时通知所有微服务实例刷新本地 JVM 内存或状态。传统手写 Spring Data Redis `MessageListener` 配置繁琐、缺乏统一类型反序列化，且在监听回调线程中直接执行阻塞业务容易导致监听连接假死。
`ateng-boot-redis` 构建了声明式全托管 Pub/Sub 广播引擎：
- **声明式注解驱动**：使用 `@ChannelTopicListener` 声明式监听广播，支持精确主题（`topics`）、通配模式（`patterns` 如 `sample:system:*`）以及 `${...}` 属性占位符；
- **全多态类型自适应反序列化**：支持单参（自动反序列化为强类型 POJO、String、byte[]、原生 Message）、双参（报文 + 实际触发频道）及三参安全适配；
- **全受管线程池与并发度隔离**：每个广播监听器由 Spring 容器受管的 `ThreadPoolTaskExecutor` 并发驱动，内置 MDC `traceId` 透传与优雅停机，杜绝阻塞 Redis 核心连接池。

### 特性矩阵
| 特性能力 | 核心类 / 注解 / SPI | 关键行为与配置 |
| :--- | :--- | :--- |
| **广播消息发布** | `RedisMessagePublisher.publish(channel, msg)` | 向精确频道或通配模式广播 POJO 结构体或纯文本，返回在线订阅客户端数 |
| **声明式精确监听** | `@ChannelTopicListener(topics = "...")` | 精确主题订阅，自动反序列化为目标强类型实体 |
| **声明式通配监听** | `@ChannelTopicListener(patterns = "...")` | 通配模式匹配（如 `sample:system:*`），双参获取报文及具体触发频道 |
| **受管并发调度** | `ChannelTopicBootstrap` | 受管 `ThreadPoolTaskExecutor` 异步消费驱动，MDC `traceId` 透传与异常隔离 |

### 专属 YAML 配置片段
```yaml
ateng:
  boot:
    redis:
      pub-sub:
        enabled: true                 # 开启主题广播发布与声明式监听容器 (默认 true)
```

### 真实业务实战代码（1:1 提取自 `ateng-boot-sample-redis`）
```java
// 1. 业务场景服务：精确主题公告广播与通配模式系统事件监听
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeBroadcastScenarioService {

    public static final String TOPIC_NOTICE_BROADCAST = "sample:notice:broadcast";
    public static final String PATTERN_SYSTEM_EVENT = "sample:system:*";

    private final RedisMessagePublisher redisMessagePublisher;
    private final List<NoticeBroadcastPayload> receivedNotices = new CopyOnWriteArrayList<>();
    private final List<String> receivedSystemEvents = new CopyOnWriteArrayList<>();

    // 1. 广播发布全站系统维护通知
    public Long publishNotice(NoticeBroadcastPayload notice) {
        log.info("[NoticeBroadcast] 广播发布系统通知: topic={}, noticeId={}", TOPIC_NOTICE_BROADCAST, notice.getNoticeId());
        return redisMessagePublisher.publish(TOPIC_NOTICE_BROADCAST, notice);
    }

    // 2. 广播发布系统通配事件
    public Long publishSystemEvent(String eventType, String eventData) {
        String channel = "sample:system:" + eventType;
        log.info("[NoticeBroadcast] 广播发布系统事件: channel={}, data={}", channel, eventData);
        return redisMessagePublisher.publish(channel, eventData);
    }

    // 3. 声明式精确主题监听：自动反序列化为强类型 POJO
    @ChannelTopicListener(topics = TOPIC_NOTICE_BROADCAST, concurrency = 1)
    public void onNoticeBroadcast(NoticeBroadcastPayload notice) {
        log.info("[NoticeBroadcast] 声明式接收系统公告广播: noticeId={}, title={}", notice.getNoticeId(), notice.getTitle());
        receivedNotices.add(notice);
    }

    // 4. 声明式通配模式监听：多参数接收 payload 与具体触发频道
    @ChannelTopicListener(patterns = PATTERN_SYSTEM_EVENT, concurrency = 1)
    public void onSystemEvent(String eventData, String channel) {
        log.info("[NoticeBroadcast] 声明式通配监听到系统事件: channel={}, data={}", channel, eventData);
        receivedSystemEvents.add(channel + "=" + eventData);
    }
}

// 2. Web 接入层控制器：RESTful 广播触发与历史探查端点
@Validated
@RestController
@RequestMapping("/sample/redis/pubsub")
@RequiredArgsConstructor
public class RedisPubSubController {

    private final NoticeBroadcastScenarioService noticeBroadcastScenarioService;

    @PostMapping("/broadcast/notice")
    public Result<BroadcastResultVO> broadcastNotice(@Valid @RequestBody NoticeBroadcastRequest request) {
        NoticeBroadcastPayload payload = new NoticeBroadcastPayload(
                request.getNoticeId(), request.getTitle(), request.getContent(), System.currentTimeMillis());
        Long subscribers = noticeBroadcastScenarioService.publishNotice(payload);
        return Result.success(BroadcastResultVO.builder()
                .topic(NoticeBroadcastScenarioService.TOPIC_NOTICE_BROADCAST)
                .subscriberCount(subscribers).success(true).build());
    }

    @PostMapping("/broadcast/system-config")
    public Result<BroadcastResultVO> broadcastSystemConfig(@Valid @RequestBody SystemEventBroadcastRequest request) {
        Long subscribers = noticeBroadcastScenarioService.publishSystemEvent(request.getEventType(), request.getEventData());
        return Result.success(BroadcastResultVO.builder()
                .topic("sample:system:" + request.getEventType())
                .subscriberCount(subscribers).success(true).build());
    }

    @GetMapping("/history/notices")
    public Result<List<NoticeBroadcastPayload>> getNoticeHistory() {
        return Result.success(noticeBroadcastScenarioService.getReceivedNotices());
    }

    @GetMapping("/history/system-events")
    public Result<List<String>> getSystemEventHistory() {
        return Result.success(noticeBroadcastScenarioService.getReceivedSystemEvents());
    }
}
```

### 核心生产机制与异常边界
1. **瞬态无持久化特性**：Redis Pub/Sub 为“发后即忘”的轻量瞬态广播协议，消息在 Redis 内部不落盘存储，客户端离线断网期间发布的广播消息将永久丢失。关键核心业务（如订单状态、交易流水）应选用 Redis 5.0+ Stream 或专业 MQ；
2. **广播风暴与反自重入**：广播监听器应保持轻量，耗时异步操作需提交至受管线程池；同时像两级缓存失效广播需携带全局唯一 `instanceId` 进行自重入拦截，防止本节点重复淘汰。

---

## 11. 分布式布隆过滤器 (`RedisBloomFilter` & `RedisBloomUtil`)

### 场景痛点
海量用户判重、防爬虫 IP 过滤与防止恶意黑客使用不存在的非法 ID 频繁穿透查询数据库时，若直接存入 Redis Set 会占用巨大内存，且原生 Redisson 布隆过滤器缺乏启动声明式自动预热与容量逼近 80% 安全告警。

### 特性矩阵
- **极小空间极速过滤**：万级至亿级数据量仅占用数兆内存即可实现 99% 精确度的存在性判定；
- **声明式配置与自动预热**：通过 `ateng.boot.redis.bloom-filters.*` 声明布隆过滤器预估容量与误判率，容器启动时自动初始化位图；
- **80% 容量预警指标**：提供 `getUsageRate` 实时监控使用率，逼近 80% 触发容量预警，防止误判率陡增；
- **双模访问**：注入 `RedisBloomFilter` 或直接使用 `RedisBloomUtil` 静态门面。

### 专属 YAML 配置片段

```yaml
ateng:
  boot:
    redis:
      bloom-filters:
        user-blacklist:
          expected-insertions: 50000          # 预期容纳元素总量 (默认 10000)
          false-probability: 0.01             # 容许误判率 (默认 0.01 即 1%)
```

### 核心代码示例（1:1 逆向自 `CacheScenarioService` 与 `RedisCacheController`）

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheScenarioService {

    private final RedisBloomFilter redisBloomFilter;

    /**
     * 1. 将用户加入黑名单（支持注入门面与 RedisBloomUtil.add 静态门面）
     */
    public boolean addBlacklist(String userId) {
        boolean added = redisBloomFilter.add("user-blacklist", userId);
        log.info("[CacheScenario] 添加用户至布隆黑名单: userId={}, added={}", userId, added);
        return added;
    }

    /**
     * 2. 判定用户是否存在于黑名单中（不存在绝对准确，存在有 1% 极低误判率）
     */
    public boolean checkBlacklist(String userId) {
        boolean inBlacklist = redisBloomFilter.contains("user-blacklist", userId);
        log.info("[CacheScenario] 检测用户是否在布隆黑名单: userId={}, exists={}", userId, inBlacklist);
        return inBlacklist;
    }

    /**
     * 3. 查询布隆过滤器健康运行指标（元素数、预估容量、使用率、误判率）
     */
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

### 核心生产机制与异常边界
1. **单向存在性判断**：布隆过滤器返回 `false` 时，该元素**100% 绝对不存在**，可直接在前置拦截返回（防穿透第一道防线）；返回 `true` 时，元素可能存在，允许放行进一步校验；
2. **容量逼近 80% 预警机制**：当插入元素数量超过 `expected-insertions * 0.8` 时，哈希碰撞率急剧攀升导致误判率显著高于声明的 1%。通过 `getBlacklistMetrics()` 对接监控系统，可在容量耗尽前实施布隆过滤器重建与双写切换；
3. **不可逆删除**：标准布隆过滤器不支持物理 `remove` 单个元素，如需重置只能调用 `delete("user-blacklist")` 全量清空重建。

---

## 12. 工业级增强单级缓存管理器 (`RedisCacheManager`)

### 场景痛点
Spring Boot 默认提供的 `RedisCacheManager` 缺乏针对不同业务缓存空间的独立 TTL 配置能力；空值默认不缓存引发高并发缓存穿透；热点缓存同一时刻集中失效引发数据库瞬时被打崩（缓存雪崩）。

### 特性矩阵
- **分空间个性化独立 TTL 隔离**：通过 YAML 配置为每个 `cacheName` 指定专属过期时间（如字典缓存 2h，验证码 5m）；
- **防穿透短 TTL 空对象缓存**：默认启用空值缓存，回源为 null 时自动写入缓存并赋予较短的 `null-ttl`（默认 3m），防止空 Key 反复回源；
- **原生随机抖动防雪崩 (`JitterTtlFunction`)**：在基准 TTL 基础上自适应随机叠加抖动偏移量，平滑打散缓存失效时间点。

### 专属 YAML 配置片段

```yaml
ateng:
  boot:
    redis:
      cache:
        enabled: true                         # 是否启用单级增强 RedisCacheManager (默认 true)
        default-ttl: 30m                      # 默认缓存过期时间 (默认 30m)
        null-ttl: 3m                          # 空值防穿透专门短 TTL (默认 3m)
        jitter-enabled: true                  # 启用随机抖动防雪崩 (默认 true)
        max-jitter: 5m                        # 最大随机抖动时间 (默认 5m)
        cache-null-values: true               # 是否缓存空对象防穿透 (默认 true)
        expires:
          sys-dict: 2h                        # 系统字典空间独立 TTL (2小时)
          sms-code: 5m                        # 验证码空间独立 TTL (5分钟)
```

### 核心代码示例（1:1 逆向自 `CacheScenarioService` 与 `RedisCacheController`）

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheScenarioService {

    /**
     * 1. 显式指定单级缓存管理器 redisCacheManager 查询系统字典
     */
    @Cacheable(cacheNames = "sys-dict", key = "#dictCode", cacheManager = "redisCacheManager")
    public SysDictVO getSysDict(String dictCode) {
        log.info("[CacheScenario] 单级字典缓存未命中，回源模拟数据库: dictCode={}", dictCode);
        return SysDictVO.builder()
                .dictCode(dictCode)
                .dictName("字典_" + dictCode)
                .dictValue("VAL_" + dictCode)
                .sortOrder(1)
                .build();
    }

    /**
     * 2. 淘汰单级系统字典缓存
     */
    @CacheEvict(cacheNames = "sys-dict", key = "#dictCode", cacheManager = "redisCacheManager")
    public void evictSysDict(String dictCode) {
        log.info("[CacheScenario] 淘汰单级字典缓存: dictCode={}", dictCode);
    }
}
```

---

## 13. 全景缓存防卫模板 (`RedisCacheTemplate`)

### 场景痛点
在高并发秒杀或热点活动大促中，单键缓存突然失效会引发海量并发线程同时穿透回源查库（缓存击穿）；批量查询时各元素分别查缓存与数据库，引发严重的 N+1 网络往返与数据库连接耗尽。

### 特性矩阵
- **单键全景防卫 (`getOrLoad`)**：双重检查锁（DCL + Redisson 分布式互斥锁）+ 短 TTL 空哨兵（`__ATENG_CACHE_NULL__`）+ 随机抖动，单键并发回源严格限制为 1 个请求；
- **批量商品安全防 N+1 (`batchGetOrLoad`)**：底层基于 Redis `MGET` 单次往返拉取全部缓存命中项；未命中项按字典序排序加锁，一次性批量回源后通过 Pipeline 批量回写 Redis，彻底杜绝 N+1；
- **对称优雅驱逐 (`evict`)**：对称清理键值与空哨兵标记。

### 核心代码示例（1:1 逆向自 `CacheScenarioService` 与 `RedisCacheController`）

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheScenarioService {

    private final RedisCacheTemplate redisCacheTemplate;
    private static final Map<Long, ProductVO> MOCK_PRODUCT_DB = Map.of(
            1001L, new ProductVO(1001L, "iPhone 16 Pro", new BigDecimal("7999.00"), 50),
            1002L, new ProductVO(1002L, "MacBook Pro M4", new BigDecimal("12999.00"), 20)
    );

    /**
     * 1. 单商品安全查询（互斥锁 DCL + 短 TTL 空哨兵防击穿/防穿透/防雪崩）
     */
    public ProductVO getProduct(Long productId) {
        return redisCacheTemplate.getOrLoad(
                "product:" + productId,
                ProductVO.class,
                key -> {
                    log.info("[CacheScenario] 缓存未命中，回源模拟数据库查询: productId={}", productId);
                    return MOCK_PRODUCT_DB.get(productId);
                }
        );
    }

    /**
     * 2. 批量商品安全查询（MGET 探测 + 字典序排序锁 + 单次批量回源防 N+1）
     */
    public Map<Long, ProductVO> batchGetProducts(List<Long> productIds) {
        return redisCacheTemplate.batchGetOrLoad(
                productIds,
                id -> "product:" + id,
                ProductVO.class,
                missingIds -> {
                    log.info("[CacheScenario] 批量缓存未命中，单次批量回源模拟数据库: missingCount={}", missingIds.size());
                    Map<Long, ProductVO> result = new LinkedHashMap<>();
                    for (Long id : missingIds) {
                        ProductVO p = MOCK_PRODUCT_DB.get(id);
                        if (p != null) {
                            result.put(id, p);
                        }
                    }
                    return result;
                }
        );
    }

    /**
     * 3. 对称清理商品缓存
     */
    public void evictProduct(Long productId) {
        redisCacheTemplate.evict("product:" + productId);
        log.info("[CacheScenario] 已驱逐商品缓存: productId={}", productId);
    }
}
```

---

## 14. 生产级内存+Redis 两级缓存体系 (`TwoLevelCache` & `TwoLevelCacheManager`)

### 场景痛点
微服务架构下单用 Redis 存在网络 I/O 往返延迟（毫秒级），单用本地 JVM 堆缓存存在多节点更新脏读；跨节点通过广播同步失效时若处理不当，容易引发节点监听到自身发出的通知而进行二次无意义清理的自重入死循环。

### 特性矩阵
- **纳秒级 L1 内存直读**：优先自动探测装载 Caffeine（W-TinyLFU）；缺依赖时自动平滑退化为内置有界 `ConcurrentLruMemoryCache`，严格限制容量上限防 OOM；
- **微秒级 L2 Redis 集中共享**：L1 未命中时平滑穿透至 L2 Redis 读取，并异步回填 L1，兼顾性能与分布式全局一致性；
- **Pub/Sub 广播淘汰与防环机制**：节点在执行 `@CacheEvict` 时，自动向 `ateng:cache:evict:topic` 广播携带当前实例唯一 `instanceId` 的淘汰事件；其他节点监听到后清理各自 L1 本地缓存，本节点因匹配自身 `instanceId` 而主动忽略，坚决杜绝死循环自重入。

### 专属 YAML 配置片段

```yaml
ateng:
  boot:
    redis:
      two-level:
        enabled: true                         # 是否启用两级缓存体系 (默认 true)
        l1:
          default-ttl: 5m                     # L1 本地缓存默认 TTL (默认 5m)
          max-size: 10000                     # L1 最大元素容量限制，超出按 LRU 淘汰防 OOM (默认 10000)
          ttls:
            user-profile: 10m                 # 会员档案 L1 专属个性化 TTL (10分钟)
```

### 核心代码示例（1:1 逆向自 `CacheScenarioService` 与 `RedisCacheController`）

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheScenarioService {

    /**
     * 1. Spring Cache 声明式两级缓存查询（默认 @Primary TwoLevelCacheManager）
     * 首次调用查库并回填 L1 与 L2；二次调用直接从 L1 内存纳秒级返回
     */
    @Cacheable(cacheNames = "user-profile", key = "#userId")
    public UserProfileVO getUserProfileVO(Long userId) {
        log.info("[CacheScenario] 两级缓存未命中，执行模拟数据库查询: userId={}", userId);
        return UserProfileVO.builder()
                .userId(userId)
                .username("user_" + userId)
                .membershipTier("VIP")
                .points(1000)
                .build();
    }

    /**
     * 2. Spring Cache 声明式定向淘汰（清除本地 L1 与 L2，并向集群广播淘汰其他实例 L1）
     */
    @CacheEvict(cacheNames = "user-profile", key = "#userId")
    public void evictUserProfile(Long userId) {
        log.info("[CacheScenario] 淘汰用户档案两级缓存: userId={}", userId);
    }

    /**
     * 3. Spring Cache 声明式全量清空两级缓存
     */
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
        enabled: true                   # 是否启用延时队列支持与生命周期调度器 (默认 true)
        retry-times: 3                  # 延时消费失败最大重试次数 (默认 3)
        retry-delay: 3s                 # 延时消费失败重试间隔 (默认 3s)
        dead-letter-enabled: true       # 是否启用消费重试耗尽转入死信队列 (默认 true)
        dead-letter-suffix: ":dlq"      # 死信队列统配名称后缀 (默认 ":dlq")
        max-replay-attempts: 3          # 死信最大受控重放次数，超限熔断防二次死信雪崩 (默认 3)
      stream:
        enabled: true                   # 是否启用 Redis Stream 流式消息支持 (默认 true)
        retry-times: 3                  # 消费失败最大重试次数 (默认 3)
        poll-timeout: 2s                # 消费拉取阻塞超时时长 (默认 2s)
        batch-size: 10                  # 单次拉取最大消息条数 (默认 10)
        dlq-suffix: ":dlq"              # 死信流统一后缀 (默认 ":dlq")
        instance-id: ""                 # 消费者实例标识 (留空则自动生成)
        max-replay-attempts: 3          # 死信最大受控重放次数，超限熔断防二次雪崩 (默认 3)
        claim-idle-time: 1m             # PEL 孤儿待决消息自动认领最小闲置超时时长 (默认 1m)
        claim-batch-size: 20            # PEL 孤儿待决消息自动认领单批最大拉取条数 (默认 20)
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
        cache-null-values: true         # 是否缓存 null 防穿透 (默认 true)
        null-ttl: 5m                    # 空对象防穿透独立短过期时间 (默认 5m)
        jitter-enabled: true            # 是否启用随机抖动防雪崩 (默认 true)
        max-jitter: 60s                 # 随机抖动最大时间跨度 (默认 60s)
        lock-wait-time: 3s              # 缓存防击穿互斥锁最大等待时长 (默认 3s)
        expires:                        # 针对不同 CacheName 的个性化 TTL (`Map<String, Duration>`)
          short_cache: 10m
          long_cache: 24h
      two-level:
        enabled: false                  # 是否启用生产级两级缓存 (L1 JVM + L2 Redis，默认 false)
        topic: "ateng:cache:evict:topic"# 跨节点两级缓存失效广播主题 (默认 "ateng:cache:evict:topic")
        l1:
          default-ttl: 5m               # L1 堆内存本地缓存默认过期时间 (默认 5m)
          default-max-size: 10000       # L1 堆内存最大元素容量，超出 LRU 淘汰严格防 OOM (默认 10000)
          ttls:                         # 针对特定 CacheName 的 L1 独立 TTL
            user-profile: 2m
          max-sizes:                    # 针对特定 CacheName 的 L1 独立容量上限
            user-profile: 5000
        l2:
          default-ttl: 30m              # L2 集中式 Redis 缓存默认过期时间 (默认 30m)
          cache-null-values: true       # L2 是否缓存空哨兵防穿透 (默认 true)
          null-ttl: 5m                  # L2 空对象防穿透独立短过期时间 (默认 5m)
          jitter-enabled: true          # L2 写入是否启用随机抖动防雪崩 (默认 true)
          max-jitter: 60s               # L2 随机抖动最大时间跨度 (默认 60s)
          ttls:                         # 针对特定 CacheName 的 L2 独立 TTL
            user-profile: 1h
```
