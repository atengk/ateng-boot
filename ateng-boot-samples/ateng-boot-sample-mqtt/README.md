# ateng-boot-sample-mqtt 物联网 MQTT 5.0 / 3.1.1 场景体验工程

> 专注于演示与自验 `ateng-boot-mqtt` 核心特性在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `application.yml` 中修改）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleMqttApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-mqtt
  ```

---

## 2. 运行环境与双模切换

- **默认 / CI 模式**：内置 `@Profile("!local")` 离线内存 Broker 桩（`EmbeddedMqttBroker`）与自洽配置，无需安装外部 EMQX / Mosquitto 服务即可直接启动与跑通测试；
- **Local 真实模式**：若需连接真实 MQTT Broker，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并配置实际 Broker 连接信息（已被 `.gitignore` 忽略，安全无泄漏风险），激活 `local` Profile 启动即可直连调试：
  ```bash
  # 命令行激活 local profile
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-mqtt -Dspring-boot.run.profiles=local
  ```

---

## 3. 核心 Controller 接口与一键体验

工程按物联网通信业务领域拆分为 7 大独立 `@RestController`，对外暴露统一的强类型 `Result<T>` 接口，支持通过标准 cURL 或 Postman 一键发起交互体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **设备遥测上报与 4D 上下文回显** | `MqttTelemetryController` | `POST /sample/mqtt/telemetry/publish` | `curl -X POST "http://localhost:8080/sample/mqtt/telemetry/publish" -H "Content-Type: application/json" -H "X-Trace-Id: trace-web-1001" -d '{"deviceId":"DEV-WEB-1001","temp":28.4,"humidity":65.2,"qos":1}'` |
| **最新遥测快照查询** | `MqttTelemetryController` | `GET /sample/mqtt/telemetry/latest` | `curl -X GET "http://localhost:8080/sample/mqtt/telemetry/latest?deviceId=DEV-WEB-1001"` |
| **集群共享订阅指标上报** | `MqttTelemetryController` | `POST /sample/mqtt/telemetry/cluster-metric` | `curl -X POST "http://localhost:8080/sample/mqtt/telemetry/cluster-metric?metric=cpu_load_85"` |
| **同步阻塞 RPC 控制指令** | `MqttRpcController` | `POST /sample/mqtt/rpc/control-sync` | `curl -X POST "http://localhost:8080/sample/mqtt/rpc/control-sync" -H "Content-Type: application/json" -d '{"action":"REBOOT","speed":50,"timeoutSeconds":3}'` |
| **异步非阻塞 RPC 控制指令** | `MqttRpcController` | `POST /sample/mqtt/rpc/control-async` | `curl -X POST "http://localhost:8080/sample/mqtt/rpc/control-async" -H "Content-Type: application/json" -d '{"action":"START_FAN","speed":80,"timeoutSeconds":3}'` |
| **工业国标十六进制报文双向转译** | `MqttIndustrialHexController` | `POST /sample/mqtt/hex/publish` | `curl -X POST "http://localhost:8080/sample/mqtt/hex/publish" -H "Content-Type: application/json" -d '{"deviceId":"DEV-HEX-001","hexString":"68AAAAAAAAAAAA6811043333333316"}'` |
| **批量并发原子下发** | `MqttBatchAndRetainedController` | `POST /sample/mqtt/batch/publish` | `curl -X POST "http://localhost:8080/sample/mqtt/batch/publish" -H "Content-Type: application/json" -d '{"items":[{"topic":"iot/devices/DEV-01/cmd","payload":"CMD_START","qos":1},{"topic":"iot/devices/DEV-02/cmd","payload":"CMD_STOP","qos":1}]}'` |
| **设备保留消息（影子状态）发布** | `MqttBatchAndRetainedController` | `POST /sample/mqtt/batch/retained-status` | `curl -X POST "http://localhost:8080/sample/mqtt/batch/retained-status" -H "Content-Type: application/json" -d '{"deviceId":"DEV-ONLINE-01","status":"ONLINE","qos":1}'` |
| **对称清理设备保留消息** | `MqttBatchAndRetainedController` | `DELETE /sample/mqtt/batch/clear-retained` | `curl -X DELETE "http://localhost:8080/sample/mqtt/batch/clear-retained?deviceId=DEV-ONLINE-01"` |
| **死信 DLT 毒丸消息注入** | `MqttDeadLetterController` | `POST /sample/mqtt/dead-letter/trigger-poison` | `curl -X POST "http://localhost:8080/sample/mqtt/dead-letter/trigger-poison?payload=POISON_PAYLOAD"` |
| **死信队列离线只读窥探** | `MqttDeadLetterController` | `GET /sample/mqtt/dead-letter/peek` | `curl -X GET "http://localhost:8080/sample/mqtt/dead-letter/peek?topic=iot/devices/poison-test/DLT&count=5&timeoutSeconds=2"` |
| **死信受控安全重放至恢复主题** | `MqttDeadLetterController` | `POST /sample/mqtt/dead-letter/replay` | `curl -X POST "http://localhost:8080/sample/mqtt/dead-letter/replay" -H "Content-Type: application/json" -d '{"sourceTopic":"iot/devices/poison-test/DLT","targetTopic":"iot/devices/poison-recovered","count":5,"timeoutSeconds":2}'` |
| **彻底清空死信队列** | `MqttDeadLetterController` | `DELETE /sample/mqtt/dead-letter/purge` | `curl -X DELETE "http://localhost:8080/sample/mqtt/dead-letter/purge?topic=iot/devices/poison-test/DLT"` |
| **查询消费端点实时运行状态** | `MqttContainerController` | `GET /sample/mqtt/container/endpoints` | `curl -X GET "http://localhost:8080/sample/mqtt/container/endpoints"` |
| **动态暂停指定监听端点** | `MqttContainerController` | `POST /sample/mqtt/container/pause` | `curl -X POST "http://localhost:8080/sample/mqtt/container/pause?endpointId=mqttContainerGovernanceScenarioService%23onGovernanceMessage"` |
| **暂停期间投送测试消息** | `MqttContainerController` | `POST /sample/mqtt/container/publish-test` | `curl -X POST "http://localhost:8080/sample/mqtt/container/publish-test?message=HELLO_CONTAINER"` |
| **动态恢复指定监听端点** | `MqttContainerController` | `POST /sample/mqtt/container/resume` | `curl -X POST "http://localhost:8080/sample/mqtt/container/resume?endpointId=mqttContainerGovernanceScenarioService%23onGovernanceMessage"` |
| **事务感知遥测发信（提交 / 回滚防脏读）** | `MqttTransactionController` | `POST /sample/mqtt/transaction/record-telemetry` | `curl -X POST "http://localhost:8080/sample/mqtt/transaction/record-telemetry" -H "Content-Type: application/json" -d '{"deviceId":"DEV-TX-1001","temp":26.8,"humidity":55.2,"shouldRollback":false}'` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-mqtt,ateng-boot-samples/ateng-boot-sample-mqtt -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-mqtt README](../../ateng-boot-mqtt/README.md)。
