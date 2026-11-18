# ateng-boot-mqtt 基础设施 Starter

> 工业级轻量 IoT 物联网 MQTT 5.0 / 3.1.1 客户端基础设施 Starter，基于 Eclipse Paho MQTT v5 官方原生 Java 客户端深度封装。全链路遵循 `ReentrantLock` 虚拟线程友好规范、统一开启 Paho `manualAcks = true` 收口确认并支持 `AckMode.MANUAL` 手动幂等确认与受管非阻塞指数退避重试（`concurrency = 1` 严格保序、`@Valid` 毒丸 0 次重试直入 DLT）、声明式 `@MqttListener` / `@MqttReply` 自动应答、`$share` 集群共享订阅与 MQTT 5.0 订阅控制选项、REST 风格通配路径变量提取、标头与四维上下文（MDC TraceId / 租户 / 用户）全链路透传、同步/异步 RPC 请求-响应、有界 `MqttDeadLetterStore` 死信持久化与防雪崩重放、引用计数驱动物理 `pause` / `resume` 容器治理、本地事务 `sendAfterCommit` 对齐、工业国标 `HexPayload` 双向转译、运行期多客户端热插拔与 `MqttTemplate.use()` 定向视图、动态认证凭据 SPI、双向 mTLS 安全认证、Actuator 健康探针与低基数 Micrometer 黄金度量体系。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.mqtt.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (`*Test` / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **基础发信** | 同步可靠消息发布 | `MqttTemplate` | `enabled`<br>`clients.<name>.publish-timeout` | `publish(String, Object)` 及 `qos` / `retained` / `userProperties` / `Duration messageExpiry` 全重载，自动适配 POJO / `String` / `byte[]` / `HexPayload`，透明透传四维上下文 | `MqttTemplateTest`<br>`MqttTelemetryController` |
| **定向发信** | 客户端绑定视图与定向发布 | `MqttTemplate` | `default-client`<br>`clients.<name>.publish-timeout` | `use(String clientName)` 返回不可变客户端绑定视图（优先级高于线程上下文）；另提供 `publishToClient(...)` 等显式指定客户端重载 | `MqttTemplateTest`<br>`NamedMqttClientRegistryTest`<br>`MqttClientGovernanceController` |
| **异步发信** | 异步非阻塞消息发布 | `MqttTemplate` | `clients.<name>.publish-timeout`<br>`clients.<name>.offline-buffer-policy` | `publishAsync(...)` / `publishAsyncToClient(...)`，返回 `CompletableFuture<Void>`，微秒级释放调用线程，支持 `messageExpiry` 与 `userProperties` | `MqttTemplateTest`<br>`SampleMqttE2eTest` |
| **保留消息** | 保留消息发布与对称清空 | `MqttTemplate` | `clients.<name>.publish-timeout` | `publishRetained(...)` / `publishRetainedToClient(...)` / `clearRetained(String)` / `clearRetained(String, int)` / `clearRetainedAsync(...)` / `clearRetainedAsyncToClient(...)`，下发空字节 + `retained=true` 彻底抹除 Broker 保留状态 | `MqttTemplateTest`<br>`MqttBatchAndRetainedController` |
| **批量发布** | 批量并发消息原子下发 | `MqttTemplate`<br>`MqttPublishRecord` | `clients.<name>.publish-timeout`<br>`clients.<name>.max-inflight` | `publishBatch(List<MqttPublishRecord>)` / `publishBatchAsync(...)` / `publishBatchToClient(...)` / `publishBatchAsyncToClient(...)`，并发聚合下发多设备指令并支持独立 `qos` / `retained` / `messageExpiry` | `MqttTemplateTest`<br>`MqttBatchAndRetainedController` |
| **事务发信** | 本地事务提交后发信 | `MqttTemplate` | `clients.<name>.publish-timeout` | `sendAfterCommit(...)` / `sendAfterCommitToClient(...)` 全参数重载，绑定 Spring 本地事务 `afterCommit` 阶段触发投递，事务回滚自动取消防脏读，无活跃事务平滑降级立即发送 | `MqttTemplateTest`<br>`MqttTransactionController` |
| **同步 RPC** | 请求-响应阻塞交互 | `MqttTemplate`<br>`MqttRequestReplyManager` | `clients.<name>.response-topic-prefix` | `sendAndReceive(...)`（支持 `MqttMessage` 或强类型 `Class<T>` 反序列化），基于 MQTT 5.0 `ResponseTopic` 与 `CorrelationData` 实现精确应答路由与超时淘汰 | `MqttRequestReplyTest`<br>`MqttRpcController` |
| **异步 RPC** | 请求-响应非阻塞流水线 | `MqttTemplate`<br>`MqttRequestReplyManager` | `clients.<name>.response-topic-prefix` | `sendAndReceiveAsync(...)`，返回 `CompletableFuture<T>` / `CompletableFuture<MqttMessage>`，支持无锁并发匹配应答与防堆积超时熔断 | `MqttRequestReplyTest`<br>`MqttRpcController` |
| **声明式应答** | `@MqttReply` 自动回发 | `@MqttReply`<br>`MqttListenerMethodAdapter` | `clients.<name>.response-topic-prefix` | 标注于 `@MqttListener` 方法上（属性 `topic`、`qos`、`retained`），自动将同步返回值或 `CompletableFuture<T>` 携带原 `CorrelationData` 回发至入站 `ResponseTopic` 或指定主题 | `MqttRequestReplyTest`<br>`MqttRpcController` |
| **消费确认** | `AUTO_AFTER_CONSUME` 与手动幂等确认 | `AckMode`<br>`MqttAcknowledgment` | `consumer.ack-mode` | 底层全量开启 `manualAcks=true`；`@MqttListener(ackMode = AckMode.MANUAL)` 注入 `MqttAcknowledgment`，提供 `ack()`、`nackToDlt()`、`abandonWithoutAck()` 与 `isAcknowledged()` 严格单次幂等状态机 | `MqttListenerContainerTest`<br>`MqttContainerController` |
| **退避重试** | 受管非阻塞指数退避重试与保序 | `@MqttListener(maxRetries = ...)` | `consumer.max-retries`<br>`consumer.initial-backoff`<br>`consumer.backoff-multiplier`<br>`consumer.max-backoff` | 瞬态异常通过受管 `ThreadPoolTaskScheduler` 非阻塞退避重试；`concurrency=1` 启用单队列严格保序；消费端 `@Valid` 校验与致命异常 0 次重试直入 DLT | `MqttListenerContainerTest`<br>`MqttDeadLetterGovernanceTest`<br>`MqttContainerController` |
| **死信治理** | 毒丸死信存储与防雪崩重放 | `MqttDeadLetterReplayTemplate`<br>`MqttDeadLetterRecord`<br>`MqttDeadLetterStore` | `max-replay-count`<br>`dlt.retained`<br>`dlt.peek-broker-fallback`<br>`dlt.max-records-per-topic`<br>`dlt.max-total-records` | `peek` / `replay` / `replayToClient` / `replayRecord` / `purge`；默认 `dlt.retained=false` 落入有界 `InMemoryMqttDeadLetterStore` 防单条覆写，注入 `X-Replay-Count`、`X-Dead-Letter-Replayed` 与 `X-Replay-Timestamp` 防二次雪崩 | `MqttDeadLetterGovernanceTest`<br>`MqttDeadLetterController` |
| **死信 SPI** | 可插拔有界死信持久化扩展 | `MqttDeadLetterStore`<br>`InMemoryMqttDeadLetterStore` | `dlt.*` | 提供 `save` / `peek` / `remove` / `purge`（均含 `clientName` 作用域重载），默认内存双维度 FIFO 有界淘汰，可自定义 `@Bean` 接入 Redis / DB | `MqttDeadLetterGovernanceTest` |
| **容器治理** | 引用计数驱动物理 `pause` / `resume` | `MqttContainerManager`<br>`MqttEndpointStatus` | - | `pause` / `resume` / `pauseAll` / `resumeAll` / `isPaused` / `getSubscribedTopics` / `getAllSubscribedTopics` / `getEndpointStatuses` / `getEndpointStatus` / `containsEndpoint`，按 `(clientName, mqttFilter)` 引用计数向 Broker 真实退订与重建订阅 | `MqttGovernanceAndShutdownTest`<br>`MqttContainerController` |
| **动态热插拔** | 运行期动态注册/替换/注销客户端 | `NamedMqttClientRegistry`<br>`MqttClientHandle` | `default-client`<br>`clients.*` | `registerClient(name, props)`、`registerOrReplaceClient(name, props)`、`unregisterClient(name, force)`、`containsClient`、`getClientHandles`，注销同步校验活跃绑定并清理度量 Gauge | `NamedMqttClientRegistryTest`<br>`MqttClientGovernanceController` |
| **线程上下文** | 线程级客户端动态绑定 | `MqttContextHolder` | `default-client` | `setClientName(String)` / `getClientName()` / `clear()` / `runWith(String, Supplier<T>)`，在当前线程作用域隐式切换目标客户端（优先级低于 `use()`） | `NamedMqttClientRegistryTest` |
| **编程式订阅** | 编程式主题回调订阅 | `MqttTemplate` | `clients.<name>.connection-timeout` | `subscribe(topicFilter, qos, callback)` / `subscribeToClient(...)` / `unsubscribe(...)` / `unsubscribeFromClient(...)`，支持 `Consumer<MqttMessage>` 与 `BiConsumer<String, byte[]>` | `MqttTemplateTest`<br>`ClientGovernanceScenarioService` |
| **声明式监听** | 全功能声明式监听端点 | `@MqttListener` | `clients.<name>.max-payload-size` | 支持 `topics`、`qos`、`client`、`sharedGroup`、`concurrency`、`enableDlt`、`dltTopic`、`ackMode`、`maxRetries`、`autoStartup` 及 MQTT 5.0 订阅选项 `noLocal`、`retainAsPublished`、`retainHandling` | `MqttListenerContainerTest`<br>`SampleMqttE2eTest` |
| **通配/标头** | 路径变量、物理主题与标头提取 | `@MqttVariable`<br>`@MqttTopic`<br>`@MqttHeader`<br>`MqttTopicPattern` | - | `{var}` 自动编译为标准 `+` 通配并提取绑定；`@MqttTopic` 注入物理主题；`@MqttHeader` 支持单键或 `Map<String, Object>` 全量标头并自动还原四维上下文且在 `finally` 清理 | `MqttListenerContainerTest`<br>`MqttTelemetryController` |
| **共享订阅** | 集群分组均衡消费 | `@MqttListener(sharedGroup = "...")` | - | 自动拼装 `$share/<group>/<topic>` 共享订阅过滤器，多实例节点间轮询负载均衡消费，彻底避免重复处理与单机瓶颈 | `MqttListenerContainerTest`<br>`MqttTelemetryController` |
| **十六进制** | 工业国标 / 车联网 Hex 载荷 | `HexPayload`<br>`HexMqttMessageConverter` | - | 强类型承载十六进制字符串与字节数组无损互转，内置偶数位校验与大写规范化，直接作为监听形参与发布载荷使用 | `MqttCodecAndObservabilityTest`<br>`MqttIndustrialHexController` |
| **转换器 SPI** | 复合可插拔消息转换器 | `MqttMessageConverter`<br>`CompositeMqttMessageConverter` | - | 责任链适配 `String`、`byte[]`、`HexPayload` 与 Jackson JSON，自定义 `@Bean` 实现 `supports`/`fromMessage`/`toPayload` 自动优先注册 | `MqttCodecAndObservabilityTest` |
| **拦截器 SPI** | 出入站全生命周期拦截切面 | `MqttMessageInterceptor` | - | 提供 `prePublish`、`postPublish`、`preConsume`、`postConsume`，支持出站动态追加签名头、发信审计与入站消费过滤拦截 | `MqttLifecycleAndInterceptorTest` |
| **凭据 SPI** | 动态认证凭据与令牌刷新 | `MqttCredentialsProvider`<br>`MqttCredentials` | `clients.<name>.username`<br>`clients.<name>.password` | 初次建连及断线重连时动态解析用户名、密码/STS/JWT 及 MQTT 5.0 增强认证字段 `authMethod`/`authData` | `AtengMqttAutoConfigurationTest` |
| **事件 SPI** | 连接拓扑震荡感知与领域事件 | `MqttConnectionEventListener`<br>`MqttConnectedEvent`<br>`MqttDisconnectedEvent`<br>`MqttConnectionLostEvent` | `clients.<name>.automatic-reconnect` | 同时通过 Spring EventBus 与 `MqttConnectionEventListener` SPI 回调 `onConnected` / `onDisconnected` / `onConnectionLost` | `MqttLifecycleAndInterceptorTest` |
| **遗嘱与兜底** | 动态遗嘱与发信失败补偿 SPI | `MqttWillProvider` / `MqttWill`<br>`MqttSendFailureHandler` | `clients.<name>.lwt.enabled`<br>`clients.<name>.lwt.will-delay-interval` | `MqttWillProvider.getWill(...)` 动态生成含主机/Pod 信息的遗嘱，支持 MQTT 5.0 延迟遗嘱；`MqttSendFailureHandler.onFailure(...)` 兜底物理发信与事务提交后失败异常 | `MqttTemplateTest`<br>`MqttLifecycleAndInterceptorTest` |
| **异常体系** | 静默确认与 RPC 超时因果异常 | `SilentAckException`<br>`MqttTimeoutException`<br>`FatalExceptionClassifier` | - | 业务/幂等切面抛出 `SilentAckException` 或 `IdempotentException` 静默 PUBACK；RPC 超时抛出 `MqttTimeoutException`；致命/校验异常 0 次重试转 DLT | `MqttRequestReplyTest`<br>`MqttListenerContainerTest` |
| **连接与高可用** | 多节点 HA、WebSocket 与缓冲策略 | `OfflineBufferPolicy`<br>`ClientIdStrategy`<br>`PersistenceType` | `clients.<name>.server-uris`<br>`clients.<name>.web-socket-headers`<br>`clients.<name>.offline-buffer-policy`<br>`clients.<name>.client-id-strategy`<br>`clients.<name>.max-inflight` | `server-uris` 高可用切换、`web-socket-headers` 握手定制、`BUFFER_WHEN_RECONNECTING` / `FAIL_FAST` 断线策略、`RANDOM_SUFFIX` / `IP_PID` 防互踢、`max-inflight=1000` 消除 32202 异常 | `ClientIdStrategyTest`<br>`MqttGovernanceAndShutdownTest` |
| **安全与守卫** | 大报文熔断与双向 mTLS 加密 | `PayloadGuard`<br>`MqttSslSocketFactoryBuilder` | `clients.<name>.max-payload-size`<br>`clients.<name>.ssl.client-key-password`<br>`clients.<name>.ssl.hostname-verification-enabled` | `PayloadGuard` 毫秒级拦截超大报文防 OOM；`MqttSslSocketFactoryBuilder` 解析 PEM CA/客户端证书/加密私钥与 JKS/PKCS12，默认开启 `hostname-verification-enabled=true` | `MqttCodecAndObservabilityTest`<br>`AtengMqttAutoConfigurationTest` |
| **可观测性** | Actuator 探针与低基数黄金度量 | `MqttHealthIndicator`<br>`MqttMetrics` | `management.health.mqtt.enabled`<br>`management.metrics.enable.mqtt`<br>`metrics.include-topic-tag`<br>`metrics.max-topic-cardinality` | `/actuator/health` 聚合展示全部客户端连接细节；Micrometer 采集连接状态/发信/耗时/DLT/超时指标，默认剥离高基数物理主题 Tag（开启时受 `200` 上限与 `__overflow__` 保护） | `MqttCodecAndObservabilityTest`<br>`SampleMqttE2eTest` |

---

## 1. 模块定位与快速起步

### 1.1 核心价值宣言

`ateng-boot-mqtt` 是面向高并发、工业级物联网与车联网生产场景的高内聚 MQTT 基础设施 Starter：
- **纯净依赖与虚拟线程就绪**：100% 基于 JDK 17 与 Spring 6 原生能力构建，网络内核直连官方 `org.eclipse.paho:org.eclipse.paho.mqttv5.client`；全链路临界区采用 `ReentrantLock` 消除 JDK 21+ 虚拟线程 Carrier Pinning，Spring Web、Spring TX、Actuator 与 Micrometer 均为可选依赖；
- **强确定性确认与有界容错**：Paho 底层全量开启 `manualAcks = true` 统一收口确认时机，支持 `AUTO_AFTER_CONSUME` 与 `MANUAL`（`MqttAcknowledgment`）双模，结合受管 `ThreadPoolTaskScheduler` 非阻塞指数退避重试与有界 `MqttDeadLetterStore` 持久留存，告别过早确认丢消息、毒丸覆写与线程阻塞；
- **全链路四维上下文透传**：自动接管 `ateng-boot-core` 的 `MessageContextPropagator`，在 MQTT 5.0 模式下无侵入读写 User Properties（`X-Trace-Id`、`X-Tenant-Id`、`X-User-Id`），在消费完成后 `finally` 清理现场，同时向下兼容 MQTT 3.1.1 存量硬件设备。

### 1.2 Maven 依赖引入

#### 方式一：通过 `ateng-boot-bom` 统一纳管版本（推荐）

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.atengk</groupId>
            <artifactId>ateng-boot-bom</artifactId>
            <version>1.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-mqtt</artifactId>
    </dependency>
</dependencies>
```

#### 方式二：显式指定版本直接引入

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-mqtt</artifactId>
    <version>1.2.0</version>
</dependency>
```

### 1.3 极简可用配置 (`application.yml`)

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
          client-id-strategy: RANDOM_SUFFIX   # 防互踢策略：自动拼接 6 位随机后缀
          protocol-version: "5.0"             # 默认 5.0，兼容 3.1.1
          automatic-reconnect: true           # 开启断线自动重连与订阅自愈
          publish-timeout: 10s                # 同步发信超时阈值
          max-inflight: 1000                  # 生产级飞行窗口（消除 Paho 32202 异常）
```

### 1.4 极简收发实战

#### 发信端：注入 `MqttTemplate` 发布强类型载荷

```java
@Service
@RequiredArgsConstructor
public class DeviceReportService {

    private final MqttTemplate mqttTemplate;

    public void reportTelemetry(String deviceId, double temp, double humidity) {
        DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
        // 同步发布至目标主题（默认 QoS 0），自动附带 MDC TraceId 与租户/用户上下文标头
        mqttTemplate.publish("iot/devices/" + deviceId + "/telemetry", telemetry);
    }
}
```

#### 消费端：声明 `@MqttListener` 自动反序列化并还原上下文

```java
@Component
@Slf4j
public class DeviceTelemetryConsumer {

    @MqttListener(topics = "iot/devices/{deviceId}/telemetry", qos = 1)
    public void onTelemetry(DeviceTelemetry telemetry,
                            @MqttTopic String topic,
                            @MqttVariable("deviceId") String deviceId,
                            @MqttHeader(value = "x-trace-id", required = false) String traceId) {
        log.info("收到设备 [{}] 遥测数据，物理主题: {}, 追踪号: {}, 载荷: {}",
                deviceId, topic, traceId, telemetry);
    }
}
```

---

## 2. 核心架构与设计不变量

```mermaid
flowchart TD
    subgraph Publisher["业务应用发信端 (MqttTemplate / use())"]
        P1["业务调用 publish / publishBatch / sendAfterCommit / sendAndReceive"]
        P2["PayloadGuard 载荷尺寸预检 (防 JVM OOM)"]
        P3["CompositeMqttMessageConverter (JSON / Hex / String / byte[])"]
        P4["MessageContextPropagator (写入 4D TraceId / 租户 / 用户标头)"]
        P5["MqttMessageInterceptor (prePublish / postPublish 审计)"]
        P6["Paho MqttAsyncClient (飞行窗口 1000 / OfflineBufferPolicy)"]
        P1 --> P2 --> P3 --> P4 --> P5 --> P6
    end

    subgraph Broker["MQTT 5.0 / 3.1.1 Broker (EMQX / Mosquitto / HiveMQ)"]
        B1["主题分发路由 (REST 编译 + / $share 负载均衡 / noLocal / 引用计数订阅)"]
    end

    subgraph Consumer["声明式监听消费端 (@MqttListener & manualAcks=true)"]
        C1["Paho CommsReceiver 单线程网络循环 (单主题串行订阅防并发 NPE)"]
        C2["MqttListenerEndpointRegistry 受管线程池 (ReentrantLock + KeepAlive 隔离)"]
        C3["PayloadGuard 入站超限熔断"]
        C4["MessageContextPropagator 还原 4D 上下文 (finally 清理)"]
        C5["业务执行 (@Valid 校验 / @MqttReply / MqttAcknowledgment)"]
        C6["瞬态异常 -> ThreadPoolTaskScheduler 非阻塞指数退避重试 (concurrency=1 保序)"]
        C7["致命或 @Valid 失败 -> 0 次重试转 MqttDeadLetterStore + PUBACK / SilentAck 静默确认"]
        C1 --> C2 --> C3 --> C4 --> C5
        C5 -.->|瞬态异常| C6
        C6 -.->|重试成功| C1
        C5 -.->|致命/@Valid/nackToDlt| C7
        C6 -.->|耗尽重试| C7
    end

    P6 -->|TCP / TLS / WS| B1
    B1 -->|推送报文| C1
```

- **虚拟线程无 Pinning 与 KeepAlive 隔离不变量**：全模块临界区（注册表变更、订阅锁、指标基数注册、`InMemoryMqttDeadLetterStore` 等）100% 使用 `ReentrantLock` 替代 `synchronized`；Paho 网络回调收到报文后立即投递至 Core 受管工作线程池处理，瞬态重试交由受管 `ThreadPoolTaskScheduler` 定时派发，绝不阻塞心跳 Loop 与回调线程。
- **`manualAcks = true` 确定性收口与分类分流不变量**：所有客户端统一开启 Paho 手动确认。`AUTO_AFTER_CONSUME` 仅在消费成功、静态/幂等静默（`SilentAckException` / `IdempotentException`）或毒丸成功转存 DLT 后才下发 `PUBACK`/`PUBCOMP`；`MANUAL` 模式下由单次幂等 `MqttAcknowledgment` 精准决定确认、转死信或放弃确认。
- **Paho 数组订阅缺陷规避与四维上下文明文不变量**：规避官方 `subscribe(String[], int[])` 内部确认竞争 NPE 缺陷，统一串行逐一订阅并按 `(clientName, mqttFilter)` 维护引用计数；业务实体内存保持纯明文，仅在跨网络帧传输时自动编解码 MQTT 5.0 User Properties 标头并在消费 `finally` 彻底清理线程上下文。

---

## 3. 设备遥测、声明式消费与确认重试可靠性套件

### 3.1 设备遥测可靠上报与四维上下文透传

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      clients:
        primary:
          publish-timeout: 10s       # 同步发信确认超时阈值
          keep-alive-interval: 60s   # 心跳保活周期
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@RestController
@RequestMapping("/sample/mqtt/telemetry")
@RequiredArgsConstructor
public class MqttTelemetryController {

    private static final Duration DEFAULT_TELEMETRY_AWAIT_TIMEOUT = Duration.ofSeconds(3);

    private final DeviceTelemetryScenarioService scenarioService;

    @PostMapping("/publish")
    public Result<TelemetryInspectionRecord> publishTelemetry(@Valid @RequestBody TelemetryPublishRequest request) {
        TelemetryInspectionRecord record = scenarioService.publishAndAwait(request, DEFAULT_TELEMETRY_AWAIT_TIMEOUT);
        return Result.success(record);
    }
}
```

#### 核心机制与防御说明
- 发信端自动感知当前线程的 MDC `traceId`（缺失时自动生成 UUID 补齐）、`TenantContextHolder` 与 `UserContextHolder`，编码为 MQTT 5.0 User Properties 随报文透传；
- 支持叠加自定义 `Map<String, String> headers` 与强类型 `Duration messageExpiry`（消息过期窗口），受 `publish-timeout`（默认 `10s`）严格保护，超时快速失败不悬挂工作线程。

---

### 3.2 声明式监听、REST 路径变量提取与全标头还原

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      clients:
        primary:
          max-payload-size: 1MB      # 入站最大报文尺寸守卫，超限拦截并转入 DLT
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@Service
public class DeviceTelemetryScenarioService {

    @MqttListener(topics = "iot/devices/{deviceId}/telemetry", qos = 1)
    public void onDeviceTelemetry(DeviceTelemetry telemetry,
                                  @MqttTopic String topic,
                                  @MqttVariable("deviceId") String deviceId,
                                  @MqttHeader(value = "x-trace-id", required = false) String traceId,
                                  @MqttHeader Map<String, Object> allHeaders) {
        // 自动从入站 User Properties 还原的受管 4D 上下文
        String tenantId = TenantContextHolder.getTenantId();
        String userId = UserContextHolder.getUserId();

        TelemetryInspectionRecord record = new TelemetryInspectionRecord(
                deviceId, topic, telemetry, traceId, tenantId, userId, allHeaders, System.currentTimeMillis()
        );
        inspectionHolder.record(record);
    }
}
```

#### 核心机制与防御说明
- **REST 风格通配编译**：`iot/devices/{deviceId}/telemetry` 在注册订阅时自动编译为 MQTT 单层通配过滤器 `iot/devices/+/telemetry`，收到物理消息时精确抽出 `{deviceId}` 并按方法形参类型绑定；
- **全量标头注入与防泄漏**：`@MqttHeader Map<String, Object>` 自动接收所有 MQTT 5.0 User Properties；四维上下文在消费方法返回或异常抛出后的 `finally` 块中强制清理还原，杜绝线程池复用串租户。

---

### 3.3 `$share` 集群共享订阅与 MQTT 5.0 订阅控制选项

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      clients:
        primary:
          protocol-version: "5.0"
          automatic-reconnect: true  # 断线重连后自动恢复全部活跃共享订阅与选项
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@Service
public class MqttContainerGovernanceScenarioService {

    @MqttListener(topics = "iot/governance/shared-flow", sharedGroup = "gov-cluster", qos = 1)
    public void onSharedFlowPrimary(String payload) {
        sharedFlowMessages.add("primary:" + payload);
    }

    @MqttListener(topics = "iot/governance/nolocal-channel", qos = 1, noLocal = true, retainHandling = 2)
    public void onNoLocalMessage(String payload) {
        noLocalMessages.add(payload);
    }
}
```

#### 核心机制与防御说明
- **共享订阅轮询扩展**：声明 `sharedGroup = "gov-cluster"` 自动组装为 `$share/gov-cluster/iot/governance/shared-flow`，保障同消费组多 Pod 实例负载均衡消费；
- **MQTT 5.0 原生订阅控制**：`noLocal = true` 阻止接收当前客户端自身发布到同主题的消息（防止回环死循环）；`retainHandling = 2` 表示订阅建立时不重推 Broker 既有保留消息；`retainAsPublished = true` 保留服务端原始 Retain 标识（仅 MQTT 5.0 下由 Paho `MqttSubscription` 原生下发）。

---

### 3.4 手动幂等确认 (`AckMode.MANUAL`) 与受管非阻塞指数退避重试

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      consumer:
        ack-mode: AUTO_AFTER_CONSUME # 全局默认确认模式，可在 @MqttListener 局部覆盖为 MANUAL
        max-retries: 2               # 瞬态异常最大重试次数（总执行次数 = 1 + maxRetries）
        initial-backoff: 200ms       # 首次退避延迟
        backoff-multiplier: 2.0      # 指数退避乘数
        max-backoff: 5s              # 单次最大退避封顶时长
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@Service
public class MqttContainerGovernanceScenarioService {

    @MqttListener(
            topics = "iot/governance/manual-ack",
            qos = 1,
            ackMode = AckMode.MANUAL,
            dltTopic = "iot/governance/manual-ack/DLT"
    )
    public void onManualAckMessage(String payload, MqttAcknowledgment ack) {
        if (payload != null && payload.contains("REJECT_DLT")) {
            // 显式拒绝并转移死信后立即确认，阻断 Broker 无限重推
            ack.nackToDlt();
            return;
        }
        manualAckedMessages.add(payload);
        // 显式正向提交 PUBACK
        ack.ack();
    }

    @MqttListener(
            topics = "iot/governance/retry-channel",
            qos = 1,
            concurrency = 1,
            maxRetries = 2,
            dltTopic = "iot/governance/retry-channel/DLT"
    )
    public void onRetryableMessage(String payload) {
        int attempt = retryAttemptsByPayload
                .computeIfAbsent(normalizeRetryKey(payload), k -> new AtomicInteger(0))
                .incrementAndGet();
        if (payload != null && payload.startsWith("TRANSIENT:") && attempt == 1) {
            throw new RuntimeException("Simulated transient error on first attempt");
        }
        retriedMessages.add(payload);
    }
}
```

#### 核心机制与防御说明
- **单次幂等状态机 (`MqttAcknowledgment`)**：在 `MANUAL` 模式下注入句柄，`ack()`（正向 PUBACK）、`nackToDlt()`（转入 DLT 后 PUBACK）与 `abandonWithoutAck()`（放弃本轮确认等待重连重投）互斥且保证仅首次状态跃迁生效，重复调用为空操作；注意 `MANUAL` 模式默认交由业务掌控流程，不会隐式介入自动重试；
- **非阻塞退避与单分区保序**：`AUTO_AFTER_CONSUME` 捕获到非致命瞬态异常且未耗尽 `maxRetries` 时，通过受管 `ThreadPoolTaskScheduler` 按 `initialBackoff * backoffMultiplier^(attempt-1)` 延迟调度重试，不占用执行线程；当端点声明 `concurrency = 1` 时强制启用单队列串行重试锁控，确保后至消息不会抢在重试消息之前消费破坏设备状态时序。

---

## 4. 双向 RPC 指令下发与 `@MqttReply` 声明式应答套件

### 4.1 同步阻塞与异步非阻塞 RPC 指令下发

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      clients:
        primary:
          response-topic-prefix: "sys/response/" # 聚合响应主题前缀，实际订阅 prefix + actualClientId
          publish-timeout: 10s
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@RestController
@RequestMapping("/sample/mqtt/rpc")
@RequiredArgsConstructor
public class MqttRpcController {

    private final DeviceControlRpcScenarioService rpcScenarioService;

    @PostMapping("/control-sync")
    public Result<CommandResponse> sendControlSync(@Valid @RequestBody DeviceCommandRequest request) {
        Duration timeout = resolveTimeout(request);
        return executeSyncRpc("[HTTP-RPC] 同步 RPC 执行失败",
                () -> rpcScenarioService.sendControlSync(request.getAction(), request.getSpeed(), timeout));
    }

    @PostMapping("/control-async")
    public CompletableFuture<Result<CommandResponse>> sendControlAsync(@Valid @RequestBody DeviceCommandRequest request) {
        Duration timeout = resolveTimeout(request);
        return rpcScenarioService.sendControlAsync(request.getAction(), request.getSpeed(), timeout)
                .thenApply(Result::success)
                .exceptionally(ex -> handleRpcException("[HTTP-RPC] 异步 RPC 执行失败", ex));
    }
}
```

#### 核心机制与防御说明
- 发信时自动向 MQTT 5.0 报文属性写入唯一的 `ResponseTopic` 与 `CorrelationData`；框架启动及断线重连后会自动订阅与重建该聚合响应前缀主题；
- 超时未响应时内置扫描机制自动清理过期 `CompletableFuture` 并抛出强类型 `MqttTimeoutException`，避免长尾请求积压引发内存泄漏。

---

### 4.2 `@MqttReply` 声明式自动应答（同步 POJO 与异步 `CompletableFuture`）

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      clients:
        primary:
          protocol-version: "5.0"              # 依赖 MQTT 5.0 ResponseTopic 与 CorrelationData
          response-topic-prefix: "sys/response/"
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@Service
public class DeviceControlRpcScenarioService {

    @MqttListener(topics = "iot/rpc/device/declarative", qos = 1)
    @MqttReply(qos = 1)
    public CommandResponse onDeclarativeSyncRpc(DeviceCommand command) {
        return new CommandResponse(
                command != null ? command.getCommandId() : "cmd-decl-sync",
                true,
                "DECLARATIVE_SYNC_OK",
                System.currentTimeMillis()
        );
    }

    @MqttListener(topics = "iot/rpc/device/declarative-async", qos = 1)
    @MqttReply(qos = 1)
    public CompletableFuture<CommandResponse> onDeclarativeAsyncRpc(DeviceCommand command) {
        CommandResponse response = new CommandResponse(
                command != null ? command.getCommandId() : "cmd-decl-async",
                true,
                "DECLARATIVE_ASYNC_OK",
                System.currentTimeMillis()
        );
        return CompletableFuture.completedFuture(response);
    }
}
```

#### 核心机制与防御说明
- 监听方法仅需标注 `@MqttReply`（可选覆盖 `topic`、`qos`、`retained`），框架自动从入站报文提取 `ResponseTopic` 与 `CorrelationData`，使用同一客户端链路与编解码器将返回值回发给请求方；
- 当方法返回 `CompletableFuture<T>` 时，容器在 Future 完成回调中异步回发响应并在期间保持确认安全有序；若方法返回 `null` 则静默跳过回发。

---

## 5. 工业物联网二进制与自定义协议编解码套件

### 5.1 工业国标与车联网十六进制报文 (`HexPayload`) 双向编解码

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      clients:
        primary:
          max-payload-size: 2MB      # 工业二进制大包超限防护
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@RestController
@RequestMapping("/sample/mqtt/hex")
@RequiredArgsConstructor
public class MqttIndustrialHexController {

    private final IndustrialHexScenarioService hexScenarioService;

    @PostMapping("/publish")
    public Result<HexPublishResponse> publishHex(@Valid @RequestBody HexPublishRequest request) {
        HexPublishResponse response = hexScenarioService.publishHexAndResolve(request.getDeviceId(), request.getHexData());
        return Result.success(response);
    }
}
```

```java
@Service
public class IndustrialHexScenarioService {

    @MqttListener(topics = "iot/devices/{deviceId}/hex", qos = 1)
    public void onHexMessage(HexPayload payload,
                             @MqttTopic String topic,
                             @MqttVariable("deviceId") String deviceId) {
        log.info("[SCENARIO-HEX] device={}, topic={}, hex={}, len={}",
                deviceId, topic, payload.getHexString(), payload.getBytes().length);
    }
}
```

#### 核心机制与防御说明
- `HexPayload` 针对电力 DL/T 645、车联网 GB/T 32960 与两客一危 JT/T 808 等工业二进制报文设计，内置偶数长度校验、前导补零及大写十六进制文本标准化；
- 发布端传入 `HexPayload` 自动转译为纯二进制字节发送，监听端直接以 `HexPayload` 形参接收即可获得强类型帧视图，告别繁琐位操作。

---

### 5.2 自定义消息转换器 SPI 插拔接管 (`MqttMessageConverter`)

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      enabled: true
```

#### 生产接管 Java 实操
```java
@Configuration(proxyBeanMethods = false)
public class CustomProtocolMqttConfig {

    @Bean
    public MqttMessageConverter customProtoConverter() {
        return new MqttMessageConverter() {
            @Override
            public boolean supports(Class<?> targetType) {
                return MyProtoMessage.class.isAssignableFrom(targetType);
            }

            @Override
            public Object fromMessage(MqttMessage message, Class<?> targetType) {
                return MyProtoMessage.parseFrom(message.getPayload());
            }

            @Override
            public byte[] toPayload(Object payload) {
                return ((MyProtoMessage) payload).toByteArray();
            }
        };
    }
}
```

#### 核心机制与防御说明
- 框架内置 `CompositeMqttMessageConverter` 责任链，依次调度自定义 `MqttMessageConverter`、原生 `byte[]`、`String`、`HexPayload` 与兜底 Jackson `JsonMqttMessageConverter`；
- Spring 容器中注册的任何自定义 `MqttMessageConverter` Bean 会优先进入转换链，无缝接入 Protobuf、Avro 或私有加密字节规约。

---

## 6. 批量并发下发、保留消息协同与本地事务对齐套件

### 6.1 批量消息并发原子下发

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      clients:
        primary:
          max-inflight: 1000         # 生产级在途飞行窗口（默认 1000，消除 32202 报错）
          publish-timeout: 10s
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@RestController
@RequestMapping("/sample/mqtt/batch")
@RequiredArgsConstructor
public class MqttBatchAndRetainedController {

    private final BatchPublishScenarioService batchScenarioService;

    @PostMapping("/publish")
    public Result<String> publishBatch(@Valid @RequestBody BatchPublishRequest request) {
        List<MqttPublishRecord> publishRecords = new ArrayList<>(request.getRecords().size());
        for (BatchPublishItemRequest item : request.getRecords()) {
            publishRecords.add(MqttPublishRecord.builder()
                    .topic(item.getTopic())
                    .payload(item.getPayload())
                    .qos(item.getQos() != null ? item.getQos() : 1)
                    .retained(item.isRetained())
                    .build());
        }
        batchScenarioService.publishBatch(publishRecords);
        return Result.success("成功原子批量投递 " + publishRecords.size() + " 条报文");
    }
}
```

#### 核心机制与防御说明
- `MqttPublishRecord` 强制前置校验主题非空、载荷非空、`qos ∈ [0, 2]` 与非负 `messageExpiry`；支持为批内每条指令独立指定主题、QoS、Retained 与 MQTT 5.0 扩展属性；
- `publishBatch` / `publishBatchAsync` 并发发起推送并通过 `CompletableFuture.allOf` 聚合等待结果，突破大规模群发时的 RTT 累加瓶颈。

---

### 6.2 设备影子保留消息发布与对称清空

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      clients:
        primary:
          clean-start: true          # 会话清理与设备影子 Retained 状态协同
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@Validated
@RestController
@RequestMapping("/sample/mqtt/batch")
@RequiredArgsConstructor
public class MqttBatchAndRetainedController {

    private final BatchPublishScenarioService batchScenarioService;

    @PostMapping("/retained-status")
    public Result<String> publishRetainedStatus(@Valid @RequestBody RetainedStatusRequest request) {
        batchScenarioService.publishRetainedStatus(request.getDeviceId(), request.getStatus());
        return Result.success("成功发布设备保留消息状态: " + request.getStatus());
    }

    @DeleteMapping("/clear-retained")
    public Result<String> clearRetainedStatus(
            @NotBlank(message = "设备标识 deviceId 不能为空")
            @RequestParam("deviceId") String deviceId) {
        batchScenarioService.clearRetainedStatus(deviceId);
        return Result.success("成功对称清空设备保留状态消息");
    }
}
```

#### 核心机制与防御说明
- `publishRetained` 将设备最新影子状态驻留 Broker 供新上线订阅者秒级获取；
- `clearRetained` / `clearRetainedAsync` 严格遵照 MQTT 协议规范向目标主题发布 `byte[0]` 空载荷 + `retained = true` 清除帧，彻底抹除下线设备历史保留态，满足增删对称闭环。

---

### 6.3 本地数据库事务提交后发信 (`sendAfterCommit`)

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      clients:
        primary:
          publish-timeout: 10s
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@Service
public class TransactionalTelemetryScenarioService {

    public void recordTelemetryWithCommit(String deviceId, double temp, double humidity) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(status -> {
            // 1. 本地数据库写操作
            dbRecordsCount.incrementAndGet();

            // 2. 挂载本地事务提交后发信钩子（支持回滚安全取消，无事务则立即投递）
            DeviceTelemetry telemetry = new DeviceTelemetry(deviceId, temp, humidity, System.currentTimeMillis());
            mqttTemplate.sendAfterCommit("iot/devices/" + deviceId + "/tx", telemetry, 1);
            return null;
        });
    }
}
```

#### 核心机制与防御说明
- 内部检查 `TransactionSynchronizationManager.isSynchronizationActive()`：处于真实数据库事务内时注册 `TransactionSynchronization.afterCommit` 钩子，确保先落库提交再外发 MQTT，事务回滚则完全取消发送杜绝下游脏读；
- 事务外调用时平滑降级为立即同步安全发布；若事务成功提交但物理发信遇网络异常，将精确路由至 `MqttSendFailureHandler` SPI 执行补偿落库。

---

## 7. 毒丸隔离、`MqttDeadLetterStore` 持久化与死信重放治理套件

### 7.1 毒丸判定、`@Valid` 校验 0 次重试与 `MqttDeadLetterStore` 有界多条留存

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      dlt:
        retained: false              # 默认 false，彻底杜绝 Broker Retained 单条覆盖多条历史死信
        peek-broker-fallback: true   # Store 为空时兼容回退探查历史 Broker Retained 死信
        max-records-per-topic: 1000  # 单 DLT 主题最大留存条数上限（超出 FIFO 淘汰）
        max-total-records: 10000     # 全局跨主题最大留存总条数上限（防内存 OOM）
      clients:
        primary:
          max-payload-size: 1MB
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@Service
public class DeadLetterReplayScenarioService {

    @MqttListener(topics = "iot/devices/poison-test", qos = 1)
    public void onPoisonPillMessage(String payload) {
        if (payload != null && payload.startsWith("POISON_")) {
            // FatalExceptionClassifier 识别不可恢复致命异常，跳过重试直入 iot/devices/poison-test/DLT
            throw new IllegalArgumentException("FATAL_POISON_PILL");
        }
        normalMessages.add(payload);
    }

    @MqttListener(
            topics = "iot/devices/valid-order",
            qos = 1,
            maxRetries = 3,
            dltTopic = "iot/devices/valid-order/DLT"
    )
    public void onValidWorkOrder(@Valid ValidatedWorkOrder workOrder) {
        // 非法工单触发 JSR-303 校验异常时：即使声明 maxRetries=3 也严格执行 0 次重试直入 DLT
        normalMessages.add(workOrder.getOrderNo());
    }
}
```

#### 核心机制与防御说明
- **多毒丸防覆写与作用域隔离**：早期 Broker `retained = true` 方式会导致连续第二条毒丸覆盖销毁第一条。现行默认通过 `MqttDeadLetterStore`（默认自动装配 `InMemoryMqttDeadLetterStore`）按 `(clientName, dltTopic)` 作用域有序保存全部死信，同时向 Broker 发出普通 DLT 消息供实时监控订阅；
- **校验失败零重试直通 DLT**：反序列化崩溃、大报文超限（`PayloadGuard`）、`FatalExceptionClassifier` 致命异常以及监听形参 `@Valid` 触发的校验异常（`ConstraintViolationException` / `MethodValidationException`）一律跳过指数退避重试，毫秒级隔离转入死信并提交 `PUBACK`。

---

### 7.2 死信只读窥探、防雪崩重放 (`X-Replay-Count`) 与对称清空

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      max-replay-count: 1            # 死信重放最大允许重试次数上限（最小 1，防二次死循环雪崩）
      dlt:
        retained: false
        peek-broker-fallback: true
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@RestController
@RequestMapping("/sample/mqtt/dead-letter")
@RequiredArgsConstructor
public class MqttDeadLetterController {

    private final DeadLetterReplayScenarioService dltScenarioService;

    @GetMapping("/peek")
    public Result<List<MqttDeadLetterRecord>> peekDeadLetters(
            @RequestParam(value = "topic", defaultValue = "iot/devices/poison-test/DLT") String dltTopic,
            @RequestParam(value = "count", defaultValue = "10") int count,
            @RequestParam(value = "timeoutSeconds", defaultValue = "3") int timeoutSeconds) {
        List<MqttDeadLetterRecord> records = dltScenarioService.peekDeadLetters(dltTopic, count, Duration.ofSeconds(timeoutSeconds));
        return Result.success(records);
    }

    @PostMapping("/replay")
    public Result<String> replayDeadLetters(@Valid @RequestBody DeadLetterReplayRequest request) {
        Duration timeout = Duration.ofSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 5);
        int replayed = dltScenarioService.replayDeadLetters(
                request.getSourceTopic(), request.getTargetTopic(), request.getCount(), timeout);
        return Result.success("成功受控重放 " + replayed + " 条死信报文至目标主题 [" + request.getTargetTopic() + "]");
    }

    @DeleteMapping("/purge")
    public Result<String> purgeDeadLetters(
            @RequestParam(value = "topic", defaultValue = "iot/devices/poison-test/DLT") String dltTopic) {
        dltScenarioService.purgeDeadLetters(dltTopic);
        return Result.success("已成功彻底清空死信主题 [" + dltTopic + "] 的积压报文");
    }
}
```

#### 核心机制与防御说明
- **安全只读探查 (`peek`)**：优先零损耗读取 `MqttDeadLetterStore` 中的 FIFO 死信记录；若 Store 无数据且 `peek-broker-fallback = true`，自动通过临时短订阅读取 Broker 残留死信；
- **防二次雪崩受控重放 (`replay` / `replayToClient` / `replayRecord`)**：重放时清洗 `x-original-topic` / `x-exception-class` / `x-exception-message` / `x-dlt-timestamp` 异常标头，注入 `X-Dead-Letter-Replayed: true`、`X-Replay-Count` 与 `X-Replay-Timestamp`；当 `replayCount >= maxReplayCount` 时硬熔断丢弃防往复雪崩；每条重放成功后从 `MqttDeadLetterStore` 精确 `remove(clientName, dltTopic, recordId)`，且单批受 `DEFAULT_LIMIT=100` / `MAX_LIMIT=1000` 保护；
- **对称彻底清空 (`purge`)**：同步清理 `MqttDeadLetterStore` 内积压并向 Broker 发送空字节清除帧清理遗留 Retained 死信。

---

## 8. 监听容器运维、多命名客户端热插拔与高可用网络治理套件

### 8.1 监听端点运行期引用计数物理 `pause` / `resume` 治理

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      clients:
        primary:
          automatic-reconnect: true
```

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@Validated
@RestController
@RequestMapping("/sample/mqtt/container")
@RequiredArgsConstructor
public class MqttContainerController {

    private final MqttContainerGovernanceScenarioService governanceScenarioService;

    @GetMapping("/endpoints")
    public Result<List<MqttEndpointStatus>> getEndpointStatuses() {
        return Result.success(governanceScenarioService.getEndpointStatuses());
    }

    @PostMapping("/pause")
    public Result<String> pauseEndpoint(@NotBlank @RequestParam("endpointId") String endpointId) {
        governanceScenarioService.pauseEndpoint(endpointId);
        return Result.success("监听端点 [" + endpointId + "] 已成功暂停");
    }

    @PostMapping("/resume")
    public Result<String> resumeEndpoint(@NotBlank @RequestParam("endpointId") String endpointId) {
        governanceScenarioService.resumeEndpoint(endpointId);
        return Result.success("监听端点 [" + endpointId + "] 已成功恢复");
    }
}
```

#### 核心机制与防御说明
- **引用计数驱动物理退订/重建**：`MqttContainerManager` 以 `(clientName, mqttFilter)` 为维度维护活跃订阅引用计数。当端点调用 `pause(endpointId)` 后，若同一物理过滤器下已无其他活跃端点（计数归零），框架向 Broker 真实下发 `unsubscribe` 实现真正流量削峰不堆积；当同连接还有其他共享或独立端点监听该过滤器时则精准保持物理订阅不断流；
- 调用 `resume(endpointId)` 时递增引用计数并幂等重建 Broker 物理订阅与 MQTT 5.0 订阅选项，另提供 `pauseAll()`、`resumeAll()`、`isPaused(id)`、`getSubscribedTopics(clientName)` 与 `getAllSubscribedTopics()`。

---

### 8.2 多命名客户端动态热插拔与 `MqttTemplate.use()` 定向绑定视图

#### 专属 YAML 配置
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

#### 真实实战 Java 代码 (逆向提取自 Sample)
```java
@Service
public class ClientGovernanceScenarioService {

    public MqttClientSummaryVO registerClient(RegisterClientRequest request) {
        // ... 构造 MqttClientProperties targetProps ...
        MqttClientHandle handle = clientRegistry.registerClient(clientName, targetProps);

        String filter = "iot/clients/" + clientName + "/+";
        try {
            // 使用不可变客户端绑定视图订阅动态通道；异常时确定性注销回滚防句柄泄漏
            mqttTemplate.use(clientName).subscribe(filter, 1, (topic, payloadBytes) -> {
                String payload = payloadBytes != null ? new String(payloadBytes, StandardCharsets.UTF_8) : "";
                consumedByClient.computeIfAbsent(clientName, k -> new CopyOnWriteArrayList<>()).add(payload);
            });
            dynamicSubscribedFilterByClient.put(clientName, filter);
        } catch (RuntimeException ex) {
            clientRegistry.unregisterClient(clientName, true);
            throw ex;
        }
        return toSummaryVO(clientName, handle);
    }

    public void publishToClient(String clientName, String topic, String payload, int qos, boolean retained) {
        mqttTemplate.use(clientName).publish(topic, payload, qos, retained);
    }

    public void unregisterClient(String clientName, boolean force) {
        String subscribedFilter = dynamicSubscribedFilterByClient.remove(clientName);
        if (!force && StringUtils.hasText(subscribedFilter) && clientRegistry.containsClient(clientName)) {
            mqttTemplate.use(clientName).unsubscribe(subscribedFilter);
        }
        clientRegistry.unregisterClient(clientName, force);
    }
}
```

#### 核心机制与防御说明
- **`MqttTemplate.use(clientName)`**：返回一个无状态、不可变、线程安全的定向客户端门面视图（与原模板共享编解码链、拦截器与并发锁），覆盖 `publish` / `publishAsync` / `sendAfterCommit` / `publishBatch` / `clearRetained` / `subscribe` / `unsubscribe` / `sendAndReceive*` 等全部能力；在需要同时向多个 Broker 发信时优先于线程级 `MqttContextHolder.runWith(...)`；
- **`NamedMqttClientRegistry` 动态生命周期**：支持 `registerClient`（重名拦截）、`registerOrReplaceClient`（原子替换旧句柄并清理连接）、`unregisterClient(clientName, force)`（默认受保护禁止注销默认主客户端与存在活跃 `@MqttListener` 绑定的客户端，并联动 `MqttMetrics.unbindClient` 释放 Gauge 防指标泄漏）。

---

### 8.3 线程上下文、`serverUris` 高可用故障转移、WebSocket 标头与断线缓冲策略

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    mqtt:
      clients:
        ha-gateway:
          server-uris:               # 集群高可用 Broker 地址池（断线自动在多节点间轮换重连）
            - tcp://broker-zone-a.corp:1883
            - tcp://broker-zone-b.corp:1883
          web-socket-headers:        # 使用 ws:// / wss:// 时附加到 WebSocket 握手的自定义 HTTP 标头
            X-Gateway-Token: "${MQTT_WS_TOKEN}"
          offline-buffer-policy: FAIL_FAST # 断线重连窗期内快速失败（默认 BUFFER_WHEN_RECONNECTING）
```

#### 真实实战 Java 代码
```java
// 1. 线程上下文作用域隐式切换默认客户端（离开 runWith 作用域自动恢复原上下文）
MqttContextHolder.runWith("edge", () -> {
    mqttTemplate.publish("edge/telemetry/context", payload);
    return null;
});
```

#### 核心机制与防御说明
- **`OfflineBufferPolicy`**：
  - `BUFFER_WHEN_RECONNECTING`（默认）：当 `automaticReconnect = true` 且 `maxBufferedMessages > 0` 时，物理掉线重连窗期内的普通/异步发信写入有界离线缓冲，重连瞬间自动有序补发；
  - `FAIL_FAST`：物理连接未处于 Connected 状态时立刻抛出异常快速拒收，严守工业实时指令“宁拒发不延迟错发”原则；
- **`server-uris` 与 `web-socket-headers`**：支持静态配置集群节点容灾列表（单配 `server-uri` 自动作为回退首节点）以及云原生网关 WebSocket 鉴权/灰度标头注入。

---

## 9. 生产扩展接管（SPI）、非功能防御底线与样例工程导览

### 9.1 双向 mTLS 安全握手与延迟遗嘱

```yaml
ateng:
  boot:
    mqtt:
      clients:
        secure-gateway:
          server-uri: ssl://iot-gateway.corp:8883
          lwt:
            enabled: true
            topic: "sys/gateway/status"
            payload: '{"status":"OFFLINE","event":"UNEXPECTED_TERMINATION"}'
            qos: 1
            retain: false
            will-delay-interval: 30s # MQTT 5.0 延迟遗嘱窗口：短时网络闪断 30s 内重连则抑制遗嘱误报
          ssl:
            enabled: true
            protocol: TLSv1.3
            ca-cert: "classpath:certs/ca.crt"
            client-cert: "classpath:certs/client.crt"
            client-key: "classpath:certs/client.key"
            client-key-password: "${MQTT_CLIENT_KEY_PASSWORD}" # 加密 PEM 私钥口令
            hostname-verification-enabled: true                # 生产默认开启 TLS 主机名严格核验
```

- `MqttSslSocketFactoryBuilder` 同时支持 PEM（含受密保保护的 PKCS#8 / PKCS#1 私钥）与 JKS / PKCS12 KeyStore/TrustStore，默认启用 HTTPS 标准主机名验证 (`hostname-verification-enabled = true`) 严防证书错配中间人攻击。

---

### 9.2 链路出入站审计拦截器 SPI (`MqttMessageInterceptor`)

```java
@Component
public class SecurityAuditMqttInterceptor implements MqttMessageInterceptor {

    @Override
    public boolean prePublish(String clientName, String topic, byte[] payload,
                              int qos, boolean retained, Map<String, String> userProperties) {
        // 发信前置拦截：可追加自定义签名 Header；返回 false 可中止发布
        userProperties.put("X-Gateway-Signed", "true");
        return true;
    }

    @Override
    public void postPublish(String clientName, String topic, byte[] payload,
                            int qos, boolean retained, Map<String, String> userProperties, Throwable ex) {
        if (ex != null) {
            // 发信失败审计告警
        }
    }

    @Override
    public boolean preConsume(String clientName, String endpointId, String topic,
                              MqttMessage message, Map<String, String> headers) {
        // 消费前置拦截：返回 false 可静默拦截丢弃当前报文
        return true;
    }

    @Override
    public void postConsume(String clientName, String endpointId, String topic,
                            MqttMessage message, Map<String, String> headers, Throwable ex) {
        // 消费后置耗时与异常统计
    }
}
```

---

### 9.3 连接生命周期事件与拓扑感知 SPI (`MqttConnectionEventListener`)

```java
@Component
@Slf4j
public class NetworkTopologyMqttEventListener implements MqttConnectionEventListener {

    @Override
    public void onConnected(MqttConnectedEvent event) {
        log.info("[MQTT-EVENT] 连接建立: client={}, clientId={}, reconnect={}",
                event.getClientName(), event.getClientId(), event.isReconnect());
    }

    @Override
    public void onDisconnected(MqttDisconnectedEvent event) {
        log.info("[MQTT-EVENT] 连接优雅断开: client={}, serverUri={}",
                event.getClientName(), event.getServerUri());
    }

    @Override
    public void onConnectionLost(MqttConnectionLostEvent event) {
        log.warn("[MQTT-EVENT] 连接意外掉线: client={}, cause={}",
                event.getClientName(), event.getCause() != null ? event.getCause().getMessage() : "unknown");
    }
}
```

- 除实现 `MqttConnectionEventListener` 接口外，亦可直接通过 Spring `@EventListener` 监听 `MqttConnectedEvent`、`MqttDisconnectedEvent` 与 `MqttConnectionLostEvent`。

---

### 9.4 动态遗嘱消息生成器 SPI (`MqttWillProvider`)

```java
@Component
public class DynamicMqttWillProvider implements MqttWillProvider {

    @Override
    public MqttWill getWill(String clientName, String actualClientId, MqttClientProperties clientProps) {
        String willJson = "{\"client\":\"" + clientName + "\",\"clientId\":\"" + actualClientId + "\",\"status\":\"OFFLINE\"}";
        return MqttWill.builder()
                .topic("sys/clients/" + clientName + "/offline")
                .payload(willJson)
                .qos(1)
                .retained(true)
                .build();
    }
}
```

---

### 9.5 发信异常终极兜底补偿 SPI (`MqttSendFailureHandler`)

```java
@Component
@Slf4j
public class OutboxFallbackMqttSendFailureHandler implements MqttSendFailureHandler {

    @Override
    public void onFailure(String clientName, String topic, byte[] payload,
                          int qos, boolean retained, Throwable cause) {
        log.error("[MQTT-FALLBACK] 发信失败触发本地 Outbox 兜底: client={}, topic={}, qos={}, size={}, error={}",
                clientName, topic, qos, payload != null ? payload.length : 0, cause != null ? cause.getMessage() : "unknown");
        // 兜底动作：写入本地数据库 Outbox 待补偿表，交由受管调度器后续重试
    }
}
```

---

### 9.6 有界死信持久化存储 SPI (`MqttDeadLetterStore`)

当默认 `InMemoryMqttDeadLetterStore` 无法满足分布式集群多副本共享查看死信需求时，可声明自定义 `@Bean` 实现 `MqttDeadLetterStore` 对接 Redis List/Stream 或关系型数据库：

```java
@Configuration(proxyBeanMethods = false)
public class RedisDeadLetterStoreConfig {

    @Bean
    public MqttDeadLetterStore customMqttDeadLetterStore() {
        return new MqttDeadLetterStore() {
            @Override
            public void save(MqttDeadLetterRecord record) {
                // 默认作用域保存实现（亦可覆写 save(String clientName, MqttDeadLetterRecord record) 隔离多客户端）
            }

            @Override
            public List<MqttDeadLetterRecord> peek(String dltTopic, int limit) {
                return Collections.emptyList();
            }

            @Override
            public boolean remove(String dltTopic, String recordId) {
                return false;
            }

            @Override
            public int purge(String dltTopic) {
                return 0;
            }
        };
    }
}
```

---

### 9.7 动态认证凭据供给 SPI (`MqttCredentialsProvider`)

适用于阿里云 IoT、腾讯云、华为云等按时间戳计算动态 HMAC 签名，或需要按周期刷新短期 JWT / STS 令牌的生产接入场景：

```java
@Component
public class DynamicTokenMqttCredentialsProvider implements MqttCredentialsProvider {

    @Override
    public MqttCredentials resolveCredentials(String clientName, MqttClientProperties clientProperties, boolean reconnect) {
        String dynamicUsername = clientProperties.getUsername();
        String freshToken = issueDynamicToken(clientName, reconnect);
        return MqttCredentials.builder()
                .username(dynamicUsername)
                .password(freshToken)
                .build();
    }

    private String issueDynamicToken(String clientName, boolean reconnect) {
        return "DYNAMIC_HMAC_OR_JWT_TOKEN";
    }
}
```

---

### 9.8 生产非功能防御底线与避坑说明

1. **虚拟线程友好与 KeepAlive 绝对隔离**：全模块同步临界区统一使用 `ReentrantLock` 消除 JDK 21+ 虚拟线程 Carrier Pinning；Paho 网络回调收到报文后即刻提交至受管工作线程池，退避重试交由受管 `ThreadPoolTaskScheduler` 非阻塞调度，杜绝心跳假死雪崩。
2. **Paho 数组订阅并发 NPE 规避**：底层自动将多主题配置拆解为单主题串行迭代订阅，规避 Paho 5.x 客户端批量订阅包确认并发 NPE 隐患。
3. **消费端幂等静默确认 (`SilentAckException`)**：分布式防重切面或幂等逻辑检测到重复投递时，抛出 `SilentAckException`（或框架内置兼容 `IdempotentException`），将直接提交静默 `PUBACK` 阻断死循环且不污染 DLT。
4. **入站与出站大报文熔断 (`PayloadGuard`)**：发布与消费双端毫秒级校验报文字节数，超出 `max-payload-size` 立即拦截阻断，守护 JVM 堆内存安全。
5. **高吞吐飞行窗口 (`maxInflight = 1000`)**：消除 Paho 原生默认 `10` 在并发群发时的 `32202 (Too many publishes in progress)` 报错瓶颈。
6. **优雅停机零遗嘱误报 (`SmartLifecycle`)**：停机阶段通过 `drainLock` 等待消费端在途任务排空再向 Broker 发送标准 `DISCONNECT` 报文，严格抑制每次正常发布触发 LWT 离线误报。

---

### 9.9 生产可观测性（Actuator 探针与低基数 Micrometer 黄金度量）

#### Spring Boot Actuator 健康检查 (`/actuator/health`)
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

#### Micrometer 黄金度量与低基数防爆防线 (`/actuator/prometheus`)
默认 `ateng.boot.mqtt.metrics.include-topic-tag = false`（强烈推荐生产保持默认），仅输出低基数维度，彻底免疫百万动态设备主题导致 Prometheus 时序爆炸与 JVM OOM；若调试或有限固定主题确需开启，受 `max-topic-cardinality = 200` 上限锁死，超额自动收敛归并为 `__overflow__`：

| 指标名称 | 类型 | 维度标签 (Tags) | 业务语义与低基数说明 |
| :--- | :--- | :--- | :--- |
| `mqtt.client.status` | Gauge | `client` | 客户端实时连接状态（`1`=已连接，`0`=断开）；动态注销时自动 `unbindClient` |
| `mqtt.message.published` | Counter | `client`, `qos` (`include-topic-tag=true` 时含有限基数 `topic`) | 出站消息发布成功累计次数 |
| `mqtt.publish.latency` | Timer | `client`, `qos` (`include-topic-tag=true` 时含有限基数 `topic`) | 出站消息发布耗时分布统计 |
| `mqtt.message.received` | Counter | `client` (`include-topic-tag=true` 时含有限基数 `topic`) | 入站原始消息接收累计次数 |
| `mqtt.consumer.latency` | Timer | `endpoint`, `topic` (声明式模板), `status` | 声明式监听器消费耗时（`topic` 采用有限基数模板如 `iot/devices/{deviceId}/telemetry`） |
| `mqtt.consumer.dlt` | Counter | `endpoint`, `topic` (声明式模板) | 转入死信毒丸累计次数（`topic` 采用有限基数声明式模板） |
| `mqtt.requestreply.timeout` | Counter | `client` (`include-topic-tag=true` 时含有限基数 `topic`) | Request-Reply RPC 请求超时累计次数 |

---

### 9.10 场景样例工程导览与双模实测闭环 (`ateng-boot-sample-mqtt`)

完整、自洽、拆分 8 大高内聚领域 `@RestController`（共 30 个 HTTP 体验端点）的演示工程位于 [ateng-boot-sample-mqtt](../ateng-boot-samples/ateng-boot-sample-mqtt)：

- **设备遥测与集群共享消费**：[`MqttTelemetryController.java`](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/controller/MqttTelemetryController.java)（`/sample/mqtt/telemetry/*`）
- **双向 RPC 与 `@MqttReply` 声明式应答**：[`MqttRpcController.java`](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/controller/MqttRpcController.java)（`/sample/mqtt/rpc/*`）
- **工业国标十六进制编解码**：[`MqttIndustrialHexController.java`](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/controller/MqttIndustrialHexController.java)（`/sample/mqtt/hex/*`）
- **批量并发下发与保留消息清除**：[`MqttBatchAndRetainedController.java`](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/controller/MqttBatchAndRetainedController.java)（`/sample/mqtt/batch/*`）
- **毒丸死信、`@Valid` 零重试与 `MqttDeadLetterStore` 治理**：[`MqttDeadLetterController.java`](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/controller/MqttDeadLetterController.java)（`/sample/mqtt/dead-letter/*`）
- **容器引用计数 `pause` / `resume`、手动 ACK 与退避重试**：[`MqttContainerController.java`](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/controller/MqttContainerController.java)（`/sample/mqtt/container/*`）
- **本地事务提交后 `sendAfterCommit` 对齐**：[`MqttTransactionController.java`](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/controller/MqttTransactionController.java)（`/sample/mqtt/transaction/*`）
- **多命名客户端热插拔与 `MqttTemplate.use()` 定向路由**：[`MqttClientGovernanceController.java`](../ateng-boot-samples/ateng-boot-sample-mqtt/src/main/java/io/github/atengk/boot/sample/mqtt/controller/MqttClientGovernanceController.java)（`/sample/mqtt/clients/*`）
- **Web Controller MockMvc 回归套件**：[`SampleMqttWebControllerTest.java`](../ateng-boot-samples/ateng-boot-sample-mqtt/src/test/java/io/github/atengk/boot/sample/mqtt/SampleMqttWebControllerTest.java)
- **双模（内存桩 / 真实 Broker）E2E 回归套件**：[`SampleMqttE2eTest.java`](../ateng-boot-samples/ateng-boot-sample-mqtt/src/test/java/io/github/atengk/boot/sample/mqtt/SampleMqttE2eTest.java)

> 快速启动命令与全场景 cURL 交互指南请参阅：[ateng-boot-sample-mqtt README](../ateng-boot-samples/ateng-boot-sample-mqtt/README.md)。

---

## 10. 完整 YAML 配置属性清单 (Configuration Properties Catalog)

以下为 `ateng-boot-mqtt` 全量强类型配置属性清单（前缀：`ateng.boot.mqtt`）：

| 配置属性键名 | 类型 | 默认值 | 描述说明与生产建议 |
| :--- | :--- | :--- | :--- |
| `enabled` | `boolean` | `true` | 全局 MQTT Starter 总开关 |
| `default-client` | `String` | `primary` | 默认生效的主客户端逻辑名称 |
| `max-replay-count` | `int` | `1` | 死信受控重放最大允许重试次数上限（最小 `1`，防二次雪崩） |
| `consumer.ack-mode` | `AckMode` | `AUTO_AFTER_CONSUME` | 全局消费确认模式：`AUTO_AFTER_CONSUME` / `MANUAL` |
| `consumer.max-retries` | `int` | `2` | 消费端瞬态异常全局最大退避重试次数（`0` 表示禁用重试） |
| `consumer.initial-backoff` | `Duration` | `200ms` | 消费异常首次退避重试等待间隔 |
| `consumer.backoff-multiplier` | `double` | `2.0` | 指数退避延迟倍增乘数（必须 ≥ `1.0`） |
| `consumer.max-backoff` | `Duration` | `5s` | 指数退避单次等待延迟封顶上限 |
| `dlt.retained` | `boolean` | `false` | DLT 消息转发至 Broker 时是否设置 Retained 保留标志（默认 `false` 防单槽覆盖） |
| `dlt.peek-broker-fallback` | `boolean` | `true` | `MqttDeadLetterStore` 无记录时是否回退临时订阅 Broker 探查历史遗留 Retained 死信 |
| `dlt.max-records-per-topic` | `int` | `1000` | `InMemoryMqttDeadLetterStore` 单个 DLT 主题最多留存死信条数（超限 FIFO 淘汰） |
| `dlt.max-total-records` | `int` | `10000` | `InMemoryMqttDeadLetterStore` 全局跨主题最大留存总条数（防内存 OOM） |
| `metrics.include-topic-tag` | `boolean` | `false` | 是否在发信/接收/超时度量中附加物理 `topic` Tag（默认 `false` 防高基数爆炸） |
| `metrics.max-topic-cardinality` | `int` | `200` | 开启 `include-topic-tag` 后允许注册的最大不同主题基数，超限收敛为 `__overflow__` |
| `clients.<name>.server-uri` | `String` | `tcp://127.0.0.1:1883` | Broker 单节点连接地址（支持 `tcp://`、`ssl://`、`ws://`、`wss://`） |
| `clients.<name>.server-uris` | `List<String>` | `[]` | Broker 集群多节点高可用故障转移地址列表（非空时优先于 `server-uri`） |
| `clients.<name>.web-socket-headers` | `Map<String, String>` | `{}` | WebSocket（`ws://` / `wss://`）建连握手附加 HTTP 自定义请求标头 |
| `clients.<name>.client-id` | `String` | `ateng-mqtt-client` | 客户端基础标识前缀 |
| `clients.<name>.client-id-strategy` | `ClientIdStrategy` | `RANDOM_SUFFIX` | 防互踢 ClientId 生成策略：`STATIC`、`RANDOM_SUFFIX`、`IP_PID` |
| `clients.<name>.username` | `String` | `null` | 认证用户名（可被 `MqttCredentialsProvider` 动态覆盖） |
| `clients.<name>.password` | `String` | `null` | 认证密码或令牌（可被 `MqttCredentialsProvider` 动态覆盖） |
| `clients.<name>.protocol-version` | `String` | `5.0` | MQTT 协议版本：默认 `5.0`，兼容 `3.1.1` |
| `clients.<name>.clean-start` | `boolean` | `true` | 是否启用 Clean Start / Clean Session |
| `clients.<name>.session-expiry-interval` | `Duration` | `0s` | MQTT 5.0 会话过期间隔（`0s` 表示断连即清理服务端会话） |
| `clients.<name>.connection-timeout` | `Duration` | `10s` | 建立物理连接与握手超时时间 |
| `clients.<name>.publish-timeout` | `Duration` | `10s` | 同步发布阻塞确认超时时间 |
| `clients.<name>.keep-alive-interval` | `Duration` | `60s` | 客户端心跳保活间隔周期 |
| `clients.<name>.max-reconnect-delay` | `Duration` | `128s` | 断线自动重连指数退避最大等待上限 |
| `clients.<name>.automatic-reconnect` | `boolean` | `true` | 是否启用断线自动重连及订阅全量自愈 |
| `clients.<name>.max-inflight` | `int` | `1000` | 最大未完成在途飞行报文窗口（调优防 `32202` 异常） |
| `clients.<name>.max-buffered-messages` | `int` | `5000` | 断线期间离线缓冲区最大消息容量 |
| `clients.<name>.offline-buffer-policy` | `OfflineBufferPolicy` | `BUFFER_WHEN_RECONNECTING` | 断线重连窗口期普通发布行为：`BUFFER_WHEN_RECONNECTING` / `FAIL_FAST` |
| `clients.<name>.max-payload-size` | `DataSize` | `1MB` | 上下行最大报文体积守卫（防 JVM 堆内存 OOM） |
| `clients.<name>.persistence-type` | `PersistenceType` | `MEMORY` | 离线消息持久化模式：`MEMORY` 或 `FILE` |
| `clients.<name>.persistence-dir` | `String` | `null` | 文件持久化存储根目录（`persistence-type = FILE` 时生效） |
| `clients.<name>.shutdown-timeout` | `Duration` | `10s` | 停机排空与优雅断连最大等待超时时间 |
| `clients.<name>.response-topic-prefix` | `String` | `sys/response/` | Request-Reply RPC 客户端聚合应答主题前缀 |
| `clients.<name>.topic-alias-maximum` | `int` | `0` | MQTT 5.0 主题别名最大数量上限（`0` 为禁用） |
| `clients.<name>.lwt.enabled` | `boolean` | `false` | 是否启用遗嘱消息（Last Will and Testament） |
| `clients.<name>.lwt.topic` | `String` | `null` | 遗嘱消息目标发布主题 |
| `clients.<name>.lwt.payload` | `String` | `null` | 遗嘱消息文本载荷内容 |
| `clients.<name>.lwt.qos` | `int` | `1` | 遗嘱消息 QoS 等级（`0`、`1`、`2`） |
| `clients.<name>.lwt.retain` | `boolean` | `false` | 遗嘱消息是否作为 Broker 保留消息发布 |
| `clients.<name>.lwt.will-delay-interval` | `Duration` | `0s` | MQTT 5.0 延迟发布遗嘱时间窗口（短时闪断重连期间抑制误报） |
| `clients.<name>.ssl.enabled` | `boolean` | `false` | 是否启用 TLS / 双向 mTLS 加密通道 |
| `clients.<name>.ssl.protocol` | `String` | `TLSv1.3` | TLS 协议版本（支持 `TLSv1.3` / `TLSv1.2`） |
| `clients.<name>.ssl.ca-cert` | `String` | `null` | CA 根证书路径（PEM 格式，支持 `classpath:`） |
| `clients.<name>.ssl.client-cert` | `String` | `null` | 客户端双向认证证书路径（PEM 格式，mTLS 必需） |
| `clients.<name>.ssl.client-key` | `String` | `null` | 客户端私钥路径（PEM 格式，mTLS 必需） |
| `clients.<name>.ssl.client-key-password` | `String` | `null` | 客户端 PEM 私钥解密密码（可选） |
| `clients.<name>.ssl.trust-store` | `String` | `null` | 信任库路径（JKS / PKCS12） |
| `clients.<name>.ssl.trust-store-password` | `String` | `null` | 信任库密码 |
| `clients.<name>.ssl.key-store` | `String` | `null` | 密钥库路径（JKS / PKCS12） |
| `clients.<name>.ssl.key-store-password` | `String` | `null` | 密钥库密码 |
| `clients.<name>.ssl.hostname-verification-enabled` | `boolean` | `true` | 是否开启 TLS 服务端证书主机名严格校验（生产强烈建议保持 `true`） |
