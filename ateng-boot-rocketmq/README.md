# ateng-boot-rocketmq 生产增强与原生分布式事务 Starter

> 基于 Apache RocketMQ 5.x 官方 Java SDK (Shaded gRPC 瘦客户端) 与 JDK 17/21+ 原生能力构建的工业级消息通信与分布式事务底座 Starter。提供生产级统一受管高阶发信门面（`RocketmqTemplate`）、流式拉取与租约控制门面（`RocketmqSimpleConsumerTemplate`）、运行期消费容器动态流控运维门面（`RocketmqContainerManager`）、死信队列治理与受控重放门面（`RocketmqDeadLetterReplayTemplate`）、毫秒级延时与定时发信、定时延时消息原生精准撤回（`recallMessage`）、批量自动容量切片（`sendBatch`）、FIFO 顺序保序投递、原生 2PC 分布式事务半消息与领域解耦统一回查分发架构（`RocketmqLocalTransactionChecker`）、本地事务提交感知发信（`sendAfterCommit` + 补偿 SPI 杜绝幽灵消息）、声明式事件驱动监听容器（`@RocketmqListener`）、微服务推断反序列化解耦、4MB 大报文防御与透明 GZIP 压缩、消费端毒丸安全丢弃与 `@Idempotent` 幂等静默确认（彻底消灭 16 次无脑重试风暴）、`SmartLifecycle` 确定性停机排空以及 100% 内存自洽双模测试沙箱。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.rocketmq.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **生产端高阶门面** | 可靠发信统一门面 (普通/延时/定时/FIFO/事务/批量) | `RocketmqTemplate` | `enabled`<br>`endpoints`<br>`ssl-enabled`<br>`request-timeout`<br>`producer.send-timeout`<br>`producer.max-attempts`<br>`producer.max-batch-size`<br>`producer.max-payload-size`<br>`producer.compression-threshold`<br>`producer.topics` | `send(topic, [tag], [bizKey], payload, [userProperties])`<br>`sendAsync(topic, [tag], [bizKey], payload, [userProperties])`<br>`sendOneway(topic, [tag], [bizKey], payload, [userProperties])`<br>`sendDelayed(topic, [tag], [bizKey], payload, delay, [userProperties])`<br>`sendDelayedAsync(topic, [tag], [bizKey], payload, delay, [userProperties])`<br>`sendDeliverAt(topic, [tag], [bizKey], payload, deliverAt, [userProperties])`<br>`sendDeliverAtAsync(topic, [tag], [bizKey], payload, deliverAt, [userProperties])`<br>`recallMessage(topic, recallHandle)`<br>`recallMessageAsync(topic, recallHandle)`<br>`sendOrderly(topic, [tag], [bizKey], payload, messageGroup, [userProperties])`<br>`sendOrderlyAsync(topic, [tag], [bizKey], payload, messageGroup, [userProperties])`<br>`sendTransaction(topic, [tag], [bizKey], payload, executor, [userProperties])`<br>`sendAfterCommit(topic, [tag], [bizKey], payload, [userProperties])`<br>`sendBatch(topic, [tag], payloads, [userProperties])`<br>`sendBatchAsync(topic, [tag], payloads, [userProperties])`<br>`getProducer()`：同步阻塞确认、异步非阻塞返回 `CompletableFuture`、单向日志发信、毫秒级定时调度、原生延时精准撤回、MessageGroup 顺序保序、2PC 事务半消息、本地事务提交感知发信、批量超限智能切片、多级 bizKey 兜底、Topic 正则防卫、4MB 报文超限 Fail-Fast、4KB GZIP 透明压缩、四维上下文透明注入与 `[MQ-SEND]` 结构化宏日志 | `RocketmqTemplateTest` / `SampleRocketmqE2eTest` |
| **流式拉取门面** | 消费端 SimpleConsumer 租约拉取与流控 | `RocketmqSimpleConsumerTemplate` | `simple-consumer.invisible-duration`<br>`simple-consumer.max-message-num`<br>`simple-consumer.await-duration` | `receive(topic, consumerGroup, [tag], [maxNum], [invisibleDuration])`<br>`receiveAsync(topic, consumerGroup, [tag], [maxNum], [invisibleDuration])`<br>`ack(consumerGroup, messageView)`<br>`ackAsync(consumerGroup, messageView)`<br>`changeInvisibleDuration(consumerGroup, messageView, invisibleDuration)`<br>`changeInvisibleDurationAsync(consumerGroup, messageView, invisibleDuration)`<br>`receiveAndExecute(topic, consumerGroup, [tag], [maxNum], [invisibleDuration], targetType, consumer)`<br>`getOrCreateConsumer(topic, consumerGroup, tag)`：基于官方 Pop 机制的长轮询微批流式拉取、动态隐身不可见租约续期、显式 ACK 确认、高阶推断反序列化与函数式闭包受控消费 | `RocketmqSimpleConsumerTemplateTest` / `SampleRocketmqE2eTest` |
| **容器运维管控门面** | 消费监听容器动态流控与生命周期运维 | `RocketmqContainerManager` | `consumer.consumption-thread-count`<br>`consumer.max-cache-message-count`<br>`consumer.close-timeout` | `getEndpointIds()`<br>`getEndpoints()`<br>`getEndpoint(id)`<br>`findEndpoints(idOrTopic)`<br>`pause(idOrTopic)`<br>`resume(idOrTopic)`<br>`pauseAll()`<br>`resumeAll()`<br>`isPaused(idOrTopic)`<br>`start(idOrTopic)`<br>`stop(idOrTopic)`<br>`startAll()`<br>`stopAll()`<br>`isRunning(idOrTopic)`：全量端点审计可见性、运行期大促流量极速削峰（返回 FAILURE 促使 Broker 阶梯退避）、不重启物理优雅启停 PushConsumer 底层连接 | `RocketmqContainerManagerTest` / `SampleRocketmqE2eTest` |
| **死信治理门面** | 死信队列只读探查、受控重放与丢弃清理 | `RocketmqDeadLetterReplayTemplate` | `dead-letter.max-replay-attempts`<br>`dead-letter.default-batch-size`<br>`dead-letter.max-batch-size`<br>`dead-letter.invisible-duration`<br>`dead-letter.discard-on-max-attempts-blocked` | `getDlqTopic(consumerGroup)`<br>`peekDeadLetters(consumerGroup, [maxNum], [invisibleDuration])`<br>`replayDeadLetters(consumerGroup, [maxNum], [targetTopic], [invisibleDuration])`<br>`discardDeadLetters(consumerGroup, [maxNum])`<br>`discardDeadLetter(consumerGroup, messageView)`<br>`getSimpleConsumerTemplate()`<br>`getRocketmqTemplate()`：死信主题 `%DLQ%<Group>` 自动映射、零 ACK 只读安全探查、受控限流原子重放至业务主题、内部重试头安全清洗、全链路审计打标、防二次雪崩熔断拦截与确定性源端 ACK 确认、批量与单条对称清理丢弃 | `RocketmqDeadLetterReplayTemplateTest` / `SampleRocketmqE2eTest` |
| **原生 2PC 分布式事务** | 统一事务回查分发器与领域解耦 SPI | `RocketmqTransactionChecker`<br>`RocketmqLocalTransactionChecker` | - | `check(messageView)`：统一实现 RocketMQ 5.x 官方 `TransactionChecker`，容器启动时自动发现业务 Checker，根据 Topic 动态策略路由至对应业务实现，决断 `COMMIT` / `ROLLBACK` / `UNKNOWN`，彻底消除微服务单体上帝类 | `RocketmqTransactionCheckerTest` / `SampleRocketmqE2eTest` |
| **本地事务执行器** | 本地业务事务决断回调函数式接口 | `RocketmqLocalTransactionExecutor` | - | `execute(sendReceipt)`：在 2PC 事务半消息成功投递后被回调执行，根据本地数据库或业务处理结果返回 `TransactionResolution`（`COMMIT` / `ROLLBACK` / `UNKNOWN`），执行异常自动触发回滚 | `RocketmqTemplateTest` / `SampleRocketmqE2eTest` |
| **事务发信补偿** | 本地事务提交后发信失败补偿 SPI | `RocketmqTransactionFailureCallback` | - | `onFailure(topic, tag, bizKey, payload, traceId, cause)`：当 `sendAfterCommit` 在本地数据库事务提交后因网络断连导致发信失败时被触发，供业务写入本地补偿表或推送到报警平台 | `RocketmqTemplateTest` |
| **声明式监听注解** | 消费端声明式事件驱动监听注解 | `@RocketmqListener` | - | `@RocketmqListener(topic = "...", consumerGroup = "...", tag = "*", sql92 = "", endpointId = "", consumptionThreadCount = 0, maxCacheMessageCount = 0)`：标注在 Spring 受管 Bean 的消费处理方法上，支持属性占位符表达式、Tag 过滤（支持 `tagA || tagB` 逻辑或）、SQL92 属性过滤下推与端点 ID 显式命名 | `RocketmqConsumerContainerTest` / `SampleRocketmqE2eTest` |
| **消费端受管容器** | 监听容器与确定性生命周期管理器 | `RocketmqConsumerContainer` | `consumer.consumption-thread-count`<br>`consumer.max-cache-message-count`<br>`consumer.close-timeout` | 实现 `SmartInitializingSingleton`、`SmartLifecycle`、`DisposableBean`：自动扫描注册监听端点、严格校验 Topic 命名正则（`^[%a-zA-Z0-9_-]+$`，坚决杜绝点号）；停机时优先关闭所有 `PushConsumer` 实例（受控于 `closeTimeout` 超时排空在途消费），保障平滑发布 | `RocketmqConsumerContainerTest` / `SampleRocketmqE2eTest` |
| **客户端工厂抽象** | PushConsumer 构造工厂与测试替身 SPI | `PushConsumerFactory` | - | `createPushConsumer(clientConfig, group, topic, filter, listener, threads, cacheCount)`：解耦底层物理 gRPC 客户端构建逻辑，支持生产环境默认工厂 `DefaultPushConsumerFactory` 与测试环境内存替身 `InMemoryRocketmqBroker` 无缝拔插切换 | `RocketmqConsumerContainerTest` / `SampleRocketmqE2eTest` |
| **流式拉取工厂抽象** | SimpleConsumer 构造工厂与测试替身 SPI | `SimpleConsumerFactory` | - | `createSimpleConsumer(clientConfig, group, awaitDuration, subscriptions)`：解耦底层物理 SimpleConsumer 构建逻辑，支持生产环境默认工厂 `DefaultSimpleConsumerFactory` 与测试环境内存替身 `InMemoryRocketmqBroker` 拔插切换 | `RocketmqSimpleConsumerTemplateTest` / `SampleRocketmqE2eTest` |
| **消费调用执行器** | 微服务推断反序列化与受控防卫核心 | `RocketmqListenerInvoker` | - | 实现 `MessageListener`：瞬态四维上下文入站还原（`MDC traceId`, `TenantContextHolder`, `UserContextHolder`）与 `finally` 现场清理；透明 GZIP 解压；依据方法参数类型与 `ParameterizedType` 精准推断反序列化（支持原生 `MessageView`、`byte[]`、`String`、业务 DTO 及泛型集合 `` `List<T>` ``）；JSR-303 参数校验；不可自愈毒丸快速丢弃返回 `SUCCESS`；`IdempotentException` 幂等冲突静默确认返回 `SUCCESS`；瞬态异常返回 `FAILURE` 触发服务端重试 | `RocketmqListenerInvokerTest` / `SampleRocketmqE2eTest` |
| **消息辅助工具** | 序列化、四维上下文与 bizKey 解析工具 | `RocketmqMessageHelper` | - | `resolveBizKey(bizKey, payload)`：多级 bizKey 兜底（显式指定 -> 反射提取 `getId()`/`getBizKey()`/`getOrderNo()`/`getCode()`/`getKey()` -> MDC traceId 兜底 -> UUID）；`extractBizKey(messageView)`：入站消息对称提取业务标识（优先 `X-Biz-Key`，兜底原生 Keys）；`toPayloadBytes(payload)`：纯净 JSON 序列化；`compressGzip(bytes)` / `decompressGzip(bytes)`：透明压缩与解压 | `RocketmqMessageHelperTest` |
| **配置属性与元数据** | 强类型配置属性类与 IDE 智能补全 | `AtengRocketmqProperties` | `ateng.boot.rocketmq.*` | 包含 `enabled`、`endpoints`、`access-key`、`secret-key`、`ssl-enabled`、`request-timeout`（`Duration`）、`producer.send-timeout`（`Duration`）、`producer.max-attempts`、`producer.max-batch-size`、`producer.max-payload-size`（`DataSize`）、`producer.compression-threshold`（`DataSize`）、`producer.topics`、`consumer.consumption-thread-count`、`consumer.max-cache-message-count`、`consumer.close-timeout`（`Duration`）、`simple-consumer.invisible-duration`（`Duration`）、`simple-consumer.max-message-num`、`simple-consumer.await-duration`（`Duration`）、`dead-letter.max-replay-attempts`、`dead-letter.default-batch-size`、`dead-letter.max-batch-size`、`dead-letter.invisible-duration`（`Duration`）、`dead-letter.discard-on-max-attempts-blocked` | `AtengRocketmqPropertiesTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-rocketmq` 是面向企业级高可靠交易通信、原生 2PC 分布式事务履约与事件驱动架构的生产就绪 Starter：
- **纯净官方 Shaded 瘦客户端**：100% 依托官方 `rocketmq-client-java:5.2.2` Shaded 坐标与 JDK 17 原生能力，物理隔绝 gRPC、Netty 与老旧 Remoting 依赖污染；
- **全场景受管高阶门面**：
  - `RocketmqTemplate`：同步/异步/单向可靠发信、毫秒级延时与定时发信、定时延时消息原生精准撤回、批量超限自动切片、FIFO 顺序保序发信与 2PC 分布式事务半消息；
  - `RocketmqSimpleConsumerTemplate`：官方 SimpleConsumer 流式拉取微批长轮询、不可见租约动态续期与高阶函数式反序列化消费；
  - `RocketmqContainerManager`：运行期消费端点审计、动态暂停/恢复削峰与物理启停；
  - `RocketmqDeadLetterReplayTemplate`：死信只读探查（零 ACK）、受控重放、协议头安全清洗与防二次雪崩熔断；
