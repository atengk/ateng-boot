# ateng-boot-sample-kafka 高吞吐流批处理与 Offset 治理端到端自验工程

> 本样例工程基于 Spring Kafka 与 `ateng-boot-kafka` 生产级增强 Starter 构建，提供 100% 离线自洽的端到端自动化测试验证沙箱。严格恪守“非 Web 基础设施 Starter 零 Controller 污染”架构铁律，通过专职业务场景服务完整覆盖 15 大企业级生产场景。

---

## 1. 场景定位与功能基线

`ateng-boot-sample-kafka` 作为 `ateng-boot-kafka` 的 1:1 独立场景沙箱，聚焦以下核心通信场景：
- **纯内存与零物理 Broker 依赖 (Zero External Kafka Cluster)**：基于 Spring Kafka 原生测试夹具与受管容器，在本地与 CI 断网环境下实现秒级全绿离线端到端验证；
- **零 Controller 架构纯净基线 (Zero-Controller Baseline)**：彻底取缔 HTTP 接入层，所有通信与运维场景均内聚于专用场景业务服务（`*ScenarioService`）；
- **声明式流畅拓扑构建器**：使用 `KafkaTopicBuilder` 链式声明业务 Topic、分区数、副本因子、最小同步副本数 (`min.insync.replicas`)、保留周期 (`retention.ms`)、日志压实 (`.compact()`)、强类型消息限制 (`.maxMessageBytes(DataSize)`) 与 `.withDlt()` 一键附带死信 Topic；
- **生产端可靠投递与微批调优**：使用 `KafkaReliableTemplate` 执行同步阻塞等待 RecordMetadata、异步投递与原生分布式事务 (`executeInTransaction`)；
- **本地事务防幽灵发信**：`sendAfterCommit` 验证事务回滚零发信与事务提交物理发信，配合 `KafkaTransactionFailureCallback` SPI 实现发信失败兜底；
- **死信受控治理闭环与结构化诊断**：`KafkaDeadLetterReplayTemplate`（`peek`、`replay`、`purge`）配合 `KafkaDeadLetterHelper`（7 类 DLT 头结构化解析），注入重放计数并防二次雪崩熔断；
- **双轨协同重试体系**：毫秒级瞬态抖动本地快速退避 + 慢业务 `@RetryableTopic` 阶梯延迟主题流转；
- **运行期位移治理与实时 Lag 探测**：`KafkaOffsetManager` 按时间戳回拨对账重放与各分区实时 Lag 计算；
- **运行期消费流控与维护窗口削峰**：`KafkaContainerManager` 支持动态暂停与恢复消费拉取。

---

## 2. 专职业务场景组件清单

| 场景服务组件 | 核心职责 | 演示场景 |
| :--- | :--- | :--- |
| `TradeKafkaScenarioService` | 交易事件与可靠发信服务 | Key 分区保序同步发送、异步发信、物理分区投递、本地事务感知发信（`sendAfterCommit`）、Kafka 原生事务原子多写（`executeInTransaction`） |
| `DeadLetterReplayScenarioService` | 死信治理与诊断重放服务 | 毒丸投递、7 类 DLT 消息头结构化解析诊断（`KafkaDeadLetterHelper`）、死信只读探查（`peek`）、受控重放与防二次雪崩熔断（`replay`）、物理清空（`purge`） |
| `BatchLogScenarioService` | 批量日志多租户安全隔离服务 | 批量发送、基于 `KafkaRecordExecutor` 逐条激活/还原租户上下文、逐条容错执行防单点故障阻断批次 |
| `NonBlockingRetryScenarioService` | 非阻塞阶梯重试服务 | 基于 `@RetryableTopic` 声明阶梯延迟重试（`topic-retry-100`、`topic-retry-200`、`topic-dlt`），全链路透传四维上下文 |
| `KafkaOpsScenarioService` | 运维治理与容器流控服务 | 消费位移按时间戳回拨、边界保护、实时全分区 Lag 积压监控（`KafkaOffsetManager`）、运行期消费容器动态暂停与恢复（`KafkaContainerManager`） |

---

## 3. 核心集成验证清单 (15 大全场景覆盖)

