# ateng-boot-rocketmq 生产增强与原生分布式事务 Starter

> 基于 Apache RocketMQ 5.x 官方 Java SDK (Shaded gRPC 瘦客户端) 与 JDK 17/21+ 原生能力构建的工业级消息通信与分布式事务底座 Starter。提供生产级统一受管高阶发信门面（`RocketmqTemplate`）、流式微批拉取与租约控制门面（`RocketmqSimpleConsumerTemplate`）、运行期消费容器动态流控运维门面（`RocketmqContainerManager`）、死信队列治理与受控重放门面（`RocketmqDeadLetterReplayTemplate`）、毫秒级延时与定时发信、定时延时消息原生精准撤回（`recallMessage`）、批量自动容量切片（`sendBatch`）、FIFO 顺序保序投递、原生 2PC 分布式事务半消息与领域解耦统一回查分发架构（`RocketmqLocalTransactionChecker`）、本地事务提交感知发信（`sendAfterCommit` + 补偿 SPI 杜绝幽灵消息）、声明式事件驱动监听容器（`@RocketmqListener`）、微服务推断反序列化解耦、4MB 大报文防御与透明 GZIP 压缩、消费端毒丸安全丢弃与 `@Idempotent` 幂等静默确认（彻底消灭 16 次无脑重试风暴）、`SmartLifecycle` 确定性停机排空以及 100% 内存自洽双模测试沙箱。

---

