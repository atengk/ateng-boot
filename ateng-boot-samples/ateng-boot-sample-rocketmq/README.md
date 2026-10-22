# ateng-boot-sample-rocketmq 原生 2PC 分布式事务与 gRPC 声明式监听端到端自验工程

> 本样例工程基于 Apache RocketMQ 5.x 官方 Java SDK 与 `ateng-boot-rocketmq` 生产级增强 Starter 构建，提供 100% 内存自洽的端到端自动化测试验证沙箱。

---

## 1. 场景定位与功能基线

`ateng-boot-sample-rocketmq` 作为 `ateng-boot-rocketmq` 的 1:1 独立场景沙箱，聚焦以下核心通信场景：
- **纯内存自洽与零物理 Broker 依赖 (Zero External RocketMQ Cluster)**：基于 `InMemoryRocketmqBroker` 测试替身 (TestDouble)，在本地与 CI 断网环境下实现秒级全绿离线端到端验证；
- **受管高阶门面与全场景发信**：基于 `RocketmqTemplate` 实现同步阻塞发信、异步非阻塞发信、毫秒级延时与定时发信、FIFO 顺序保序发信（`MessageGroup`）；
- **原生 2PC 分布式事务半消息与统一回查分发架构**：通过 `rocketmqTemplate.sendTransaction` 回调 `RocketmqLocalTransactionExecutor` 完成事务半消息协同，结合 `RocketmqTransactionChecker` 与 `RocketmqLocalTransactionChecker` SPI 实现按 Topic 领域解耦的统一回查决断；
- **本地事务提交感知发信**：`sendAfterCommit` 验证事务回滚零发信与事务提交物理发信，配合 `RocketmqTransactionFailureCallback` SPI 实现发信失败兜底；
- **声明式 `@RocketmqListener` 事件驱动容器**：服务端 Tag（支持逻辑或 `create || pay`）与 SQL92 属性过滤下推；
- **微服务推断反序列化解耦**：依据监听器方法入参强类型与泛型反射自动还原 Java 实体，彻底摆脱发送端类全限定名的耦合枷锁；
- **大报文防御与透明解压缩**：4MB 硬上限 Fail-Fast 拦截，4KB 阈值透明 GZIP 压缩与入站解压缩；
- **消费端毒丸丢弃与防重幂等静默确认**：不可自愈异常（JSR-303 格式错误、致命参数等）记录 `[MQ-FATAL-POISON-PILL]` 安全丢弃返回 SUCCESS；`IdempotentException` 记录 `[MQ-IDEMPOTENT-DUPLICATE]` 静默确认阻断重复重试风暴；
- **确定性停机排空保障**：实现 `SmartLifecycle`，应用停机时优先关闭所有 PushConsumer 实例，排空在途请求，保障平滑发布。

---

## 2. 核心集成验证清单

| 序号 | 验证场景 | 覆盖核心门面 / API / 注解 | 测试动作 | 预期校验点 |
| :---: | :--- | :--- | :--- | :--- |
| **01** | 同步发信与四维上下文透传 | `RocketmqTemplate.send` + `@RocketmqListener` | 投递订单实体并设置 MDC traceId、TenantContextHolder、UserContextHolder | 同步发送成功，消费端强类型反序列化，四维上下文 MDC、租户与用户无损还原 |
| **02** | 异步发信与回调消费 | `RocketmqTemplate.sendAsync` | 异步投递订单消息 | 返回 CompletableFuture，异步回调成功，消费端顺利接收 |
| **03** | 延时与定时消息调度 | `RocketmqTemplate.sendDelayed` / `sendDeliverAt` | 投递 15 分钟延时与未来时间戳订单 | 消息携带 deliveryTimestamp 正确投递至 `OrderDelayConsumer` |
| **04** | FIFO 顺序保序消息 | `RocketmqTemplate.sendOrderly` | 携带相同 MessageGroup 连续发送 2 条订单 | 消费端严格按 MessageGroup 顺序接收，属性无损保留 |
| **05** | 原生 2PC 分布式事务半消息 | `RocketmqTemplate.sendTransaction` + `RocketmqLocalTransactionExecutor` | 分别模拟本地事务成功与失败决断 | 本地成功决断 COMMIT 下游成功消费；本地失败决断 ROLLBACK 半消息丢弃 |
| **06** | 全局统一事务回查分发 | `RocketmqTransactionChecker` + `RocketmqLocalTransactionChecker` | 模拟 Broker 超时未决回查视图 | 根据 Topic 准确路由至 `SampleOrderTransactionChecker`，决断 COMMIT / ROLLBACK / UNKNOWN |
| **07** | 本地事务感知发信与回滚防幽灵 | `RocketmqTemplate.sendAfterCommit` | 分别执行 `@Transactional` 提交与回滚 | 事务提交后发信成功，事务回滚时零发信杜绝幽灵消息 |
| **08** | SQL92 服务端属性过滤 | `@RocketmqListener(sql92 = "amount > 100")` | 发送属性 `amount=250` 与 `amount=50` | `amount > 100` 顺利接收，`amount <= 100` 服务端拦截不投递 |
| **09** | 毒丸异常安全丢弃与快速确认 | `FatalExceptionClassifier` + `RocketmqListenerInvoker` | 投递致命参数毒丸报文 | 判定毒丸记录 `[MQ-FATAL-POISON-PILL]`，返回 SUCCESS 安全丢弃，阻断循环重试 |
| **10** | 防重幂等静默确认 | `IdempotentException` + `RocketmqListenerInvoker` | 投递重复订单消息 | 首次消费正常，重复消息命中防重抛出 `IdempotentException`，记录 `[MQ-IDEMPOTENT-DUPLICATE]` 并静默确认 |
| **11** | 4MB 大报文防御超限拦截 | `RocketmqTemplate` 大报文防御 | 发送体积超过 4MB 的报文 | 前置抛出 `MessageDeliveryException`，阻断网络传输 |
| **12** | SmartLifecycle 确定性停机排空 | `RocketmqConsumerContainer.stop()` | 容器生命周期停机触发 | 所有活动 PushConsumer 均调用 `close()`，现场资源无残留 |

---

## 3. 本地自验执行指南

执行以下 Maven 命令即可在纯内存环境中以并行加速模式完成端到端离线自验：

```bash
mvn test -pl ateng-boot-rocketmq,ateng-boot-samples/ateng-boot-sample-rocketmq -am -T 1C -nsu -B
```