- **原生 2PC 分布式事务统一回查**：内置全局 `RocketmqTransactionChecker`，通过 `RocketmqLocalTransactionChecker` SPI 将 Broker 事务回查精准路由到业务组件，彻底消除单体上帝类；
- **本地事务提交感知发信**：`sendAfterCommit` 严格保障数据库事务物理 Commit 成功后再投递消息，事务回滚时绝对零发信，并配属失败补偿 SPI；
- **微服务推断反序列化解耦**：依据监听器参数强类型与泛型反射自动还原 Java 实体，彻底摆脱发送端类全限定名的耦合枷锁，杜绝 `LinkedHashMap` 强转异常；
- **高韧性生产安全防线**：不可自愈毒丸记录 `[MQ-FATAL-POISON-PILL]` 安全丢弃返回 SUCCESS；`@Idempotent` 防重冲突记录 `[MQ-IDEMPOTENT-DUPLICATE]` 静默确认；4MB 硬上限拦截与 4KB 透明压缩保护 Broker 内存与网络；
- **确定性停机排空**：实现 Spring `SmartLifecycle`，应用停机时优先关闭所有 PushConsumer 实例（排空在途消费），再安全关闭 SimpleConsumer 缓存与受管 Producer，保障平滑发布；
- **100% 离线自洽双模测试沙箱**：基于纯应用层 `PushConsumerFactory` 与 `SimpleConsumerFactory` SPI，由 `InMemoryRocketmqBroker` 提供全场景测试替身，零外部网络与 Docker 依赖，秒级全绿。

