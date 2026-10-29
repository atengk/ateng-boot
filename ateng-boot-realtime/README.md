# ateng-boot-realtime 实时通信底座 Starter

> 依托 Spring 6 与 Tomcat 10 原生底层构建的零臃肿全双工与流式双模实时通信底座，统一融通全双工 WebSocket 与半双工流式 Server-Sent Events (SSE)，提供并发安全会话装饰器（彻底根除 `TEXT_PARTIAL_WRITING` 并发写崩溃与 512KB 缓冲治理）、三阶握手鉴权（Query -> Header -> Cookie）与 Sa-Token 自动桥接、声明式上行方法级双向 Request-Response 路由（`@RealtimeAction` 与业务异常转译）、正交二进制帧上行监听 SPI（`RealtimeBinaryMessageListener`）、会话级滑动窗口限流防刷与 4005 阶梯断开（`SessionRateLimiter`）、Redisson Set 分布式群组治理与广播解散通知、Redisson ZSet 有界离线消息存储与 SSE `Last-Event-ID` 断点重放、统一推送门面（`RealtimeTemplate`）、大模型打字机流助手（`SseStreamHelper`）、幽灵死链 60s 心跳探针排空（`GhostConnectionDetector`）、去中心化多节点集群协同与 `sourceNodeId` 防反射风暴总线、Sa-Token 封禁事件联动实时切断、Spring `SmartLifecycle` 四阶优雅停机排空以及 RFC 6455 生产扩展状态码契约。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **统一推送门面** | 统一推送与会话治理门面 | `RealtimeTemplate` | `ateng.boot.realtime.enabled` | `sendToSession(sessionKey, message)` / `sendBinary(sessionKey, payload)` / `sendToUser(userId, message)` / `sendViaWebSocket(sessionKey, message)` / `sendViaSse(sessionKey, message)` / `sendToUserViaWebSocket(userId, message)` / `sendToUserViaSse(userId, message)` / `sendToGroup(groupId, message)` / `sendToTenant(tenantId, message)` / `sendToAll(message)` / `joinGroup(groupId, sessionKey/userId)` / `leaveGroup(groupId, sessionKey/userId)` / `getGroupSessions(groupId)` / `getClusterGroupMemberCount(groupId)` / `getClusterGroupMembers(groupId)` / `getLocalGroupMemberCount(groupId)` / `getLocalGroupMembers(groupId)` / `dismissGroup(groupId)` / `kickoutSession(sessionKey, [status])` / `kickoutUser(userId, [status])` / `kickoutTenant(tenantId, [status])` / `isUserOnline(userId)` / `isSessionOnline(sessionKey)` / `getOnlineCount()` / `getOnlineUserCount()` / `getOnlineCountByChannel(channelType)` / `getSessionsByUser(userId)` / `getSessionsByTenant(tenantId)` / `getAllSessions()` / `getSession(sessionKey)` / `getClusterOnlineCount()` / `pullAndClearOfflineMessages(userId)` / `getOfflineMessages(userId)` / `getOfflineMessagesSince(userId, lastEventId/fromTimestamp)` / `clearOfflineMessages(userId)` / `getOfflineMessageCount(userId)` | `RealtimeTemplateTest` / `ClusterAndOfflineScenarioTest` / `GroupMulticastScenarioTest` / `RealtimeAuthScenarioTest` / `WebSocketActionScenarioTest` / `RealtimeClusterDualModeScenarioTest` |
| **集群分布式协同** | 多节点去中心化广播门面 | `RealtimeClusterBroadcaster`<br>`StandaloneRealtimeClusterBroadcaster`<br>`RedissonRealtimeClusterBroadcaster` | `ateng.boot.realtime.cluster-mode`<br>`ateng.boot.realtime.cluster.topic`<br>`ateng.boot.realtime.cluster.nodes-hash-key`<br>`ateng.boot.realtime.cluster.metrics-interval` | `broadcast(packet)` / `addConsumer(consumer)` / `removeConsumer(consumer)` / `getNodeId()` / `isClusterEnabled()`，依托 Redis Pub/Sub 广播并注入 `sourceNodeId` 防反射回环丢弃 | `RealtimeClusterBroadcasterTest` / `RealtimeClusterAntiReflectionTest` / `RealtimeClusterMulticastTest` / `ClusterAndOfflineScenarioTest` / `RealtimeClusterDualModeScenarioTest` |
| **集群在线度量** | 集群节点在线度量统计与聚合 | `RealtimeClusterMetricsProvider`<br>`StandaloneClusterMetricsProvider`<br>`RedissonClusterMetricsReporter` | `ateng.boot.realtime.cluster.nodes-hash-key`<br>`ateng.boot.realtime.cluster.metrics-interval` | `getClusterOnlineCount()`，单机直接统计本地连接数，集群模式通过定时受管调度任务将本地计数上报至 Redis Hash 并聚合汇总 | `RealtimeClusterMetricsTest` / `ClusterAndOfflineScenarioTest` |
| **分布式群组治理** | Redisson Set 分布式群组管理器 | `RealtimeGroupManager`<br>`RedissonRealtimeGroupManager` | `ateng.boot.realtime.cluster.group-key-prefix`<br>`ateng.boot.realtime.cluster.session-groups-key-prefix` | `joinGroup(groupId, sessionKey)` / `leaveGroup(groupId, sessionKey)` / `leaveAllGroups(sessionKey)` / `dismissGroup(groupId)` / `getClusterGroupMembers(groupId)` / `getClusterGroupMemberCount(groupId)` / `getLocalGroupMembers(groupId)` / `getLocalGroupMemberCount(groupId)` / `getSessionGroups(sessionKey)` / `getAllGroupIds()` / `clear()`，正反向双索引维护，解散广播通知并自动剔除 | `RealtimeGroupManagerTest` / `RedissonRealtimeGroupManagerTest` / `GroupMulticastScenarioTest` / `RealtimeClusterDualModeScenarioTest` |
| **离线消息存储 SPI** | 离线暂存扩展接口与实现 | `RealtimeOfflineMessageProvider`<br>`RedissonRealtimeOfflineMessageProvider`<br>`InMemoryRealtimeOfflineMessageProvider`<br>`NoOpRealtimeOfflineMessageProvider` | `ateng.boot.realtime.offline.enabled`<br>`ateng.boot.realtime.offline.max-messages-per-user`<br>`ateng.boot.realtime.offline.ttl`<br>`ateng.boot.realtime.offline.key-prefix` | `saveOfflineMessage(userId, message)` / `pullAndClearOfflineMessages(userId)` / `getOfflineMessages(userId)` / `getOfflineMessagesSince(userId, lastEventId/fromTimestamp)` / `clearOfflineMessages(userId)` / `getOfflineMessageCount(userId)`，Redis ZSet 有界存储、时间戳 score 排序、只读断点窥探与原子 FIFO 淘汰 | `RealtimeOfflineMessageProviderTest` / `RedissonRealtimeOfflineMessageProviderTest` / `ClusterAndOfflineScenarioTest` / `SseSubscriptionScenarioTest` / `RealtimeClusterDualModeScenarioTest` |
| **会话级限流防刷** | 会话自适应滑动窗口限流器 | `SessionRateLimiter`<br>`RateLimitDecision` | `ateng.boot.realtime.websocket.rate-limit.enabled`<br>`ateng.boot.realtime.websocket.rate-limit.max-requests-per-second`<br>`ateng.boot.realtime.websocket.rate-limit.consecutive-violations-before-close` | `tryAcquire()`，滑动窗口精确限流，心跳 Ping 静默放行，超限下发 429 警告帧，连续恶意刷量以 4005 (RATE_LIMIT_EXCEEDED) 阶梯断开 | `SessionRateLimiterTest` / `AtengWebSocketHandlerTest` / `WebSocketActionScenarioTest` |
| **声明式双向路由** | 客户端上行事件动作标记 | `@RealtimeAction` | - | `@RealtimeAction("event")`，方法级注解声明，支持自适应注入 `RealtimeSession`、`SessionKey`、`RealtimeMessage` 及强类型业务 DTO 载荷；返回非 void/非 null 结果自动封装单播回发 `{event}:reply` 应答帧 | `RealtimeActionDispatcherTest` / `WebSocketActionScenarioTest` |
| **上行路由引擎** | 双向通信调度与上下文贯通引擎 | `RealtimeMessageDispatcher` | - | `dispatch(session, message)`，容器启动动态扫描构建反射路由表；执行前自动绑定 MDC `traceId`、`UserContextHolder`、`TenantContextHolder`，执行后 `finally` 确定性清理；拦截 `BusinessException` 转译为 `Result.failure` 应答帧 | `RealtimeActionDispatcherTest` / `WebSocketActionScenarioTest` |
| **二进制监听 SPI** | 原生二进制帧上行消费扩展点 | `RealtimeBinaryMessageListener` | - | `onBinaryMessage(session, payload)`，直接处理 WebSocket 原始字节帧（音视频流 ASR/TTS、Protobuf、文件分片），绕过 JSON 反序列化开销，贯通四维上下文；配合 `session.sendBinary(payload)` / `RealtimeTemplate.sendBinary(sessionKey, payload)` 实现完全对称的二进制双向全双工吞吐 | `AtengWebSocketHandlerTest` / `WebSocketActionScenarioTest` / `RealtimeTemplateTest` |
| **优雅停机排空** | 云原生优雅停机排空处理器 | `RealtimeGracefulShutdownHandler` | `ateng.boot.realtime.shutdown.enabled`<br>`ateng.boot.realtime.shutdown.drain-timeout`<br>`ateng.boot.realtime.shutdown.quiet-period` | 实现 Spring `SmartLifecycle` (Phase=Integer.MAX_VALUE - 1000)，按序执行拒绝新连接 -> 向本地会话广播 1012 重启帧 -> 缓冲排空与确定性物理切断 | `RealtimeGracefulShutdownTest` |
| **权限事件切断** | Sa-Token 账号封禁联动切断 | `RealtimeSaTokenListener` | - | 实现 Sa-Token `SaTokenListener` SPI，监听 `doDisable` 封禁与 `doKickout` 踢退，全集群广播 4002 (USER_KICKED_OUT) 物理切断长连接 | `RealtimeSaTokenSecurityTest` / `ClusterAndOfflineScenarioTest` |
| **会话中心** | 统一受管会话管理器 | `RealtimeSessionManager`<br>`RealtimeSession` | `ateng.boot.realtime.session.max-connections-per-user`<br>`ateng.boot.realtime.session.eviction-policy` | `registerSession(session)` / `removeSession(sessionKey, [status])` / `removeAndClose(sessionKey, status)` / `getSession(sessionKey)` / `getSessionsByUser(userId)` / `getSessionsByChannel(channelType)` / `getSessionsByTenant(tenantId)` / `getAllSessions()` / `getOnlineCount()` / `getOnlineUserCount()` / `containsSession(sessionKey)` / `clear()` / `addListener(listener)`，多维索引维护与单用户超限 FIFO 淘汰或 REJECT 治理 | `RealtimeSessionManagerTest` / `RealtimeSessionTest` |
| **打字机流助手** | 专职 SSE 命令式流助手 | `SseStreamHelper` | `ateng.boot.realtime.sse.default-timeout` | `create([timeout])` / `sendChunk(emitter, chunk)` / `sendEvent(emitter, message)` / `sendEvent(emitter, event, data, [id])` / `sendKeepalive(emitter)` / `sendComment(emitter, comment)` / `complete(emitter)` / `completeWithError(emitter, throwable)` / `isClientAbortException(throwable)`，静默吞咽客户端断开防日志风暴 | `SseStreamHelperTest` / `SseSubscriptionScenarioTest` |
| **并发写安全** | 生产级并发安全会话装饰器 | `ConcurrentWebSocketHandlerDecorator`<br>`ConcurrentWebSocketSessionDecorator` | `ateng.boot.realtime.websocket.send-buffer-limit`<br>`ateng.boot.realtime.websocket.send-timeout` | 统一为 WebSocket 端点挂载并发写安全装饰器，提供 512KB 缓冲区与无锁高并发安全发送，彻底根除 `TEXT_PARTIAL_WRITING` 异常 | `ConcurrentWebSocketSessionDecoratorTest` |
| **死链心跳探针** | 幽灵连接排空探针 | `GhostConnectionDetector` | `ateng.boot.realtime.heartbeat.interval`<br>`ateng.boot.realtime.heartbeat.idle-timeout` | 实现 Spring `SmartLifecycle` 托管生命周期，定时周期性向 WebSocket 下发协议 Ping 帧与向 SSE 发送 `:keepalive\n\n` 注释，超时 60s 强制 Close 排空死链 | `GhostConnectionDetectorTest` |
| **三阶握手鉴权** | 统一端点握手拦截器 | `AtengHandshakeInterceptor` | `ateng.boot.realtime.auth.token-query-param-names`<br>`ateng.boot.realtime.auth.header-name`<br>`ateng.boot.realtime.auth.token-prefix`<br>`ateng.boot.realtime.auth.cookie-name`<br>`ateng.boot.realtime.auth.allow-anonymous` | 三阶自适应提取凭据 Token（URL Query -> HTTP Header -> Cookie），结合 `RealtimeAuthHandshakeProvider` SPI 认证；显式带非法 Token 强行拒绝 401 | `AtengHandshakeInterceptorTest` / `RealtimeAuthScenarioTest` |
| **Sa-Token 桥接** | Sa-Token 原生鉴权提供者 | `SaTokenRealtimeAuthProvider` | - | `@ConditionalOnClass(StpUtil.class)` 条件装配，根据 Token 解析 `LoginId`、`LoginDevice` 与 `LoginUser` 载荷并提取多租户标识 | `SaTokenRealtimeAuthProviderTest` / `RealtimeAuthScenarioTest` |
| **原生应用事件** | Spring 原生生命周期事件广播 | `RealtimeSessionEventPublisher`<br>`RealtimeConnectedEvent`<br>`RealtimeDisconnectedEvent` | - | 监听会话注册与注销，向 Spring 容器广播标准应用事件，支持 `@EventListener` 跨模块解耦监听 | `RealtimeSessionEventPublisherTest` |
| **状态码契约** | RFC 6455 生产扩展关闭状态码 | `RealtimeCloseStatus` | - | 定义 `NORMAL(1000)`、`SERVICE_RESTART(1012)`、`AUTH_FAILED(4001)`、`USER_KICKED_OUT(4002)`、`MAX_CONNECTIONS_EXCEEDED(4003)`、`IDLE_TIMEOUT(4004)`、`RATE_LIMIT_EXCEEDED(4005)` 标准契约 | `RealtimeCloseStatusTest` |
| **通用消息契约** | 统一数据下发强类型载荷 | `RealtimeMessage<T>` | - | `RealtimeMessage.of(event, data)` / `RealtimeMessage.of(id, event, data)` / `RealtimeMessage.ofPersistent(event, data)`，规范化封装 `id`、`event`、`data`、`timestamp`、`traceId` 与 `tenantId` | `RealtimeMessageTest` |
| **端点三维坐标** | 用户端点会话唯一路由标识 | `SessionKey` | - | `SessionKey.of(userId, deviceType, clientId)` / `SessionKey.of(userId, clientId)` / `SessionKey.anonymous(clientId)`，实现多端多设备独立路由 | `SessionKeyTest` |
| **鉴权提供者 SPI** | 握手安全扩展接口 | `RealtimeAuthHandshakeProvider` | - | `authenticate(token)` / `authenticate(token, attributes)`，支持下游业务实现私有 JWT 或自定义鉴权接管 | `AtengHandshakeInterceptorTest` |
| **消息分发 SPI** | 上行未匹配消息兜底监听器 | `RealtimeMessageListener` | - | `onMessage(session, message)`，处理未被 `@RealtimeAction` 拦截的上行消息 | `RealtimeActionDispatcherTest` |
| **会话监听器 SPI** | 会话生命周期扩展接口 | `RealtimeSessionListener` | - | `onSessionRegistered(session)` / `onSessionClosed(session, [status])`，用于会话上下线扩展联动 | `RealtimeGroupManagerTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-realtime` 是面向生产就绪的工业级实时通信基础设施模块：
- **零臃肿哲学**：坚持纯原生标准库与 Spring 6 原生 WebSocket/SSE 能力优先，坚决杜绝引入 Netty、Socket.IO、Tio 等重型全量第三方框架；
- **双模全双工/流式对等互通**：
  - **全双工 WebSocket**：基于 `@EnableWebSocket` 与原生 `WebSocketHandler`，支持高频双向实时交互；
  - **半双工流式 SSE**：基于 `SseEmitter` 与 `SseStreamHelper`，专职应对大模型打字机推流与任务状态监控；
  - **统一调度门面**：通过 `RealtimeTemplate` 抹平底层传输协议与单机/集群拓扑差异，提供开箱即用的统一单播、组播与全服广播；
