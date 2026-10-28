# RocketMQ Context

`ateng-boot-rocketmq` 负责提供基于 RocketMQ 5.x 官方新一代 gRPC 瘦客户端的生产增强与原生分布式事务底座，支持受管高阶门面 RocketmqTemplate、延时调度与 FIFO 保序投递、原生 2PC 分布式事务半消息与统一回查分发架构 RocketmqTransactionChecker、本地事务提交感知发信（sendAfterCommit + 补偿 SPI）、声明式 @RocketmqListener 事件驱动容器、微服务推断反序列化解耦、大报文 4MB 前置防御与 4KB 透明 GZIP 压缩、消费端毒丸丢弃与 @Idempotent 幂等静默确认、SmartLifecycle 确定性停机排空与 100% 离线自洽 TestDouble 测试沙箱。

## Language

**RocketmqTemplate**:
RocketMQ 5.x 生产端高阶增强门面，提供同步发送 `send`、异步非阻塞发送 `sendAsync`、毫秒级延时发送 `sendDelayed` 与 `sendDeliverAt`、FIFO 顺序发送 `sendOrderly`、原生 2PC 事务半消息 `sendTransaction`、本地事务提交感知发信 `sendAfterCommit`，内置四维上下文自动注入、原生 `keys` 与 `X-Biz-Key` 双写、4MB 大报文 Fail-Fast 拦截与 4KB 透明 GZIP 压缩，底层原生 `Producer` 作为可选 Bean 暴露。
_Avoid_: RocketmqSender, RocketmqProducer, RocketmqHelper, SimpleRocketmqTemplate

**RocketmqTransactionChecker**:
RocketMQ 5.x 全局统一事务回查分发器，作为原生 `Producer` 的全局唯一 Checker，扫描并管理所有实现了 `RocketmqLocalTransactionChecker` SPI 的 Bean，当 Broker 触发状态回查时，依据消息 Topic 策略路由分发至对应的业务检查器，彻底解耦微服务多业务事务回查。
_Avoid_: TransactionManager, RocketmqCheckManager, MonolithicTransactionChecker

**RocketmqLocalTransactionChecker**:
本地事务状态回查可插拔 SPI 扩展点，由业务领域组件实现并声明关注的 Topic，当对应半消息发生超时未决时供框架回调 `checkLocalTransaction` 并返回 `TransactionResolution`（COMMIT / ROLLBACK / UNKNOWN）。
_Avoid_: TxChecker, TransactionListener, RocketmqCheckerCallback

**RocketmqListener**:
RocketMQ 5.x 声明式监听器注解，用于标注在 Spring 受管 Bean 的消费方法上，声明 `topic`、`consumerGroup`、`tag`（默认 `*`）与 `sql92` 过滤表达式，底层自动组装受管 `PushConsumer` 实例，内聚四维上下文激活、微服务推断反序列化、泛型集合解析、毒丸致命异常受控丢弃与 `@Idempotent` 幂等冲突静默确认。
_Avoid_: RocketListener, MqListener, RocketmqConsumer, RocketMessageListener

**RocketmqTransactionFailureCallback**:
本地事务提交后发信失败兜底补偿 SPI 扩展点，当 `sendAfterCommit` 在本地数据库事务物理 Commit 成功后因网络抖动或 Broker 故障导致发信失败时被触发，供业务落库本地补偿表或触发告警，杜绝发信静默丢失。
_Avoid_: TxFailureListener, RocketmqRollbackHandler, PostCommitFailureCallback

**AtengRocketmqProperties**:
RocketMQ 增强底座强类型配置属性类，纳管于 `ateng.boot.rocketmq.*`，严格采用 `Duration` 与 `DataSize` 消除单位歧义，提供生产级合理默认值与 IDE 补全元数据。
_Avoid_: RocketmqConfig, RocketmqSettings, AtengRocketmqConfig

**AtengRocketmqAutoConfiguration**:
RocketMQ 增强底座自动装配核心类，统一注册于 Spring Boot 3 标准 `AutoConfiguration.imports`，配置缺失 `endpoints` 时平滑静默跳过，装配 `RocketmqTemplate`、原生 `Producer`、全局事务回查分发器与 `@RocketmqListener` 容器注册器。
_Avoid_: RocketmqConfiguration, RocketmqConfigRegistry, RocketmqStarterAutoConfiguration