### Maven 坐标引入
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-rocketmq</artifactId>
</dependency>
```

---

## 2. 维度一：最小起步体验 (Happy Path & Out-of-the-Box)

### 场景痛点
使用原生客户端时，每个微服务发送事务消息被迫编写全局单一的 TransactionChecker 上帝类；本地事务内部直接发信容易产生幽灵脏数据；畸形报文进入队列后导致服务端 16 次无脑重试风暴；微服务间包名不同导致反序列化崩溃；运维缺乏动态削峰与死信重放的受控手段。

### 最小可用配置 (`application.yml`)
```yaml
spring:
  application:
    name: ateng-boot-sample-rocketmq
  config:
    import: "optional:classpath:application-local.yml"

ateng:
  boot:
    rocketmq:
      enabled: true
      endpoints: "127.0.0.1:8081"
      ssl-enabled: false
      request-timeout: 3s
      producer:
        send-timeout: 3s
        max-attempts: 3
        max-batch-size: 500
        max-payload-size: 4MB
        compression-threshold: 4KB
        topics:
          - sample_order_topic
          - sample_delay_topic
          - sample_fifo_topic
          - sample_tx_topic
          - sample_sql_topic
      consumer:
        consumption-thread-count: 20
        max-cache-message-count: 1024
        close-timeout: 15s
      simple-consumer:
        invisible-duration: 30s
        max-message-num: 32
        await-duration: 5s
      dead-letter:
        max-replay-attempts: 3
        default-batch-size: 32
        max-batch-size: 100
        invisible-duration: 30s
        discard-on-max-attempts-blocked: false
