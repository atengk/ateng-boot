# ateng-boot-mqtt 基础设施 Starter

> 工业级轻量 IoT 物联网 MQTT 5.0 / 3.1.1 客户端基础设施 Starter，基于 Eclipse Paho MQTT v5 官方原生 Java 客户端深度封装，提供强类型对称发信与声明式消费模型，内置多客户端动态路由、REST 风格通配路径变量提取、标头与四维上下文（MDC TraceId / 租户 / 用户）全链路透传、集群共享订阅、MQTT 5.0 同步 RPC 请求-响应、本地事务发信严格对齐、工业国标/车联网强类型十六进制（HexPayload）双向转译、防互踢/防幽灵会话、Silent ACK 静默确认、DLT 毒丸死信隔离、入站大报文熔断、KeepAlive 线程隔离、双向 mTLS 安全认证、Spring Boot Actuator 生产健康感知与 Micrometer 黄金度量体系。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.mqtt.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **消息发布** | 同步/异步可靠发布 | `MqttTemplate` | `ateng.boot.mqtt.enabled`<br>`ateng.boot.mqtt.clients.<name>.publish-timeout` | `publish(String, Object)` / `publish(String, Object, int)` / `publish(String, Object, int, boolean)` / `publishAsync(...)`，自动适配 POJO / String / byte[] / HexPayload，透明透传 MDC TraceId 标头 | `MqttTemplateTest` / `SampleMqttE2eTest` |
| **批量发布** | 批量消息可靠下发 | `MqttTemplate`<br>`MqttPublishRecord` | `ateng.boot.mqtt.clients.<name>.publish-timeout` | `publishBatch(List<MqttPublishRecord>)` / `publishBatchAsync(...)` / `publishBatch(String, List<MqttPublishRecord>)`，批量原子下发多设备指令，支持混合 QoS 与 Retained 配置 | `MqttTemplateTest` / `SampleMqttE2eTest` |
| **保留消息** | 保留消息对称清理 | `MqttTemplate` | - | `clearRetained(String topic)` / `clearRetainedToClient(String clientName, String topic)`，发送空载荷与 retained=true 报文，彻底抹除 Broker 保留状态 | `MqttTemplateTest` / `SampleMqttE2eTest` |
| **事务发信** | 本地事务提交后发信 | `MqttTemplate` | `ateng.boot.mqtt.clients.<name>.publish-timeout` | `sendAfterCommit(String, Object)` / `sendAfterCommit(String, Object, int, boolean)`，严格绑定 Spring 本地事务提交阶段触发投递，回滚自动撤销，具备 spring-tx 缺失时的反射隔离安全 | `MqttTemplateTest` / `SampleMqttE2eTest` |
| **同步 RPC** | 请求-响应阻塞交互 | `MqttTemplate`<br>`MqttRequestReplyManager` | `ateng.boot.mqtt.clients.<name>.response-topic-prefix` | `sendAndReceive(String, Object, Class<T>)` / `sendAndReceive(String, Object, Class<T>, Duration)`，基于 MQTT 5.0 `ResponseTopic` 与 `CorrelationData` 实现高并发独立响应路由与超时扫描 | `MqttRequestReplyTest` / `SampleMqttE2eTest` |
| **异步 RPC** | 请求-响应非阻塞交互 | `MqttTemplate`<br>`MqttRequestReplyManager` | `ateng.boot.mqtt.clients.<name>.response-topic-prefix` | `sendAndReceiveAsync(String, Object, Class<T>)` / `sendAndReceiveAsync(String, Object, Class<T>, Duration)`，返回 `CompletableFuture<T>`，微秒级异步响应匹配 | `MqttRequestReplyTest` / `SampleMqttE2eTest` |
| **死信治理** | 毒丸死信窥探与重放 | `MqttDeadLetterReplayTemplate`<br>`MqttDeadLetterRecord` | `ateng.boot.mqtt.clients.<name>.max-payload-size` | `peek(String, int, Duration)` 离线拉取窥探、`replay(String, String, int, Duration)` 受控重放至目标主题、`purge(String)` 彻底清空死信队列 | `MqttDeadLetterReplayTemplateTest` / `SampleMqttE2eTest` |
| **容器治理** | 监听端点动态生命周期 | `MqttContainerManager` | - | `pause(String)` / `resume(String)` / `pauseAll()` / `resumeAll()` / `isPaused(String)` / `getEndpointStatuses()` / `getSubscribedTopics(String)`，动态启停监听端点与感知状态 | `MqttContainerManagerTest` / `SampleMqttE2eTest` |
| **动态路由** | 多客户端路由门面 | `MqttTemplate`<br>`NamedMqttClientRegistry` | `ateng.boot.mqtt.default-client`<br>`ateng.boot.mqtt.clients.*` | `use(String clientName)`，返回绑定目标 Broker 连接的独立不可变操作门面；`getClientRegistry()` 反查受管客户端实体 | `NamedMqttClientRegistryTest` / `SampleMqttE2eTest` |
| **声明式监听** | 声明式消息监听器 | `@MqttListener` | `ateng.boot.mqtt.clients.<name>.max-payload-size` | 声明于 Spring Bean 方法上，支持 `topics`、`client`、`qos`、`sharedGroup`、`concurrency`、`autoStartup`，自动纳管生命周期 | `MqttListenerContainerTest` / `SampleMqttE2eTest` |
| **主题通配提取** | REST 风格路径变量提取 | `@MqttVariable`<br>`MqttTopicPattern` | - | 形如 `iot/devices/{deviceId}/telemetry` 声明式通配，自动编译为标准 MQTT `+` 过滤器，并将占位值强类型绑定至 `@MqttVariable("deviceId")` 形参 | `MqttListenerContainerTest` / `SampleMqttE2eTest` |
| **物理主题注入** | 实际推送主题注入 | `@MqttTopic` | - | 方法参数标注 `@MqttTopic String topic`，直接注入 Broker 实际下发的完整物理主题 | `MqttListenerContainerTest` / `SampleMqttE2eTest` |
| **标头透传** | 标头提取与四维还原 | `@MqttHeader`<br>`MessageContextPropagator` | - | 提取 MQTT 5.0 User Properties（大小写不敏感匹配，支持单个键值或注入 `Map<String, Object>` 全量标头），在沙箱内自动还原 MDC `traceId` 与租户/用户上下文，并在执行完毕后彻底清理现场 | `MqttListenerContainerTest` / `SampleMqttE2eTest` |
| **共享订阅** | 集群分组均衡消费 | `@MqttListener(sharedGroup = "...")` | - | 声明 `sharedGroup` 自动编译为 `$share/<group>/<topic>` 共享订阅过滤器，实现分布式集群节点间无锁均衡轮询消费 | `MqttListenerContainerTest` / `SampleMqttE2eTest` |
| **十六进制转译** | 工业国标/车联网 Hex 载荷 | `HexPayload`<br>`HexMqttMessageConverter` | - | 强类型承载十六进制报文字符串与裸字节数组双向无损互转，内置自动补零与大写格式化规范 | `MqttCodecAndObservabilityTest` / `SampleMqttE2eTest` |
| **消息转换器 SPI** | 复合可插拔消息转换器 | `MqttMessageConverter`<br>`CompositeMqttMessageConverter` | - | 编解码 SPI 接口，内置 String、ByteArray、HexPayload 与 Jackson POJO 转换器，支持自定义 `@Bean` 自动接管 | `MqttCodecAndObservabilityTest` / `SampleMqttE2eTest` |
| **链路拦截器 SPI** | 消息出入站拦截审计 | `MqttMessageInterceptor` | - | 出入站全生命周期拦截 SPI（`prePublish` / `postPublish` / `preConsume` / `postConsume`），支持对报文实施安全审计、加解密或自定义标头改写 | `MqttInterceptorTest` |
| **连接事件 SPI** | 连接事件分发与拓扑感知 | `MqttConnectionEventListener`<br>`MqttConnectionEventDispatcher` | `ateng.boot.mqtt.clients.<name>.automatic-reconnect` | 统一监听 `MqttConnectedEvent`、`MqttDisconnectedEvent`、`MqttConnectionLostEvent`，感知物理连接全生命周期震荡与拓扑变化 | `MqttConnectionEventTest` |
| **动态遗嘱 SPI** | 动态遗嘱消息生成器 | `MqttWillProvider` | `ateng.boot.mqtt.clients.<name>.lwt.*` | 客户端连接建立阶段动态提供遗嘱主题、载荷、QoS 与保留标志，支持自定义 `@Bean` 覆写默认配置 | `MqttClientConnectionTest` |
| **发信降级 SPI** | 发布失败降级处理器 | `MqttSendFailureHandler` | - | 发布超时或异常中断时的降级补偿回调接口，支持入库重试或警报，默认记录详细因果异常日志 | `MqttTemplateTest` |
| **幂等静默确认** | 消费重复静默确认 | `SilentAckException` | - | 监听方法遇到幂等重复报文时抛出 `SilentAckException`，框架自动执行静默提交（Silent ACK），平滑终结 QoS 1/2 重投死循环 | `MqttListenerContainerTest` |
| **毒丸死信隔离** | 毒丸死信转移机制 | `FatalExceptionClassifier` | `ateng.boot.mqtt.clients.<name>.max-payload-size` | 反序列化错误等不可恢复的毒丸异常自动隔离并转发至死信主题 `${topic}/DLT`，防止 Poison Pill 阻断队列 | `MqttListenerContainerTest` |
| **大报文熔断** | 入站大报文超限守卫 | `MqttPayloadGuard` | `ateng.boot.mqtt.clients.<name>.max-payload-size` | 毫秒级探测报文字节大小，超过阈值立即 Fail-Fast 熔断，记录度量指标并触发 DLT 隔离，捍卫 JVM 堆内存安全 | `MqttPayloadGuardTest` |
| **双向 mTLS** | TLS 加密与双向证书认证 | `MqttSslSocketFactoryBuilder` | `ateng.boot.mqtt.clients.<name>.ssl.*` | 支持 CA 根证书、客户端双向 PEM 证书与私钥解析，无缝构建标准 `SSLSocketFactory`，严守 TLSv1.2 / TLSv1.3 安全基线 | `MqttSslSocketFactoryBuilderTest` |
| **健康状态探针** | Actuator 生产健康感知 | `MqttHealthIndicator` | `management.health.mqtt.enabled` | 深度集成 Spring Boot Actuator `/actuator/health`，实时上报所有受管客户端连接状态、Broker 地址与协议版本 | `MqttHealthIndicatorTest` / `SampleMqttE2eTest` |
| **黄金度量指标** | Micrometer 核心度量采集 | `MqttMetrics` | `management.metrics.enable.mqtt` | 实时输出客户端状态 Gauge（`mqtt.client.status`）、发信与消费计数器/耗时 Timer、Request-Reply 超时统计与 DLT 死信计数 | `MqttCodecAndObservabilityTest` / `SampleMqttE2eTest` |
| **防互踢策略** | 客户端 ID 动态生成策略 | `ClientIdStrategy` | `ateng.boot.mqtt.clients.<name>.client-id-strategy` | 支持 `STATIC`、`RANDOM_SUFFIX`（下划线随机后缀）与 `IP_PID`（宿主 IP 与进程号），彻底防御微服务多实例部署互踢雪崩 | `MqttClientConnectionTest` |
| **高吞吐抗压** | 生产飞行报文窗口调优 | `MqttClientProperties` | `ateng.boot.mqtt.clients.<name>.max-inflight` | 默认将 Paho 极其保守的 10 飞行窗口调优至 1000，彻底消除高并发吞吐下的 Paho `32202` (Too many publishes in progress) 异常 | `MqttClientConnectionTest` |
| **配置元数据** | 强类型配置属性与元数据 | `AtengMqttProperties`<br>`MqttClientProperties` | `ateng.boot.mqtt.*` | 时间统一声明为 `Duration`，容量统一声明为 `DataSize`，消除无量纲配置歧义，提供生产合理默认值与 IDE 智能补全 | - |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-mqtt` 是面向高并发、工业级物联网生产场景的高内聚 MQTT 基础设施 Starter：
- **纯净依赖与底座优先**：100% 基于 JDK 17 与 Spring 6，核心网络层直接对接官方原生的 `org.eclipse.paho:org.eclipse.paho.mqttv5.client`；Spring Web、Spring TX、Actuator 与 Micrometer 均为可选依赖（`optional`），零引入任何第三方冗余工具包；
- **全链路追踪与上下文还原**：全流程打通 `ateng-boot-core` 的 `MessageContextPropagator`，在发信端自动写入 MQTT 5.0 User Properties（`X-Trace-Id`、`X-Tenant-Id`、`X-User-Id`），在消费端自动还原 MDC 并清理现场；
- **安全防卫与韧性保障**：内置 KeepAlive 线程池隔离，规避阻塞心跳导致假死断连；规避 Paho 数组订阅底层并发竞争 NPE Bug；提供入站大报文熔断与毒丸死信（DLT）保护；
- **双模协议兼容**：默认采用先进的 MQTT 5.0 协议标准（支持 User Properties、Response Topic、Correlation Data、Session Expiry 等），同时向后兼容 MQTT 3.1.1 协议。

### Maven 坐标引入

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-mqtt</artifactId>
</dependency>
```

