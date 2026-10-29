# ateng-boot-kafka 工业级全场景流批通信、死信治理与运维监控 Starter

> 基于 Spring 官方 Spring Kafka 生态与 JDK 17/21+ 构建的工业级流批消息通信底座，集成微批高吞吐调优（32KB batch + 10ms linger + Snappy 压缩 + 幂等生产）、本地事务防幽灵发信（`sendAfterCommit` + `KafkaTransactionFailureCallback` 补偿 SPI）、原生 Kafka 分布式事务（`executeInTransaction`）、微服务推断反序列化解耦（`ByteArrayJsonMessageConverter` 彻底打破微服务类路径绑定并封杀 `__TypeId__` 反序列化漏洞）、死信受控治理闭环（`KafkaDeadLetterReplayTemplate` 探查、安全重放与防二次雪崩熔断、`KafkaDeadLetterHelper` 7 类 DLT 结构化标头双模解析）、双轨协同重试体系（本地快速指数退避 + `@RetryableTopic` 官方非阻塞阶梯延迟主题）、位移治理与实时 Lag 积压监控（`KafkaOffsetManager`）、运行期消费容器动态流控（`KafkaContainerManager`）与再均衡扩展点（`KafkaRebalanceListener` SPI）、双监听容器正交共存（单条 `kafkaListenerContainerFactory` 与批量 `batchKafkaListenerContainerFactory`）、批量拉取多租户安全隔离执行器（`KafkaRecordExecutor` 杜绝多租户与链路追踪串门）、声明式拓扑构建器（`KafkaTopicBuilder` 日志压实与强类型大报文策略）、云原生协作再均衡（`CooperativeStickyAssignor`）、10 秒优雅停机排空以及 100% 离线自洽测试沙箱。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.kafka.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **可靠投递门面** | 生产端可靠投递与事务感知门面 | `KafkaReliableTemplate` | `send-timeout`<br>`max-payload-size`<br>`producer.batch-size`<br>`producer.linger-ms`<br>`producer.compression-type`<br>`logging.enabled`<br>`logging.payload-max-length` | `sendReliable(topic, [partition], [key], payload, [bizKey], [timeout])`<br>`sendReliableAsync(topic, [partition], [key], payload, [bizKey])`<br>`sendAfterCommit(topic, [partition], [key], payload, [bizKey], [timeout])`<br>`executeInTransaction(callback)`<br>`sendBatch(topic, keyPayloadMap, [timeout])`<br>`sendBatch(topic, [key], payloads, [timeout])`<br>`getKafkaTemplate()`：同步阻塞确认、异步非阻塞 Future、Murmur2 Key 分区保序、物理 Partition 定向投递、本地 DB 事务感知防幽灵发信、Kafka 原生分布式事务原子多写、三级 bizKey 兜底、1MB 报文超限 Fail-Fast、四维上下文透明注入与 `[MQ-SEND]` 结构化宏日志 | `KafkaReliableTemplateTest`<br>`KafkaTransactionReliableTemplateTest`<br>`SampleKafkaE2eTest` |
| **死信治理门面** | 死信探查、受控重放与物理清空门面 | `KafkaDeadLetterReplayTemplate` | `dead-letter.default-replay-limit`<br>`dead-letter.max-replay-count`<br>`dead-letter.replay-group-id` | `peek(dlqTopic, limit)`：非破坏性只读探查 DLT 消息，不提交消费位移<br>`replay(dlqTopic, targetTopic, limit, [filter])`：受控重放死信回目标主题，自动清洗 `kafka_dlt-*` 底层异常头，注入 `X-Dead-Letter-Replayed`、时间戳与递增 `X-Replay-Count`；超出最大重放次数（默认 1 次）自动熔断阻断防二次死信雪崩<br>`purge(dlqTopic)`：推进起始位移物理清空死信积压 | `KafkaDeadLetterReplayTemplateTest`<br>`SampleKafkaE2eTest` |
| **死信诊断工具** | 死信结构化诊断与元数据解析工具 | `KafkaDeadLetterHelper`<br>`DeadLetterRecord` | - | `extractDeadLetterRecord(record/headers)` / `parse(record/headers)`：提取并解析 Spring Kafka 写入 DLT 的 7 类元数据（原主题、原分区、原位移、原时间戳、异常全限定类名、异常消息、堆栈轨迹），宽容兼容 4/8 字节二进制与 UTF-8 String 异构格式，返回强类型不可变记录 `DeadLetterRecord` | `KafkaDeadLetterHelperTest`<br>`SampleKafkaE2eTest` |
| **位移与积压治理** | 运行期消费位移回拨与实时 Lag 积压探测 | `KafkaOffsetManager`<br>`ConsumerGroupLag` | `offset.timeout` | `getConsumerGroupOffsets(groupId, [topic])`：查询消费组已提交位移与元数据<br>`resetOffsetsToTimestamp(groupId, topic, timestamp)`：按时间戳精准回溯重置位移（自动超出 LEO 边界降级防护）<br>`resetOffsetsToEarliest(groupId, topic)` / `resetOffsetsToLatest(groupId, topic)`：位移重置至首末端<br>`getConsumerGroupLag(groupId, topic)`：并行拉取最新 LEO 与提交位移，精确计算每个分区的 Lag 及全主题总积压 `totalLag`（防负数安全防护） | `KafkaOffsetManagerTest`<br>`SampleKafkaE2eTest` |
| **容器动态流控** | 运行期消费容器暂停与恢复削峰门面 | `KafkaContainerManager` | - | `pause(idOrTopic)` / `resume(idOrTopic)`：按 Listener ID 或 Topic 动态暂停与恢复消费拉取，优雅应对下游维护窗口与流量削峰<br>`stop(idOrTopic)` / `start(idOrTopic)`：停止/启动容器生命周期<br>`isPaused(idOrTopic)` / `isRunning(idOrTopic)`：容器状态探测<br>`pauseAll()` / `resumeAll()` / `stopAll()` / `startAll()`：全局容器批量管控 | `KafkaContainerManagerTest`<br>`SampleKafkaE2eTest` |
| **再均衡扩展点** | 消费者再均衡生命周期钩子 SPI | `KafkaRebalanceListener` | - | `onPartitionsAssigned(consumer, partitions)`：分区分配完成钩子，用于初始化本地缓存或对齐位移<br>`onPartitionsRevoked(consumer, partitions)`：分区撤销前钩子，用于刷新挂起状态或手动 Flush 脏数据<br>`onPartitionsLost(consumer, partitions)`：分区意外丢失钩子，用于清理资源并打印告警日志；双容器工厂自动扫描并注入全部实现 | `KafkaContainerManagerTest`<br>`SampleKafkaE2eTest` |
| **拓扑构建器** | 声明式流畅 Topic 构建器 (Fluent DSL) | `KafkaTopicBuilder` | `auto-create-topics` | `topic(name)`<br>`.partitions(n)`<br>`.replicas(n)`<br>`.minInSyncReplicas(n)`<br>`.retention(duration)`<br>`.segmentSize(dataSize)`<br>`.compact()`：配置 `cleanup.policy=compact` 开启日志压实<br>`.deleteAndCompact()`：配置 `cleanup.policy=delete,compact` 双重策略<br>`.maxMessageBytes(dataSize)`：强类型消除单位歧义配置单条消息上限<br>`.compressionType(type)`：主题端压缩算法覆盖<br>`.withDlt([suffix], [customizer])`<br>`.withoutDlt()`<br>`.buildMain()` / `.buildDlt()` / `.buildList()` / `.build()`：返回 `KafkaAdmin.NewTopics` 集合 Bean | `KafkaTopicBuilderTest`<br>`KafkaTopicBuilderExtendedTest`<br>`SampleKafkaE2eTest` |
| **双轨协同重试** | 本地轻量退避与非阻塞阶梯重试 | `AtengKafkaErrorHandler`<br>`@RetryableTopic` | `consumer.max-retries`<br>`consumer.retry-interval`<br>`consumer.retry-multiplier` | 本地退避：针对瞬态网络抖动快速自愈（默认 3 次，1s, 2s, 4s，上限 30s）；<br>非阻塞阶梯主题：适配 `@RetryableTopic`（如 `topic-retry-1000`、`topic-retry-2000`、`topic-dlt`），慢业务/慢外部依赖失败分流至延迟主题，防分区卡死与重平衡风暴，四维上下文全链路穿透 | `AtengKafkaErrorHandlerTest`<br>`SampleKafkaE2eTest` |
| **批量隔离执行器** | 批量消息多租户安全隔离与容错执行器 | `KafkaRecordExecutor` | - | `generateBatchTraceId()`<br>`runInContext(record, consumer/biConsumer)`<br>`callInContext(record, function/biFunction)`<br>`processBatchSafely(records, processor, [failureHandler])`：在批量拉取场景下生成批次全局 TraceId，逐条激活单条消息的四维上下文（MDC、TenantId、UserId），在 `finally` 中严格清理现场，杜绝租户脏读串门；提供逐条异常容错回调，防止单条失败阻断整批 Offset 提交 | `KafkaRecordExecutorTest`<br>`SampleKafkaE2eTest` |
| **毒丸错误处理** | 生产级错误处理器与致命异常快速隔离 | `AtengKafkaErrorHandler` | `consumer.max-retries`<br>`consumer.retry-interval`<br>`consumer.retry-multiplier` | 继承 `DefaultErrorHandler`，整合 `FatalExceptionClassifier` 判定致命不可自愈毒丸（反序列化失败、JSR-303 参数校验违规、类型转换异常等）执行 0 次重试；可自愈异常执行本地指数退避重试；配置 `setCommitRecovered(true)` 与 `setAckAfterHandle(true)` 确保恢复后正常提交 Offset | `AtengKafkaErrorHandlerTest`<br>`SampleKafkaE2eTest` |
| **死信路由恢复** | 生产级死信发布恢复器与终点防卫 | `AtengDeadLetterPublishingRecoverer` | `consumer.dlt-suffix` | `resolveDestination(record, exception, [properties])`：拦截 `IdempotentException` 输出 `[MQ-IDEMPOTENT-DUPLICATE]` 警告并返回 `null` 触发静默提交；校验死信终点不变量（Terminal Invariant），Topic 为 `.DLT` 消费失败时输出 `[MQ-DLT-TERMINAL]` 严重告警并返回 `null`，严禁死信循环反弹；其余不可自愈消息路由至 `${topic}.DLT` 并输出 `[MQ-DLT-FORWARD]` 告警 | `AtengDeadLetterPublishingRecovererTest`<br>`SampleKafkaE2eTest` |
| **事务发信补偿** | 事务提交后发信失败补偿 SPI | `KafkaTransactionFailureCallback` | - | `onFailure(topic, partition, key, payload, bizKey, traceId, cause)`：当 `sendAfterCommit` 在本地数据库事务物理 Commit 成功后因网络断连发信失败时被触发，供业务将失败消息写入补偿表或推送到告警平台 | `KafkaReliableTemplateTest`<br>`SampleKafkaE2eTest` |
| **单条/批量拦截** | 上下文环绕与结构化审计拦截器 | `KafkaRecordInterceptor`<br>`KafkaBatchInterceptor` | `logging.enabled`<br>`logging.payload-max-length` | 实现 Spring Kafka 原生 `RecordInterceptor` 与 `BatchInterceptor`，在消费前后透明激活/还原四维上下文，并在消费成功或异常时打印 `[MQ-CONSUME]` / `[MQ-CONSUME-BATCH]` 宏日志 | `KafkaRecordInterceptorTest`<br>`KafkaBatchInterceptorTest` |
| **消息类型推断** | 泛型推断反序列化消息转换器 | `ByteArrayJsonMessageConverter` | - | 默认注入开启 `TypePrecedence.INFERRED` 与 `alwaysConvertToInferredType(true)` 的 Jackson 转换器，消费端 100% 依监听方法参数推断反序列化，解耦微服务契约，杜绝 `__TypeId__` / `@class` 注入漏洞 | `ByteArrayJsonMessageConverterTest`<br>`SampleKafkaE2eTest` |
| **双监听容器工厂** | 单条与批量正交共存监听容器工厂 | `ConcurrentKafkaListenerContainerFactory` | `consumer.concurrency`<br>`consumer.batch-concurrency`<br>`consumer.shutdown-timeout` | 自动装配并正交暴露 `KafkaConstants.DEFAULT_CONTAINER_FACTORY`（单条 AckMode.RECORD）与 `KafkaConstants.BATCH_CONTAINER_FACTORY`（批量 `setBatchListener(true)`、AckMode.MANUAL_IMMEDIATE），统配 10 秒优雅停机超时与虚拟线程支持 | `AtengKafkaAutoConfigurationTest`<br>`SampleKafkaE2eTest` |
| **只读拓扑安全** | 拓扑自动声明开关与权限安全防卫 | `kafkaAdminPostProcessor` | `auto-create-topics` | `BeanPostProcessor` 监听 `KafkaAdmin`，当 `auto-create-topics` 置为 `false` 时自动将 `KafkaAdmin.autoCreate` 设为 `false`，生产只读账号不主动向 Broker 发起声明，防御 403 启动崩溃 | `AtengKafkaAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-kafka` 是面向企业级高吞吐流批处理、分布式可靠事务事件、状态快照与 SRE 智能治理的生产就绪 Starter：
- **纯原生零依赖**：100% 基于 Spring 官方 `spring-kafka`（3.1+）与 JDK 17，坚持纯原生标准库优先，严禁引入第三方全量工具包；
- **微批高吞吐预调优**：开箱默认注入 `acks=all`、`idempotence=true`、`batch.size=32KB`、`linger.ms=10ms`、`compression.type=snappy`，榨干网络系统调用性能；
- **事务防幽灵消息与原生分布式事务**：提供 `sendAfterCommit` 门面（数据库事务 Commit 后才发信）与 `executeInTransaction`（纯 Kafka 多主题原子生产与流式处理），杜绝幽灵消息；
- **死信治理与防二次雪崩**：提供 `KafkaDeadLetterReplayTemplate`（`peek`、`replay`、`purge`）与 `KafkaDeadLetterHelper`（7 类 DLT 头结构化解析），注入重放计数并在超限后强制熔断，封死死循环雪崩；
- **双轨协同重试体系**：毫秒级抖动本地快速退避 + 慢业务 `@RetryableTopic` 阶梯延迟主题，兼顾高吞吐与防分区卡死；
- **运行期位移治理与实时 Lag 探测**：提供 `KafkaOffsetManager` 实现时间戳回拨对账重放与各分区实时 Lag 计算，提供 `KafkaContainerManager` 支持维护窗口动态暂停/恢复消费。