```

### 开箱极简代码示例

#### 1. 业务实体定义 (`OrderMessage.java`)
```java
package io.github.atengk.boot.sample.rocketmq.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
```

#### 2. 标准可靠发信服务 (`StandardRocketmqScenarioService.java`)
```java
package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class StandardRocketmqScenarioService {

    private final RocketmqTemplate rocketmqTemplate;

    public SendReceipt sendSync(OrderMessage order) {
        return sendSync(order, "create");
    }

    public SendReceipt sendSync(OrderMessage order, String tag) {
        log.info("[STANDARD-SCENARIO] 同步发送订单消息: orderId={}, tag={}", order.getOrderId(), tag);
        return rocketmqTemplate.send(TradeRocketmqProducer.TOPIC_ORDER, tag, order.getOrderId(), order);
    }

    public CompletableFuture<SendReceipt> sendAsync(OrderMessage order) {
        log.info("[STANDARD-SCENARIO] 异步发送订单消息: orderId={}", order.getOrderId());
        return rocketmqTemplate.sendAsync(TradeRocketmqProducer.TOPIC_ORDER, "create", order.getOrderId(), order);
    }

    public void sendOneway(OrderMessage order) {
        log.info("[STANDARD-SCENARIO] 单向发送订单埋点消息: orderId={}", order.getOrderId());
        rocketmqTemplate.sendOneway(TradeRocketmqProducer.TOPIC_ORDER, "oneway", order.getOrderId(), order);
    }

    public SendReceipt sendFifo(OrderMessage order, String messageGroup) {
        log.info("[STANDARD-SCENARIO] 顺序发送局部保序消息: orderId={}, messageGroup={}", order.getOrderId(), messageGroup);
        return rocketmqTemplate.sendOrderly(TradeRocketmqProducer.TOPIC_FIFO, "fifo", order.getOrderId(), order, messageGroup);
    }

    public SendReceipt sendSqlFiltered(OrderMessage order, double amount) {
        log.info("[STANDARD-SCENARIO] 发送携带 SQL92 自定义属性消息: orderId={}, amount={}", order.getOrderId(), amount);
        return rocketmqTemplate.send(TradeRocketmqProducer.TOPIC_SQL, "sql", order.getOrderId(), order, Map.of("amount", String.valueOf((long) amount)));
    }
}
```

#### 3. 消费端声明式监听器 (`OrderStandardConsumer.java`)
```java
package io.github.atengk.boot.sample.rocketmq.consumer;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.rocketmq.annotation.RocketmqListener;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderStandardConsumer {

    @RocketmqListener(
            topic = "sample_order_topic",
            consumerGroup = "sample_order_group",
            tag = "create || pay",
            endpointId = "ep-order-standard"
    )
    public void onOrderMessage(@Valid OrderMessage order, MessageView messageView) {
        String traceId = MDC.get("traceId");
        String tenantId = TenantContextHolder.getTenantId();
        String userId = UserContextHolder.getUserId();

        log.info("[SAMPLE-CONSUME] 接收订单消息: orderId={}, tag={}, traceId={}, tenantId={}, userId={}",
                order.getOrderId(),
                messageView.getTag().orElse(""),
                traceId,
                tenantId,
                userId);
    }
}
```

---

## 3. 维度二：生产扩展接管 (Customization & Extensibility)

### 扩展点一：实现 `RocketmqLocalTransactionChecker` 领域解耦事务回查
微服务中只需实现 `RocketmqLocalTransactionChecker` 接口并声明支持的主题（`getTopic()`），即可自动挂载到全局回查分发器，彻底消除单体上帝类：
```java
package io.github.atengk.boot.sample.rocketmq.checker;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.rocketmq.spi.RocketmqLocalTransactionChecker;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SampleOrderTransactionChecker implements RocketmqLocalTransactionChecker {

    private final Set<String> committedOrders = ConcurrentHashMap.newKeySet();
    private final Set<String> rolledBackOrders = ConcurrentHashMap.newKeySet();

    @Override
    public String getTopic() {
        return TradeRocketmqProducer.TOPIC_TX;
    }

    @Override
    public TransactionResolution check(MessageView messageView) {
        String bizKey = resolveBizKey(messageView);
        log.info("[SAMPLE-TX-CHECKER] 收到 Broker 事务半消息回查: topic={}, bizKey={}", getTopic(), bizKey);

        if (committedOrders.contains(bizKey)) {
            return TransactionResolution.COMMIT;
        }
        if (rolledBackOrders.contains(bizKey)) {
            return TransactionResolution.ROLLBACK;
        }
        return TransactionResolution.UNKNOWN;
    }

    private String resolveBizKey(MessageView messageView) {
        Map<String, String> properties = messageView.getProperties();
        if (properties != null && properties.containsKey(MessageHeaderConstants.BIZ_KEY)) {
            return properties.get(MessageHeaderConstants.BIZ_KEY);
        }
        if (messageView.getKeys() != null && !messageView.getKeys().isEmpty()) {
            return messageView.getKeys().iterator().next();
        }
        return "";
    }
}
```

### 扩展点二：实现 `RocketmqTransactionFailureCallback` 事务发信失败兜底
当使用 `rocketmqTemplate.sendAfterCommit` 时，若本地数据库事务提交成功但因网络不可用发信失败，框架将自动触发该 SPI 回调，由业务写入本地补偿表或推送到报警系统：
```java
@Component
@Slf4j
public class OrderTransactionFailureCallback implements RocketmqTransactionFailureCallback {