- **双向 Request-Response 模式**：`@RealtimeAction` 支持方法返回值自动封装应答帧并单播回传，帧 ID 精准对齐进来的请求 `id`，业务异常（`BusinessException`）自动转译为语义化 `Result.failure` 错误帧且物理连接稳定不中断；
- **原生二进制上行监听 SPI**：`RealtimeBinaryMessageListener` 支持原始音视频流（ASR/TTS）、Protobuf 等高吞吐场景，绕过 JSON 解析损耗，与 JSON 动作路由正交解耦；
- **会话级滑动窗口限流防刷**：内置自适应滑动窗口计数器，阶梯防御洪峰攻击（单秒突发下发 429 警告帧，持续恶意刷量下发 4005 状态码强制切断连接，心跳 Ping 协议帧静默豁免）；
- **去中心化多节点集群协同**：无侵入探测 `RedissonClient`，自适应升级为 Redis Pub/Sub 广播总线，基于 `sourceNodeId` 彻底消除反射回环风暴；
- **离线消息可靠存储与断点重放**：基于 Redisson ZSet 结构实现毫秒时间戳排序的有界离线存储，原子 FIFO 裁切防 OOM；支持 W3C SSE `Last-Event-ID` 只读断点续传（多端多 Tab 安全）；
- **云原生优雅停机排空**：基于 Spring `SmartLifecycle` 托管生命周期，实现四阶渐进排空（拒绝新连接 -> 注销集群 -> 广播 1012 重启帧 -> 缓冲排空），彻底杜绝滚动更新引发的客户端惊群风暴。

