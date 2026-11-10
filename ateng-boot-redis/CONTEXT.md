# Redis Context

`ateng-boot-redis` 负责 Redis 双核驱动基础设施（Spring Data Redis + Redisson 原生客户端）与企业级分布式协同能力的自动装配，涵盖无 `@class` 污染安全序列化、全能数据与脚本原语、分布式并发锁与流控幂等、多级缓存三防与布隆过滤，以及延时队列、Stream 消息流与 Pub/Sub 广播协同。

## Language

### 双核底座与数据原语

**DualCoreClient**:
在共享同一套 Spring Boot 官方 `spring.data.redis.*` 数据源配置的前提下，同时装配 `RedisTemplate`（负责通用 K-V、Pipeline、Stream、Pub/Sub 与缓存）与 `RedissonClient`（负责分布式锁、信号量、令牌桶、布隆过滤与延时队列）的双核驱动协同架构。
_Avoid_: HybridClient, MultiClient, RedisRedissonBridge

**RedisSerializerFactory**:
纯净安全序列化器工厂，提供以 `StringRedisSerializer` 序列化键、以零 `@class` 多态污染且内置 JSR-310 日期与大整数精度保护的 JSON 序列化器序列化值的标准构建门面。
_Avoid_: RedisTemplateCustomizer, RedisConfig, JacksonRedisConfig

**RedisUtil**:
面向五大基础结构（String/Hash/List/Set/ZSet）与三大高级结构（Geo/BitMap/HyperLogLog）的统一操作门面，提供 Pipeline 批处理、`buildKey` 前缀辅助，并强制以游标 `SCAN` 分批拉取替代高危 `KEYS *`。
_Avoid_: RedisHelper, CacheUtil, RedisManager

**RedisScriptExecutor**:
受管原子 Lua 脚本执行门面，负责内联源码与 Classpath 脚本加载、SHA1 摘要预编译缓存、`EVALSHA` 优先通信与强类型结果转换。
_Avoid_: LuaUtil, ScriptRunner, RedisLuaTemplate

**RedisAtomicUtil**:
分布式原子计数与业务发号门面，提供带过期保护的步长递增递减、CAS 原子比较置换、`getAndSet` 对称更新、跨天自重置业务流水号生成以及 ZSet 毫秒级滑动窗口频控原语。
_Avoid_: CounterUtil, RedisCounter, AtomicSequence

### 分布式并发与流控治理

**DistributedLock**:
声明式分布式锁注解，标注于业务方法上，支持 SpEL 动态键与多键联锁（MultiLock），提供可重入锁、公平锁、读锁、写锁四阶模式以及看门狗自动续期与超时快速失败契约。
_Avoid_: RedissonLock, RedisLock, LockAnnotation, SyncLock

**LockMode**:
分布式锁四阶加锁模式强类型枚举，涵盖 `REENTRANT`（可重入排他锁）、`FAIR`（公平排队锁）、`READ`（共享读锁）与 `WRITE`（排他写锁）。
_Avoid_: RedisLockType, SyncMode, LockStrategy

**DistributedLockAspect**:
驱动 `@DistributedLock` 的 AOP 环绕切面，全量委托 `DistributedLockTemplate` 统一执行锁获取、持锁状态安全释放与慢锁告警审计。
_Avoid_: LockInterceptor, DistributedLockHandler, RedissonLockAspect

**DistributedLockTemplate**:
编程式分布式锁受管执行模板，提供支持受检/非受检异常无损穿透的函数式闭包、争锁超时 `fallback` 降级、非阻塞 `tryExecute` 试探、`finally` 持锁人校验防误删释放与慢锁审计。
_Avoid_: LockExecutor, RedissonTemplate, DistributedLockManager

**RedisLockUtil**:
编程式分布式锁的全局静态操作门面，底层委托受管单例 `DistributedLockTemplate`，供静态上下文免注入安全执行加锁闭包。
_Avoid_: LockUtil, RedissonUtil, DistributedLockHelper

