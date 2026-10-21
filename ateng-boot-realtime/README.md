# ateng-boot-realtime 实时通信底座 Starter

> 依托 Spring 6 与 Tomcat 10 原生底层构建的零臃肿全双工与流式双模实时通信底座，统一融通全双工 WebSocket 与半双工流式 Server-Sent Events (SSE)，提供并发安全会话装饰器（彻底根除 `TEXT_PARTIAL_WRITING` 并发写崩溃与 64KB 缓冲治理）、三阶握手鉴权（Query -> Header -> Cookie）与 Sa-Token 自动桥接、声明式上行方法级路由（`@RealtimeAction`）、单机会话协同与房间订阅管理（`RealtimeGroupManager`）、统一推送门面（`RealtimeTemplate`）、大模型打字机流助手（`SseStreamHelper`）、幽灵死链 60s 心跳探针排空（`GhostConnectionDetector`）以及 RFC 6455 生产扩展状态码契约。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **统一推送门面** | 统一推送与会话治理门面 | `RealtimeTemplate` | `ateng.boot.realtime.enabled` | `sendToSession(sessionKey, message)` / `sendToUser(userId, message)` / `sendViaWebSocket(sessionKey, message)` / `sendViaSse(sessionKey, message)` / `sendToUserViaWebSocket(userId, message)` / `sendToUserViaSse(userId, message)` / `sendToGroup(groupId, message)` / `sendToTenant(tenantId, message)` / `sendToAll(message)` / `joinGroup(groupId, sessionKey/userId)` / `leaveGroup(groupId, sessionKey/userId)` / `getGroupSessions(groupId)` / `getGroupMemberCount(groupId)` / `kickoutSession(sessionKey, [status])` / `kickoutUser(userId, [status])` / `isUserOnline(userId)` / `isSessionOnline(sessionKey)` / `getOnlineCount()` / `getOnlineUserCount()` / `getOnlineCountByChannel(channelType)` / `getSessionsByUser(userId)` / `getAllSessions()` / `getSession(sessionKey)` | `RealtimeTemplateTest` / `GroupMulticastScenarioTest` / `RealtimeAuthScenarioTest` |
| **会话中心** | 统一受管会话管理器 | `RealtimeSessionManager`<br>`RealtimeSession` | `ateng.boot.realtime.session.max-connections-per-user`<br>`ateng.boot.realtime.session.eviction-policy` | `registerSession(session)` / `removeSession(sessionKey, [status])` / `removeAndClose(sessionKey, status)` / `getSession(sessionKey)` / `getSessionsByUser(userId)` / `getSessionsByChannel(channelType)` / `getSessionsByTenant(tenantId)` / `getAllSessions()` / `getOnlineCount()` / `getOnlineUserCount()` / `containsSession(sessionKey)` / `clear()` / `addListener(listener)`，多维索引维护与单用户超限 FIFO 淘汰或 REJECT 治理 | `RealtimeSessionManagerTest` / `RealtimeSessionTest` |
| **分组协同** | 单机频道订阅与分组管理器 | `RealtimeGroupManager` | - | `joinGroup(groupId, sessionKey)` / `leaveGroup(groupId, sessionKey)` / `leaveAllGroups(sessionKey)` / `getGroupSessions(groupId)` / `getGroupMemberCount(groupId)` / `getSessionGroups(sessionKey)` / `getAllGroupIds()` / `clear()`，实现 `RealtimeSessionListener` 监听会话断开自动排空群组 | `RealtimeGroupManagerTest` / `GroupMulticastScenarioTest` |
| **打字机流助手** | 专职 SSE 命令式流助手 | `SseStreamHelper` | `ateng.boot.realtime.sse.timeout` | `create([timeout])` / `sendChunk(emitter, chunk)` / `sendEvent(emitter, message)` / `sendEvent(emitter, event, data, [id])` / `sendKeepalive(emitter)` / `complete(emitter)` / `completeWithError(emitter, throwable)` / `isClientAbortException(throwable)`，静默吞咽客户端断开防日志风暴 | `SseStreamHelperTest` / `SseSubscriptionScenarioTest` |
| **声明式路由** | 客户端上行事件动作标记 | `@RealtimeAction` | - | `@RealtimeAction("event")`，方法级注解声明，支持自适应注入 `RealtimeSession`、`SessionKey`、`RealtimeMessage` 及强类型业务 DTO 载荷 | `RealtimeActionDispatcherTest` / `WebSocketActionScenarioTest` |
| **上行路由引擎** | 客户端上行消息分发调度引擎 | `RealtimeMessageDispatcher` | - | `dispatch(session, jsonPayload)`，容器启动时动态扫描 Spring Bean 构建反射路由表，未匹配时委托 `RealtimeMessageListener` SPI 兜底 | `RealtimeActionDispatcherTest` / `WebSocketActionScenarioTest` |
| **并发写安全** | 生产级并发安全会话装饰器 | `ConcurrentWebSocketSessionDecorator` | `ateng.boot.realtime.websocket.buffer-size` | 基于原生 `ConcurrentWebSocketSessionDecorator` 封装，提供 64KB 环形缓冲与无锁高并发安全发送，彻底根除 `TEXT_PARTIAL_WRITING` 异常 | `ConcurrentWebSocketSessionDecoratorTest` |
| **死链心跳探针** | 幽灵连接排空探针 | `GhostConnectionDetector` | `ateng.boot.realtime.heartbeat.ping-interval`<br>`ateng.boot.realtime.heartbeat.idle-timeout` | 实现 Spring `SmartLifecycle` 托管生命周期，定时周期性向 WebSocket 下发协议 Ping 帧与向 SSE 发送 `:keepalive\n\n` 注释，超时 60s 强制 Close 排空死链 | `GhostConnectionDetectorTest` |
| **三阶握手鉴权** | 统一端点握手拦截器 | `AtengHandshakeInterceptor` | `ateng.boot.realtime.auth.token-param-name`<br>`ateng.boot.realtime.auth.token-header-name`<br>`ateng.boot.realtime.auth.allow-anonymous` | 三阶自适应提取凭据 Token（URL Query -> HTTP Header -> Cookie），结合 `RealtimeAuthHandshakeProvider` SPI 认证；显式带非法 Token 强行拒绝 401 | `AtengHandshakeInterceptorTest` / `RealtimeAuthScenarioTest` |
| **Sa-Token 桥接** | Sa-Token 原生鉴权提供者 | `SaTokenRealtimeAuthProvider` | - | `@ConditionalOnClass(StpUtil.class)` 条件装配，根据 Token 解析 `LoginId`、`LoginDevice` 与 `LoginUser` 载荷并提取多租户标识 | `SaTokenRealtimeAuthProviderTest` / `RealtimeAuthScenarioTest` |
| **原生应用事件** | Spring 原生生命周期事件广播 | `RealtimeSessionEventPublisher`<br>`RealtimeConnectedEvent`<br>`RealtimeDisconnectedEvent` | - | 监听会话注册与注销，向 Spring 容器广播标准应用事件，支持 `@EventListener` 跨模块解耦监听 | `RealtimeSessionEventPublisherTest` |
| **状态码契约** | RFC 6455 生产扩展关闭状态码 | `RealtimeCloseStatus` | - | 定义 `NORMAL(1000)`、`SERVICE_RESTART(1012)`、`AUTH_FAILED(4001)`、`USER_KICKED_OUT(4002)`、`MAX_CONNECTIONS_EXCEEDED(4003)`、`IDLE_TIMEOUT(4004)` 标准契约 | `RealtimeCloseStatusTest` |
| **通用消息契约** | 统一数据下发强类型载荷 | `RealtimeMessage<T>` | - | `RealtimeMessage.of(event, data)`，规范化封装 `id`、`event`、`data`、`timestamp`、`traceId` 与 `tenantId` | `RealtimeMessageTest` |
| **端点三维坐标** | 用户端点会话唯一路由标识 | `SessionKey` | - | `SessionKey.of(userId, deviceType, clientId)` / `SessionKey.of(userId, clientId)` / `SessionKey.anonymous(clientId)`，实现多端多设备独立路由 | `SessionKeyTest` |
| **鉴权提供者 SPI** | 握手安全扩展接口 | `RealtimeAuthHandshakeProvider` | - | `authenticate(token)` / `authenticate(token, attributes)`，支持下游业务实现私有 JWT 或自定义鉴权接管 | `AtengHandshakeInterceptorTest` |
| **消息分发 SPI** | 上行未匹配消息兜底监听器 | `RealtimeMessageListener` | - | `onMessage(session, message)`，处理未被 `@RealtimeAction` 拦截的上行消息 | `RealtimeActionDispatcherTest` |
| **会话监听器 SPI** | 会话生命周期扩展接口 | `RealtimeSessionListener` | - | `onSessionRegistered(session)` / `onSessionClosed(session, [status])`，用于会话上下线扩展联动 | `RealtimeGroupManagerTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-realtime` 是面向生产就绪的工业级实时通信基础设施模块：
- **零臃肿哲学**：坚持纯原生标准库与 Spring 6 原生 WebSocket/SSE 能力优先，坚决杜绝引入 Netty、Socket.IO、Tio、Hutool 等重型第三方全量工具包；
- **双模全双工/流式对等互通**：
  - **全双工 WebSocket**：基于 `@EnableWebSocket` 与 原生 `WebSocketHandler`，支持高频双向实时互动；
  - **半双工流式 SSE**：基于 `SseEmitter` 与 `SseStreamHelper`，专职应对大模型打字机推流与任务状态监控；
  - **统一调度门面**：通过 `RealtimeTemplate` 抹平传输协议差异，提供统一单播、组播与全服广播；