### Maven 依赖引入
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-realtime</artifactId>
</dependency>
```

---

## 2. 基础配置说明与生产范式

### 场景痛点
在实时通信系统建设中，传统架构普遍面临：
1. **并发写崩溃**：原生 WebSocket 会话在多线程并发发送消息时抛出 `TEXT_PARTIAL_WRITING` 甚至断开物理连接；
2. **恶意刷量与连接打满**：单个恶意客户端在单一长连接上瞬间高频上行百万帧，导致工作线程耗尽与服务雪崩；
3. **节点孤岛与回环风暴**：多节点部署时长连接彼此隔离；基于 Redis 广播时本节点收发自身消息引发死循环与重复消费；
4. **离线消息黑洞与断点丢失**：弱网或未登录用户下发消息直接丢失；重新连上时缺乏标准只读窥探与 SSE `Last-Event-ID` 历史补发；
5. **滚动发布惊群风暴**：微服务发布重启时长连接被瞬时暴力截断，成千上万客户端同一瞬间发起重连，导致存活节点雪崩；
6. **权限越权长连接滞留**：用户被封禁或修改密码后，HTTP 拦截生效但已建立的长连接依然存活，无法实时切断。

### 生产推荐配置 (`application.yml`)
```yaml
ateng:
  boot:
    realtime:
      enabled: true                     # 是否启用实时通信底座自动装配（默认：true）
      cluster-mode: auto                # 集群模式：AUTO (探测到 RedissonClient 自动启用 Redis 广播，否则降级单机), STANDALONE (强制单机), REDIS (强制 Redis 集群)
      cluster:
        topic: ateng:realtime:broadcast # 集群跨节点广播 Redis Topic（默认：ateng:realtime:broadcast）
        nodes-hash-key: ateng:realtime:cluster:nodes # 集群在线节点度量上报 Redis Hash 键名
        group-key-prefix: ateng:realtime:cluster:group: # 集群分布式群组正向成员索引 Redis Set 键前缀
        session-groups-key-prefix: ateng:realtime:cluster:session: # 集群分布式群组反向所属索引 Redis Set 键前缀
        metrics-interval: 5s            # 集群节点度量上报心跳周期（默认：5s）
      websocket:
        enabled: true                   # 是否开启 WebSocket 传输引擎（默认：true）
        path: /ws/realtime              # WebSocket 端点暴露路径（默认：/ws/realtime）
        max-text-message-buffer-size: 64KB   # 底层容器单次文本缓冲区上限（默认：64KB）
        max-binary-message-buffer-size: 64KB # 底层容器单次二进制缓冲区上限（默认：64KB）
        async-send-timeout: 10s         # 底层容器异步发送超时阈值（默认：10s）
        send-buffer-limit: 512KB        # 并发安全装饰器发送缓冲队列上限（默认：512KB）
        send-timeout: 10s               # 并发安全装饰器单次写超时阈值（默认：10s）
        rate-limit:
          enabled: true                 # 是否开启会话级上行流量限制（默认：false）
          max-requests-per-second: 50   # 单会话每秒最大允许上行请求数（默认：50）
          consecutive-violations-before-close: 3 # 连续超限触发强制断开长连接的违规次数阈值（默认：3，设为 0 表示不强制断开仅丢弃警告）
      cors:
        allowed-origins:                # 跨域握手允许来源白名单（默认：*）
          - "*"
      sse:
        enabled: true                   # 是否开启 SSE 传输引擎（默认：true）
        path: /sse/realtime             # SSE 端点暴露路径（默认：/sse/realtime）
        default-timeout: 30m            # SSE 默认长连接超时时间（默认：30m）
      session:
        max-connections-per-user: 10    # 单用户最大并发连接数（默认：10，0 为无限制）
        eviction-policy: fifo           # 超限淘汰策略：fifo (先入先出踢掉旧连接), reject (拒绝新连接)
      heartbeat:
        interval: 25s                   # 服务端保活探测周期（默认：25s，定时发送 Ping 帧与 :keepalive\n\n 注释）
        idle-timeout: 60s               # 最大空闲容忍时延（默认：60s，超时未发生任何上行动静则强制安全排空）
      auth:
        allow-anonymous: false          # 是否允许未携带凭据的匿名访客连接（默认：false，生产建议保持 false）
        token-query-param-names:        # URL Query 凭据参数名列表（按序匹配）
          - token
          - satoken
          - access_token
        header-name: Authorization      # HTTP 请求头凭据名称（默认：Authorization）
        token-prefix: "Bearer "         # HTTP 请求头凭据前缀（默认：Bearer ）
        cookie-name: satoken            # Cookie 凭据名称（默认：satoken）
      offline:
        enabled: true                   # 是否开启离线消息暂存（默认：false）
        max-messages-per-user: 100      # 单用户离线消息最大保留条数（默认：100，超出按 FIFO 淘汰防 OOM）
        ttl: 7d                         # 离线消息保留时间（默认：7d）
        key-prefix: ateng:realtime:offline: # 离线消息 Redis ZSet 键前缀
      shutdown:
        enabled: true                   # 是否开启 Spring SmartLifecycle 四阶优雅停机排空（默认：true）
        drain-timeout: 3s               # 停机在途缓冲物理冲刷等待超时阈值（默认：3s）
        quiet-period: 500ms             # 握手熔断静默期（默认：500ms）
