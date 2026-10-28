# ateng-boot-rabbitmq 消息通信与可靠投递底座 Starter

> 依托 Spring 官方 Spring AMQP 生态与 JDK 17 原生能力构建的工业级可靠消息通信底座，提供零丢信双重确认（Publisher Confirms 与 ReturnsCallback）、事务感知延迟发信（`sendAfterCommit` + 补偿 SPI 杜绝幽灵消息）、泛型推断反序列化解耦（`TypePrecedence.INFERRED` 杜绝 microservice 类名耦合与 `@class` 注入漏洞）、消费端幂等静默确认（拦截 `IdempotentException` 触发 Silent ACK 阻断重试风暴）、毒丸快速隔离至死信、死信终点不变量强校验（Terminal DLQ Invariant）、流畅拓扑构建器（`RabbitTopologyBuilder` 支持 Quorum 仲裁队列与单活独占消费者）、只读运维账号启动安全防御（`auto-declare: false`）以及 100% 离线自洽测试沙箱。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **可靠投递门面** | 生产端可靠投递与事务感知门面 | `RabbitReliableTemplate` | `ateng.boot.rabbitmq.reliable.default-timeout`<br>`ateng.boot.rabbitmq.reliable.returns-callback-enabled`<br>`ateng.boot.rabbitmq.producer.max-payload-size`<br>`ateng.boot.rabbitmq.producer.compression-threshold` | `sendReliable(exchange, routingKey, payload, [bizKey], [timeout])`<br>`sendReliableAsync(exchange, routingKey, payload, [bizKey])`<br>`sendDelayed(exchange, routingKey, payload, [bizKey], delay)`<br>`sendAfterCommit(exchange, routingKey, payload, [bizKey])`<br>`getRabbitTemplate()`：同步阻塞等待 Confirm ACK、异步非阻塞返回 Future、`x-delay` 延迟投递、本地事务提交后物理发信、大报文超限 Fail-Fast (2MB) 与透明 GZIP 压缩 (>2KB) | `RabbitReliableTemplateTest` / `SampleRabbitmqE2eTest` |
| **拓扑构建器** | 声明式流畅拓扑构建器 (Fluent API) | `RabbitTopologyBuilder` | `ateng.boot.rabbitmq.topology.auto-declare` | `direct(name)` / `topic(name)` / `fanout(name)` / `delayed(name, [type])`<br>`ttlDelayQueue(name, delay, targetEx, targetRk)`<br>`queue(name).quorum().singleActiveConsumer().ttl(duration).maxLength(limit).deadLetter(dlx, dlq, rk).ttlQueue(name, delay).build()`：一键声明 Raft Quorum 队列、单活独占消费者、死信终点校验与双模延迟队列，返回 Spring 原生 `Declarables` | `RabbitTopologyBuilderTest` / `SampleRabbitmqE2eTest` |
| **死信诊断工具** | 死信元数据只读诊断助手 | `RabbitDeadLetterHelper` | - | `getOriginalQueue(message/props)`<br>`getOriginalExchange(message/props)`<br>`getDeathReason(message/props)`<br>`getDeathCount(message/props)`<br>`getOriginalRoutingKeys(message/props)`<br>`getFirstDeath(message/props)`<br>`getAllDeaths(message/props)`：安全解析复杂的嵌套 `x-death` 头，宽容兼容 String、`byte[]`、LongString 等异构驱动类型，提取原队列、原交换机、死因与重试流转计数 | `RabbitDeadLetterHelperTest` / `SampleRabbitmqE2eTest` |
| **消费环绕拦截** | 消费端全局上下文与幂等切面 | `RabbitListenerGlobalAdvice` | `ateng.boot.rabbitmq.logging.enabled`<br>`ateng.boot.rabbitmq.logging.log-payload-max-length` | 实现 AOP `MethodInterceptor`，在消费方法执行前后自动激活/清理四维上下文（TraceId、TenantId、UserId），记录 `[MQ-CONSUME]` 结构化审计日志；捕获 `IdempotentException` 自动执行静默确认（Silent ACK）阻断重复重试风暴 | `RabbitListenerGlobalAdviceTest` / `SampleRabbitmqE2eTest` |
| **毒丸致命判定** | 毒丸与致命异常分类策略 | `RabbitFatalExceptionStrategy` | - | 实现 Spring AMQP `FatalExceptionStrategy`，精准识别反序列化失败（`MessageConversionException`）、格式校验违规（`MethodArgumentNotValidException` / `ConstraintViolationException`）以及业务断言失败，执行 0 次重试快速送死信（DLQ） | `RabbitFatalExceptionStrategyTest` / `SampleRabbitmqE2eTest` |
| **本地受控重试** | 容器内本地指数退避重试 | `RabbitRetryPolicy` | `ateng.boot.rabbitmq.consumer.retry.enabled`<br>`ateng.boot.rabbitmq.consumer.retry.max-attempts`<br>`ateng.boot.rabbitmq.consumer.retry.initial-interval`<br>`ateng.boot.rabbitmq.consumer.retry.multiplier`<br>`ateng.boot.rabbitmq.consumer.retry.max-interval` | 继承 Spring Retry `SimpleRetryPolicy`，配合 `RejectAndDontRequeueRecoverer` 在本地内存执行指数退避重试（默认 3 次），重试耗尽安全转移至 DLQ，彻底取缔 Broker Requeue 死循环自旋 | `RabbitRetryPolicyTest` / `SampleRabbitmqE2eTest` |
| **不可路由告警** | 不可路由退回消息回调 SPI | `RabbitUnroutableMessageCallback` | `ateng.boot.rabbitmq.reliable.returns-callback-enabled` | `onUnroutableMessage(returned)`：当开启 `mandatory=true` 且消息未命中任何队列被 Broker 退回时，打印格式化告警 `[MQ-RETURNS]` 并回调此 SPI 供企业落库或钉钉告警 | `RabbitReturnsCallbackTest` |
| **事务发信补偿** | 事务提交后发信失败补偿 SPI | `RabbitTransactionFailureCallback` | - | `onTransactionPostCommitFailed(exchange, routingKey, payload, bizKey, ex)`：当 `sendAfterCommit` 在本地事务 Commit 成功后因网络抖动发信失败时被触发，供业务落库本地重发表兜底 | `RabbitReliableTemplateTest` |
| **消息类型推断** | 泛型推断反序列化消息转换器 | `Jackson2JsonMessageConverter` | - | 默认注入开启 `TypePrecedence.INFERRED` 与 `alwaysConvertToInferredType(true)` 的 Jackson 转换器，消费端 100% 依监听方法参数推断反序列化，解耦微服务契约，杜绝 `@class` 注入 RCE | `Jackson2JsonMessageConverterTest` |
| **优雅停机排空** | 消费端容器优雅下线排空定制 | `ContainerCustomizer<SimpleMessageListenerContainer>` | `ateng.boot.rabbitmq.consumer.shutdown-timeout` | 默认设置 `shutdownTimeout`（默认 30s），K8s 滚动停机收到 SIGTERM 时停止拉取新消息，预留窗口排空在途消费 | `AtengRabbitmqAutoConfigurationTest` |
| **只读拓扑安全** | 拓扑自动声明开关 | `RabbitAdmin` 生命周期后置增强 | `ateng.boot.rabbitmq.topology.auto-declare` | 当置为 `false` 时，后置处理器自动将 `RabbitAdmin.autoStartup` 设为 `false`，生产只读账号不主动向 Broker 发起声明，防御 403 启动崩溃 | `AtengRabbitmqAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-rabbitmq` 是面向企业级分布式事务与可靠消息履约场景的高可靠 AMQP 基础设施 Starter：
- **纯原生零依赖**：100% 基于 Spring 官方 `spring-boot-starter-amqp`（Spring AMQP 3.1+）与 JDK 17，杜绝私造重型协议封装与引入第三方全量工具包；
- **双重投递可靠闭环**：通过 Publisher Confirms 同步等待磁盘落盘 ACK、`mandatory=true` 退回告警（ReturnsCallback）与可拔插 SPI，构建零丢信安全防线；
- **事务防幽灵消息**：提供 `sendAfterCommit` 门面，严格保障数据库事务 Commit 后再物理发信，回滚时绝对零发信，并为提交后发信失败提供补偿回调；
- **微服务解耦与安全反序列化**：强制开启 `TypePrecedence.INFERRED`，根据消费方法参数反射推断类型，无需在 Header 传递 `__TypeId__` 类名，杜绝反序列化 CVE；
- **弹性抗雪崩消费**：四维上下文透明透传、`@Idempotent` 幂等静默确认（Silent ACK）、毒丸 0 次重试送死信、本地指数退避重试与 Quorum 拓扑标准化。