- **高并发写安全与幽灵死链防御**：内置 64KB 环形缓冲并发装饰器与 60s 幽灵连接探针，解决底层长连接内存与线程安全隐患。

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
1. **并发写崩溃**：原生 WebSocket 会话在多线程同时发送消息时直接抛出 `TEXT_PARTIAL_WRITING` 甚至断开物理连接；
2. **幽灵假死连接泄露**：客户端因网络断网、断电或异常崩溃未走标准 TCP FIN/RST 流程，服务端长时间无法感知导致连接句柄泄露、内存溢出；
3. **鉴权不对称与协议穿透**：握手阶段缺乏有效凭据拦截，伪造凭据容易伪装成匿名用户建立连接引发安全渗透；
4. **长连接大模型打字机日志风暴**：客户端主动关闭浏览器时，服务端 SSE 抛出大量的 `ClientAbortException` 导致控制台日志刷屏。

### 生产推荐配置 (`application.yml`)
```yaml
ateng:
  boot:
    realtime:
      enabled: true                     # 是否启用实时通信模块自动装配（默认：true）
      websocket:
        enabled: true                   # 是否开启 WebSocket 传输引擎（默认：true）
        path: /ws/realtime              # WebSocket 端点暴露路径（默认：/ws/realtime）
        allowed-origins: "*"            # 跨域握手允许来源白名单（默认：*）
        buffer-size: 64KB               # 单会话发送缓冲区队列上限（默认：64KB，彻底根治 TEXT_PARTIAL_WRITING）
      sse:
        enabled: true                   # 是否开启 SSE 传输引擎（默认：true）
        path: /sse/realtime              # SSE 端点暴露路径（默认：/sse/realtime）
        timeout: 30m                    # SSE 默认长连接超时时间（默认：30m）
      session:
        max-connections-per-user: 5     # 单用户最大并发连接数（默认：10，0 为无限制）
        eviction-policy: fifo           # 超限淘汰策略：fifo (先入先出踢掉旧连接), reject (拒绝新连接)
      heartbeat:
        ping-interval: 25s              # 服务端保活探测周期（默认：25s，定时发送 Ping 帧与 :keepalive\n\n 注释）
        idle-timeout: 60s               # 最大空闲容忍时延（默认：60s，超时未发生任何上行动静则强制安全排空）
      auth:
        token-param-name: token         # URL Query 凭据参数名（默认：token）
        token-header-name: Sec-WebSocket-Protocol # 请求头凭据名称（支持 Sec-WebSocket-Protocol 或 Authorization）
        allow-anonymous: true           # 是否允许未携带凭据的匿名访客连接（默认：false，生产建议根据业务配置）
```

