# ateng-boot-tcp 基础设施 Starter

> 工业级 Netty 双模长连接通信底座 Starter，原生基于 Netty 4.1.x 高性能异步事件驱动网络库构建，深度支持“服务端（Server）+ 客户端（Client）”双模全场景长连接通信。内置确定性粘包拆包切片、直接内存安全释放、会话生命周期治理与踢旧留新置换、双向全双工 RPC 请求-应答闭环与超时熔断、出站缓冲区高低水位背压流控防 OOM、网络层 IP 访问控制防火墙与 CIDR 白名单、全局与单连接流量整形限速削峰填谷、集群跨节点分组组播防回环、双轨消息路由（声明式 `@TcpListener` / `@TcpMapping` 与函数式 `TcpMessageHandler` SPI）、线程池异步隔离与 MDC Trace 穿透、多端口多协议监听拓扑、命名多客户端管理、TLS/mTLS 链路加密、三阶段平滑优雅停机排空、Spring Boot Actuator 生产管控端点与 Micrometer 网络黄金度量体系。
> 
> 架构设计决策详见：[ADR-0083 工业级 TCP 全场景高并发长连接通信底座架构决策](../docs/adr/0083-tcp-full-scenario-hardening-backpressure-and-resilient-governance-architecture.md) 与 [模块上下文说明](./CONTEXT.md)。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.tcp.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **服务端发信** | 会话/设备发信与广播 | `TcpServerTemplate` | `ateng.boot.tcp.server.enabled` | `send(String sessionId, Object payload)`<br>`sendToDevice(String deviceId, Object payload)`<br>`broadcast(Object payload)`<br>`close(sessionId)` / `closeDevice(deviceId)` 主动断开并注销会话，支持直接发送 `TcpPacket`、POJO、字符串或原始字节 | `TcpServerTemplateTest` / `SampleTcpE2eTest` |
| **服务端同步 RPC** | 全双工请求-应答闭环 | `TcpServerTemplate`<br>`TcpRequestCorrelationManager` | `ateng.boot.tcp.server.enabled` | `request(sessionId, payload)` / `request(sessionId, payload, Duration)` / `request(sessionId, payload, Class<T>)` / `request(sessionId, payload, Duration, Class<T>)`<br>`requestToDevice(deviceId, payload)` / `requestToDevice(deviceId, payload, Duration)` / `requestToDevice(deviceId, payload, Class<T>)` / `requestToDevice(deviceId, payload, Duration, Class<T>)`，支持泛型强类型自动转换与 `HashedWheelTimer` 超时熔断 | `TcpServerTemplateTest` / `SampleTcpE2eTest` |
| **服务端异步 RPC** | 异步非阻塞应答 | `TcpServerTemplate` | `ateng.boot.tcp.server.enabled` | `requestAsync(sessionId, payload)` / `requestAsync(sessionId, payload, Duration)`<br>`requestToDeviceAsync(deviceId, payload)` / `requestToDeviceAsync(deviceId, payload, Duration)`，默认 10s 超时，返回 `CompletableFuture<TcpPacket>` | `TcpServerTemplateTest` / `SampleTcpE2eTest` |
| **集群跨节点推送** | 两级自适应集群桥接 | `TcpServerTemplate`<br>`TcpClusterBridge` SPI<br>`TcpClusterBroadcastMessage` | `ateng.boot.tcp.cluster.*` | `sendToCluster(String deviceId, Object payload)`<br>`broadcastToCluster(Object payload)`，第一级本地命中直发，未命中投递 Redis 广播并防回环过滤；支持 `PACKET` 载荷全量透传 Command、RequestId、DeviceId 与 Headers 元数据 | `TcpClusterBridgeTest` / `SampleTcpE2eTest` |
| **集群分组组播** | 跨节点车队群组组播 | `TcpServerTemplate`<br>`TcpClusterBridge` SPI<br>`TcpSessionManager` | `ateng.boot.tcp.cluster.*` | `sendGroupToCluster(String group, Object payload)`，本地直接广播当前节点分组会话并跨节点广播至 Redis，各节点按 `group` 路由并过滤防回环 | `TcpClusterBridgeTest` / `SampleTcpE2eTest` |
| **客户端发信** | 客户端主动上行发信 | `TcpClientTemplate` | `ateng.boot.tcp.client.enabled` | `send(Object payload)` / `sendAsync(Object payload)`，通过当前活跃长连接通道异步发送数据 | `TcpClientTemplateTest` / `SampleTcpE2eTest` |
| **客户端同步 RPC** | 客户端全双工应答 | `TcpClientTemplate`<br>`TcpRequestCorrelationManager` | `ateng.boot.tcp.client.enabled` | `request(payload)` / `request(payload, Duration)` / `request(payload, Class<T>)` / `request(payload, Class<T>, Duration)`<br>`requestAsync(payload)` / `requestAsync(payload, Duration)`，等待服务端应答，超时自动清理挂起句柄 | `TcpClientTemplateTest` / `TcpClientIntegrationTest` |
| **多客户端调度** | 命名多客户端路由注册 | `TcpClientRegistry`<br>`TcpClientTemplate` | `ateng.boot.tcp.clients.*` | `use(String clientName)`，动态获取指定远程连接的独立客户端操作门面；`getClientRegistry()` 反查客户端实体 | `TcpClientRegistryTest` / `TcpMultiClientIntegrationTest` |
| **会话状态治理** | 会话生命周期与踢旧留新 | `TcpSessionManager` | `ateng.boot.tcp.server.max-connections` | `register(session)` / `unregister(sessionId, reason)`<br>`bind(sessionId, deviceId)` / `unbind(deviceId)`<br>`closeSession(sessionId, reason)` / `closeDevice(deviceId, reason)`，设备重连自动执行“踢旧留新”，切断旧物理会话 | `TcpSessionManagerTest` / `SampleTcpE2eTest` |
| **分组与会话检索** | 设备/分组多维检索 | `TcpSessionManager` | - | `getSession(sessionId)` / `getSessionByDeviceId(deviceId)`<br>`addToGroup(group, sessionId)` / `removeFromGroup(group, sessionId)`<br>`getSessionsByGroup(group)`（推荐）/ `getGroupSessions(group)`（`@Deprecated`）/ `getActiveCount()` | `TcpSessionManagerTest` / `SampleTcpE2eTest` |
| **出站背压流控** | 高低水位与出站流控守卫 | `TcpSession`<br>`BackpressurePolicy`<br>`TcpBackpressureException` | `ateng.boot.tcp.server.write-buffer-high-water-mark`<br>`ateng.boot.tcp.server.write-buffer-low-water-mark`<br>`ateng.boot.tcp.server.backpressure-policy`<br>`ateng.boot.tcp.server.backpressure-timeout` | `session.isWritable()` / `session.getPendingWriteBytes()`；支持 `FAIL_FAST`（生产默认）快速失败阻断、`BLOCK_TIMEOUT` 阻塞等待、`WARN_AND_SEND` 告警发送，彻底消除慢客户端堆积引发的 JVM OOM 崩溃 | `TcpBackpressureIntegrationTest` / `SampleTcpE2eTest` |
| **网络层 IP 防火墙** | CIDR 白名单与黑名单安全 | `TcpIpFilterProperties`<br>`TcpIpFilterHandler`<br>`TcpIpBlockedEvent` | `ateng.boot.tcp.server.ip-filter.enabled`<br>`ateng.boot.tcp.server.ip-filter.whitelist`<br>`ateng.boot.tcp.server.ip-filter.blacklist` | 管道首位纳秒级握手拦截，支持精确 IP（`192.168.1.100`）与 CIDR 网段（`10.0.0.0/8`）；白名单非空自动切换白名单严控模式；拦截时发布 `TcpIpBlockedEvent`，零直接内存分配 | `TcpIpFilterIntegrationTest` / `SampleTcpE2eTest` |
| **带宽流量整形** | 全局与通道级限速削峰 | `TcpTrafficShapingProperties`<br>`GlobalTrafficShapingHandler`<br>`ChannelTrafficShapingHandler` | `ateng.boot.tcp.server.traffic-shaping.enabled`<br>`ateng.boot.tcp.server.traffic-shaping.global-read-limit`<br>`ateng.boot.tcp.server.traffic-shaping.global-write-limit`<br>`ateng.boot.tcp.server.traffic-shaping.channel-read-limit`<br>`ateng.boot.tcp.server.traffic-shaping.channel-write-limit`<br>`ateng.boot.tcp.server.traffic-shaping.check-interval`<br>`ateng.boot.tcp.server.traffic-shaping.max-time` | 基于令牌桶算法限制全局与单连接出入站带宽速率（`DataSize` 强类型）；优雅停机阶段受管调用 `release()` 释放整形定时线程 | `TcpTrafficShapingIntegrationTest` / `SampleTcpE2eTest` |
| **声明式监听** | 声明式长连接监听器 | `@TcpListener` | - | 声明于 Spring Bean 上，标识当前类为 TCP 监听器；支持指定归属服务端或客户端角色，自动纳管生命周期 | `TcpDispatcherTest` / `SampleTcpE2eTest` |
| **声明式方法路由** | 命令字方法映射与分发 | `@TcpMapping` | - | 标注于方法上，绑定业务命令字（如 `@TcpMapping("LOCATION_REPORT")`）；方法形参支持 Session、Packet、Payload、POJO、byte[]、String | `TcpDispatcherTest` / `SampleTcpE2eTest` |
| **硬件标识注入** | 设备编号参数自动提取 | `@TcpDeviceId` | - | 标注于方法参数上，自动提取当前报文或会话绑定的硬件标识 `deviceId` | `TcpDispatcherTest` / `SampleTcpE2eTest` |
| **通信标头注入** | 自定义标头提取与默认值 | `@TcpHeader` | - | 标注于方法参数上，支持从报文头或 JSON 属性提取自定义参数（如 `@TcpHeader("X-Device-Id")`），支持 `required` 与 `defaultValue` | `TcpDispatcherTest` / `SampleTcpE2eTest` |
| **函数式路由 SPI** | 强类型命令字处理器 SPI | `TcpMessageHandler<T>` | - | 强类型函数式接口，声明 `getCommand()` 与 `handle(session, payload, rawPacket)`，无需反射高效路由 | `TcpDispatcherTest` |
| **路由命令字提取 SPI** | 可插拔命令字解析 SPI | `TcpRouteKeyExtractor`<br>`CompositeRouteKeyExtractor` | - | 支持从入站报文中提取路由键；内置 `JsonRouteKeyExtractor`（从 JSON 提取 command 等属性）与 `BinaryOffsetRouteKeyExtractor`（固定偏移截取）；可扩展国标二进制复合提取器 | `TcpRouteKeyExtractorTest` / `SampleTcpE2eTest` |
| **关联流水号提取 SPI** | RPC 关联标识提取 SPI | `TcpCorrelationIdExtractor` | - | 从报文提取 RPC 关联流水号 `requestId`；默认优先取 `packet.getRequestId()` 或从 JSON/Map 解析 | `TcpRequestCorrelationManagerTest` |
| **通道定制逃生舱** | Netty Channel 流水线定制 | `TcpChannelCustomizer` SPI | - | 在 Netty Pipeline 自由编排注册自定义 Handler；支持按组件角色（SERVER/CLIENT）与实例名定向定制 | `TcpChannelCustomizerTest` / `SampleTcpE2eTest` |
| **报文拦截过滤 SPI** | 入站/出站双向拦截器 | `TcpPacketFilter` SPI<br>`TcpFilterChain` | - | 链式拦截入站与出站报文；支持验签、限流、解密、日志记录与短路阻断 | `TcpPacketFilterHandlerTest` |
| **自愈指数退避重连** | 客户端断线自愈重连 | `TcpClient` | `ateng.boot.tcp.client.reconnect.*` | 支持连接失败或异常断开后自动后台重连；基于 $2^n$ 指数退避算法并附加最大间隔与重试次数上限保护 | `TcpClientIntegrationTest` |
| **空闲心跳保活** | 双向心跳探测与空闲超时 | `TcpServer` / `TcpClient` | `ateng.boot.tcp.server.idle.*`<br>`ateng.boot.tcp.client.heartbeat.*` | 服务端检测读写空闲超时自动断开假死连接；客户端定时发送心跳包保活，防止防火墙 NAT 超时静默切断 | `TcpHeartbeatIntegrationTest` |
| **粘包半包分帧切片** | 数据帧切片解码与出站定界 | `TcpFramingDecoderFactory`<br>`TcpPayloadCodecHandler` | `ateng.boot.tcp.server.framing.*`<br>`ateng.boot.tcp.client.framing.*` | 支持 `NONE`、`DELIMITER`（定界符）、`FIXED_LENGTH`（固定长度）及 `LENGTH_FIELD`（基于长度字段）；出站自动补齐定界符 | `TcpFramingDecoderFactoryTest` / `SampleTcpE2eTest` |
| **国标复合分帧编解码** | JT/T 808 国标风格转义与校验 | `Jt808CompositeFrameCodec`<br>`ChecksumUtil` | `ateng.boot.tcp.server.framing.delimiter=0x7E` | 支持 `0x7E` 起止定界、`0x7D 0x02 <-> 0x7E` 与 `0x7D 0x01 <-> 0x7D` 报文转义还原与 BCC 异或（XOR）全真校验和防伪验证 | `Jt808CompositeFrameCodecTest` / `SampleTcpE2eTest` |
| **直接内存安全释放** | 入站安全堆转译与零泄漏 | `TcpPayloadCodecHandler` | - | 入站 ByteBuf 确定性在 `finally` 块通过 `ReferenceCountUtil.release()` 释放，业务层真明文 `TcpPayload` 安全交付 | `TcpPayloadCodecHandlerTest` |
| **业务强类型编解码 SPI** | 可插拔双向编解码器 SPI | `TcpCodec<T>` SPI | - | 定义 `encode(T message)` 与 `decode(TcpPayload payload)`，支持将自定义私有协议强类型对象无损双向转译 | `TcpPayloadCodecHandlerTest` |
| **工业校验和计算** | 工业国标校验码工具 | `ChecksumUtil` | - | 提供 BCC 异或校验（XOR）、CRC-16 Modbus/CCITT、CRC-32、JT/T 808 转义及十六进制与字节数组双向转换原语 | `ChecksumUtilTest` |
| **多端口监听拓扑** | 服务端多端口多协议支持 | `TcpServerRegistry` | `ateng.boot.tcp.servers.*` | 支持同时监听多个不同物理端口并挂载独立协议分帧与通道定制逻辑（如车机 JT/T 808 端口与通用 JSON 端口共存） | `TcpMultiPortIntegrationTest` |
| **链路加密安全** | TLS/mTLS 传输加密与双向认证 | `TcpSslContextFactory` | `ateng.boot.tcp.server.ssl.*`<br>`ateng.boot.tcp.client.ssl.*` | 支持 CA 根证书、PEM 格式证书与双向 mTLS 客户端证书强认证，严守 TLSv1.2 / TLSv1.3 安全基线 | `TcpTlsIntegrationTest` |
| **三阶段平滑停机** | Spring 受管生命周期停机 | `TcpServer` / `TcpClient`<br>`SmartLifecycle` | `ateng.boot.tcp.server.drain-timeout`<br>`ateng.boot.tcp.client.drain-timeout` | 阶段 1：解除端口监听；阶段 2：受管排空窗口等待在途 RPC Future；阶段 3：注销存量会话并安全关闭 EventLoop 线程池 | `TcpGracefulShutdownTest` |
| **健康状态与管控端点** | Actuator 生产运维端点 | `TcpActuatorEndpoint` | `management.endpoints.web.exposure.include=tcp` | 暴露 `/actuator/tcp` 输出全局健康状态、活跃会话数、多服务端/客户端拓扑及指标；提供 `/actuator/tcp/{deviceId}` 单设备穿透诊断 | `TcpObservabilityIntegrationTest` / `SampleTcpE2eTest` |
| **黄金度量指标** | Micrometer 网络指标注册 | `TcpMetricsCollector` | - | 自动注册 `ateng.tcp.connections.active` (Gauge)、`total`、`messages.received/sent`、`bytes.received/sent` (Counter) 与 `request.duration` (Timer) | `TcpObservabilityIntegrationTest` / `SampleTcpE2eTest` |
| **全链路领域与治理事件** | Spring 领域感知事件驱动 | `TcpSessionConnectedEvent`<br>`TcpDeviceBoundEvent`<br>`TcpSessionClosedEvent`<br>`TcpSessionIdleEvent`<br>`TcpChannelWritabilityChangedEvent`<br>`TcpIpBlockedEvent` | - | 会话建立、设备绑定、连接断开（带 `CloseReason`）、空闲超时、出站背压高低水位切换及 IP 防火墙拦截自动发布领域事件 | `TcpSessionManagerTest` / `SampleTcpE2eTest` |
| **强类型配置模型** | IDE 智能补全与无量纲防卫 | `AtengTcpProperties`<br>`TcpServerProperties`<br>`TcpClientProperties` | `ateng.boot.tcp.*` | 超时时间统一声明为 `Duration`，缓冲与帧体积声明为 `DataSize`，彻底消除无量纲单位歧义，生产合理默认值开箱即用 | `AtengTcpPropertiesTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-tcp` 是面向生产就绪的高并发、全场景长连接通信核心底座 Starter：
- **纯原生零臃肿依赖**：100% 依托 JDK 17 LTS、Spring 6 与 Netty 原生能力，杜绝引入任何第三方全量工具包；
- **双模架构统一抽象**：统一支持服务端长连接多设备纳管与客户端主动长连接集成，双向全双工 RPC 统一契约；
- **全链路上下文透传**：打通 `ateng-boot-core` 的 `AsyncContext` 与受管线程池，在分发至业务方法时自动透传 MDC `traceId`，并在任务结束后严格清理现场；
- **工业级网络韧性防线**：内置高低水位背压流控、网络层 IP 访问控制防火墙、带宽流量整形、超时熔断、断线自愈重连、踢旧留新防僵尸连接与三阶段受管停机排空。