---

## 2. 生产基础配置与极简三板斧起步

### 2.1 基础配置范式 (application.yml)

```yaml
ateng:
  boot:
    mqtt:
      enabled: true
      default-client: primary
      clients:
        primary:
          server-uri: tcp://127.0.0.1:1883
          client-id: ateng-iot-service
          client-id-strategy: RANDOM_SUFFIX   # 防互踢策略：自动拼接随机短后缀
          protocol-version: "5.0"             # 协议版本：默认 5.0，支持 3.1.1
          clean-start: true                   # MQTT 5.0 清理会话
          connection-timeout: 10s             # 连接建立超时
          publish-timeout: 10s                # 同步发信超时
          keep-alive-interval: 60s            # 心跳保活周期
          max-reconnect-delay: 128s           # 断线指数退避最大重连间隔
          automatic-reconnect: true           # 开启自动重连
          max-inflight: 1000                  # 最大飞行窗口（默认 1000，彻底消除 32202 吞吐雪崩）
          max-buffered-messages: 5000         # 断线期间最大离线缓冲条数
          max-payload-size: 1MB               # 最大报文体积上限，防御 JVM OOM
          response-topic-prefix: "sys/response/" # RPC 响应主题前缀

# 可选集成：Spring Boot Actuator 健康探针与 Micrometer 指标
management:
  health:
    mqtt:
      enabled: true
  endpoint:
    health:
      show-details: always
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
```