```

---

## 3. 最小起步体验 (Happy Path & Out-of-the-Box)

### 第一板斧：声明式双向 Request-Response 与异常转译 (`@RealtimeAction`)
业务组件通过在 Spring Bean 方法上标记 `@RealtimeAction("event")`，方法返回非 void/非 null 业务结果时，框架自动封装为带相同请求 `id` 的 `{event}:reply` 强类型应答帧单播回发给客户端；业务层抛出 `BusinessException` 时，框架自动转译为 `Result.failure` 响应帧，物理连接保持稳固健康：

```java
package io.github.atengk.boot.sample.realtime.handler;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.realtime.annotation.RealtimeAction;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatActionHandler {

    private final RealtimeTemplate realtimeTemplate;

    /**
     * 1. 传统单向/回执交互
     */
    @RealtimeAction("chat")
    public void handleChatMessage(RealtimeSession session, RealtimeMessage<Map<String, Object>> message) throws IOException {
        Map<String, Object> data = message.getData();
        log.info("[Sample] 收到客户端聊天消息: fromUser={}, data={}", session.getUserId(), data);

        // 回执当前用户已收到
        RealtimeMessage<String> receipt = RealtimeMessage.of("chat:ack", "Message received from " + session.getUserId());
        session.sendMessage(receipt);
    }

    /**
     * 2. 双向 Request-Response 自动回发
     * 客户端发送: {"id":"req-001","event":"order:query","data":{"orderId":"ORD-888"}}
     * 服务端回发: {"id":"req-001","event":"order:query:reply","data":{"code":200,"data":{"status":"PAID"...}}}
     */
    @RealtimeAction("order:query")
    public Result<Map<String, Object>> handleOrderQuery(Map<String, Object> payload) {
        String orderId = payload != null ? String.valueOf(payload.get("orderId")) : "ORD-001";
        log.info("[Sample] 收到订单查询请求: orderId={}", orderId);
        return Result.success(Map.of("orderId", orderId, "status", "PAID", "amount", 199.9));
    }

    /**
     * 3. 业务受控异常自动语义化转译
     * 客户端发送: {"id":"req-002","event":"order:cancel","data":{"orderId":"ORD-888"}}
     * 服务端回发: {"id":"req-002","event":"order:cancel:reply","data":{"code":40001,"msg":"订单 [ORD-888] 已锁定不可取消","success":false}}
     */
    @RealtimeAction("order:cancel")
    public Result<Void> handleOrderCancel(Map<String, Object> payload) {
        String orderId = payload != null ? String.valueOf(payload.get("orderId")) : "ORD-001";
        log.info("[Sample] 收到订单取消请求: orderId={}", orderId);
        throw new BusinessException(40001, "订单 [" + orderId + "] 已锁定不可取消");
    }
}
```

### 第二板斧：使用 `RealtimeTemplate` 执行单播、组播解散与全服广播
```java
package io.github.atengk.boot.sample.realtime.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/realtime")
@RequiredArgsConstructor
public class RealtimeDemoController {