### Maven 坐标引入

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-tcp</artifactId>
</dependency>
```

---

## 2. 生产基础配置与极简起步 (Happy Path & Out-of-the-Box)

### 2.1 生产级配置范式 (application.yml)

```yaml
ateng:
  boot:
    tcp:
      enabled: true
      # 服务端长连接配置
      server:
        enabled: true
        host: 0.0.0.0
        port: 9099
        max-connections: 10000        # 最大允许长连接并发数
        framing:
          mode: DELIMITER             # 粘包拆包分帧模式：DELIMITER / FIXED_LENGTH / LENGTH_FIELD / NONE
          delimiter: "\n"             # 分隔符（出站自动对称补齐，入站自动剔除）
        idle:
          reader-idle: 60s            # 读空闲心跳检测超时阈值（超过自动切断断网假死连接）
        drain-timeout: 5s             # 停机排空在途 RPC 等待窗口
        # 出站高低水位背压流控（防慢客户端堆积引发 JVM OOM）
        write-buffer-low-water-mark: 32KB
        write-buffer-high-water-mark: 64KB
        backpressure-policy: BLOCK_TIMEOUT
        backpressure-timeout: 3s
        # 网络层 IP 访问控制防火墙（纳秒级首位拦截，白名单非空自动切换严格白名单模式）
        ip-filter:
          enabled: true
          whitelist:
            - 127.0.0.1
            - 192.168.0.0/16
          blacklist:
            - 10.0.0.99
        # 全局与单连接流量整形（限速与削峰填谷）
        traffic-shaping:
          enabled: false
          global-read-limit: 10MB
          global-write-limit: 20MB
          channel-read-limit: 1MB
          channel-write-limit: 2MB

