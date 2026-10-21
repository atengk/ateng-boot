# ateng-boot-sample-rabbitmq 生产级路由与可靠投递端到端自验工程

> 本样例工程基于 Spring AMQP 与 `ateng-boot-rabbitmq` 生产级增强 Starter 构建，提供 100% 离线自洽的端到端自动化测试验证沙箱。

---

## 1. 场景定位与功能基线

`ateng-boot-sample-rabbitmq` 作为 `ateng-boot-rabbitmq` 的 1:1 独立场景沙箱，聚焦以下核心通信场景：
- **纯内存与零网络依赖（Zero External Broker）**：基于 Spring AMQP 原生对象与 Mock 设施，在本地与 CI 断网环境下实现秒级全绿离线端到端验证；
- **声明式流畅拓扑构建器**：使用 `RabbitTopologyBuilder` 链式声明 Direct、Topic、Quorum 队列、单活独占消费者、死信拓扑（DLX/DLQ）与插件延迟队列；
- **双重投递可靠性与生产门面**：使用 `RabbitReliableTemplate` 执行同步阻塞等待 Confirm ACK 与异步投递；
- **消费端幂等静默确认**：拦截 `IdempotentException` 触发 Silent ACK，阻断重复重试风暴；
- **延迟业务信令**：订单超时未支付自动取消；
- **毒丸隔离与死信诊断**：毒丸异常 0 次重试快速送 DLQ，配合 `RabbitDeadLetterHelper` 提取 `x-death` 异构元数据；
- **事务提交感知发信**：`sendAfterCommit` 验证事务回滚零发信与事务提交正常发信，杜绝幽灵消息（Ghost Message）。

---

## 2. 核心集成验证清单

| 序号 | 验证场景 | 覆盖核心机制 / API | 测试方式 / 动作 | 预期校验点 |
| :---: | :--- | :--- | :--- | :--- |
| **01** | Direct 与 Topic 精准与层级路由 | `RabbitReliableTemplate.sendReliable` / `sendReliableAsync` | 投递订单与系统日志 | Direct 精准到达、Topic 通配匹配、异步 Future 成功完成 Confirm |
| **02** | 消费端幂等静默确认 | `IdempotentException` + `RabbitListenerGlobalAdvice` | 投递相同业务主键消息 | 首次消费正常，重复消息命中幂等拦截触发 Silent ACK |
| **03** | 订单延时超时取消 | `RabbitReliableTemplate.sendDelayed` | 投递带有 delay 时长的超时取消消息 | 延迟消费者成功接收超时订单履约信令 |
| **04** | 毒丸隔离与死信元数据诊断 | `RabbitFatalExceptionStrategy` + `RabbitDeadLetterHelper` | 投递毒丸消息触发死信流转 | 消息被快速路由至 DLQ，死信助手准确提取原队列、死因与流转计数 |
| **05** | 本地事务感知发信 | `RabbitReliableTemplate.sendAfterCommit` | 分别执行事务提交与事务回滚 | 事务提交后发信成功，事务回滚时零发信杜绝幽灵消息 |

---

## 3. 本地自验执行指南

执行以下 Maven 命令即可在纯内存环境中以并行加速模式完成端到端离线自验：

```bash
mvn test -pl ateng-boot-rabbitmq,ateng-boot-samples/ateng-boot-sample-rabbitmq -am -T 1C -nsu -B
```
