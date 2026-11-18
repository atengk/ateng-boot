# MQTT 物联网通信与边缘控制底座

基于 Eclipse Paho MQTT v5 官方客户端构建的工业物联网与车联网双向轻量通信基础设施，提供声明式监听、QoS 保证、四维链路穿透、共享订阅与断线重连治理。

## Language

**MQTT Template (MQTT 门面)**:
提供发布、持久保留、请求-响应与本地事务协同对称调用的高内聚业务操作门面。
_Avoid_: MqttHelper, MqttService, MqttClientWrapper

**MQTT Listener (声明式监听器)**:
基于方法注解声明订阅主题、QoS 等级、多客户端绑定与共享消费组的事件驱动容器。
_Avoid_: MqttConsumer, MqttSubscriber, MqttHandler

**Shared Subscription (共享订阅)**:
通过特定组前缀实现集群多实例负载均衡消费且单报文仅处理一次的消费拓扑。
_Avoid_: 集群订阅, 组订阅, 负载订阅

**User Properties (用户属性)**:
MQTT 5.0 协议原生支持的用于透明传递全链路追踪 TraceId 与多租户上下文的键值标头。
_Avoid_: 消息头, 扩展属性, 载荷头

**Request-Response Pattern (请求-响应模式)**:
利用 Response Topic 与 Correlation Data 实现平台指令下发与设备应答闭环匹配的交互范式。
_Avoid_: MQTT RPC, 双向回执, 伪同步调用

**Named MQTT Client (命名客户端)**:
在多 Broker 或多工业园区拓扑下通过唯一逻辑名称隔离配置与连接生命周期的受管客户端。
_Avoid_: 动态数据源, 多连接实例, 多数据通道

**Dead Letter Topic (死信主题)**:
当消费端发生不可自愈毒丸异常或重试超限时自动将原始载荷与错误堆栈转移隔离的目标主题。
_Avoid_: 错误队列, 失败主题, 垃圾主题

**Last Will and Testament (遗嘱消息)**:
客户端非正常断开或意外宕机时由 Broker 代为自动向指定监控主题广播的告警报文。
_Avoid_: 掉线通知, 遗言消息, 离线告警

**Anti-Stolen ClientId Strategy (防互踢标识策略)**:
通过动态后缀生成防御微服务多 Pod 并发启动因客户端标识冲突导致连接互相踢下线的策略。
_Avoid_: 动态ID, 客户端哈希, 实例标识

**Silent ACK (静默确认)**:
捕获幂等冲突异常后直接向 Broker 提交确认以阻断 QoS 1 重复报文无限重推的风暴防卫机制。
_Avoid_: 假确认, 异常忽略, 静默丢弃

**Message Converter (消息转换器)**:
负责原始协议载荷（JSON、原始字节流、十六进制 HEX、Protobuf）与业务强类型参数双向转译的 SPI 规范。
_Avoid_: 序列化器, 数据转译器, 解码器

**Payload Guard (载荷安全守卫)**:
基于强类型尺寸阈值（`maxPayloadSize`）在上行反序列化前对大报文实施快速熔断以防御 JVM 堆内存溢出的防卫机制。
_Avoid_: 报文限流, 尺寸拦截, 内存熔断

**Dead Letter Replay Template (死信重放门面)**:
提供死信报文只读探查、清洗原死信标头、注入重放追踪元数据并受控重新投递的专用治理门面，具备防二次死信雪崩防线。
_Avoid_: 死信搬运工, 错误重推器, 异常恢复工具

**Batch Publish Record (批量发布记录)**:
封装目标主题、业务载荷、QoS、保留标志、用户属性与过期时长的强类型原子发布描述模型。
_Avoid_: 批量消息体, 多发包, 聚合参数

**MQTT Container Manager (MQTT 容器管理器)**:
负责在运行期动态监控、暂停、恢复声明式监听端点并协调断网重连自愈订阅的受管运维门面。
_Avoid_: 监听管理器, 消费者控制器, 订阅句柄

**Message Expiry Interval (消息过期时长)**:
MQTT 5.0 协议原生属性，定义报文在 Broker 离线队列中驻留的最长生存时间，超期自动物理剔除以防过期指令误执行。
_Avoid_: 消息TTL, 超时时间, 存活周期

