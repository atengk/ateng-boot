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