### Maven 坐标引入
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-rabbitmq</artifactId>
</dependency>
```

---

## 2. 维度一：最小起步体验 (Happy Path & Out-of-the-Box)

### 场景痛点
原生 Spring AMQP 发信默认无事务感知、异步无确认，网络抖动或配置错误时容易丢信；消费端反序列化需要提供同名 Class，且毒丸消息容易导致无休止重试自旋。

### 最小可用配置 (`application.yml`)
```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

ateng:
  boot:
    rabbitmq:
      enabled: true
      reliable:
        default-timeout: 3s
      consumer:
        prefetch: 20
        retry:
          max-attempts: 3
```

### 开箱极简代码示例

#### 1. 声明 Direct 拓扑与 Raft Quorum 队列
```java
@Configuration(proxyBeanMethods = false)
public class RabbitOrderTopologyConfig {

    @Bean
    public Declarables orderTopology() {
        return RabbitTopologyBuilder.direct("sample.order.direct")
                .queue("sample.order.create.queue")
                .quorum()                  // Raft Quorum 高可用仲裁队列
                .singleActiveConsumer()    // 单活独占消费者
                .routingKey("order.create")
                .build();
    }
}
```

#### 2. 可靠发信（等待 Broker Confirm ACK）
```java
@Component
@RequiredArgsConstructor
public class TradeMessageProducer {