### 2.2 极简发信三板斧 (Publish)

直接注入核心门面 `MqttTemplate` 即可实现结构化 POJO、字符串或原始字节的极速投递：

```java
@Service
public class DeviceReportService {

    private final MqttTemplate mqttTemplate;

    public DeviceReportService(MqttTemplate mqttTemplate) {
        this.mqttTemplate = mqttTemplate;
    }

    public void reportTelemetry(String deviceId, double temp, double humidity) {
        DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
        
        // 极简三板斧：同步发布到目标主题，默认 QoS 1，自动附带 MDC TraceId
        mqttTemplate.publish("iot/devices/" + deviceId + "/telemetry", telemetry);
    }
}
```

### 2.3 极简消费三板斧 (Listener)

只需在 Spring Bean 方法上声明 `@MqttListener`，框架全自动完成订阅绑定与 POJO 自适应反序列化：

```java
@Component
public class DeviceTelemetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeviceTelemetryConsumer.class);

    @MqttListener(topics = "iot/devices/{deviceId}/telemetry", qos = 1)
    public void onTelemetry(DeviceTelemetry telemetry,
                            @MqttTopic String topic,
                            @MqttVariable("deviceId") String deviceId,
                            @MqttHeader("x-trace-id") String traceId) {
        log.info("收到设备 [{}] 遥测数据，主题: {}, 追踪号: {}, 内容: {}", deviceId, topic, traceId, telemetry);
    }
}
```