# 可选集成：暴露 Actuator 生产监控与管控端点
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, tcp
  endpoint:
    tcp:
      enabled: true
```

### 2.2 服务端极简起步 (Server: 声明式监听与全双工下发)

#### 第一步：声明式接收终端报文并绑定设备标识

```java
package io.github.atengk.boot.sample.tcp.listener;

import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;
import io.github.atengk.boot.tcp.annotation.TcpDeviceId;
import io.github.atengk.boot.tcp.annotation.TcpListener;
import io.github.atengk.boot.tcp.annotation.TcpMapping;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@TcpListener
public class VehicleTelemetryListener {

    private static final Logger log = LoggerFactory.getLogger(VehicleTelemetryListener.class);
    private final TcpSessionManager sessionManager;

    public VehicleTelemetryListener(TcpSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 终端上线登录认证：绑定设备唯一硬件标识
     */
    @TcpMapping(command = "LOGIN")
    public void onLogin(TcpSession session, @TcpDeviceId String deviceId, Map<String, Object> body) {
        String effectiveDeviceId = deviceId;
        if (effectiveDeviceId == null && body != null && body.containsKey("deviceId")) {
            effectiveDeviceId = String.valueOf(body.get("deviceId"));
        }
        if (effectiveDeviceId != null && session != null) {
            sessionManager.bind(session.getSessionId(), effectiveDeviceId.trim());
            log.info("车机登录认证成功，完成设备标识绑定: sessionId={}, deviceId={}", session.getSessionId(), effectiveDeviceId);
        }
    }

    /**
     * 接收终端高频定位遥测数据帧并自动回复 ACK
     */
    @TcpMapping(command = "LOCATION_REPORT")
    public Map<String, Object> onLocationReport(TcpSession session, @TcpDeviceId String deviceId, LocationReportPacket report) {
        String targetDeviceId = deviceId != null ? deviceId : (report != null ? report.getDeviceId() : null);
        log.info("接收到车机定位上报: deviceId={}, seq={}, speed={}km/h",
                targetDeviceId, report != null ? report.getSeq() : null, report != null ? report.getSpeed() : null);
        return Map.of("command", "LOCATION_ACK", "status", "SUCCESS");
    }
}
```

#### 第二步：向指定车机下发全双工 RPC 参数查询指令并同步等待应答

```java
package io.github.atengk.boot.sample.tcp.service;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQuery;
import io.github.atengk.boot.sample.tcp.model.VehicleParamQueryAck;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class VehicleTelemetryScenarioService {

    private final TcpServerTemplate tcpServerTemplate;

    public VehicleTelemetryScenarioService(TcpServerTemplate tcpServerTemplate) {
        this.tcpServerTemplate = tcpServerTemplate;
    }

    /**
     * 向指定车机下发全双工 RPC 参数查询指令并在 3 秒内同步等待应答
     */
    public VehicleParamQueryAck queryDeviceParams(String deviceId, String paramType) {
        String requestId = IdUtil.fastSimpleUUID();
        VehicleParamQuery query = new VehicleParamQuery(requestId, deviceId, paramType);

        TcpPacket packet = TcpPacket.builder()
                .command("PARAM_QUERY")
                .requestId(requestId)
                .deviceId(deviceId)
                .payload(TcpPayload.fromJson(query))
                .build();

        return tcpServerTemplate.requestToDevice(deviceId, packet, Duration.ofSeconds(3), VehicleParamQueryAck.class);
    }

    /**
     * 向全网在线长连接车机广播预警消息
     */
    public void broadcastSpeedLimitAlert(String alertMessage) {
        TcpPacket packet = TcpPacket.ofJson("ALERT_BROADCAST", java.util.Map.of(
                "command", "ALERT_BROADCAST",
                "message", alertMessage
        ));
        tcpServerTemplate.broadcast(packet);
    }
}
```

### 2.3 极简客户端三板斧 (Client: 主动发起长连接与发信)

配置启用客户端：

```yaml
ateng:
  boot:
    tcp:
      client:
        enabled: true
        host: 192.168.1.100
        port: 9099
        reconnect:
          enabled: true               # 开启断线自动重连
          initial-interval: 1s        # 初始重试退避间隔
          max-interval: 30s           # 最大重试退避上限
          multiplier: 2.0             # 指数退避乘数
        heartbeat:
          enabled: true               # 开启自动心跳探测保活
          interval: 30s
        # 客户端出站背压流控
        write-buffer-low-water-mark: 32KB
        write-buffer-high-water-mark: 64KB
        backpressure-policy: BLOCK_TIMEOUT
```

通过核心门面 `TcpClientTemplate` 发送或发起 RPC 请求：

```java
@Autowired
private TcpClientTemplate tcpClientTemplate;

public void reportTelemetry(Object telemetryData) {
    // 异步发送数据帧
    tcpClientTemplate.send(telemetryData);
}

public ConfigResponse queryServerConfig() {
    // 发起 RPC 同步请求，5 秒超时
    return tcpClientTemplate.request(
            TcpPacket.ofJson("GET_CONFIG", Map.of("key", "timeout")),
            ConfigResponse.class,
            Duration.ofSeconds(5)
    );
}
```

---

## 3. 生产扩展与深度定制 (Customization & Extensibility)

### 3.1 出站背压流控策略与通道可写性变迁监听

针对生产者发送速率远超消费者处理能力的慢客户端场景，框架提供三种出站背压流控策略：

| 策略枚举 (`BackpressurePolicy`) | 核心行为 | 适用场景 |
| :--- | :--- | :--- |
| `FAIL_FAST` (生产默认) | 触达高水位时立即抛出 `TcpBackpressureException`，拒绝出站堆积 | 实时流控敏感系统，快速失败并将背压向上游传播 |
| `BLOCK_TIMEOUT` | 阻塞调用线程等待对端排空，超过超时阈值抛出 `TcpBackpressureException` | 高吞吐低延迟场景，兼顾背压流控与业务可用性 |
| `WARN_AND_SEND` | 记录高危 WARN 日志后强行压入出站队列（允许堆积） | 诊断分析或不可丢弃的关键下发指令 |

开发者不仅可以通过配置文件统一设定全局背压策略，还可在业务运行期针对特定重要长连接会话动态编程式调整背压行为：

```java
// 针对关键会话动态定制为阻塞等待 2 秒策略
session.setBackpressurePolicy(BackpressurePolicy.BLOCK_TIMEOUT, Duration.ofSeconds(2));
```

监听出站可写性变迁事件：

```java
@Component
public class TcpBackpressureEventListener {

