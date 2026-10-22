# ateng-boot-kafka 高性能流批处理与 Offset 治理底座 Starter

> 依托 Spring 官方 Spring Kafka 生态与 JDK 17/21+ 原生能力构建的工业级流批消息通信底座，提供高吞吐微批预调优（32KB batch + 10ms linger + Snappy 压缩 + 幂等生产）、本地事务防幽灵发信（`sendAfterCommit` + 事务补偿 SPI 杜绝幽灵消息）、微服务推断反序列化解耦（`ByteArrayJsonMessageConverter` 彻底打破微服务类路径绑定并封杀 `__TypeId__` 反序列化漏洞）、消费端毒丸隔离（整合 `FatalExceptionClassifier` 执行 0 次重试快速进 `*.DLT`）、死信终点不变量强守卫（Terminal DLT Invariant 封死死信循环反弹）、`@Idempotent` 幂等静默提交（捕获 `IdempotentException` 触发 Silent Commit 阻断重试风暴）、双监听容器正交共存（单条 `kafkaListenerContainerFactory` 与批量 `batchKafkaListenerContainerFactory`）、批量拉取多租户安全隔离执行器（`KafkaRecordExecutor` 杜绝批拉取租户与链路追踪串门）、流畅拓扑构建器（`KafkaTopicBuilder` 一键附带死信主题）、云原生协作再均衡（`CooperativeStickyAssignor`）、10 秒优雅停机排空以及 100% 离线自洽测试沙箱。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.kafka.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **可靠投递门面** | 生产端可靠投递与事务感知门面 | `KafkaReliableTemplate` | `send-timeout`<br>`max-payload-size`<br>`producer.batch-size`<br>`producer.linger-ms`<br>`producer.compression-type`<br>`logging.enabled`<br>`logging.payload-max-length` | `sendReliable(topic, [partition], [key], payload, [bizKey], [timeout])`<br>`sendReliableAsync(topic, [partition], [key], payload, [bizKey])`<br>`sendAfterCommit(topic, [partition], [key], payload, [bizKey], [timeout])`<br>`sendBatch(topic, keyPayloadMap, [timeout])`<br>`getKafkaTemplate()`：同步阻塞等待确认、异步非阻塞返回 Future、Murmur2 Key 分区保序、物理 Partition 定向投递、本地事务提交后物理发信、三级 bizKey 兜底、1MB 报文超限 Fail-Fast、四维上下文透明注入与 `[MQ-SEND]` 结构化宏日志 | `KafkaReliableTemplateTest` / `SampleKafkaE2eTest` |
| **拓扑构建器** | 声明式流畅 Topic 构建器 (Fluent DSL) | `KafkaTopicBuilder` | `auto-create-topics` | `topic(name)`<br>`.partitions(n)`<br>`.replicas(n)`<br>`.minInSyncReplicas(n)`<br>`.retention(duration)`<br>`.segmentSize(dataSize)`<br>`.config(k, v)`<br>`.withDlt([suffix], [consumer])`<br>`.withoutDlt()`<br>`.buildMain()` / `.buildDlt()` / `.buildList()` / `.build()`：链式声明单分区或多分区 Topic、最小同步副本数、保留周期，支持一键附带生成配套 `.DLT` 死信主题，返回 `KafkaAdmin.NewTopics` 集合 Bean | `KafkaTopicBuilderTest` / `SampleKafkaE2eTest` |
| **批量隔离执行器** | 批量消息多租户安全隔离与容错执行器 | `KafkaRecordExecutor` | - | `generateBatchTraceId()`<br>`runInContext(record, consumer/biConsumer)`<br>`callInContext(record, function/biFunction)`<br>`processBatchSafely(records, processor, [failureHandler])`：在批量拉取场景下生成批次全局 TraceId，逐条激活单条消息的四维上下文（MDC、TenantId、UserId），在 `finally` 中严格清理现场，杜绝租户脏读串门；提供逐条异常容错回调，防止单条失败阻断整批 Offset 提交 | `KafkaRecordExecutorTest` / `SampleKafkaE2eTest` |
| **毒丸错误处理** | 生产级错误处理器与退避重试 | `AtengKafkaErrorHandler` | `consumer.max-retries`<br>`consumer.retry-interval`<br>`consumer.retry-multiplier` | 继承 `DefaultErrorHandler`，整合 `FatalExceptionClassifier` 判定致命不可自愈毒丸（反序列化失败、JSR-303 参数校验违规、类型转换异常等）执行 0 次重试；可自愈异常执行本地指数退避重试（默认 3 次，1s, 2s, 4s，上限 30s）；配置 `setCommitRecovered(true)` 与 `setAckAfterHandle(true)` 确保恢复后正常提交 Offset | `AtengKafkaErrorHandlerTest` / `SampleKafkaE2eTest` |
| **死信路由恢复** | 生产级死信发布恢复器与终点防卫 | `AtengDeadLetterPublishingRecoverer` | `consumer.dlt-suffix` | `resolveDestination(record, exception, [properties])`：拦截 `IdempotentException` 输出 `[MQ-IDEMPOTENT-DUPLICATE]` 警告并返回 `null` 触发静默提交；校验死信终点不变量（Terminal Invariant），Topic 为 `.DLT` 消费失败时输出 `[MQ-DLT-TERMINAL]` 严重告警并返回 `null`，严禁死信循环反弹；其余不可自愈消息路由至 `${topic}.DLT` 并输出 `[MQ-DLT-FORWARD]` 告警 | `AtengDeadLetterPublishingRecovererTest` / `SampleKafkaE2eTest` |
| **事务发信补偿** | 事务提交后发信失败补偿 SPI | `KafkaTransactionFailureCallback` | - | `onFailure(topic, partition, key, payload, bizKey, traceId, cause)`：当 `sendAfterCommit` 在本地数据库事务物理 Commit 成功后因网络断连发信失败时被触发，供业务将失败消息写入补偿表或推送到告警平台 | `KafkaReliableTemplateTest` / `SampleKafkaE2eTest` |
| **单条消费拦截** | 单条消息四维上下文环绕拦截器 | `KafkaRecordInterceptor` | `logging.enabled`<br>`logging.payload-max-length` | 实现 `RecordInterceptor`，在消费方法执行前解析消息头并激活四维上下文（TraceId、TenantId、UserId），计算消费耗时并在消费成功或异常时打印 `[MQ-CONSUME]` 宏日志，在 `afterRecord` 中绝对可靠清理现场 | `KafkaRecordInterceptorTest` / `SampleKafkaE2eTest` |
| **批量消费拦截** | 批量消费批次级上下文拦截器 | `KafkaBatchInterceptor` | `logging.enabled`<br>`logging.payload-max-length` | 实现 `BatchInterceptor`，在批量消费拉取批次前后注入批次级全局 `traceId`，打印 `[MQ-CONSUME-BATCH]` 审计日志，并在批次消费完毕后清理线程现场 | `KafkaBatchInterceptorTest` / `SampleKafkaE2eTest` |
| **消息类型推断** | 泛型推断反序列化消息转换器 | `ByteArrayJsonMessageConverter` | - | 默认注入开启 `TypePrecedence.INFERRED` 与 `alwaysConvertToInferredType(true)` 的 Jackson 转换器，消费端 100% 依监听方法参数推断反序列化，解耦微服务契约，杜绝 `__TypeId__` / `@class` 注入 RCE | `ByteArrayJsonMessageConverterTest` / `SampleKafkaE2eTest` |
| **双监听容器工厂** | 单条与批量正交共存监听容器工厂 | `ConcurrentKafkaListenerContainerFactory` | `consumer.dlt-suffix`<br>`consumer.max-retries` | 自动装配并正交暴露 `KafkaConstants.DEFAULT_CONTAINER_FACTORY`（单条 AckMode.RECORD）与 `KafkaConstants.BATCH_CONTAINER_FACTORY`（批量 `setBatchListener(true)`、AckMode.MANUAL_IMMEDIATE），统配 10 秒优雅停机超时与虚拟线程支持 | `AtengKafkaAutoConfigurationTest` / `SampleKafkaE2eTest` |
| **只读拓扑安全** | 拓扑自动声明开关与权限安全防卫 | `kafkaAdminPostProcessor` | `auto-create-topics` | `BeanPostProcessor` 监听 `KafkaAdmin`，当 `auto-create-topics` 置为 `false` 时自动将 `KafkaAdmin.autoCreate` 设为 `false`，生产只读账号不主动向 Broker 发起声明，防御 403 启动崩溃 | `AtengKafkaAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-kafka` 是面向企业级高吞吐流批处理、行为日志流与分布式可靠消息履约场景的生产就绪 Starter：
- **纯原生零依赖**：100% 基于 Spring 官方 `spring-kafka`（3.1+）与 JDK 17，坚持纯原生标准库优先，严禁引入第三方全量工具包；
- **微批高吞吐预调优**：开箱默认注入 `acks=all`、`idempotence=true`、`batch.size=32KB`、`linger.ms=10ms`、`compression.type=snappy`，榨干网络系统调用性能；
- **事务防幽灵消息**：提供 `sendAfterCommit` 门面，严格保障数据库事务 Commit 后再物理发信，回滚时绝对零发信，并为提交后发信失败提供可拔插补偿回调；
- **微服务解耦与安全反序列化**：强制推断反序列化（`TypePrecedence.INFERRED`），根据消费方法入参签名反射解析，彻底取缔在 Header 传递 `__TypeId__` 全类名的强耦合陷阱；
- **消费端毒丸隔离与死信终点不变量**：不可自愈毒丸 0 次重试快速进 `*.DLT`，死信终点不变量强校验封死死信循环反弹；
- **批量拉取多租户严格隔离**：提供 `KafkaRecordExecutor` 闭包安全执行器，解决批量拉取下多租户与链路追踪串门的核心痛点；
- **云原生协作再均衡与优雅停机**：默认推荐 `CooperativeStickyAssignor`，容器默认配属 10 秒优雅停机排空缓冲（`shutdownTimeout=10000`）。

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
原生 Kafka 生产端单条即发吞吐低、无事务感知易产生幽灵数据；跨微服务消费需要相同的类路径与包名；毒丸消息容易导致监听器容器崩溃死循环。