---

## 3. 高阶生产特性与场景指南

### 3.1 REST 风格通配主题与上下文标头注入

框架创新性地引入了 RESTful 风格的通配符语法与四维上下文无缝注入：
- **`@MqttVariable`**：在 `topics` 中使用 `{varName}` 声明占位，底层自动编译为 MQTT 单层通配符 `+`，并在消费端通过反射高效提取；
- **`@MqttTopic`**：直接获取下发的完整实际主题；
- **`@MqttHeader`**：提取 MQTT 5.0 User Properties 标头，不区分大小写匹配，未找到且声明 `required = true` 时触发快速失败。

```java
@MqttListener(topics = "iot/devices/{deviceId}/status", qos = 1)
public void onDeviceStatus(String statusJson,
                           @MqttTopic String topic,
                           @MqttVariable("deviceId") String deviceId,
                           @MqttHeader(value = "X-Tenant-Id", required = false) String tenantId) {
    // 业务处理...
}
```

### 3.2 `$share` 共享订阅集群负载均衡

在高并发遥测或指令上报场景中，单节点消费容易成为瓶颈。只需配置 `sharedGroup` 属性，框架自动将其包装为 MQTT 标准共享订阅过滤器 `$share/<group>/<topic>`：

```java
@MqttListener(topics = "iot/cluster/metrics", sharedGroup = "telemetry-worker", qos = 1)
public void onClusterMetric(String metricJson) {
    // 同一个 sharedGroup 下的多个微服务实例将以无锁均衡轮询方式消费，避免重复消费
    log.info("集群节点消费共享度量报文: {}", metricJson);
}
```

### 3.3 MQTT 5.0 同步 RPC 请求-响应机制 (`sendAndReceive`)

传统 MQTT 为单向解耦发布订阅模型，在下发控制指令时常常需要人工拼装 Correlation ID 并维护复杂的响应映射。
`ateng-boot-mqtt` 原生依托 MQTT 5.0 规范，提供开箱即用的同步阻塞请求-响应门面：

```java
// 1. 发起端（微服务发起设备控制请求）
DeviceCommand command = new DeviceCommand("cmd-restart", "REBOOT", 0);
CommandResponse response = mqttTemplate.sendAndReceive(
        "iot/rpc/device/control",
        command,
        CommandResponse.class,
        Duration.ofSeconds(5) // 5 秒超时阻塞等待
);
log.info("设备执行结果: code={}, message={}", response.getStatus(), response.getMessage());

// 2. 接收端（边缘网关或模拟设备端响应）
@MqttListener(topics = "iot/rpc/device/control", qos = 1)
public void onRpcControl(MqttMessage message) {
    MqttProperties props = message.getProperties();
    String responseTopic = props.getResponseTopic();
    byte[] correlationData = props.getCorrelationData();

    // 组装回执
    CommandResponse res = new CommandResponse("cmd-restart", "SUCCESS", "Reboot scheduled in 3s");
    
    // 原生回传：向 responseTopic 投递响应，并原样附带 correlationData
    MqttMessage responseMsg = new MqttMessage(JsonUtil.toJsonBytes(res));
    MqttProperties replyProps = new MqttProperties();
    replyProps.setCorrelationData(correlationData);
    responseMsg.setProperties(replyProps);
    
    mqttTemplate.use("primary").getClientRegistry().getClient("primary").publish(responseTopic, responseMsg);
}
```

