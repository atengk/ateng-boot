# 39. RocketMQ 5.x gRPC 生产增强与原生分布式事务架构决策 (RocketMQ 5.x gRPC Production Hardening, Native 2PC Transaction & Safe In-Memory Architecture)

## 状态
`Accepted`

---

## 背景与痛点 (Context & Problem Statement)

在金融核心、在线支付、订单交易与关键电信级业务场景中，RocketMQ 凭借高可靠存储架构、原生分布式事务支持与毫秒级保序调度，成为支撑高价值交易通信的核心战略底座。然而，在真实生产落地与高并发微服务场景下，直接使用原生客户端或老旧组件面临一系列严峻挑战与深水区隐患：

1. **旧式 Remoting 协议与老旧 Starter 依赖污染 (Legacy Protocol & Dependency Hell)**：
   老旧的 `rocketmq-spring-boot-starter` 与 4.x 客户端深度绑定了老版 TCP Remoting 协议，传递引入了大量老旧第三方库（如 fastjson 1.x、老版 netty 等），在 Spring Boot 3 与 JDK 17+ 模块化环境下极易发生依赖冲突与类加载异常。
2. **2PC 事务半消息与微服务业务解耦割裂 (Transaction Half-Message Routing Fragility)**：
   RocketMQ 5.x 官方 SDK 约束一个 `Producer` 只能绑定全局唯一的 `TransactionChecker`。当微服务应用同时发送多种不同业务实体（如订单、支付、优惠券）的事务半消息时，若缺乏统一路由分发机制，回查逻辑将被迫塞进单个上帝类中，造成严重的职责倒错与代码膨胀。
3. **“幽灵消息”与资损隐患 (Ghost Messages in Non-2PC Scenarios)**：
   在无需 2PC 回查的常规业务解耦场景中，若在本地数据库事务 `@Transactional` 内部直接发送网络消息，一旦本地数据库回滚，网络消息却已发出造成下游消费脏数据；反之在事务提交后发信，若网络故障又缺乏可靠的失败补偿网。
4. **“毒丸消息”死循环自旋与资源耗尽 (Poison-Pill Retry Storms)**：
   畸形 JSON 报文或破坏 JSR-303 约束的消息进入队列后，反序列化器直接抛出异常。若消费端无脑返回 `FAILURE`，RocketMQ 服务端将按照阶梯无脑重试高达 16 次，造成宝贵的消费线程被毒丸长期占用，严重拖垮正常业务消费。
5. **微服务类路径绑定与反序列化强转崩溃 (Microservice Package Coupling & Type Erasure)**：
   跨微服务通信时，生产者与消费者的 DTO 类全限定名往往不同。若序列化器写入发送端包名，消费端将抛出 `ClassNotFoundException`；若监听入参为泛型集合（如 `List<OrderDTO>`），未做深度泛型提取会导致 Jackson 粗暴推断为 `List<LinkedHashMap>`，引发运行时强转崩溃。
6. **停机时序错乱导致的在途请求夭折 (Abrupt Shutdown & Outbound Failure)**：
   Spring 容器停机销毁若无严格时序守护，若先关闭全局 `Producer`，此时在途消费线程若恰好触发下行确认发信将直接失败抛错；若粗暴关闭消费端，在途长事务可能被强制掐断。

---

## 决策内容与架构拓扑 (Decision & Architecture)

为彻底攻克上述深水区缺陷，`ateng-boot-rocketmq` Starter 遵循**官方纯净 gRPC 瘦客户端基线（5.2.2 Shaded）、四维上下文透明贯通、受管高阶门面（RocketmqTemplate）、原生 2PC 事务半消息统一回查、事务提交感知（sendAfterCommit + 补偿 SPI）、声明式事件驱动监听（@RocketmqListener）、微服务推断反序列化解耦、大报文双重防御、确定性停机排空（SmartLifecycle）、100% 离线自洽**的工业级设计原则，确立以下核心架构体系：