## 0. 特性与公共 API 全景速查矩阵 (维度零：100% 全覆盖)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.rocketmq.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **可靠消息发信** | 统一生产端发信门面 (普通/延时/定时/FIFO/事务/批量) | `RocketmqTemplate` | `enabled`<br>`endpoints`<br>`ssl-enabled`<br>`request-timeout`<br>`producer.send-timeout`<br>`producer.max-attempts`<br>`producer.max-batch-size`<br>`producer.max-payload-size`<br>`producer.compression-threshold`<br>`producer.topics` | `send(topic, [tag], [bizKey], payload, [userProperties])`<br>`sendAsync(topic, [tag], [bizKey], payload, [userProperties])`<br>`sendOneway(topic, [tag], [bizKey], payload, [userProperties])`<br>`sendDelayed(topic, [tag], [bizKey], payload, delay, [userProperties])`<br>`sendDelayedAsync(topic, [tag], [bizKey], payload, delay, [userProperties])`<br>`sendDeliverAt(topic, [tag], [bizKey], payload, deliverAt, [userProperties])`<br>`sendDeliverAtAsync(topic, [tag], [bizKey], payload, deliverAt, [userProperties])`<br>`recallMessage(topic, recallHandle)`<br>`recallMessageAsync(topic, recallHandle)`<br>`sendOrderly(topic, [tag], [bizKey], payload, messageGroup, [userProperties])`<br>`sendOrderlyAsync(topic, [tag], [bizKey], payload, messageGroup, [userProperties])`<br>`sendTransaction(topic, [tag], [bizKey], payload, executor, [userProperties])`<br>`sendAfterCommit(topic, [tag], [bizKey], payload, [userProperties])`<br>`sendBatch(topic, [tag], payloads, [userProperties])`<br>`sendBatchAsync(topic, [tag], payloads, [userProperties])`<br>`getProducer()`：同步阻塞确认、异步非阻塞返回 `CompletableFuture`、单向日志发信、毫秒级定时调度、原生延时精准撤回、MessageGroup 顺序保序、2PC 事务半消息、本地事务提交感知发信、批量超限智能切片、多级 bizKey 兜底、Topic 正则防卫、4MB 报文超限 Fail-Fast、4KB GZIP 透明压缩、四维上下文透明注入与 `[MQ-SEND]` 结构化宏日志 | `RocketmqTemplateTest` / `RocketmqProduceController` / `SampleRocketmqWebControllerTest` |
| **流式微批拉取** | 消费端 SimpleConsumer 租约拉取与流控 | `RocketmqSimpleConsumerTemplate` | `simple-consumer.invisible-duration`<br>`simple-consumer.max-message-num`<br>`simple-consumer.await-duration` | `receive(topic, consumerGroup, [tag], [maxNum], [invisibleDuration])`<br>`receiveAsync(topic, consumerGroup, [tag], [maxNum], [invisibleDuration])`<br>`ack(consumerGroup, messageView)`<br>`ackAsync(consumerGroup, messageView)`<br>`changeInvisibleDuration(consumerGroup, messageView, invisibleDuration)`<br>`changeInvisibleDurationAsync(consumerGroup, messageView, invisibleDuration)`<br>`receiveAndExecute(topic, consumerGroup, [tag], [maxNum], [invisibleDuration], targetType, consumer)`<br>`getOrCreateConsumer(topic, consumerGroup, tag)`：基于官方 Pop 机制的长轮询微批流式拉取、动态隐身不可见租约续期、显式 ACK 确认、高阶推断反序列化与函数式闭包受控消费 | `RocketmqSimpleConsumerTemplateTest` / `RocketmqSimpleConsumerController` / `SampleRocketmqWebControllerTest` |
| **容器运维管控** | 消费监听容器动态流控与生命周期运维 | `RocketmqContainerManager` | `consumer.consumption-thread-count`<br>`consumer.max-cache-message-count`<br>`consumer.close-timeout` | `getEndpointIds()`<br>`getEndpoints()`<br>`getEndpoint(id)`<br>`findEndpoints(idOrTopic)`<br>`pause(idOrTopic)`<br>`resume(idOrTopic)`<br>`pauseAll()`<br>`resumeAll()`<br>`isPaused(idOrTopic)`<br>`start(idOrTopic)`<br>`stop(idOrTopic)`<br>`startAll()`<br>`stopAll()`<br>`isRunning(idOrTopic)`：全量端点审计可见性、运行期大促流量极速削峰（返回 FAILURE 促使 Broker 阶梯退避）、不重启物理优雅启停 PushConsumer 底层连接 | `RocketmqContainerManagerTest` / `RocketmqContainerController` / `SampleRocketmqWebControllerTest` |
| **死信队列治理** | 死信队列只读探查、受控重放与丢弃清理 | `RocketmqDeadLetterReplayTemplate` | `dead-letter.max-replay-attempts`<br>`dead-letter.default-batch-size`<br>`dead-letter.max-batch-size`<br>`dead-letter.invisible-duration`<br>`dead-letter.discard-on-max-attempts-blocked` | `getDlqTopic(consumerGroup)`<br>`peekDeadLetters(consumerGroup, [maxNum], [invisibleDuration])`<br>`replayDeadLetters(consumerGroup, [maxNum], [targetTopic], [invisibleDuration])`<br>`discardDeadLetters(consumerGroup, [maxNum])`<br>`discardDeadLetter(consumerGroup, messageView)`<br>`getSimpleConsumerTemplate()`<br>`getRocketmqTemplate()`：死信主题 `%DLQ%<Group>` 自动映射、零 ACK 只读安全探查、受控限流原子重放至业务主题、内部重试头安全清洗、全链路审计打标、防二次雪崩熔断拦截与确定性源端 ACK 确认、批量与单条对称清理丢弃 | `RocketmqDeadLetterReplayTemplateTest` / `RocketmqDeadLetterController` / `SampleRocketmqWebControllerTest` |
| **原生 2PC 分布式事务** | 统一事务回查分发器与领域解耦 SPI | `RocketmqTransactionChecker`<br>`RocketmqLocalTransactionChecker` | - | `check(messageView)`：统一实现 RocketMQ 5.x 官方 `TransactionChecker`，容器启动时自动发现业务 Checker，根据 Topic 动态策略路由至对应业务实现，决断 `COMMIT` / `ROLLBACK` / `UNKNOWN`，彻底消除微服务单体上帝类 | `RocketmqTransactionCheckerTest` / `RocketmqTransactionController` / `SampleRocketmqWebControllerTest` |
| **本地事务执行器** | 本地业务事务决断回调函数式接口 | `RocketmqLocalTransactionExecutor` | - | `execute(sendReceipt)`：在 2PC 事务半消息成功投递后被回调执行，根据本地数据库或业务处理结果返回 `TransactionResolution`（`COMMIT` / `ROLLBACK` / `UNKNOWN`），执行异常自动触发回滚 | `RocketmqTemplateTest` / `RocketmqTransactionController` / `SampleRocketmqWebControllerTest` |
| **事务发信补偿** | 本地事务提交后发信失败补偿 SPI | `RocketmqTransactionFailureCallback` | - | `onFailure(topic, tag, bizKey, payload, traceId, cause)`：当 `sendAfterCommit` 在本地数据库事务提交后因网络断连导致发信失败时被触发，供业务写入本地补偿表或推送到报警平台 | `RocketmqTemplateTest` / `RocketmqTransactionController` |
| **声明式监听注解** | 消费端声明式事件驱动监听注解 | `@RocketmqListener` | - | `@RocketmqListener(topic = "...", consumerGroup = "...", tag = "*", sql92 = "", endpointId = "", consumptionThreadCount = 0, maxCacheMessageCount = 0)`：标注在 Spring 受管 Bean 的消费处理方法上，支持属性占位符表达式、Tag 过滤（支持 `tagA || tagB` 逻辑或）、SQL92 属性过滤下推与端点 ID 显式命名 | `RocketmqConsumerContainerTest` / `OrderStandardConsumer` / `SampleRocketmqWebControllerTest` |
| **消费端受管容器** | 监听容器与确定性生命周期管理器 | `RocketmqConsumerContainer` | `consumer.consumption-thread-count`<br>`consumer.max-cache-message-count`<br>`consumer.close-timeout` | 实现 `SmartInitializingSingleton`、`SmartLifecycle`、`DisposableBean`：自动扫描注册监听端点、严格校验 Topic 命名正则（`^[%a-zA-Z0-9_-]+$`，坚决杜绝点号）；停机时优先关闭所有 `PushConsumer` 实例（受控于 `closeTimeout` 超时排空在途消费），保障平滑发布 | `RocketmqConsumerContainerTest` / `SampleRocketmqE2eTest` |
| **客户端工厂抽象** | PushConsumer 构造工厂与测试替身 SPI | `PushConsumerFactory` | - | `createPushConsumer(clientConfig, group, topic, filter, listener, threads, cacheCount)`：解耦底层物理 gRPC 客户端构建逻辑，支持生产环境默认工厂 `DefaultPushConsumerFactory` 与测试环境内存替身 `InMemoryRocketmqBroker` 无缝拔插切换 | `RocketmqConsumerContainerTest` / `SampleRocketmqE2eTest` |
| **流式拉取工厂抽象** | SimpleConsumer 构造工厂与测试替身 SPI | `SimpleConsumerFactory` | - | `createSimpleConsumer(clientConfig, group, awaitDuration, subscriptions)`：解耦底层物理 SimpleConsumer 构建逻辑，支持生产环境默认工厂 `DefaultSimpleConsumerFactory` 与测试环境内存替身 `InMemoryRocketmqBroker` 拔插切换 | `RocketmqSimpleConsumerTemplateTest` / `SampleRocketmqE2eTest` |
| **消费调用执行器** | 微服务推断反序列化与受控防卫核心 | `RocketmqListenerInvoker` | - | 实现 `MessageListener`：瞬态四维上下文入站还原（`MDC traceId`, `TenantContextHolder`, `UserContextHolder`）与 `finally` 现场清理；透明 GZIP 解压；依据方法参数类型与 `ParameterizedType` 精准推断反序列化（支持原生 `MessageView`、`byte[]`、`String`、业务 DTO 及泛型集合 `` `List<T>` ``）；JSR-303 参数校验；不可自愈毒丸快速丢弃返回 `SUCCESS`；`IdempotentException` 幂等冲突静默确认返回 `SUCCESS`；瞬态异常返回 `FAILURE` 触发服务端重试 | `RocketmqListenerInvokerTest` / `SampleRocketmqWebControllerTest` |
| **消息辅助工具** | 序列化、四维上下文与 bizKey 解析工具 | `RocketmqMessageHelper` | - | `resolveBizKey(bizKey, payload)`：多级 bizKey 兜底（显式指定 -> 反射提取 `getId()`/`getBizKey()`/`getOrderNo()`/`getCode()`/`getKey()` -> MDC traceId 兜底 -> UUID）；`extractBizKey(messageView)`：入站消息对称提取业务标识（优先 `X-Biz-Key`，兜底原生 Keys）；`toPayloadBytes(payload)`：纯净 JSON 序列化；`compressGzip(bytes)` / `decompressGzip(bytes)`：透明压缩与解压 | `RocketmqMessageHelperTest` |
| **强类型配置属性** | 强类型配置属性类与 IDE 智能补全 | `AtengRocketmqProperties` | `ateng.boot.rocketmq.*` | 包含 `enabled`、`endpoints`、`access-key`、`secret-key`、`ssl-enabled`、`request-timeout`（`Duration`）、`producer.send-timeout`（`Duration`）、`producer.max-attempts`、`producer.max-batch-size`、`producer.max-payload-size`（`DataSize`）、`producer.compression-threshold`（`DataSize`）、`producer.topics`、`consumer.consumption-thread-count`、`consumer.max-cache-message-count`、`consumer.close-timeout`（`Duration`）、`simple-consumer.invisible-duration`（`Duration`）、`simple-consumer.max-message-num`、`simple-consumer.await-duration`（`Duration`）、`dead-letter.max-replay-attempts`、`dead-letter.default-batch-size`、`dead-letter.max-batch-size`、`dead-letter.invisible-duration`（`Duration`）、`dead-letter.discard-on-max-attempts-blocked` | `AtengRocketmqPropertiesTest` |

