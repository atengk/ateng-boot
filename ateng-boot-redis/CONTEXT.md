# Redis Context

`ateng-boot-redis` 负责 Redis 基础设施与企业级分布式协同能力的开箱即用自动装配，涵盖双核驱动客户端生命周期管控、安全序列化、全能操作工具门面、四阶声明式分布式锁、双模轻量延时队列、Pub/Sub 广播通信以及业务级并发流控。

## Language

**DualCoreClient**:
在共享同一套 Spring Boot 官方 `spring.data.redis.*` 数据源配置的前提下，同时装配 `RedisTemplate`（负责通用 K-V 操作与 Spring 缓存）与 `RedissonClient`（负责分布式锁与高级并发数据结构）的双核驱动协同架构。
_Avoid_: HybridClient, MultiClient, RedisRedissonBridge

**RedisTemplateCustomizer**:
负责装配强类型 `RedisTemplate<String, Object>`，以 StringRedisSerializer 序列化 Key，以纯净 JSON（无 `@class` 污染，防御反序列化漏洞与重命名风险）序列化 Value 并保持 Java 8 日期与 Long 精度防失真的序列化器。
_Avoid_: RedisConfig, JacksonRedisConfig, TemplateBuilder

**RedisUtil**:
开箱即用的静态与 Bean 统一操作门面，覆盖 String, Hash, List, Set, ZSet 五大结构与 Geo, BitMap, HyperLogLog 高级结构，提供流水线 Pipeline 流式执行，并在生产运维层面强制以游标 `scan` 分批扫描完全杜绝 `keys *` 阻塞事故。
_Avoid_: RedisHelper, CacheUtil, RedisManager

**DistributedLock**:
声明式分布式锁注解，声明于业务方法上，支持 SpEL 动态键解析与数组联锁，提供可重入锁、公平锁、读锁、写锁四种模式，以及看门狗自动延期与超时快速失败策略。
_Avoid_: RedissonLock, RedisLock, LockAnnotation, SyncLock

**DistributedLockAspect**:
基于 AOP 环绕拦截的分布式锁切面，驱动 `RedissonClient` 实施锁争抢、超时判断，在 `finally` 阶段安全校验当前线程持锁状态以杜绝 `IllegalMonitorStateException` 异常覆盖，并在持锁时间超过慢锁阈值时输出告警审计日志。
_Avoid_: LockInterceptor, DistributedLockHandler, RedissonLockAspect

**DistributedLockTemplate**:
编程式分布式锁执行模板，提供基于函数式回调的受保护代码块执行，自动编排锁获取、看门狗自动延期、`finally` 安全防误删释放、慢锁审计与超时快速失败。
_Avoid_: LockExecutor, RedissonTemplate, DistributedLockManager

**RedisLockUtil**:
编程式分布式锁的静态操作门面，底层委托给 `DistributedLockTemplate`，支持在静态上下文或无需注入组件中一键式安全执行加锁代码块。
_Avoid_: LockUtil, RedissonUtil, DistributedLockHelper

**DelayedQueue**:
基于 Redisson `RDelayedQueue` 与 `RBlockingQueue` 封装的企业级分布式轻量延时任务队列，支持到期自动出队调度。
_Avoid_: DelayQueue, RedisDelayQueue, TimeoutQueue

**DelayedQueueListener**:
声明式延时队列消费者注解，支持标注于组件类或方法上，由框架自动拉起受管后台调度线程进行无阻塞 `take()` 消费与异常重试。
_Avoid_: RedisDelayedConsumer, DelayMessageListener, QueueSubscriber

**RedisMessagePublisher**:
基于 Redis Pub/Sub 的轻量消息发布客户端，支持向指定主题广播事件对象与指令，供集群多节点状态刷新或两级缓存失效。
_Avoid_: TopicPublisher, RedisBroadcaster, EventSender

**RedisRateLimiterClient**:
基于 Redisson 令牌桶算法封装的独立业务级分布式限流客户端，支持在 Controller 之外的定时任务、MQ 消费及 RPC 调用中进行平滑流控。
_Avoid_: RedisLimiter, RateLimitService, TokenBucketClient

**RedisAtomicUtil**:
分布式原子自增与计数器操作工具，提供基于 Redis 计数器的步长递增、递减、滑动窗口频次防刷与日历重置号段发号支持。
_Avoid_: CounterUtil, RedisCounter, AtomicSequence

