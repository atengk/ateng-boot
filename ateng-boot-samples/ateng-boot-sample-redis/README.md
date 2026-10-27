# ateng-boot-sample-redis 分布式协同与缓存场景自验工程

> 专注于 Redis / Redisson 分布式锁并发防超卖、Redisson 双模延时队列与死信保障、Redis 5.0+ Stream 消息流引擎与消费组监听、非 Web 业务级防重幂等、受管分布式信号量并发度治理、受管 Lua 脚本与对称原子原语、Pub/Sub 主题广播、两级缓存（L1+L2）与缓存三防护航（防击穿/防穿透/防雪崩/批量防 N+1）。

---

## 1. 场景定位与零 Web 污染架构

根据架构准则与 ADR-0028 规范，非 Web 基础设施 Starter（如 `ateng-boot-redis`）对应的场景样例工程严格保持**“零 Controller 污染”**，杜绝为了演示纯后台协同与消息流转强行生造不合理的 HTTP 控制层与 Web 容器依赖：
- **纯粹非 Web 后台服务**：零 Web 容器开销，基于真实业务场景 Service 驱动；
- **100% 离线自洽集成测试**：依托内存级双亲桩（Test Doubles）与 `@SpringBootTest` 端到端跑通所有业务场景；
- **8 大全景真实场景组件**：1:1 覆盖 `ateng-boot-redis` 的所有对外暴露的核心门面、声明式切面与监听容器。

---

## 2. 8 大场景业务组件矩阵

| 场景分类 | 场景业务组件 | 核心门面 / 注解 / SPI | 核心业务行为与机制 |
| :--- | :--- | :--- | :--- |
| **分布式并发锁** | `StockDeductScenarioService` | `@DistributedLock`<br>`DistributedLockTemplate`<br>`RedisLockUtil` | SpEL 动态键库存并发防超卖、锁重入自验、看门狗 30s 自动续期（`leaseTime = -1`）、争锁超时降级 fallback 闭包与多键联锁 (MultiLock) |
| **双模延时队列** | `OrderDelayScenarioService` | `RedisDelayedQueue`<br>`@DelayedQueueListener` | 编程式高精度投递订单延时取消任务、受管线程池声明式监听消费、重试耗尽自动流转死信队列（`{queue}:dlq`）与空安全拉取运维 |
| **Stream 可靠消息流** | `StreamEventScenarioService` | `RedisStreamTemplate`<br>`@RedisStreamListener` | Redis 5.0+ Stream 消息追加、`MKSTREAM` 幂等自愈建组、多消费者并发消费、自动确认 (XACK)、异常重试耗尽流转 `{stream}:dlq` 与队列裁剪 |
| **非 Web 防重幂等** | `IdempotentScenarioService` | `RedisIdempotentTemplate`<br>`@RedisIdempotent`<br>`RedisIdempotentUtil` | 拦截 MQ 重复消费与定时任务并发重入，三态状态机保护，业务失败 `deleteOnFailure = true` 自动删除凭据支持上游重试 |
| **并发度治理信号量** | `SemaphoreThrottlingScenarioService` | `RedisSemaphoreTemplate`<br>`RedisSemaphoreUtil` | 限制调用昂贵下游接口的全局并发硬阈值，争锁超时优雅降级、静态门面调用与动态配额调配 (`resetPermits` / `availablePermits`) |
| **受管 Lua 脚本与原子原语** | `ScriptExecutionScenarioService` | `RedisScriptExecutor`<br>`RedisAtomicUtil`<br>`RedisUtil` | 内联与 Classpath Lua 脚本加载执行、SHA1 预编译缓存、CAS 原子比较置换、`getAndSet` 对称原子更新、跨天自重置流水号与安全游标扫描 |
| **Pub/Sub 广播订阅** | `NoticeBroadcastScenarioService` | `RedisMessagePublisher`<br>`@ChannelTopicListener` | 精确频道系统公告广播、通配模式事件监听 (`sample:system:*`)、强类型 POJO 智能反序列化与多参数消费 |
| **两级缓存与防卫模板** | `CacheScenarioService` | `RedisCacheTemplate`<br>`TwoLevelCacheManager`<br>`@Cacheable`<br>`@CacheEvict`<br>`RedisBloomFilter` | DCL 互斥锁防击穿、短 TTL 空哨兵防穿透、批量 MGET 消除 N+1、Spring Cache L1 Caffeine/LRU + L2 Redis 两级缓存协同淘汰与布隆过滤器容量告警 |

---

## 3. 本地构建与端到端场景自验

样例工程配对完整的 `@SpringBootTest` 端到端集成测试套件，在离线内存环境下全真验证 8 大业务场景组件协同流转：

### 3.1 执行场景端到端实测

```bash
mvn test -pl ateng-boot-redis,ateng-boot-samples/ateng-boot-sample-redis -am -T 1C -nsu -B
```

**测试执行输出**：
```text
[INFO] Running io.github.atengk.boot.sample.redis.SampleRedisIntegrationTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 16.10 s -- in io.github.atengk.boot.sample.redis.SampleRedisIntegrationTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for io.github.atengk:ateng-boot 1.0.0:
[INFO] 
[INFO] io.github.atengk:ateng-boot ........................ SUCCESS [  0.265 s]
[INFO] io.github.atengk:ateng-boot-core ................... SUCCESS [ 16.632 s]
[INFO] io.github.atengk:ateng-boot-redis .................. SUCCESS [ 23.166 s]
[INFO] io.github.atengk:ateng-boot-samples ................ SUCCESS [  0.002 s]
[INFO] io.github.atengk:ateng-boot-sample-redis ........... SUCCESS [ 25.927 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

---

## 4. 关键配置示例 (`application.yml`)

```yaml
spring:
  application:
    name: ateng-boot-sample-redis
  config:
    import: "optional:classpath:application-local.yml"

ateng:
  boot:
    redis:
      enabled: true
      key-prefix: "sample:"
      two-level:
        enabled: true
        l1-max-size: 10000
        l1-ttl: 5m
        l2-ttl: 30m
      delay-queue:
        enabled: true
        concurrency: 1
      stream:
        enabled: true
        poll-timeout: 2s
        batch-size: 10
      idempotent:
        enabled: true
        prefix: "sample:idemp:"
      bloom-filters:
        user-blacklist:
          expected-insertions: 50000
          false-probability: 0.01
```