    @Override
    public void onFailure(String topic, String tag, String bizKey, Object payload, String traceId, Throwable cause) {
        log.error("[TX-FALLBACK] 本地事务已提交但消息投递失败: topic={}, bizKey={}, traceId={}, cause={}",
                topic, bizKey, traceId, cause.getMessage(), cause);

        // 写入本地可靠事件表或发送告警通知
    }
}
```

### 扩展点三：替换 `PushConsumerFactory` 与 `SimpleConsumerFactory` 自定义物理客户端或测试替身
通过声明同类型 Bean，平滑覆盖默认的客户端构建工厂：
```java
@Configuration(proxyBeanMethods = false)
public class CustomRocketmqFactoryConfig {

    @Bean
    public PushConsumerFactory customPushConsumerFactory() {
        return new DefaultPushConsumerFactory();
    }

    @Bean
    public SimpleConsumerFactory customSimpleConsumerFactory() {
        return new DefaultSimpleConsumerFactory();
    }
}
```

### 扩展点四：消费监听容器动态流控运维 (`ContainerOpsScenarioService.java`)
在大促流量激增、依赖服务降级或日常维护窗口期，通过 `RocketmqContainerManager` 对消费端点实施毫秒级动态流控，无需重启应用：
```java
@Service
@RequiredArgsConstructor
public class ContainerOpsScenarioService {