| 序号 | 验证场景 | 覆盖核心门面 / API | 测试方式 / 动作 | 预期校验点 |
| :---: | :--- | :--- | :--- | :--- |
| **01** | Key 分区保序与可靠收发 | `TradeKafkaScenarioService` / `KafkaReliableTemplate.sendReliable` | 投递订单与用户事件，指定 Key 与分区 | 同步与异步发送成功，单条消费者按 Key 正确消费，四维上下文 MDC 完整保留 |
| **02** | 本地事务感知发信与回滚防幽灵 | `TradeKafkaScenarioService.createOrderWithSendAfterCommit` | 分别执行事务提交与事务回滚 | 事务提交后发信成功，事务回滚时零发信杜绝幽灵消息 |
| **03** | Kafka 原生分布式事务执行 | `TradeKafkaScenarioService.executeTradeAtomicMultiSend` | 原生事务内原子多写订单与审计事件 | 事务正常提交，双主题消息均成功投递并携带四维上下文 |
| **04** | 微服务推断反序列化解耦 | `ByteArrayJsonMessageConverter` | 纯净 JSON 报文单对象与泛型集合转换 | 无需 `__TypeId__` 强依赖，自动推断目标 DTO 类型与泛型集合解析成功 |
| **05** | @Idempotent 消费端幂等拦截 | `IdempotentException` + `AtengDeadLetterPublishingRecoverer` | 投递相同业务主键消息 | 首次消费正常，重复消息命中幂等拦截触发 Silent Commit，不进 DLT |
| **06** | 毒丸隔离与死信终点不变量 | `FatalExceptionClassifier` + `AtengKafkaErrorHandler` | 投递格式非法毒丸报文 | 0 次重试快速分流至 `*.DLT`；死信消费者异常阻断二次循环反弹 |
| **07** | 死信结构化诊断 | `DeadLetterReplayScenarioService.diagnose` / `KafkaDeadLetterHelper` | 提取并解析 DLT 消息头 | 7 类元数据（原主题、分区、位移、时间戳、异常类、消息、堆栈）100% 结构化强类型还原 |
| **08** | 死信安全重放与防二次雪崩 | `DeadLetterReplayScenarioService.replayDeadLetters` | 执行死信消息受控重放回业务主题 | 消息头自动清洗、注入重放计数；超出最大重放次数强制阻断防死信雪崩 |
| **09** | 死信主题探查与物理清空 | `DeadLetterReplayScenarioService.peekDeadLetters` / `purgeDeadLetters` | 执行只读探查与物理清理 | 只读 peek 不提交消费位移，purge 推进起始位移清空死信积压 |
| **10** | 批量拉取与多租户上下文隔离 | `BatchLogScenarioService.processBatchSafely` / `KafkaRecordExecutor` | 批量消费不同租户记录，模拟单条故障 | 单条记录执行时捕获对应租户上下文，消费完成后现场干净；单条故障不阻断批次 |
| **11** | 1MB 报文超限 Fail-Fast 拦截 | `KafkaReliableTemplate` 大报文防御 | 发送体积超过 1MB 报文 | 前置抛出 `BusinessException(BAD_REQUEST)`，阻断网络 I/O |
| **12** | 声明式日志压实拓扑构建 | `KafkaTopicBuilder.compact` / `maxMessageBytes` | 构建状态快照与大报文主题 | 生成 NewTopic 配置正确包含 `cleanup.policy=compact` 与 `max.message.bytes` |
| **13** | 非阻塞阶梯延迟重试 | `NonBlockingRetryScenarioService.onMessage` / `@RetryableTopic` | 模拟慢业务故障流转 | 消息按阶梯延迟主题流转，不阻塞主分区，四维上下文全程透传 |
| **14** | 运行期位移治理与实时 Lag 探测 | `KafkaOpsScenarioService` / `KafkaOffsetManager` | 执行时间戳回拨与 Lag 探测 | 位移成功重置（超范围平滑对齐 LEO），实时计算全分区 Lag 与 totalLag |
| **15** | 运行期消费容器动态流控 | `KafkaOpsScenarioService.pauseConsumer` / `resumeConsumer` | 动态暂停与恢复指定主题消费容器 | 维护窗口容器暂停停止拉取，维护完成后恢复继续消费 |

---

## 4. 本地自验执行指南

执行以下 Maven 命令即可在纯内存环境中以并行加速模式完成端到端离线自验：

```bash
mvn test -pl ateng-boot-kafka,ateng-boot-samples/ateng-boot-sample-kafka -am -T 1C -nsu -B
```
