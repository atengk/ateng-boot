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

**TCP Packet (TCP 数据包 / 帧报文模型)**:
承载业务命令字（`command`）、关联追踪流水号（`requestId`）与业务数据载荷（`payload`）的统一通信实体。
_Avoid_: NettyFrame, RawBuffer, ByteBufMessage

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
在多目标外部服务拓扑中，通过唯一名称隔离配置、网络通道与生命周期的受管客户端实例（`TcpClientRegistry`）。
_Avoid_: 动态数据源, 多连接通道, 客户端池

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

**Multi-Port Server (多端口多协议服务拓扑)**:
在同一服务实例内同时监听多个不同网络端口、独立装配不同分帧规则与编解码流水线的受管多服务拓扑（`servers.<name>`）。
_Avoid_: 多网卡监听, 端口多路复用, 混部服务

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
