# ateng-boot-sample-mqtt 物联网 MQTT 5.0 / 3.1.1 场景集成样例工程

> 面向物联网工业场景的无 Controller 纯业务底座样例（Zero-Controller Invariant），依托 7 大独立业务场景服务（Scenario Services）驱动，实现 REST 风格主题通配符变量提取、四维上下文（MDC TraceId / 租户 / 用户）全链路透传、集群共享订阅、MQTT 5.0 同步与异步 RPC 请求-响应、工业国标/车联网十六进制载荷（HexPayload）双向转译、批量原子下发与保留消息清理、毒丸死信（DLT）窥探与重放治理、监听容器动态启停治理、本地事务对齐发信防脏读、Actuator 健康探针与 Micrometer 黄金度量 100% 离线自洽端到端闭环。

---

## 1. 架构定位与无 Controller 纯净化 (Zero-Controller Invariant)

在传统物联网与微服务研发中，消息中间件样例工程常被随意套用 Web Controller 暴露 HTTP 接口。然而，MQTT 基础设施作为异步事件驱动、长连接高并发的设备接入网关与工业通讯底座，其核心交互模型完全解耦于 Servlet Web 容器：
- **零 Web 依赖侵入 (Zero-Bloat)**：彻底移除 `spring-boot-starter-web` 依赖，避免轻量边缘网关、后台工控处理节点引入冗余的 HTTP 容器与 DispatcherServlet 开销；
- **纯粹场景服务驱动 (Scenario Services)**：将 7 项典型业务特性封装为独立、高内聚的业务场景服务组件（`*ScenarioService`），展示在纯净 Spring 容器下的原生依赖注入与调用范式；
- **端到端测试闭环 (Hermetic Testing)**：采用 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)` 进行全链路集成测试，内置内存 Netty MQTT 5.0/3.1.1 Broker 测试桩，秒级运行，完全离线自洽。

---

## 2. 7 大业务场景服务矩阵 (Scenario Services Matrix)

| 序号 | 业务场景服务 | 覆盖核心机制 / API | 核心方法 / 特性 | 业务行为说明 |
| :--- | :--- | :--- | :--- | :--- |
| **01** | `DeviceTelemetryScenarioService` | `MqttTemplate.publish`<br>`@MqttListener` + `@MqttVariable` + `@MqttHeader` | `publishTelemetry`<br>`publishClusterMetric` | 上报设备遥测数据，动态提取 `{deviceId}` 路径变量；透传并还原 TraceId、TenantId、UserId 上下文；字典注入全部 Header；`$share` 集群共享订阅多节点均衡轮询 |
| **02** | `DeviceControlRpcScenarioService` | `MqttTemplate.sendAndReceive`<br>`MqttTemplate.sendAndReceiveAsync` | `sendControlSync`<br>`sendControlAsync` | 基于 MQTT 5.0 `ResponseTopic` 与 `CorrelationData` 实现微秒级响应路由；支持同步阻塞等待与 CompletableFuture 异步非阻塞响应 |
| **03** | `IndustrialHexScenarioService` | `HexPayload`<br>`HexMqttMessageConverter` | `publishHex` | 电力 DL/T 645、车联网 GB/T 32960 等十六进制定长报文双向无损互转，强类型解析与大写格式化 |
| **04** | `BatchPublishScenarioService` | `MqttTemplate.publishBatch`<br>`MqttTemplate.clearRetained` | `publishBatch`<br>`publishRetainedStatus`<br>`clearRetainedStatus` | 批量原子下发多设备指令；对称发布与清空设备影子保留消息（Retained Message） |
| **05** | `DeadLetterReplayScenarioService` | `MqttDeadLetterReplayTemplate`<br>`FatalExceptionClassifier` | `triggerPoisonPill`<br>`peekDeadLetters`<br>`replayDeadLetters`<br>`purgeDeadLetters` | 毒丸消息（Poison Pill）反序列化或业务不可恢复异常自动转入 DLT 死信主题；支持死信窥探、批量受控重放至目标恢复主题及全量清理 |
| **06** | `MqttContainerGovernanceScenarioService` | `MqttContainerManager` | `pauseEndpoint`<br>`resumeEndpoint`<br>`getEndpointStatuses` | 动态启停指定 `@MqttListener` 消费端点，防止削峰填谷期间下游过载，实时感知各端点监听与运行状态 |
| **07** | `TransactionalTelemetryScenarioService` | `MqttTemplate.sendAfterCommit`<br>`TransactionTemplate` | `recordTelemetryWithCommit`<br>`recordTelemetryWithRollback` | 本地数据库事务与发信严格对齐：事务成功提交后触发投递；事务回滚自动丢弃撤销，绝对防御下游脏读 |

---

## 3. 核心场景服务调用范式与代码示例

### 3.1 设备遥测与通配符路径提取 (`DeviceTelemetryScenarioService`)

```java
@Service
public class DeviceTelemetryScenarioService {