### 3.4 Spring 本地事务与发信严格对齐 (`sendAfterCommit`)

为防御“业务数据尚未持久化成功，MQTT 消息已被投递至下游消费者”导致的分布式竞态脏读问题，`MqttTemplate` 提供了事务对齐发信机制：

```java
@Service
public class MqttTransactionalOrderService {

    private final MqttTemplate mqttTemplate;

    public MqttTransactionalOrderService(MqttTemplate mqttTemplate) {
        this.mqttTemplate = mqttTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public void createOrderAndNotify(String orderId, String deviceId) {
        // 1. 模拟本地持久层数据库写操作（如 orderRepository.save(...)）
        log.info("本地订单持久化成功: orderId={}", orderId);

        // 2. 注册本地事务对齐发信：仅当本地事务 100% 提交成功后才在 TransactionSynchronization.afterCommit 中投递
        // 若当前方法抛出异常触发 rollback，MQTT 消息将被自动作废，绝对严禁泄漏！
        mqttTemplate.sendAfterCommit("iot/orders/" + orderId + "/created", new OrderEvent(orderId, deviceId));
    }
}
```

### 3.5 工业国标/车联网强类型十六进制载荷 (`HexPayload`)

在电力国标（DL/T 645、GB/T 32960）、物联网 Modbus、JT/T 808 等场景中，报文通常以定长十六进制二进制流传输。框架内置 `HexPayload` 与 `HexMqttMessageConverter`，原生支持类型自适应编解码：

```java
// 发送端：直接发布 HexPayload
String meterFrame = "68AAAAAAAAAAAA6811043333333316";
mqttTemplate.publish("iot/devices/meter-88/hex", HexPayload.of(meterFrame));

// 接收端：参数强类型声明为 HexPayload，框架自动转译为十六进制字符串与 byte[]
@MqttListener(topics = "iot/devices/{deviceId}/hex", qos = 1)
public void onHexMessage(HexPayload payload,
                         @MqttTopic String topic,
                         @MqttVariable("deviceId") String deviceId) {
    log.info("收到电表设备 [{}] 十六进制国标报文: hex={}, 字节长度={}",
            deviceId, payload.getHexString(), payload.getBytes().length);
}
```

### 3.6 动态多客户端路由 (`MqttTemplate.use(...)`)

在多园区、多租户或内外网双 MQTT Broker 隔离场景下，通过配置多个客户端逻辑名并使用 `use(...)` 切换：

```yaml
ateng:
  boot:
    mqtt:
      default-client: primary
      clients:
        primary:
          server-uri: tcp://broker-internal.corp:1883
        edge:
          server-uri: tcp://broker-edge.corp:1883
```

```java
// 默认向 primary 客户端投递
mqttTemplate.publish("internal/telemetry", data);

// 动态切换向 edge 客户端投递（无状态线程安全）
mqttTemplate.use("edge").publish("edge/telemetry", data);
```

### 3.7 双向 mTLS 证书安全连接

支持通过标准 PEM 证书文件配置工业级双向 TLS 握手：

```yaml
ateng:
  boot:
    mqtt:
      clients:
        secure-client:
          server-uri: ssl://iot-gateway.corp:8883
          ssl:
            enabled: true
            protocol: TLSv1.3
            ca-cert: "classpath:certs/ca.crt"
            client-cert: "classpath:certs/client.crt"
            client-key: "classpath:certs/client.key"
```

### 3.8 批量消息下发与保留消息对称清理 (`publishBatch` / `clearRetained`)

针对大规模设备群控或告警广播，`MqttTemplate` 提供批量消息投递与保留状态（Retained）彻底抹除门面：

```java
// 1. 批量下发不同主题与 QoS 的控制指令
List<MqttPublishRecord> records = List.of(
        MqttPublishRecord.of("iot/devices/device-01/command", new DeviceCommand("cmd-1", "RESTART", 0), 1),
        MqttPublishRecord.of("iot/devices/device-02/command", new DeviceCommand("cmd-2", "CALIBRATE", 1), 1)
);
mqttTemplate.publishBatch(records);

// 2. 清理 Broker 上的保留消息（设备离线或状态归零时发送空载荷且 retained=true 的报文）
mqttTemplate.clearRetained("iot/devices/device-01/status");
```

### 3.9 MQTT 5.0 异步非阻塞 RPC (`sendAndReceiveAsync`)

