# RocketMQ Context

`ateng-boot-rocketmq` 负责提供基于 RocketMQ 5.x 官方新一代 gRPC 瘦客户端的生产增强与原生分布式事务底座，支持受管高阶门面 RocketmqTemplate、单向极速投递与批量自动切片可靠发信、延时调度与定时消息精准撤回、FIFO 保序投递、原生 2PC 分布式事务半消息与统一回查分发架构 RocketmqTransactionChecker、单条与批量本地事务提交感知发信（sendAfterCommit / sendBatchAfterCommit + 补偿 SPI）、声明式 @RocketmqListener 事件驱动容器、SimpleConsumer 租约拉取与流式处理门面 RocketmqSimpleConsumerTemplate、运行期容器动态运维 RocketmqContainerManager 与不可变状态快照 RocketmqContainerStatus、死信队列结构化诊断与受控重放 RocketmqDeadLetterReplayTemplate、消费端毒丸归档与回调 RocketmqPoisonPillCallback、微服务推断反序列化解耦、大报文 4MB 前置防御与 4KB 透明 GZIP 压缩、@Idempotent 幂等静默确认、全链路 ReentrantLock 防虚拟线程 Pinning、SmartLifecycle 确定性停机排空与 100% 离线自洽 TestDouble 测试沙箱。

## Language

**RocketmqTemplate**:
RocketMQ 5.x 生产端高阶增强门面，提供同步发送 `send`、异步非阻塞发送 `sendAsync`、单向极速投递 `sendOneway`、基于 `BatchSliceUtil` 自动分片并发发送 `sendBatch` / `sendBatchAsync`、毫秒级延时发送 `sendDelayed` 与 `sendDeliverAt`（支持自定义属性与异步重载）、定时消息精准撤回 `recallMessage` / `recallMessageAsync`、FIFO 顺序发送 `sendOrderly`、原生 2PC 事务半消息 `sendTransaction`、单条与批量本地事务提交感知发信 `sendAfterCommit` / `sendBatchAfterCommit`（内聚注册时刻四维上下文快照固化），内置原生 `keys` 与 `X-Biz-Key` 双写、4MB 大报文 Fail-Fast 拦截与 4KB 透明 GZIP 压缩，底层原生 `Producer` 作为可选 Bean 暴露。
_Avoid_: RocketmqSender, RocketmqProducer, RocketmqHelper, SimpleRocketmqTemplate

**RocketmqSimpleConsumerTemplate**:
RocketMQ 5.x 消费端流式拉取与租约控制高阶门面，实现 `SmartLifecycle` 确定性停机排空，基于官方 `SimpleConsumer` 提供按需批量拉取 `receive`、主动确认 `ack`、不可见租约动态续期 `changeInvisibleDuration` 与高阶函数式闭包 `receiveAndExecute`，内聚动态 `FilterExpression` 订阅刷新、推断反序列化、四维上下文还原、单条异常容错隔离（含 `IdempotentException` 静默确认）与受管租约自动续期守护（Lease Renewal Watchdog）。
_Avoid_: RocketmqPullConsumer, SimpleConsumerHelper, RocketmqPopTemplate

**RocketmqContainerManager**:
RocketMQ 5.x 运行期消费容器动态运维与生命周期管理门面，管理由 `@RocketmqListener` 注册的所有活动端点，提供不可变状态快照查询 `getContainerStatuses` / `getContainerStatus`、端点元数据查询 `getEndpoints`、短时退避削峰 `pause` / `resume` 以及长时维护窗口物理连接启停 `stop` / `start`。
_Avoid_: ContainerManager, RocketmqEndpointRegistry, MqConsumerManager

**RocketmqContainerStatus**:
RocketMQ 消费监听容器运行期状态不可变快照模型，由 `RocketmqContainerManager.getContainerStatuses` / `getContainerStatus` 产出，包含监听端点标识（`endpointId`）、订阅主题（`topic`）、消费组（`consumerGroup`）、Tag 表达式（`tag`）、SQL92 表达式（`sql92`）、运行态（`running`）、暂停态（`paused`）、并发线程数（`consumptionThreadCount`）及本地最大缓存数（`maxCacheMessageCount`）。
_Avoid_: ContainerInfo, ListenerState, RocketmqEndpointView