    private final RabbitReliableTemplate rabbitReliableTemplate;

    public void sendOrderCreate(OrderCreateMessage message) {
        // 同步阻塞等待 Broker 磁盘落盘确认，超时或 NACK 抛出 MessageDeliveryException
        rabbitReliableTemplate.sendReliable(
                "sample.order.direct",
                "order.create",
                message,
                message.orderId()
        );
    }
}
```

#### 3. 消费端业务消费（强类型推断与四维上下文透传）
```java
@Component
@Slf4j
public class OrderConsumer {

    @RabbitListener(queues = "sample.order.create.queue")
    public void onOrderCreate(OrderCreateMessage message,
                              @Header(name = "X-Trace-Id", required = false) String traceId) {
        log.info("[CONSUMER-ORDER] 消费订单创建消息成功: orderId={}, traceId={}",
                message.orderId(), traceId);
    }
}
```

---

## 3. 维度二：生产扩展接管 (Customization & Extensibility)

当框架开箱行为无法满足企业特定监控、补偿与多数据中心需求时，可通过 SPI 或 Spring Bean 覆盖无缝接管：

### 1. 不可路由退回消息告警与落库 SPI (`RabbitUnroutableMessageCallback`)
当生产者向交换机投递消息但 RoutingKey 未匹配任何绑定队列时，Broker 触发 `Basic.Return`，框架触发此 SPI：
```java
@Component
@Slf4j
public class CustomUnroutableCallback implements RabbitUnroutableMessageCallback {

    @Override
    public void onUnroutableMessage(RabbitTemplate.ReturnsCallback.Returned returned) {
        log.error("[ALERT] 消息不可路由，推送钉钉告警: exchange={}, routingKey={}, replyText={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
        // 可在此接入企业死信补偿表或告警机器人
    }
}
```

### 2. 事务提交后发信失败补偿 SPI (`RabbitTransactionFailureCallback`)
当使用 `sendAfterCommit` 时，数据库已物理提交但网络抖动导致发信失败时触发：
```java
@Component
@Slf4j
public class CustomTxFailureCallback implements RabbitTransactionFailureCallback {

