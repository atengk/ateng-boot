# 64. RabbitMQ 全场景工业级加固、死信受控治理、批量削峰与现代 Quorum 拓扑架构决策 (RabbitMQ Full-Scenario Hardening, Dead Letter Replay Governance, Batching & Modern Quorum Topology Architecture)

## 状态
`Accepted`

---

## 背景与痛点 (Context & Problem Statement)

在 [ADR-0037](./0037-rabbitmq-production-routing-and-reliable-delivery-architecture.md) 中，`ateng-boot-rabbitmq` Starter 确立了依托 Spring AMQP 官方起步基线的消息底座，实现了 Publisher Confirms 同步等待、`sendAfterCommit` 事务感知防幽灵消息、INFERRED 泛型推断反序列化解耦、毒丸快速隔离至死信（DLQ）以及 `RabbitTopologyBuilder` 基础构建能力。

然而，随着高并发分布式事务、海量审计日志削峰与复杂拓扑治理场景的深入，存量模块暴露出以下深水区痛点与成熟度断层：

1. **死信治理“半残门面”与缺乏处置闭环 (Unidirectional Dead Letter Accumulation)**：
   原 Starter 仅提供 `RabbitDeadLetterHelper` 只读诊断工具（解析 `x-death` 提取死因），缺乏受控重放、重新入队与清洗门面（“只查不救”），违背工业级九道防线之第一防线“对称语义完备性”。业务修复上游缺陷后，缺乏标准化手段安全恢复积压死信，容易被迫通过 UI 盲目搬移导致“二次死信雪崩”。
2. **海量打点与高吞吐削峰下的批量收发断层 (Batch Publishing & Consuming Void)**：
   高频日志、审计埋点与订单事件等场景下，单条调用 `sendReliable` 等待 Confirm 以及单条消费 ACK 带来极高的网络 RTT 与磁盘 I/O 负担，急需批量投递与批量聚合消费能力。
3. **现代 Quorum 队列高级治理参数缺位 (Missing Modern Quorum & Overflow Governance)**：
   面向 RabbitMQ 4.0 镜像队列淘汰基线，当前拓扑构建器虽支持 `.quorum()`，但缺乏 `x-delivery-limit`（Quorum 专属投递上限）、`x-dead-letter-strategy: at-least-once`、`x-max-priority`（优先级队列）以及 `x-overflow`（队列溢出策略），易引发生产队列堆积时的非预期行为。
4. **广播场景动态临时队列样板代码繁琐 (Dynamic Exclusive Queues for Fanout)**：
   缓存刷新、配置广播场景需要每个微服务 Pod 动态绑定独立临时队列（`auto-delete`, `exclusive`, 随机队列名），原构建器缺乏开箱即用的流式声明支持。
5. **架构边界防线需要进一步确立 (Clarity on RPC & Outbox Boundaries)**：
   部分团队尝试通过 MQ 强行封装同步 RPC 或试图在 MQ Starter 中硬编码数据库 Outbox 表，违背了微服务通信契约分工（同步走 HTTP/gRPC）与底座“零臃肿”原则，必须形成明确架构决议。

---

## 决策内容与架构拓扑 (Decision & Architecture)

经过全场景对标与严谨架构推演，我们对 `ateng-boot-rabbitmq` 实施以下全场景加固与治理升级：

```mermaid
flowchart TD
    subgraph PublisherEnhancement["生产端批量与优先级增强"]
        BatchSend["sendBatchReliable(exchange, rk, List<T>)<br>独立 CorrelationData + CompletableFuture.allOf"]
        PrioritySend["sendReliable(..., int priority)"]
        PostProcessorSend["sendReliable(..., MessagePostProcessor...)"]
    end

    subgraph TopologyGovernance["现代拓扑流式构建 (RabbitTopologyBuilder)"]
        MaxPriority[".maxPriority(int)"]
        Overflow[".overflow(OverflowStrategy)<br>(DROP_HEAD / REJECT_PUBLISH / REJECT_PUBLISH_DLX)"]
        DeliveryLimit[".deliveryLimit(int) & .deadLetterStrategy(...)"]
        AltEx[".alternateExchange(name)"]
        AnonQueue[".anonymousQueue() / .exclusiveAutoDeleteQueue()"]
    end

    subgraph DeadLetterGovernance["死信治理门面 (RabbitDeadLetterReplayTemplate)"]
        Replay["replay(dlq, targetEx, targetRk, limit, [filter])<br>清洗原死信头 + 注入 X-Dead-Letter-Replayed: true + 重放计数"]
        Peek["peek(dlq, limit) (只读探测)"]
        Purge["purge(dlq) (清空清理)"]
    end

    subgraph ConsumerBatching["消费端批量支持 (ContainerFactory)"]
        BatchConfig["ateng.boot.rabbitmq.consumer.batch.enabled=true<br>consumerBatchEnabled=true"]
        BatchListener["@RabbitListener<br>public void onBatch(List<OrderMessage> orders)"]
    end
```

