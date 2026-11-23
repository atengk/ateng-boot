# Kafka Context

`ateng-boot-kafka` 负责提供基于 Spring Kafka 官方起步基线的高性能流批处理与 Offset 治理底座，支持微批高吞吐与幂等生产调优、单条/批量同步与异步可靠投递、事务提交感知发信（`sendAfterCommit` / `sendBatchAfterCommit` + 补偿 SPI）与原生分布式事务（`executeInTransaction`）、ErrorHandlingDeserializer 反序列化崩溃防卫、FatalExceptionClassifier 毒丸快速分流至 `*.DLT`、死信结构化诊断与受控重放（`KafkaDeadLetterReplayTemplate` / `KafkaDeadLetterHelper`）、运行期位移治理与实时积压监控（`KafkaOffsetManager` / `ConsumerGroupLag`）、运行期容器动态流控与扩缩容（`KafkaContainerManager` / `KafkaContainerStatus`）、双容器工厂正交共存、KafkaRecordExecutor 批量消费多租户严格隔离、@Idempotent 静默提交 Offset、KafkaTopicBuilder 声明式拓扑构建与 100% 离线自洽测试。

## Language

**KafkaReliableTemplate**:
Kafka 生产端高可靠投递与事务感知增强门面，提供同步等待确认的 `sendReliable`、异步非阻塞 `sendReliableAsync`、同步批量 `sendBatch` / `sendBatchRecords`、异步批量 `sendBatchAsync` / `sendBatchRecordsAsync`、本地事务提交感知发信 `sendAfterCommit` / `sendBatchAfterCommit` / `sendBatchRecordsAfterCommit` 及原生分布式事务 `executeInTransaction`，内置四维上下文自动注入、自定义扩展头传递、Murmur2 Key 分区保序、物理 Partition 定向投递、批量两阶段全量前置防卫与 1MB 大报文 Fail-Fast 拦截。
_Avoid_: KafkaSender, MqProducer, KafkaHelper, SimpleKafkaTemplate

**KafkaTopicBuilder**:
Kafka 声明式流式 Topic 拓扑构建器（Fluent API），用于链式声明主题、分区数（Partitions）、副本因子（Replicas）、最小同步副本数（`min.insync.replicas`）、保留时长（Retention）、日志压实策略（`.compact()` / `.deleteAndCompact()`）以及一键声明配套死信主题（`.withDlt()`），返回 Spring 原生 `NewTopic` 集合。
_Avoid_: TopicManager, TopicHelper, KafkaAdminHelper

**KafkaRecordInterceptor**:
单条消费生命周期拦截器，位于监听器容器前置与后置链条，负责入站提取解码 UTF-8 String 消息头，激活四维消息上下文（TraceId、TenantId、UserId、IgnoreTenant）与 MDC，提取/补齐非空 `bizKey`，并在 `afterRecord` 的 `finally` 块中执行确定性现场清理与 `[MQ-CONSUME]` 结构化日志输出。
_Avoid_: KafkaConsumerAdvice, TraceInterceptor, RecordFilter

**KafkaBatchInterceptor**:
批量消费入站生命周期拦截器，负责在拉取到批量记录时生成批次全局 `batch-<UUID>` 追踪标识并绑定 MDC，统计整批消费耗时并在批次结束的 `finally` 块中确定性清理线程现场与输出 `[MQ-CONSUME-BATCH]` 结构化审计日志。
_Avoid_: BatchConsumeAdvice, BatchTraceInterceptor, KafkaBatchFilter

**KafkaRecordExecutor**:
批量消费上下文与多租户安全执行器，为批次分配统一的全局 `Batch-TraceId`，提供 `runInContext` / `callInContext` 原子闭包与 `processBatchSafely` 容错遍历执行，单条记录独立绑定租户与链路并在处理后严格排空现场，单条失败回调 `failureHandler` 且不阻断剩余正常记录处理，彻底杜绝多租户数据串门与批次死锁。
_Avoid_: BatchHelper, RecordProcessor, TenantKafkaUtil