对于高并发微服务调用边缘设备场景，使用 `sendAndReceiveAsync` 避免线程阻塞：

```java
CompletableFuture<CommandResponse> future = mqttTemplate.sendAndReceiveAsync(
        "iot/devices/device-01/control",
        new DeviceCommand("cmd-async", "PING", 0),
        CommandResponse.class,
        Duration.ofSeconds(5)
);

future.thenAccept(response -> {
    log.info("异步收到设备回执: {}", response.getMessage());
}).exceptionally(ex -> {
    log.error("指令执行超时或异常: {}", ex.getMessage());
    return null;
});
```

### 3.10 毒丸死信窥探、重放与清理门面 (`MqttDeadLetterReplayTemplate`)

当入站报文触发不可恢复异常（如反序列化失败）并被转入死信主题 `${topic}/DLT` 后，可通过 `MqttDeadLetterReplayTemplate` 进行生产运维治理：

```java
@Autowired
private MqttDeadLetterReplayTemplate dltTemplate;

// 1. 窥探死信队列报文（拉取前 10 条，不破坏队列）
List<MqttDeadLetterRecord> records = dltTemplate.peek("iot/devices/poison-test/DLT", 10, Duration.ofSeconds(2));
for (MqttDeadLetterRecord record : records) {
    log.warn("发现死信: 原始主题={}, 异常类型={}, 发生时间={}",
            record.getOriginalTopic(), record.getExceptionClass(), record.getDltTimestamp());
}

// 2. 批量受控重放至业务恢复主题
int replayedCount = dltTemplate.replay(
        "iot/devices/poison-test/DLT",
        "iot/devices/poison-recovered",
        10,
        Duration.ofSeconds(5)
);
log.info("成功重放 [{}] 条死信至恢复主题", replayedCount);

// 3. 彻底清空 Broker 上的死信主题保留报文
dltTemplate.purge("iot/devices/poison-test/DLT");
```

### 3.11 监听容器动态生命周期治理 (`MqttContainerManager`)

在生产削峰填谷或服务降级维护期间，可动态启停指定 `@MqttListener` 消费端点：

```java
@Autowired
private MqttContainerManager containerManager;

// 暂停指定端点消费（拒绝接收新报文）
containerManager.pause("deviceTelemetryScenarioService#onTelemetry");

// 查询各端点状态
Map<String, Boolean> statuses = containerManager.getEndpointStatuses();

// 恢复消费
containerManager.resume("deviceTelemetryScenarioService#onTelemetry");
```

---

## 4. 生产扩展与 SPI 自定义接管

### 4.1 自定义消息转换器 SPI (`MqttMessageConverter`)

当需要支持 Protobuf、Avro 或自定义二进制协议时，只需实现 `MqttMessageConverter` 并注册为 Spring `@Bean`：

```java
@Configuration(proxyBeanMethods = false)
public class CustomMqttConfig {

    @Bean
    public MqttMessageConverter customProtoConverter() {
        return new MqttMessageConverter() {
            @Override
            public boolean supports(Class<?> targetType) {
                return MyProtoMessage.class.isAssignableFrom(targetType);
            }

            @Override
            public MqttMessage toMessage(Object payload, int defaultQos) {
                MyProtoMessage proto = (MyProtoMessage) payload;
                return new MqttMessage(proto.toByteArray());
            }

            @Override
            public Object fromMessage(MqttMessage message, Class<?> targetType) {
                return MyProtoMessage.parseFrom(message.getPayload());
            }
        };
    }
}
```

### 4.2 自定义动态遗嘱 SPI (`MqttWillProvider`)

用于在设备上线握手阶段，根据宿主 IP、实例 ID 或当前租户动态生成遗嘱消息：

```java
@Component
public class DynamicMqttWillProvider implements MqttWillProvider {

    @Override
    public MqttMessage createWillMessage(String clientName, MqttClientProperties properties) {
        String willJson = "{\"status\":\"OFFLINE\",\"reason\":\"UNEXPECTED_DISCONNECT\"}";
        MqttMessage message = new MqttMessage(willJson.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        message.setRetained(true);
        return message;
    }

    @Override
    public String getWillTopic(String clientName, MqttClientProperties properties) {
        return "sys/clients/" + clientName + "/offline";
    }
}
```

### 4.3 自定义发信失败降级处理器 SPI (`MqttSendFailureHandler`)

当发布发生网络中断、Broker 拒绝或超时异常时，回调该 SPI 执行降级补偿：

```java
@Component
public class DatabaseFallbackMqttSendFailureHandler implements MqttSendFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(DatabaseFallbackMqttSendFailureHandler.class);

    @Override
    public void handle(String clientName, String topic, Object payload, Throwable cause) {
        log.error("[MQTT] 发信失败触发降级补偿: client={}, topic={}, 异常={}", clientName, topic, cause.getMessage());
        // 补偿动作：写入本地待重试消息表 (Outbox Pattern)，由后台调度任务重发
    }
}
```