**Topic Alias (主题别名)**:
MQTT 5.0 协议原生支持的在蜂窝物联网高频通信中通过双字节数值别名替换冗长主题字符串以极致压缩流量开销的优化机制。
_Avoid_: 主题映射, 简短主题, 编号主题

**Connection Lifecycle Event (连接生命周期事件)**:
客户端发生连接建立、断开、网络掉线时由底座广播的 Spring 原生领域事件体系。
_Avoid_: 状态回调, 物理钩子, 连通通知

**Message Interceptor (消息拦截器)**:
在报文发布与消费生命周期前后执行统一横切逻辑（审计打点、加解密、动态脱敏）的扩展点 SPI。
_Avoid_: 消息过滤器, 传输切面, 管道钩子

**Dead Letter Store (死信存储)**:
负责持久化或有界缓存已转移的死信记录以支撑可靠只读探查、受控重放与确定性清空的可插拔存储 SPI，规避 Broker 保留消息单槽覆盖丢信问题。
_Avoid_: 死信缓存池, 异常记录库

**Offline Buffer Policy (断线缓冲策略)**:
决定客户端在断线重连期间对出站发布请求采取本地离线缓冲补发还是快速失败的治理策略。
_Avoid_: 断网队列策略, 离线降级开关

**Will Delay Interval (遗嘱延迟间隔)**:
MQTT 5.0 协议原生属性，在客户端异常掉线后延迟指定时长再由 Broker 广播遗嘱消息，从而屏蔽短暂网络抖动触发的虚假离线告警。
_Avoid_: 遗嘱冷却期, 掉线防抖时间

**Subscription Options (订阅选项)**:
MQTT 5.0 协议原生的主题订阅控制参数组合，涵盖防回环 `noLocal`、保留标志透传 `retainAsPublished` 与初始保留报文投递策略 `retainHandling`。
_Avoid_: 订阅标志位, 消费过滤开关

**Credentials Provider (动态凭据供给器)**:
在客户端建连及断线重连阶段按需动态提供或刷新认证凭据（如 IoT 设备签名或短效 JWT 令牌）的扩展点 SPI。
_Avoid_: 静态密码刷新器, 鉴权回调

**Ack Mode (消费确认模式)**:
控制监听器何时向 Broker 提交消费确认（`AUTO_AFTER_CONSUME` 消费及重试闭环后自动确认，`MANUAL` 交由业务显式触发）的可靠性契约。
_Avoid_: 提交策略, 自动应答开关

**MQTT Acknowledgment (手动确认句柄)**:
在 `MANUAL` 确认模式下注入监听方法形参的显式确认控制契约，提供 `ack`、`nackToDlt` 与 `abandonWithoutAck` 三态语义。
_Avoid_: 消息回执器, 手动提交器

**Declarative Reply (声明式应答)**:
在请求-响应模式下，监听方法通过非空返回值（含异步 `CompletableFuture`）或可选 `@MqttReply` 自动向入站 `ResponseTopic` 回发携带 `CorrelationData` 应答的交互范式。
_Avoid_: 注解回传, 自动回调发送

**Consumer Retry Policy (消费退避重试策略)**:
针对监听端可恢复瞬态异常执行受管非阻塞指数退避重试，并在耗尽后转入死信或按策略确认的容错契约。
_Avoid_: 无限重推, 死循环重试

**Topic Pattern Cardinality Guard (主题标签基数防线)**:
在度量采集中强制使用有限基数声明式主题模板并默认屏蔽高基数物理主题标签的防 OOM 治理机制。
_Avoid_: 指标过滤开关, 标签裁剪器

**Client-Scoped Template (客户端绑定视图)**:
通过 `MqttTemplate.use(clientName)` 派生的不可变命名客户端操作视图，显式锁定目标 Broker 路由且优先级高于线程级上下文绑定。
_Avoid_: MqttClientWrapper, MqttClientTemplate, 客户端代理类

**Dynamic Client Lifecycle (动态客户端热插拔)**:
基于 `NamedMqttClientRegistry` 在运行期安全注册、替换与注销命名客户端并联动解除订阅与清理度量资源的对称治理能力。
_Avoid_: 客户端池热重载, 临时连接池






