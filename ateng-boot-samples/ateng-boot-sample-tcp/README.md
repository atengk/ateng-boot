# ateng-boot-sample-tcp 工业物联网与车联网双模长连接体验工程

> 专注于演示与自验 `ateng-boot-tcp` 核心特性在工业物联网与车联网场景下的双模协同，集成 `ateng-boot-web` 对外暴露 5 大高内聚 HTTP 控制域（服务端、客户端、会话、协议、治理）共 25 个交互端点，同时保留无 Web 容器纯净端到端验证能力。

---

## 1. 快速启动与服务端口

- **核心定位**：按真实车联网业务域拆分 [`TcpServerController`](./src/main/java/io/github/atengk/boot/sample/tcp/controller/TcpServerController.java)、[`TcpClientController`](./src/main/java/io/github/atengk/boot/sample/tcp/controller/TcpClientController.java)、[`TcpSessionController`](./src/main/java/io/github/atengk/boot/sample/tcp/controller/TcpSessionController.java)、[`TcpProtocolController`](./src/main/java/io/github/atengk/boot/sample/tcp/controller/TcpProtocolController.java) 与 [`TcpGovernanceController`](./src/main/java/io/github/atengk/boot/sample/tcp/controller/TcpGovernanceController.java) 5 大控制器，严格执行 Request DTO JSR-303 入参校验与强类型 `Result<T>` + VO 响应（零底层实体泄露）。
- **多实例监听与客户端拓扑**（定义于 [`application.yml`](./src/main/resources/application.yml)）：

| 实例 / 端点 | 角色与配置路径 | 默认监听 / 目标地址 | 核心开启特性 |
| :--- | :--- | :--- | :--- |
| **Web HTTP 控制面** | `server.port` | `8080` | 承载 `/sample/tcp/**` 与 `/actuator/*` 端点 |
| **主 TCP 服务端 (`default`)** | `ateng.boot.tcp.server` | `0.0.0.0:9099` | `LENGTH_FIELD`(4B)、高低水位 `32KB/64KB`、`FAIL_FAST`、`idle.reader-idle-time: 60s`、IP 黑白名单、全局/通道流量整形 |
| **二进制多端口 (`binary-jt808`)** | `ateng.boot.tcp.servers.binary-jt808` | `127.0.0.1:9100` | `LENGTH_FIELD`(4B)、`reader-idle-time: 45s`、独立接入验证 |
| **安全多端口 (`tls-gateway`)** | `ateng.boot.tcp.servers.tls-gateway` | `127.0.0.1:9101` | `LENGTH_FIELD`(4B)、mTLS 双向强认证（`ssl.client-auth: NEED`、`PKCS12`） |
| **默认客户端 (`default`)** | `ateng.boot.tcp.client` | `127.0.0.1:9099` | `20s` 心跳（`{"command":"HEARTBEAT"}`）、指数退避重连（`500ms` ~ `10s`，最多 `5` 次） |
| **命名客户端 (`binary-client`)** | `ateng.boot.tcp.clients.binary-client` | `127.0.0.1:9100` | `LENGTH_FIELD`(4B)、独立多客户端调度与断线重连 |
| **安全客户端 (`tls-client`)** | `ateng.boot.tcp.clients.tls-client` | `127.0.0.1:9101` | mTLS 客户端双向证书（[`certs/`](./src/main/resources/certs)）、`LENGTH_FIELD`(4B) |

- **一键启动方式**：
  ```bash
  # 方式一：在 IDE 中运行 SampleTcpApplication.java 的 main 方法
  # 方式二：Maven 命令行启动（默认离线双模自洽，自动拉起 VEHICLE-001 / VEHICLE-002 仿真车机）
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-tcp
  ```

---

## 2. 运行环境与双模切换