    private static final Logger log = LoggerFactory.getLogger(TcpBackpressureEventListener.class);

    @EventListener
    public void onWritabilityChanged(TcpChannelWritabilityChangedEvent event) {
        log.info("通道可写性跃迁: sessionId={}, writable={}, pendingBytes={}",
                event.getSession().getSessionId(),
                event.isWritable(),
                event.getSession().getPendingWriteBytes());
    }
}
```

### 3.2 网络层 IP 访问控制防火墙与 CIDR 白名单

在应用握手最前置阶段直接阻断非授权 IP，规避恶意连接耗尽 Socket 句柄：

```yaml
ateng:
  boot:
    tcp:
      server:
        ip-filter:
          enabled: true
          # 白名单非空时自动切换白名单严控模式（仅放行白名单，阻断其他全部 IP）
          # 白名单为空时自动切换黑名单模式（仅拦截黑名单，放行其他全部 IP）
          whitelist:
            - 127.0.0.1               # 单一主机地址
            - 192.168.1.0/24          # CIDR 局域网段
            - 10.0.0.0/8              # 内网骨干网段
          blacklist:
            - 10.0.0.99               # 明确恶意 IP
```

监听被防火墙拦截的安全事件：

```java
@Component
public class TcpSecurityAuditListener {

    private static final Logger log = LoggerFactory.getLogger(TcpSecurityAuditListener.class);

