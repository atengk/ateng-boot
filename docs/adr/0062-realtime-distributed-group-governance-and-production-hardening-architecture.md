# 62. 实时通信分布式群组治理、离线消息可靠重放、会话级限流防刷与双向契约架构决策 (Realtime Distributed Group Governance, Offline Reliable Replay, Session-Level Rate Limiting & Bidirectional Contract Architecture)

## 状态
`Accepted`

---

## 背景与痛点 (Context & Problem Statement)

在 [ADR-0036](./0036-realtime-cluster-and-distributed-coordination.md) 中，`ateng-boot-realtime` 确立了全双工 WebSocket 与半双工 SSE 双规合一底座，以及基于 Redis Pub/Sub 的防反射广播和优雅停机机制。但在深入高频交互、大规模在线群聊、大模型打字机流式重连以及长连接安全防御等企业级真实场景中，暴露出以下关键系统瓶颈与功能盲区：

1. **分布式群组与房间成员感知盲区 (Distributed Group & Presence Blindspots)**：
   原 `RealtimeGroupManager` 仅在本地 JVM 内存中维护群组映射。虽然通过 Redis Pub/Sub 实现了跨节点下发，但当调用 `getGroupMemberCount(groupId)` 或 `getGroupSessions(groupId)` 时，仅能获取当前单实例的连接，无法感知集群全域房间总人数与在线成员清单；且缺少集群全域房间解散通知契约。
2. **多节点离线消息孤岛与 SSE 断点重放缺失 (Offline Message Silos & SSE Resumption Gap)**：
   默认离线消息提供者仅有内存实现（`InMemoryRealtimeOfflineMessageProvider`）。在微服务集群部署时，用户 A 离线在节点 1 接收暂存，上线连接到节点 2 时由于内存隔离无法拉取；且 W3C 原生 EventSource 客户端重连携带的 `Last-Event-ID` 标头未被利用，无法实现断线增量事件的无损重放。
3. **上行无界泛洪与长连接 DoS 攻击风险 (Upstream Message Flood & DoS Vulnerability)**：
   外层网关（如 Nginx）仅能在握手阶段限流。一旦长连接建立，客户端可以不受频控地每秒发送成千上万个数据帧，直接穿透至 JSON 解析与反射路由，极易造成服务实例 CPU 耗尽与线程饥饿。
4. **上行路由单向限制与 RPC 回执样板代码 (One-Way Action Limitation & RPC Boilerplate)**：
   `@RealtimeAction` 方法的返回值被直接丢弃，若业务需要向请求方回执应答（如 ACK 或业务结果 `Result<T>`），必须手动注入 `RealtimeTemplate` 显式调用 `sendToSession`，缺乏标准化的 Request-Response 双向通信机制。
5. **长连接上下文断层与 MDC 追踪断流 (Context Disconnect & Lost TraceId)**：
   握手阶段解析出的用户与租户身份未在分发器调度时自动挂载至当前执行线程，导致 `@RealtimeAction` 业务方法无法直接使用 `UserContextHolder`、`TenantContextHolder` 与结构化日志 `MDC.get("traceId")`。
6. **多模态二进制帧处理空白 (Binary Message Routing Void)**：
   原生 `handleBinaryMessage` 仅作为活跃时间刷新存根，面对现代语音大模型流式输入（ASR）、Protobuf 工业协议与文件切片上行缺乏轻量解耦的专职 SPI。

---

## 决策内容与架构拓扑 (Decision & Architecture)

基于纯原生、零臃肿与分层防卫原则，我们在 `ateng-boot-realtime` 中全面构建 **分布式群组治理、离线存储闭环、会话级限流防刷与双向契约架构**：