---

## 3. 最小起步体验 (Happy Path & Out-of-the-Box)

### 第一板斧：声明式上行路由交互 (`@RealtimeAction`)
业务组件通过在 Spring Bean 方法上标记 `@RealtimeAction("event")`，自动映射客户端发送的 JSON 上行事件：
```java
package io.github.atengk.boot.sample.realtime.handler;

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
     * 处理客户端点对点聊天互动
     */
    @RealtimeAction("chat")
    public void handleChatMessage(RealtimeSession session, RealtimeMessage<Map<String, Object>> message) throws IOException {
        Map<String, Object> data = message.getData();
        log.info("[Sample] 收到客户端聊天消息: fromUser={}, data={}", session.getUserId(), data);

        // 回执当前用户已收到
        RealtimeMessage<String> receipt = RealtimeMessage.of("chat:ack", "Message received from " + session.getUserId());
        session.sendMessage(receipt);

        // 若携带目标用户，则通过 RealtimeTemplate 单播转发
        if (data != null && data.containsKey("targetUser")) {
            String targetUser = String.valueOf(data.get("targetUser"));
            realtimeTemplate.sendToUser(targetUser, RealtimeMessage.of("chat:received", data.get("content")));
        }
    }

    /**
     * 处理客户端加入房间请求
     */
    @RealtimeAction("join-room")
    public void handleJoinRoom(SessionKey sessionKey, Map<String, Object> payload) {
        String roomId = payload != null ? String.valueOf(payload.get("roomId")) : "default-room";
        realtimeTemplate.joinGroup(roomId, sessionKey);
        realtimeTemplate.sendToSession(sessionKey, RealtimeMessage.of("room:joined", "Joined room: " + roomId));
    }
}
```