- **默认 / CI 离线模式（`@Profile("!local")`）**：
  - 应用就绪后，[`FleetSimulationRunner`](./src/main/java/io/github/atengk/boot/sample/tcp/simulator/FleetSimulationRunner.java) 自动拉起 2 台嵌入式 [`VehicleSimulator`](./src/main/java/io/github/atengk/boot/sample/tcp/simulator/VehicleSimulator.java) 连入主端口 `9099`：`VEHICLE-001`（所属车队 `FLEET-ALPHA`，JSON 协议）与 `VEHICLE-002`（所属车队 `FLEET-BETA`，JT/T 808 二进制协议）；
  - 集群桥接默认使用纯内存 `LocalTcpClusterBridge`（`ateng.boot.redis.enabled: false`、`ateng.boot.tcp.cluster.redis.enabled: false`），并内建自签名开发测试 `.p12` 证书夹具，无需外部物理设备或 Redis 即可启动并跑通全链路。
- **Local 真实联调模式（`local` Profile）**：
  - [`application.yml`](./src/main/resources/application.yml) 已配置 `spring.config.import: "optional:classpath:application-local.yml"`（已被 `.gitignore` 忽略）；
  - 复制 [`application-local.example.yml`](./src/main/resources/application-local.example.yml) 为 `application-local.yml`，填入真实 Redis、外部 TCP 服务端地址与生产 `.p12` 证书路径；激活 `local` 后将自动关闭离线仿真车机（`sample.tcp.simulator.enabled: false`）并切换为 `RedisPubSubTcpClusterBridge`：
  ```bash
  # 激活 local Profile 连接真实环境与 Redis 集群桥接
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-tcp -Dspring-boot.run.profiles=local
  ```

---

## 3. 核心 Controller 接口与一键体验

所有 HTTP 接口统一返回 `Result<T>` 强类型包装；注：受 `ateng-boot-web` 统一序列化策略约束，VO 与报文中的 `Long`/`long` 字段在 HTTP JSON 中序列化为字符串（如 `"seq":"2048"`）。