---

## 1. 模块定位与快速起步 (Happy Path & Out-of-the-Box)

### 1.1 模块定位与核心价值
`ateng-boot-rocketmq` 是面向生产级高可靠交易通信、原生 2PC 分布式事务履约与事件驱动架构的 Starter：
- **纯净官方 Shaded 瘦客户端**：100% 依托官方 `rocketmq-client-java:5.2.2` Shaded 坐标与 JDK 17 原生能力，物理隔绝 gRPC、Netty 与老旧 Remoting 依赖污染；
- **四维高阶门面矩阵**：
  - `RocketmqTemplate`：同步/异步/单向可靠发信、毫秒级延时与定时发信、定时延时消息原生精准撤回、批量超限自动切片、FIFO 顺序保序发信与 2PC 分布式事务半消息；
  - `RocketmqSimpleConsumerTemplate`：官方 SimpleConsumer 流式拉取微批长轮询、不可见租约动态续期与高阶函数式反序列化消费；
  - `RocketmqContainerManager`：运行期消费端点审计、动态暂停/恢复削峰与物理启停；
  - `RocketmqDeadLetterReplayTemplate`：死信只读探查（零 ACK）、受控重放、协议头安全清洗与防二次雪崩熔断；
- **原生 2PC 分布式事务统一回查**：内置全局 `RocketmqTransactionChecker`，通过 `RocketmqLocalTransactionChecker` SPI 将 Broker 事务回查精准路由到业务组件，彻底消除单体上帝类；
- **本地事务提交感知发信**：`sendAfterCommit` 严格保障数据库事务物理 Commit 成功后再投递消息，事务回滚时绝对零发信，并配属失败补偿 SPI；
- **微服务推断反序列化解耦**：依据监听器参数强类型与泛型反射自动还原 Java 实体，彻底摆脱发送端类全限定名的耦合枷锁；
- **高韧性生产安全防线**：不可自愈毒丸安全丢弃返回 SUCCESS；`@Idempotent` 防重冲突静默确认；4MB 硬上限拦截与 4KB 透明压缩保护 Broker；
- **确定性停机排空**：实现 Spring `SmartLifecycle`，应用停机时优先关闭所有 PushConsumer 实例（排空在途消费），保障平滑发布；
- **100% 离线自洽双模测试沙箱**：基于纯应用层 `PushConsumerFactory` 与 `SimpleConsumerFactory` SPI，由 `InMemoryRocketmqBroker` 提供全场景测试替身，零外部网络与 Docker 依赖，秒级全绿。

### 1.2 Maven 坐标引入
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-rocketmq</artifactId>
</dependency>
```

### 1.3 最小起步配置 (`application.yml`)
```yaml
ateng:
  boot:
    rocketmq:
      enabled: true
      endpoints: "127.0.0.1:8081"          # RocketMQ 5.x gRPC Proxy 地址
      request-timeout: 3s                  # gRPC 客户端全局请求超时
      producer:
        send-timeout: 3s                   # 生产端发信超时
        topics:
          - sample_order_topic             # 预先初始化的业务主题