### 1. 死信治理专职门面与防二次雪崩机制 (`RabbitDeadLetterReplayTemplate`)
- **对称闭环**：新增 `RabbitDeadLetterReplayTemplate`，提供 `replay(dlq, targetEx, targetRk, limit, filter)`、`peek(dlq, limit)` 与 `purge(dlq)`，与只读诊断 `RabbitDeadLetterHelper` 形成读写/处置对称完备门面；
- **防二次死信雪崩**：重放时默认安全清洗消息中底层的死信原因头，并在 Header 注入 `X-Dead-Letter-Replayed: true`、`X-Replay-Time` 与重放计数值；若重放后的消息再次失败进死信且达上限（默认 1 次），强制阻断重放，从根本上防止死信在业务队列与死信队列之间形成无限死循环震荡；
- **分层防卫限制**：强制限制单次最大重放数量（默认 ≤ 100，硬上限 1000），提供 `Predicate<Message>` 过滤器重载，支持精确定向重放。

### 2. 生产端批量可靠投递、事务感知批量发信与双模延迟投递 (`RabbitReliableTemplate`)
- **独立 CorrelationData 确认模型**：在 `RabbitReliableTemplate` 中提供 `sendBatchReliable` 与 `sendBatchReliableAsync`，每条消息独立注入四维上下文（TraceId/BizKey）与独立的 `CorrelationData`，底层并发投递并依托 `CompletableFuture.allOf` 聚合等待 Broker ACK；
- **事务提交后批量并发发信 (`sendBatchAfterCommit`)**：针对批量下单、批量状态流转等事务场景，在活跃事务中仅注册 1 个 `afterCommit` 同步钩子，提交前前置完成全批次体积校验与四维上下文快照捕获，提交后并发聚合等待 Confirm，异常时以 `payloads` 列表与 `"batch[N]"` 回调 `RabbitTransactionFailureCallback.onFailure`；
- **零插件原生消息级 TTL 延迟投递 (`sendWithExpiration` / `sendAsyncWithExpiration`)**：支持直接传入 `Duration expiration` 设置 AMQP 标准消息过期属性，配合 `RabbitTopologyBuilder.ttlDelayQueue` 构建零插件依赖的延迟队列闭环，与基于插件 `x-delay` 的 `sendDelayed` 形成双模互补；
- **精准故障定位**：若批次中某条消息发生 Broker NACK 或超时，精准收集失败的 index 与 bizKey，统一抛出 `MessageDeliveryException`，保留完整的失败链条。

### 3. 消费端批量消费支持、多租户上下文隔离 (`RabbitBatchExecutor`) 与重试耗尽可观测性
- 在 `RabbitmqProperties.Consumer` 中新增 `batch` 配置项（`batch.enabled`、`batch.size`、`batch.max-wait-time`），容器工厂自动装配 `consumerBatchEnabled=true`；
- **全局拦截链兼容与重试耗尽告警**：升级 `RabbitListenerGlobalAdvice`，智能适配单条 `Message` 与批量 `List<Message>` / `List<T>` 传参（当 `List<POJO>` 无原生 `Message` 入参时自动生成 `batch-<UUID>` 批次追踪标识激活 MDC）；保持 `retryInterceptor` 在外、`globalAdvice` 在内以确保每次重试均独立还原/清理四维上下文，同时升级受管 `RejectAndDontRequeueRecoverer`，在本地重试耗尽转入死信前显式输出 `[MQ-CONSUME-RETRY-EXHAUSTED]` 结构化告警；
- **批量消费多租户安全执行器 (`RabbitBatchExecutor`)**：提供静态原语（`runInContext` / `callInContext` / `processBatchSafely`）与 Spring Bean 强类型反序列化重载（基于容器 `MessageConverter` 逐条反序列化为 `Class<T>`），逐条激活并确定性清理四维上下文，遇到 `IdempotentException` 自动静默跳过，单条异常走 `failureHandler` 隔离回调，彻底根除批量消费下的多租户数据串门与整批回滚死锁。