    @Override
    public void onTransactionPostCommitFailed(String exchange, String routingKey,
                                              Object payload, String bizKey, Throwable ex) {
        log.error("[TX-COMPENSATE] 事务已提交但发信失败，落库本地重发表: bizKey={}", bizKey, ex);
        // 落库本地死信待发信表，由后台定时任务捞起重试发信
    }
}
```

### 3. 自定义 `MessageConverter` 或覆盖 `RabbitReliableTemplate`
所有对外暴露的 `@Bean` 均带有 `@ConditionalOnMissingBean` 守卫：
```java
@Configuration(proxyBeanMethods = false)
public class CustomAmqpConfig {

    @Bean
    public MessageConverter customMessageConverter() {
        // 自定义 Jackson 属性或特定序列化器
        return new Jackson2JsonMessageConverter();
    }
}
```

---

## 4. 维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 1. 事务提交感知发信与防幽灵消息 (`sendAfterCommit`)
- **执行语义**：在带有 `@Transactional` 的方法内调用 `sendAfterCommit`，框架检测到当前处于活跃事务中，将发信操作延迟挂载至 `TransactionSynchronization.afterCommit` 阶段；
- **回滚行为**：若事务因异常触发 Rollback，**物理网络发信操作直接作废，发信次数严格为 0**，杜绝“幽灵消息（Ghost Message）”；
- **无事务兼容**：若当前无事务执行上下文，立即退化为标准可靠发信，无需修改业务调用代码。

### 2. 消费端防重幂等静默确认 (Silent ACK)
- **执行语义**：业务防重拦截（如 `@Idempotent` 或分布式锁检查）检测到消息已被处理时，抛出 `io.github.atengk.boot.core.exception.IdempotentException`；
- **底层行为**：最外层的 `RabbitListenerGlobalAdvice` 捕获该异常，输出格式化轻量宏日志 `[MQ-CONSUME-IDEMPOTENT-SILENT-ACK]`，**阻断异常继续上抛**，容器将其视为成功并执行标准 ACK，从根源切断重复重试风暴。

### 3. 毒丸快速隔离与死信终点不变量 (Terminal DLQ Invariant)
- **毒丸判定**：`RabbitFatalExceptionStrategy` 识别到参数不合法、反序列化失败或 JSR-303 违规时，判定为不可自愈异常，**0 次重试快速送死信队列（DLQ）**，保护主队列不发生阻塞雪崩；
- **死信终点铁律**：`RabbitTopologyBuilder` 在拓扑声明期严格禁止在死信队列（以 `.dlq` 结尾或含 `dead-letter`）上再次挂载 DLX，防止死信在不同队列间形成互咬死循环。

### 4. 生产级报文超限保护与透明 GZIP 压缩
- **超限 Fail-Fast**：出站报文体积超过 `ateng.boot.rabbitmq.producer.max-payload-size`（默认 2MB）时，前置直接抛出 `MessageDeliveryException`，保护 Broker 内存防 OOM；
- **透明压缩与解压**：报文超过 `ateng.boot.rabbitmq.producer.compression-threshold`（默认 2KB）时，自动使用 GZIP 压缩出站并打标 `Content-Encoding: gzip`；消费端通过 `GUnzipPostProcessor` 实现自动透明解压，节约 70% 网络带宽。

### 5. 只读账号启动安全治理 (`auto-declare: false`)
- 当生产环境采用只读账号连接 RabbitMQ Broker 时，配置 `ateng.boot.rabbitmq.topology.auto-declare: false`，自动关闭 `RabbitAdmin` 启动建队，避免 403 权限不足导致应用崩溃。

---

## 5. 维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)

> 本章节所有代码片段、配置与执行日志，**100% 1:1 逆向提取自已跑通的自验沙箱工程** [`ateng-boot-sample-rabbitmq`](../ateng-boot-samples/ateng-boot-sample-rabbitmq)。

### 1. 拓扑配置全真示例 (`RabbitScenarioTopologyConfig.java`)
```java
@Configuration(proxyBeanMethods = false)
public class RabbitScenarioTopologyConfig {