| 功能域与场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令（全部经 `SampleTcpWebControllerTest` 实测） |
| :--- | :--- | :--- | :--- |
| **查看多端口服务端拓扑** | `TcpServerController` | `GET /sample/tcp/server/list` | `curl -X GET "http://localhost:8080/sample/tcp/server/list"` |
| **查看指定服务端详情** | `TcpServerController` | `GET /sample/tcp/server/detail` | `curl -X GET "http://localhost:8080/sample/tcp/server/detail?serverName=tls-gateway"` |
| **服务端全网预警广播** | `TcpServerController` | `POST /sample/tcp/server/broadcast` | `curl -X POST "http://localhost:8080/sample/tcp/server/broadcast" -H "Content-Type: application/json" -d '{"message":"WEATHER_ALERT_TYPHOON","cluster":false}'` |
| **车队分组定向组播** | `TcpServerController` | `POST /sample/tcp/server/group-send` | `curl -X POST "http://localhost:8080/sample/tcp/server/group-send" -H "Content-Type: application/json" -d '{"group":"FLEET-ALPHA","message":"FLEET_DISPATCH_ORDER_001","cluster":false}'` |
| **查看受管客户端实例** | `TcpClientController` | `GET /sample/tcp/client/list` | `curl -X GET "http://localhost:8080/sample/tcp/client/list"` |
| **客户端推流 (mTLS JSON / HEX)** | `TcpClientController` | `POST /sample/tcp/client/send` | `curl -X POST "http://localhost:8080/sample/tcp/client/send" -H "Content-Type: application/json" -d '{"clientName":"tls-client","payloadType":"JSON","command":"HEARTBEAT","content":"TLS_SECURE_HEARTBEAT"}'` |
| **客户端同步 RPC 交互** | `TcpClientController` | `POST /sample/tcp/client/request` | `curl -X POST "http://localhost:8080/sample/tcp/client/request" -H "Content-Type: application/json" -d '{"clientName":"default","command":"HEARTBEAT","timeoutMillis":3000,"asyncMode":false}'` |
| **客户端异步非阻塞 RPC** | `TcpClientController` | `POST /sample/tcp/client/request-async` | `curl -X POST "http://localhost:8080/sample/tcp/client/request-async" -H "Content-Type: application/json" -d '{"clientName":"tls-client","command":"HEARTBEAT","timeoutMillis":3000,"asyncMode":true}'` |
| **手动断开命名客户端** | `TcpClientController` | `POST /sample/tcp/client/disconnect` | `curl -X POST "http://localhost:8080/sample/tcp/client/disconnect?clientName=binary-client"` |
| **手动重连命名客户端** | `TcpClientController` | `POST /sample/tcp/client/reconnect` | `curl -X POST "http://localhost:8080/sample/tcp/client/reconnect?clientName=binary-client"` |
| **按车队筛选在线会话** | `TcpSessionController` | `GET /sample/tcp/session/list` | `curl -X GET "http://localhost:8080/sample/tcp/session/list?group=FLEET-ALPHA"` |
| **单设备会话与遥测穿透详情** | `TcpSessionController` | `GET /sample/tcp/session/detail` | `curl -X GET "http://localhost:8080/sample/tcp/session/detail?deviceId=VEHICLE-001"` |
| **会话改绑 / 注册设备标识** | `TcpSessionController` | `POST /sample/tcp/session/bind` | `curl -X POST "http://localhost:8080/sample/tcp/session/bind" -H "Content-Type: application/json" -d '{"sessionId":"YOUR_ACTIVE_SESSION_ID","deviceId":"VEHICLE-TMP-KICK-TARGET"}'` |
| **动态加入 / 移出车队分组** | `TcpSessionController` | `POST /sample/tcp/session/group` | `curl -X POST "http://localhost:8080/sample/tcp/session/group" -H "Content-Type: application/json" -d '{"deviceId":"VEHICLE-001","group":"FLEET-TEMP-AUDIT","action":"JOIN"}'` |
| **管理员合规踢线下线** | `TcpSessionController` | `POST /sample/tcp/session/kick` | `curl -X POST "http://localhost:8080/sample/tcp/session/kick" -H "Content-Type: application/json" -d '{"deviceId":"VEHICLE-TMP-KICK-TARGET","reason":"ADMIN_AUDIT_KICK"}'` |
| **远程单车控制 RPC 指令** | `TcpProtocolController` | `POST /sample/tcp/protocol/control` | `curl -X POST "http://localhost:8080/sample/tcp/protocol/control" -H "Content-Type: application/json" -d '{"deviceId":"VEHICLE-001","command":"LOCK","params":{"securityMode":"HIGH"}}'` |
| **全双工车机参数查询 RPC** | `TcpProtocolController` | `POST /sample/tcp/protocol/param-query` | `curl -X POST "http://localhost:8080/sample/tcp/protocol/param-query" -H "Content-Type: application/json" -d '{"deviceId":"VEHICLE-002","paramType":"BATTERY"}'` |
| **JT/T 808 复合帧编码与校验计算** | `TcpProtocolController` | `POST /sample/tcp/protocol/jt808/encode` | `curl -X POST "http://localhost:8080/sample/tcp/protocol/jt808/encode" -H "Content-Type: application/json" -d '{"deviceId":"888888","seq":2048,"longitude":116.407412,"latitude":39.904231,"speed":92.5,"engineStatus":"ON"}'` |
| **JT/T 808 复合帧反转义与解码** | `TcpProtocolController` | `POST /sample/tcp/protocol/jt808/decode` | `curl -X POST "http://localhost:8080/sample/tcp/protocol/jt808/decode" -H "Content-Type: application/json" -d '{"hexFrame":"7E0200...7E"}'` |
| **触发车机上报（JSON / JT808）** | `TcpProtocolController` | `POST /sample/tcp/protocol/report` | `curl -X POST "http://localhost:8080/sample/tcp/protocol/report" -H "Content-Type: application/json" -d '{"deviceId":"VEHICLE-002","protocol":"JT808","longitude":121.48,"latitude":31.24,"speed":84.5,"engineStatus":"ON"}'` |
| **治理与 SPI 全景观测** | `TcpGovernanceController` | `GET /sample/tcp/governance/overview` | `curl -X GET "http://localhost:8080/sample/tcp/governance/overview"` |
| **动态调整会话背压策略** | `TcpGovernanceController` | `POST /sample/tcp/governance/backpressure` | `curl -X POST "http://localhost:8080/sample/tcp/governance/backpressure" -H "Content-Type: application/json" -d '{"deviceId":"VEHICLE-001","policy":"BLOCK_TIMEOUT","timeoutMillis":750}'` |
| **集群桥接定向 / 分组 / 广播** | `TcpGovernanceController` | `POST /sample/tcp/governance/cluster-broadcast` | `curl -X POST "http://localhost:8080/sample/tcp/governance/cluster-broadcast" -H "Content-Type: application/json" -d '{"mode":"GROUP","target":"FLEET-ALPHA","message":"CLUSTER_GROUP_NOTICE"}'` |
| **动态调优全局流量整形限速** | `TcpGovernanceController` | `POST /sample/tcp/governance/traffic-shaping` | `curl -X POST "http://localhost:8080/sample/tcp/governance/traffic-shaping" -H "Content-Type: application/json" -d '{"serverName":"default","writeLimitBytesPerSec":5242880,"readLimitBytesPerSec":5242880}'` |
| **SPI 审计 / 报文拦截探针** | `TcpGovernanceController` | `POST /sample/tcp/governance/spi-probe` | `curl -X POST "http://localhost:8080/sample/tcp/governance/spi-probe" -H "Content-Type: application/json" -d '{"mode":"AUDIT_RPC","note":"SAMPLE_AUDIT_VERIFY"}'` |