**TwoLevelCache**:
两级缓存核心门面与 SPI 契约，统一抽象 L1 本地内存缓存（优先适配高性能 Caffeine，无依赖时优雅降级为原生 JVM 内存结构）与 L2 Redis 集中分布式缓存，集成跨节点 Pub/Sub 失效广播协议以确保分布式缓存最终一致性。
_Avoid_: MultiLevelCache, HybridCache, DoubleCache

**TwoLevelCacheManager**:
Spring Cache 统一适配管理器，将 Spring 声明式缓存注解（`@Cacheable`、`@CachePut`、`@CacheEvict`）无缝桥接至底层 `TwoLevelCache` 引擎，实现零侵入享受两级缓存极速读取与自动广播淘汰。
_Avoid_: HybridCacheManager, MultiCacheManager, DualCacheManager

**RedisCacheTemplate**:
工业级防击穿、防穿透、防雪崩缓存安全执行模板，内置分布式互斥锁防击穿、DCL 双重检查加锁、空值短 TTL 缓存防穿透以及基准 TTL 随机抖动防雪崩，并在极端争锁超时场景下提供平滑穿透查库的容灾兜底保障。
_Avoid_: CacheHelper, SafeCacheTemplate, RedisCacheExecutor

**RedisBloomFilter**:
基于 Redisson `RBloomFilter` 封装的分布式布隆过滤器操作门面，支持配置化声明式预热、动态编程式初始化与容量逼近安全预警，用于在大规模黑名单、爬虫 IP 过滤与海量数据存在性判重场景中消除内存膨胀。
_Avoid_: BloomFilterUtil, RedissonBloom, RedisFilter

**DelayedQueueDeadLetter**:
延时队列死信保障机制，在消费者重试次数耗尽后自动将异常消息转入专用死信队列（`{queueName}:dlq`）并触发失败处理 SPI 回调，彻底杜绝数据静默丢失。
_Avoid_: PoisonQueue, ErrorQueue, DroppedQueue

**ChannelTopicListener**:
基于 Redis Pub/Sub 的声明式广播订阅注解，支持标注于 Spring Bean 方法上，由后台受管监听容器自动完成报文反序列化与方法反射分发。
_Avoid_: RedisListener, TopicSubscriber, PubSubConsumer

**DeadLetterMessage**:
延时队列死信结构体契约，封装原始队列名称、消息载荷、重试次数、失败时间、异常类名与错误明细，用于死信回溯与异步重放。
_Avoid_: BadMessage, PoisonMessage, FailedPayload

**DelayedQueueFailureHandler**:
延时队列终态消费失败处理扩展点（SPI），在重试次数耗尽后触发，供业务方接入外部告警通知（钉钉/飞书）或持久化补偿。
_Avoid_: DelayErrorHandler, DeadLetterCallback, QueueFailureListener

**CacheEvictMessage**:
两级缓存跨节点广播失效载荷，封装目标 cacheName、缓存 key、发起方实例唯一 instanceId 与全量清空标记，杜绝自重入死循环。
_Avoid_: InvalidationPayload, EvictionEvent, CacheInvalidateCommand

**RedisStreamTemplate**:
基于 Redis 5.0+ Stream 封装的流式消息生产与消费组运维门面，支持流追加、消费组管理、待决消息拉取与显式确认。
_Avoid_: StreamUtil, StreamManager, RedisStreamProducer

**RedisStreamListener**:
基于 Redis Stream 消费组模型的声明式消费监听注解，由受管异步线程池驱动，支持多消费者并发、消息确认与异常隔离。
_Avoid_: StreamConsumer, StreamMessageListener, RedisStreamHandler

**RedisIdempotentTemplate**:
面向非 Web 业务链路（MQ 消费、定时调度、异步 RPC）的受管分布式防重幂等模板，支持多态业务闭包与执行保护。
_Avoid_: IdempotentExecutor, DeduplicateTemplate, AntiReplayManager

**RedisSemaphoreTemplate**:
基于 Redisson 信号量封装的受管分布式并发度治理模板，提供资源槽位申请、超时快速失败与确定性安全归还。
_Avoid_: SemaphoreExecutor, ConcurrencyLimiter, PermitManager

**RedisScriptExecutor**:
受管原子 Lua 脚本执行器，提供脚本动态加载、SHA1 预编译缓存、参数序列化与强类型结果转换门面。
_Avoid_: LuaUtil, ScriptRunner, RedisLuaTemplate


