# ateng-boot-rocketmq 生产增强与原生分布式事务 Starter

> 基于 Apache RocketMQ 5.x 官方 Java SDK (Shaded gRPC 瘦客户端) 与 JDK 17/21+ 原生能力构建的工业级消息通信与分布式事务底座 Starter。提供生产级统一受管高阶门面（`RocketmqTemplate`）、毫秒级延时调度、FIFO 顺序保序投递、原生 2PC 分布式事务半消息与领域解耦统一回查分发架构（`RocketmqTransactionChecker`）、事务感知发信（`sendAfterCommit` + 补偿 SPI 杜绝幽灵消息）、声明式事件驱动监听容器（`@RocketmqListener`）、微服务推断反序列化解耦（消除 `__TypeId__` 强依赖与泛型集合擦除陷阱）、4MB 大报文防御与透明 GZIP 压缩、消费端毒丸安全丢弃与 `@Idempotent` 幂等静默确认（彻底消灭 16 次无脑重试风暴）、`SmartLifecycle` 确定性停机排空以及 100% 内存自洽 TestDouble 验证沙箱。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.rocketmq.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **生产端高阶门面** | 可靠发信统一门面 (普通/延时/FIFO/事务) | `RocketmqTemplate` | `enabled`<br>`endpoints`<br>`request-timeout`<br>`producer.max-attempts`<br>`producer.max-payload-size`<br>`producer.compression-threshold`<br>`producer.topics` | `send(topic, [tag], [bizKey], payload, [userProperties])`<br>`sendAsync(topic, [tag], [bizKey], payload, [userProperties])`<br>`sendDelayed(topic, [tag], [bizKey], payload, delay, [userProperties])`<br>`sendDeliverAt(topic, [tag], [bizKey], payload, deliverAt, [userProperties])`<br>`sendOrderly(topic, [tag], [bizKey], payload, messageGroup, [userProperties])`<br>`sendOrderlyAsync(topic, [tag], [bizKey], payload, messageGroup, [userProperties])`<br>`sendTransaction(topic, [tag], [bizKey], payload, executor, [userProperties])`<br>`sendAfterCommit(topic, [tag], [bizKey], payload, [userProperties])`<br>`buildMessage(...)`：同步阻塞确认、异步非阻塞返回 `CompletableFuture`、毫秒级定时调度、MessageGroup 顺序保序、2PC 事务半消息、本地事务提交感知发信、三级 bizKey 兜底、4MB 报文超限 Fail-Fast、4KB GZIP 透明压缩、四维上下文透明注入与 `[MQ-SEND]` 结构化宏日志 | `RocketmqTemplateTest` / `SampleRocketmqE2eTest` |
| **原生 2PC 分布式事务** | 统一事务回查分发器与领域解耦 SPI | `RocketmqTransactionChecker`<br>`RocketmqLocalTransactionChecker` | - | `check(messageView)`：统一实现 RocketMQ 5.x 官方 `TransactionChecker`，容器启动时自动发现业务 Checker，根据 Topic 动态策略路由至对应业务实现，决断 `COMMIT` / `ROLLBACK` / `UNKNOWN`，彻底消除微服务单体上帝类 | `RocketmqTransactionCheckerTest` / `SampleRocketmqE2eTest` |
| **本地事务执行器** | 本地业务事务决断回调函数式接口 | `RocketmqLocalTransactionExecutor` | - | `execute(sendReceipt)`：在 2PC 事务半消息成功投递后被回调执行，根据本地数据库或业务处理结果返回 `TransactionResolution`（`COMMIT` / `ROLLBACK` / `UNKNOWN`），执行异常自动触发回滚 | `RocketmqTemplateTest` / `SampleRocketmqE2eTest` |
| **事务发信补偿** | 本地事务提交后发信失败补偿 SPI | `RocketmqTransactionFailureCallback` | - | `onFailure(topic, tag, bizKey, payload, traceId, cause)`：当 `sendAfterCommit` 在本地数据库事务提交后因网络断连导致发信失败时被触发，供业务写入本地补偿表或推送到报警平台 | `RocketmqTemplateTest` |
| **声明式监听注解** | 消费端声明式事件驱动监听注解 | `@RocketmqListener` | - | `@RocketmqListener(topic = "...", consumerGroup = "...", tag = "*", sql92 = "", consumptionThreadCount = 0, maxCacheMessageCount = 0)`：标注在 Spring 受管 Bean 的消费处理方法上，支持属性占位符表达式、Tag 过滤（支持 `tagA || tagB` 逻辑或）与 SQL92 属性过滤下推 | `RocketmqConsumerContainerTest` / `SampleRocketmqE2eTest` |
| **消费端受管容器** | 监听容器与确定性生命周期管理器 | `RocketmqConsumerContainer` | `consumer.consumption-thread-count`<br>`consumer.max-cache-message-count` | 实现 `SmartInitializingSingleton`、`SmartLifecycle`、`DisposableBean`：自动扫描注册监听端点、严格校验 Topic 命名正则（`^[%a-zA-Z0-9_-]+$`，坚决杜绝点号）；停机时优先关闭所有 `PushConsumer` 实例（排空在途消费），保障平滑发布 | `RocketmqConsumerContainerTest` / `SampleRocketmqE2eTest` |
| **客户端工厂抽象** | PushConsumer 构造工厂与测试替身 SPI | `PushConsumerFactory` | - | `createPushConsumer(clientConfig, group, topic, filter, listener, threads, cacheCount)`：解耦底层物理 gRPC 客户端构建逻辑，支持生产环境默认工厂 `DefaultPushConsumerFactory` 与测试环境内存替身 `InMemoryRocketmqBroker` 无缝拔插切换 | `RocketmqConsumerContainerTest` / `SampleRocketmqE2eTest` |
| **消费调用执行器** | 微服务推断反序列化与受控防卫核心 | `RocketmqListenerInvoker` | - | 实现 `MessageListener`：瞬态四维上下文入站还原（`MDC traceId`, `TenantContextHolder`, `UserContextHolder`）与 `finally` 现场清理；透明 GZIP 解压；依据方法参数类型与 `ParameterizedType` 精准推断反序列化（支持原生 `MessageView`、`byte[]`、`String`、业务 DTO 及泛型集合 `` `List<T>` ``）；JSR-303 参数校验；不可自愈毒丸快速丢弃返回 `SUCCESS`；`IdempotentException` 幂等冲突静默确认返回 `SUCCESS`；瞬态异常返回 `FAILURE` 触发服务端重试 | `RocketmqListenerInvokerTest` / `SampleRocketmqE2eTest` |
| **消息辅助工具** | 序列化、四维上下文与 bizKey 解析工具 | `RocketmqMessageHelper` | - | `resolveBizKey(bizKey, payload)`：三级 bizKey 兜底（显式指定 -> `@RocketmqBizKey` / `getBizKey()` / `getId()` -> UUID）；`toPayloadBytes(payload)`：纯净 JSON 序列化；`compressGzip(bytes)` / `decompressGzip(bytes)`：透明压缩与解压 | `RocketmqMessageHelperTest` |
| **配置属性与元数据** | 强类型配置属性类与 IDE 智能补全 | `AtengRocketmqProperties` | `ateng.boot.rocketmq.*` | 包含 `enabled`、`endpoints`、`access-key`、`secret-key`、`request-timeout`（`Duration`）、`producer.max-attempts`、`producer.max-payload-size`（`DataSize`）、`producer.compression-threshold`（`DataSize`）、`producer.topics`、`consumer.consumption-thread-count`、`consumer.max-cache-message-count` | `AtengRocketmqPropertiesTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-rocketmq` 是面向企业级高可靠交易通信、原生 2PC 分布式事务履约与事件驱动架构的生产就绪 Starter：
- **纯净官方 Shaded 瘦客户端**：100% 依托官方 `rocketmq-client-java:5.2.2` Shaded 坐标与 JDK 17 原生能力，物理隔绝 gRPC、Netty 与老旧 Remoting 依赖污染；
- **全场景受管高阶门面**：`RocketmqTemplate` 内聚同步发信、异步发信、毫秒级延时与定时发信、FIFO 顺序保序发信与 2PC 分布式事务半消息；
- **原生 2PC 分布式事务统一回查**：内置全局 `RocketmqTransactionChecker`，通过 `RocketmqLocalTransactionChecker` SPI 将 Broker 事务回查精准路由到业务组件，彻底消除单体上帝类；
- **本地事务提交感知发信**：`sendAfterCommit` 严格保障数据库事务物理 Commit 成功后再投递消息，事务回滚时绝对零发信，并配属失败补偿 SPI；
- **微服务推断反序列化解耦**：依据监听器参数强类型与泛型反射自动还原 Java 实体，彻底摆脱发送端类全限定名的耦合枷锁，杜绝 `LinkedHashMap` 强转异常；
- **高韧性生产安全防线**：不可自愈毒丸记录 `[MQ-FATAL-POISON-PILL]` 安全丢弃返回 SUCCESS；`@Idempotent` 防重冲突记录 `[MQ-IDEMPOTENT-DUPLICATE]` 静默确认；4MB 硬上限拦截与 4KB 透明压缩保护 Broker 内存与网络；
- **确定性停机排空**：实现 Spring `SmartLifecycle`，应用停机时优先关闭所有 PushConsumer 实例（排空在途消费），保障平滑发布；
- **100% 离线自洽测试沙箱**：基于纯应用层 `PushConsumerFactory` SPI 与 `InMemoryRocketmqBroker` 测试替身，零外部网络与 Docker 依赖，秒级全绿。

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
使用原生客户端时，每个微服务发送事务消息被迫编写全局单一的 TransactionChecker 上帝类；本地事务内部直接发信容易产生幽灵脏数据；畸形报文进入队列后导致服务端 16 次无脑重试风暴；微服务间包名不同导致反序列化崩溃。

