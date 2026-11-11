# TCP 工业级网络通信底座

基于 Netty 4.1.x 构建的面向工业物联网、智能硬件网关与企业长连接交互的双模网络通信基础设施，提供服务/客户双模协同、自适应粘包拆包编解码、会话多维绑定、空闲保活检测、双轨消息路由与受管线程池隔离调度。

## Language

**TCP Server Template (TCP 服务端门面)**:
面向服务端业务场景，提供单播（指定会话或设备）、全局广播、分组组播与同步等待应答的对称操作门面（`TcpServerTemplate`）。
_Avoid_: TcpServerHelper, SocketServerUtil, NettyServerService

**TCP Client Template (TCP 客户端门面)**:
面向客户端主动发信、长连接状态监控、连接池化管理与请求-响应（Request-Response）同步等待调用的对称操作门面（`TcpClientTemplate`）。
_Avoid_: TcpClientHelper, SocketClientUtil, NettyClientManager

**TCP Session (TCP 会话)**:
统一封装底层 Netty Channel、唯一会话标识（`sessionId`）、通道属性上下文（`AttributeMap`）、创建与最近活跃时间戳的领域会话契约。
_Avoid_: ChannelContext, SocketWrapper, NettyChannelHolder

**TCP Session Manager (TCP 会话管理器)**:
专职负责 TCP 会话的注册、注销、生命周期维护，以及业务设备标识（`deviceId`）与会话多维双向映射绑定的管理底座。
_Avoid_: ConnectionManager, SessionRegistry, ChannelMap

**TCP Listener (声明式 TCP 监听器)**:
基于方法注解声明式监听特定业务命令字（Command）、自动执行载荷反序列化与线程池异步派发的消费契约（`@TcpListener` / `@TcpMapping`）。
_Avoid_: SocketListener, TcpConsumer, ChannelConsumer

**TCP Message Handler (TCP 消息处理器 SPI)**:
面向复杂工业协议解析、高性能流水线或全报文原始流拦截的高性能函数式处理接口（`TcpMessageHandler<T>`）。
_Avoid_: PacketProcessor, ChannelHandler, InboundInterceptor

**Dual-Mode Architecture (双模网络架构)**:
TCP Server 与 TCP Client 拥有独立装配开关、配置命名空间与生命周期管理的协同运行架构。
_Avoid_: 混部网络, 通用Socket, 客户端服务端融合

**TCP Packet (TCP 帧报文模型)**:
封装业务命令字（`command`）、关联追踪流水号（`requestId`）、扩展路由头与底层 `TcpPayload` 的完整结构化通信帧模型，专职用于协议路由分发（`TcpDispatcher`）与请求-响应 RPC 相关性匹配（`TcpPacket`）。
_Avoid_: NettyFrame, RawBuffer, ByteBufMessage, PayloadWrapper

**TCP Payload (TCP 载荷载体)**:
聚焦单帧原始字节数据的安全堆内存封装，在底层完成堆外 `ByteBuf` 确定性释放后向上提供 `byte[]`、十六进制 `Hex`、`String` 及基于 `JsonUtil` 的强类型对象解码读写原语（`TcpPayload`），严禁将底层直接内存缓冲区透传至业务层。
_Avoid_: RawByteBuf, PacketHeaderEnvelope, TcpCodec, FrameEnvelope

**TCP Framing Strategy (数据帧切片策略)**:
解决流式 TCP 粘包拆包的数据边界切片机制，支持分隔符（Delimiter）、固定长度（FixedLength）与长度字段偏移（LengthField）。
_Avoid_: 拆包器, 粘包处理器, 报文分片

**TCP Route Key Extractor (路由键提取器 SPI)**:
从入站数据帧或报文中动态提取业务命令字（Command）以驱动 `@TcpMapping` 与处理器路由匹配的提取策略契约。
_Avoid_: CommandParser, ActionResolver, OpCodeFinder

**Kick-out Strategy (踢旧留新策略)**:
当已绑定的设备标识（`deviceId`）发起新连接时，主动关闭并回收旧通道会话以防御网络假死和幽灵连接的置换治理机制。
_Avoid_: 强制下线, 顶号处理, 连接覆盖

**Request-Response Correlation (请求-响应关联匹配)**:
基于唯一请求关联号（`requestId`）与有界 Future 映射池，在全双工 TCP 通道中实现异步回包精准唤醒调用方的闭环协同范式。
_Avoid_: 伪同步, 同步等待锁, 轮询回执

**Named TCP Client (命名 TCP 客户端)**:
在多目标外部服务拓扑中，通过唯一逻辑名称隔离配置、网络通道与生命周期，并由 `TcpClientRegistry` 统一检索调用（`getRequiredClient` / `getRequiredTemplate`）的受管客户端实例（`clients.<name>`）。
_Avoid_: 动态数据源, 多连接通道, 客户端池, TcpClientTemplate.use(), getClientRegistry()

**Graceful Draining (平滑停机排空)**:
在 Spring 容器生命周期停止（`SmartLifecycle`）阶段，按序执行拒绝新接入、等待在途请求处理完成、平滑注销存量连接并释放 EventLoop 线程资源的停机机制。
_Avoid_: 停机钩子, 暴力关闭, 强制断链

**Frame Length Guard (帧长度安全守卫)**:
基于强类型尺寸阈值（`maxFrameLength`）在数据帧切片阶段对超大畸形报文实施快速熔断并主动断链，以防御堆外内存溢出的防卫机制。
_Avoid_: 报文限流, 尺寸拦截, 内存切断