    private final MqttTemplate mqttTemplate;

    // 发布设备遥测数据（自动透传 MDC TraceId 与租户/用户上下文）
    public void publishTelemetry(String deviceId, double temp, double humidity) {
        DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
        mqttTemplate.publish("iot/devices/" + deviceId + "/telemetry", telemetry, 1);
    }

    // 声明式监听：REST 风格路径变量提取 + 四维上下文还原 + Headers 字典全量注入
    @MqttListener(topics = "iot/devices/{deviceId}/telemetry", qos = 1)
    public void onDeviceTelemetry(DeviceTelemetry telemetry,
                                  @MqttTopic String topic,
                                  @MqttVariable("deviceId") String deviceId,
                                  @MqttHeader(value = "x-trace-id", required = false) String traceId,
                                  @MqttHeader Map<String, Object> allHeaders) {
        // 业务处理：自动还原受管 4D 上下文（TraceId、TenantId、UserId），线程安全隔离
    }

    // 集群共享订阅：多节点负载均衡轮询消费
    @MqttListener(topics = "iot/cluster/metrics", sharedGroup = "telemetry-worker", qos = 1)
    public void onClusterMetric(String metricJson) {
        // 自动解析为 $share/telemetry-worker/iot/cluster/metrics
    }
}
```

### 3.2 MQTT 5.0 同步与异步 RPC 控制指令 (`DeviceControlRpcScenarioService`)

```java
@Service
public class DeviceControlRpcScenarioService {

    private final MqttTemplate mqttTemplate;

    // 1. 同步阻塞 RPC：向设备下发指令并阻塞等待回执
    public CommandResponse sendControlSync(String action, int speed, Duration timeout) {
        DeviceCommand command = new DeviceCommand("cmd-sync-" + System.currentTimeMillis(), action, Map.of("speed", speed));
        return mqttTemplate.sendAndReceive(
                "iot/rpc/device/control",
                command,
                CommandResponse.class,
                timeout
        );
    }

    // 2. 异步非阻塞 RPC：返回 CompletableFuture
    public CompletableFuture<CommandResponse> sendControlAsync(String action, int speed, Duration timeout) {
        DeviceCommand command = new DeviceCommand("cmd-async-" + System.currentTimeMillis(), action, Map.of("speed", speed));
        return mqttTemplate.sendAndReceiveAsync(
                "iot/rpc/device/control",
                command,
                CommandResponse.class,
                timeout
        );
    }
}
```

### 3.3 工业国标/十六进制报文转译 (`IndustrialHexScenarioService`)

```java
@Service
public class IndustrialHexScenarioService {

    private final MqttTemplate mqttTemplate;

    // 发布国标十六进制报文（自动规范化转译）
    public void publishHex(String deviceId, String hexString) {
        HexPayload payload = HexPayload.of(hexString);
        mqttTemplate.publish("iot/devices/" + deviceId + "/hex", payload, 1);
    }

    // 工业国标十六进制报文监听器（HexPayload 强类型绑定）
    @MqttListener(topics = "iot/devices/{deviceId}/hex", qos = 1)
    public void onHexMessage(HexPayload payload,
                             @MqttTopic String topic,
                             @MqttVariable("deviceId") String deviceId) {
        // payload.getHexString() 获得规范化大写十六进制字符串
        // payload.getBytes() 获得底层原始字节数组
    }
}
```

### 3.4 批量下发与保留消息清理 (`BatchPublishScenarioService`)

```java
@Service
public class BatchPublishScenarioService {

    private final MqttTemplate mqttTemplate;

    // 批量并发聚合分发指令
    public void publishBatch(List<MqttPublishRecord> records) {
        mqttTemplate.publishBatch(records);
    }

    // 发布设备在线保留状态
    public void publishRetainedStatus(String deviceId, String status) {
        mqttTemplate.publish("iot/devices/" + deviceId + "/status", status, 1, true);
    }

    // 对称清理设备保留状态（发送 0 字节保留消息）
    public void clearRetainedStatus(String deviceId) {
        mqttTemplate.clearRetained("iot/devices/" + deviceId + "/status");
    }
}
```

### 3.5 毒丸死信隔离、窥探、重放与清理 (`DeadLetterReplayScenarioService`)

```java
@Service
public class DeadLetterReplayScenarioService {

    private final MqttTemplate mqttTemplate;
    private final MqttDeadLetterReplayTemplate deadLetterReplayTemplate;

    // 发送毒丸测试消息（触发不可恢复异常转入 DLT）
    public void triggerPoisonPill(String payload) {
        mqttTemplate.publish("iot/devices/poison-test", payload, 1);
    }