### 4. 运行期消费监听容器动态运维与队列积压探测 (`RabbitContainerManager`)
- 新增标准运维门面 `RabbitContainerManager`（封装 `RabbitListenerEndpointRegistry` 与可选 `RabbitAdmin`），提供按监听端点 ID 或队列名称（`idOrQueue`）及全局维度的启停流控（`stop` / `start` / `stopAll` / `startAll` / `isRunning`）；
- 支持运行期动态并发扩缩容（`scaleConcurrency`）与预取数热调优（`updatePrefetchCount`），并提供不可变状态快照查询 `getContainerStatuses()`（`RabbitContainerStatus`）与队列实时积压探测 `getQueueStatus(queueName)`（`RabbitQueueStatus`）；
- 在 `ateng-boot-sample-rabbitmq` 中配套暴露 `RabbitContainerController`（`/sample/rabbitmq/container/*`），并将 `@Profile("!local")` 内存 AMQP 路由与队列模拟桩下沉至 Sample `src/main`，实现 `mvn spring-boot:run` 与双模测试 100% 离线自洽。

### 5. 现代 Quorum 拓扑流式参数全量补齐 (`RabbitTopologyBuilder`)
- 引入强类型枚举 `OverflowStrategy`（`DROP_HEAD`、`REJECT_PUBLISH`、`REJECT_PUBLISH_DLX`）；
- 构建器暴露 `.maxPriority(int)`、`.overflow(OverflowStrategy)`、`.deliveryLimit(int)` 与 `.deadLetterStrategy(DeadLetterStrategy)`（支持 `DeadLetterStrategy.AT_LEAST_ONCE` / `"at-least-once"`），全面对标现代 Quorum 高可用队列；
- 提供 `.alternateExchange(name)` 与 `.anonymousQueue()`，一键声明备用交换机与广播排他临时队列。

### 6. 架构边界决议 (Boundary Invariants)
- **拒绝 MQ RPC 门面**：微服务间同步调用 100% 归属于 HTTP (OpenFeign/RestClient) 或 gRPC；MQ 坚守异步解耦边界，避免封装同步阻塞 RPC 消耗容器线程；保留 `getRabbitTemplate().sendAndReceive(...)` 极个别场景逃生门；
- **拒绝强绑定数据库 Outbox**：维持 `sendAfterCommit` + `RabbitTransactionFailureCallback` SPI 机制，不强捆绑特定关系数据库（MySQL/PG）的本地消息表，保持 MQ Starter 底座的纯粹性与数据库中立性。

---

## 后果与权衡 (Consequences & Trade-offs)

### 正向收益 (Positive Consequences)
1. **死信处置安全可控**：告别手工搬移死信的粗放运维，通过注入标记与重放上限彻底防御了二次死信雪崩。
2. **削峰吞吐显著提升**：批量发送并发等待与批量消费聚合 ACK 极大减少了高频场景下的网络往返与磁盘刷盘瓶颈。
3. **拓扑契约类型安全**：消除死信策略与溢出参数的裸字符串硬编码，对齐 RabbitMQ 4.0 Raft Quorum 官方演进标准。
4. **100% 向后兼容**：存量代码无感知平滑运行，所有新增 API 均为增强重载与流式扩展。

### 妥协与成本 (Trade-offs & Mitigations)
1. **批量消费局部失败处理要求**：Spring AMQP 原生批量监听器在方法抛出异常时会整批重试。对此，我们在开发规范中强制推行业务内部逐条 try-catch 结合持久层幂等主键防御。
2. **独立 CorrelationData 确认的网络开销**：逐条独立 CorrelationData 并发确认比客户端物理打包（BatchingRabbitTemplate）微增部分网络开销，但换取了微服务下游消费者自由对接与标准报文契约的巨大收益。