### 最小可用配置 (`application.yml`)
```yaml
ateng:
  boot:
    rocketmq:
      enabled: true
      endpoints: "127.0.0.1:8081"
      request-timeout: 3s
      producer:
        max-attempts: 3
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

#### 2. 生产端发信业务组件 (`TradeRocketmqProducer.java`)
```java
package io.github.atengk.boot.sample.rocketmq.producer;

import io.github.atengk.boot.rocketmq.spi.RocketmqLocalTransactionExecutor;
import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class TradeRocketmqProducer {

    public static final String TOPIC_ORDER = "sample_order_topic";
    public static final String TOPIC_DELAY = "sample_delay_topic";
    public static final String TOPIC_FIFO = "sample_fifo_topic";
    public static final String TOPIC_TX = "sample_tx_topic";
    public static final String TOPIC_SQL = "sample_sql_topic";

    private final RocketmqTemplate rocketmqTemplate;

    public SendReceipt sendOrder(OrderMessage order) {
        return rocketmqTemplate.send(TOPIC_ORDER, "create", order.getOrderId(), order);
    }

    public CompletableFuture<SendReceipt> sendOrderAsync(OrderMessage order) {
        return rocketmqTemplate.sendAsync(TOPIC_ORDER, "create", order.getOrderId(), order);
    }

    public SendReceipt sendOrderDelay(OrderMessage order, Duration delay) {
        return rocketmqTemplate.sendDelayed(TOPIC_DELAY, "delay", order.getOrderId(), order, delay);
    }

    public SendReceipt sendOrderDeliverAt(OrderMessage order, Instant deliverAt) {
        return rocketmqTemplate.sendDeliverAt(TOPIC_DELAY, "delay", order.getOrderId(), order, deliverAt);
    }

    public SendReceipt sendOrderFifo(OrderMessage order, String messageGroup) {
        return rocketmqTemplate.sendOrderly(TOPIC_FIFO, "fifo", order.getOrderId(), order, messageGroup);
    }

    public SendReceipt sendOrderTransaction(OrderMessage order, RocketmqLocalTransactionExecutor executor) {
        return rocketmqTemplate.sendTransaction(TOPIC_TX, "tx", order.getOrderId(), order, executor);
    }

    public void sendOrderAfterCommit(OrderMessage order) {
        rocketmqTemplate.sendAfterCommit(TOPIC_ORDER, "create", order.getOrderId(), order);
    }

    public SendReceipt sendOrderWithSqlProperties(OrderMessage order, Map<String, String> properties) {
        return rocketmqTemplate.send(TOPIC_SQL, null, order.getOrderId(), order, properties);
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
            tag = "create || pay"
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
微服务中只需实现 `RocketmqLocalTransactionChecker` 接口并声明支持的主题（`getTopic()`），即可自动挂载到全局回查分发器，彻底解决单体上帝类问题：
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

@Slf4j
@Component
public class SampleOrderTransactionChecker implements RocketmqLocalTransactionChecker {

    @Override
    public String getTopic() {
        return TradeRocketmqProducer.TOPIC_TX;
    }

    @Override
    public TransactionResolution check(MessageView messageView) {
        String bizKey = resolveBizKey(messageView);
        log.info("[SAMPLE-TX-CHECKER] 收到 Broker 事务半消息回查: topic={}, bizKey={}", getTopic(), bizKey);

        // 根据本地业务数据库订单实际落库状态决断
        boolean orderCommitted = checkOrderExistsInDatabase(bizKey);
        if (orderCommitted) {
            return TransactionResolution.COMMIT;
        }
        return TransactionResolution.ROLLBACK;
    }

    private boolean checkOrderExistsInDatabase(String orderId) {
        // 查询本地数据库
        return true;
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
当使用 `rocketmqTemplate.sendAfterCommit` 时，若本地数据库事务提交成功但因网络不可用发信失败，框架将自动触发该 SPI 回调：
```java
@Component
@Slf4j
public class OrderTransactionFailureCallback implements RocketmqTransactionFailureCallback {

    @Override
    public void onFailure(String topic, String tag, String bizKey, Object payload, String traceId, Throwable cause) {
        log.error("[TX-FALLBACK] 本地事务已提交但消息投递失败: topic={}, bizKey={}, traceId={}, cause={}",
                topic, bizKey, traceId, cause.getMessage(), cause);

        // 将失败消息写入本地补偿表或发送至告警渠道
    }
}
```

### 扩展点三：替换 `PushConsumerFactory` 自定义物理客户端或测试替身
通过声明同类型 Bean，平滑覆盖默认的 `DefaultPushConsumerFactory`：
```java
@Configuration(proxyBeanMethods = false)
public class CustomConsumerFactoryConfig {

    @Bean
    public PushConsumerFactory customPushConsumerFactory() {
        return new PushConsumerFactory() {
            @Override
            public PushConsumer createPushConsumer(
                    ClientConfiguration clientConfiguration,
                    String consumerGroup,
                    String topic,
                    FilterExpression filterExpression,
                    MessageListener messageListener,
                    int threadCount,
                    int maxCacheMessageCount) throws ClientException {
                // 自定义 PushConsumer 构造参数、连接池或 Mock 代理
                return new DefaultPushConsumerFactory().createPushConsumer(
                        clientConfiguration, consumerGroup, topic, filterExpression, messageListener, threadCount, maxCacheMessageCount);
            }
        };
    }
}
```

---

## 4. 维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 1. 确定性生命周期与停机排空 (`SmartLifecycle`)
- **排空时序**：容器实现 Spring `SmartLifecycle`，关闭时**先关闭所有 `PushConsumer` 实例**（停止拉取、排空在途消费），保障在途长事务顺利完成；最后关闭全局受管 `Producer` 实例；
- **资源安全**：防止在途消费线程在处理下行发信或异步确认时因底层网络连接已销毁而引发运行时错误。

### 2. 4MB 大报文前置防御与透明 GZIP 压缩
- **Fail-Fast 超限拦截**：通过 `ateng.boot.rocketmq.producer.max-payload-size`（默认 `4MB`）严格限制发信报文大小，超限即刻抛出 `MessageDeliveryException`，阻断大报文穿透至 Broker 引发 GC 停顿与存储溢出；
- **透明压缩与解压缩**：当报文体积超过 `ateng.boot.rocketmq.producer.compression-threshold`（默认 `4KB`）时，生产端自动执行 GZIP 压缩并在消息 Header 标记 `X-Compression: GZIP`；消费端透明识别并解压，对业务代码 100% 透明。

### 3. 微服务推断反序列化解耦与泛型集合解析
- **零包名耦合**：生产端统一序列化为纯净 JSON，不携带发送方的包名或类全限定名；
- **泛型集合深度解析**：消费端依据监听器方法签名反射提取 `java.lang.reflect.ParameterizedType`，精准反序列化为 `` `List<OrderItemDTO>` ``、`` `Map<String, Object>` ``，坚决杜绝 Jackson 默认推断为 `LinkedHashMap` 导致的类型转换崩溃。

### 4. 消费端毒丸不可自愈异常判定与安全确认丢弃
- **毒丸拦截机制**：当报文存在畸形 JSON、破坏 JSR-303 参数校验规则或抛出 `IllegalArgumentException` 等不可自愈异常时，框架整合 Core 模块 `FatalExceptionClassifier`，自动判定为毒丸报文；
- **安全丢弃**：记录 `[MQ-FATAL-POISON-PILL]` 告警日志，并直接向 Broker 返回 `ConsumeResult.SUCCESS` 确认丢弃，**彻底封死 16 次无脑重试风暴与消费线程被卡死的深水区隐患**。

### 5. 防重幂等静默确认
- 当消费方法标注 `@Idempotent` 或业务主动检测到重复并抛出 `IdempotentException` 时；
- 框架自动捕获该异常，输出 `[MQ-IDEMPOTENT-DUPLICATE]` 警告日志，并安全返回 `ConsumeResult.SUCCESS` 触发静默确认，阻断服务端重复重试。

### 6. 本地事务提交感知发信 (`sendAfterCommit`)
- 在 `@Transactional` 方法内部调用 `rocketmqTemplate.sendAfterCommit` 时，发信逻辑挂载至 Spring `TransactionSynchronizationManager` 的 `afterCommit` 钩子；
- **回滚零发信**：若本地事务抛出异常回滚，钩子自动作废，物理发信绝不触发，彻底消灭幽灵消息；
- **失败补偿网**：若事务提交后网络故障导致发信失败，自动调用 `RocketmqTransactionFailureCallback` SPI。

---

## 5. 维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)

本项目所有示例代码与配置均经过 1:1 独立场景沙箱工程 `ateng-boot-sample-rocketmq` 实测全真验证。

### 运行全景端到端自验
```bash
mvn test -pl ateng-boot-rocketmq,ateng-boot-samples/ateng-boot-sample-rocketmq -am -T 1C -nsu -B
```

控制台真实日志片段（节选自 `SampleRocketmqE2eTest` 真实控制台输出）：
```log
2026-10-22T10:55:22.052+08:00  INFO 17560 --- [ateng-boot-sample-rocketmq] [           main] g.a.b.r.a.AtengRocketmqAutoConfiguration : [ateng-boot-rocketmq] RocketMQ 5.x 增强基础设施自动装配已激活 (endpoints=127.0.0.1:8081, requestTimeout=PT3S)
2026-10-22T10:55:39.063+08:00  INFO 17560 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.s.r.consumer.OrderDelayConsumer  : [SAMPLE-DELAY-CONSUME] 接收延时订单: orderId=ORD-DELIVER-AT-004, deliveryTimestamp=1792641339052, tag=delay
2026-10-22T10:55:39.063+08:00  INFO 17560 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.r.l.RocketmqListenerInvoker      : [MQ-CONSUME] mq=rocketmq topic=sample_delay_topic consumerGroup=sample_delay_group tag=delay bizKey=ORD-DELIVER-AT-004 traceId=6c869a0e4104628c2a63aa47d3f886a1 size=87B status=SUCCESS cost=7ms messageId=MSG-MOCK-1011
2026-10-22T10:55:39.065+08:00  INFO 17560 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.r.template.RocketmqTemplate      : [MQ-SEND] mq=rocketmq topic=sample_delay_topic tag=delay bizKey=ORD-DELIVER-AT-004 traceId= size=87B status=SUCCESS cost=13ms messageId=MSG-RECEIPT-1012
2026-10-22T10:55:39.644+08:00  INFO 17560 --- [ateng-boot-sample-rocketmq] [           main] .b.s.r.s.RocketmqOrderTransactionService : [2PC-EXECUTE] 收到半消息发送回执: messageId=MSG-RECEIPT-1027, 开始执行本地事务
2026-10-22T10:55:39.645+08:00  WARN 17560 --- [ateng-boot-sample-rocketmq] [           main] .b.s.r.s.RocketmqOrderTransactionService : [2PC-EXECUTE] 本地事务执行失败，决断 ROLLBACK: orderId=ORD-2PC-ROLLBACK-006
2026-10-22T10:55:39.645+08:00  INFO 17560 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.s.r.t.InMemoryRocketmqBroker     : [TEST-DOUBLE-2PC] 事务回滚 ROLLBACK，丢弃半消息 (count=1)
2026-10-22T10:55:39.652+08:00  INFO 17560 --- [ateng-boot-sample-rocketmq] [           main] i.g.a.b.r.template.RocketmqTemplate      : [MQ-TRANSACTION-SEND] mq=rocketmq topic=sample_tx_topic tag=tx bizKey=ORD-2PC-ROLLBACK-006 traceId= size=95B status=ROLLED_BACK cost=13ms messageId=MSG-RECEIPT-1027
```
