# ateng-boot-sample-kafka 高吞吐流批处理与 Offset 治理端到端自验工程

> 本样例工程基于 Spring Kafka 与 `ateng-boot-kafka` 生产级增强 Starter 构建，提供 100% 离线自洽的端到端自动化测试验证沙箱。

---

## 1. 场景定位与功能基线

`ateng-boot-sample-kafka` 作为 `ateng-boot-kafka` 的 1:1 独立场景沙箱，聚焦以下核心通信场景：
- **纯内存与零物理 Broker 依赖 (Zero External Kafka Cluster)**：基于 Spring Kafka 原生测试夹具与受管容器，在本地与 CI 断网环境下实现秒级全绿离线端到端验证；
- **声明式流畅拓扑构建器**：使用 `KafkaTopicBuilder` 链式声明业务 Topic、分区数、副本因子、最小同步副本数 (`min.insync.replicas`)、保留周期 (`retention.ms`) 与 `.withDlt()` 一键附带死信 Topic；
- **生产端可靠投递与微批调优**：使用 `KafkaReliableTemplate` 执行同步阻塞等待 RecordMetadata 与异步投递；
- **本地事务防幽灵发信**：`sendAfterCommit` 验证事务回滚零发信与事务提交物理发信，配合 `KafkaTransactionFailureCallback` SPI 实现发信失败兜底；
- **消费端推断反序列化**：`ByteArrayJsonMessageConverter` 智能反序列化，解耦发送方与接收方包名，消除 `__TypeId__` 强依赖；
- **消费端幂等静默提交**：拦截 `IdempotentException` 触发 Silent Commit，阻断重复重试风暴；
- **毒丸隔离与死信终点不变量**：毒丸异常 0 次重试快速进 `*.DLT`，死信消费者异常阻断二次循环反弹；
- **批量拉取与租户安全隔离**：`BATCH_CONTAINER_FACTORY` 批量拉取结合 `KafkaRecordExecutor` 确保单条记录上下文隔离与异常熔断隔离。

---

## 2. 核心集成验证清单

| 序号 | 验证场景 | 覆盖核心门面 / API | 测试方式 / 动作 | 预期校验点 |
| :---: | :--- | :--- | :--- | :--- |
| **01** | Key 分区保序与可靠收发 | `KafkaReliableTemplate.sendReliable` / `sendReliableAsync` | 投递订单与用户事件，指定 Key 与分区 | 同步与异步发送成功，单条消费者按 Key 正确消费，四维上下文 MDC 完整保留 |
| **02** | 本地事务感知发信与回滚防幽灵 | `KafkaReliableTemplate.sendAfterCommit` | 分别执行事务提交与事务回滚 | 事务提交后发信成功，事务回滚时零发信杜绝幽灵消息 |
| **03** | 微服务推断反序列化解耦 | `ByteArrayJsonMessageConverter` | 纯净 JSON 报文转换 | 无需 `__TypeId__` 强依赖，自动推断目标 DTO 类型并解析成功 |
| **04** | @Idempotent 消费端幂等拦截 | `IdempotentException` + `AtengDeadLetterPublishingRecoverer` | 投递相同业务主键消息 | 首次消费正常，重复消息命中幂等拦截触发 Silent Commit，不进 DLT |
| **05** | 毒丸隔离与死信终点不变量 | `FatalExceptionClassifier` + `AtengKafkaErrorHandler` | 投递格式非法毒丸报文 | 0 次重试快速分流至 `*.DLT`；死信消费者异常阻断二次反弹 |
| **06** | 批量拉取与多租户上下文隔离 | `KafkaRecordExecutor.processBatchSafely` | 批量消费不同租户记录 | 单条记录执行时捕获对应租户上下文，消费完成后现场干净无污染 |
| **07** | 1MB 报文超限 Fail-Fast 拦截 | `KafkaReliableTemplate` 大报文防御 | 发送体积超过 1MB 报文 | 前置抛出 `BusinessException(BAD_REQUEST)`，阻断网络 I/O |
| **08** | 结构化宏日志与可观测性 | `[MQ-SEND]` / `[MQ-CONSUME]` / `[MQ-DLT-FORWARD]` | 发送与消费执行 | 输出结构化日志，包含 topic, partition, offset, bizKey, traceId 等元数据 |

---

## 3. 本地自验执行指南

执行以下 Maven 命令即可在纯内存环境中以并行加速模式完成端到端离线自验：

```bash
mvn test -pl ateng-boot-kafka,ateng-boot-samples/ateng-boot-sample-kafka -am -T 1C -nsu -B
```