    @EventListener
    public void onIpBlocked(TcpIpBlockedEvent event) {
        log.warn("防空网警报: 拦截非法连接 remote={}, ruleDetail={}",
                event.getRemoteAddress(), event.getRuleDetail());
    }
}
```

### 3.3 全局与单连接流量整形削峰填谷

```yaml
ateng:
  boot:
    tcp:
      server:
        traffic-shaping:
          enabled: true
          global-read-limit: 50MB     # 全局读取上限（所有连接共享）
          global-write-limit: 100MB   # 全局写入上限
          channel-read-limit: 2MB     # 单长连接读取速率上限
          channel-write-limit: 5MB    # 单长连接写入速率上限
          check-interval: 500ms       # 速率统计周期
          max-time: 15s               # 延迟等待上限（超过则丢弃或断开）
```

### 3.4 车队分组集群跨节点组播 (Redis Pub/Sub 广播防回环)

将长连接按业务群组划分（如车队、房间、租户），实现跨节点集群定向组播：

```java
// 1. 将车机当前活跃会话加入指定车队分组
sessionManager.addToGroup("FLEET-ALPHA", session.getSessionId());

// 2. 向 FLEET-ALPHA 车队进行跨节点集群组播（本地直发 + 远程 Redis Pub/Sub 广播）
tcpServerTemplate.sendGroupToCluster("FLEET-ALPHA", TcpPacket.ofJson("FLEET_ALERT", Map.of(
        "group", "FLEET-ALPHA",
        "message", "ALPHA车队请注意保持安全车距"
)));
```

#### 自定义集群桥接扩展点 (TcpClusterBridge SPI 接管)
框架默认提供基于 Redis Pub/Sub 的分布式桥接实现（`RedisPubSubTcpClusterBridge`）。当业务需要使用 Kafka、RocketMQ、RabbitMQ 或专用 RPC 总线跨节点分发时，只需实现 `TcpClusterBridge` SPI 并注册为 Spring Bean：

```java
@Component
public class CustomRocketMqClusterBridge implements TcpClusterBridge {