**Connection Capacity Guard (连接容量守卫)**:
服务端配置最大在线并发连接数（`maxConnections`），超过阈值在握手阶段快速拒绝并关闭通道，以防御操作系统文件句柄（FD）耗尽的过载保护机制。
_Avoid_: 连接限流, 握手拦截, 端口防御

**Mutual TLS / mTLS (双向证书认证)**:
基于 Netty SslContext 构建的服务端与客户端双向非对称证书校验机制，用于严苛金融与国标硬件接入的安全加密通道。
_Avoid_: 双向SSL, 客户端密钥认证, 强加密握手

**TCP Server Registry (TCP 多端口服务端注册表)**:
在同一服务实例内同时纳管主端口（`default`）与命名多端口（`servers.<name>`），支持各自独立装配分帧规则、空闲超时与 SSL/TLS 证书并由 `TcpServerRegistry`（`getRequiredServer` / `getDefaultServer`）统一调度生命周期的多端口多协议服务端拓扑。
_Avoid_: 多网卡监听, 端口多路复用, 混部服务

**Heartbeat Keep-Alive (心跳保活与空闲检测)**:
服务端基于 Netty `IdleStateHandler` 与 `TcpIdleProperties`（`reader-idle-time` / `writer-idle-time` / `all-idle-time`）监测读写空闲并触发 `TcpSessionIdleEvent`，客户端则依据 `TcpHeartbeatProperties` 定时发送主动保活 Ping 帧以协同维持长连接活性并剔除假死会话。
_Avoid_: reader-idle, writer-idle, all-idle, 盲发心跳, 死循环保活

**Exponential Backoff Reconnect (指数退避自愈重连)**:
客户端在链路意外断开后依据 `TcpReconnectProperties`（`initialInterval`、`multiplier`、`maxInterval`、`maxAttempts`）按指数退避调度后台自愈重连，并在手动同步重连入口依托 `RetryUtil` 执行有界退避重试，杜绝网络闪断期间的并发重连风暴。
_Avoid_: 无限死循环重连, 固定死等待重连, 野线程重连

**Cluster Push Bridge (集群跨节点推送桥接 SPI)**:
在微服务集群多 Pod 拓扑下，通过 Redis Pub/Sub 实现跨节点设备寻址与下行指令去中心化广播透传的自适应桥接规范（`TcpClusterBridge`）。
_Avoid_: 集群转发, 跨节点广播, 会话共享器

**TCP Domain Events (TCP 领域事件)**:
统一在 Spring ApplicationContext 发布的承载会话连接（Connected）、断开（Closed，附带注销原因）、设备绑定（Bound）及空闲超时（Idle）的状态变迁事件总线。
_Avoid_: 网络监听器, 连接钩子, 通道回调

**TCP Packet Filter (报文过滤器 SPI)**:
位于数据帧切片之后、业务分发之前的入站与出站报文拦截链，专职负责工业协议转义清洗、校验和（XOR/CRC）比对与格式验真（`TcpPacketFilter`）。
_Avoid_: 报文拦截器, 协议切面, 通道处理器

**Checksum Validator (工业级校验和验证器)**:
封装工业现场高频使用的异或校验（XOR）、CRC16-Modbus、CRC16-CCITT 与 CRC32 的快速计算与一致性比对原语（`ChecksumUtil`）。
_Avoid_: 校验计算器, 哈希验证器, 数据验签器

**Backpressure Guard (背压流控守卫)**:
当底层 Netty 通道写缓冲区积压触达高水位时，主动检测通道可写状态并按 `BackpressurePolicy`（支持经 `TcpSession.setBackpressurePolicy` 动态调优）拦截或限流非阻塞推流以防御 JVM 直接内存与堆内存 OOM 的自愈流控机制。
_Avoid_: 内存阻断器, 发送限速, 强制等待

**Write Buffer Water Mark (写缓冲水位守卫)**:
配置底层网络通道高低水位阈值（`writeBufferLowWaterMark` / `writeBufferHighWaterMark`）以触发通道可写状态跃迁的核心度量边界。
_Avoid_: 缓冲区边界, 阀值参数, 水位限制

**IP Firewall (IP 访问控制防火墙)**:
在连接握手建立的首个网络层阶段，基于黑白名单与 CIDR 掩码规则快速拦截未授权终端并立即切断释放物理连接的安全防线。
_Avoid_: 黑白名单过滤器, IP拦截切面, 端口守卫

**Traffic Shaping (网络流量整形)**:
在长连接通信中基于令牌桶算法对全局或单连接的读写吞吐速率实施平滑带宽削峰填谷与超限挂起的流量治理机制。
_Avoid_: 带宽限流器, 字节限速, 传输节流阀

**Cluster Group Multicast (集群分组组播)**:
在分布式多节点拓扑下，通过跨节点消息总线向指定业务设备分组内所有在线终端执行去中心化精准分发的消息投递契约。
_Avoid_: 集群分发, 分组广播, 跨节点批量推送

**Industrial Protocol Codec (工业协议复合编解码模式)**:
基于 `ateng-boot-tcp` 内置原语（配置分帧 + `TcpPacketFilter` + `ChecksumUtil` + `TcpPayload` + `BinaryOffsetRouteKeyExtractor`）按业务协议拼装而成的组合应用模式，并非框架内置黑盒实现；具体国标协议实现（如 `ateng-boot-sample-tcp` 中的 JT/T 808 示例组件）归属业务或样例工程演进。
_Avoid_: TcpCodec, 内置国标协议黑盒, 私有协议解析器, 工业解码切面