### 第二板斧：使用 `RealtimeTemplate` 统一执行单播、组播与全服广播
```java
package io.github.atengk.boot.sample.realtime.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/realtime")
@RequiredArgsConstructor
public class RealtimeDemoController {

    private final RealtimeTemplate realtimeTemplate;

    /**
     * 向指定用户单播消息（覆盖该用户所有在线设备与通道）
     */
    @PostMapping("/send-user")
    public Result<Integer> sendToUser(@RequestParam String userId, @RequestParam String message) {
        int count = realtimeTemplate.sendToUser(userId, RealtimeMessage.of("notice:user", message));
        return Result.success(count);
    }

    /**
     * 全服广播公告
     */
    @PostMapping("/broadcast")
    public Result<Integer> broadcast(@RequestParam String message) {
        int count = realtimeTemplate.sendToAll(RealtimeMessage.of("notice:all", message));
        return Result.success(count);
    }

    /**
     * 主动驱逐指定用户全端下线
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

---

## 4. 生产定制与扩展接管 (Customization & Extensibility)

### 4.1 自定义鉴权提供者 SPI (`RealtimeAuthHandshakeProvider`)
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

### 4.2 监听 Spring 原生连接生命周期事件
框架在每个会话握手成功与断开关闭时，均会向 Spring 上下文发布领域事件：
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

### 5.1 高并发写安全防卫 (`TEXT_PARTIAL_WRITING` 终结者)
- **底层隐患**：标准 Java WebSocket（JSR-356）规范明确规定：当一个消息正在异步发送时，严禁并发调用 `sendMessage()`，否则直接抛出 `java.lang.IllegalStateException: The remote endpoint was in state [TEXT_PARTIAL_WRITING] which is an invalid state for called method` 并强行关闭 Socket 连接；
- **防护机制**：`AtengWebSocketHandler` 在握手完成接入底层会话时，自动将其包装为受管的 `ConcurrentWebSocketSessionDecorator`，并设置 64KB 发送缓冲队列。当并发写入冲突时，消息自动进入无锁环形等待队列顺序排空，彻底杜绝并发写异常。

### 5.2 幽灵连接 (Ghost Connections) 60s 心跳探针排空
- **被动保活探测**：客户端发送的任何动作（底层 WebSocket 协议 Pong 帧、文本 Ping 消息或业务 `@RealtimeAction` 交互）均自动被动刷新 `lastActiveTime`；
- **主动周期探测**：后台 `GhostConnectionDetector`（基于 `SmartLifecycle` 托管生命周期）每隔 25s 自动向当前节点的所有 WebSocket 会话下发物理 Ping 协议帧，并向 SSE 连接发送 `:keepalive\n\n` 注释；
- **死链确定性排空**：若连接空闲超过 `idleTimeout`（默认 60s），探针执行确定性安全排空，以 `RealtimeCloseStatus.IDLE_TIMEOUT (4004)` 关闭物理句柄并排空 `RealtimeSessionManager` 与 `RealtimeGroupManager` 索引。

### 5.3 严格对称身份防卫（非法 Token 拒绝降级）
- 即使全局配置了 `allow-anonymous: true`，只要客户端在请求中显式携带了 `token` 参数或请求头，一旦校验失败（Token 过期、伪造或无效），服务端 WebSocket 与 SSE 端点均统一**硬性响应 401 握手拒绝**，坚决杜绝非法 Token 静默伪装成匿名访客穿透安全屏障。

### 5.4 RFC 6455 生产扩展关闭状态码契约
遵循 RFC 6455 规范，4000-4999 区间专由应用层定义：

| 状态码 | 常量定义 | 业务场景语义 | 客户端处理与重连建议 |
| :--- | :--- | :--- | :--- |
| **1000** | `NORMAL` | 正常自愿关闭连接 | 正常结束，无需重连 |
| **1012** | `SERVICE_RESTART` | 服务端优雅停机或应用重启 | **必须重连**：使用带随机抖动（Jitter）的指数退避重连，严防瞬间雪崩 |
| **4001** | `AUTH_FAILED` | Token 过期、无效或鉴权失败 | **严禁自动重连**：应引导用户重新登录或刷新 Token 凭据 |
| **4002** | `USER_KICKED_OUT` | 用户被管理员踢下线或单端登录被挤出 | **严禁自动重连**：弹出被踢提示并注销本地会话 |
| **4003** | `MAX_CONNECTIONS_EXCEEDED` | 单用户并发连接数超过配额上限 | **严禁重连**：提示用户关闭其他多余标签页或设备 |
| **4004** | `IDLE_TIMEOUT` | 连接假死或心跳无动静超过 60s 超时 | **允许重连**：延迟数秒后重新发起握手 |
