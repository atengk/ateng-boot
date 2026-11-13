# ateng-boot-redis

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.13-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![JDK](https://img.shields.io/badge/JDK-17%2B%20(21%20VT%20Ready)-blue.svg)](https://openjdk.org/)
[![Dual Core](https://img.shields.io/badge/Engine-Lettuce%20%2B%20Redisson-red.svg)](#2-双核架构全局前缀契约与核心不变量)
[![Serialization](https://img.shields.io/badge/Codec-Pure%20JSON%20(No%20%40class)-orange.svg)](#21-双核引擎与纯净序列化拓扑)

`ateng-boot-redis` 是面向生产环境的企业级 Redis 基础设施与分布式协同 Starter。基于 **Spring Data Redis (Lettuce) + Redisson** 双核原生融合架构，消除跨语言 `@class` 类型污染与网关空闲断连隐患；收敛基础数据结构、受管 Lua / 原子原语、四阶分布式锁、可靠延时队列、Stream 流式消息、防重幂等、信号量与限流、Pub/Sub 广播以及多级协同缓存防卫体系，全栈遵循 `Duration` 强类型与虚拟线程防 Pinning 规范。

---

## 0. 特性与公共 API 全景速查矩阵

> 阅读下文前，可通过下表在 30 秒内定位目标能力的核心组件、公共 API、配置属性与验证锚点（100% 对齐模块公共接口与 [示例工程 README](../ateng-boot-samples/ateng-boot-sample-redis/README.md)）。

| 能力域 | 核心注解 / 受管组件 | 关键公共方法 / 配置属性（`ateng.boot.redis.*`） | 典型应用场景 | 锚定验证源头（`*Test` / Sample） |
| :--- | :--- | :--- | :--- | :--- |
| **基础数据与序列化** | `RedisTemplate<String, Object>`<br>`RedisUtil`<br>`RedisSerializerFactory` | `buildKey`、`hasKey`、`expire`、`getExpire`、`persist`、`delete`、`scan` / `scanKeys` / `scanCursor`、`set` / `get`、`hSet` / `hGet` / `hGetAll` / `hDel` / `hScan`、`lPush` / `lRange`、`sAdd` / `sMembers`、`zAdd` / `zIncrBy` / `zRevRangeWithScores`、`geoAdd`、`setBit`、`pfAdd`、`executePipelined`、`init` | 购物车 Hash、排行榜 ZSet、安全游标 `SCAN`、Pipeline 预热 | `RedisUtilTest`<br>`RedisSerializerFactoryTest`<br>`RedisOpsAndScriptController` |
| **受管 Lua 与原子原语** | `RedisScriptExecutor`<br>`RedisAtomicUtil` | `execute(script, type, keys, args)`、`executeClasspath(path, type, keys, args)`；`increment` / `decrement`、`compareAndSet`、`getAndSet`、`generateDailySeq`、`slidingWindowCheck`、`isAllowedBySlidingWindow`、`getSlidingWindowCount`、`init` | 优惠券原子核销、按日重置流水号、CAS 水位更新 | `RedisScriptExecutorTest`<br>`RedisAtomicUtilTest`<br>`RedisOpsAndScriptController` |
| **双模分布式锁** | `@DistributedLock`<br>`DistributedLockTemplate`<br>`RedisLockUtil` / `LockMode` | `@DistributedLock(key, keys, prefix, mode, waitTime, leaseTime, timeUnit, message)`；`LockMode`: `REENTRANT` / `FAIR` / `READ` / `WRITE`；`execute` (含 `fallback` 与 `List<String>` 联锁)、`tryExecute`；`lock.enabled`、`lock.prefix`、`lock.wait-time`、`lock.lease-time`、`lock.time-unit`、`lock.slow-threshold`、`lock.watchdog-timeout`、`redisson.lock-watchdog-timeout` | 秒杀防超卖、跨服务可重入结算、读写规则保护、多 SKU 联锁、争锁降级 | `DistributedLockTemplateTest`<br>`DistributedLockAspectTest`<br>`RedisLockUtilTest`<br>`RedisLockController` |
| **轻量可靠延时队列** | `RedisDelayedQueue`<br>`RedisDelayedQueueUtil`<br>`@DelayedQueueListener`<br>`DelayedQueueFailureHandler` | `offer`、`poll`、`size` / `delayedSize`、`getDeadLetterSize`、`pollDeadLetter`、`peekDeadLetters`、`replayDeadLetter` / `replayDeadLetters`、`clearDeadLetterQueue`、`init`；`DelayedQueueFailureHandler.onFailed(deadLetterMessage, cause)`；`delay-queue.enabled`、`delay-queue.retry-times`、`delay-queue.retry-delay`、`delay-queue.dead-letter-enabled`、`delay-queue.dead-letter-suffix`、`delay-queue.max-replay-attempts` | 订单超时关单、失败退避重试、DLQ 死信检视重放与 SPI 告警 | `RedisDelayedQueueTest`<br>`DelayedQueueBootstrapTest`<br>`RedisDelayQueueController` |
| **Stream 流消息引擎** | `RedisStreamTemplate`<br>`RedisStreamUtil`<br>`@RedisStreamListener`<br>`StreamFailureHandler` | `send`、`createGroup` / `destroyGroup`、`acknowledge`、`read`、`pendingSummary` / `pending`、`claim` / `claimPendingMessages`、`size` / `trim`、`peekDeadLetters`、`replayDeadLetter` / `replayDeadLetters`、`clearDeadLetters`、`init`；`StreamFailureHandler.onFailure` / `onFailed(deadLetterMessage, cause)`；`stream.enabled`、`stream.retry-times`、`stream.poll-timeout`、`stream.batch-size`、`stream.dlq-suffix`、`stream.max-replay-attempts`、`stream.claim-idle-time`、`stream.claim-batch-size`、`stream.instance-id` | 订单领域事件异步解耦、PEL 孤儿消息 `XCLAIM` 自愈、跨节点四维上下文透传 | `RedisStreamTemplateTest`<br>`RedisStreamUtilTest`<br>`StreamBootstrapTest`<br>`RedisStreamController` |
| **分布式防重幂等** | `@RedisIdempotent`<br>`RedisIdempotentTemplate`<br>`RedisIdempotentUtil` | `@RedisIdempotent(key, prefix, expire, deleteOnFailure, message)`；`execute`、`tryAcquire`、`setSuccess`、`setFailed`、`delete`、`getStatus`、`isSuccess` / `isTrying` / `isFailed`、`getBucket`、`formatKey`、`init`；`idempotent.enabled`、`idempotent.prefix`、`idempotent.default-expire`、`idempotent.delete-on-failure` | 支付异步回调防重、异常删除凭据重试、退款核销幂等 | `RedisIdempotentTemplateTest`<br>`RedisIdempotentAspectTest`<br>`RedisIdempotentUtilTest`<br>`RedisIdempotentController` |
| **流控与在途信号量** | `RedisSemaphoreTemplate`<br>`RedisSemaphoreUtil`<br>`RedisRateLimiterClient`<br>`RedisAtomicUtil` | `RedisSemaphoreTemplate` / `Util`: `execute`、`tryAcquire`、`release`、`trySetPermits`、`addPermits`、`resetPermits`、`availablePermits`、`delete`、`init`；`RedisRateLimiterClient`: `tryAcquire`、`acquire`、`getAvailablePermits`、`reset`、`delete`、`getRateLimiter`；`semaphore.enabled`、`semaphore.prefix` | 下游通道在途并发隔离、动态扩缩容、令牌桶网关流控、短信滑动窗口防刷 | `RedisSemaphoreTemplateTest`<br>`RedisRateLimiterClientTest`<br>`RedisTrafficController` |
| **Pub/Sub 主题广播** | `RedisMessagePublisher`<br>`@ChannelTopicListener` | `publish`、`subscribe`、`subscribeChannel`、`unsubscribe`、`getListenerContainer`；`@ChannelTopicListener(value / topics / topic / patterns, concurrency)`；`pub-sub.enabled` | 全站维护公告广播、通配模式配置刷新、动态订阅注销 | `RedisMessagePublisherTest`<br>`ChannelTopicBootstrapTest`<br>`RedisPubSubController` |
| **单级 / 防卫 / 两级缓存** | `RedisCacheManager`<br>`RedisCacheTemplate`<br>`TwoLevelCacheManager`<br>`TwoLevelCache` | `RedisCacheTemplate`: `getOrLoad`、`batchGetOrLoad`、`put` / `evict` / `batchEvict` / `hasKey`、`buildKey` / `buildLockKey` / `calculateTtl`；`TwoLevelCache`: `put(k, v, ttl)`、`evictLocal`、`clearLocal`、`get(k, type)`；`cache.enabled`、`cache.default-ttl`、`cache.cache-null-values`、`cache.null-ttl`、`cache.jitter-enabled`、`cache.max-jitter`、`cache.expires`、`cache.lock-wait-time`；`two-level.enabled`、`two-level.topic`、`two-level.l1.*`、`two-level.l2.*` | 字典独立 TTL 缓存、DCL + 空哨兵防穿透/击穿/雪崩、L1+L2 纳秒命中与广播失效 | `JitterTtlFunctionTest`<br>`RedisCacheTemplateTest`<br>`TwoLevelCacheManagerTest`<br>`TwoLevelCacheTest`<br>`L1MemoryCacheFactoryTest`<br>`RedisCacheController` |
| **分布式布隆过滤器** | `RedisBloomFilter`<br>`RedisBloomUtil`<br>`BloomFilterBootstrap` | `tryInit`、`add` / `addAll`、`contains` / `containsAll`、`count`、`getExpectedInsertions`、`getFalseProbability`、`getUsageRate`、`isOverCapacity`、`delete`、`buildKey`、`init`；`bloom-filters.<name>.expected-insertions`、`bloom-filters.<name>.false-probability` | 黑名单海量元素毫秒级拦截、启动声明式预热、80% 容量水位预警 | `RedisBloomFilterTest`<br>`RedisCacheController` |
| **双核装配与 Redisson 扩展** | `AtengRedisAutoConfiguration`<br>`RedisProperties` | `enabled`、`key-prefix`、`redisson.config`、`redisson.threads`、`redisson.netty-threads`、`redisson.ping-connection-interval`、`redisson.connection-pool-size`、`redisson.connection-minimum-idle-size`、`redisson.retry-attempts`、`redisson.retry-interval`、`redisson.lock-watchdog-timeout` | NAT 心跳保活、外部 `redisson.yaml` 接管、`@ConditionalOnMissingBean` 定制 | `AtengRedisAutoConfigurationTest`<br>`RedisPropertiesTest`<br>`SampleRedisIntegrationTest` |

---

## 1. 模块定位与快速起步

### 1.1 模块定位

- **核心底座依赖**：依赖 `ateng-boot-core`，复用全局唯一安全异常体系 `BusinessException` / `IdempotentException` / `CommonResultCode`、高精度 JSON 定制规约以及四维上下文持有器 `UserContextHolder` / `TenantContextHolder`。
- **双核零侵入融合**：同时托管 Spring Data Redis (`LettuceConnectionFactory`) 与 `RedissonClient`（标注 `@ConditionalOnMissingBean`），默认无缝继承 Spring Boot 原生 `spring.data.redis.*` 单机 / 哨兵 / 集群 / SSL 连接参数，无需维护两套数据源连接池。
- **防御性自动装配顺序**：[`AtengRedisAutoConfiguration`](./src/main/java/io/github/atengk/boot/redis/autoconfigure/AtengRedisAutoConfiguration.java) 显式标注 `@AutoConfigureAfter(AtengCoreAutoConfiguration.class)` 与 `@AutoConfigureBefore({RedisAutoConfiguration.class, CacheAutoConfiguration.class})`，并在容器就绪后通过 `SmartInitializingSingleton` 命名 `redisStaticFacadesInitializer` Bean 统一初始化 8 个全局静态工具门面。

### 1.2 Maven 引入坐标

**方式一：通过 `ateng-boot-bom` 统一纳管版本（推荐）**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.atengk</groupId>
            <artifactId>ateng-boot-bom</artifactId>
            <version>1.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-redis</artifactId>
    </dependency>
    <!-- 可选：开启两级缓存高性能 Caffeine L1 时引入（缺类时自动降级为原生 JVM LRU） -->
    <dependency>
        <groupId>com.github.ben-manes.caffeine</groupId>
        <artifactId>caffeine</artifactId>
    </dependency>
</dependencies>
```

**方式二：独立显式版本号引入**

```xml
<dependencies>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-redis</artifactId>
        <version>1.2.0</version>
    </dependency>
</dependencies>
```

### 1.3 最小可用配置与极简实战代码

只需配置标准 `spring.data.redis.*` 连接参数（按需附加 `ateng.boot.redis.key-prefix`），即可零样板代码直接注入并调用基础操作、声明式锁与防雪崩缓存：

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      database: 0
      timeout: 3000ms

ateng:
  boot:
    redis:
      key-prefix: demo
```

```java
@Service
public class QuickStartRedisService {

    public String cacheAndRead(String userId) {
        String key = RedisUtil.buildKey("user:session:" + userId);
        RedisUtil.set(key, "ACTIVE", Duration.ofMinutes(30));
        return RedisUtil.get(key, String.class);
    }

    @DistributedLock(key = "'order:' + #orderId")
    public void processOrder(String orderId) {
        // 自动挂载 demo:lock:order:{orderId} 分布式可重入锁与看门狗自动续期
    }
}
```

---

## 2. 双核架构、全局前缀契约与核心不变量

### 2.1 双核引擎与纯净序列化拓扑

```text
                       [ spring.data.redis.* (单机 / 哨兵 / 集群 / SSL) ]
                                            │
                       ┌────────────────────┴────────────────────┐
                       ▼                                         ▼
        Spring Data Redis (Lettuce)                   Redisson (Netty Pool)
       ┌───────────────────────────┐             ┌───────────────────────────┐
       │ RedisTemplate / CacheMgr  │             │ RLock / RDelayedQueue     │
       │ PubSub / Stream / Atomic  │             │ RRateLimiter / RSemaphore │
       │ Lua Script / TwoLevel L2  │             │ RBloomFilter / Watchdog   │
       └─────────────┬─────────────┘             └─────────────┬─────────────┘
                     └────────────────────┬────────────────────┘
                                          ▼
          [ 统一纯净 JSON Codec: RedisSerializerFactory.createSafeObjectMapper() ]
             - 剔除 GenericJackson2JsonRedisSerializer 默认的 @class 全限定类名
             - 强制使用 Jackson JavaTimeModule (LocalDateTime -> "yyyy-MM-dd HH:mm:ss")
             - 对标东八区 (GMT+8) 与大数字精度保护
```

### 2.2 全局键前缀 `key-prefix` 幂等合成规范与生效范围

[`RedisProperties.buildKey(modulePrefix, rawKey)`](./src/main/java/io/github/atengk/boot/redis/properties/RedisProperties.java) 在拼接键名时严格执行 **幂等去重解析（Idempotent Key Prefix Resolution）**：自动规范化末尾英文冒号 `:`，若调用方传入的键已携带全局前缀 `key-prefix` 或二级模块前缀（如 `lock:`、`idempotent:`、`semaphore:`、`bloom:`），自动剥离避免生成 `sample:sample:` 或 `sample:lock:lock:` 等冗余前缀。

| 组件 / 入口 | 是否自动挂载全局 `key-prefix` | 二级模块前缀来源 | 当配置 `key-prefix: sample` 且业务入参为 `<k>` 时最终物理 Key |
| :--- | :--- | :--- | :--- |
| **`RedisUtil` 原生数据操作** (`set` / `hSet` / `zAdd` / `scan`) | ❌ 保留调用方原始键 | 无（按业务显式传入或显式调用 `RedisUtil.buildKey(k)`） | `<k>`（若显式调用 `RedisUtil.buildKey(k)` 则为 `sample:<k>`） |
| **`RedisAtomicUtil`** (`incr` / `compareAndSet` / `slidingWindowCheck` / `generateDailySeq`) | ✅ 全量方法通过 `formatKey` 自动挂载 | 无 | `sample:<k>` |
| **`RedisScriptExecutor`** (`execute` / `executeClasspath`) | ❌ 保持传入 `keys` 列表原样 | 由业务显式传入（如 `RedisUtil.buildKey(...)` 或常量前缀） | `<k>`（透传脚本 `KEYS[i]`） |
| **`@DistributedLock` / `DistributedLockTemplate` / `RedisLockUtil`** | ✅ 自动挂载 | `ateng.boot.redis.lock.prefix`（默认 `lock:`）或注解 `prefix` | `sample:lock:<k>` |
| **`@RedisIdempotent` / `RedisIdempotentTemplate` / `RedisIdempotentUtil`** | ✅ 自动挂载 | `ateng.boot.redis.idempotent.prefix`（默认 `idempotent:`） | `sample:idempotent:<k>` |
| **`RedisSemaphoreTemplate` / `RedisSemaphoreUtil`** | ✅ 自动挂载 | `ateng.boot.redis.semaphore.prefix`（默认 `semaphore:`） | `sample:semaphore:<k>` |
| **`RedisRateLimiterClient`** (`tryAcquire` / `acquire` / `reset`) | ✅ 自动挂载 | 无 | `sample:<k>` |
| **`RedisBloomFilter` / `RedisBloomUtil`** (`tryInit` / `add` / `contains`) | ✅ 自动挂载 | 固定二级前缀 `bloom:` | `sample:bloom:<filterName>` |
| **`RedisDelayedQueue` / `RedisDelayedQueueUtil`** | ✅ 全队列通过 `resolveQueueName` 自动挂载 | 死信队列额外追加 `delay-queue.dead-letter-suffix`（默认 `:dlq`） | `sample:<queueName>`（死信：`sample:<queueName>:dlq`） |
| **`RedisStreamTemplate` / `RedisStreamUtil`** | ✅ 主流与死信流通过 `formatStreamKey` 自动挂载 | 死信流额外追加 `stream.dlq-suffix`（默认 `:dlq`） | `sample:<stream>`（死信：`sample:<stream>:dlq`） |
| **`RedisCacheManager`** (`@Cacheable` 等) | ✅ `key-prefix` 非空时通过 `computePrefixWith` 自动挂载 | Spring Cache 规范 `cacheName::` | `sample:<cacheName>::<key>`（未配 `key-prefix` 时为 `<cacheName>::<key>`） |
| **`RedisCacheTemplate`** (`getOrLoad` / `batchGetOrLoad`) | ✅ 自动挂载 | 防击穿互斥锁固定二级前缀 `lock:cache:` | 数据键 `sample:<k>`；互斥锁 `sample:lock:cache:<k>` |
| **`TwoLevelCacheManager` / `DefaultTwoLevelCache`** | ✅ L2 数据键与跨节点广播 `topic` 均自动挂载 | L2 格式 `<cacheName>:<key>` | L2 键 `sample:<cacheName>:<key>`；广播频道 `sample:<topic>` |

### 2.3 跨组件上下文传播分级

| 上下文维度 | Redis Stream (`RedisStreamTemplate` + `StreamBootstrap`) | 延时队列 (`DelayedQueueBootstrap`) & 广播 (`ChannelTopicBootstrap`) |
| :--- | :--- | :--- |
| **跨节点透传维度** | **全四维上下文跨节点透传**：发送端自动注入 `traceId`、`__ateng_tenant_id`、`__ateng_ignore_tenant`、`__ateng_user_ctx`；消费端先恢复四维上下文、剥离 `__ateng_*` 元字段后再反序列化绑定 POJO | **纯净业务载荷**：不将用户登录态或租户身份序列化至 Redis 消息体 |
| **消费侧线程隔离** | 消费前恢复消息内携带的四维快照（若无 `traceId` 则由消费侧兜底生成），`finally` 中无条件执行 `MDC.clear()`、`TenantContextHolder.clear()`、`UserContextHolder.clear()` | 消费前先清理历史残余上下文并注入独立 `traceId`（`DelayedQueueBootstrap` 生成新 UUID，`ChannelTopicBootstrap` 优先继承分发线程 `traceId` 否则生成新 UUID），`finally` 中全量清理 |

### 2.4 全局工程不变量与客户端接管

- **Redisson 客户端接管与 NAT 保活**：业务未自建 `RedissonClient` Bean 时自动按 `spring.data.redis.*` 装配并默认开启 **30 秒 TCP 心跳保活**（`pingConnectionInterval`）防御云原生 NAT 网关静默断连；锁看门狗默认 `30s`（优先级：`ateng.boot.redis.lock.watchdog-timeout` > `ateng.boot.redis.redisson.lock-watchdog-timeout`）；支持配置 `redisson.config` 外部 YAML 接管。
- **空安全与并发规约**：查询类集合返回值遇空统一保底空集合，严禁返回 `null`；核心临界区同步统一采用 `ReentrantLock` 替代 `synchronized`，杜绝 JDK 21+ 虚拟线程 Carrier Pinning。

---

## 3. 基础数据操作与受管 Lua / 原子原语

对应示例控制器：[`RedisOpsAndScriptController`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/controller/RedisOpsAndScriptController.java) & [`ScriptExecutionScenarioService`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/service/ScriptExecutionScenarioService.java)

> 将 `RedisUtil` 多数据结构基础操作、受管 `RedisScriptExecutor` Lua 执行器与 `RedisAtomicUtil` 对称原子原语统一收敛至日常高频数据层。

### 3.1 局部专属 YAML 配置

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      database: 0
      timeout: 3000ms

ateng:
  boot:
    redis:
      enabled: true
      key-prefix: sample
```

### 3.2 1:1 真实 Sample 核心代码

**1）Classpath 优惠券原子扣减脚本 (`scripts/coupon_deduct.lua`)**

```lua
-- scripts/coupon_deduct.lua
-- 优惠券原子库存扣减 Lua 脚本
-- KEYS[1]: 优惠券库存键 (如 sample:coupon:stock:{couponId})
-- ARGV[1]: 扣减数量 (通常为正整数)
local key = KEYS[1]
local deduct = tonumber(ARGV[1])
local raw = redis.call('get', key)
local current = 0
if raw then
    if type(raw) == 'string' then
        raw = string.gsub(raw, '^"*(.-)"*$', '%1')
    end
    current = tonumber(raw) or 0
end

if current >= deduct then
    local remaining = current - deduct
    redis.call('set', key, tostring(remaining))
    return remaining
else
    return -1
end
```

**2）基础 Hash/ZSet/SCAN/Pipeline 与受管 Lua/CAS 场景编排（截取自真实 Sample）**

```java
// 1. 购物车 Hash、热销榜 ZSet、安全 SCAN 与 Pipeline 批量预热（RedisOpsAndScriptController）
String cartKey = CART_PREFIX + request.getUserId();
RedisUtil.hSet(cartKey, request.getSkuId(), item);

String member = request.getProductId() + ":" + request.getProductName();
Double newScore = RedisUtil.zIncrBy(RANK_KEY, member, request.getDeltaScore());
Set<ZSetOperations.TypedTuple<Object>> tupleSet = RedisUtil.zRevRangeWithScores(RANK_KEY, 0, limit - 1);

Set<String> matched = scriptExecutionScenarioService.scanKeys(pattern, count);
RedisUtil.executePipelined(ops -> {
    stockMap.forEach((skuId, stock) -> {
        String key = STOCK_PREFIX + skuId;
        ops.opsForValue().set(key, stock);
    });
});

// 2. Classpath Lua 预编译缓存执行（避免优惠券库存查改并发竞态）
String couponKey = COUPON_STOCK_PREFIX + request.getCouponId();
if (RedisUtil.get(couponKey) == null) {
    RedisUtil.set(couponKey, 100L);
}
Long remaining = scriptExecutionScenarioService.executeClasspathScript(
        "scripts/coupon_deduct.lua",
        Long.class,
        List.of(couponKey),
        request.getDeductCount()
);

// 3. 跨天自动重置单号 & 原子 CAS 水位切换（ScriptExecutionScenarioService）
public String generateDailyOrderNo(String prefix, int length) {
    String seq = redisAtomicUtil.generateDailySeq(prefix, length);
    log.info("[ScriptExecution] 生成按日原子流水号成功: prefix={}, length={}, seq={}", prefix, length, seq);
    return seq;
}

public boolean compareAndSet(String key, Long expect, Long update) {
    boolean success = redisAtomicUtil.compareAndSet(key, expect, update);
    log.info("[ScriptExecution] CAS 执行结果: key={}, expect={}, update={}, success={}", key, expect, update, success);
    return success;
}
```

### 3.3 核心机制与边界防御

- **禁绝 `KEYS *` 阻塞主线程**：`RedisUtil.scan(pattern, count)` / `scanKeys` / `scanCursor` 底层统一采用 `ScanOptions` 游标增量遍历并封装自动释放资源，杜绝大 Key 空间扫描引发的 Redis 单线程卡顿。
- **Lua 脚本兼容 JSON 引号清洗与 SHA1 预编译缓存**：`RedisScriptExecutor` 通过脚本源码或 Classpath 路径自动计算 SHA1 并缓存 `DefaultRedisScript`，内部自动适配 `Integer.class` 与 Redis 原生 `Long` 返回类型转换；脚本内显式兼容 Jackson JSON 序列化写入的引号包裹数值。
- **按日原子流水号与前缀约束**：`RedisAtomicUtil.generateDailySeq(prefix, length)` 内部通过 `RedisAtomicUtil.formatKey(...)` 挂载全局 `key-prefix`，并在当日首次初始化后自动附加跨天失效 TTL 防止历史序列键无限堆积。

### 3.4 预期控制台呈现

```text
INFO  [           main] [b132281c21571059c72552313f2973a0] a.b.s.r.s.ScriptExecutionScenarioService : [ScriptExecution] 执行受管 Classpath Lua 脚本: path=scripts/coupon_deduct.lua, keys=[sample:coupon:stock:COUPON_NEW_USER]
INFO  [           main] [NO-TRACE] a.b.s.r.s.ScriptExecutionScenarioService : [ScriptExecution] CAS 执行结果: key=atomic:cas:order1001, expect=10, update=20, success=true
INFO  [           main] [NO-TRACE] a.b.s.r.s.ScriptExecutionScenarioService : [ScriptExecution] 生成按日原子流水号成功: prefix=PAY, length=6, seq=PAY20261113000001
INFO  [           main] [NO-TRACE] a.b.s.r.s.ScriptExecutionScenarioService : [ScriptExecution] 游标扫描完成: pattern=sample:user:*, count=100, matchedSize=1
```

---

## 4. 双模一体化分布式锁

对应示例控制器：[`RedisLockController`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/controller/RedisLockController.java) & [`StockDeductScenarioService`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/service/StockDeductScenarioService.java) & [`OrderSettlementService`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/service/OrderSettlementService.java)

> 同时提供声明式 `@DistributedLock`、编程式 `DistributedLockTemplate` 与静态门面 `RedisLockUtil`，统一覆盖可重入锁、公平锁、读写锁、MultiLock 多键联锁与争锁降级。

### 4.1 局部专属 YAML 配置

```yaml
ateng:
  boot:
    redis:
      key-prefix: sample
      redisson:
        # 全局 Redisson 看门狗检查周期（默认 30s）
        lock-watchdog-timeout: 30s
      lock:
        enabled: true
        prefix: "lock:"
        wait-time: 3s
        # -1ms 代表激活 Redisson 看门狗自动续期
        lease-time: -1ms
        slow-threshold: 3000ms
        # 锁子模块看门狗覆盖时长（可选；未配置时继承 redisson.lock-watchdog-timeout）
        watchdog-timeout: 30s
```

### 4.2 1:1 真实 Sample 核心代码

```java
// 1. 声明式可重入排他锁与跨 Bean 真实重入调用（OrderSettlementService -> StockDeductScenarioService）
@DistributedLock(key = "'product:' + #request.productId", waitTime = 3000, leaseTime = 5000, mode = LockMode.REENTRANT)
public OrderSettleVO settleOrder(OrderSettleRequest request) {
    // 同一线程内跨 Bean 调用同样声明锁定 'product:' + #request.productId 的 deductStock，平滑重入不死锁
    StockDeductResultVO deductResult = stockDeductScenarioService.deductStock(
            StockDeductRequest.builder()
                    .productId(request.getProductId())
                    .count(request.getQuantity())
                    .build());
    return OrderSettleVO.builder()
            .orderId(request.getOrderId())
            .productId(request.getProductId())
            .remainingStock(deductResult.getRemainingStock())
            .status("SETTLED")
            .build();
}

// 2. 公平排队 / 共享读锁 / MultiLock 多 SKU 联锁（StockDeductScenarioService）
@DistributedLock(key = "'fair:activity:' + #request.activityId", waitTime = 3000, leaseTime = 5000, mode = LockMode.FAIR)
public FairQuotaVO acquireFairQuota(FairQuotaRequest request) {
    int queueNo = fairQueueCounter.incrementAndGet();
    return FairQuotaVO.builder()
            .activityId(request.getActivityId())
            .userId(request.getUserId())
            .queueNumber(queueNo)
            .acquired(true)
            .build();
}

public PromotionRuleVO getPromotionRule(String ruleId) {
    String lockKey = "promo:rule:" + ruleId;
    return distributedLockTemplate.execute(lockKey, LockMode.READ, 3000L, 5000L, TimeUnit.MILLISECONDS, () -> {
        PromotionRuleVO rule = promoRuleTable.get(ruleId);
        if (rule == null) {
            throw new BusinessException("促销规则不存在: " + ruleId);
        }
        return rule;
    });
}

public MultiSkuDeductVO deductMultiSku(MultiSkuDeductRequest request) {
    List<String> lockKeys = request.getProductIds().stream()
            .map(id -> "product:" + id)
            .toList();
    return distributedLockTemplate.execute(lockKeys, LockMode.REENTRANT, 3000L, 5000L, TimeUnit.MILLISECONDS, () -> {
        for (Long productId : request.getProductIds()) {
            Integer current = stockTable.get(productId);
            if (current == null || current < request.getQuantity()) {
                throw new BusinessException("商品库存不足，扣减终止: productId=" + productId);
            }
        }
        for (Long productId : request.getProductIds()) {
            stockTable.computeIfPresent(productId, (k, v) -> v - request.getQuantity());
        }
        return MultiSkuDeductVO.builder()
                .comboId(request.getComboId())
                .productIds(request.getProductIds())
                .success(true)
                .message("组合套餐全量商品联锁扣减成功")
                .build();
    });
}

// 3. 高峰争锁失败平滑 fallback 降级 & RedisLockUtil 静态门面一键加锁
public LockFallbackVO executeWithFallback(LockFallbackRequest request) {
    String resourceId = request.getResourceId();
    return distributedLockTemplate.execute("resource:" + resourceId,
            () -> LockFallbackVO.builder().resourceId(resourceId).fallbackTriggered(false).result("NORMAL_SUCCESS").build(),
            () -> LockFallbackVO.builder().resourceId(resourceId).fallbackTriggered(true).result("FALLBACK_TRIGGERED").build());
}

public StaticLockVO executeStaticLock(StaticLockRequest request) {
    String key = "static:" + request.getResourceId();
    return RedisLockUtil.execute(key, () -> {
        log.info("[StockDeduct] 静态工具 RedisLockUtil 加锁成功: key={}", key);
        return StaticLockVO.builder()
                .resourceId(request.getResourceId())
                .locked(true)
                .message("静态门面锁执行成功")
                .build();
    });
}
```

### 4.3 核心机制与边界防御

- **可重入与安全释放**：`DistributedLockTemplate` 解锁前严格校验 `lock.isHeldByCurrentThread()`，杜绝异常超时后误删其他线程持有的分布式锁；同一线程对同一 Key 多次重入仅增减 Redisson Hash 计数，最后一次退出才物理释放。
- **MultiLock 自动去重与防死锁**：多键联锁内部统一按唯一键集合编排加锁与解锁，杜绝业务传入重复 SKU ID 或并发逆序加锁造成的死锁风险；非阻塞探测提供 `tryExecute` 重载链。
- **Watchdog 续期与慢锁可观测**：当 `leaseTime <= 0`（或注解默认 `-1L`）时激活 Redisson Watchdog 自动续期（超时周期支持通过 `ateng.boot.redis.lock.watchdog-timeout` 覆写），持锁耗时超过 `slow-threshold` 自动输出 `WARN` 慢锁审计日志。

### 4.4 预期控制台呈现

```text
INFO  [           main] [aa41bf6fa8646483384e9488e93c7c6a] i.g.a.b.s.r.s.OrderSettlementService     : [OrderSettle] 外层服务获取商品锁成功，进入订单结算流程: orderId=ORD_SETTLE_2001, productId=1001
INFO  [           main] [4549c0dd9769a3a055b436a0a0d5334b] i.g.a.b.s.r.s.StockDeductScenarioService : [StockDeduct] 获取分布式锁成功，开始扣减库存: productId=1001, count=10
INFO  [           main] [aa41bf6fa8646483384e9488e93c7c6a] i.g.a.b.s.r.s.OrderSettlementService     : [OrderSettle] 跨 Bean 重入扣减库存成功: orderId=ORD_SETTLE_2001, remainingStock=95
INFO  [           main] [NO-TRACE] i.g.a.b.s.r.s.StockDeductScenarioService : [StockDeduct] 批量联锁获取成功，扣减组合套餐商品: comboId=ORD_MULTI_1, products=[1001]
```

---

## 5. 轻量可靠延时队列与受控重放

对应示例控制器：[`RedisDelayQueueController`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/controller/RedisDelayQueueController.java) & [`OrderDelayScenarioService`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/service/OrderDelayScenarioService.java) & [`SampleDelayedQueueFailureHandler`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/handler/SampleDelayedQueueFailureHandler.java)

> 基于 Redisson `RDelayedQueue + RBlockingQueue` 构建极简高可靠延时任务引擎，内置退避重试、DLQ 死信流转、有界受控重放与失败告警 SPI。

### 5.1 局部专属 YAML 配置

```yaml
ateng:
  boot:
    redis:
      key-prefix: sample
      delay-queue:
        enabled: true
        retry-times: 3
        retry-delay: 3s
        dead-letter-enabled: true
        dead-letter-suffix: ":dlq"
        # 防止毒丸死信无限重放形成雪崩的上限门禁
        max-replay-attempts: 3
```

### 5.2 1:1 真实 Sample 核心代码

```java
// 1. 编程式投递与声明式监听消费（OrderDelayScenarioService）
public static final String QUEUE_ORDER_CANCEL = "sample:order:cancel";

public void sendOrderCancelTask(String orderId, Duration delay, String reason) {
    String effectiveReason = StringUtils.hasText(reason) ? reason : "订单未支付超时自动取消";
    OrderDelayPayload payload = new OrderDelayPayload(orderId, effectiveReason, System.currentTimeMillis());
    log.info("[OrderDelay] 编程式投递延时取消任务: queue={}, orderId={}, delay={}", QUEUE_ORDER_CANCEL, orderId, delay);
    redisDelayedQueue.offer(QUEUE_ORDER_CANCEL, payload, delay);
}

@DelayedQueueListener(queue = QUEUE_ORDER_CANCEL, concurrency = 1)
public void onOrderCancel(OrderDelayPayload payload) {
    if (payload != null && payload.getOrderId() != null && payload.getOrderId().contains("POISON")) {
        log.error("[OrderDelay] 声明式监听捕获毒丸订单，抛出异常触发重试: orderId={}", payload.getOrderId());
        throw new BusinessException("毒丸订单触发模拟异常: orderId=" + payload.getOrderId());
    }
    log.info("[OrderDelay] 声明式监听到期消费: orderId={}, reason={}", payload.getOrderId(), payload.getReason());
    consumedOrders.add(payload);
    latch.countDown();
}

// 2. 非破坏性检视 DLQ 与受控重放（OrderDelayScenarioService）
public List<DeadLetterMessage<OrderDelayPayload>> peekCancelOrderDeadLetters(int limit) {
    return redisDelayedQueue.peekDeadLetters(QUEUE_ORDER_CANCEL, limit, OrderDelayPayload.class);
}

public int replayCancelOrderDeadLetters(int maxCount, Duration delay) {
    return redisDelayedQueue.replayDeadLetters(QUEUE_ORDER_CANCEL, maxCount, delay);
}

// 3. 自定义 DelayedQueueFailureHandler SPI 告警捕获（SampleDelayedQueueFailureHandler）
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
}
```

### 5.3 核心机制与边界防御

- **退避重试与死信隔离**：`DelayedQueueBootstrap` 消费异常时按 `retry-delay` 执行退避并最多重试 `retry-times` 次；耗尽后若 `dead-letter-enabled=true`，自动封装 `DeadLetterMessage`（含异常摘要与堆栈快照）移送 `{global}{queueName}:dlq` 并触发 `DelayedQueueFailureHandler.onFailed(deadLetterMessage, cause)` SPI。
- **非破坏检视与有界重放**：`peekDeadLetters` 允许在不移出队列的情况下审阅死信内容；`replayDeadLetters` 严格校验 `max-replay-attempts` 重放计数门禁，超限毒丸直接拦截拒绝重放，避免故障自循环。
- **消费侧独立链路隔离**：工作线程前缀为 `redis-delay-queue-{queueName}-`，每条延时任务拉起消费前自动清理工作线程历史残余上下文并生成独立 `traceId`，在 `finally` 全量清理 MDC / Tenant / User 上下文，杜绝线程池上下文污染。

### 5.4 预期控制台呈现

> 注：受管消费者线程名格式为 `redis-delay-queue-{queueName}-{n}`（以下日志遵循 `%-15.15thread` 格式截断展示）。

```text
INFO  [           main] [NO-TRACE] i.g.a.b.s.r.s.OrderDelayScenarioService  : [OrderDelay] 编程式投递延时取消任务: queue=sample:order:cancel, orderId=POISON_ORD_DLQ_002, delay=PT0S
ERROR [:order:cancel-1] [4bb5739307363a4536b67227f56c317b] i.g.a.b.s.r.s.OrderDelayScenarioService  : [OrderDelay] 声明式监听捕获毒丸订单，抛出异常触发重试: orderId=POISON_ORD_DLQ_002
WARN  [:order:cancel-1] [4bb5739307363a4536b67227f56c317b] b.s.r.h.SampleDelayedQueueFailureHandler : [DelayedQueueAlert] 捕获延时任务重试耗尽告警: queue=sample:order:cancel, retryCount=3, error=毒丸订单触发模拟异常: orderId=POISON_ORD_DLQ_002
```

---

## 6. Redis 5.0+ Stream 可靠流消息与自愈引擎

对应示例控制器：[`RedisStreamController`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/controller/RedisStreamController.java) & [`StreamEventScenarioService`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/service/StreamEventScenarioService.java) & [`SampleStreamFailureHandler`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/handler/SampleStreamFailureHandler.java)

> 依托 Redis 5.0+ Stream 原生持久化与消费组语义，集成自动建组、四维全链路上下文透传、PEL 孤儿待决消息 `XCLAIM` 自愈、DLQ 死信转移重放与双参告警 SPI。

### 6.1 局部专属 YAML 配置

```yaml
ateng:
  boot:
    redis:
      key-prefix: sample
      stream:
        enabled: true
        retry-times: 3
        poll-timeout: 2s
        batch-size: 10
        dlq-suffix: ":dlq"
        max-replay-attempts: 3
        claim-idle-time: 1m
        claim-batch-size: 20
        # 多节点部署可显式指定实例标识隔离消费者命名，留空时自动生成 UUID
        # instance-id: order-node-01
```

### 6.2 1:1 真实 Sample 核心代码

```java
// 1. 模板 / 静态双门面投递与声明式消费组监听（StreamEventScenarioService）
public static final String STREAM_ORDER_EVENTS = "stream:order:events";
public static final String GROUP_ORDER_PROCESSOR = "group:order:processor";

public RecordId sendOrderEvent(OrderStreamEvent event) {
    return redisStreamTemplate.send(STREAM_ORDER_EVENTS, event);
}

public RecordId sendOrderEventStatic(OrderStreamEvent event) {
    return RedisStreamUtil.send(STREAM_ORDER_EVENTS, event);
}

@RedisStreamListener(stream = STREAM_ORDER_EVENTS, group = GROUP_ORDER_PROCESSOR, concurrency = 1, autoAck = true)
public void onOrderEvent(OrderStreamEvent event) {
    if (event != null && event.getOrderId() != null && event.getOrderId().contains("POISON")) {
        throw new BusinessException("Stream 流事件毒丸订单触发模拟异常: orderId=" + event.getOrderId());
    }
    receivedEvents.add(event);
    latch.countDown();
}

// 2. PEL 巡检认领、Stream 裁剪与 DLQ 受控重放
public List<MapRecord<String, Object, Object>> claimPendingMessages(String newConsumer, Duration minIdleTime, int count) {
    return redisStreamTemplate.claimPendingMessages(
            STREAM_ORDER_EVENTS, GROUP_ORDER_PROCESSOR, newConsumer, minIdleTime, count);
}

public Long trimStream(long maxLen) {
    return redisStreamTemplate.trim(STREAM_ORDER_EVENTS, maxLen);
}

public int replayDeadLetters(int count) {
    return redisStreamTemplate.replayDeadLetters(STREAM_ORDER_EVENTS, count);
}

// 3. SPI 失败告警回调实现（SampleStreamFailureHandler）
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
}
```

### 6.3 核心机制与边界防御

- **跨节点四维上下文无缝续传**：`RedisStreamTemplate.send` 自动把 `traceId`、`TenantContextHolder`（租户 ID 与忽略标识）、`UserContextHolder`（登录用户快照）随 `__ateng_*` 元字段写入流记录；`StreamBootstrap` 消费前置恢复四维上下文、剥离 `__ateng_*` 元字段后再向业务方法反序列化注入纯净 POJO，执行结束后在 `finally` 彻底清理避免 ThreadLocal 泄露。
- **PEL 孤儿消息自动认领自愈**：当某节点宕机遗留未 `XACK` 消息在 Pending Entries List（PEL）且闲置超过 `claim-idle-time` 时，`claimPendingMessages` / `claim` 自动转移认领至存活消费者，兼顾 `autoAck = false` 显式确认模式。
- **原因链完备的双参 SPI**：`StreamFailureHandler` 保留函数式单参 `onFailure(StreamDeadLetterMessage)` 向后兼容，新增默认方法 `onFailed(StreamDeadLetterMessage deadLetterMessage, Throwable cause)` 供 `StreamBootstrap` 直接传递完整异常原因链 `lastError`；受管工作线程前缀为 `redis-stream-{stream}-{group}-`。

### 6.4 预期控制台呈现

> 注：工作线程全名格式为 `redis-stream-stream_order_events-group_order_processor-{n}`（下述截断前缀 `der_processor-1`），同一次请求与消费完整透传相同 `traceId`。

```text
INFO  [           main] [e57448352734645857360b4728d696a7] i.g.a.b.s.r.s.StreamEventScenarioService : [StreamEvent] 模板门面投递订单流事件: stream=stream:order:events, event=OrderStreamEvent(orderId=ORD_STREAM_CTRL_001, amount=199.99, eventType=ORDER_CREATED, timestamp=1794541589489)
INFO  [der_processor-1] [e57448352734645857360b4728d696a7] i.g.a.b.s.r.s.StreamEventScenarioService : [StreamEvent] 监听到消费组流事件: orderId=ORD_STREAM_CTRL_001, eventType=ORDER_CREATED, amount=199.99
WARN  [der_processor-1] [47cc5e83d0749e68c4fae880006d54bd] i.g.a.b.s.r.h.SampleStreamFailureHandler : [StreamAlert] 捕获 Stream 消费重试耗尽告警: stream=stream:order:events, group=group:order:processor, consumer=80274a460dac89be4b648ba24659dbfb:1, recordId=1794541589569-0, error=Stream 流事件毒丸订单触发模拟异常: orderId=POISON_STREAM_CTRL_001
```

---

## 7. 通用业务级分布式防重幂等

对应示例控制器：[`RedisIdempotentController`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/controller/RedisIdempotentController.java) & [`IdempotentScenarioService`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/service/IdempotentScenarioService.java)

> 声明式 `@RedisIdempotent` 切面 + 编程式 `RedisIdempotentTemplate` + 静态门面 `RedisIdempotentUtil`，基于 Redisson `RBucket.setIfAbsent` 原子抢占与 `TRYING / SUCCESS / FAILED` 三态状态机防御并发重入。

### 7.1 局部专属 YAML 配置

```yaml
ateng:
  boot:
    redis:
      key-prefix: sample
      idempotent:
        enabled: true
        prefix: "idempotent:"
        default-expire: 10m
        # 业务抛出异常时默认删除防重凭证以便上游合法重试
        delete-on-failure: true
```

### 7.2 1:1 真实 Sample 核心代码

```java
// 1. 声明式支付回调并发防重与异常自动释放重试（IdempotentScenarioService）
@RedisIdempotent(
        key = "'idemp:pay:' + #request.paymentNo",
        expire = "60s",
        deleteOnFailure = true,
        message = "支付回调正在处理中或已处理完成，请勿重复通知"
)
public PaymentCallbackVO handlePaymentCallback(PaymentCallbackRequest request) {
    successProcessCount.incrementAndGet();
    return PaymentCallbackVO.builder()
            .paymentNo(request.getPaymentNo())
            .orderId(request.getOrderId())
            .processed(true)
            .message("支付回调处理成功，账务已入账")
            .build();
}

// 2. 编程式模板防重退款核销（RedisIdempotentTemplate）
public RefundVO executeRefundTemplate(RefundRequest request) {
    String key = "idemp:refund:tpl:" + request.getRefundNo();
    return redisIdempotentTemplate.execute(key, Duration.ofMinutes(1), () -> {
        successProcessCount.incrementAndGet();
        return RefundVO.builder()
                .refundNo(request.getRefundNo())
                .refundAmount(request.getRefundAmount())
                .success(true)
                .message("编程式模板退款核销成功")
                .build();
    });
}

// 3. 静态门面防重核销退款（RedisIdempotentUtil）
public RefundVO executeRefundStatic(RefundRequest request) {
    String key = "idemp:refund:sta:" + request.getRefundNo();
    return RedisIdempotentUtil.execute(key, () -> {
        log.info("[IdempotentScenario] 静态门面防重处理退款: refundNo={}, amount={}",
                request.getRefundNo(), request.getRefundAmount());
        successProcessCount.incrementAndGet();

        return RefundVO.builder()
                .refundNo(request.getRefundNo())
                .refundAmount(request.getRefundAmount())
                .success(true)
                .message("静态门面退款核销成功")
                .build();
    });
}
```

### 7.3 核心机制与边界防御

- **Redisson `RBucket` 原子占坑与三态状态机**：`RedisIdempotentTemplate` 底层获取 `redissonClient.getBucket(fullKey, StringCodec.INSTANCE)`，通过 `bucket.setIfAbsent(IdempotentStatus.TRYING.name(), effectiveTtl)` 原子抢占并发坑位；业务正常返回后写入 `IdempotentStatus.SUCCESS`；当执行抛出异常时，若 `deleteOnFailure = true` 则调用 `bucket.delete()` 立即释放防重凭证以允许上游合法重试，否则流转为 `IdempotentStatus.FAILED`。
- **幂等键前缀合成与降级兜底**：注解未填写 `key` 时自动按方法全限定签名生成兜底键；键合成严格遵守 `{global}{idempotent.prefix}{key}` 幂等解析逻辑，不重复拼接 `idempotent:idempotent:`；同时提供 `tryAcquire`、`setSuccess`、`setFailed`、`getStatus`、`isSuccess` / `isTrying` / `isFailed` 与 `formatKey` 全生命周期控制原语。
- **统一响应码映射**：并发重复请求被拦截后抛出 `IdempotentException(errorMsg)`（继承自 `BusinessException`，默认错误码映射 `CommonResultCode.REPEAT_SUBMIT`），由上层 Web 统一异常体系转换为标准业务错误响应。

### 7.4 预期控制台呈现

```text
INFO  [           main] [fab9505585da0e6c6a4e4190c779493e] i.g.a.b.s.r.s.IdempotentScenarioService  : [IdempotentScenario] 收到支付异步回调通知: paymentNo=PAY_NOTIFY_1001, orderId=ORD_NOTIFY_1001, amount=99.00
INFO  [           main] [NO-TRACE] i.g.a.b.s.r.s.IdempotentScenarioService  : [IdempotentScenario] 编程式模板防重处理退款: refundNo=PAY_20261027_001, amount=88.88
WARN  [           main] [NO-TRACE] i.g.a.b.r.i.RedisIdempotentTemplate      : [RedisIdempotent] 重复操作已被拦截: key=idempotent:idemp:order:ORD_IDEMP_001
```

---

## 8. 分布式流控与在途信号量治理

对应示例控制器：[`RedisTrafficController`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/controller/RedisTrafficController.java) & [`SemaphoreThrottlingScenarioService`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/service/SemaphoreThrottlingScenarioService.java)

> 收敛 `RedisSemaphoreTemplate` / `RedisSemaphoreUtil` 并发槽位信号量、`RedisRateLimiterClient` 令牌桶限流与 `RedisAtomicUtil` 毫秒级滑动窗口防刷三大流控护城河。

### 8.1 局部专属 YAML 配置

```yaml
ateng:
  boot:
    redis:
      key-prefix: sample
      semaphore:
        enabled: true
        prefix: "semaphore:"
```

### 8.2 1:1 真实 Sample 核心代码

```java
// 1. 信号量并发槽位管控与超时兜底降级（SemaphoreThrottlingScenarioService）
public CreditCheckVO checkCredit(CreditCheckRequest request) {
    return redisSemaphoreTemplate.execute(
            "sem:credit:eval",
            10,
            1,
            Duration.ofMillis(500),
            () -> CreditCheckVO.builder().idCard(masked).customerName(request.getCustomerName())
                    .creditScore(750).fallback(false).message("征信评估成功，信用等级极佳").build(),
            () -> CreditCheckVO.builder().idCard(masked).customerName(request.getCustomerName())
                    .creditScore(null).fallback(true).message("征信下游通道在途并发饱和，已触发弹性降级保护").build());
}

// 动态调整槽位配额 & 静态门面调用
public boolean resetPermits(String serviceName, int newPermits) {
    String semaphoreKey = serviceName.startsWith("sem:") ? serviceName : "sem:heavy:" + serviceName;
    return redisSemaphoreTemplate.resetPermits(semaphoreKey, newPermits);
}

// 2. 分布式令牌桶限流 & 动态配置重置（RedisTrafficController）
boolean allowed = redisRateLimiterClient.tryAcquire(
        request.getApiKey(), request.getRate(), Duration.ofSeconds(request.getIntervalSeconds()));
long remaining = redisRateLimiterClient.getAvailablePermits(request.getApiKey());
boolean reset = redisRateLimiterClient.reset(request.getApiKey());

// 3. 短信 ZSet 毫秒级滑动窗口频控
boolean allowed = redisAtomicUtil.slidingWindowCheck(
        "rate:sms:" + request.getPhoneNumber(),
        request.getMaxAttempts(),
        Duration.ofSeconds(request.getWindowSeconds()));
```

### 8.3 核心机制与边界防御

- **并发槽位必释放**：`RedisSemaphoreTemplate.execute` 无论目标闭包正常完成还是抛出异常，都在 `finally` 块准确归还等量 `permits`；支持直接通过 `resetPermits` / `addPermits` 热调下游服务并发配额。
- **令牌桶动态重设**：`RedisRateLimiterClient.reset(key, rate, rateInterval)` 支持线上热修改速率窗口参数而不依赖应用重启，且全方法通过 `RedisProperties.buildKey` 纳入全局前缀体系。
- **无临界毛刺的毫秒滑动窗口**：`RedisAtomicUtil.slidingWindowCheck` 依托 Redis ZSet 时间戳分数清理历史窗口、统计并写入当次唯一成员，彻底规避固定整点窗口的双倍突发穿透漏洞。

### 8.4 预期控制台呈现

```text
INFO  [           main] [0abde0740ae88e7c8b597b0a8629ba0b] s.r.s.SemaphoreThrottlingScenarioService : [SemaphoreThrottling] 成功获取信号量槽位，执行征信评估: customerName=张三, idCard=110******2345
INFO  [           main] [f52d5393d337762ae874e12724a09315] s.r.s.SemaphoreThrottlingScenarioService : [SemaphoreThrottling] 静态门面获取信号量成功: customerName=张三, idCard=110******2345
WARN  [           main] [NO-TRACE] s.r.s.SemaphoreThrottlingScenarioService : [Semaphore] 获取信号量许可证超时，触发兜底降级响应: service=timeout-gateway
```

---

## 9. 广播发布与订阅引擎

对应示例控制器：[`RedisPubSubController`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/controller/RedisPubSubController.java) & [`NoticeBroadcastScenarioService`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/service/NoticeBroadcastScenarioService.java)

> 声明式 `@ChannelTopicListener` 零样板注册 + 编程式 `RedisMessagePublisher` 动态发布与监听，支持强类型 POJO 自动反序列化与 `PatternTopic` 通配广播。

### 9.1 局部专属 YAML 配置

```yaml
ateng:
  boot:
    redis:
      enabled: true
      pub-sub:
        enabled: true
```

### 9.2 1:1 真实 Sample 核心代码

```java
// NoticeBroadcastScenarioService：发布广播 & 声明式精确/通配双模监听
public static final String TOPIC_NOTICE_BROADCAST = "sample:notice:broadcast";
public static final String PATTERN_SYSTEM_EVENT = "sample:system:*";

public Long publishNotice(NoticeBroadcastPayload notice) {
    return redisMessagePublisher.publish(TOPIC_NOTICE_BROADCAST, notice);
}

public Long publishSystemEvent(String eventType, String eventData) {
    String channel = "sample:system:" + eventType;
    log.info("[NoticeBroadcast] 广播发布系统事件: channel={}, data={}", channel, eventData);
    return redisMessagePublisher.publish(channel, eventData);
}

@ChannelTopicListener(topics = TOPIC_NOTICE_BROADCAST, concurrency = 1)
public void onNoticeBroadcast(NoticeBroadcastPayload notice) {
    receivedNotices.add(notice);
    noticeLatch.countDown();
}

@ChannelTopicListener(patterns = PATTERN_SYSTEM_EVENT, concurrency = 1)
public void onSystemEvent(String eventData, String channel) {
    receivedSystemEvents.add(channel + "=" + eventData);
    eventLatch.countDown();
}
```

### 9.3 核心机制与边界防御

- **多参数签名智能绑定与动态取消订阅**：`@ChannelTopicListener` 由 `ChannelTopicBootstrap` 自动扫描，支持 `onMessage(T payload)`、`onMessage(T payload, String channel)` 与 `onMessage(T payload, String channel, String pattern)` 三种参数形式，自动按目标参数泛型执行 JSON 反序列化或原生 `String` / `byte[]` 透传；编程式同时提供 `subscribeChannel`、`unsubscribe` 与 `getListenerContainer` 控制原语。
- **事件循环与业务线程解耦**：可按监听器独立配置 `concurrency` 并发度分发至受管执行器（线程前缀 `redis-pubsub-{cleanTopicName}-`），防止慢消费阻塞 Netty Redis 连接订阅通道。
- **多实例子节点广播隔离**：广播频道保持 `RedisMessagePublisher` 指定频道名原样路由，各实例节点消费回调进入前清理并隔离工作线程 MDC / Tenant / User 上下文（优先继承分发侧 `traceId` 或兜底生成），完成后立即清理。

### 9.4 预期控制台呈现

> 注：受管广播工作线程前缀为 `redis-pubsub-{cleanTopicName}-`（以下展示同链路 `traceId` 透传与 `%-15.15thread` 截断格式）。

```text
INFO  [           main] [cb61c7121d9b3f5698f0ce04bc95a9fe] a.b.s.r.s.NoticeBroadcastScenarioService : [NoticeBroadcast] 广播发布系统通知: topic=sample:notice:broadcast, noticeId=NOT_CTRL_001, title=全站停机维护公告
INFO  [ice_broadcast-1] [cb61c7121d9b3f5698f0ce04bc95a9fe] a.b.s.r.s.NoticeBroadcastScenarioService : [NoticeBroadcast] 声明式接收系统公告广播: noticeId=NOT_CTRL_001, title=全站停机维护公告
INFO  [           main] [4452c5b5aeeac233e74683d39a48c2e7] a.b.s.r.s.NoticeBroadcastScenarioService : [NoticeBroadcast] 广播发布系统事件: channel=sample:system:config_refresh, data={"refreshKey":"ALL_CACHE"}
INFO  [mple_system__-1] [4452c5b5aeeac233e74683d39a48c2e7] a.b.s.r.s.NoticeBroadcastScenarioService : [NoticeBroadcast] 声明式通配监听到系统事件: channel=sample:system:config_refresh, data={"refreshKey":"ALL_CACHE"}
```

---

## 10. 多级缓存防卫体系与布隆过滤器

对应示例控制器：[`RedisCacheController`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/controller/RedisCacheController.java) & [`CacheScenarioService`](../ateng-boot-samples/ateng-boot-sample-redis/src/main/java/io/github/atengk/boot/sample/redis/service/CacheScenarioService.java)

> 将 **单级 Spring Cache (`RedisCacheManager`) + 防击穿/穿透/雪崩模板 (`RedisCacheTemplate`) + L1/L2 两级联动缓存 (`TwoLevelCacheManager`) + 分布式布隆过滤器 (`RedisBloomFilter` / `BloomFilterBootstrap`)** 完整闭环收敛于统一缓存治理体系。

### 10.1 局部专属 YAML 配置

```yaml
ateng:
  boot:
    redis:
      key-prefix: sample
      # 1. Spring Cache 单级与 RedisCacheTemplate 共用防卫参数
      cache:
        enabled: true
        default-ttl: 30m
        cache-null-values: true
        null-ttl: 5m
        jitter-enabled: true
        max-jitter: 60s
        lock-wait-time: 3s
        expires:
          sys-dict: 2h
      # 2. L1 (Caffeine/LRU) + L2 (Redis) 两级缓存与跨节点广播淘汰
      two-level:
        enabled: true
        topic: "ateng:cache:evict:topic"
        l1:
          default-max-size: 10000
          default-ttl: 5m
          max-sizes:
            user-profile: 50000
          ttls:
            user-profile: 3m
        l2:
          default-ttl: 30m
          cache-null-values: true
          null-ttl: 5m
          jitter-enabled: true
          max-jitter: 60s
          ttls:
            user-profile: 1h
      # 3. 分布式布隆过滤器启动声明式预热
      bloom-filters:
        user-blacklist:
          expected-insertions: 100000
          false-probability: 0.01
```

### 10.2 1:1 真实 Sample 核心代码

```java
// 1. 单级独立 TTL 字典缓存：显式指向 redisCacheManager（绕开 @Primary 的两级缓存）
@Cacheable(cacheNames = "sys-dict", key = "#dictCode", cacheManager = "redisCacheManager")
public SysDictVO getSysDict(String dictCode) {
    dbQueryCount.incrementAndGet();
    return SysDictVO.builder()
            .dictCode(dictCode)
            .dictName("字典_" + dictCode)
            .dictValue("VAL_" + dictCode)
            .sortOrder(1)
            .build();
}

@CacheEvict(cacheNames = "sys-dict", key = "#dictCode", cacheManager = "redisCacheManager")
public void evictSysDict(String dictCode) {
    log.info("[CacheScenario] 淘汰单级字典缓存: dictCode={}", dictCode);
}

// 2. RedisCacheTemplate 单键防击穿/穿透/雪崩 与 批量 MGET 防 N+1 查询
public ProductVO getProduct(Long productId) {
    return redisCacheTemplate.getOrLoad(
            "product:" + productId,
            ProductVO.class,
            key -> {
                dbQueryCount.incrementAndGet();
                return MOCK_PRODUCT_DB.get(productId);
            });
}

public Map<Long, ProductVO> batchGetProducts(List<Long> productIds) {
    return redisCacheTemplate.batchGetOrLoad(
            productIds,
            id -> "product:" + id,
            ProductVO.class,
            missingIds -> {
                dbQueryCount.incrementAndGet();
                Map<Long, ProductVO> result = new LinkedHashMap<>();
                for (Long id : missingIds) {
                    ProductVO p = MOCK_PRODUCT_DB.get(id);
                    if (p != null) {
                        result.put(id, p);
                    }
                }
                return result;
            });
}

// 3. L1 + L2 两级缓存：默认走 @Primary TwoLevelCacheManager，@CacheEvict 触发全集群 Pub/Sub 淘汰
@Cacheable(cacheNames = "user-profile", key = "#userId")
public UserProfileVO getUserProfileVO(Long userId) {
    dbQueryCount.incrementAndGet();
    return UserProfileVO.builder()
            .userId(userId)
            .username("user_" + userId)
            .membershipTier("VIP")
            .points(1000)
            .build();
}

@CacheEvict(cacheNames = {"user-profile", "user-profile-legacy"}, key = "#userId")
public void evictUserProfile(Long userId) {
    log.info("[CacheScenario] 淘汰用户档案两级缓存: userId={}", userId);
}

// 4. 分布式布隆过滤器拦截与 80% 容量水位巡检
public boolean addBlacklist(String userId) {
    return redisBloomFilter.add("user-blacklist", userId);
}

public boolean checkBlacklist(String userId) {
    return redisBloomFilter.contains("user-blacklist", userId);
}
```

### 10.3 核心机制与边界防御

- **`@Primary` 两级接管与单级显式指定**：开启 `two-level.enabled=true` 时，`TwoLevelCacheManager` 作为默认 `@Primary` `CacheManager` 生效；单级场景只需通过 `@Cacheable(..., cacheManager = "redisCacheManager")` 即可精准分流至纯 Redis 缓存空间，避免不必要占用本机 L1 堆内存。
- **`RedisCacheTemplate` 四重防卫与反 N+1**：
  - **防穿透**：空结果自动写入短周期哨兵 `__ATENG_CACHE_NULL__`（`null-ttl` 默认 `5m`）并直接拦截后续空查；
  - **防击穿**：按字典序排好 `MultiLock` 锁顺序与单键 DCL 双重检查互斥锁防止并发缓存击穿死锁；
  - **防雪崩**：借助 `JitterTtlFunction` 在基准 TTL 之上叠加 `[0, max-jitter]` 随机抖动偏移；
  - **防 N+1**：`batchGetOrLoad` 使用一次网络 `multiGet` 提取命中项，仅针对未命中子集加锁与单次批量回源并 Pipeline 回写；内部提供 `buildKey`、`buildLockKey` 与 `calculateTtl` 公开计算入口。
- **布隆过滤器声明式预热与容量预警**：`BloomFilterBootstrap` 在单例就绪阶段自动为 YAML `bloom-filters.*` 执行幂等 `tryInit`；当 `getUsageRate(name)` 接近安全阈值（如 80%）时应主动触发重建扩容量化。

### 10.4 预期控制台呈现

```text
INFO  [           main] [NO-TRACE] i.g.a.b.r.bloom.BloomFilterBootstrap     : 分布式布隆过滤器 [user-blacklist] 声明式预热初始化成功: expectedInsertions=50000, falseProbability=0.01
INFO  [           main] [NO-TRACE] i.g.a.b.s.r.s.CacheScenarioService       : [CacheScenario] 缓存未命中，回源模拟数据库查询: productId=1001
INFO  [           main] [38ebe7013367becc660fa914f0378772] i.g.a.b.s.r.s.CacheScenarioService       : [CacheScenario] 两级缓存未命中，执行模拟数据库查询: userId=3001
INFO  [           main] [59e804f19e581bac7d0d32b4ac249bb4] i.g.a.b.s.r.s.CacheScenarioService       : [CacheScenario] 淘汰用户档案两级缓存: userId=3001
```

---

## 11. 生产扩展接管与核心非功能边界

### 11.1 生产级 SPI 契约扩展点

下游业务仅需在 Spring 容器中声明以下接口实现 Bean（无需修改 Starter 源码），即可无缝接管终态告警或自定义两级缓存实例：

| SPI 接口 | 核心契约方法 | 触发时机与默认实现 |
| :--- | :--- | :--- |
| **`DelayedQueueFailureHandler`** | `void onFailed(DeadLetterMessage<?> deadLetterMessage, Throwable cause)` | 延时消息重试耗尽写入 DLQ 后触发（默认 `LoggingDelayedQueueFailureHandler`） |
| **`StreamFailureHandler`** | `void onFailure(StreamDeadLetterMessage deadLetterMessage)`<br>`default void onFailed(StreamDeadLetterMessage deadLetterMessage, Throwable cause)` | Stream 消息消费重试耗尽写入 `:dlq` 后触发，容器优先调用双参 `onFailed` 传递 `cause` 原因链（默认 `LoggingStreamFailureHandler`） |
| **`TwoLevelCache`** | `put(key, value, ttl)`、`evictLocal(key)`、`clearLocal()`、`get(key, type)` | L1+L2 两级缓存扩展与跨节点剔除本地缓存协议，避免广播消息重入死循环（默认 `DefaultTwoLevelCache`） |

### 11.2 `@ConditionalOnMissingBean` 基础设施接管与静态门面联动

[`AtengRedisAutoConfiguration`](./src/main/java/io/github/atengk/boot/redis/autoconfigure/AtengRedisAutoConfiguration.java) 所有核心组件均标注 `@ConditionalOnMissingBean`，支持按需局部替换：

- **外部配置文件接管 Redisson**：配置 `ateng.boot.redis.redisson.config=classpath:redisson.yaml`，Starter 将加载外部 YAML 并在其之上合并看门狗覆盖与纯净 JSON Codec，适用于精细控制读写分离拓扑；
- **自定义 Bean 覆盖**：直接声明自定义 `RedissonClient`、`RedisTemplate<String, Object>`、`redisValueSerializer`、`RedisCacheManager`、`TwoLevelCacheManager` 或任意受管 `*Template` Bean 即可自动覆盖默认装配；
- **8 大静态门面联动刷新**：容器生命周期 Bean `redisStaticFacadesInitializer` 实现 `SmartInitializingSingleton`，在所有单例初始化完成并最终确定用户覆盖 Bean 后，统一将生效实例绑定至 `RedisUtil`、`RedisAtomicUtil`、`RedisBloomUtil`、`RedisSemaphoreUtil`、`RedisLockUtil`、`RedisIdempotentUtil`、`RedisStreamUtil` 与 `RedisDelayedQueueUtil`，确保静态工具调用与自定义模板行为一致。

### 11.3 核心非功能边界与生产防线矩阵

| 风险类别 | 内置工程防线与边界机制 |
| :--- | :--- |
| **JDK 21+ 虚拟线程 Pinning** | 两级缓存分段条带锁及消费容器临界区全部使用 `ReentrantLock`，杜绝 `synchronized` 在 I/O 场景引发载体线程固定（Carrier Pinning） |
| **阻塞主线程风险** | 禁用高危 `KEYS *`，`RedisUtil` 与 `DefaultTwoLevelCache.clear()` 统一采用游标 `SCAN`（默认分片批次大小 100）安全清理 |
| **L1 本地堆内存 OOM** | 引入 Caffeine 时按 `max-sizes` / `default-max-size`（默认 10,000 条）执行 W-TinyLFU 淘汰；未引入 Caffeine 时 `L1MemoryCacheFactory` 平滑退化为内置有界 `ConcurrentLruMemoryCache` |
| **云原生 NAT 空闲断连** | `RedissonClient` 默认以 `pingConnectionInterval = 30s` 发送心跳 `PING`，并配合 `retryAttempts = 3`、`retryInterval = 1500ms` 抵御瞬时网络抖动 |
| **死信雪崩与毒丸循环** | 延时队列与 Stream 重放均强制校验 `max-replay-attempts`（默认 3 次），超限毒丸拒绝重回业务通道 |
| **因果异常链与驱动解耦** | 底层 Redisson / Redis 数据访问异常在各门面边界统一转译为 `BusinessException`（幂等拦截抛出 `IdempotentException`），完整保留原始 `cause` 异常链 |

---

## 12. 完整 YAML 配置属性清单

以下全量属性严格对应 [`RedisProperties`](./src/main/java/io/github/atengk/boot/redis/properties/RedisProperties.java) 中的强类型定义与默认值：

```yaml
ateng:
  boot:
    redis:
      # 是否启用 Redis 增强基础设施自动装配（默认 true）
      enabled: true
      # 全局 Redis 键统一命名前缀（默认空字符串，保留原生键结构）
      key-prefix: ""

      # 1. 分布式锁配置
      lock:
        enabled: true
        prefix: "lock:"
        wait-time: 3s
        lease-time: -1ms
        time-unit: SECONDS
        slow-threshold: 3000ms
        # 可选覆盖全局 Redisson 看门狗超时；未配置时继承 redisson.lock-watchdog-timeout
        watchdog-timeout:

      # 2. 分布式信号量配置
      semaphore:
        enabled: true
        prefix: "semaphore:"

      # 3. 分布式防重幂等配置
      idempotent:
        enabled: true
        prefix: "idempotent:"
        default-expire: 10m
        delete-on-failure: true

      # 4. 延时队列配置
      delay-queue:
        enabled: true
        retry-times: 3
        retry-delay: 3s
        dead-letter-enabled: true
        dead-letter-suffix: ":dlq"
        max-replay-attempts: 3

      # 5. Stream 流式消息配置
      stream:
        enabled: true
        retry-times: 3
        poll-timeout: 2s
        batch-size: 10
        dlq-suffix: ":dlq"
        max-replay-attempts: 3
        claim-idle-time: 1m
        claim-batch-size: 20
        instance-id:

      # 6. Pub/Sub 主题广播配置
      pub-sub:
        enabled: true

      # 7. Spring Cache 与防卫模板共用配置
      cache:
        enabled: true
        default-ttl: 30m
        cache-null-values: true
        null-ttl: 5m
        jitter-enabled: true
        max-jitter: 60s
        lock-wait-time: 3s
        expires: {}

      # 8. L1 + L2 两级缓存配置（按需开启）
      two-level:
        enabled: false
        topic: "ateng:cache:evict:topic"
        l1:
          default-max-size: 10000
          default-ttl: 5m
          max-sizes: {}
          ttls: {}
        l2:
          default-ttl: 30m
          cache-null-values: true
          null-ttl: 5m
          jitter-enabled: true
          max-jitter: 60s
          ttls: {}

      # 9. 分布式布隆过滤器预热映射
      bloom-filters: {}

      # 10. Redisson 底层扩展与连接保活配置
      redisson:
        config:
        threads: 0
        netty-threads: 0
        ping-connection-interval: 30s
        connection-pool-size: 64
        connection-minimum-idle-size: 16
        retry-attempts: 3
        retry-interval: 1500ms
        lock-watchdog-timeout: 30s
```

---

## 13. 相关文档与架构决策

- [体验沙箱工程 README](../ateng-boot-samples/ateng-boot-sample-redis/README.md) — `ateng-boot-sample-redis` 一键运行与 8 大控制器 cURL 验证清单
- [模块领域词汇表 CONTEXT.md](./CONTEXT.md) — `ateng-boot-redis` 核心受控概念与禁用反义词表
- [双核驱动与分布式协同架构 ADR-0022](../docs/adr/0022-dual-core-redis-and-distributed-coordination-architecture.md) — 演进阶段、不变量及架构决策记录