```mermaid
flowchart TD
    subgraph ProducerDomain["生产端可靠投递与事务治理"]
        Tx["本地事务 (@Transactional)"]
        Template["RocketmqTemplate 生产门面"]
        TxSync["TransactionSynchronization<br>(afterCommit 提交钩子)"]
        TxFailSPI["RocketmqTransactionFailureCallback SPI"]
        Native2PC["原生 2PC 事务半消息<br>(sendMessageInTransaction)"]
        TxChecker["RocketmqTransactionChecker<br>(统一 Topic 路由分发器)"]
        LocalCheckerSPI["RocketmqLocalTransactionChecker SPI"]
    end

    subgraph RocketMQBroker["RocketMQ 5.x gRPC Proxy & Broker"]
        HalfMsg["事务半消息 (Half-Message)<br>(暂不可见)"]
        CommitMsg["提交消息 (Commit)<br>(下游可见)"]
        TopicQueue["标准/顺序/延时主题队列"]
    end

    subgraph ConsumerDomain["消费端受控防卫与声明式监听"]
        ListenerContainer["@RocketmqListener 容器<br>(PushConsumer 事件驱动)"]
        ContextRestore["四维上下文还原 + bizKey 提取<br>(finally 严格现场清理)"]
        IdempCheck["@Idempotent 防重校验"]
        FatalClassifier["FatalExceptionClassifier<br>(不可自愈毒丸快速判定)"]
        PoisonDrop["安全确认 (ConsumeResult.SUCCESS)<br>(记录告警/死信，阻断自旋)"]
        NormalSuccess["正常确认 (ConsumeResult.SUCCESS)"]
    end

    Tx -->|sendAfterCommit| TxSync
    TxSync -->|事务正常提交| Template
    TxSync -.->|事务回滚| Drop["作废钩子 (零发信，防幽灵消息)"]
    Template -.->|发信失败| TxFailSPI

    Template -->|sendTransaction| Native2PC
    Native2PC -->|投递半消息| HalfMsg
    Native2PC -->|本地事务成功| CommitMsg
    HalfMsg -.->|超时未决，主动回查| TxChecker
    TxChecker -->|Topic 策略路由| LocalCheckerSPI
    LocalCheckerSPI -->>|COMMIT / ROLLBACK| RocketMQBroker

    Template -->|普通/FIFO/延时投递| TopicQueue

    TopicQueue -->|流式推送| ListenerContainer
    ListenerContainer --> ContextRestore
    ContextRestore --> IdempCheck

    IdempCheck -->|抛出 IdempotentException| SilentAck["静默确认 (ConsumeResult.SUCCESS)<br>[MQ-IDEMPOTENT-DUPLICATE] 阻断重试"]
    IdempCheck -->|业务正常处理| NormalSuccess
    ContextRestore -->|发生异常| FatalClassifier

    FatalClassifier -->|不可自愈毒丸| PoisonDrop
    FatalClassifier -->|瞬态业务故障| Retry["返回 ConsumeResult.FAILURE<br>(服务端阶梯重试)"]
```

### 1. 官方 gRPC 瘦客户端与受管高阶生产门面 (`RocketmqTemplate`)
1. **Shaded 基线统管**：采用官方 `rocketmq-client-java:5.2.2` Shaded 坐标，物理隔绝 gRPC、Netty 与 Spring Boot 3 的依赖污染；
2. **高阶统一门面**：对外暴露 Spring 受管 `RocketmqTemplate`，内聚纯净 JSON 序列化、四维上下文自动注入消息 `userProperties`、`bizKey` 自适应提取、异常转译为 `MessageDeliveryException` 与标准宏日志；原生 `Producer` 作为底层 Bean 备选暴露；
3. **强类型延时与保序**：提供 `sendDelayed(..., Duration)`、`sendDeliverAt(..., Instant)` 映射 5.x 任意精度定时；提供 `sendOrderly(..., String messageGroup)` 显式保障 FIFO 分区保序。

### 2. 原生 2PC 分布式事务半消息与统一回查分发架构
1. **统一全局回查分发器**：内置实现 `RocketmqTransactionChecker`，容器启动时自动扫描所有实现了 `RocketmqLocalTransactionChecker` 接口的 Bean；
2. **Topic 策略解耦**：Broker 回查触发时，分发器提取消息 Topic 精准路由至对应业务 Checker，返回最终决断，彻底消除微服务上帝类；
3. **轻量事务防幽灵**：针对常规解耦消息提供 `sendAfterCommit`，在当前本地数据库事务成功 Commit 后再发信；发信失败触发告警并回调 `RocketmqTransactionFailureCallback` SPI。