    private final RealtimeTemplate realtimeTemplate;

    /**
     * 向指定用户单播消息（目标在线时实时推送，离线时自动存入 ZSet 离线暂存箱）
     */
    @PostMapping("/send-user")
    public Result<Integer> sendToUser(@RequestParam String userId, @RequestParam String message) {
        int count = realtimeTemplate.sendToUser(userId, RealtimeMessage.of("notice:user", message));
        return Result.success(count);
    }

    /**
     * 全服广播公告（自动跨 Redis 集群多节点扇出投递）
     */
    @PostMapping("/broadcast")
    public Result<Integer> broadcast(@RequestParam String message) {
        int count = realtimeTemplate.sendToAll(RealtimeMessage.of("notice:all", message));
        return Result.success(count);
    }

    /**
     * 解散房间群组（全集群广播解散通知并清空群组成员）
     */
    @PostMapping("/dismiss-group")
    public Result<Integer> dismissGroup(@RequestParam String groupId) {
        int localEjected = realtimeTemplate.dismissGroup(groupId);
        return Result.success(localEjected);
    }

    /**
     * 查询离线消息并清空
     */
    @GetMapping("/offline-messages")
    public Result<List<RealtimeMessage<?>>> pullOfflineMessages(@RequestParam String userId) {
        List<RealtimeMessage<?>> messages = realtimeTemplate.pullAndClearOfflineMessages(userId);
        return Result.success(messages);
    }

