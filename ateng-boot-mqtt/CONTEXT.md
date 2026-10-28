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


