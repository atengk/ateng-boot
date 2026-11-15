# ateng-boot-sample-rocketmq RocketMQ 5.x 生产级全场景通信体验工程

> 专注于演示与自验 `ateng-boot-rocketmq` 核心特性在真实交易业务场景下的开箱使用，提供 6 大领域 REST 控制器与 100% 离线自洽双模沙箱。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `application.yml` 中修改）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleRocketmqApplication.java 的 main 方法（需本机可用 127.0.0.1:8081 Proxy 或配置 application-local.yml）
  # 方式二：命令行启动应用（默认连接 endpoints 配置的 Proxy；离线集成/Controller/E2E 全场景请执行第 4 节 mvn test）
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-rocketmq
  # 方式三：启用 local Profile 直连真实 RocketMQ 5.x Proxy
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-rocketmq "-Dspring-boot.run.profiles=local"
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 离线测试模式**：在 `src/test` 测试上下文中内置 `@Profile("!local")` `@TestConfiguration` 内存桩 `InMemoryRocketmqBroker` 与自洽配置，无需外部 RocketMQ 5.x Proxy/Namesrv 即可 100% 离线跑通全部单测、MockMvc 与 E2E 测试（注：直接执行 `mvn spring-boot:run` 的主运行期不含测试内存桩，需确保 `127.0.0.1:8081` 或 `application-local.yml` 指向可用 Proxy）。
- **Local 真实模式**：`application.yml` 已配置 `spring.config.import: "optional:classpath:application-local.yml"`。若需连接真实 RocketMQ 5.x 集群，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实接入点与凭证（已被 `.gitignore` 忽略，严禁提交真实凭据），激活 `local` Profile 启动或测试即可直连真实集群。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 6 大领域 REST 控制器（共 43 个交互端点）发起一键体验与运行期状态观测：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **标准同步发信** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/sync` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/sync" -H "Content-Type: application/json" -d '{"orderId":"ORD-WEB-001","amount":199.9,"customerName":"张三","tag":"create"}'` |
| **异步非阻塞发信** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/async` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/async" -H "Content-Type: application/json" -d '{"orderId":"ORD-WEB-002","amount":299.9,"customerName":"李四"}'` |
| **单向极速埋点** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/oneway` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/oneway" -H "Content-Type: application/json" -d '{"orderId":"ORD-WEB-003","amount":9.9,"customerName":"王五"}'` |
| **FIFO 局部保序** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/fifo` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/fifo" -H "Content-Type: application/json" -d '{"orderId":"ORD-FIFO-001","amount":88.0,"customerName":"赵六","messageGroup":"GRP_USER_1001"}'` |
| **SQL92 属性过滤** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/sql` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/sql" -H "Content-Type: application/json" -d '{"orderId":"ORD-SQL-001","amount":250.0,"customerName":"大额买家"}'` |
| **同步批量自动切片发信** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/batch` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/batch" -H "Content-Type: application/json" -d '[{"orderId":"ORD-B-1","amount":10.0},{"orderId":"ORD-B-2","amount":20.0}]'` |
| **异步批量自动切片发信** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/batch-async` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/batch-async" -H "Content-Type: application/json" -d '[{"orderId":"ORD-BA-1","amount":10.0},{"orderId":"ORD-BA-2","amount":20.0}]'` |
| **查询常规消费审计记录** | `RocketmqProduceController` | `GET /sample/rocketmq/produce/received` | `curl -s "http://localhost:8080/sample/rocketmq/produce/received"` |
| **清空常规消费审计记录** | `RocketmqProduceController` | `DELETE /sample/rocketmq/produce/clear` | `curl -s -X DELETE "http://localhost:8080/sample/rocketmq/produce/clear"` |
| **同步相对时长延时发信** | `RocketmqDelayController` | `POST /sample/rocketmq/delay/duration` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/delay/duration" -H "Content-Type: application/json" -d '{"orderId":"ORD-DELAY-001","amount":88.0,"delaySeconds":60}'` |
| **异步相对时长延时发信** | `RocketmqDelayController` | `POST /sample/rocketmq/delay/duration-async` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/delay/duration-async" -H "Content-Type: application/json" -d '{"orderId":"ORD-DELAY-002","amount":88.0,"delaySeconds":60}'` |
| **同步绝对时间定时调度** | `RocketmqDelayController` | `POST /sample/rocketmq/delay/deliver-at` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/delay/deliver-at" -H "Content-Type: application/json" -d '{"orderId":"ORD-DELAY-003","amount":99.0,"futureSeconds":120}'` |
| **异步绝对时间定时调度** | `RocketmqDelayController` | `POST /sample/rocketmq/delay/deliver-at-async` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/delay/deliver-at-async" -H "Content-Type: application/json" -d '{"orderId":"ORD-DELAY-004","amount":99.0,"futureSeconds":120}'` |
| **原生延时精准撤回** | `RocketmqDelayController` | `POST /sample/rocketmq/delay/recall` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/delay/recall" -H "Content-Type: application/json" -d '{"recallHandle":"RECALL-HANDLE-FROM-RECEIPT"}'` |
| **查询延时消费审计记录** | `RocketmqDelayController` | `GET /sample/rocketmq/delay/received` | `curl -s "http://localhost:8080/sample/rocketmq/delay/received"` |
| **清空延时消费审计记录** | `RocketmqDelayController` | `DELETE /sample/rocketmq/delay/clear` | `curl -s -X DELETE "http://localhost:8080/sample/rocketmq/delay/clear"` |
| **2PC 事务半消息发信** | `RocketmqTransactionController` | `POST /sample/rocketmq/transaction/half-message` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/transaction/half-message" -H "Content-Type: application/json" -d '{"orderId":"ORD-TX-001","amount":666.0,"localSuccess":true}'` |
| **单条本地事务感知发信** | `RocketmqTransactionController` | `POST /sample/rocketmq/transaction/send-after-commit` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/transaction/send-after-commit" -H "Content-Type: application/json" -d '{"orderId":"ORD-PAC-001","amount":333.0,"localSuccess":true}'` |
| **批量本地事务感知发信** | `RocketmqTransactionController` | `POST /sample/rocketmq/transaction/send-batch-after-commit` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/transaction/send-batch-after-commit" -H "Content-Type: application/json" -d '[{"orderId":"ORD-PB-1","amount":120.0,"localSuccess":true},{"orderId":"ORD-PB-2","amount":150.0,"localSuccess":true}]'` |
| **事务回查状态审计** | `RocketmqTransactionController` | `GET /sample/rocketmq/transaction/checker/status` | `curl -s "http://localhost:8080/sample/rocketmq/transaction/checker/status"` |
| **查询事务消费审计记录** | `RocketmqTransactionController` | `GET /sample/rocketmq/transaction/received` | `curl -s "http://localhost:8080/sample/rocketmq/transaction/received"` |
| **清空事务审计与状态** | `RocketmqTransactionController` | `DELETE /sample/rocketmq/transaction/clear` | `curl -s -X DELETE "http://localhost:8080/sample/rocketmq/transaction/clear"` |
| **流式微批长轮询拉取** | `RocketmqSimpleConsumerController` | `POST /sample/rocketmq/simple-consumer/pull` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/simple-consumer/pull" -H "Content-Type: application/json" -d '{"maxNum":10,"invisibleSeconds":30}'` |
| **动态续期不可见租约** | `RocketmqSimpleConsumerController` | `POST /sample/rocketmq/simple-consumer/renew` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/simple-consumer/renew" -H "Content-Type: application/json" -d '{"messageId":"YOUR_MESSAGE_ID","invisibleSeconds":60}'` |
| **显式确认出队 (ACK)** | `RocketmqSimpleConsumerController` | `POST /sample/rocketmq/simple-consumer/ack` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/simple-consumer/ack" -H "Content-Type: application/json" -d '{"messageId":"YOUR_MESSAGE_ID"}'` |
| **函数式一步闭包消费** | `RocketmqSimpleConsumerController` | `POST /sample/rocketmq/simple-consumer/consume` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/simple-consumer/consume" -H "Content-Type: application/json" -d '{"maxNum":5}'` |
| **单条容错与自动续期消费** | `RocketmqSimpleConsumerController` | `POST /sample/rocketmq/simple-consumer/consume-resilient` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/simple-consumer/consume-resilient" -H "Content-Type: application/json" -d '{"maxNum":5,"invisibleSeconds":2,"simulateDelayMillis":2500}'` |
| **待决租约句柄审计** | `RocketmqSimpleConsumerController` | `GET /sample/rocketmq/simple-consumer/cached-views` | `curl -s "http://localhost:8080/sample/rocketmq/simple-consumer/cached-views"` |
| **查询流式消费记录** | `RocketmqSimpleConsumerController` | `GET /sample/rocketmq/simple-consumer/consumed` | `curl -s "http://localhost:8080/sample/rocketmq/simple-consumer/consumed"` |
| **清空流式消费状态** | `RocketmqSimpleConsumerController` | `DELETE /sample/rocketmq/simple-consumer/clear` | `curl -s -X DELETE "http://localhost:8080/sample/rocketmq/simple-consumer/clear"` |
| **全量消费端点元数据** | `RocketmqContainerController` | `GET /sample/rocketmq/container/endpoints` | `curl -s "http://localhost:8080/sample/rocketmq/container/endpoints"` |
| **不可变容器状态快照** | `RocketmqContainerController` | `GET /sample/rocketmq/container/statuses` | `curl -s "http://localhost:8080/sample/rocketmq/container/statuses"` |
| **动态暂停端点削峰** | `RocketmqContainerController` | `POST /sample/rocketmq/container/pause` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/container/pause" -H "Content-Type: application/json" -d '{"endpointId":"sample_order_group#sample_order_topic"}'` |
| **恢复端点正常消费** | `RocketmqContainerController` | `POST /sample/rocketmq/container/resume` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/container/resume" -H "Content-Type: application/json" -d '{"endpointId":"sample_order_group#sample_order_topic"}'` |
| **物理关闭底层连接** | `RocketmqContainerController` | `POST /sample/rocketmq/container/stop` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/container/stop" -H "Content-Type: application/json" -d '{"endpointId":"sample_order_group#sample_order_topic"}'` |
| **启动底层物理连接** | `RocketmqContainerController` | `POST /sample/rocketmq/container/start` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/container/start" -H "Content-Type: application/json" -d '{"endpointId":"sample_order_group#sample_order_topic"}'` |
| **全局暂停所有消费端点** | `RocketmqContainerController` | `POST /sample/rocketmq/container/pause-all` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/container/pause-all"` |
| **全局恢复所有消费端点** | `RocketmqContainerController` | `POST /sample/rocketmq/container/resume-all` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/container/resume-all"` |
| **死信只读探查 (零ACK)** | `RocketmqDeadLetterController` | `POST /sample/rocketmq/dead-letter/peek` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/dead-letter/peek" -H "Content-Type: application/json" -d '{"consumerGroup":"sample_poison_group","limit":10}'` |
| **死信结构化诊断窥视** | `RocketmqDeadLetterController` | `POST /sample/rocketmq/dead-letter/peek-diagnosed` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/dead-letter/peek-diagnosed" -H "Content-Type: application/json" -d '{"consumerGroup":"sample_poison_group","limit":10}'` |
| **触发样本毒丸转储 %DLQ%** | `RocketmqDeadLetterController` | `POST /sample/rocketmq/dead-letter/poison-pill` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/dead-letter/poison-pill" -H "Content-Type: application/json" -d '{"mode":"FATAL","orderId":"ORD-POISON-001"}'` |
| **死信受控清洗重放** | `RocketmqDeadLetterController` | `POST /sample/rocketmq/dead-letter/replay` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/dead-letter/replay" -H "Content-Type: application/json" -d '{"consumerGroup":"sample_poison_group","limit":10}'` |
| **死信批量物理丢弃** | `RocketmqDeadLetterController` | `POST /sample/rocketmq/dead-letter/discard` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/dead-letter/discard" -H "Content-Type: application/json" -d '{"consumerGroup":"sample_poison_group","limit":10}'` |

---

## 4. 全真自动化回归命令

在项目根目录下执行以下命令，即可在离线内存沙箱环境中以并行加速模式回归 Starter 与 Sample 全部测试：

```bash
mvn test -pl "ateng-boot-rocketmq,ateng-boot-samples/ateng-boot-sample-rocketmq" -am -T 1C -nsu -B
```

> 详细架构原理、生产级设计不变量、11 大章节详解与完整配置清单，请参阅核心 Starter 文档：[ateng-boot-rocketmq README](../../ateng-boot-rocketmq/README.md)。