**KafkaTransactionFailureCallback**:
本地事务提交后发信失败兜底补偿 SPI 扩展点，当 `sendAfterCommit` 或 `sendBatchAfterCommit` 在数据库事务物理 Commit 成功后因网络抖动导致发信失败时被触发，供业务落库本地消息表或触发死信补偿告警，杜绝发信丢失。
_Avoid_: TxFailureListener, KafkaRollbackHandler, PostCommitKafkaFailure

**AtengKafkaProperties**:
Kafka 增强底座强类型配置属性类，纳管于 `ateng.boot.kafka.*`，严格采用 `Duration`、`DataSize` 与正整数边界校验消除单位与非法阈值歧义，统管生产微批、消费退避、死信治理与位移监控参数并提供生产级合理默认值。
_Avoid_: KafkaConfig, KafkaSettings, AtengKafkaConfig

**AtengKafkaAutoConfiguration**:
Kafka 增强底座自动装配核心类，统一注册于 Spring Boot 3 标准 `AutoConfiguration.imports`，装配双容器工厂（单条 `kafkaListenerContainerFactory` 与批量 `batchKafkaListenerContainerFactory`）、微批与幂等生产定制器、反序列化崩溃防卫拦截、`AtengKafkaErrorHandler` 毒丸隔离、死信治理与运维门面及 10 秒优雅停机。
_Avoid_: KafkaConfiguration, KafkaConfigRegistry, KafkaStarterAutoConfiguration

**AtengKafkaErrorHandler**:
Kafka 生产级通用消费错误处理器，继承 `DefaultErrorHandler` 并整合 Core `FatalExceptionClassifier`，对反序列化异常、JSR-303 校验失败与幂等冲突执行 0 次重试快速分流，对可自愈瞬态异常执行受控本地指数退避重试并在耗尽后交由恢复器处置。
_Avoid_: KafkaExceptionHandler, CustomErrorHandler, RetryErrorHandler

**AtengDeadLetterPublishingRecoverer**:
Kafka 生产级死信发布恢复器，继承 `DeadLetterPublishingRecoverer`，负责拦截 `IdempotentException` 实现静默提交 Offset（Silent Commit）、守卫 `.DLT` 死信终点不变量（Terminal Invariant，严禁死信二次反弹循环）并将不可自愈故障消息路由至配套死信主题。
_Avoid_: DltRecoverer, DeadLetterSenderRecoverer, KafkaErrorRecoverer

**KafkaDeadLetterReplayTemplate**:
Kafka 死信主题受控治理与安全重放门面，提供死信原始探查（`peek`）、结构化诊断探查（`peekDiagnosed`）、受控重放回目标主题（`replay`）与位移物理清空（`purge`），内置单批上限保护、剥离原底层死信错误头、注入 `X-Dead-Letter-Replayed` 溯源标记并严格防范二次死信雪崩死循环。
_Avoid_: KafkaDltManager, DeadLetterSender, DltHelper

**KafkaDeadLetterHelper**:
Kafka 死信诊断辅助工具类，负责解析由 Spring Kafka `DeadLetterPublishingRecoverer` 写入 DLT 消息头的 7 类元数据（原主题、原分区、原位移、原时间戳、异常类名、异常摘要与异常调用堆栈），宽容兼容二进制与 UTF-8 字符串异构编码并转换为强类型 `DeadLetterRecord` 诊断模型。
_Avoid_: DltParser, KafkaDltUtil, ExceptionHeaderHelper

**KafkaMessageHelper**:
Kafka 消息头与上下文内聚辅助工具类，负责提取 `ConsumerRecord` 消息头映射、按优先级自适应补齐并回写 `X-Biz-Key` 以及递归解包异常因果链识别 `IdempotentException`。
_Avoid_: HeaderUtil, KafkaHeaderExtractor, MessageContextUtil