### 4.4 全链路消息出入站拦截器 SPI (`MqttMessageInterceptor`)

实现 `MqttMessageInterceptor` 并注册为 Spring `@Bean`，即可在消息发布前、发布后、消费前、消费后实施审计、加解密或自定义标头改写：

```java
@Component
public class SecurityAuditMqttInterceptor implements MqttMessageInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditMqttInterceptor.class);

    @Override
    public MqttMessage prePublish(String clientName, String topic, MqttMessage message) {
        log.info("[MQTT-AUDIT] 发信前置拦截: client={}, topic={}", clientName, topic);
        return message;
    }

    @Override
    public void postPublish(String clientName, String topic, MqttMessage message, Throwable failure) {
        if (failure != null) {
            log.error("[MQTT-AUDIT] 发信失败告警: topic={}, 异常={}", topic, failure.getMessage());
        }
    }

    @Override
    public boolean preConsume(String endpointId, String topic, MqttMessage message) {
        // 返回 false 可中断消息分发
        return true;
    }

    @Override
    public void postConsume(String endpointId, String topic, MqttMessage message, Throwable failure) {
        if (failure != null) {
            log.warn("[MQTT-AUDIT] 消费异常: endpoint={}, topic={}", endpointId, topic);
        }
    }
}
```

### 4.5 连接事件监听与网络拓扑感知 SPI (`MqttConnectionEventListener`)

实现 `MqttConnectionEventListener` 并注册为 Spring `@Bean`，即可统一监听连接建立、主动断开与异常掉线事件：

```java
@Component
public class NetworkTopologyMqttEventListener implements MqttConnectionEventListener {

    private static final Logger log = LoggerFactory.getLogger(NetworkTopologyMqttEventListener.class);

    @Override
    public void onEvent(MqttConnectionEvent event) {
        if (event instanceof MqttConnectedEvent connected) {
            log.info("[MQTT-EVENT] 连接成功: client={}, reconnect={}", connected.getClientName(), connected.isReconnect());
        } else if (event instanceof MqttConnectionLostEvent lost) {
            log.warn("[MQTT-EVENT] 连接异常丢失: client={}, 原因={}", lost.getClientName(), lost.getCause().getMessage());
        } else if (event instanceof MqttDisconnectedEvent disconnected) {
            log.info("[MQTT-EVENT] 连接正常断开: client={}", disconnected.getClientName());
        }
    }
}
```

---

## 5. 核心生产边界与非功能机制

### 5.1 KeepAlive 线程隔离守卫 (KeepAlive Thread Isolation)
- **底层痛点**：Eclipse Paho 内部依靠单一单线程事件循环（`CommsReceiver` / `ClientState`）维护 PingReq/PingResp 心跳探测。若在 `@MqttListener` 消费方法中执行耗时数据库查询或第三方 HTTP 调用，一旦占用网络回调线程超过 `keep-alive-interval`，Broker 将判定客户端失活并主动掐断连接，导致“偶发性频繁断线重连”恶性循环。
- **框架防卫**：`MqttListenerEndpointRegistry` 强制将所有消息接收调度隔离至受管的独立异步线程池（`ThreadPoolTaskExecutor`），网络回调线程仅负责快速将报文分发至工作线程队列，毫秒级返还网络句柄，彻底保障心跳时序绝对纯净可靠。

### 5.2 Paho 数组订阅底层 Bug 规避 (Paho Array Subscription Bug Workaround)
- **底层痛点**：在 Paho Java 客户端底层源码中，使用 `client.subscribe(String[] topics, int[] qos)` 批量订阅时，内部针对每个主题的确认包处理存在并发竞争缺陷，极易偶发抛出 `NullPointerException` 或导致订阅状态不一致。
- **框架防卫**：`MqttClientHandle` 强制将批量主题声明转换为单主题串行迭代订阅模式（`client.subscribe(singleTopic, qos)`），从根本上免疫官方客户端底层的数组并发竞争缺陷。

### 5.3 消费端幂等静默确认 (Silent ACK Invariant)
- **底层机制**：在分布式物联网环境下，网络抖动容易引发 QoS 1 报文重复推送。当业务监听器识别出重复报文（例如已处理过的业务流水号）时，若直接抛出异常，会导致 Paho 拒收或重复投递陷入死循环；
- **解决范式**：业务方法抛出 `SilentAckException`（或被其标记的异常），框架在底层自动捕获并执行静默成功提交（Silent ACK），平滑终结重投死循环，优雅保障消费幂等。