    private final RocketmqContainerManager containerManager;

    // 1. 查询所有受管端点标识
    public Set<String> getEndpointIds() {
        return containerManager.getEndpointIds();
    }

    // 2. 动态暂停指定端点削峰（Push 消息直接返回 FAILURE 促使 Broker 阶梯退避）
    public boolean pauseEndpoint(String endpointId) {
        return containerManager.pause(endpointId);
    }

    // 3. 动态恢复指定端点正常消费
    public boolean resumeEndpoint(String endpointId) {
        return containerManager.resume(endpointId);
    }

    // 4. 物理优雅停止底层 PushConsumer 连接
    public boolean stopEndpoint(String endpointId) {
        return containerManager.stop(endpointId);
    }

    // 5. 重新启动已停止的底层 PushConsumer 连接
    public boolean startEndpoint(String endpointId) {
        return containerManager.start(endpointId);
    }
}
```

### 扩展点五：死信队列受控重放治理 (`DeadLetterReplayScenarioService.java`)
通过 `RocketmqDeadLetterReplayTemplate` 实现对死信队列 `%DLQ%<ConsumerGroup>` 的只读探查与安全重放：
```java
@Service
@RequiredArgsConstructor
public class DeadLetterReplayScenarioService {

    private final RocketmqDeadLetterReplayTemplate deadLetterReplayTemplate;

    // 1. 只读探查死信（零 ACK 保留现场，供控制台审计展示）
    public List<MessageView> peekDeadLetters(String consumerGroup, int limit) {
        return deadLetterReplayTemplate.peekDeadLetters(consumerGroup, limit);
    }

    // 2. 受控重放死信至原始主题（自动清洗底层内部重试头，防雪崩熔断，重投成功后原子确认原死信）
    public DeadLetterReplayResult replayDeadLetters(String consumerGroup, int limit) {
        return deadLetterReplayTemplate.replayDeadLetters(consumerGroup, limit);
    }

    // 3. 受控重放死信至指定分流主题
    public DeadLetterReplayResult replayDeadLettersToTopic(String consumerGroup, int limit, String targetTopic) {
        return deadLetterReplayTemplate.replayDeadLetters(consumerGroup, limit, targetTopic);
    }