```

### 1.4 极简实战代码（业务实体 + 发信 + 消费）
```java
// 1. 业务实体模型
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderMessage {
    @NotBlank(message = "订单ID不能为空")
    private String orderId;
    @PositiveOrZero(message = "订单金额不能为负数")
    private Double amount;
    private String customerName;
    private String status;
}

// 2. 生产端发信示例
@Service
@RequiredArgsConstructor
public class OrderProducerService {
    private final RocketmqTemplate rocketmqTemplate;

    public SendReceipt sendOrder(OrderMessage order) {
        return rocketmqTemplate.send("sample_order_topic", "create", order.getOrderId(), order);
    }
}

// 3. 消费端声明式监听示例
@Component
@Slf4j
public class OrderConsumer {
    @RocketmqListener(topic = "sample_order_topic", consumerGroup = "sample_order_group", tag = "create")
    public void onOrder(@Valid OrderMessage order, MessageView messageView) {
        log.info("[ORDER-CONSUMED] 接收订单消息: orderId={}, tag={}", order.getOrderId(), messageView.getTag().orElse(""));
    }
}
```

---

## 2. 可靠消息生产套件 (Reliable Message Producing Suite)

### 2.1 同步阻塞发信与四维上下文透传
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        producer:
          send-timeout: 3s                 # 同步发信超时时间（默认 3 秒）
          max-attempts: 3                  # 发送失败最大重试次数
  ```
- **业务 Java 示例**（逆向提取自 `RocketmqProduceController.java`）：
  ```java
  @PostMapping("/sync")
  public Result<OrderProduceResponse> sendSync(@Valid @RequestBody OrderProduceRequest request) {
      OrderMessage order = request.toOrderMessage();
      String tag = StringUtils.hasText(request.getTag()) ? request.getTag() : "create";
      SendReceipt receipt = standardScenarioService.sendSync(order, tag);
      OrderProduceResponse response = OrderProduceResponse.builder()
              .orderId(order.getOrderId())
              .messageId(receipt.getMessageId().toString())
              .tag(tag)
              .status("SUCCESS")
              .build();
      return Result.success(response);
  }
  ```
- **核心机制与边界防御**：
  同步阻塞直至 Broker 返回 `SendReceipt`。框架自动提取四维上下文快照（`MDC traceId`, `TenantContextHolder`, `UserContextHolder`）并无损注入消息自定义属性；多级 `bizKey` 解析保障即使调用方未传入 `bizKey`，也能依次通过反射查找 `getId()`/`getOrderId()`/`getBizKey()`、MDC traceId 或 UUID 进行兜底，确保全链路消息可检索。

### 2.2 异步非阻塞发信与单向极速埋点
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        producer:
          send-timeout: 3s
  ```
- **业务 Java 示例**（逆向提取自 `RocketmqProduceController.java`）：
  ```java
  // 异步非阻塞发信
  @PostMapping("/async")
  public Result<OrderProduceResponse> sendAsync(@Valid @RequestBody OrderProduceRequest request) {
      OrderMessage order = request.toOrderMessage();
      CompletableFuture<SendReceipt> future = standardScenarioService.sendAsync(order);
      SendReceipt receipt = future.join();
      return Result.success(OrderProduceResponse.builder()
              .orderId(order.getOrderId())
              .messageId(receipt.getMessageId().toString())
              .tag("create")
              .status("ASYNC_SUCCESS")
              .build());
  }

  // 单向极速发送（埋点/日志）
  @PostMapping("/oneway")
  public Result<Void> sendOneway(@Valid @RequestBody OrderProduceRequest request) {
      OrderMessage order = request.toOrderMessage();
      standardScenarioService.sendOneway(order);
      return Result.success();
  }
  ```
- **核心机制与边界防御**：
  `sendAsync` 返回原生 `CompletableFuture<SendReceipt>`，天然适配反应式或非阻塞响应式流水线；`sendOneway` 在底层 gRPC 客户端以单向 Fire-and-Forget 模式投递，不等待 Broker 响应应答，极大降低客户端等待延时，专用于海量非交易埋点数据收集。

---

## 3. 保序消息与批量切片套件 (FIFO & Batch Slicing Suite)

### 3.1 局部严格保序消息投递 (FIFO MessageGroup)
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        producer:
          topics:
            - sample_fifo_topic            # 开启 FIFO 的主题
  ```
- **业务 Java 示例**（逆向提取自 `RocketmqProduceController.java`）：
  ```java
  @PostMapping("/fifo")
  public Result<OrderProduceResponse> sendFifo(@Valid @RequestBody OrderProduceRequest request) {
      OrderMessage order = request.toOrderMessage();
      String messageGroup = StringUtils.hasText(request.getMessageGroup()) ? request.getMessageGroup() : order.getOrderId();
      SendReceipt receipt = standardScenarioService.sendFifo(order, messageGroup);
      OrderProduceResponse response = OrderProduceResponse.builder()
              .orderId(order.getOrderId())
              .messageId(receipt.getMessageId().toString())
              .tag("fifo")
              .status("FIFO_SUCCESS")
              .build();
      return Result.success(response);
  }
  ```
- **核心机制与边界防御**：
  基于 RocketMQ 5.x 规范使用 `messageGroup`。相同 `messageGroup` 的消息必定被哈希至相同的 MessageQueue 队列分区，由消费端单线程严格按序消费；不同 `messageGroup` 之间保持完全并发，兼顾顺序保序与水平吞吐扩展。