```mermaid
flowchart TD
    subgraph ClientLayer["多端客户端接入层"]
        WsClient["WebSocket 客户端 (多端/多Tab)"]
        SseClient["EventSource 客户端 (支持 Last-Event-ID)"]
    end

    subgraph EdgeGuard["传输边界与会话级流量防卫"]
        SessionRateLimiter["SessionRateLimiter (无锁纳秒级滑动窗口限流器)"]
        WsDecorator["ConcurrentWebSocketHandlerDecorator (512KB 缓冲区)"]
    end

    subgraph CoreEngine["实时调度与上下文生命周期引擎"]
        WsHandler["AtengWebSocketHandler"]
        Dispatcher["RealtimeMessageDispatcher (MDC + User/TenantContext 自动绑定与清理)"]
        ActionEngine["@RealtimeAction 路由引擎 (支持返回值自动打包 {event}:reply 与异常转译)"]
        BinarySPI["RealtimeBinaryMessageListener SPI (二进制流正交路由)"]
        SseController["AtengSseController (增量重放与只读窥探)"]
    end

    subgraph StorageAndCluster["分布式状态协同与存储底座 (ateng-boot-redis 可选协作)"]
        RedissonGroupManager["RedissonRealtimeGroupManager<br>(双向 Redis Set: group:{id} & session:{key}:groups)"]
        RedissonOfflineProvider["RedissonRealtimeOfflineMessageProvider<br>(Redis ZSet: 有界时间序存储与 TTL 淘汰)"]
        PubSubBus["Redis Pub/Sub 广播总线 (带 sourceNodeId 防反射风暴)"]
    end

    WsClient -->|文本/JSON 上行| SessionRateLimiter
    SessionRateLimiter -- "超限警告 429 / 恶意熔断 4005" --> WsClient
    SessionRateLimiter -- "放行" --> WsHandler
    WsClient -->|二进制上行| WsHandler
    WsHandler -->|二进制帧| BinarySPI
    WsHandler -->|JSON 文本帧| Dispatcher
    Dispatcher --> ActionEngine
    ActionEngine -->|自动回执 {event}:reply| WsDecorator
    WsDecorator --> WsClient

    SseClient -->|GET /sse/realtime?Last-Event-ID=...| SseController
    SseController -->|ZRANGEBYSCORE 只读窥探重放| RedissonOfflineProvider

    CoreEngine --> RedissonGroupManager
    CoreEngine --> RedissonOfflineProvider
    CoreEngine --> PubSubBus
```

---

## 核心设计与防线展开 (Core Design & Safeguards)

### 1. `ateng-boot-redis` 可选协作与依赖零臃肿
- **依赖声明契约**：在 `ateng-boot-realtime/pom.xml` 中将裸 `org.redisson:redisson` 升级为 `ateng-boot-redis`（标记为 `<optional>true</optional>`），统一复用已纳管的 `RedissonClient`、`RedisUtil` 与原子执行能力；
- **优雅自适应降级**：通过 `@ConditionalOnClass(RedissonClient.class)` 与 `@ConditionalOnBean` 守卫，无 Redis 依赖时 100% 平滑退回单机纯内存自洽（`StandaloneRealtimeClusterBroadcaster`、`RealtimeGroupManager`、`InMemoryRealtimeOfflineMessageProvider`），保证底座零膨胀。

### 2. 双向 Set 分布式群组治理与全域解散协议 (`RedissonRealtimeGroupManager`)
- **双向 Redis Set 索引结构**：
  - 正向成员索引：`ateng:realtime:cluster:group:{groupId}`，集合内存储 `SessionKey.toString()`；
  - 反向所属索引：`ateng:realtime:cluster:session:{sessionKey}:groups`，集合内存储该会话加入的全部 `groupId`；
- **全域在线感知**：提供 `getClusterGroupMemberCount(groupId)` 与 `getClusterGroupMembers(groupId)`，通过 Redis SCARD 与 SMEMBERS 获取全集群跨节点房间实时总人数与完整成员坐标；
- **级联清理与解散通知**：
  - 会话注销、心跳超时或节点停机时，依据反向索引原子清除其所在的所有群组成员记录；
  - `RealtimeTemplate.dismissGroup(groupId)`：原子删除 Redis 正向群组 Set，并通过 Redis Pub/Sub 广播 `RealtimeClusterPacket.DISMISS_GROUP`，全集群各节点本地解散该房间并向下游连接推送标准 `group_dismissed` 系统事件。