预期 `Result<T>` 典型响应示例（提取自 `SampleTcpWebControllerTest` 实测接口）：

- **查看 `tls-gateway` mTLS 服务端详情**（`GET /sample/tcp/server/detail?serverName=tls-gateway`）：
  ```json
  {
    "code": 200,
    "msg": "操作成功",
    "data": {
      "name": "tls-gateway",
      "running": true,
      "host": "127.0.0.1",
      "configuredPort": 9101,
      "actualPort": 9101,
      "framingMode": "LENGTH_FIELD",
      "sslEnabled": true,
      "clientAuth": "NEED",
      "activeConnections": 1
    },
    "success": true
  }
  ```
- **动态调整会话背压策略**（`POST /sample/tcp/governance/backpressure`）：
  ```json
  {
    "code": 200,
    "msg": "操作成功",
    "data": {
      "deviceId": "VEHICLE-001",
      "active": true,
      "writable": true,
      "backpressurePolicy": "BLOCK_TIMEOUT",
      "backpressureTimeoutMs": "750"
    },
    "success": true
  }
  ```
- **Actuator 生产运维端点**：支持 `GET http://localhost:8080/actuator/tcp` 与 `GET http://localhost:8080/actuator/health` 实时观测全量连接与指标快照。

---

## 4. 全真自动化回归命令

样例工程提供**双轨自动化测试守护**：
1. **Web HTTP 体验与 JSR-303 校验轨道**（[`SampleTcpWebControllerTest`](./src/test/java/io/github/atengk/boot/sample/tcp/SampleTcpWebControllerTest.java)）：依托 `MockMvc` 覆盖 5 大 Controller 全部 25 个端点正/负向断言、`FLEET-ALPHA`/`FLEET-BETA` 双仿真车机、mTLS 双向传输、多端绑定踢线及 Actuator 观测。
2. **无 Web 纯后台端到端轨道**（[`SampleTcpE2eTest`](./src/test/java/io/github/atengk/boot/sample/tcp/SampleTcpE2eTest.java) + [`Jt808CompositeFrameCodecTest`](./src/test/java/io/github/atengk/boot/sample/tcp/codec/Jt808CompositeFrameCodecTest.java)）：在 `WebEnvironment.NONE` 与 `test` Profile 下验证核心 `ateng-boot-tcp` 在无 Servlet 容器环境中的纯净装配，完整覆盖 13 步端到端长连接、JT/T 808 转义/BCC 校验防伪、全双工 RPC、踢旧留新与断线重连。

```bash
mvn test -pl ateng-boot-tcp,ateng-boot-samples/ateng-boot-sample-tcp -am -T 1C -nsu -B
```

> 详细原理机制、全量属性配置字典与 SPI 扩展定制，请参阅核心 Starter 文档：[ateng-boot-tcp README](../../ateng-boot-tcp/README.md)。
