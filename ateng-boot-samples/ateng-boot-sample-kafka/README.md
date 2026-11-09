# ateng-boot-sample-kafka 高吞吐流批通信与 Offset 治理端到端自验工程

> 专注于演示与自验 `ateng-boot-kafka` 生产级流批通信、事务发信、多租户批量隔离、死信受控治理、非阻塞阶梯重试、运行时位移治理与容器流控在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `src/main/resources/application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中直接运行 SampleKafkaApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-kafka
  ```

---

## 2. 运行环境与双模切换

- **默认 / CI 模式**：内置 `@Profile("!local")` 离线内存测试桩与自洽配置，无需外部真实 Kafka Broker 即可秒级启动与跑通端到端测试；
- **Local 真实模式**：若需连接真实 Kafka 集群，复制 `src/main/resources/application-local.example.yml` 为 `src/main/resources/application-local.yml` 并配置真实 Broker 地址（已被 `.gitignore` 忽略，CI 零风险），激活 `local` Profile 启动即可直连真实 Kafka 集群测试。

---

## 3. 核心 Controller 接口与一键体验

工程按业务领域拆分为 5 大独立控制器，提供 23 个强类型 REST 端点：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **同步可靠投递订单** | `KafkaProduceController` | `POST /sample/kafka/produce/order-sync` | `curl -s -X POST "http://localhost:8080/sample/kafka/produce/order-sync" -H "Content-Type: application/json" -d '{"orderId":"ORD-1001","amount":199.9,"userId":"U1001"}'` |
| **异步非阻塞投递订单** | `KafkaProduceController` | `POST /sample/kafka/produce/order-async` | `curl -s -X POST "http://localhost:8080/sample/kafka/produce/order-async" -H "Content-Type: application/json" -d '{"orderId":"ORD-1002","amount":299.0,"userId":"U1002"}'` |
| **异步投递用户行为事件** | `KafkaProduceController` | `POST /sample/kafka/produce/user-event-async` | `curl -s -X POST "http://localhost:8080/sample/kafka/produce/user-event-async" -H "Content-Type: application/json" -d '{"userId":"U1003","eventType":"LOGIN","page":"/home"}'` |
| **本地事务提交感知发信** | `KafkaProduceController` | `POST /sample/kafka/produce/order-tx-commit` | `curl -s -X POST "http://localhost:8080/sample/kafka/produce/order-tx-commit" -H "Content-Type: application/json" -d '{"orderId":"ORD-TX-001","amount":500.0,"userId":"U1004"}'` |
| **本地事务回滚零发信拦截** | `KafkaProduceController` | `POST /sample/kafka/produce/order-tx-rollback` | `curl -s -X POST "http://localhost:8080/sample/kafka/produce/order-tx-rollback" -H "Content-Type: application/json" -d '{"orderId":"ORD-TX-ROLLBACK","amount":500.0,"userId":"U1005"}'` |
| **Kafka 原生分布式事务双写** | `KafkaProduceController` | `POST /sample/kafka/produce/order-native-tx` | `curl -s -X POST "http://localhost:8080/sample/kafka/produce/order-native-tx" -H "Content-Type: application/json" -d '{"order":{"orderId":"ORD-NTX-001","amount":888.0,"userId":"U1006"},"auditContent":"CREATE_ORDER_AUDIT"}'` |
| **探查单条已消费订单列表** | `KafkaProduceController` | `GET /sample/kafka/produce/received-orders` | `curl -s -X GET "http://localhost:8080/sample/kafka/produce/received-orders"` |
| **探查本地模拟落库订单** | `KafkaProduceController` | `GET /sample/kafka/produce/db-orders` | `curl -s -X GET "http://localhost:8080/sample/kafka/produce/db-orders"` |
| **清理生产场景测试桩现场** | `KafkaProduceController` | `POST /sample/kafka/produce/clear` | `curl -s -X POST "http://localhost:8080/sample/kafka/produce/clear"` |
| **微批聚合批量发送订单** | `KafkaBatchController` | `POST /sample/kafka/batch/orders` | `curl -s -X POST "http://localhost:8080/sample/kafka/batch/orders" -H "Content-Type: application/json" -d '{"orders":[{"orderId":"ORD-B1","amount":10.0,"userId":"U1"},{"orderId":"ORD-B2","amount":20.0,"userId":"U2"}]}'` |
| **超限大报文 Fail-Fast 拦截** | `KafkaBatchController` | `POST /sample/kafka/batch/large-payload` | `curl -s -X POST "http://localhost:8080/sample/kafka/batch/large-payload?sizeBytes=1048640"` |
| **探查批量多租户隔离消费明细** | `KafkaBatchController` | `GET /sample/kafka/batch/received-logs` | `curl -s -X GET "http://localhost:8080/sample/kafka/batch/received-logs"` |
| **清理批量消费记录状态** | `KafkaBatchController` | `POST /sample/kafka/batch/clear` | `curl -s -X POST "http://localhost:8080/sample/kafka/batch/clear"` |
| **投递毒丸 0次重试分流死信** | `KafkaDeadLetterController` | `POST /sample/kafka/dead-letter/trigger-poison` | `curl -s -X POST "http://localhost:8080/sample/kafka/dead-letter/trigger-poison?poisonData=CORRUPTED_POISON"` |
| **消费端幂等防重与静默确认** | `KafkaDeadLetterController` | `POST /sample/kafka/dead-letter/trigger-idempotent` | `curl -s -X POST "http://localhost:8080/sample/kafka/dead-letter/trigger-idempotent?silent=true" -H "Content-Type: application/json" -d '{"orderId":"ORD-IDEMP-001","amount":100.0,"userId":"U1"}'` |
| **探查死信 7 类元数据诊断记录** | `KafkaDeadLetterController` | `GET /sample/kafka/dead-letter/audit-records` | `curl -s -X GET "http://localhost:8080/sample/kafka/dead-letter/audit-records"` |
| **只读探查死信队列积压消息** | `KafkaDeadLetterController` | `GET /sample/kafka/dead-letter/peek` | `curl -s -X GET "http://localhost:8080/sample/kafka/dead-letter/peek?dltTopic=sample.poison.topic.DLT&limit=10"` |
| **死信受控清洗重放至业务主题** | `KafkaDeadLetterController` | `POST /sample/kafka/dead-letter/replay` | `curl -s -X POST "http://localhost:8080/sample/kafka/dead-letter/replay" -H "Content-Type: application/json" -d '{"dlqTopic":"sample.poison.topic.DLT","targetTopic":"sample.order.topic","limit":100}'` |
| **物理清空死信队列位移积压** | `KafkaDeadLetterController` | `POST /sample/kafka/dead-letter/purge` | `curl -s -X POST "http://localhost:8080/sample/kafka/dead-letter/purge?dltTopic=sample.poison.topic.DLT"` |
| **投递消息触发阶梯非阻塞重试** | `KafkaRetryController` | `POST /sample/kafka/retry/trigger` | `curl -s -X POST "http://localhost:8080/sample/kafka/retry/trigger?orderId=ORD-RETRY-001&fail=true"` |
| **探查阶梯重试流转审计日志** | `KafkaRetryController` | `GET /sample/kafka/retry/logs` | `curl -s -X GET "http://localhost:8080/sample/kafka/retry/logs"` |
| **查询消费组当前已提交位移** | `KafkaOpsController` | `GET /sample/kafka/ops/committed-offsets` | `curl -s -X GET "http://localhost:8080/sample/kafka/ops/committed-offsets?groupId=sample-group"` |
| **按指定策略精准回拨消费位移** | `KafkaOpsController` | `POST /sample/kafka/ops/reset-offsets` | `curl -s -X POST "http://localhost:8080/sample/kafka/ops/reset-offsets" -H "Content-Type: application/json" -d '{"groupId":"sample-group","topic":"sample.order.topic","resetType":"EARLIEST"}'` |
| **实时查询消费组全分区 Lag** | `KafkaOpsController` | `GET /sample/kafka/ops/lag` | `curl -s -X GET "http://localhost:8080/sample/kafka/ops/lag?groupId=sample-group&topic=sample.order.topic"` |
| **动态管控监听容器生命周期** | `KafkaOpsController` | `POST /sample/kafka/ops/container-action` | `curl -s -X POST "http://localhost:8080/sample/kafka/ops/container-action" -H "Content-Type: application/json" -d '{"containerIdOrTopic":"sample.order.topic","action":"PAUSE"}'` |
| **查询消费监听容器运行与暂停态** | `KafkaOpsController` | `GET /sample/kafka/ops/container-status` | `curl -s -X GET "http://localhost:8080/sample/kafka/ops/container-status?idOrTopic=sample.order.topic"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-kafka,ateng-boot-samples/ateng-boot-sample-kafka -am -T 1C -nsu -B
```

> 详细架构原理、机制边界说明与完整 YAML 配置清单，请参阅核心 Starter 文档：[ateng-boot-kafka README](../../ateng-boot-kafka/README.md)。
