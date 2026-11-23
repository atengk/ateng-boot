# ateng-boot-sample-rabbitmq 生产级路由、事务、运维与可靠投递体验工程

> 专注于演示与自验 `ateng-boot-rabbitmq` 生产级增强 Starter 在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `src/main/resources/application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleRabbitmqApplication.java 的 main 方法
  # 方式二：命令行启动（默认激活离线内存模拟 Broker，无需外部 RabbitMQ）
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-rabbitmq
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式 (`!local`)**：内置 `@Profile("!local")` 离线自洽模拟装配（`OfflineAmqpConfiguration`、`OfflineAmqpBroker` 与 `OfflineRabbitTemplate`），无需外部 RabbitMQ Broker 即可直接启动全部 7 组 REST 控制器与跑通端到端测试；
- **Local 真实模式 (`local`)**：若需连接真实 RabbitMQ Broker，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实 Broker 连接信息（该文件已被 `.gitignore` 强制忽略，CI 零泄露风险；若远端未安装 `rabbitmq_delayed_message_exchange` 插件，请保持 `ateng.boot.rabbitmq.sample.delayed-plugin-enabled: false`），通过以下参数激活 `local` Profile 启动即可直连联调：
  ```bash
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-rabbitmq -Dspring-boot.run.profiles=local
  ```

---

## 3. 核心 Controller 接口与一键体验

> 全量 7 大业务控制器均遵循 `/sample/rabbitmq/<feature>/<action>` 路由规范并显式返回强类型 `Result<T>`。

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **同步可靠投递订单** | `RabbitProduceController` | `POST /sample/rabbitmq/produce/order` | `curl -X POST "http://localhost:8080/sample/rabbitmq/produce/order" -H "Content-Type: application/json" -d '{"orderId":"ORD-1001","amount":188.00,"userId":"user-01"}'` |
| **异步非阻塞可靠投递** | `RabbitProduceController` | `POST /sample/rabbitmq/produce/order-async` | `curl -X POST "http://localhost:8080/sample/rabbitmq/produce/order-async" -H "Content-Type: application/json" -d '{"orderId":"ORD-ASYNC-1002","amount":299.90,"userId":"user-02"}'` |
| **Topic 层次通配投递日志** | `RabbitProduceController` | `POST /sample/rabbitmq/produce/log` | `curl -X POST "http://localhost:8080/sample/rabbitmq/produce/log" -H "Content-Type: application/json" -d '{"routingKey":"log.error.trade","level":"ERROR","serviceName":"order-service","content":"库存扣减失败"}'` |
| **不可路由消息 Returns 告警** | `RabbitProduceController` | `POST /sample/rabbitmq/produce/unroutable` | `curl -X POST "http://localhost:8080/sample/rabbitmq/produce/unroutable?payload=unroutable-test-data"` |
| **探查已消费订单列表** | `RabbitProduceController` | `GET /sample/rabbitmq/produce/received-orders` | `curl -X GET "http://localhost:8080/sample/rabbitmq/produce/received-orders"` |
| **探查已消费系统日志** | `RabbitProduceController` | `GET /sample/rabbitmq/produce/received-logs` | `curl -X GET "http://localhost:8080/sample/rabbitmq/produce/received-logs"` |
| **清空生产消费探查记录** | `RabbitProduceController` | `DELETE /sample/rabbitmq/produce/clear` | `curl -X DELETE "http://localhost:8080/sample/rabbitmq/produce/clear"` |
| **单条事务提交物理发信** | `RabbitTransactionController` | `POST /sample/rabbitmq/transaction/commit` | `curl -X POST "http://localhost:8080/sample/rabbitmq/transaction/commit" -H "Content-Type: application/json" -d '{"orderId":"ORD-TX-COMMIT-01","amount":99.00,"userId":"user-tx"}'` |
| **单条事务异常回滚零发信** | `RabbitTransactionController` | `POST /sample/rabbitmq/transaction/rollback` | `curl -X POST "http://localhost:8080/sample/rabbitmq/transaction/rollback" -H "Content-Type: application/json" -d '{"orderId":"ORD-TX-ROLLBACK-01","amount":99.00,"userId":"user-tx"}'` |
| **批量事务提交单钩子发信** | `RabbitTransactionController` | `POST /sample/rabbitmq/transaction/batch/commit` | `curl -X POST "http://localhost:8080/sample/rabbitmq/transaction/batch/commit" -H "Content-Type: application/json" -d '{"orders":[{"orderId":"TX-B-01","amount":50.00,"userId":"u1"},{"orderId":"TX-B-02","amount":60.00,"userId":"u2"}]}'` |
| **批量事务回滚整批零发信** | `RabbitTransactionController` | `POST /sample/rabbitmq/transaction/batch/rollback` | `curl -X POST "http://localhost:8080/sample/rabbitmq/transaction/batch/rollback" -H "Content-Type: application/json" -d '{"orders":[{"orderId":"TX-RB-01","amount":50.00,"userId":"u1"}]}'` |
| **探查事务场景已消费订单** | `RabbitTransactionController` | `GET /sample/rabbitmq/transaction/records` | `curl -X GET "http://localhost:8080/sample/rabbitmq/transaction/records"` |
| **查询本地落库尝试计数** | `RabbitTransactionController` | `GET /sample/rabbitmq/transaction/db-count` | `curl -X GET "http://localhost:8080/sample/rabbitmq/transaction/db-count"` |
| **微批订单同步聚合投递** | `RabbitBatchAndPriorityController` | `POST /sample/rabbitmq/batch/orders` | `curl -X POST "http://localhost:8080/sample/rabbitmq/batch/orders" -H "Content-Type: application/json" -d '{"orders":[{"orderId":"BATCH-01","amount":10.00,"userId":"u1"},{"orderId":"BATCH-02","amount":20.00,"userId":"u2"}]}'` |
| **微批订单异步聚合投递** | `RabbitBatchAndPriorityController` | `POST /sample/rabbitmq/batch/orders-async` | `curl -X POST "http://localhost:8080/sample/rabbitmq/batch/orders-async" -H "Content-Type: application/json" -d '{"orders":[{"orderId":"BATCH-ASYNC-01","amount":15.00,"userId":"u1"}]}'` |
| **探查批量削峰已消费订单** | `RabbitBatchAndPriorityController` | `GET /sample/rabbitmq/batch/received-batches` | `curl -X GET "http://localhost:8080/sample/rabbitmq/batch/received-batches"` |
| **Quorum 队列优先级订单** | `RabbitBatchAndPriorityController` | `POST /sample/rabbitmq/priority/order` | `curl -X POST "http://localhost:8080/sample/rabbitmq/priority/order" -H "Content-Type: application/json" -d '{"order":{"orderId":"ORD-VIP-01","amount":888.88,"userId":"vip-user"},"priority":9}'` |
| **探查优先级消费订单列表** | `RabbitBatchAndPriorityController` | `GET /sample/rabbitmq/priority/received-orders` | `curl -X GET "http://localhost:8080/sample/rabbitmq/priority/received-orders"` |
| **探查优先级消费等级列表** | `RabbitBatchAndPriorityController` | `GET /sample/rabbitmq/priority/received-priorities` | `curl -X GET "http://localhost:8080/sample/rabbitmq/priority/received-priorities"` |
| **多租户混排批量隔离投递** | `RabbitBatchAndPriorityController` | `POST /sample/rabbitmq/batch/tenant-isolated` | `curl -X POST "http://localhost:8080/sample/rabbitmq/batch/tenant-isolated" -H "Content-Type: application/json" -d '{"items":[{"orderId":"TENANT-01","amount":99.00,"tenantId":"t-alpha","userId":"u-101","ignoreTenant":false},{"orderId":"TENANT-02","amount":120.00,"tenantId":"t-beta","userId":"u-202","ignoreTenant":true}]}'` |
| **探查多租户批量隔离审计** | `RabbitBatchAndPriorityController` | `GET /sample/rabbitmq/batch/tenant-records` | `curl -X GET "http://localhost:8080/sample/rabbitmq/batch/tenant-records"` |
| **清空批量与优先级消费记录** | `RabbitBatchAndPriorityController` | `DELETE /sample/rabbitmq/batch/clear` | `curl -X DELETE "http://localhost:8080/sample/rabbitmq/batch/clear"` |
| **插件延迟订单超时关单** | `RabbitDelayController` | `POST /sample/rabbitmq/delay/timeout-order` | `curl -X POST "http://localhost:8080/sample/rabbitmq/delay/timeout-order" -H "Content-Type: application/json" -d '{"orderId":"ORD-DELAY-01","timeoutSeconds":1800,"reason":"30分钟未支付超时取消"}'` |
| **原生消息级 TTL 同步投递** | `RabbitDelayController` | `POST /sample/rabbitmq/delay/expiration-order` | `curl -X POST "http://localhost:8080/sample/rabbitmq/delay/expiration-order" -H "Content-Type: application/json" -d '{"orderId":"ORD-TTL-01","timeoutSeconds":30,"reason":"原生消息级TTL过期校验"}'` |
| **原生消息级 TTL 异步投递** | `RabbitDelayController` | `POST /sample/rabbitmq/delay/expiration-order-async` | `curl -X POST "http://localhost:8080/sample/rabbitmq/delay/expiration-order-async" -H "Content-Type: application/json" -d '{"orderId":"ORD-TTL-ASYNC-01","timeoutSeconds":30,"reason":"原生消息级TTL异步校验"}'` |
| **探查超时关单消费记录** | `RabbitDelayController` | `GET /sample/rabbitmq/delay/received-orders` | `curl -X GET "http://localhost:8080/sample/rabbitmq/delay/received-orders"` |
| **清空延迟订单消费记录** | `RabbitDelayController` | `DELETE /sample/rabbitmq/delay/clear` | `curl -X DELETE "http://localhost:8080/sample/rabbitmq/delay/clear"` |
| **触发致命毒丸快速进 DLQ** | `RabbitDeadLetterController` | `POST /sample/rabbitmq/dead-letter/trigger-poison` | `curl -X POST "http://localhost:8080/sample/rabbitmq/dead-letter/trigger-poison?bizKey=biz-poison-01"` |
| **触发幂等静默确认 (Silent ACK)** | `RabbitDeadLetterController` | `POST /sample/rabbitmq/dead-letter/trigger-idempotent` | `curl -X POST "http://localhost:8080/sample/rabbitmq/dead-letter/trigger-idempotent?bizKey=order-idemp-1001"` |
| **死信受控重放与元数据清洗** | `RabbitDeadLetterController` | `POST /sample/rabbitmq/dead-letter/replay` | `curl -X POST "http://localhost:8080/sample/rabbitmq/dead-letter/replay" -H "Content-Type: application/json" -d '{"dlq":"sample.biz.dlq","targetExchange":"sample.biz.direct","targetRoutingKey":"biz.work","limit":10}'` |
| **只读探查死信队列头部积压** | `RabbitDeadLetterController` | `GET /sample/rabbitmq/dead-letter/peek` | `curl -X GET "http://localhost:8080/sample/rabbitmq/dead-letter/peek?limit=5"` |
| **清空死信队列积压消息** | `RabbitDeadLetterController` | `POST /sample/rabbitmq/dead-letter/purge` | `curl -X POST "http://localhost:8080/sample/rabbitmq/dead-letter/purge"` |
| **探查死信诊断元数据列表** | `RabbitDeadLetterController` | `GET /sample/rabbitmq/dead-letter/audit-records` | `curl -X GET "http://localhost:8080/sample/rabbitmq/dead-letter/audit-records"` |
| **探查幂等防重统计计数** | `RabbitDeadLetterController` | `GET /sample/rabbitmq/dead-letter/idempotent-counts` | `curl -X GET "http://localhost:8080/sample/rabbitmq/dead-letter/idempotent-counts"` |
| **清空死信与幂等审计记录** | `RabbitDeadLetterController` | `DELETE /sample/rabbitmq/dead-letter/clear` | `curl -X DELETE "http://localhost:8080/sample/rabbitmq/dead-letter/clear"` |
| **Fanout 全局广播发布** | `RabbitBroadcastController` | `POST /sample/rabbitmq/broadcast/publish` | `curl -X POST "http://localhost:8080/sample/rabbitmq/broadcast/publish" -H "Content-Type: application/json" -d '{"message":"SYSTEM_CONFIG_REFRESH"}'` |
| **探查匿名队列接收广播** | `RabbitBroadcastController` | `GET /sample/rabbitmq/broadcast/received` | `curl -X GET "http://localhost:8080/sample/rabbitmq/broadcast/received"` |
| **清空广播消费记录** | `RabbitBroadcastController` | `DELETE /sample/rabbitmq/broadcast/clear` | `curl -X DELETE "http://localhost:8080/sample/rabbitmq/broadcast/clear"` |
| **查询全部监听容器状态** | `RabbitContainerController` | `GET /sample/rabbitmq/container/list` | `curl -X GET "http://localhost:8080/sample/rabbitmq/container/list"` |
| **查询指定容器状态快照** | `RabbitContainerController` | `GET /sample/rabbitmq/container/status` | `curl -X GET "http://localhost:8080/sample/rabbitmq/container/status?target=sample.order.create.queue"` |
| **启动指定监听容器** | `RabbitContainerController` | `POST /sample/rabbitmq/container/start` | `curl -X POST "http://localhost:8080/sample/rabbitmq/container/start" -H "Content-Type: application/json" -d '{"target":"sample.order.create.queue"}'` |
| **停止指定监听容器** | `RabbitContainerController` | `POST /sample/rabbitmq/container/stop` | `curl -X POST "http://localhost:8080/sample/rabbitmq/container/stop" -H "Content-Type: application/json" -d '{"target":"sample.order.create.queue"}'` |
| **启动全部监听容器** | `RabbitContainerController` | `POST /sample/rabbitmq/container/start-all` | `curl -X POST "http://localhost:8080/sample/rabbitmq/container/start-all"` |
| **停止全部监听容器** | `RabbitContainerController` | `POST /sample/rabbitmq/container/stop-all` | `curl -X POST "http://localhost:8080/sample/rabbitmq/container/stop-all"` |
| **运行期调整并发消费者上下限** | `RabbitContainerController` | `POST /sample/rabbitmq/container/scale` | `curl -X POST "http://localhost:8080/sample/rabbitmq/container/scale" -H "Content-Type: application/json" -d '{"target":"sample.order.create.queue","concurrentConsumers":2,"maxConcurrentConsumers":6}'` |
| **运行期动态调节预取数** | `RabbitContainerController` | `POST /sample/rabbitmq/container/prefetch` | `curl -X POST "http://localhost:8080/sample/rabbitmq/container/prefetch" -H "Content-Type: application/json" -d '{"target":"sample.order.create.queue","prefetchCount":50}'` |
| **查询指定队列积压深度** | `RabbitContainerController` | `GET /sample/rabbitmq/container/queue-status` | `curl -X GET "http://localhost:8080/sample/rabbitmq/container/queue-status?queueName=sample.order.create.queue"` |

---

## 4. 全真自动化回归命令

```bash
# 默认离线自洽全量测试（CI / 开发者本机零依赖运行）
mvn test -pl ateng-boot-rabbitmq,ateng-boot-samples/ateng-boot-sample-rabbitmq -am -T 1C -nsu -B

# 激活 local Profile 连接真实 RabbitMQ 集群验证（需提前准备 application-local.yml）
mvn test -pl ateng-boot-samples/ateng-boot-sample-rabbitmq -Dspring.profiles.active=local -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-rabbitmq README](../../ateng-boot-rabbitmq/README.md)。