**RedisIdempotent**:
面向非 Web 业务链路（MQ 消费、定时调度、内部服务调用）的声明式分布式防重幂等注解，支持 SpEL 动态唯一键与业务异常时自动释放幂等凭据（`deleteOnFailure`）以保障上游重试自愈。
_Avoid_: BizIdempotent, AntiDuplicate, DeduplicateAnnotation

**RedisIdempotentTemplate**:
非 Web 业务级分布式防重幂等受管执行模板，封装原子占位、重复拦截与异常凭据自动回滚生命周期。
_Avoid_: IdempotentExecutor, DeduplicateTemplate, AntiReplayManager

**RedisIdempotentUtil**:
分布式业务防重幂等的全局静态操作门面，底层委托受管单例 `RedisIdempotentTemplate`。
_Avoid_: IdempotentHelper, DeduplicateUtil

**RedisSemaphoreTemplate**:
基于 Redisson `RSemaphore` 封装的分布式在途并发度（In-Flight Concurrency）受管模板，提供集群许可证配额自动初始化、等待超时优雅降级、运行时动态扩缩容与 `finally` 精确归还。
_Avoid_: SemaphoreExecutor, ConcurrencyLimiter, PermitManager

**RedisSemaphoreUtil**:
分布式受管信号量的全局静态操作门面，底层委托受管单例 `RedisSemaphoreTemplate`。
_Avoid_: SemaphoreHelper, PermitUtil

**RedisRateLimiterClient**:
基于 Redisson 令牌桶（`RRateLimiter`）封装的业务级分布式平滑限流客户端，支持全局受管键前缀隔离、动态速率重置与阻塞/非阻塞令牌申请。
_Avoid_: RedisLimiter, RateLimitService, TokenBucketClient

### 多级缓存与布隆防卫

**JitterTtlFunction**:
缓存过期时间随机抖动计算函数，在基准 TTL 上叠加 `0 ~ maxJitter` 动态随机偏移量以打散集中过期时刻、防御缓存雪崩，并对空值赋予独立短过期时间。
_Avoid_: RandomTtlResolver, AvalancheProtector

**RedisCacheTemplate**:
全景缓存三防（防击穿、防穿透、防雪崩）编程式执行模板，提供单键 DCL 互斥锁回源、批量 MGET 结合字典序排序锁防 N+1 回源、短 TTL 空哨兵写入及争锁超时穿透容灾。
_Avoid_: CacheHelper, SafeCacheTemplate, RedisCacheExecutor

**TwoLevelCache**:
两级缓存核心门面与 SPI 契约，统一编排 L1 本地内存缓存（自适应优先采用 Caffeine，缺省平滑退化为有界 `ConcurrentLruMemoryCache`）与 L2 Redis 分布式缓存，以及跨节点失效广播协议。
_Avoid_: MultiLevelCache, HybridCache, DoubleCache

**TwoLevelCacheManager**:
Spring Cache 标准适配管理器，将 `@Cacheable`、`@CachePut`、`@CacheEvict` 无缝桥接至 `TwoLevelCache` 引擎，支撑纳秒级本地命中与集群级协同淘汰。
_Avoid_: HybridCacheManager, MultiCacheManager, DualCacheManager

**CacheEvictMessage**:
两级缓存跨节点失效广播数据契约，封装目标 `cacheName`、缓存键、发起实例唯一 `instanceId` 与全量清空标记，从拓扑上阻断自重入回环。
_Avoid_: InvalidationPayload, EvictionEvent, CacheInvalidateCommand

**RedisBloomFilter**:
基于 Redisson `RBloomFilter` 封装的分布式布隆过滤器门面，支持配置化声明式预热、运行时动态初始化、全局键前缀隔离与 80% 容量水位安全预警。
_Avoid_: BloomFilterUtil, RedissonBloom, RedisFilter

**RedisBloomUtil**:
分布式布隆过滤器的全局静态操作门面，底层委托受管单例 `RedisBloomFilter`。
_Avoid_: BloomHelper, StaticBloomFilter

### 分布式异步协同与广播

**RedisDelayedQueue**:
基于 Redisson `RDelayedQueue` 与 `RBlockingQueue` 封装的分布式轻量延时任务门面，提供延时任务投递、到期拉取、取消移除以及死信队列（`{queue}:dlq`）探查、重放与清理能力。
_Avoid_: DelayQueue, DelayedQueue, TimeoutQueue

