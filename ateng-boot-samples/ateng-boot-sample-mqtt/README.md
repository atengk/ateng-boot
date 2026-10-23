# ateng-boot-sample-mqtt 物联网 MQTT 5.0 / 3.1.1 端到端自验工程

> 专一通配符路径变量提取、四维上下文 TraceId 透传与还原、集群共享订阅负载均衡、MQTT 5.0 同步 RPC 请求-响应、本地事务发信对齐、工业国标/车联网 HexPayload 强类型转译、Actuator 生产探针与 Micrometer 黄金度量 100% 端到端实测沙箱。

---

## 1. 场景定位与功能矩阵

物联网与边缘计算场景在接入 MQTT 时通常面临以下核心痛点：
1. **多主题通配符提取繁琐**：单层 `+` 与多层 `#` 订阅后，业务代码需反复手工 `split("/")` 解析设备 ID，易出错且代码臃肿；
2. **全链路追踪断链**：消息在异步网络队列中流转，日志缺少统一的 TraceId 贯通，生产排查宛如大海捞针；
3. **集群横向扩容重复消费**：多个微服务 Pod 实例订阅同一主题时，缺少原生分组负载均衡机制，导致重复消费；
4. **RPC 交互实现复杂**：向设备下发指令并等待结果回传时，需人工拼装 Correlation ID 并维护高并发超时的状态机；
5. **本地事务与发信竞态**：本地数据库写事务尚未完成，消息已被下游消费者提前读取引发分布式脏读；
6. **工业二进制与国标报文转换繁重**：电力 DL/T 645、车联网 GB/T 32960 等十六进制定长报文需反复手写编解码样板；
7. **Paho 官方底层并发 Bug 与吞吐陷阱**：数组批量订阅并发竞争 NPE、默认 10 飞行窗口引发 `32202` 吞吐雪崩；
8. **心跳阻塞假死断连**：业务耗时操作挤占 Paho 事件循环线程，导致 Ping 报文发送延迟被 Broker 掐断。

`ateng-boot-sample-mqtt` 提供生产级、纯内存自洽的端到端验证方案：
- **纯内存离线自洽（Zero External Broker）**：内置 Netty In-Memory MQTT 5.0/3.1.1 Broker 测试桩，无需外部物理 EMQX / Mosquitto，秒级全绿准出；
- **REST 风格主题通配变量**：形如 `iot/devices/{deviceId}/telemetry` 模式，自动编译并在方法形参 `@MqttVariable("deviceId")` 强类型提取；
- **四维上下文全链路透传**：`X-Trace-Id` 自动写入 MQTT 5.0 User Properties，监听方法在沙箱内自动还原 MDC `traceId`；
- **集群 `$share` 共享订阅**：声明 `sharedGroup = "telemetry-worker"` 自动编译为 `$share` 过滤器，集群多节点无锁均衡轮询；
- **MQTT 5.0 同步 RPC 门面**：`sendAndReceive` 原生基于 `ResponseTopic` 与 `CorrelationData` 实现高并发独立响应路由与超时扫描；
- **本地事务对齐发信**：`sendAfterCommit` 严格在 Spring 本地事务提交后投递，事务回滚自动取消，保障数据绝对一致；
- **工业国标十六进制报文转译**：`HexPayload` 强类型承载十六进制字符串与字节数组双向自适应互转；
- **可观测性闭环**：深度接入 Spring Boot Actuator `/actuator/health` 探针与 Micrometer `/actuator/prometheus` 黄金指标。

---

## 2. 7 项端到端自验场景全景表

| 序号 | 自验场景 | 覆盖核心机制 / API | 验证端点 / 动作 | 预期响应 / 校验点 |
| :--- | :--- | :--- | :--- | :--- |
| **01** | 设备遥测上行与通配消费 | `MqttTemplate.publish`<br>`@MqttListener` + `@MqttVariable` + `@MqttHeader` | `POST /api/mqtt/publish/telemetry` | HTTP 200 成功，监听器提取 `deviceId` 与 `X-Trace-Id`，反查 `GET /api/mqtt/telemetries` 数据匹配 |
| **02** | 工业国标 Hex 报文转译 | `HexPayload`<br>`HexMqttMessageConverter` | `POST /api/mqtt/publish/hex` | 强类型接收并转译 `68AAAAAAAAAAAA6811043333333316`，十六进制与字节长度无损解析 |
| **03** | `$share` 共享订阅均衡消费 | `@MqttListener(sharedGroup = "...")` | `POST /api/mqtt/publish/cluster-metric` | 自动添加 `$share/telemetry-worker/` 前缀订阅，多节点分组轮询消费成功 |
| **04** | MQTT 5.0 同步 RPC 指令响应 | `MqttTemplate.sendAndReceive`<br>`MqttRequestReplyManager` | `POST /api/mqtt/rpc/control` | 阻塞等待边缘端基于 `ResponseTopic` 与 `CorrelationData` 回传回执，返回统一结果状态 |
| **05** | 本地事务对齐发信防脏读 | `MqttTemplate.sendAfterCommit`<br>`@Transactional` | `POST /api/mqtt/publish/transactional` | 事务提交后投递成功，消费者接收到带有事务一致性的订单遥测记录 |
| **06** | Actuator 生产健康感知探针 | `MqttHealthIndicator` + Actuator | `GET /actuator/health` | 实时感知底层 MQTT 客户端连接状态，输出 `UP` 与详细 Broker 地址、ClientId、协议版本 |
| **07** | Micrometer 核心度量采集 | `MqttMetrics` + Prometheus Registry | 模拟发信与消费 | 统计 `mqtt.client.status`、`mqtt.publish.total`、`mqtt.consume.total` 等指标全覆盖 |

---

## 3. 本地启动与实测体验

### 1. 启动服务

```bash
# 启动样例工程（需本地已运行 MQTT Broker，如 EMQX / Mosquitto，或启动嵌入式测试）
mvn -pl ateng-boot-samples/ateng-boot-sample-mqtt spring-boot:run
```

### 2. 发布设备遥测数据 (HTTP POST)

```bash
curl -X POST "http://localhost:8080/api/mqtt/publish/telemetry?deviceId=sensor-001&temp=26.8&humidity=55.4"
```

### 3. 查询监听器聚合接收结果 (HTTP GET)

```bash
curl "http://localhost:8080/api/mqtt/telemetries"
```

### 4. 发起同步 RPC 设备控制指令

```bash
curl -X POST "http://localhost:8080/api/mqtt/rpc/control?commandId=cmd-999&payload=RESTART"
```

### 5. 查看 Actuator 健康探针与度量

```bash
# 查看健康状态
curl "http://localhost:8080/actuator/health"

# 查看 Prometheus 格式度量
curl "http://localhost:8080/actuator/prometheus"
```

---

## 4. 自动化回归测试命令

无需启动外部真实 MQTT Broker，直接执行以下命令即可触发纯内存 100% 离线自洽集成测试：

```bash
mvn test -pl ateng-boot-mqtt,ateng-boot-samples/ateng-boot-sample-mqtt -am -T 1C -nsu -B
```
