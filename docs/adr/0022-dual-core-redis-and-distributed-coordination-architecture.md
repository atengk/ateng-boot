# 22. 双核驱动 Redis 基础设施与企业级分布式协同架构决策 (Dual-Core Redis Infrastructure and Distributed Coordination Architecture)

## 背景与痛点

随着企业级业务向高并发、分布式及微服务架构演进，数据缓存与分布式并发控制成为不可或缺的基础底座：

1. **单核选型两难困境**：
   - 纯采用 `spring-boot-starter-data-redis`（Lettuce）：具备一流的 Spring 生态契合度与标准 `RedisTemplate` 习惯，但其分布式锁仅能依托裸 `SETNX`，缺乏看门狗（Watchdog）自动续期机制，无法原生支持公平锁、读写锁与联锁，极易发生锁提前超时导致数据并发脏写；
   - 纯采用官方 `redisson-spring-boot-starter`：具备顶级的分布式协调原语，但其 Starter 会强行接管/覆写 Spring 的 `RedisConnectionFactory`，与 Spring Boot 3.3.x 数据源配置规范存在潜在冲突，且基础 K-V 操作习惯与国内主流开发者心智模型割裂。
2. **反序列化 CVE 与跨微服务类名绑定风险**：
   - 常见开源封装偏好开启 Jackson 的 `DefaultTyping.NON_FINAL`（JSON 携带 `@class` 全限定名），一旦微服务重构类路径变更即导致反序列化崩溃，且存在已知的反序列化远程代码执行安全隐患。
3. **分布式锁异常掩盖与慢锁治理黑盒**：
   - 业务处理超长导致锁提前失效时，切面若盲目调用 `lock.unlock()` 会抛出 Redisson 的 `IllegalMonitorStateException`，掩盖真实的业务异常与根因；同时缺乏持锁耗时监控，难以定位慢事务。
4. **轻量延时任务与广播消费门槛高**：
   - 中小企业对于“订单 30 分钟未支付关单”或“本地缓存刷新广播”等轻量异步场景，引入 RocketMQ/RabbitMQ 运维成本高昂，手写 Redis zset/pubsub 轮询极易发生漏处理与死循环。

---

## 核心架构与决策内容

经由 `/grill-with-docs` 决策树 3 轮推演与全面对齐，`ateng-boot` 决定新增独立子模块 `ateng-boot-redis`，确立**“双核融合驱动、安全类型自洽、全能并发协调与双模延时消息”**的架构决策。

```mermaid
flowchart TD
    subgraph ConfigLayer["配置层: 约定优于配置"]
        SpringCfg["spring.data.redis.* (官方数据源配置)"]
        AtengCfg["ateng.boot.redis.* (锁租约/慢锁阈值/看门狗)"]
    end

    subgraph DualCore["双核底层驱动 (Dual-Core Engine)"]
        LettuceCore["Spring Data Redis (Lettuce)\n• 原生连接池与生态契合\n• Spring Cache 管理器"]
        RedissonCore["Redisson Native Client\n• 复用同一连接配置\n• 纯净原生实例, 拒绝劫持"]
    end

    subgraph FacadeLayer["企业级门面与契约层"]
        RT["RedisTemplate<String, Object>\n(纯净 JSON 序列化 / 无 @class 污染)"]
        RU["RedisUtil 全能操作门面\n(String/Hash/List/Set/ZSet/Geo/BitMap/HLL/Pipeline)\n【防呆拦截: 强制游标 scan, 严禁 keys *】"]
        DL["@DistributedLock 声明式锁切面\n(SpEL 动态解析 + 看门狗 + 慢锁告警)"]
        DT["DistributedLockTemplate 编程式模板\n(函数式闭包 + 统一降级/重载 + 安全释放)"]
        LU["RedisLockUtil 静态门面\n(无注入无侵入静态调用)"]
        DQ["延时队列双模引擎 (DelayedQueue)\n(声明式 @DelayedQueueListener + 编程式 API)"]
        PS["Pub/Sub 广播引擎\n(跨实例轻量通知与本地缓存刷新)"]
        RL["业务级流控与计数\n(RedisRateLimiterClient + RedisAtomicUtil)"]
        CM["增强 CacheManager\n(独立 TTL / 防穿透空对象 / 防雪崩抖动)"]
    end

    SpringCfg --> LettuceCore
    SpringCfg --> RedissonCore
    AtengCfg --> DL
    AtengCfg --> DT

    LettuceCore --> RT
    LettuceCore --> CM
    RT --> RU

    RedissonCore --> DT
    DT --> DL
    DT --> LU
    RedissonCore --> DQ
    RedissonCore --> RL
    LettuceCore --> PS
```