**RocketmqDeadLetterReplayTemplate**:
RocketMQ 5.x 死信队列 (DLQ) 治理与受控重放门面，支持从死信主题 `%DLQ%<ConsumerGroup>` 执行只读探查 `peekDeadLetters`、结构化诊断探查 `peekDiagnosed`、受控重放 `replayDeadLetters` 与物理确认丢弃 `discardDeadLetters` / `discardDeadLetter`，内聚内部重试属性清洗、重放深度防二次雪崩熔断与投递成功原死信确认。
_Avoid_: DlqManager, RocketmqDeadLetterService, DeadLetterCleaner

**RocketmqDeadLetterRecord**:
RocketMQ 结构化死信诊断不可变记录模型，由 `RocketmqDeadLetterReplayTemplate.peekDiagnosed` 解析产出，包含死信消息 ID（`messageId`）、原消息 ID（`originalMessageId`）、死信主题（`dlqTopic`）、原业务主题（`originalTopic`）、消费组（`consumerGroup`）、标签（`tag`）、业务键（`bizKey`）、原生键集合（`keys`）、顺序分组（`messageGroup`）、投递尝试次数（`deliveryAttempt`）、累计重放次数（`replayCount`）、重放标记（`replayed`）、死信判定原因分类（`reason`）、归档时间戳（`deadLetterTimestamp`）、异常类名（`exceptionClassName`）、异常信息（`exceptionMessage`）、解压/十六进制回退载荷预览（`payloadAsString`）、十六进制预览标记（`hexPreview`）、原始载荷字节长度（`payloadSizeBytes`）及属性快照（`properties`）。
_Avoid_: DlqMessageRecord, DeadLetterPayload, DlqErrorInfo

**RocketmqPoisonPillCallback**:
RocketMQ 消费端不可自愈毒丸消息处置 SPI 扩展点，当 `@RocketmqListener` 捕获到解压失败、反序列化失败、JSR-303 校验违规或 `FatalExceptionClassifier` 判定的致命毒丸异常时回调，配合可选死信归档实现“0 次重试阻断自旋 + 毒丸可追溯不丢信”。
_Avoid_: PoisonPillHandler, FatalMessageListener, RocketmqErrorRecoverer

**RocketmqTransactionChecker**:
RocketMQ 5.x 全局统一事务回查分发器，作为原生 `Producer` 的全局唯一 Checker，扫描并管理所有实现了 `RocketmqLocalTransactionChecker` SPI 的 Bean，当 Broker 触发状态回查时，依据消息 Topic 策略路由分发至对应的业务检查器，彻底解耦微服务多业务事务回查。
_Avoid_: TransactionManager, RocketmqCheckManager, MonolithicTransactionChecker

**RocketmqLocalTransactionChecker**:
本地事务状态回查可插拔 SPI 扩展点，由业务领域组件实现并声明关注的 Topic，当对应半消息发生超时未决时供框架回调 `check` / `checkLocalTransaction` 并返回 `TransactionResolution`（COMMIT / ROLLBACK / UNKNOWN）。
_Avoid_: TxChecker, TransactionListener, RocketmqCheckerCallback

**RocketmqListener**:
RocketMQ 5.x 声明式监听器注解，用于标注在 Spring 受管 Bean 的消费方法上，支持 `id` / `endpointId`（`@AliasFor` 双向别名）、`topic`、`consumerGroup`、`tag`（默认 `*`）与 `sql92` 过滤表达式，底层自动组装受管 `PushConsumer` 实例，内聚四维上下文激活、微服务推断反序列化、泛型集合解析、毒丸致命异常归档/丢弃与 `@Idempotent` 幂等冲突静默确认。
_Avoid_: RocketListener, MqListener, RocketmqConsumer, RocketMessageListener

**RocketmqTransactionFailureCallback**:
本地事务提交后发信失败兜底补偿 SPI 扩展点，当 `sendAfterCommit` 或 `sendBatchAfterCommit` 在本地数据库事务物理 Commit 成功后因网络抖动或 Broker 故障导致发信失败时被触发（支持携带 `topic`、`tag`、`bizKey`、`payload`、`traceId`、`cause` 完整上下文的 6 参数默认重载），供业务落库本地补偿表或触发告警，杜绝发信静默丢失。
_Avoid_: TxFailureListener, RocketmqRollbackHandler, PostCommitFailureCallback

