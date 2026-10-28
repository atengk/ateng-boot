# RabbitMQ Context

`ateng-boot-rabbitmq` 负责提供基于 Spring AMQP 官方起步基线的工业级消息通信与可靠投递底座，支持双重确认保障（Publisher Confirms/Returns）、事务感知延迟发信（sendAfterCommit + 补偿 SPI）、泛型推断反序列化解耦、消费端幂等静默确认、毒丸快速隔离、死信终点不变量强校验及声明式流式拓扑构建。

## Language

**RabbitReliableTemplate**:
RabbitMQ 生产端高可靠投递与事务感知增强门面，提供同步等待 Broker 确认的 `sendReliable`、异步非阻塞 `sendReliableAsync`、延迟投递 `sendDelayed`、本地事务提交感知发信 `sendAfterCommit`，内置四维上下文自动注入、大报文超限 Fail-Fast 与透明 GZIP 压缩。
_Avoid_: RabbitSender, MqProducer, ReliableAmqpTemplate, RabbitHelper

**RabbitTopologyBuilder**:
RabbitMQ 声明式流式拓扑构建器（Fluent API），用于链式声明 Direct、Topic、Fanout 及 Delayed 插件交换机与队列路由，原生支持 Raft Quorum 仲裁队列（`.quorum()`）、单活独占消费者（`.singleActiveConsumer()`）、死信终点不变量强校验的死信拓扑（`.deadLetter(...)`）及纯原生 TTL+DLX 双模延迟队列，返回 Spring 原生 `Declarables` 集合。
_Avoid_: AmqpTopologyHelper, QueueExchangeBuilder, MqTopologyManager

**RabbitDeadLetterHelper**:
RabbitMQ 死信元数据只读诊断助手工具类，从 AMQP 消息属性复杂的嵌套 `x-death` 头中安全提取原死信队列名、原交换机、死信原因（`rejected` / `expired` / `maxlen`）与流转计数值，宽容兼容 String、byte[] 与 LongString 等底层异构驱动类型。
_Avoid_: DeadLetterUtil, XDeathExtractor, DlqDiagnosticHelper

**RabbitListenerGlobalAdvice**:
消费端全局统一环绕拦截通知链，位于消费容器最外层，负责在调用业务消费方法前激活四维消息上下文（TraceId、TenantId、UserId）与 MDC，耗时度量，并在捕获 `IdempotentException` 时执行静默确认（Silent ACK）阻断重试风暴，在 `finally` 块中执行确定性现场清理。
_Avoid_: ConsumeAspect, RabbitInterceptor, MqConsumeAdvice

**RabbitFatalExceptionStrategy**:
容器级致命异常与毒丸分类判定策略，精确识别反序列化失败、JSR-303 参数校验违规以及业务断言失败等不可自愈异常，阻断无脑 requeue 重试死循环，快速将毒丸消息路由至死信队列（DLQ）。
_Avoid_: ErrorClassifier, PoisonPillHandler, FatalDetector

**RabbitRetryPolicy**:
容器内本地指数退避重试策略，在消费容器内存中执行受控重试（默认 3 次），抗击瞬态网络抖动，与底层 Broker Requeue 严格解耦，彻底避免消费端死循环自旋。
_Avoid_: MqRetryPolicy, RabbitBackoffRule

**RabbitUnroutableMessageCallback**:
不可路由退回消息处理 SPI 扩展点，当发送端将消息投递至 Exchange 但未命中任何绑定队列（Broker 触发 Basic.Return）时，经由全局 `ReturnsCallback` 回调此扩展点，供业务扩展告警推送或数据落库补偿。
_Avoid_: ReturnCallbackSpi, UnroutableHandler, DeadMessageListener

**RabbitTransactionFailureCallback**:
本地事务提交后发信失败兜底补偿 SPI 扩展点，当 `sendAfterCommit` 在数据库事务物理 Commit 成功后因网络抖动导致发信失败时被触发，供业务落库本地消息表或触发死信补偿告警，杜绝发信丢失。
_Avoid_: TxCompensationSpi, PostCommitFailureListener, RollbackMessageCallback

**RabbitmqProperties**:
RabbitMQ 增强底座强类型配置属性类，纳管于 `ateng.boot.rabbitmq.*`，严格采用 `Duration` 与 `DataSize` 消除单位歧义，提供生产级合理默认值与配置元数据。
_Avoid_: AmqpConfig, MqSettings, RabbitProperties

**AtengRabbitmqAutoConfiguration**:
RabbitMQ 增强底座自动装配核心类，统一注册于 Spring Boot 3 标准 `AutoConfiguration.imports`，装配开启 INFERRED 泛型推断的 Jackson 消息转换器、RabbitTemplateCustomizer、优雅停机排空及生命周期后置增强。
_Avoid_: RabbitConfiguration, AmqpConfigRegistry