    /**
     * 主动驱逐指定用户全端下线（全集群多节点同步断开长连接并返回 4002 状态码）
     */
    @PostMapping("/kickout")
    public Result<Integer> kickout(@RequestParam String userId) {
        int count = realtimeTemplate.kickoutUser(userId);
        return Result.success(count);
    }
}
```

### 第三板斧：大模型 LLM Token-by-Token 打字机流式推流 (`SseStreamHelper`)
```java
    /**
     * 演示 LLM 风格打字机异步流式推流
     */
    @GetMapping(value = "/stream-llm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLlm() {
        SseEmitter emitter = SseStreamHelper.create(60_000L);
        CompletableFuture.runAsync(() -> {
            try {
                String[] words = {"Hello", " ", "World", "!", " ", "This", " ", "is", " ", "ateng-boot-realtime", " ", "streaming."};
                for (String word : words) {
                    Thread.sleep(50);
                    // 发送分块；若客户端断开连接，立即返回 false，便于退出高开销大模型推理
                    boolean sent = SseStreamHelper.sendChunk(emitter, word);
                    if (!sent) {
                        return;
                    }
                }
                SseStreamHelper.complete(emitter);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return emitter;
    }
```

### 第四板斧：正交二进制音频流上行监听 (`RealtimeBinaryMessageListener`)
实现 `RealtimeBinaryMessageListener` SPI 并注册为 Spring Bean，即可零反序列化损耗直收客户端上行的二进制帧：
```java
package io.github.atengk.boot.sample.realtime.handler;

import io.github.atengk.boot.realtime.dispatcher.RealtimeBinaryMessageListener;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AudioBinaryStreamListener implements RealtimeBinaryMessageListener {

    @Override
    public void onBinaryMessage(RealtimeSession session, byte[] payload) {
        log.info("[Sample] 收到客户端原生二进制音频流分片: sessionKey={}, byteCount={}",
                session.getSessionKey(), payload != null ? payload.length : 0);
        // 直接交付语音识别 ASR 引擎或流式推入音频队列
    }
}
```

---

## 4. 生产定制与扩展接管 (Customization & Extensibility)

### 4.1 自定义二进制流监听 SPI (`RealtimeBinaryMessageListener`)
当需要处理原生音视频传输、Protobuf 自定义协议或大文件分片时，声明实现即可：
```java
@Component
public class ProtobufBinaryMessageListener implements RealtimeBinaryMessageListener {

    @Override
    public void onBinaryMessage(RealtimeSession session, byte[] payload) {
        // 使用 Google Protobuf 直接反序列化，与 JSON 动作路由互不干扰
        // MyProtoMessage proto = MyProtoMessage.parseFrom(payload);
    }
}
```

### 4.2 自定义离线消息暂存 SPI (`RealtimeOfflineMessageProvider`)
当默认的 Redisson ZSet / 内存离线存储无法满足业务审计或长期历史持久化需求时，开发者可声明自定义 Bean 无缝接管：
```java
@Component
public class CustomDatabaseOfflineMessageProvider implements RealtimeOfflineMessageProvider {

    @Override
    public void saveOfflineMessage(Object userId, RealtimeMessage<?> message) {
        // 存储到业务持久化数据库或专用离线消息表
    }

    @Override
    public List<RealtimeMessage<?>> pullAndClearOfflineMessages(Object userId) {
        // 从数据库拉取未读消息并标记为已排空
        return Collections.emptyList();
    }

    @Override
    public List<RealtimeMessage<?>> getOfflineMessages(Object userId) {
        // 只读查询未读离线消息（不清除，幂等安全）
        return Collections.emptyList();
    }

    @Override
    public List<RealtimeMessage<?>> getOfflineMessagesSince(Object userId, String lastEventId) {
        // 根据 W3C SSE Last-Event-ID 进行历史增量重放
        return Collections.emptyList();
    }

    @Override
    public List<RealtimeMessage<?>> getOfflineMessagesSince(Object userId, long fromTimestamp) {
        // 根据时间戳查询增量离线消息
        return Collections.emptyList();
    }

    @Override
    public void clearOfflineMessages(Object userId) {
        // 清理指定用户的离线暂存消息
    }

    @Override
    public int getOfflineMessageCount(Object userId) {
        // 统计未读消息总数
        return 0;
    }
}
```

### 4.3 自定义握手鉴权提供者 SPI (`RealtimeAuthHandshakeProvider`)
当默认的 Sa-Token 鉴权无法满足需求时（例如需要解析私有 JWT、双向 mTLS 证书或第三方 OAuth2 Token），只需声明 Bean 即可无缝接管：
```java
@Component
public class CustomRealtimeAuthProvider implements RealtimeAuthHandshakeProvider {

    @Override
    public RealtimeAuthPayload authenticate(String token, Map<String, Object> attributes) {
        if (!"valid-secret-token".equals(token)) {
            return null; // 返回 null 立即触发 401 握手拒绝
        }
        return RealtimeAuthPayload.builder()
                .userId("user-custom-007")
                .deviceType("WEB")
                .tenantId(1001L)
                .build();
    }
}
```

### 4.4 监听 Spring 原生连接生命周期事件
框架在每个会话握手成功与断开关闭时，均会向 Spring 上下文发布标准应用事件：
```java
@Component
@Slf4j
public class RealtimeAuditEventListener {

    @EventListener
    public void onConnected(RealtimeConnectedEvent event) {
        log.info("[Audit] 客户端上线: sessionKey={}, channel={}, remoteAddress={}",
                event.getSessionKey(), event.getChannelType(), event.getRemoteAddress());
    }

    @EventListener
    public void onDisconnected(RealtimeDisconnectedEvent event) {
        log.info("[Audit] 客户端离线: sessionKey={}, channel={}, closeStatus={}",
                event.getSessionKey(), event.getChannelType(), event.getCloseStatus());
    }
}
```

---

## 5. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 5.1 会话级自适应滑动窗口限流防刷与 4005 阶梯断开机制
为了防御单连接高频上行洪水攻击（DoS）：
- **滑动窗口精确限流**：配置 `rate-limit.enabled=true` 时，底层通过 `SessionRateLimiter` 维持毫秒级精准滑动窗口（`maxRequestsPerSecond` 默认 50）；
- **应用层心跳静默豁免**：无论是字符串字面量 `"ping"` 还是标准 JSON 心跳 `{"event":"ping"}`，均在前置卫语句中优先识别、即时回执 `"pong"` 并静默豁免限流统计，杜绝心跳探针被误杀；
- **阶梯式两阶段惩罚**：
  1. **阶段一（突发超限）**：单秒请求超限时，丢弃当前请求帧并向客户端下发 `429` 告警帧（`{"event":"error","data":{"code":429,"message":"Rate limit exceeded. Please slow down."}}`），连接保持活跃；
  2. **阶段二（恶意洪峰熔断）**：若客户端无视告警，连续超限违规达到 `consecutiveViolationsBeforeClose`（默认 3 次），服务端立即下发 `429` 终结帧并以 `RealtimeCloseStatus.RATE_LIMIT_EXCEEDED (4005)` 强制切断物理连接。

### 5.2 Redisson Set 分布式群组治理与广播解散通知机制
- **正反双索引原子维护**：基于 Redisson Set 结构，正向维护群组成员索引（`groupKeyPrefix + groupId`），反向维护会话所属群组索引（`sessionGroupsKeyPrefix + sessionKey`），保证毫秒级双向查询与解散；
- **解散广播通知**：调用 `realtimeTemplate.dismissGroup(groupId)` 时，服务端原子清空 Redis Set，向群组内所有成员广播下发 `RealtimeMessage.EVENT_GROUP_DISMISSED`（`group_dismissed`）通知帧，并在多节点集群广播 `DISMISS_GROUP` 信令同步排空其他节点的本地会话。

### 5.3 Redisson ZSet 有界离线存储原子裁切与 SSE `Last-Event-ID` 断点续传
- **时间戳 score 排序与原子 FIFO 裁切**：使用 Redis Sorted Set (ZSet) 存储离线消息，score 为毫秒时间戳；每次写入时通过 Lua 脚本原子执行 `ZREMRANGEBYRANK` 裁切超出 `maxMessagesPerUser`（默认 100 条）的最旧消息，设置 TTL（默认 7 天），绝对杜绝内存膨胀；
- **只读窥探与断点续传**：
  - 调用 `getOfflineMessages` / `getOfflineMessagesSince` 采用只读窥探模式，不清除底层 ZSet 元素，确保同账号多端多 Tab 并发重连时的幂等安全；
  - SSE 客户端在重连时携带 HTTP 请求头 `Last-Event-ID` 或 Query 参数 `lastEventId`，`AtengSseController` 自动提取并只读重放该 ID 之后的所有未读消息。

### 5.4 声明式双向通信与四维上下文透传零污染铁律
- **Request-Response ID 继承**：客户端请求帧携带 `id`（如 `"req-001"`）时，处理方法返回非 void/非 null 结果自动封装回发单播应答帧，保证应答帧的 `id` 100% 对齐请求 `id`，事件名默认为 `{event}:reply`；
- **业务异常受控转译**：`@RealtimeAction` 方法抛出 `BusinessException` 时，框架自动捕获并因果转译为 `Result.failure(code, msg)` 响应帧下发给客户端，物理连接保持稳固不中断；
- **四维上下文贯通与零污染清理**：在反射执行目标方法前，自动从会话提取并绑定 MDC `traceId`、`UserContextHolder`、`TenantContextHolder`；在 `finally` 块中严格执行 `MDC.remove("traceId")`、`UserContextHolder.clear()` 与 `TenantContextHolder.clear()`，彻底根除工作线程池复用污染。

### 5.5 多节点去中心化广播与 `sourceNodeId` 防反射风暴
- **无主架构 (Peer-to-Peer)**：基于 Redis Pub/Sub 实现跨节点消息广播，单播、组播与租户广播时，发送节点无需探知目标连接驻留在哪一台物理机器上，直接通过 Redis Topic 扇出；
- **防反射风暴保障**：每个集群节点启动时在内存中生成唯一且不可变的 `sourceNodeId`。跨节点广播通过 `RealtimeClusterPacket` 信封包装，各节点监听订阅 Topic 时，在前置卫语句中严格对比 `sourceNodeId`，丢弃自身发出的消息，彻底消除死循环与重复消费。

### 5.6 Spring `SmartLifecycle` 四阶优雅停机排空机制
为了彻底解决微服务发布上线时的惊群风暴与句柄泄露，`RealtimeGracefulShutdownHandler` 挂载至 `SmartLifecycle`（Phase 为 `Integer.MAX_VALUE - 1000` 即 `2147481647`，优先于 Web 容器与其他业务 Bean 销毁前排空）：
- **阶段一：停止握手接纳**：置位内部状态 `draining = true`，握手拦截器与 SSE 端点对所有新连接请求返回 HTTP 503 或拒绝握手；
- **阶段二：定向发送 1012 重启帧**：向当前节点纳管的所有活跃 WebSocket 与 SSE 连接主动下发 `RealtimeCloseStatus.SERVICE_RESTART (1012)` 关闭帧或事件，客户端收到后启动带抖动的指数退避（Jittered Backoff）重新连接其他可用节点；
- **阶段三：确定性超时排空**：等待 `drainTimeout`（默认 3s）缓冲冲刷；
- **阶段四：物理资源清理与集群注销**：注销集群广播订阅与集群在线节点度量，执行最终强制物理切断并清理底层会话池。

### 5.7 Sa-Token 账号封禁 / 踢下线 4002 强退切断联动
- **权限与长连接实时同步**：实现 Sa-Token 的 `SaTokenListener` 监听器；
- **全集群 4002 阻断**：当业务侧调用 `StpUtil.kickout(userId)`、`StpUtil.disable(userId, ...)` 封禁用户时，监听器自动拦截并向集群发起踢人广播，全集群各节点检索并强制切断该用户的所有物理长连接，发送标准 RFC 6455 状态码 `4002 (USER_KICKED_OUT)`，前端可据此精准展示“账号已被封禁/踢出”并阻断自动重连。

### 5.8 高并发写安全防卫 (`TEXT_PARTIAL_WRITING` 终结者)
- **底层隐患**：标准 Java WebSocket（JSR-356）规范明确规定：当一个消息正在异步发送时，严禁并发调用 `sendMessage()`，否则直接抛出 `java.lang.IllegalStateException: The remote endpoint was in state [TEXT_PARTIAL_WRITING]` 并强行关闭 Socket 连接；
- **防护机制**：`AtengWebSocketConfigurer` 在注册端点时自动挂载 `ConcurrentWebSocketHandlerDecorator`，接入层统一使用 `ConcurrentWebSocketSessionDecorator` 包装物理 Session，并配置 512KB 发送排队缓冲区上限与 10s 写超时保护。当并发写入冲突时，消息自动进入无锁环形等待队列顺序排空，彻底杜绝并发写异常。

### 5.9 幽灵连接 (Ghost Connections) 60s 心跳探针排空
- **主动周期探测**：后台 `GhostConnectionDetector`（基于 `SmartLifecycle` 托管生命周期）每隔 25s 自动向当前节点的所有 WebSocket 会话下发物理 Ping 协议帧，并向 SSE 连接发送 `:keepalive\n\n` 注释；
- **死链确定性排空**：若连接空闲超过 `idleTimeout`（默认 60s），探针执行确定性安全排空，以 `RealtimeCloseStatus.IDLE_TIMEOUT (4004)` 关闭物理句柄并排空 `RealtimeSessionManager` 与 `RealtimeGroupManager` 索引。

### 5.10 RFC 6455 生产扩展关闭状态码契约
遵循 RFC 6455 规范，4000-4999 区间专由应用层定义：

| 状态码 | 常量定义 | 业务场景语义 | 客户端处理与重连建议 |
| :--- | :--- | :--- | :--- |
| **1000** | `NORMAL` | 正常自愿关闭连接 | 正常结束，无需重连 |
| **1012** | `SERVICE_RESTART` | 服务端优雅停机或应用重启 | **必须重连**：使用带随机抖动（Jitter）的指数退避重连，严防瞬间雪崩 |
| **4001** | `AUTH_FAILED` | Token 过期、无效或鉴权失败 | **严禁自动重连**：应引导用户重新登录或刷新 Token 凭据 |
| **4002** | `USER_KICKED_OUT` | 用户被管理员封禁或踢出下线 | **严禁自动重连**：弹出被踢提示并注销本地会话 |
| **4003** | `MAX_CONNECTIONS_EXCEEDED` | 单用户并发连接数超过配额上限 | **严禁重连**：提示用户关闭其他多余标签页或设备 |
| **4004** | `IDLE_TIMEOUT` | 连接假死或心跳无动静超过 60s 超时 | **允许重连**：延迟数秒后重新发起握手 |
| **4005** | `RATE_LIMIT_EXCEEDED` | 会话持续恶意高频上行刷量超限熔断 | **延迟重连**：提示用户操作过于频繁，冷却后重连 |