### Maven 坐标引入
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-kafka</artifactId>
</dependency>
```

---

## 2. 维度一：最小起步体验 (Happy Path & Out-of-the-Box)

### 场景痛点
原生 Kafka 生产端单条即发吞吐低、无事务感知易产生幽灵数据；跨微服务消费需要相同的类路径与包名；毒丸消息容易导致监听容器崩溃死循环；死信只堆不救缺乏处置门面。

### 最小可用配置 (`application.yml`)
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: trade-order-group
      auto-offset-reset: earliest

ateng:
  boot:
    kafka:
      enabled: true
      send-timeout: 5s
      max-payload-size: 1MB
      auto-create-topics: true
      producer:
        compression-type: snappy
        batch-size: 32KB
        linger-ms: 10ms
      consumer:
        max-retries: 3
        retry-interval: 1s
        retry-multiplier: 2.0
      dead-letter:
        default-replay-limit: 100
        max-replay-count: 1
      offset:
        timeout: 10s
```

### 开箱极简代码示例

#### 1. 声明式定义业务主题、日志压实与死信主题
```java
@Configuration(proxyBeanMethods = false)
public class KafkaTopicTopologyConfig {

    @Bean
    public KafkaAdmin.NewTopics orderTopics() {
        return KafkaTopicBuilder.topic("order.create.topic")
                .partitions(3)
                .replicas(1)
                .minInSyncReplicas(1)
                .retention(Duration.ofDays(7))
                .withDlt() // 一键附带生成 order.create.topic.DLT
                .build();
    }

    @Bean
    public KafkaAdmin.NewTopics stateSnapshotTopics() {
        return KafkaTopicBuilder.topic("user.balance.snapshot")
                .partitions(6)
                .compact() // 开启日志压实 (cleanup.policy=compact)
                .maxMessageBytes(DataSize.ofMegabytes(10))
                .compressionType("snappy")
                .build();
    }
}
```