### 1. 双核融合驱动与配置零侵入
- 依赖层同时引入 `spring-boot-starter-data-redis` 与原生 `redisson` 核心包（非第三方 starter）；
- 自动装配时 100% 优先继承 Spring 官方 `spring.data.redis.*`（涵盖单机、哨兵与集群模式），以此构建 `RedissonClient`，实现一套配置、两路共用、零污染。

### 2. 安全 JSON 序列化与跨系统解耦
- Key/HashKey 统一采用 `StringRedisSerializer`；
- Value/HashValue 采用基于 Jackson 的安全序列化，支持 Java 8 日期时间标准格式化与 Long 精度防丢失；
- **坚决不开启 `@class` 多态侵入**，Redis 中存储纯净标准 JSON，读取时通过 `RedisUtil.get(key, Class<T>)` 或 `TypeReference<T>` 完成安全映射，杜绝反序列化漏洞与跨服务重命名故障。

### 3. 双模一体化分布式锁引擎 (`@DistributedLock` + `DistributedLockTemplate` + `RedisLockUtil`)
- **声明式与编程式同构内核**：通过底层核心模板 `DistributedLockTemplate` 实现加锁、看门狗自动延期、`finally` 安全防误删释放与慢锁审计的 100% 逻辑复用，`DistributedLockAspect` 切面直接委托给模板执行；
- **双层调用接口**：
  - 声明式注解 `@DistributedLock`：面向方法级切面声明，支持 SpEL 动态键解析与数组多键联锁；
  - 编程式模板 `DistributedLockTemplate`：面向复杂流程内部受管闭包，支持 `execute`（默认快速失败）、带降级回调 `fallback` 重载与 `tryExecute`（布尔试探）；
  - 静态门面 `RedisLockUtil`：面向任意静态上下文与无需注入 Spring Bean 的轻量调用；
- **四阶锁模式**：支持 `REENTRANT`（可重入锁）、`FAIR`（公平锁）、`READ`（读锁）、`WRITE`（写锁）；
- **复合联锁 (MultiLock)**：支持同时原子锁定多个业务键，防止分布式死锁；
- **租约与看门狗**：租约时间默认 `-1` 激活 Redisson 看门狗自动续期（30s 周期自延）；支持自定义超时时长与等待时长；
- **防呆释放与慢锁审计**：在 `finally` 块中严格以 `lock.isHeldByCurrentThread()` 防御，释放失败仅记录告警日志，绝不抛出 `IllegalMonitorStateException` 掩盖业务异常；业务持锁超过慢锁阈值（默认 3s）自动触发 WARN 审计日志；争锁超时统一抛出 `BusinessException(429)`。

### 4. 游标安全扫描与全能门面 (`RedisUtil`)
- 封装五大基础结构（String, Hash, List, Set, ZSet）以及高频高级结构（Geo 地理位置、BitMap 签到打卡、HyperLogLog UV 统计）；
- 提供流式批处理 `executePipelined`；
- **生产防呆强红线**：严禁暴露裸 `keys *` 接口，全量收敛为基于游标的 `scan(pattern, count)` 分批分段扫描。

### 5. 双模轻量延时队列与广播消息
- 提供编程式 `RedisDelayedQueue` 门面（`offer`, `poll`, `take`）；
- 提供声明式 `@DelayedQueueListener(queue = "...")` 注解，由框架自动管理后台线程池轮询调度与异常补偿；
- 提供轻量 Pub/Sub 广播门面，便于实现集群节点事件通知与两级缓存失效。

### 6. 缓存增强与两级缓存 SPI 预留
- 定制 `RedisCacheManager`：支持 `@Cacheable` 为不同 cacheName 配置独立 TTL、自动缓存空对象防穿透、自动加入随机抖动防雪崩；
- 预留 `TwoLevelCache` SPI 契约扩展点，保证主模块轻量，不强制捆绑 Caffeine 内存包。

