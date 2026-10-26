# ateng-boot-sample-tcp 工业物联网与车联网双模长连接自验工程

> 模拟真实智能车联网/IoT 物联网长连接场景，涵盖车机登录认证、高频定位遥测数据上报、全双工同步 RPC 参数下发查询、全网限速预警广播、“踢旧留新”重连置换、三阶段平滑优雅停机排空以及 Actuator 生产管控探针与 Micrometer 黄金度量 100% 端到端自验沙箱。

---

## 1. 业务场景与架构设计

在车联网与工业物联网长连接架构中，系统面临以下典型痛点：
1. **网络粘包与半包干扰**：TCP 字节流协议无消息边界，若缺乏确定的帧定界策略，高频定位与控制指令极易串包损坏；
2. **连接假死与僵尸连接**：弱网隧道闪断或车载终端非正常掉电时，操作系统 TCP 协议栈无法及时感知，僵尸连接占用内存与设备编号；
3. **指令下发与阻塞雪崩**：服务端向车机下发参数查询或控制指令时，若直接占用事件循环线程同步阻塞等待，将迅速耗尽网络线程池引发雪崩；
4. **多实例跨节点推送壁垒**：微服务横向扩容部署时，车载终端长连接分散在不同 Pod 节点，传统单机应用无法跨实例精准投递；
5. **升级断网与在途丢包**：K8s 容器滚动升级或应用重启时，若直接关闭进程，将导致正在传输的数据帧中断与尚未回执的 RPC 指令丢失。

`ateng-boot-sample-tcp` 基于 `ateng-boot-tcp` 核心 Starter 构建了纯内存可运行的完整业务闭环：
- **纯内存离线自洽（100% Hermetic Test）**：零外部网络服务与物理 Redis 依赖，随机空闲端口绑定，本地秒级全绿准出；
- **声明式命令字路由**：车机终端上行报文通过 `@TcpListener` 与 `@TcpMapping` 声明式解析，结合 `@TcpDeviceId` 强类型提取硬件标识；
- **全双工同步 RPC 闭环**：服务端调用 `tcpServerTemplate.requestToDevice(deviceId, packet, timeout)` 同步等待终端应答，基于时间轮实现确定性超时熔断；
- **“踢旧留新”确定性置换**：当设备重连上线并发送登录认证时，框架自动切断旧物理会话，确保当前通道始终保持最新状态；
- **Spring 领域事件驱动**：长连接建立、设备绑定、连接断开（附带精确原因代码）自动发布领域事件，驱动上下线日志审计；
- **生产运维管控端点**：深度打通 Spring Boot Actuator `/actuator/tcp` 与 Micrometer 7 项网络黄金度量指标。

---

## 2. 端到端自验场景全景表

| 序号 | 自验场景 | 覆盖核心机制 / API | 验证动作 / HTTP 接口 | 预期结果 / 校验点 |
| :--- | :--- | :--- | :--- | :--- |
| **01** | 车机长连接建立与登录认证 | `TcpClient`<br>`@TcpMapping("LOGIN")`<br>`TcpSessionManager.bind` | 仿真终端连接并发送 `LOGIN` 数据帧 | 连接建立，触发 `TcpDeviceBoundEvent`，设备标识与长连接双向映射成功 |
| **02** | 高频定位遥测数据帧上报 | `@TcpMapping("LOCATION_REPORT")`<br>`@TcpDeviceId`<br>`AsyncContext` 异步隔离 | 仿真终端发送经纬度、车速与流水号 | 异步受管线程池接管执行，更新车辆实时定位与在线状态，日志透传 MDC `traceId` |
| **03** | 全双工同步 RPC 参数查询 | `TcpServerTemplate.requestToDevice`<br>`TcpRequestCorrelationManager` | `POST /api/tcp/vehicle/query-params?deviceId=...` | 服务端下发 `PARAM_QUERY`，仿真终端拦截并返回回执，服务端 3 秒内同步返回应答 VO |
| **04** | 全网广播限速预警通知 | `TcpServerTemplate.broadcast`<br>`TcpPacket.ofJson` | `POST /api/tcp/vehicle/broadcast-alert?message=...` | 向全网在线终端下发 `ALERT_BROADCAST`，车机仿真终端成功接收预警内容 |
| **05** | 网络重连与“踢旧留新”置换 | `TcpSessionManager.bind`<br>`CloseReason.KICK_OUT` | 启动新模拟器以相同 `deviceId` 登录 | 旧长连接被切断注销，新通道接管设备标识，防止僵尸连接 |
| **06** | Actuator 生产管控诊断 | `TcpActuatorEndpoint`<br>`/actuator/tcp` | `GET /actuator/tcp`<br>`GET /actuator/tcp/{deviceId}` | 输出整体运行概览、活跃长连接数及单设备长连接通道状态诊断详情 |
| **07** | Micrometer 网络黄金度量 | `TcpMetricsCollector`<br>Prometheus Registry | 查询度量注册表 | 活跃连接 Gauge、收发报文 Counter、网络字节量 Counter 及 RPC 往返耗时 Timer 正常递增 |

---

## 3. 本地启动与实测体验

### 1. 启动样例工程

```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-tcp spring-boot:run
```

默认服务端将监听 `0.0.0.0:9099` 端口，Web 服务端口为 `8080`。

### 2. 查询车机当前状态 (HTTP GET)

```bash
curl -X GET "http://localhost:8080/api/tcp/vehicle/status?deviceId=VEHICLE-E2E-8888"
```

### 3. 下发全双工 RPC 参数查询 (HTTP POST)

```bash
curl -X POST "http://localhost:8080/api/tcp/vehicle/query-params?deviceId=VEHICLE-E2E-8888&paramType=SYSTEM"
```

### 4. 广播限速预警 (HTTP POST)

```bash
curl -X POST "http://localhost:8080/api/tcp/vehicle/broadcast-alert?message=前方施工请减速慢行"
```

### 5. 查看 Actuator 监控与诊断 (HTTP GET)

```bash
# 全局概览
curl -X GET "http://localhost:8080/actuator/tcp"

# 单设备穿透
curl -X GET "http://localhost:8080/actuator/tcp/VEHICLE-E2E-8888"
```