#### 2. 可靠发信（按 Key 分区保序、本地事务感知与原生事务）
```java
@Service
@RequiredArgsConstructor
public class TradeKafkaScenarioService {

    private final KafkaReliableTemplate kafkaReliableTemplate;

    // 1. 同步按 Key 发送订单（Murmur2 Hash 保序投递至固定分区）
    public void sendOrderSync(OrderMessage order) {
        kafkaReliableTemplate.sendReliable("order.create.topic", order.getOrderId(), order);
    }

    // 2. 本地 DB 事务感知发信：事务 Commit 后异步发信，回滚时绝对零发信
    @Transactional
    public void createOrderInLocalTx(OrderMessage order) {
        // 本地数据库保存...
        kafkaReliableTemplate.sendAfterCommit("order.create.topic", order.getOrderId(), order);
    }

    // 3. Kafka 原生分布式事务原子双写
    public void executeAtomicTradeSend(OrderMessage order, String auditLog) {
        kafkaReliableTemplate.executeInTransaction(operations -> {
            operations.send("order.create.topic", order.getOrderId(), order);
            operations.send("audit.log.topic", order.getUserId(), auditLog);
            return true;
        });
    }
}
```

#### 3. 消费端单条、批量隔离与非阻塞重试
```java
@Component
@Slf4j
public class TradeKafkaConsumers {

    // 1. 单条推断消费（四维上下文 MDC 透明还原）
    @KafkaListener(topics = "order.create.topic", containerFactory = KafkaConstants.DEFAULT_CONTAINER_FACTORY)
    public void onOrderMessage(@Payload OrderMessage order, @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        log.info("消费订单成功: key={}, orderId={}, traceId={}", key, order.getOrderId(), MDC.get("traceId"));
    }

    // 2. 批量拉取多租户严格隔离消费
    @KafkaListener(topics = "order.batch.topic", containerFactory = KafkaConstants.BATCH_CONTAINER_FACTORY)
    public void onBatchOrders(List<ConsumerRecord<String, OrderMessage>> records) {
        KafkaRecordExecutor.processBatchSafely(records, (record, order) -> {
            // 每条记录执行时捕获专属 TenantContextHolder，消费完成后现场干净无污染
            log.info("处理批量订单: orderId={}, tenantId={}", order.getOrderId(), TenantContextHolder.getTenantId());
        });
    }
}
```