### 3.2 批量发信智能切片 (sendBatch)
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        producer:
          max-batch-size: 500              # 批量发信单次分片上限（默认 500 条）
  ```
- **业务 Java 示例**（逆向提取自 `RocketmqProduceController.java`）：
  ```java
  @PostMapping("/batch")
  public Result<BatchProduceResponse> sendBatch(@Valid @RequestBody ValidList<OrderProduceRequest> requests) {
      List<OrderMessage> orders = new ArrayList<>(requests.size());
      for (OrderProduceRequest req : requests) {
          orders.add(req.toOrderMessage());
      }
      List<SendReceipt> receipts = standardScenarioService.sendBatch(orders, "batch");
      return Result.success(BatchProduceResponse.builder()
              .total(orders.size())
              .successCount(receipts.size())
              .orderIds(orders.stream().map(OrderMessage::getOrderId).toList())
              .build());
  }
  ```
- **核心机制与边界防御**：
  当批量消息数量超过 `producer.max-batch-size` 时，底层通过 `BatchSliceUtil` 自动执行切片分包，多批次并发投递并汇聚最终回执列表，彻底杜绝单次批量报文超出 Broker 单包上限而被直接拒绝。

---

## 4. 延时调度与精准撤回套件 (Delay Scheduling & Recall Suite)

### 4.1 相对时长延时与绝对时间定时投递
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        producer:
          topics:
            - sample_delay_topic
  ```
- **业务 Java 示例**（逆向提取自 `RocketmqDelayController.java`）：
  ```java
  // 相对时长延时投递（例如 60 秒后关单）
  @PostMapping("/duration")
  public Result<OrderDelayResponse> sendDelayed(@Valid @RequestBody OrderDelayRequest request) {
      OrderMessage order = request.toOrderMessage();
      long seconds = request.resolveDelaySeconds();
      Duration delay = Duration.ofSeconds(seconds);
      SendReceipt receipt = batchDelayScenarioService.sendDelayed(order, delay);

      OrderDelayResponse response = OrderDelayResponse.builder()
              .orderId(order.getOrderId())
              .messageId(receipt.getMessageId().toString())
              .delaySeconds(seconds)
              .deliverAtTimestamp(System.currentTimeMillis() + delay.toMillis())
              .recallHandle(receipt.getRecallHandle())
              .status("DELAY_SCHEDULED")
              .build();
      return Result.success(response);
  }

  // 绝对时间定时调度
  @PostMapping("/deliver-at")
  public Result<OrderDelayResponse> sendDeliverAt(@Valid @RequestBody OrderDelayRequest request) {
      OrderMessage order = request.toOrderMessage();
      long targetTimestamp = request.resolveDeliverAtTimestamp();
      Instant deliverAt = Instant.ofEpochMilli(targetTimestamp);
      SendReceipt receipt = batchDelayScenarioService.sendDeliverAt(order, deliverAt);

      OrderDelayResponse response = OrderDelayResponse.builder()
              .orderId(order.getOrderId())
              .messageId(receipt.getMessageId().toString())
              .deliverAtTimestamp(targetTimestamp)
              .recallHandle(receipt.getRecallHandle())
              .status("SCHEDULED_SUCCESS")
              .build();
      return Result.success(response);
  }
  ```
- **核心机制与边界防御**：
  RocketMQ 5.x 原生支持毫秒级精度的任意定时延时调度，无需受限于 4.x 固定的 18 个延时等级。前置校验拦截已过期时间戳与非法负数时长，确保调度时间处于合理生产时间窗口。

### 4.2 原生延时消息精准撤回 (`recallMessage`)
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        producer:
          send-timeout: 3s
  ```
- **业务 Java 示例**（逆向提取自 `RocketmqDelayController.java`）：
  ```java
  @PostMapping("/recall")
  public Result<Boolean> recallDelayedMessage(@Valid @RequestBody OrderRecallRequest request) {
      String topic = StringUtils.hasText(request.getTopic()) ? request.getTopic() : TradeRocketmqProducer.TOPIC_DELAY;
      batchDelayScenarioService.recallDelayedMessage(topic, request.getRecallHandle());
      return Result.success(true);
  }
  ```
- **核心机制与边界防御**：
  对于未到期的延时消息，如果业务状态发生翻转（如用户在倒计时内完成支付），调用方无需等待延时到达后再由消费端做防御，可直接凭发送回执中的 `recallHandle` 在 Broker 端将其物理撤回，极大减轻消费端无效唤醒与计算压力。

---

## 5. 2PC 分布式事务半消息与统一回查分发架构 (2PC Transaction Suite)

### 5.1 2PC 半消息发信与本地决断执行
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        producer:
          topics:
            - sample_tx_topic
  ```
- **业务 Java 示例**（逆向提取自 `RocketmqTransactionController.java`）：
  ```java
  @PostMapping("/half-message")
  public Result<OrderProduceResponse> sendHalfMessage(@Valid @RequestBody OrderTransactionRequest request) {
      OrderMessage order = request.toOrderMessage();
      boolean success = request.getLocalSuccess() == null || request.getLocalSuccess();
      SendReceipt receipt = transactionScenarioService.createOrderWith2PcTransaction(order, success);
      OrderProduceResponse response = OrderProduceResponse.builder()
              .orderId(order.getOrderId())
              .messageId(receipt.getMessageId().toString())
              .tag("tx")
              .status(success ? "2PC_COMMITTED" : "2PC_ROLLED_BACK")
              .build();
      return Result.success(response);
  }
  ```
- **核心机制与边界防御**：
  执行标准 RocketMQ 5.x 2PC 两阶段协议：先投递事务半消息，Broker 持久化后回调业务实现的 `RocketmqLocalTransactionExecutor.execute(sendReceipt)`；本地事务执行成功返回 `COMMIT`，失败返回 `ROLLBACK`，网络挂起或异常返回 `UNKNOWN`。执行抛出异常时框架自动降级决断 `ROLLBACK`，防止半消息无休止悬挂。