### 最小可用配置 (`application.yml`)
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: order-service-group
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
```

### 开箱极简代码示例

#### 1. 声明 Topic 与配套死信主题
```java
@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

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
}
```

#### 2. 可靠发信（按 Key 分区保序与本地事务感知）
```java
@Component
@RequiredArgsConstructor
public class TradeKafkaProducer {

    private final KafkaReliableTemplate kafkaReliableTemplate;

    // 同步按 Key 发送订单（Murmur2 Hash 保序投递至固定分区）
    public void sendOrderSync(OrderMessage order) {
        kafkaReliableTemplate.sendReliable("order.create.topic", order.getOrderId(), order);
    }

    // 事务感知发信：本地事务成功 Commit 后才物理发信，回滚时绝对零发信
    @Transactional
    public void createOrderInTx(OrderMessage order) {
        // 1. 本地落库
        // 2. 挂载发信钩子
        kafkaReliableTemplate.sendAfterCommit("order.create.topic", order.getOrderId(), order);
    }
}
```

#### 3. 消费端单条与批量消费
```java
@Component
@Slf4j
public class OrderKafkaConsumer {

    // 1. 单条推断消费（无需 __TypeId__ Header 强耦合，四维上下文透明还原）
    @KafkaListener(
            topics = "order.create.topic",
            containerFactory = KafkaConstants.DEFAULT_CONTAINER_FACTORY
    )
    public void onOrderMessage(@Payload OrderMessage order,
                               @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        log.info("收到订单消息: key={}, orderId={}, traceId={}", key, order.getOrderId(), MDC.get("traceId"));
    }

