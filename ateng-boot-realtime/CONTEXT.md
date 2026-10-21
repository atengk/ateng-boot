# Realtime Context

`ateng-boot-realtime` 负责提供全双工交互（WebSocket）与轻量长连接流（SSE）双规合一的现代化实时通信底座，支持多端多 Tab 精准路由、单机极速自洽及 Redis Pub/Sub 跨节点集群自动升级。

## Language

**RealtimeTemplate**:
实时通信统一业务推送门面具体类，提供自适应多通道智能路由、按用户/按租户/全员广播、协议定向单播、组播、强制踢人及在线度量能力。
_Avoid_: WebSocketTemplate, SseTemplate, PushUtil, RealtimeService

**RealtimeSession**:
跨传输协议的受管统一会话抽象，封装底层物理连接（WebSocketSession 或 SseEmitter）、通道类型、租户标识、最后活跃时间及会话唯一坐标。
_Avoid_: UserSession, ClientConnection, SocketSession, ConnectionHolder

**SessionKey**:
用户端点会话唯一路由标识对象，由用户标识（userId）、设备类型（deviceType）与客户端会话标识（clientId）构成的确定性三维坐标。
_Avoid_: SessionId, ConnectionId, ChannelKey

**RealtimeMessage**:
跨通道统一数据下发强类型契约载荷，规范化封装消息唯一编号（id）、业务事件名（event）、泛型数据（data）、发送时间戳（timestamp）与四维 TraceId（traceId）。
_Avoid_: SocketMessage, WsPayload, EventMessage, PushData

**ChannelType**:
实时通信底层物理通道协议类型枚举，明确定义为 WEBSOCKET（全双工）与 SSE（单向流）。
_Avoid_: ProtocolType, ConnType, SocketType

**RealtimeAuthHandshakeProvider**:
握手连接身份认证与凭据解析 SPI 扩展点，按优先级自适应解析 Query 参数、HTTP Header 及 Cookie，并将认证结果映射绑定至会话。
_Avoid_: HandshakeInterceptor, AuthProvider, TokenParser

**RealtimeClusterBroadcaster**:
跨节点分布式集群广播门面组件，单机模式下内存自洽，探测到 RedissonClient 时自动无缝升级为 Redis Pub/Sub 广播并附带源节点防反射过滤。
_Avoid_: MessageBroadcaster, ClusterManager, RedisBroadcaster

**GhostConnectionDetector**:
幽灵连接与死链安全排空探针组件，按周期发起协议帧/JSON 双模心跳探测与 SSE 保活注释推流，超时无应答时强制安全关闭并释放物理连接句柄。
_Avoid_: HeartbeatScheduler, IdleChecker, PingPongTask

**RealtimeAction**:
声明式客户端上行消息业务处理动作注解，绑定特定业务事件标识（event），由分发引擎自动路由调用对应 Spring Bean 业务方法。
_Avoid_: MessageMapping, SocketAction, OnMessage, ActionHandler

**RealtimeMessageDispatcher**:
客户端上行消息统一分发路由调度引擎，优先匹配声明式动作注解方法，缺省回退至函数式监听器。
_Avoid_: MessageRouter, ActionDispatcher, EventRouter

**RealtimeMessageListener**:
客户端上行原始消息编程式消费监听 SPI 扩展点，面向底层透传或无特定注解绑定的消息消费。
_Avoid_: SocketListener, MessageReceiver, WebSocketHandler

**RealtimeConnectedEvent**:
客户端成功建立物理连接并通过握手认证后发布的 Spring 原生应用事件，携带会话详情与用户信息。
_Avoid_: ConnectionOpenEvent, OnlineEvent, SessionCreatedEvent

**RealtimeDisconnectedEvent**:
客户端物理连接断开、网络异常闪断或被服务端主动安全排空后发布的 Spring 原生应用事件。
_Avoid_: ConnectionCloseEvent, OfflineEvent, SessionDestroyedEvent

**RealtimeOfflineMessageProvider**:
用户离线未读消息暂存与补偿重放 SPI 扩展点，默认提供无操作丢弃实现，允许下游扩展 Redis 或数据库存储。
_Avoid_: OfflineStore, MessageQueueStore, OfflineBuffer

**SseStreamHelper**:
面向单次 HTTP 请求的轻量流式推流助手组件，提供大模型 Token 打字机输出、生命周期回调及客户端主动中止静默捕获。
_Avoid_: SseHelper, StreamUtil, SseFactory

**RealtimeGroupManager**:
客户端会话分组与频道订阅受管注册表组件，提供多端会话加入、退出与组播映射的确定性维护。
_Avoid_: RoomManager, ChannelManager, GroupService

**RealtimeCloseStatus**:
RFC 6455 标准扩展关闭状态码契约常量类，统一定义 4001（鉴权失败）、4002（被踢下线）、4003（连接配额超限）与 1012（服务优雅停机排空）语义。
_Avoid_: WsStatus, CloseCode, DisconnectReason

**RealtimeGracefulShutdownHandler**:
实时底座优雅停机受管处理器，接入 Spring SmartLifecycle 体系，执行拒绝新连接握手、广播 1012 状态码与存量发送缓冲队列安全排空。
_Avoid_: ShutdownManager, RealtimeCloser, WsShutdown