    public static final String EXCHANGE_ORDER_DIRECT = "sample.order.direct";
    public static final String QUEUE_ORDER_CREATE = "sample.order.create.queue";
    public static final String ROUTING_KEY_ORDER_CREATE = "order.create";

    public static final String QUEUE_ORDER_IDEMPOTENT = "sample.order.idempotent.queue";
    public static final String ROUTING_KEY_ORDER_IDEMPOTENT = "order.idempotent";

    public static final String EXCHANGE_LOG_TOPIC = "sample.log.topic";
    public static final String QUEUE_LOG_ERROR = "sample.log.error.queue";
    public static final String ROUTING_KEY_LOG_ERROR = "log.error.#";

    public static final String EXCHANGE_ORDER_DELAY = "sample.order.delay.direct";
    public static final String QUEUE_ORDER_DELAY = "sample.order.delay.queue";
    public static final String ROUTING_KEY_ORDER_TIMEOUT = "order.timeout";

    public static final String EXCHANGE_BIZ_DIRECT = "sample.biz.direct";
    public static final String QUEUE_BIZ_MAIN = "sample.biz.queue";
    public static final String ROUTING_KEY_BIZ = "biz.work";
    public static final String EXCHANGE_BIZ_DLX = "sample.biz.dlx";
    public static final String QUEUE_BIZ_DLQ = "sample.biz.dlq";
    public static final String ROUTING_KEY_BIZ_DEAD = "biz.dead";

    @Bean
    public Declarables orderDirectTopology() {
        return RabbitTopologyBuilder.direct(EXCHANGE_ORDER_DIRECT)
                .queue(QUEUE_ORDER_CREATE)
                .quorum()
                .singleActiveConsumer()
                .routingKey(ROUTING_KEY_ORDER_CREATE)
                .andQueue(QUEUE_ORDER_IDEMPOTENT)
                .routingKey(ROUTING_KEY_ORDER_IDEMPOTENT)
                .build();
    }

    @Bean
    public Declarables logTopicTopology() {
        return RabbitTopologyBuilder.topic(EXCHANGE_LOG_TOPIC)
                .queue(QUEUE_LOG_ERROR)
                .routingKey(ROUTING_KEY_LOG_ERROR)
                .build();
    }

    @Bean
    public Declarables orderDelayTopology() {
        return RabbitTopologyBuilder.delayed(EXCHANGE_ORDER_DELAY)
                .queue(QUEUE_ORDER_DELAY)
                .routingKey(ROUTING_KEY_ORDER_TIMEOUT)
                .build();
    }

    @Bean
    public Declarables bizDeadLetterTopology() {
        return RabbitTopologyBuilder.direct(EXCHANGE_BIZ_DIRECT)
                .queue(QUEUE_BIZ_MAIN)
                .routingKey(ROUTING_KEY_BIZ)
                .deadLetter(EXCHANGE_BIZ_DLX, QUEUE_BIZ_DLQ, ROUTING_KEY_BIZ_DEAD)
                .build();
    }
}
```

### 2. 事务感知发信全真示例 (`OrderTransactionService.java`)
```java
@Service
@RequiredArgsConstructor
public class OrderTransactionService {

    private final RabbitReliableTemplate rabbitReliableTemplate;

    @Transactional
    public void createOrderWithCommit(OrderCreateMessage message) {
        // 1. 本地数据库写操作
        // 2. 挂载事务提交后发信钩子 (此时不发起物理网络 IO，等待 DB Commit 触发)
        rabbitReliableTemplate.sendAfterCommit(
                RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DIRECT,
                RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_CREATE,
                message,
                message.orderId()
        );
    }