### 3. 声明式 `@RocketmqListener` 容器体系与微服务推断反序列化
1. **事件驱动容器**：自研 Spring 声明式监听器注册体系，以官方 `PushConsumer` 为底层引擎，自动绑定 Topic、ConsumerGroup 与四维上下文全生命周期管理；
2. **Tag / SQL92 双模过滤**：注解默认支持 `tag = "*"` 与 `tag = "pay || refund"`；配置 `sql92 = "..."` 自动升级为 SQL92 属性过滤表达式；
3. **推断类型 (Inferred Type) 优先**：生产端不写入 `__TypeId__`；消费端直接依据监听方法入参强类型推断反序列化；反射提取 `ParameterizedType` 精确解析泛型集合，彻底阻断 `LinkedHashMap` 强转污染。

### 4. 消费端毒丸防卫与 `@Idempotent` 幂等静默确认
1. **毒丸快速失败与丢弃**：整合 Core 模块 `FatalExceptionClassifier`，反序列化与参数校验失败判定为不可自愈异常，记录 `[MQ-FATAL-POISON-PILL]` 告警并安全返回 `ConsumeResult.SUCCESS` 确认丢弃，彻底消除 16 次无脑重试风暴；
2. **幂等静默确认**：监听方法标注 `@Idempotent` 发生重复投递冲突时，捕获 `IdempotentException`，记录 `[MQ-IDEMPOTENT-DUPLICATE]` 警告并安全返回 `ConsumeResult.SUCCESS`，阻断无限重试；
3. **Key 双向保底映射**：发信时同时写入原生 `setKeys(bizKey)` 与 `X-Biz-Key` 消息头；消费端级联提取确保宏日志 `bizKey` 100% 绝对非空。

### 5. 大报文前置防御、透明压缩与生命周期确定性停机
1. **大报文双重防御**：生产端配置 `max-payload-size`（默认 4MB）执行 Fail-Fast 拦截；超过 `compression-threshold`（默认 4KB）透明 GZIP 压缩，消费端自动解压；
2. **确定性停机排空 (`SmartLifecycle`)**：实现 Spring `SmartLifecycle`，停机时先关闭所有 `PushConsumer`（停止拉取、排空在途消费，超时 15s），最后安全关闭全局 `Producer`，杜绝请求被掐断与资源泄露；
3. **100% 离线自洽单测**：基于纯应用层 `In-Memory TestDouble` 门面驱动，完全脱离外部 Broker 与 Docker，毫秒级离线全绿。

---

## 带来的收益与权衡分析 (Consequences & Trade-offs)

### 正向收益 (Positive Impacts)
- **零依赖冲突与高阶特性完备**：借助官方 5.2.2 Shaded 瘦客户端，彻底摆脱 4.x 老旧 Starter 依赖泥潭，完整保留 5.x 原生 2PC 半消息、任意精度定时与 FIFO 顺序消息；
- **微服务架构彻底解耦**：统一回查分发器解耦多业务事务；推断反序列化解耦微服务类路径；
- **高韧性生产安全防线**：毒丸快速丢弃与幂等静默确认消灭了死循环自旋；4MB 拦截与 GZIP 压缩保护了 Broker 内存与网络带宽；确定性停机保证了平滑发布；
- **极速离线自验**：纯内存应用 TestDouble 确保在断网 CI 矩阵中秒级全绿。

### 权衡与取舍 (Trade-offs & Mitigations)
- **需前置部署 Proxy**：RocketMQ 5.x gRPC 瘦客户端依赖 5.x Proxy 组件。此为官方云原生标准架构，框架在文档与启动校验中明确指引，确保前置基础设施就绪；
- **单集群优先设计**：自动配置聚焦单集群开箱即用，通过提供底层 SDK 标准构建指南支持多集群，兼顾零臃肿与生产扩展性。