### 5.2 领域解耦多 Topic 回查分发架构 (`RocketmqLocalTransactionChecker`)
- **业务 Java 示例**（逆向提取自 `SampleOrderTransactionChecker.java`）：
  ```java
  @Component
  @Slf4j
  public class SampleOrderTransactionChecker implements RocketmqLocalTransactionChecker {
      @Getter
      private final Set<String> committedOrders = ConcurrentHashMap.newKeySet();
      @Getter
      private final Set<String> rolledBackOrders = ConcurrentHashMap.newKeySet();

      @Override
      public String getTopic() {
          return TradeRocketmqProducer.TOPIC_TX;
      }

      @Override
      public TransactionResolution check(MessageView messageView) {
          String bizKey = resolveBizKey(messageView);
          if (committedOrders.contains(bizKey)) {
              return TransactionResolution.COMMIT;
          }
          if (rolledBackOrders.contains(bizKey)) {
              return TransactionResolution.ROLLBACK;
          }
          return TransactionResolution.UNKNOWN;
      }
  }
  ```
- **核心机制与边界防御**：
  框架内置单例 `RocketmqTransactionChecker`，应用启动时自动扫描所有实现 `RocketmqLocalTransactionChecker` 的业务 Bean；当 Broker 发起超时未决回查时，依据 `MessageView.getTopic()` 智能精准路由至对应领域 Checker，彻底消除传统 RocketMQ 微服务中单一臃肿的“上帝回查类”。

---

## 6. 本地事务感知发信与补偿防线 (Post-Commit Transaction Guard)

### 6.1 `sendAfterCommit` 提交感知与回滚防幽灵消息
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        producer:
          send-timeout: 3s
  ```
- **业务 Java 示例**（逆向提取自 `RocketmqTransactionController.java`）：
  ```java
  @PostMapping("/send-after-commit")
  public Result<String> sendAfterCommit(@Valid @RequestBody OrderTransactionRequest request) {
      OrderMessage order = request.toOrderMessage();
      boolean success = request.getLocalSuccess() == null || request.getLocalSuccess();
      if (success) {
          transactionScenarioService.createOrderWithSendAfterCommit(order);
          return Result.success("TX_COMMITTED_AND_SENT");
      } else {
          try {
              transactionScenarioService.createOrderWithRollback(order);
          } catch (IllegalStateException ex) {
              log.warn("[TX-SAMPLE] 捕获预期的本地事务回滚异常: orderId={}, error={}", order.getOrderId(), ex.getMessage());
          }
          return Result.success("TX_ROLLED_BACK_ZERO_SEND");
      }
  }
  ```
- **核心机制与边界防御**：
  在 `@Transactional` 方法内调用 `sendAfterCommit` 时，消息投递逻辑挂载于 Spring `TransactionSynchronizationManager` 的 `afterCommit` 钩子。**本地事务回滚时钩子自动撤回作废，物理发信绝对零触发**，彻底终结了“数据库回滚但下游收到发信脏数据”的幽灵消息漏洞。

### 6.2 事务发信失败补偿扩展 (`RocketmqTransactionFailureCallback`)
- **业务 Java 示例**：
  ```java
  @Component
  @Slf4j
  public class OrderTransactionFailureCallback implements RocketmqTransactionFailureCallback {
      @Override
      public void onFailure(String topic, String tag, String bizKey, Object payload, String traceId, Throwable cause) {
          log.error("[TX-FALLBACK] 本地事务已提交但发信失败: topic={}, bizKey={}, traceId={}, cause={}",
                  topic, bizKey, traceId, cause.getMessage(), cause);
          // 写入本地可靠事件表或发送告警通知
      }
  }
  ```
- **核心机制与边界防御**：
  针对极端小概率的“本地数据库提交成功，但在 afterCommit 阶段触发发信时因网络闪断导致发信失败”场景，自动触发该 SPI 兜底通知，确保数据可补偿可对账。

---

## 7. 声明式监听容器与服务端过滤推断 (Declarative Consumer & Filter Suite)

### 7.1 服务端 Tag 逻辑或与 SQL92 属性过滤下推
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        consumer:
          consumption-thread-count: 20     # 默认消费并发线程数
          max-cache-message-count: 1024    # 客户端最大缓存消息条数
  ```
- **业务 Java 示例**（逆向提取自 `OrderStandardConsumer.java` 与 `OrderSqlConsumer.java`）：
  ```java
  // 1. Tag 过滤下推（支持 tagA || tagB 逻辑或）
  @RocketmqListener(
          topic = "sample_order_topic",
          consumerGroup = "sample_order_group",
          tag = "create || pay",
          endpointId = "sample_order_group#sample_order_topic"
  )
  public void onOrderMessage(@Valid OrderMessage order, MessageView messageView) {
      log.info("[SAMPLE-CONSUME] 接收订单消息: orderId={}, tag={}", order.getOrderId(), messageView.getTag().orElse(""));
  }

  // 2. SQL92 属性表达式过滤下推（服务端计算）
  @RocketmqListener(
          topic = "sample_sql_topic",
          consumerGroup = "sample_sql_group",
          sql92 = "amount > 100"
  )
  public void onLargeOrder(OrderMessage order) {
      log.info("[SQL-CONSUME] 接收到大额订单: orderId={}, amount={}", order.getOrderId(), order.getAmount());
  }
  ```