**DelayedQueueListener**:
声明式延时队列消费监听注解，由受管异步线程池驱动后台消费、四维上下文透传、失败退避重试与优雅停机排空。
_Avoid_: RedisDelayedConsumer, DelayMessageListener, QueueSubscriber

**DeadLetterMessage**:
延时队列死信结构化载荷契约，记录原始队列名、业务载荷、已重试次数、失败时间、异常类型与错误明细，供死信审计与重放使用。
_Avoid_: BadMessage, PoisonMessage, FailedPayload

**DelayedQueueFailureHandler**:
延时队列终态消费失败处理扩展点（SPI），在重试耗尽转入死信队列后触发，供业务接管外部告警或持久化补偿。
_Avoid_: DelayErrorHandler, DeadLetterCallback, QueueFailureListener

**RedisDelayedQueueUtil**:
延时队列全局静态操作门面，底层委托受管单例 `RedisDelayedQueue`，供静态上下文免注入快速执行延时投递、拉取、死信检视与受控重放。
_Avoid_: DelayQueueUtil, StaticDelayQueue, DelayedQueueHelper

**DeadLetterReplay**:
死信受控重放标准机制，针对延时队列（`{queue}:dlq`）与 Stream 死信流（`${stream}:dlq`）中的死信记录，在严格校验 `replayCount < maxReplayAttempts` 防雪崩阈值的前提下，自增重试计数并重新投递回原始业务队列/流执行消费重试；超过最大重放上限时强行熔断以阻断二次死信风暴。
_Avoid_: DeadLetterResend, RetryDeadLetter, ForceReplay

**PELOrphanClaiming**:
PEL 孤儿消息认领自愈机制，针对 Redis Stream 消费者突发崩溃或宕机后滞留在待决列表（Pending Entries List, PEL）中超时未确认的孤儿消息，由 `SmartLifecycle` 容器后台巡检或通过 `claimPendingMessages` 自动将超过最小闲置时长（`minIdleTime`）的待决消息重新指派抢占至健康消费者的容灾自愈流程。
_Avoid_: OrphanRescue, PendingSteal, StreamRebalance

**RedisStreamTemplate**:
基于 Redis 5.0+ Stream 封装的可靠流式消息与消费组运维门面，提供流追加、幂等建组、消费组读取、显式 `acknowledge`、PEL 待决消息探查、`claim` 孤儿认领、定长 `trim` 裁剪与死信流转。
_Avoid_: StreamManager, RedisStreamProducer, StreamClient

**RedisStreamUtil**:
Redis Stream 消息流的全局静态操作门面，底层委托受管单例 `RedisStreamTemplate`。
_Avoid_: StreamHelper, StaticStreamClient

**RedisStreamListener**:
基于 Redis Stream 消费组模型的声明式监听注解，由受管异步线程池驱动，支持自动建组、自动或手动 XACK、MDC 链路透传与死信隔离。
_Avoid_: StreamConsumer, StreamMessageListener, RedisStreamHandler

**StreamDeadLetterMessage**:
Redis Stream 死信结构化载荷契约，封装原始流名、消费组、消费者标识、原始记录 ID、消息内容、重试次数与终态异常信息。
_Avoid_: StreamBadRecord, PoisonStreamEntry

**StreamFailureHandler**:
Redis Stream 消费终态失败处理扩展点（SPI），在消费组重试耗尽并写入死信流（`${stream}:dlq`）后触发告警或审计回调。
_Avoid_: StreamErrorHandler, StreamDeadLetterCallback

**RedisMessagePublisher**:
基于 Redis Pub/Sub 的集群广播发布与动态订阅客户端，负责跨节点事件通知与本地状态刷新广播。
_Avoid_: TopicPublisher, RedisBroadcaster, EventSender

**ChannelTopicListener**:
基于 Redis Pub/Sub 的声明式频道监听注解，支持精确主题、`${...}` 占位符与通配模式订阅，以及多参数强类型安全反序列化分发。
_Avoid_: RedisListener, TopicSubscriber, PubSubConsumer