### 5.4 毒丸死信转移机制 (DLT Invariant)
- **底层机制**：当 Broker 下发了非法 JSON、畸形二进制格式或反序列化类型无法匹配的报文（Poison Pill）时，该报文永远无法被正常消费；
- **解决范式**：`FatalExceptionClassifier` 识别到不可恢复的致命异常后，自动将毒丸报文连同失败异常信息转移至受管死信主题 `sys/dlt/<client>/<topic>`，并记录 `mqtt.consume.fatal` 告警指标，坚决杜绝单条毒丸报文阻塞消费队列。

### 5.5 大报文内存熔断防线 (`maxPayloadSize`)
- **防御机制**：边缘设备或非法攻击者上报超大报文（如数百 MB 伪造文件）极易瞬间打爆应用 JVM 堆内存导致 OOM。
- **框架防卫**：入站报文经由 `MqttPayloadGuard` 执行毫秒级预检，凡超过 `max-payload-size`（默认 1MB）阈值的报文立即 Fail-Fast 阻断，记录超限告警并直接触发 DLT 死信隔离。

### 5.6 生产高并发飞行窗口调优 (`maxInflight`)
- **吞吐优化**：Paho 官方默认的 `maxInflight` 仅为极其保守的 10。在万级 TPS 遥测发布或突发流量下，第 11 条报文将直接触发 `32202 - Too many publishes in progress` 严重系统崩溃。
- **框架防卫**：`ateng-boot-mqtt` 在配置中将默认 `maxInflight` 提升至生产级的 `1000`，彻底消除高并发吞吐下的飞行报文雪崩瓶颈。

---

## 6. 生产可观测性与健康度量

### 6.1 Spring Boot Actuator 健康探针 (`/actuator/health`)

引入 `spring-boot-starter-actuator` 后，框架自动注册 `MqttHealthIndicator`，实时监测所有客户端健康状态：

```json
{
  "status": "UP",
  "components": {
    "mqtt": {
      "status": "UP",
      "details": {
        "clients": {
          "primary": {
            "status": "CONNECTED",
            "connected": true,
            "clientId": "ateng-sample-primary_4cb4f0",
            "serverUri": "tcp://127.0.0.1:1883",
            "protocolVersion": "5.0",
            "automaticReconnect": true,
            "bufferedMessages": 0
          }
        }
      }
    }
  }
}
```

### 6.2 Micrometer 黄金度量指标 (`/actuator/prometheus`)

引入 `micrometer-registry-prometheus` 后，框架通过 `MqttMetrics` 实时采集黄金生产度量：

| 指标名称 | 类型 | 维度标签 (Tags) | 业务语义说明 |
| :--- | :--- | :--- | :--- |
| `mqtt.client.status` | Gauge | `client` | 客户端实时连接状态（1: 已连接, 0: 未连接/断开） |
| `mqtt.message.published` | Counter | `client`, `topic`, `qos` | 消息发布累计次数 |
| `mqtt.publish.latency` | Timer | `client`, `topic`, `qos` | 消息发布耗时分布统计 |
| `mqtt.message.received` | Counter | `client`, `topic` | 入站接收消息累计次数 |
| `mqtt.consumer.latency` | Timer | `endpoint`, `topic`, `status` | 监听端点消息消费业务耗时分布统计 |
| `mqtt.consumer.dlt` | Counter | `endpoint`, `topic` | 业务异常转入毒丸死信队列 (DLT) 累计次数 |
| `mqtt.requestreply.timeout` | Counter | `client`, `topic` | 请求-响应 (Request-Reply) 同步等待超时累计次数 |

---

## 7. 真实场景样例工程 (ateng-boot-sample-mqtt)

完整、自洽、开箱即用的端到端演示代码收录于 `ateng-boot-samples/ateng-boot-sample-mqtt`（纯业务场景服务驱动，Zero-Controller Invariant）：
- **设备遥测与通配服务**：[DeviceTelemetryScenarioService.java](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/service/DeviceTelemetryScenarioService.java)
- **指令下发与 RPC 服务**：[DeviceControlRpcScenarioService.java](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/service/DeviceControlRpcScenarioService.java)
- **工业十六进制报文服务**：[IndustrialHexScenarioService.java](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/service/IndustrialHexScenarioService.java)
- **批量下发与保留消息服务**：[BatchPublishScenarioService.java](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/service/BatchPublishScenarioService.java)
- **毒丸死信治理与重放服务**：[DeadLetterReplayScenarioService.java](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/service/DeadLetterReplayScenarioService.java)
- **监听容器动态治理服务**：[MqttContainerGovernanceScenarioService.java](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/service/MqttContainerGovernanceScenarioService.java)
- **本地事务协同发信服务**：[TransactionalTelemetryScenarioService.java](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/service/TransactionalTelemetryScenarioService.java)
- **100% 离线自洽集成测试**：[SampleMqttE2eTest.java](../ateng-boot-samples/ateng-boot-sample-mqtt/src/test/java/io/github/atengk/boot/sample/mqtt/SampleMqttE2eTest.java)