- **核心机制与边界防御**：
  过滤规则在 Broker 服务端计算执行，不符合条件的消息不会在网络层投递至客户端，大幅节约网络带宽与网卡吞吐。

### 7.2 微服务推断反序列化解耦与 JSR-303 快速失败
- **核心机制说明**：
  消费端依据监听方法形参类型与反射泛型（支持原生 `MessageView`、`byte[]`、`String`、业务 DTO 及泛型集合 `` `List<T>` ``）动态推断反序列化，彻底摆脱发送端类全限定名的耦合枷锁。配合 `@Valid` 注解在消费第一道防线实现 Fail-Fast 校验。

---

## 8. 流式微批拉取与租约控制套件 (SimpleConsumer & Lease Suite)

### 8.1 微批长轮询流式拉取与不可见租约续期
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        simple-consumer:
          invisible-duration: 30s          # 默认不可见租约时长（默认 30 秒）
          max-message-num: 32              # 单批次最大拉取条数
          await-duration: 5s               # 长轮询挂起等待时长
  ```
- **业务 Java 示例**（逆向提取自 `RocketmqSimpleConsumerController.java`）：
  ```java
  // 1. 微批拉取消息并暂存句柄
  @PostMapping("/pull")
  public Result<List<SimpleMessageVO>> pullMessages(@Valid @RequestBody(required = false) SimplePullRequest request) {
      SimplePullRequest req = request != null ? request : new SimplePullRequest();
      List<MessageView> messages = simpleConsumerPullScenarioService.pullMessages(
              req.resolveTopic(), req.resolveGroup(), req.resolveTag(), req.resolveMaxNum(), req.resolveInvisibleDuration());
      List<SimpleMessageVO> vos = messages.stream().map(SimpleConsumerPullScenarioService::convertToVO).toList();
      return Result.success(vos);
  }

  // 2. 动态续期不可见租约
  @PostMapping("/renew")
  public Result<String> renewLease(@Valid @RequestBody SimpleRenewRequest request) {
      simpleConsumerPullScenarioService.renewLease(request.getGroup(), request.getMessageId(), request.resolveDuration());
      return Result.success("租约续期成功: messageId=" + request.getMessageId());
  }
  ```
- **核心机制与边界防御**：
  拉取后的消息在 `invisibleDuration` 时长内对同组其他实例隐身。对于重型批处理任务，通过 `changeInvisibleDuration` 动态延长租约，杜绝因处理未完成导致消息被重新拉取并发重复执行。

### 8.2 显式确认出队 (ACK) 与函数式一步闭包消费
- **业务 Java 示例**（逆向提取自 `RocketmqSimpleConsumerController.java`）：
  ```java
  // 1. 显式确认出队 (ACK)
  @PostMapping("/ack")
  public Result<String> acknowledge(@Valid @RequestBody SimpleAckRequest request) {
      simpleConsumerPullScenarioService.acknowledge(request.getGroup(), request.getMessageId());
      return Result.success("消息确认出队成功: messageId=" + request.getMessageId());
  }

  // 2. 高阶函数式闭包受控消费
  @PostMapping("/consume")
  public Result<Integer> consumeAndExecute(@Valid @RequestBody(required = false) SimplePullRequest request) {
      SimplePullRequest req = request != null ? request : new SimplePullRequest();
      int executed = simpleConsumerPullScenarioService.pullAndExecuteOrders(
              req.resolveTopic(), req.resolveGroup(), req.resolveTag(), req.resolveMaxNum(), req.resolveInvisibleDuration());
      return Result.success(executed);
  }
  ```
- **核心机制与边界防御**：
  `ack` 成功后原消息彻底出队并清理句柄；`receiveAndExecute` 将“拉取 -> 上下文还原 -> 推断反序列化 -> 业务执行 -> 成功自动确认”封装为一步原子闭包，执行异常自动放弃确认触发租约到期重试。

---

## 9. 消费端容器运维与死信受控治理套件 (Ops & DLQ Governance Suite)

### 9.1 消费监听容器动态削峰暂停与物理优雅启停
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        consumer:
          close-timeout: 15s               # 容器优雅停止排空超时时间
  ```
- **业务 Java 示例**（逆向提取自 `RocketmqContainerController.java`）：
  ```java
  // 查询全量端点元数据视图
  @GetMapping("/endpoints")
  public Result<List<ConsumerEndpointVO>> getEndpoints() {
      return Result.success(containerOpsService.getEndpoints().stream()
              .map(ConsumerEndpointVO::from)
              .toList());
  }

  // 动态暂停削峰
  @PostMapping("/pause")
  public Result<Boolean> pauseEndpoint(@Valid @RequestBody ContainerControlRequest request) {
      return Result.success(containerOpsService.pauseEndpoint(request.getEndpointId()));
  }

  // 恢复消费
  @PostMapping("/resume")
  public Result<Boolean> resumeEndpoint(@Valid @RequestBody ContainerControlRequest request) {
      return Result.success(containerOpsService.resumeEndpoint(request.getEndpointId()));
  }
  ```
- **核心机制与边界防御**：
  暂停后 PushConsumer 保持底层连接心跳，但内部 Invoker 收到消息后直接返回 `ConsumeResult.FAILURE`，促使 Broker 进行阶梯退避重试，实现零内存/线程压力的毫秒级极速削峰；支持运行期不重启应用优雅启停物理客户端。

### 9.2 死信队列只读探查、受控重放与防二次雪崩熔断
- **专属 YAML 配置**：
  ```yaml
  ateng:
    boot:
      rocketmq:
        dead-letter:
          max-replay-attempts: 3           # 最大累计重放次数（防二次雪崩熔断）
          default-batch-size: 32           # 默认批次重放大小
          max-batch-size: 100              # 最大单批治理上限
          invisible-duration: 30s          # 探查与重放不可见租约
          discard-on-max-attempts-blocked: false # 达到熔断上限时是否丢弃
  ```