---

## 3. 维度二：生产扩展接管 (Customization & Extensibility)

### 1. 事务提交后发信失败补偿 SPI (`KafkaTransactionFailureCallback`)
当使用 `sendAfterCommit` 时，若本地数据库事务成功提交后，因网络抖动导致发信最终超时或失败，框架自动回调企业自定义 SPI，供业务落库本地重发表或推送到监控中心：

```java
@Component
@Slf4j
public class OrderTxFailureCallback implements KafkaTransactionFailureCallback {

    @Override
    public void onFailure(String topic, Integer partition, String key, Object payload, String bizKey, String traceId, Throwable cause) {
        log.error("[TX-RETRY-COMPENSATION] 本地事务提交后发信失败，写入兜底补偿表: topic={}, key={}, bizKey={}, error={}",
                topic, key, bizKey, cause.getMessage());
        // 业务写入本地消息补偿表，由定时任务补偿扫表投递
    }
}
```

### 2. 消费者再均衡生命周期钩子 SPI (`KafkaRebalanceListener`)
在分区重新分配与撤销时赋予有状态消费清理本地缓存与 Flush 脏数据的扩展点：

```java
@Component
@Slf4j
public class StatefulConsumerRebalanceListener implements KafkaRebalanceListener {

    @Override
    public void onPartitionsRevoked(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        log.warn("分区被撤销，立即将本地在途内存数据 Flush 并持久化: partitions={}", partitions);
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        log.info("分区已重新分配，加载本地缓存现场: partitions={}", partitions);
    }
}
```