**AtengRocketmqProperties**:
RocketMQ 增强底座强类型配置属性类，纳管于 `ateng.boot.rocketmq.*`，严格采用 `Duration` 与 `DataSize` 消除单位歧义，提供生产级合理默认值与 IDE 补全元数据。
_Avoid_: RocketmqConfig, RocketmqSettings, AtengRocketmqConfig

**AtengRocketmqAutoConfiguration**:
RocketMQ 增强底座自动装配核心类，统一注册于 Spring Boot 3 标准 `AutoConfiguration.imports`，配置缺失 `endpoints` 时平滑静默跳过，装配 `RocketmqTemplate`、原生 `Producer`、全局事务回查分发器、`RocketmqSimpleConsumerTemplate`、`RocketmqContainerManager`、`RocketmqDeadLetterReplayTemplate` 与 `@RocketmqListener` 容器注册器。
_Avoid_: RocketmqConfiguration, RocketmqConfigRegistry, RocketmqStarterAutoConfiguration

**RocketmqProduceController**:
RocketMQ 5.x 生产端场景体验控制器，提供标准同步发信 `sync`、异步非阻塞发信 `async`、单向极速埋点 `oneway`、FIFO 局部保序 `fifo`、SQL92 属性过滤 `sql`、批量自动切片 `batch`、异步批量发信 `batch-async` 以及内存消费审计查询/清理（`received` / `clear`）接口。
_Avoid_: ProducerController, MqSendController, RocketProduceApi

**RocketmqDelayController**:
RocketMQ 5.x 延时调度与定时撤回体验控制器，提供相对时长延时发送 `duration` / `duration-async`、绝对时间戳定时调度 `deliver-at` / `deliver-at-async`、凭 `recallHandle` Broker 端原生精准撤回 `recall` 及延时消费记录查询 `received` 与清空 `clear` 接口。
_Avoid_: DelayMessageController, ScheduleController, MqDelayApi

**RocketmqTransactionController**:
RocketMQ 5.x 2PC 分布式事务与本地事务感知体验控制器，提供 2PC 事务半消息提交/回滚 `half-message`、本地事务感知单条/批量发信 `send-after-commit` / `send-batch-after-commit`、多 Topic 业务回查分发状态审计 `checker/status` 及事务消费记录查询/清理（`received` / `clear`）接口。
_Avoid_: TxController, TransactionProduceController, MqTxApi

**RocketmqSimpleConsumerController**:
RocketMQ 5.x SimpleConsumer 流式拉取与租约控制体验控制器，提供按需微批拉取 `pull`、不可见租约动态续期 `renew`、显式确认出队 `ack`、函数式闭包受控消费 `consume`、单条异常容错闭包 `consume-resilient`、待决租约句柄审计 `cached-views` 及已消费记录查询/清理（`consumed` / `clear`）接口。
_Avoid_: SimplePullController, PopConsumerController, MqPullApi

**RocketmqContainerController**:
RocketMQ 5.x 消费监听容器动态运维体验控制器，提供全量端点元数据视图查询 `endpoints`、不可变运行态状态快照查询 `statuses`、单端点与全局动态削峰暂停 `pause` / `pause-all`、恢复消费 `resume` / `resume-all`、底层物理连接销毁 `stop` 与启动 `start` 接口。
_Avoid_: ContainerOpsController, ConsumerManageController, MqContainerApi

**RocketmqDeadLetterController**:
RocketMQ 5.x 死信队列治理与受控重放体验控制器，提供零 ACK 死信只读探查 `peek`、结构化诊断窥视 `peek-diagnosed`、样本毒丸触发与 `%DLQ%` 转储演示 `poison-pill`、受控清洗重放至业务主题 `replay`（含历史异常诊断头剥离与最大重放次数防二次雪崩熔断）及死信批量物理确认丢弃 `discard` 接口。
_Avoid_: DlqController, DeadLetterManageController, MqDeadLetterApi