- **业务 Java 示例**（逆向提取自 `RocketmqDeadLetterController.java`）：
  ```java
  // 1. 只读探查死信 (零 ACK 保持现场)
  @PostMapping("/peek")
  public Result<List<DeadLetterMessageVO>> peekDeadLetters(@Valid @RequestBody DeadLetterPeekRequest request) {
      List<MessageView> messages = deadLetterService.peekDeadLetters(request.getConsumerGroup(), request.resolveLimit());
      return Result.success(messages.stream().map(DeadLetterReplayScenarioService::convertToMessageVO).toList());
  }

  // 2. 受控重放死信至业务主题（支持目标分流与防二次雪崩熔断）
  @PostMapping("/replay")
  public Result<DeadLetterReplayVO> replayDeadLetters(@Valid @RequestBody DeadLetterReplayRequest request) {
      DeadLetterReplayResult result = StringUtils.hasText(request.getTargetTopic())
              ? deadLetterService.replayDeadLettersToTopic(request.getConsumerGroup(), request.resolveLimit(), request.getTargetTopic().trim())
              : deadLetterService.replayDeadLetters(request.getConsumerGroup(), request.resolveLimit());
      return Result.success(DeadLetterReplayScenarioService.convertToReplayVO(result));
  }

  // 3. 批量物理确认丢弃
  @PostMapping("/discard")
  public Result<Integer> discardDeadLetters(@Valid @RequestBody DeadLetterDiscardRequest request) {
      return Result.success(deadLetterService.discardDeadLetters(request.getConsumerGroup(), request.resolveLimit()));
  }
  ```
- **核心机制与边界防御**：
  探查过程绝不触发 ACK，租约到期后自动恢复；重放时自动在 Header 累加 `X-Dead-Letter-Replay-Count`，达到阈值强制熔断拦截，杜绝死循环雪崩；彻底清洗内部重试协议头，重投成功后源端原子 ACK。

---

## 10. 高韧性生产安全防线与完整配置清单 (Production Hardening & Config Appendix)

### 10.1 毒丸不可自愈异常安全丢弃与防重幂等静默确认
- **毒丸判定机制**：报文破坏 JSR-303、参数致命错误或反序列化失败时，框架自动判定为毒丸报文，输出 `[MQ-FATAL-POISON-PILL]` 告警并向 Broker 返回 `ConsumeResult.SUCCESS` 确认丢弃，彻底消除 16 次无脑重试风暴；
- **防重幂等静默确认**：业务方法抛出 `IdempotentException` 时，框架记录 `[MQ-IDEMPOTENT-DUPLICATE]` 警告并安全返回 `SUCCESS`，实现静默出队，阻止服务端重复重发。

### 10.2 4MB 大报文前置防御与 4KB 透明 GZIP 压缩
- **超限 Fail-Fast**：单包报文体积超出 `producer.max-payload-size`（默认 4MB）立即抛出 `MessageDeliveryException`，阻断大包进入网络；
- **透明压缩与解压**：体积超出 `producer.compression-threshold`（默认 4KB）自动触发 GZIP 压缩与解压，对业务代码完全透明。

### 10.3 `SmartLifecycle` 确定性停机排空时序
容器实现 Spring `SmartLifecycle`，应用停机时优先关闭所有 `PushConsumer`（停止拉取、排空在途消费），再安全关闭 `SimpleConsumer` 缓存，最后销毁受管 `Producer` 实例，彻底保障零丢信平滑停机发布。

### 10.4 完整 YAML 配置属性清单 (全量速查附录)

```yaml
ateng:
  boot:
    rocketmq:
      enabled: true                        # 是否启用 RocketMQ Starter（默认 true）
      endpoints: "127.0.0.1:8081"          # RocketMQ 5.x gRPC Proxy 地址
      access-key: ""                       # 鉴权 AccessKey
      secret-key: ""                       # 鉴权 SecretKey
      ssl-enabled: false                   # 是否开启 TLS/SSL 加密
      request-timeout: 3s                  # 客户端 gRPC 请求全局超时时间
      producer:
        send-timeout: 3s                   # 生产端发信超时时间
        max-attempts: 3                    # 失败重试次数
        max-batch-size: 500                # 批量发信单次分片最大数量
        max-payload-size: 4MB              # 单包报文最大体积上限（超出 Fail-Fast 阻断）
        compression-threshold: 4KB         # GZIP 透明压缩阈值
        topics:                            # 预加载生产主题列表
          - sample_order_topic
          - sample_delay_topic
          - sample_fifo_topic
          - sample_tx_topic
          - sample_sql_topic
      consumer:
        consumption-thread-count: 20       # 消费监听端点默认并发线程数
        max-cache-message-count: 1024      # 客户端最大缓存消息数量
        close-timeout: 15s                 # 容器停机优雅关闭等待超时
      simple-consumer:
        invisible-duration: 30s            # 默认不可见租约时长
        max-message-num: 32                # 单批次最大拉取数量
        await-duration: 5s                 # 长轮询挂起等待时长
      dead-letter:
        max-replay-attempts: 3             # 死信累计重放上限（防二次雪崩熔断防线）
        default-batch-size: 32             # 默认死信治理批次大小
        max-batch-size: 100                # 最大单批死信治理上限
        invisible-duration: 30s            # 死信探查与重放不可见租约时长
        discard-on-max-attempts-blocked: false # 达到熔断上限时是否丢弃
```