    // 只读窥探死信队列
    public List<MqttDeadLetterRecord> peekDeadLetters(String dltTopic, int maxRecords, Duration timeout) {
        return deadLetterReplayTemplate.peek(dltTopic, maxRecords, timeout);
    }

    // 受控重放死信报文至目标恢复主题（内置防二次雪崩熔断）
    public int replayDeadLetters(String dltTopic, String targetTopic) {
        return deadLetterReplayTemplate.replay(dltTopic, targetTopic, 10);
    }

    // 清空死信队列（对称抹除保留死信）
    public void purgeDeadLetters(String dltTopic) {
        deadLetterReplayTemplate.purge(dltTopic);
    }
}
```

### 3.6 监听容器动态启停治理 (`MqttContainerGovernanceScenarioService`)

```java
@Service
public class MqttContainerGovernanceScenarioService {

    private final MqttContainerManager containerManager;

    // 暂停指定端点消费调度
    public boolean pauseEndpoint(String endpointId) {
        containerManager.pause(endpointId);
        return true;
    }

    // 恢复指定端点消费调度
    public boolean resumeEndpoint(String endpointId) {
        containerManager.resume(endpointId);
        return true;
    }

    // 查询全量端点运行状态快照列表
    public List<MqttEndpointStatus> getEndpointStatuses() {
        return containerManager.getEndpointStatuses();
    }
}
```

### 3.7 本地事务发信对齐 (`TransactionalTelemetryScenarioService`)

```java
@Service
public class TransactionalTelemetryScenarioService {

    private final MqttTemplate mqttTemplate;
    private final PlatformTransactionManager transactionManager;

    // 本地事务成功提交后投递（commit 后触发投递，防脏读与反向竞态）
    public void recordTelemetryWithCommit(String deviceId, double temp, double humidity) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(status -> {
            // 本地数据库持久化操作...
            DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
            mqttTemplate.sendAfterCommit("iot/devices/" + deviceId + "/tx", telemetry, 1);
            return null;
        });
    }

    // 本地事务异常回滚，消息自动丢弃
    public void recordTelemetryWithRollback(String deviceId, double temp, double humidity) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        try {
            template.execute(status -> {
                DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
                mqttTemplate.sendAfterCommit("iot/devices/" + deviceId + "/tx", telemetry, 1);
                throw new IllegalStateException("Simulated business error to trigger rollback");
            });
        } catch (IllegalStateException e) {
            // 事务回滚，投递钩子被安全丢弃，Broker 零收到
        }
    }
}
```

---

## 4. 生产可观测性（Actuator 探针与 Micrometer 度量）

### 4.1 Actuator 生产健康感知探针 (`MqttHealthIndicator`)
- 集成 Spring Boot Actuator，通过 `MqttHealthIndicator` 实时监测所有 MQTT 客户端的连接状态；
- 连接正常输出 `UP`，包含 Broker 地址、ClientId、协议版本、自动重连与离线缓冲信息；断开连接时标记 `DOWN`。

### 4.2 Micrometer 黄金度量指标 (`MqttMetrics`)
框架原生注册 Micrometer 指标，支持 Prometheus 抓取：
- `mqtt.client.status`：客户端连接状态 Gauge（1: 已连接, 0: 未连接）；
- `mqtt.message.published`：消息发布累计 Counter；
- `mqtt.publish.latency`：消息发布耗时 Timer；
- `mqtt.message.received`：消息接收累计 Counter；
- `mqtt.consumer.latency`：监听器业务处理耗时 Timer；
- `mqtt.consumer.dlt`：毒丸死信转移累计 Counter；
- `mqtt.requestreply.timeout`：RPC 超时统计 Counter。

---

## 5. 自动化构建与测试指南

### 5.1 纯内存离线自洽测试（默认模式）

无需启动任何物理 MQTT Broker，项目内置 Netty In-Memory Broker 测试桩，秒级运行全量测试用例：

```bash
mvn test -pl ateng-boot-mqtt,ateng-boot-samples/ateng-boot-sample-mqtt -am -T 1C -nsu -B
```

### 5.2 本地真实 Broker 联调（可选模式）

若需连接本地或远程真实 MQTT Broker（如 EMQX / Mosquitto）：
1. 复制模板：`cp ateng-boot-samples/ateng-boot-sample-mqtt/src/main/resources/application-local.example.yml ateng-boot-samples/ateng-boot-sample-mqtt/src/main/resources/application-local.yml`
2. 配置真实 Broker 连接串与凭据（该文件已被 `.gitignore` 忽略，安全无泄露风险）；
3. 激活 `local` profile 启动或测试：
   ```bash
   mvn test -pl ateng-boot-samples/ateng-boot-sample-mqtt -Dspring.profiles.active=local
   ```