### 3. 覆盖默认死信重放、位移治理或流控门面 (`@ConditionalOnMissingBean`)
若业务需要自定义底层实现，仅需声明同类型 Bean 即可平滑接管：

```java
@Configuration(proxyBeanMethods = false)
public class CustomKafkaGovernanceConfig {

    @Bean
    public KafkaDeadLetterReplayTemplate customReplayTemplate(
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            AtengKafkaProperties properties) {
        return new KafkaDeadLetterReplayTemplate(consumerFactory, kafkaTemplate, properties);
    }
}
```

---

## 4. 维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 1. 毒丸 0 次重试与死信终点不变量 (Poison-Pill & Terminal DLT Invariant)
- **毒丸 0 次重试**：当畸形报文触发反序列化失败、JSR-303 参数校验异常（`ValidationException`）或非法参数异常时，`AtengKafkaErrorHandler` 动态退避函数直接返回 `FixedBackOff(0L, 0L)`，跳过无效重试快速转移至 `${topic}.DLT`，彻底消除监听容器崩溃死循环；
- **死信终点不变量 (Terminal Invariant)**：若死信消费者消费 `*.DLT` 消息本身失败，`AtengDeadLetterPublishingRecoverer` 检测到目标 Topic 已是死信 Topic，记录 `[MQ-DLT-TERMINAL]` 严重告警并返回 `null`，坚决禁止死信消息再次投向死信，彻底阻断死信无限循环反弹。