### 3. Redis ZSet 有界离线存储与 SSE `Last-Event-ID` 只读重放 (`RedissonRealtimeOfflineMessageProvider`)
- **有序存储与时间序索引**：采用 Redis ZSet `ateng:realtime:offline:{userId}`，以消息创建毫秒级时间戳作为 `score`，确保时序严格单调递增；
- **双重有界防爆仓防线**：单次写入原子执行 `ZREMRANGEBYRANK 0 -(maxMessages)` 保障 FIFO 淘汰，结合 Redis 键级 `EXPIRE` 注入强类型 `ttl`（默认 7 天），从物理根源防御存储泄漏；
- **W3C SSE 断点续传**：`AtengSseController` 接收 `Last-Event-ID`，通过 `ZRANGEBYSCORE (lastTimestamp, +inf)` 执行**只读窥探（Read-Only Peek）**推流重放，既支持多端同时接入重放，又规避了破坏性读取导致的消息丢失。

### 4. 会话级内存滑动窗口限流与两阶梯防御 (`SessionRateLimiter`)
- **零网络延迟频控**：在每个 `RealtimeSession` 内部维护无锁原子滑动计数器，纯在本地易失性内存执行，吞吐超数十万次/秒且对 Redis 零压力；
- **阶梯式惩罚处置**：
  - **第 1 阶（超限丢弃与警告）**：超出 `ateng.boot.realtime.websocket.rate-limit.max-requests-per-second` 时直接丢弃当前请求帧，原路返回 `event: "error", code: 429` 警告通知；
  - **第 2 阶（恶意熔断与强制排空）**：同一连接连续 3 次触发超限或瞬时超过阈值 3 倍时，服务端判定为恶意泛洪或代码死循环，主动发送 RFC 6455 应用层专用关闭码 `4005 (RATE_LIMIT_EXCEEDED)` 并物理切断长连接。

### 5. `@RealtimeAction` 双向契约与因果异常转译 (`RealtimeActionResponse`)
- **透明 Request-Response 回发**：客户端上行携带 `id`（请求序列号）与 `event`；当业务方法返回非 `null`/非 `void` 结果时，分发器自动组装下行 `RealtimeMessage`，保持原 `id`，事件名默认为 `{event}:reply`，原路推回发送方；
- **受管业务异常保底**：当业务方法抛出 `BusinessException` 时，分发器就地捕获并转译为 `Result.failure(e.getResultCode(), e.getMessage())` 下发给客户端，杜绝未捕获异常引发长连接崩溃。

### 6. 四维上下文确定性装配与优雅停机清场
- **分发器全链路透传**：`RealtimeMessageDispatcher` 在调度前，提取消息 `traceId` 注入 MDC，并将 `RealtimeSession` 的 `userId` 与 `tenantId` 填充至 `UserContextHolder` 与 `TenantContextHolder`；执行后在 `finally` 中严格调用 `clear()`，彻底杜绝跨长连接复用线程池时的脏读；
- **优雅停机反向批量注销**：在 `SmartLifecycle` Phase 1~2 停机阶段，`RedissonRealtimeGroupManager` 批量反注册本节点纳管的全部群组成员，消除停机遗留的幽灵节点成员。

---

## 收益与不变量保证 (Consequences & Invariants)

1. **环境韧性与零臃肿不变量**：保持对 `ateng-boot-redis` 的可选依赖解耦，单机与分布式集群对外 API 100% 保持对称；
2. **集群全域状态一致性保证**：Redis 双向 Set 与 ZSet 提供了确定性的房间成员感知与离线断点重放，杜绝死连接与幽灵成员；
3. **分层安全与防爆防线保证**：会话级轻量限流填补了握手后长连接流量治理空白，4005 状态码与因果异常转译保障系统高吞吐下的平稳运行；
4. **开发体验与链路追踪保障**：双向路由与 MDC/上下文自动贯通使实时长连接开发体验与标准 Spring MVC REST 接口保持高度一致。