    @Override
    public boolean sendToCluster(String deviceId, Object payload) {
        // 自定义 MQ 投递目标设备报文
        return true;
    }

    @Override
    public boolean broadcastToCluster(Object payload) {
        // 自定义 MQ 全网广播
        return true;
    }

    @Override
    public boolean sendGroupToCluster(String group, Object payload) {
        // 自定义 MQ 车队/分组广播
        return true;
    }
}
```
框架自动遵循 `@ConditionalOnMissingBean(TcpClusterBridge.class)` 契约，由开发者自定义桥接平滑接管，无需侵入框架核心。

### 3.5 交通部 JT/T 808 国标复合分帧与双轨路由 SPI

支持国标二进制复合分帧（`0x7E` 起止符、`0x7D` 转义还原与 BCC 校验）与标准 JSON 协议共存：

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Jt808RouteKeyExtractor implements TcpRouteKeyExtractor {

    private final JsonRouteKeyExtractor jsonExtractor = new JsonRouteKeyExtractor();

    @Override
    public String extract(TcpPacket packet) {
        if (packet == null || packet.getPayload() == null) {
            return null;
        }
        byte[] bytes = packet.getPayload().asBytes();
        if (bytes != null && bytes.length > 3 && bytes[0] == Jt808CompositeFrameCodec.DELIMITER) {
            // 国标二进制复合帧：0x7E 开头，先提取帧内头部数据执行反转义，防御 0x7D 转义字符造成的偏移
            int probeLen = Math.min(bytes.length - 1, 8);
            byte[] headerProbe = new byte[probeLen];
            System.arraycopy(bytes, 1, headerProbe, 0, probeLen);
            byte[] unescaped = ChecksumUtil.unescapeJt808(headerProbe);
            if (unescaped.length >= 2) {
                int msgId = ((unescaped[0] & 0xFF) << 8) | (unescaped[1] & 0xFF);
                if (msgId == (Jt808CompositeFrameCodec.MSG_ID_LOCATION & 0xFFFF)) {
                    return "JT808_LOCATION";
                }
                return String.format("JT808_0x%04X", msgId);
            }
        }
        // 兜底委托给标准 JSON 路由命令字提取器
        return jsonExtractor.extract(packet);
    }
}
```

```java
@TcpMapping(command = "JT808_LOCATION")
public Map<String, Object> onJt808LocationReport(TcpSession session, TcpPacket packet) {
    // 自动反转义、校验 BCC 校验和并解码为强类型定位实体
    LocationReportPacket report = Jt808CompositeFrameCodec.decodeLocationReport(packet.getPayload().asBytes());
    return Map.of("command", "JT808_LOCATION_ACK", "status", "SUCCESS");
}
```

### 3.6 Netty 流水线定制逃生舱 (TcpChannelCustomizer SPI)

```java
@Component
public class CustomChannelCustomizer implements TcpChannelCustomizer {

    @Override
    public void customize(Channel channel, String role, String name) {
        if ("SERVER".equalsIgnoreCase(role)) {
            // 在 Netty Pipeline 自由编排插入业务专用 Handler
            channel.pipeline().addBefore("serverHandler", "rateLimiterHandler", new MyRateLimiterHandler());
        }
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
```

### 3.7 函数式强类型消息处理器 SPI (TcpMessageHandler)

除声明式 `@TcpMapping` 之外，框架支持通过实现 `TcpMessageHandler<T>` SPI 完成免反射、强类型的高性能业务分发：

```java
@Component
public class DeviceAlarmMessageHandler implements TcpMessageHandler<DeviceAlarmDTO> {

    @Override
    public String getCommand() {
        return "DEVICE_ALARM";
    }

    @Override
    public void handle(TcpSession session, DeviceAlarmDTO payload, TcpPacket rawPacket) {
        log.warn("收到设备告警: deviceId={}, level={}, msg={}",
                session.getDeviceId(), payload.getLevel(), payload.getMessage());
    }
}
```

---

## 4. 核心生产边界与非功能故障语义 (Production Boundary & Failure Modes)

### 4.1 出站背压与防 JVM OOM 原理 (Watermark Backpressure)
在长连接通信中，当对端网络劣化或接收极慢时，服务端调用 `channel.writeAndFlush(...)` 会将数据无限堆积在 Netty 的 `ChannelOutboundBuffer` 链表中，最终撑爆 JVM 堆内存。
- 框架将出站缓冲区高低水位收敛为强类型 `write-buffer-high-water-mark` 与 `write-buffer-low-water-mark`；
- 当待发送未排空字节数触达高水位时，Netty 自动将通道置为不可写（`isWritable = false`）并发布 `TcpChannelWritabilityChangedEvent`；
- 发送拦截器根据配置的 `BackpressurePolicy` 实施快速失败阻断（抛出 `TcpBackpressureException`）或超时等待，彻底消除因慢客户端无限堆积导致的 JVM OOM 崩溃。

### 4.2 直接内存绝对可靠释放 (Zero Direct-Memory Leak)
Netty 默认使用堆外直接内存（Direct ByteBuf）以获取高性能零拷贝吞吐。若业务代码或异常分支未释放引用计数，将引发宿主操作系统的直接内存泄漏。
- `ateng-boot-tcp` 将直接内存转译与释放全部收敛于底层 `TcpPayloadCodecHandler`；
- 原始入站 `ByteBuf` 统一拷贝转译为业务层不可变安全实体 `TcpPayload`，随后在底层 `finally` 块中通过 `ReferenceCountUtil.release(byteBuf)` 绝对确定性释放引用计数；
- 无论业务层处理成功、抛出未受检异常或超时，网络直接内存 100% 确保零泄漏。

### 4.3 网络层防火墙纳秒级快速拦截 (Zero-Allocation Firewall)
- `TcpIpFilterHandler` 继承自 Netty 的 `RuleBasedIpFilter`，直接挂载于 ChannelPipeline 首位；
- 在 TCP 连接三次握手建立的物理事件上立即执行规则匹配，非白名单或黑名单 IP 在分配任何堆外缓冲区之前即被强制关闭物理通道；
- 拦截过程同步发布 `TcpIpBlockedEvent` 领域事件，实现兼顾毫秒级安全审计与零内存分配开销。

### 4.4 流量整形排队平滑与优雅停机
- 流量整形基于令牌桶算法对网络包进行平滑限速，当瞬间突发流量超过限制时，整形处理器将数据帧放入内部等待队列定时排空，消除流量毛刺；
- 在应用停机阶段，`TcpServer` 在销毁阶段显式触发 `GlobalTrafficShapingHandler.release()`，绝对保证内部的 Netty 整形定时线程被安全注销，杜绝停机线程悬挂。

### 4.5 全双工 RPC 时间轮超时熔断与挂起防泄漏
在全双工 TCP 通信中，一个长连接可能并发承载上万次双向 RPC 请求：
- `TcpRequestCorrelationManager` 使用哈希槽时间轮 `HashedWheelTimer` 统一调度每一个注册的在途 `CompletableFuture`；
- 当超过开发者指定的 `timeout`（或默认超时阈值）仍未收到对端应答时，时间轮确定性触发超时熔断，向调用方抛出 `TcpTimeoutException` 并从并发映射池中彻底剔除挂起句柄，杜绝长期无应答引发的 JVM 堆内存膨胀与内存泄漏。

### 4.6 跨节点集群组播防回环路由机制
- 跨节点广播通过 Redis Pub/Sub 将数据下发给所有集群节点；
- 每一个 `TcpClusterBroadcastMessage` 均携带发射节点的全局唯一 `nodeId`（基于无锁 `fastSimpleUUID` 生成）；
- 接收节点在消费广播消息时，首先对比自身 `nodeId`：若发现消息源于本节点，立即静默丢弃，彻底防止“本地发信 -> Redis 广播 -> 本地再次接收重发”的广播回环风暴。

### 4.7 弱网重连“踢旧留新 (Kick-out)”置换状态机
在车联网或工业 IoT 场景中，4G/5G 弱网闪断或设备频繁掉线重连是常态。若服务端未能及时感知网络断开，往往会残留旧连接并形成僵尸连接：
- 当新连接完成认证登录调用 `sessionManager.bind(sessionId, deviceId)` 时，框架主动探查该 `deviceId` 是否存在旧的活跃物理会话；
- 若检测到旧会话，系统主动触发“踢旧留新”置换机制：向旧通道发送关闭断开信号（附带原因 `CloseReason.KICK_OUT`）并从注册表中剔除，将设备映射平滑置换至新通道；
- 彻底杜绝僵尸会话占用硬件设备标识导致的指令下发错位与指令丢失。

### 4.8 三阶段受管优雅停机排空时序
在云原生 K8s 容器缩容或滚动更新发布时，暴力杀掉应用进程会导致在途传输报文中断丢包。框架依托 Spring `SmartLifecycle` 契约提供三阶段排空时序：
1. **阶段 1（解绑端口监听）**：优先关闭 ServerSocket Channel，解除端口监听，拒绝任何外部新长连接接入，同时告知客户端暂停自愈重连调度；
2. **阶段 2（受管排空在途窗口）**：启动 `drain-timeout`（生产默认 5s）受管等待窗口期，持续检测 `correlationManager.hasPendingRequests()`，排空在途传输与等待应答的 RPC Future；
3. **阶段 3（平滑注销存量会话与线程释放）**：向存量在线客户端广播 `CloseReason.SERVER_SHUTDOWN`，注销会话并发布事件，优雅关闭 Netty EventLoop 线程池，实现无损平滑升级。

---

## 5. 云原生运维与可观测性闭环 (Observability & Actuator)

### 5.1 生产运维管控端点 (/actuator/tcp)

向外部运维监控暴露 `/actuator/tcp` HTTP 管控端点：

```bash
# 查询全局 TCP 服务端与客户端运行状态、活跃会话与度量指标概览
curl -X GET "http://localhost:8080/actuator/tcp"
```

返回结构示例：

```json
{
  "status": "UP",
  "activeSessions": 1,
  "servers": {
    "default": {
      "name": "default",
      "running": true,
      "host": "0.0.0.0",
      "port": 9099,
      "actualPort": 9099,
      "framingMode": "DELIMITER",
      "sslEnabled": false
    }
  },
  "metrics": {
    "connectionsTotal": 120,
    "messagesReceived": 45000,
    "messagesSent": 45000,
    "bytesReceived": 1024000,
    "bytesSent": 512000
  }
}
```

单设备长连接穿透诊断查询：

```bash
# 查询指定硬件设备当前的物理长连接详情
curl -X GET "http://localhost:8080/actuator/tcp/VEHICLE-001"
```

```json
{
  "deviceId": "VEHICLE-001",
  "sessionId": "42a8f233a5b0f7da3f204594fd0045e6",
  "remoteAddress": "/127.0.0.1:58412",
  "active": true,
  "connectedAt": "2026-11-02T10:02:50.123",
  "lastActiveTime": "2026-11-02T10:04:33.074"
}
```

### 5.2 Micrometer 网络黄金度量指标

自动注册以下标准 Prometheus 度量指标：

| 指标名称 | 类型 | 含义说明 |
| :--- | :---: | :--- |
| `ateng.tcp.connections.active` | Gauge | 当前存活在线的 TCP 活跃长连接总数 |
| `ateng.tcp.connections.total` | Counter | 累计接入建立的长连接总数 |
| `ateng.tcp.messages.received` | Counter | 累计接收到的网络数据包总数 |
| `ateng.tcp.messages.sent` | Counter | 累计发送的网络数据包总数 |
| `ateng.tcp.bytes.received` | Counter | 累计接收到的网络字节总量（单位：bytes） |
| `ateng.tcp.bytes.sent` | Counter | 累计发送的网络字节总量（单位：bytes） |
| `ateng.tcp.request.duration` | Timer | 全双工同步 RPC 从发送到对端应答的往返耗时分布 |