    // 4. 批量清理丢弃死信消息（物理 ACK 彻底移出死信队列）
    public int discardDeadLetters(String consumerGroup, int limit) {
        return deadLetterReplayTemplate.discardDeadLetters(consumerGroup, limit);
    }
}
```

---

## 4. 维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 1. SimpleConsumer 租约拉取、不可见时间续期与显式确认
- **长轮询微批拉取**：基于官方推荐的 Pop 模式，单批次最多拉取 `maxMessageNum` 条消息，挂起等待受控于 `awaitDuration`；
- **租约安全防护**：拉取后的消息在 `invisibleDuration` 时长内对同组其他消费者隐身不可见。对于长耗时计算任务，业务可通过 `changeInvisibleDuration` 动态续期不可见租约，坚决杜绝任务仍在执行时发生重复投递；
- **显式确认出队**：业务处理完毕后显式调用 `ack` 彻底出队；若业务发生未捕获异常未确认，租约到期后消息将重新可见由集群竞争重试。

### 2. 延时消息原生精准撤回 (`recallMessage`)
- **秒级定时调度**：支持相对时长 `sendDelayed`（Duration）与绝对时间点 `sendDeliverAt`（Instant）；
- **原生精准撤回**：对于未到投递时间的延时消息（如未支付订单关单倒计时），发送回执携带 `recallHandle`，业务可直接调用 `rocketmqTemplate.recallMessage(topic, recallHandle)` 在 Broker 端精准撤销，杜绝下游无效消费。

### 3. 批量消息超限分批切片保障
- **智能切片防超限**：调用 `sendBatch` 投递大批量消息时，框架依据 `producer.max-batch-size`（默认 500 条）实施自动切片分割，多批次流式并发投递并合并回执，彻底阻断因单次批量过大被 Broker 拒绝。

### 4. 4MB 大报文前置防御与 4KB 透明 GZIP 压缩
- **Fail-Fast 超限拦截**：通过 `producer.max-payload-size`（默认 4MB）严格限制发信报文大小，超限即刻抛出 `MessageDeliveryException`，阻断大报文穿透至 Broker 引发 GC 停顿与存储溢出；
- **透明压缩与解压缩**：当报文体积超过 `producer.compression-threshold`（默认 4KB）时，生产端自动执行 GZIP 压缩并在消息 Header 标记 `X-Compression: GZIP`；消费端透明识别并解压，对业务代码 100% 透明。

### 5. 消费端毒丸不可自愈异常判定与安全确认丢弃
- **毒丸拦截机制**：当报文存在畸形 JSON、破坏 JSR-303 参数校验规则或抛出 `IllegalArgumentException` 等不可自愈异常时，框架整合 Core 模块 `FatalExceptionClassifier`，自动判定为毒丸报文；
- **安全丢弃**：记录 `[MQ-FATAL-POISON-PILL]` 告警日志，并直接向 Broker 返回 `ConsumeResult.SUCCESS` 确认丢弃，**彻底封死 16 次无脑重试风暴与消费线程被卡死的深水区隐患**。

### 6. 防重幂等静默确认
- 当消费方法标注 `@Idempotent` 或业务主动检测到重复并抛出 `IdempotentException` 时；
- 框架自动捕获该异常，输出 `[MQ-IDEMPOTENT-DUPLICATE]` 警告日志，并安全返回 `ConsumeResult.SUCCESS` 触发静默确认，阻断服务端重复重试。

### 7. 本地事务提交感知发信 (`sendAfterCommit`)
- 在 `@Transactional` 方法内部调用 `rocketmqTemplate.sendAfterCommit` 时，发信逻辑挂载至 Spring `TransactionSynchronizationManager` 的 `afterCommit` 钩子；
- **回滚零发信**：若本地事务抛出异常回滚，钩子自动作废，物理发信绝不触发，彻底消灭幽灵消息；
- **失败补偿网**：若事务提交后网络故障导致发信失败，自动调用 `RocketmqTransactionFailureCallback` SPI。

### 8. 死信队列防二次雪崩熔断与重试协议头清洗
- **重放次数硬拦截**：死信重放时在 Header 累加 `X-Dead-Letter-Replay-Count`，超过 `dead-letter.max-replay-attempts`（默认 3 次）时强制熔断，防止故障消息在死信与业务队列间无限死循环重放；
- **协议头安全清洗**：重放前彻底剥离内部系统重试协议头（如 `TRACE_ON`、`BORG_TAG` 等），保障重投消息在业务端被视为纯净新事件。

### 9. 确定性停机排空 (`SmartLifecycle`)
- **排空时序**：容器实现 Spring `SmartLifecycle`，关闭时**先关闭所有 `PushConsumer` 实例**（停止拉取、排空在途消费），保障在途长事务顺利完成；再销毁 `RocketmqSimpleConsumerTemplate` 中的流式拉取客户端缓存；最后关闭全局受管 `Producer` 实例；
- **资源安全**：防止在途消费线程在处理下行发信或异步确认时因底层网络连接已销毁而引发运行时错误。

---

## 5. 维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)

本项目所有示例代码与配置均经过 1:1 独立场景沙箱工程 `ateng-boot-sample-rocketmq` 实测全真验证。

### 双模自适应架构说明
- **默认离线模式 (CI 模式)**：未配置 `application-local.yml` 时，Spring Boot 自动激活 `@Profile("!local")` 下的 `InMemoryRocketmqBroker` 测试替身，100% 内存自洽运行 20 大场景集成测试，零外部依赖，极速秒级全绿；
- **Local 真实联调模式**：复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并激活 `local` Profile，测试套件无缝切换为直连真实 RocketMQ 5.x gRPC Proxy 服务，验证真实连通性与网络行为。

### 运行全景端到端自验
```bash
mvn test -pl ateng-boot-rocketmq,ateng-boot-samples/ateng-boot-sample-rocketmq -am -T 1C -nsu -B
```

控制台真实日志片段（节选自 `SampleRocketmqE2eTest` 真实控制台输出）：
```log
2026-10-30T07:44:20.102+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] g.a.b.r.a.AtengRocketmqAutoConfiguration : [ateng-boot-rocketmq] RocketMQ 5.x 增强基础设施自动装配已激活 (endpoints=127.0.0.1:8081, requestTimeout=PT3S)
2026-10-30T07:44:20.105+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] g.a.b.r.a.AtengRocketmqAutoConfiguration : [ateng-boot-rocketmq] RocketMQ 5.x SimpleConsumer 流式拉取门面已注入
2026-10-30T07:44:20.108+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] g.a.b.r.a.AtengRocketmqAutoConfiguration : [ateng-boot-rocketmq] RocketMQ 5.x 死信队列治理与受控重放门面已注入
2026-10-30T07:44:20.111+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] g.a.b.r.a.AtengRocketmqAutoConfiguration : [ateng-boot-rocketmq] RocketMQ 5.x 消费监听容器运维管控门面已注入
2026-10-30T07:44:21.050+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.s.r.s.StandardRocketmqScenarioService : [STANDARD-SCENARIO] 同步发送订单消息: orderId=ORD-SYNC-001, tag=create
2026-10-30T07:44:21.065+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.r.template.RocketmqTemplate      : [MQ-SEND] mq=rocketmq topic=sample_order_topic tag=create bizKey=ORD-SYNC-001 traceId=5b525287f4c549fcb25a3ea14ea08873 size=87B status=SUCCESS cost=15ms messageId=MSG-MOCK-1001
2026-10-30T07:44:21.120+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.s.r.s.BatchDelayScenarioService  : [DELAY-SCENARIO] 原生撤回未到期定时延时消息: topic=sample_delay_topic, recallHandle=RECALL-ORD-DELAY-003
2026-10-30T07:44:21.135+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.s.r.s.SimpleConsumerPullScenarioService : [SIMPLE-PULL-SCENARIO] 流式批量拉取消息: topic=sample_order_topic, group=sample_simple_group, maxNum=10, invisibleDuration=PT30S
2026-10-30T07:44:21.138+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.r.t.RocketmqSimpleConsumerTemplate: [MQ-SIMPLE-RECEIVE] mq=rocketmq topic=sample_order_topic consumerGroup=sample_simple_group tag=* count=2 cost=3ms
2026-10-30T07:44:21.140+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.r.t.RocketmqSimpleConsumerTemplate: [MQ-SIMPLE-ACK] mq=rocketmq messageId=MSG-SIMPLE-001 topic=sample_order_topic group=sample_simple_group
2026-10-30T07:44:21.180+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.s.r.s.ContainerOpsScenarioService : [OPS-SCENARIO] 暂停指定监听端点: endpointId=ep-order-standard
2026-10-30T07:44:21.182+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.r.c.RocketmqContainerManager     : [MQ-CONTAINER-PAUSE] 成功暂停消费监听端点: idOrTopic=ep-order-standard, matchedCount=1, pausedCount=1
2026-10-30T07:44:21.210+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.s.r.s.DeadLetterReplayScenarioService : [DLQ-SCENARIO] 受控重放死信队列至原始主题: consumerGroup=sample_order_group, limit=10
2026-10-30T07:44:21.215+08:00  INFO 14520 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.r.t.RocketmqDeadLetterReplayTemplate : [MQ-DLQ-REPLAY] 死信消息重放成功: messageId=MSG-DLQ-001, targetTopic=sample_order_topic, replayCount=1
```
