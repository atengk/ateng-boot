# Kafka Context

`ateng-boot-kafka` 负责提供基于 Spring Kafka 官方起步基线的高性能流批处理与 Offset 治理底座，支持微批高吞吐与幂等生产调优、事务提交感知发信（sendAfterCommit + 补偿 SPI）、ErrorHandlingDeserializer 反序列化崩溃防卫、FatalExceptionClassifier 毒丸快速分流至 *.DLT、双容器工厂正交共存、KafkaRecordExecutor 批量消费多租户严格隔离、@Idempotent 静默提交 Offset、KafkaTopicBuilder 声明式拓扑构建与 @EmbeddedKafka 100% 离线自洽测试。

## Language

**KafkaReliableTemplate**:
Kafka 生产端高可靠投递与事务感知增强门面，提供同步等待确认的 `sendReliable`、异步非阻塞 `sendReliableAsync`、批量发送 `sendBatch`、本地事务提交感知发信 `sendAfterCommit`，内置四维上下文自动注入、Murmur2 Key 分区保序、物理 Partition 定向投递与 1MB 大报文前置 Fail-Fast 拦截。
_Avoid_: KafkaSender, MqProducer, KafkaHelper, SimpleKafkaTemplate

**KafkaTopicBuilder**:
Kafka 声明式流式 Topic 拓扑构建器（Fluent API），用于链式声明主题、分区数（Partitions）、副本因子（Replicas）、最小同步副本数（`min.insync.replicas`）、保留时长（Retention）以及一键声明配套死信主题（`.withDlt()`），返回 Spring 原生 `NewTopic` 集合。
_Avoid_: TopicManager, TopicHelper, KafkaAdminHelper

**KafkaRecordInterceptor**:
单条消费生命周期拦截器，位于监听器容器前置与后置链条，负责入站提取解码 UTF-8 String 消息头，激活四维消息上下文（TraceId、TenantId、UserId）与 MDC，提取/补齐非空 `bizKey`，并在 `afterRecord` 的 `finally` 块中执行确定性现场清理与 `[MQ-CONSUME]` 结构化日志输出。
_Avoid_: KafkaConsumerAdvice, TraceInterceptor, RecordFilter

**KafkaRecordExecutor**:
批量消费上下文与多租户安全执行器，为批次分配统一的全局 `Batch-TraceId`，提供 `runInContext` 原子闭包与 `processBatchSafely` 容错遍历执行，单条记录独立绑定租户与链路并在处理后严格排空现场，单条失败回调 `failureHandler` 且不阻断剩余正常记录处理，彻底杜绝多租户数据串门与批次死锁。
_Avoid_: BatchHelper, RecordProcessor, TenantKafkaUtil

**KafkaTransactionFailureCallback**:
本地事务提交后发信失败兜底补偿 SPI 扩展点，当 `sendAfterCommit` 在数据库事务物理 Commit 成功后因网络抖动导致发信失败时被触发，供业务落库本地消息表或触发死信补偿告警，杜绝发信丢失。
_Avoid_: TxFailureListener, KafkaRollbackHandler, PostCommitKafkaFailure

**AtengKafkaProperties**:
Kafka 增强底座强类型配置属性类，纳管于 `ateng.boot.kafka.*`，严格采用 `Duration` 与 `DataSize` 消除单位歧义，提供生产级合理默认值与 IDE 补全元数据。
_Avoid_: KafkaConfig, KafkaSettings, AtengKafkaConfig

**AtengKafkaAutoConfiguration**:
Kafka 增强底座自动装配核心类，统一注册于 Spring Boot 3 标准 `AutoConfiguration.imports`，装配双容器工厂（单条 `kafkaListenerContainerFactory` 与批量 `batchKafkaListenerContainerFactory`）、微批与幂等生产定制器、反序列化崩溃防卫拦截、`DefaultErrorHandler` 毒丸隔离、Topic 构建器与 K8s 10 秒优雅停机。
_Avoid_: KafkaConfiguration, KafkaConfigRegistry, KafkaStarterAutoConfiguration

**KafkaDeadLetterReplayTemplate**:
Kafka 死信主题受控治理与安全重放门面，提供死信只读探查（peek）、受控重放回目标主题（replay）与位移物理清空（purge），内置单批上限保护、剥离原底层死信错误头、注入 `X-Dead-Letter-Replayed` 溯源标记并严格防范二次死信雪崩死循环。
_Avoid_: KafkaDltManager, DeadLetterSender, DltHelper

**KafkaDeadLetterHelper**:
Kafka 死信诊断辅助工具类，负责解析由 Spring Kafka `DeadLetterPublishingRecoverer` 写入 DLT 消息头的 7 类元数据（原主题、原分区、原位移、原时间戳、异常类名、异常摘要与异常调用堆栈），转换为人类可读且结构化的 `DeadLetterRecord` 诊断模型。
_Avoid_: DltParser, KafkaDltUtil, ExceptionHeaderHelper

**KafkaOffsetManager**:
Kafka 运行期位移治理与紧急逃生门面，基于 Kafka AdminClient 池化受管调用，提供查询消费组位移与实时 Lag 积压探测（`getConsumerGroupLag`），以及按指定时间戳（Rewind to timestamp）、起始点（Earliest）或最新点（Latest）安全回拨重设位移能力，具备确定性资源释放与防无界挂起保障。
_Avoid_: OffsetHelper, KafkaAdminUtil, ConsumerOffsetUtil

**KafkaContainerManager**:
Kafka 运行期监听容器动态管控门面，封装 `KafkaListenerEndpointRegistry`，为微服务在下游系统维护、DDL 变更或突发削峰场景下提供按 Topic 或 Listener ID 动态暂停（pause）、恢复（resume）、启动（start）与停止（stop）拉取能力。
_Avoid_: ContainerHelper, KafkaListenerManager, EndpointManager

**KafkaRebalanceListener**:
Kafka 消费端再均衡生命周期 SPI 扩展点，由监听器容器工厂自动扫描并注入，在分区被撤销（partitionsRevoked）与分区被重新分配（partitionsAssigned）时触发业务自定义钩子，支持有状态消费安全清理本地分区缓存或手动位移 Flush。
_Avoid_: ConsumerRebalanceHandler, RebalanceCallback, KafkaPartitionListener