**KafkaOffsetManager**:
Kafka 运行期位移治理与紧急逃生门面，基于 Kafka AdminClient 受管调用，提供查询消费组位移与实时 Lag 积压探测（`getConsumerGroupLag`），以及按指定时间戳（Rewind to timestamp）、起始点（Earliest）或最新点（Latest）安全回拨重设位移能力，具备确定性资源释放与防无界挂起保障。
_Avoid_: OffsetHelper, KafkaAdminUtil, ConsumerOffsetUtil

**KafkaContainerManager**:
Kafka 运行期监听容器动态管控与观测门面，封装 `KafkaListenerEndpointRegistry`，为微服务在下游系统维护、DDL 变更或突发削峰场景下提供按 Topic 或 Listener ID 动态暂停（`pause`）、恢复（`resume`）、启动（`start`）、停止（`stop`）、并发度热扩缩容（`scaleConcurrency`）以及容器运行状态快照查询（`getContainerStatuses` / `getContainerStatus`）能力。
_Avoid_: ContainerHelper, KafkaListenerManager, EndpointManager

**KafkaContainerStatus**:
Kafka 消费监听容器运行期状态不可变快照模型，由 `KafkaContainerManager.getContainerStatuses` / `getContainerStatus` 产出，包含监听端点 ID（`listenerId`）、消费组 ID（`groupId`）、订阅主题列表（`topics`）、运行态（`running`）、暂停态（`paused`）、自动启动标志（`autoStartup`）、批量监听标志（`batchListener`）、并发度（`concurrency`）、活跃子容器数（`activeContainerCount`）及当前已分配分区列表（`assignedPartitions`）。
_Avoid_: ContainerInfo, ListenerState, KafkaEndpointView

**KafkaRebalanceListener**:
Kafka 消费端再均衡生命周期 SPI 扩展点，由监听器容器工厂自动扫描并注入，在分区被撤销（`onPartitionsRevoked`）、重新分配（`onPartitionsAssigned`）与意外丢失（`onPartitionsLost`）时触发业务自定义钩子，支持有状态消费安全清理本地分区缓存或手动位移 Flush。
_Avoid_: ConsumerRebalanceHandler, RebalanceCallback, KafkaPartitionListener

**KafkaAdminClientFactory**:
Kafka `AdminClient` 创建工厂 SPI 扩展点，默认由底座委托 `AdminClient.create(properties)` 构建原生客户端；支持在离线内存仿真、自定义安全认证或专用测试环境中向 Spring 容器注册 Bean 接管底层管理客户端创建。
_Avoid_: AdminClientProvider, KafkaAdminBuilder, AdminFactory

**DeadLetterRecord**:
Kafka 结构化死信元数据强类型记录模型，由 `KafkaDeadLetterHelper` 及 `KafkaDeadLetterReplayTemplate.peekDiagnosed` 解析产出，包含原主题（`originalTopic`）、原分区（`originalPartition`）、原位移（`originalOffset`）、原时间戳（`originalTimestamp`）、异常类名（`exceptionClassName`）、异常信息（`exceptionMessage`）、异常堆栈（`exceptionStackTrace`）、重放计数（`replayCount`）、是否重放标记（`isReplayed`）以及死信消息键值与载荷内容（`key` / `payload`）。
_Avoid_: DltMessageRecord, DeadLetterPayload, DltErrorInfo

**ConsumerGroupLag**:
Kafka 消费组实时积压诊断模型，由 `KafkaOffsetManager.getConsumerGroupLag` 产出，包含消费组 ID（`groupId`）、业务主题（`topic`）、各分区最新位移与提交位移明细映射（`partitionLags`）以及防负数保护的全主题总积压（`totalLag`）。
_Avoid_: GroupLagInfo, OffsetLagResult, KafkaLagMetric