### 7. Phase 2 生产级深度演进 (Two-Level Cache, Cache Defense & Queue Hardening)
经由 `/grill-with-docs` 双轮推演，确立以下深化增量决策：
1. **两级缓存体系 (TwoLevelCache & TwoLevelCacheManager)**：
   - **双模驱动**：同时提供底层编程式 `TwoLevelCache` 门面与 Spring 原生 `TwoLevelCacheManager` 适配器，无缝支持 `@Cacheable` / `@CacheEvict`；
   - **依赖解耦与退化**：Caffeine 设为编译期 `<optional>true</optional>`；存在 Caffeine 时发挥 W-TinyLFU 顶级性能；缺类时自动平滑退化为基于原生 JVM `LinkedHashMap` 的软容量 LRU 内存缓存，严格限定最大条数防 OOM；
   - **单集中广播与防环**：全系统多 CacheName 共用单一集中 Redis 广播频道（`ateng:cache:evict:topic`），载荷封装 `CacheEvictMessage(cacheName, key, instanceId, clearAll)`；各实例根据唯一 `instanceId` 防自重入消除网络延迟竞争。
2. **工业级缓存防卫模板 (RedisCacheTemplate)**：
   - **单键与批量防击穿**：同时支持单 Key `getOrLoad` 与批量 `batchGetOrLoad`（结合 MGET + 缺失键 DCL + 分布式锁原子批量查库 + Pipeline 批量回填，彻底杜绝 N+1 穿透）；
   - **三防合一**：分布式锁防击穿、短 TTL 空值缓存防穿透、Jitter 随机偏移防雪崩；
   - **容灾穿透兜底**：争抢分布式锁超时后自动打 WARN 告警并平滑穿透查库，保证极端高并发或网络抖动下的可用性优先。
3. **分布式布隆过滤器 (RedisBloomFilter)**：
   - 基于 Redisson `RBloomFilter` 封装；支持 YAML 声明式配置启动预热（`ateng.boot.redis.bloom-filters.<name>`）与编程式动态双轨；内置容量逼近（80%）监控告警。
4. **延时队列生产级死信与受管线程治理**：
   - **受管线程合规**：全面取缔内部 `Executors.newFixedThreadPool` 野线程，接入 Core 受管线程池设施与 MDC 上下文装饰；
   - **死信队列 (DLQ)**：重试耗尽后自动将消息包装为 `DeadLetterMessage` 存入 `{queueName}:dlq`，并回调 `DelayedQueueFailureHandler` SPI（支持业务定制钉钉/飞书告警或持久化补偿）。
5. **声明式 Pub/Sub 订阅 (@ChannelTopicListener)**：
   - 补齐声明式订阅切面，支持 `${...}` 动态占位符与通配模式，根据方法入参类型完成安全 JSON 智能自动反序列化。
6. **全局 KeyPrefix 分层治理契约**：
   - 高级受管语义组件（锁、缓存模板、两级缓存、布隆、延时队列）统一自动前置拼接 `keyPrefix`；底层 `RedisUtil` 保持原生透传，提供 `buildKey()` 辅助，分层清晰透明。

---

## 权衡与收益考量 (Consequences)

### 正向收益
1. **兼收并蓄**：既享有 Spring Data Redis 原生生态红利，又坐拥 Redisson 强大的分布式协调能力；
2. **生产安全等级最高**：彻底屏蔽 `keys *` 生产事故隐患、屏蔽 `@class` 反序列化 CVE 隐患、屏蔽非法持锁人释放异常导致的故障掩盖、杜绝延时任务失败静默丢数据；
3. **极佳的开发体验**：开箱即用的两级缓存、批量防击穿模板、死信队列、注解驱动广播与声明式分布式锁，极大幅度削减业务胶水代码；
4. **零臃肿与弹性伸缩**：Caffeine 可选依赖不绑架底座体积，缺依赖平滑降级，单集中频道节省连接句柄。

### 妥协与成本
1. **轻微的内存与连接开销**：双核共存模式下，Lettuce 与 Redisson 会各自维护一套连接池。但在生产环境下，通过合理设置两者连接池最小空闲与最大连接数，其开销对现代服务器几乎微乎其微；
2. **两级缓存最终一致性窗口**：L1 本地缓存与 L2 Redis 之间存在极短暂的 Pub/Sub 广播网络传播时延（通常 < 10ms），适用于高频读、容忍微小延迟的准实时业务场景。