### 2. 死信受控重放与防二次雪崩熔断机制
- **消息头清洗与状态注入**：使用 `KafkaDeadLetterReplayTemplate.replay` 时，自动清洗底层 `kafka_dlt-*` 标头，注入 `X-Dead-Letter-Replayed: true`、`X-Replay-Time` 与递增的 `X-Replay-Count`；
- **防二次死信雪崩熔断**：若被重放的消息再次失败进 DLT，且重放次数超过 `max-replay-count`（默认 1 次），系统强制阻断重放，彻底防止业务主题与死信主题之间的无限死循环震荡。

### 3. 双轨协同重试防线
- **毫秒级微抖动**：依托 `AtengKafkaErrorHandler` 在消费线程内执行指数退避重试（默认 3 次），快速自愈瞬态网络抖动；
- **慢业务与外部依赖故障**：依托 Spring Kafka 官方 `@RetryableTopic` 机制，将慢响应消息分流至阶梯延迟主题（`topic-retry-1000`、`topic-retry-2000`、`topic-dlt`），保持主主题高吞吐不阻塞，全链路穿透四维上下文。

### 4. 运行期运维位移安全回拨与实时 Lag 探测
- **时间戳回拨与 LEO 保护**：`KafkaOffsetManager.resetOffsetsToTimestamp` 封装 `AdminClient` 连接池与 `try-with-resources`，当时间戳超出最新位移时自动平滑降级对齐至 LEO，杜绝因超出位移引发越界异常；
- **实时 Lag 积压探测**：`getConsumerGroupLag(groupId, topic)` 并行拉取全分区最新位移与消费组提交位移，内置 `Math.max(0L, leo - committed)` 防负数防卫，为自动化弹性伸缩与巡检告警提供原生指标。

### 5. 维护窗口消费动态流控削峰
- 使用 `KafkaContainerManager` 可在下游系统深夜维护、DDL 变更或突发故障时按 Topic 或 Listener ID 动态暂停消费拉取（`pause`），消息安全积压在 Kafka Broker；待维护完成后通过 `resume` 一键恢复，杜绝粗暴停机重启。

---

## 5. 维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)

本项目所有示例代码与配置均经由真实自洽测试套件 1:1 跑通并验证：
- 场景测试沙箱：[`ateng-boot-sample-kafka`](../ateng-boot-samples/ateng-boot-sample-kafka)
- 端到端全场景测试（15 大场景）：[`SampleKafkaE2eTest.java`](../ateng-boot-samples/ateng-boot-sample-kafka/src/test/java/io/github/atengk/boot/sample/kafka/SampleKafkaE2eTest.java)
- 单元验证测试：
  - [`KafkaReliableTemplateTest.java`](src/test/java/io/github/atengk/boot/kafka/template/KafkaReliableTemplateTest.java)
  - [`KafkaTransactionReliableTemplateTest.java`](src/test/java/io/github/atengk/boot/kafka/template/KafkaTransactionReliableTemplateTest.java)
  - [`KafkaDeadLetterReplayTemplateTest.java`](src/test/java/io/github/atengk/boot/kafka/template/KafkaDeadLetterReplayTemplateTest.java)
  - [`KafkaDeadLetterHelperTest.java`](src/test/java/io/github/atengk/boot/kafka/helper/KafkaDeadLetterHelperTest.java)
  - [`KafkaOffsetManagerTest.java`](src/test/java/io/github/atengk/boot/kafka/manager/KafkaOffsetManagerTest.java)
  - [`KafkaContainerManagerTest.java`](src/test/java/io/github/atengk/boot/kafka/manager/KafkaContainerManagerTest.java)
  - [`KafkaTopicBuilderTest.java`](src/test/java/io/github/atengk/boot/kafka/builder/KafkaTopicBuilderTest.java)
  - [`KafkaTopicBuilderExtendedTest.java`](src/test/java/io/github/atengk/boot/kafka/builder/KafkaTopicBuilderExtendedTest.java)

### 离线全量端到端验证执行命令
```bash
mvn test -pl ateng-boot-kafka,ateng-boot-samples/ateng-boot-sample-kafka -am -T 1C -nsu -B
```
