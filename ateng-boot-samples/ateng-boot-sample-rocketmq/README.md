# ateng-boot-sample-rocketmq RocketMQ 5.x 生产级全场景通信体验工程

> 专注于演示与自验 `ateng-boot-rocketmq` 核心特性在真实交易业务场景下的开箱使用，提供多领域 REST 控制器与 100% 离线自洽双模沙箱。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `application.yml` 中修改）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleRocketmqApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-rocketmq
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置 `@Profile("!local")` 内存桩 `InMemoryRocketmqBroker` 与自洽配置，无需外部 RocketMQ 5.x Proxy/Namesrv 即可启动与跑通全部集成测试。
- **Local 真实模式**：若需连接真实 RocketMQ 5.x 集群，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并配置真实 endpoints（已被 Git 忽略，CI 零风险），激活 `local` Profile 启动即可直连真实集群。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 6 大领域 REST 控制器接口发起一键体验与状态观测：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **标准同步发信** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/sync` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/sync" -H "Content-Type: application/json" -d '{"orderId":"ORD-WEB-001","amount":199.9,"customerName":"张三","tag":"create"}'` |
| **异步非阻塞发信** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/async` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/async" -H "Content-Type: application/json" -d '{"orderId":"ORD-WEB-002","amount":299.9,"customerName":"李四"}'` |
| **单向极速埋点** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/oneway` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/oneway" -H "Content-Type: application/json" -d '{"orderId":"ORD-WEB-003","amount":9.9,"customerName":"王五"}'` |
| **FIFO 局部保序** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/fifo` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/fifo?messageGroup=GRP_USER_1001" -H "Content-Type: application/json" -d '{"orderId":"ORD-FIFO-001","amount":88.0,"customerName":"赵六"}'` |
| **SQL92 属性过滤** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/sql` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/sql?amount=250" -H "Content-Type: application/json" -d '{"orderId":"ORD-SQL-001","amount":250.0,"customerName":"大额买家"}'` |
| **批量自动切片发信** | `RocketmqProduceController` | `POST /sample/rocketmq/produce/batch` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/produce/batch" -H "Content-Type: application/json" -d '[{"orderId":"ORD-B-1","amount":10.0},{"orderId":"ORD-B-2","amount":20.0}]'` |
| **查询消费审计记录** | `RocketmqProduceController` | `GET /sample/rocketmq/produce/received` | `curl -s "http://localhost:8080/sample/rocketmq/produce/received"` |
| **相对时长延时发信** | `RocketmqDelayController` | `POST /sample/rocketmq/delay/duration` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/delay/duration" -H "Content-Type: application/json" -d '{"orderId":"ORD-DELAY-001","amount":88.0,"delaySeconds":60}'` |
| **绝对时间定时调度** | `RocketmqDelayController` | `POST /sample/rocketmq/delay/deliver-at` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/delay/deliver-at" -H "Content-Type: application/json" -d '{"orderId":"ORD-DELAY-002","amount":99.0,"futureSeconds":120}'` |
| **原生延时精准撤回** | `RocketmqDelayController` | `POST /sample/rocketmq/delay/recall` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/delay/recall" -H "Content-Type: application/json" -d '{"recallHandle":"RECALL-HANDLE-FROM-RECEIPT"}'` |
| **2PC 事务半消息发信** | `RocketmqTransactionController` | `POST /sample/rocketmq/transaction/half-message` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/transaction/half-message" -H "Content-Type: application/json" -d '{"orderId":"ORD-TX-001","amount":666.0,"localSuccess":true}'` |
| **本地事务感知发信** | `RocketmqTransactionController` | `POST /sample/rocketmq/transaction/send-after-commit` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/transaction/send-after-commit" -H "Content-Type: application/json" -d '{"orderId":"ORD-PAC-001","amount":333.0,"localSuccess":true}'` |
| **事务回查状态审计** | `RocketmqTransactionController` | `GET /sample/rocketmq/transaction/checker/status` | `curl -s "http://localhost:8080/sample/rocketmq/transaction/checker/status"` |
| **流式微批长轮询拉取** | `RocketmqSimpleConsumerController` | `POST /sample/rocketmq/simple-consumer/pull` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/simple-consumer/pull" -H "Content-Type: application/json" -d '{"maxNum":10,"invisibleSeconds":30}'` |
| **动态续期不可见租约** | `RocketmqSimpleConsumerController` | `POST /sample/rocketmq/simple-consumer/renew` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/simple-consumer/renew" -H "Content-Type: application/json" -d '{"messageId":"YOUR_MESSAGE_ID","invisibleSeconds":60}'` |
| **显式确认出队 (ACK)** | `RocketmqSimpleConsumerController` | `POST /sample/rocketmq/simple-consumer/ack` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/simple-consumer/ack" -H "Content-Type: application/json" -d '{"messageId":"YOUR_MESSAGE_ID"}'` |
| **函数式一步闭包消费** | `RocketmqSimpleConsumerController` | `POST /sample/rocketmq/simple-consumer/consume` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/simple-consumer/consume" -H "Content-Type: application/json" -d '{"maxNum":5}'` |
| **待决租约句柄审计** | `RocketmqSimpleConsumerController` | `GET /sample/rocketmq/simple-consumer/cached-views` | `curl -s "http://localhost:8080/sample/rocketmq/simple-consumer/cached-views"` |
| **全量消费端点审计** | `RocketmqContainerController` | `GET /sample/rocketmq/container/endpoints` | `curl -s "http://localhost:8080/sample/rocketmq/container/endpoints"` |
| **动态暂停端点削峰** | `RocketmqContainerController` | `POST /sample/rocketmq/container/pause` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/container/pause" -H "Content-Type: application/json" -d '{"endpointId":"sample_order_group#sample_order_topic"}'` |
| **恢复端点正常消费** | `RocketmqContainerController` | `POST /sample/rocketmq/container/resume` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/container/resume" -H "Content-Type: application/json" -d '{"endpointId":"sample_order_group#sample_order_topic"}'` |
| **物理关闭底层连接** | `RocketmqContainerController` | `POST /sample/rocketmq/container/stop` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/container/stop" -H "Content-Type: application/json" -d '{"endpointId":"sample_order_group#sample_order_topic"}'` |
| **启动底层物理连接** | `RocketmqContainerController` | `POST /sample/rocketmq/container/start` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/container/start" -H "Content-Type: application/json" -d '{"endpointId":"sample_order_group#sample_order_topic"}'` |
| **死信只读探查 (零ACK)** | `RocketmqDeadLetterController` | `POST /sample/rocketmq/dead-letter/peek` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/dead-letter/peek" -H "Content-Type: application/json" -d '{"consumerGroup":"sample_order_group","limit":10}'` |
| **死信受控清洗重放** | `RocketmqDeadLetterController` | `POST /sample/rocketmq/dead-letter/replay` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/dead-letter/replay" -H "Content-Type: application/json" -d '{"consumerGroup":"sample_order_group","limit":10}'` |
| **死信批量物理丢弃** | `RocketmqDeadLetterController` | `POST /sample/rocketmq/dead-letter/discard` | `curl -s -X POST "http://localhost:8080/sample/rocketmq/dead-letter/discard" -H "Content-Type: application/json" -d '{"consumerGroup":"sample_order_group","limit":10}'` |

---

## 4. 全真自动化回归命令

在项目根目录下执行以下命令，即可在离线内存沙箱环境中以并行加速模式回归全套测试：

```bash
mvn test -pl ateng-boot-rocketmq,ateng-boot-samples/ateng-boot-sample-rocketmq -am -T 1C -nsu -B
```

> 详细架构原理、生产级设计不变量、10 大能力领域详解与完整配置清单，请参阅核心 Starter 文档：[ateng-boot-rocketmq README](../../ateng-boot-rocketmq/README.md)。