    @Transactional
    public void createOrderWithRollback(OrderCreateMessage message) {
        rabbitReliableTemplate.sendAfterCommit(
                RabbitScenarioTopologyConfig.EXCHANGE_ORDER_DIRECT,
                RabbitScenarioTopologyConfig.ROUTING_KEY_ORDER_CREATE,
                message,
                message.orderId()
        );
        // 抛出异常触发本地事务回滚，发信钩子自动作废
        throw new IllegalStateException("模拟业务抛错，触发事务回滚");
    }
}
```

### 3. 死信元数据只读诊断全真示例 (`DeadLetterAuditConsumer.java`)
```java
@Component
@Slf4j
public class DeadLetterAuditConsumer {

    @RabbitListener(queues = RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ)
    public void onDeadLetter(Message message) {
        String originQueue = RabbitDeadLetterHelper.getOriginalQueue(message);
        String originExchange = RabbitDeadLetterHelper.getOriginalExchange(message);
        String reason = RabbitDeadLetterHelper.getDeathReason(message);
        long count = RabbitDeadLetterHelper.getDeathCount(message);
        RabbitDeadLetterHelper.DeadLetterRecord record = RabbitDeadLetterHelper.getFirstDeath(message);

        log.warn("[DLQ-AUDIT] 收到死信消息: originQueue={}, originExchange={}, reason={}, count={}",
                originQueue, originExchange, reason, count);
    }
}
```

### 4. 实测控制台输出日志源头锚定 (`mvn test` 真实日志截取)
```text
2026-10-21 22:00:25.731 INFO  [main] [trace-scenario-a] i.g.a.b.r.t.RabbitReliableTemplate : [MQ-SEND] mq=rabbitmq topic=sample.order.direct routingKey=order.create bizKey=ORD-20261021-001 traceId=trace-scenario-a size=110B status=SUCCESS cost=0ms
2026-10-21 22:00:25.748 INFO  [main] [trace-scenario-a] i.g.a.b.s.r.consumer.OrderConsumer : [CONSUMER-ORDER] 消费订单创建消息成功: orderId=ORD-20261021-001, amount=199.00, traceId=trace-scenario-a, tenantId=tenant-999
2026-10-21 22:00:25.749 INFO  [main] [trace-scenario-a] i.g.a.b.s.r.p.TradeMessageProducer : [PRODUCER] 订单创建消息投递成功并收到 Broker Confirm ACK: orderId=ORD-20261021-001
2026-10-21 22:00:25.769 INFO  [main] [trace-scenario-a] i.g.a.b.r.t.RabbitReliableTemplate : [MQ-SEND] mq=rabbitmq topic=sample.log.topic routingKey=log.error.order bizKey=order-service traceId=trace-scenario-a size=90B status=SUCCESS cost=2ms
2026-10-21 22:00:25.772 INFO  [main] [trace-scenario-a] i.g.a.b.s.r.consumer.LogTopicConsumer : [CONSUMER-LOG] 收到系统错误日志: service=order-service, level=ERROR, content=订单支付超时重试失败
2026-10-21 22:00:25.808 DEBUG [main] [NO-TRACE] i.g.a.b.s.r.SampleRabbitmqApplication$1 : Initiating transaction commit
2026-10-21 22:00:25.814 INFO  [main] [f1e0d9a099021bdb950de4ae6cd98ef9] i.g.a.b.r.t.RabbitReliableTemplate : [MQ-SEND] mq=rabbitmq topic=sample.order.direct routingKey=order.create bizKey=ORD-TX-COMMIT-01 traceId=f1e0d9a099021bdb950de4ae6cd98ef9 size=109B status=SUCCESS cost=2ms
2026-10-21 22:00:25.815 INFO  [main] [NO-TRACE] i.g.a.b.s.r.consumer.OrderConsumer : [CONSUMER-ORDER] 消费订单创建消息成功: orderId=ORD-TX-COMMIT-01, amount=500.00, traceId=f1e0d9a099021bdb950de4ae6cd98ef9, tenantId=null
2026-10-21 22:00:25.823 DEBUG [main] [NO-TRACE] i.g.a.b.s.r.SampleRabbitmqApplication$1 : Initiating transaction rollback
```