    // 2. 批量拉取与租户安全隔离消费
    @KafkaListener(
            topics = "order.batch.topic",
            containerFactory = KafkaConstants.BATCH_CONTAINER_FACTORY
    )
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

### 2. 覆盖默认死信恢复器或错误处理器 (`@ConditionalOnMissingBean`)
若业务需要自定义死信投递规则或错误拦截器，仅需声明同类型 Bean 即可平滑接管：

```java
@Configuration(proxyBeanMethods = false)
public class CustomKafkaConfig {

    @Bean
    public CommonErrorHandler customErrorHandler(ConsumerRecordRecoverer recoverer, AtengKafkaProperties properties) {
        // 自定义错误处理器逻辑
        return new AtengKafkaErrorHandler(recoverer, properties);
    }
}
```

---

## 4. 维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 1. 毒丸 0 次重试与死信终点不变量 (Poison-Pill & Terminal DLQ Invariant)
- **毒丸 0 次重试**：当畸形报文触发反序列化失败、JSR-303 参数校验异常（`ValidationException`）或非法参数异常时，`AtengKafkaErrorHandler` 动态退避函数直接返回 `FixedBackOff(0L, 0L)`，跳过无效重试快速转移至 `${topic}.DLT`，彻底消除监听容器崩溃死循环；
- **死信终点不变量 (Terminal Invariant)**：若死信消费者消费 `*.DLT` 消息本身失败，`AtengDeadLetterPublishingRecoverer` 检测到目标 Topic 已是死信 Topic，记录 `[MQ-DLT-TERMINAL]` 严重告警并返回 `null`，坚决禁止死信消息再次投向死信，彻底阻断死信无限循环反弹。

### 2. 微服务推断反序列化解耦 (Zero TypeId Tight-Coupling)
默认注入 `ByteArrayJsonMessageConverter` 并开启 `TypePrecedence.INFERRED`：
- 发送端无需在消息 Header 携带发送方类的全限定名（`__TypeId__`）；
- 跨微服务投递时，即使微服务间 DTO 包名或类名完全不同，接收端依 `@Payload TargetDTO` 方法参数反射推断类型并安全解析；
- 杜绝反序列化 CVE 远程代码执行漏洞。

### 3. 本地事务感知发信 (Ghost Message Prevention)
- **事务内安全**：在 `@Transactional` 本地事务中调用 `sendAfterCommit`，框架注册 `TransactionSynchronization` 挂载 `afterCommit` 钩子；
- **回滚零发信**：若本地事务因异常或断言触发 Rollback，`afterCommit` 绝不执行，物理消息零发射，彻底杜绝下游消费“幽灵消息”；
- **提交后发信保障**：若事务提交后发信遇到网络瞬断，自动触发 `KafkaTransactionFailureCallback` SPI 实施兜底补偿。

### 4. 批量拉取多租户隔离 (`KafkaRecordExecutor`)
在开启 `BATCH_CONTAINER_FACTORY` 批量消费时：
- 单批拉取的多条记录可能来自不同租户；
- `KafkaRecordExecutor.processBatchSafely` 逐条提取消息头 `X-Tenant-Id` 并激活 `TenantContextHolder`，在 `finally` 中严格清理现场；
- 避免单条消息异常中断整批处理，提供安全失败回调。

### 5. 高吞吐微批预调优与协作重平衡 (Micro-Batch & Cooperative Rebalance)
- **微批调优**：开箱默认配置 `batch.size=32KB`、`linger.ms=10ms` 与 `compression.type=snappy`，兼顾百微秒级低延迟与数十倍吞吐提升；
- **云原生协作再均衡**：消费端默认推荐 `CooperativeStickyAssignor`，在 K8s 容器扩缩容与滚动发布时避免 Eager 全局业务暂停（STW），仅迁移必要分区；
- **10 秒优雅停机**：监听容器默认设置 `shutdownTimeout=10000`，收到停机信号后停止接收新拉取，预留 10 秒时间窗口排空在途消费并完成 Offset 提交。

---

## 5. 维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)

本项目所有示例代码与配置均经由真实自洽测试套件 1:1 跑通并验证：
- 场景测试沙箱：[`ateng-boot-sample-kafka`](../ateng-boot-samples/ateng-boot-sample-kafka)
- 集成验证测试：[`SampleKafkaE2eTest.java`](../ateng-boot-samples/ateng-boot-sample-kafka/src/test/java/io/github/atengk/boot/sample/kafka/SampleKafkaE2eTest.java)
- 单元验证测试：[`KafkaReliableTemplateTest.java`](src/test/java/io/github/atengk/boot/kafka/template/KafkaReliableTemplateTest.java)、[`KafkaTopicBuilderTest.java`](src/test/java/io/github/atengk/boot/kafka/builder/KafkaTopicBuilderTest.java)

### 离线自验执行命令
```bash
mvn test -pl ateng-boot-kafka,ateng-boot-samples/ateng-boot-sample-kafka -am -T 1C -nsu -B
```
