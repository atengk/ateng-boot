# ateng-boot-sample-tcp 工业物联网与车联网双模长连接自验工程

> 模拟真实智能车联网/IoT 物联网长连接场景，涵盖车机接入登录认证、交通部 JT/T 808 国标风格复合报文（0x7E 分界、0x7D 反转义与 BCC 异或校验和验证）、高频定位遥测数据上报与自动 ACK、全双工同步 RPC 参数下发查询、车队集群分组组播调度、全网限速预警广播、出站背压流控感知、“踢旧留新”重连置换、客户端自愈重连以及 Actuator 生产管控探针 100% 端到端纯净自验沙箱。

---

## 1. 业务场景与架构设计

在车联网与工业物联网长连接架构中，系统面临以下典型痛点：
1. **网络粘包与半包干扰**：TCP 字节流协议无消息边界，若缺乏确定的帧定界策略，高频定位与控制指令极易串包损坏；
2. **连接假死与僵尸连接**：弱网隧道闪断或车载终端非正常掉电时，操作系统 TCP 协议栈无法及时感知，僵尸连接占用内存与设备编号；
3. **指令下发与阻塞雪崩**：服务端向车机下发参数查询或控制指令时，若直接占用事件循环线程同步阻塞等待，将迅速耗尽网络线程池引发雪崩；
4. **多实例跨节点推送壁垒**：微服务横向扩容部署时，车载终端长连接分散在不同 Pod 节点，传统单机应用无法跨实例精准投递；
5. **升级断网与在途丢包**：K8s 容器滚动升级或应用重启时，若直接关闭进程，将导致正在传输的数据帧中断与尚未回执的 RPC 指令丢失。

`ateng-boot-sample-tcp` 遵循框架**非 Web Starter 零 Controller 刚性铁律**，彻底剔除任何 HTTP Controller 与 Web 容器依赖，基于 `ateng-boot-tcp` 核心 Starter 构建了纯内存可运行的完整业务闭环：
- **纯内存离线自洽（100% Hermetic Test）**：零外部网络服务与物理 Redis 依赖，随机空闲端口绑定，本地秒级全绿准出；
- **声明式命令字路由**：车机终端上行报文通过 `@TcpListener` 与 `@TcpMapping` 声明式解析，结合 `@TcpDeviceId` 强类型提取硬件标识；
- **JT/T 808 国标复合分帧**：支持 `0x7E` 起止标识符、`0x7D` 转义还原（`0x7D 0x02 <-> 0x7E`、`0x7D 0x01 <-> 0x7D`）以及 BCC XOR 异或全真校验和验证；
- **全双工同步 RPC 闭环**：服务端调用 `tcpServerTemplate.requestToDevice(deviceId, packet, timeout)` 同步等待终端应答，基于时间轮实现确定性超时熔断；
- **车队分组集群组播**：基于 `tcpServerTemplate.sendGroupToCluster(group, packet)` 实现跨节点车队组播，严格保障车队间隔离；
- **出站背压流控感知**：支持出站高低水位配置与 `session.isWritable()` 状态感知，保障高并发写入不 OOM；
- **“踢旧留新”确定性置换**：当设备重连上线并发送登录认证时，框架自动切断旧物理会话，确保当前通道始终保持最新状态；
- **Spring 领域事件驱动**：长连接建立、设备绑定、连接断开、可写性变化与 IP 防火墙拦截自动发布领域事件，驱动审计闭环；
- **生产运维管控端点**：深度打通 Spring Boot Actuator `/actuator/tcp` 与 Micrometer 7 项网络黄金度量指标。

---

## 2. 端到端自验场景全景表

| 序号 | 自验场景 | 覆盖核心机制 / API | 验证动作 / 触发方式 | 预期结果 / 校验点 |
| :--- | :--- | :--- | :--- | :--- |
| **01** | 车机长连接建立与登录认证 | `TcpClient`<br>`@TcpMapping("LOGIN")`<br>`TcpSessionManager.bind` | 仿真终端连接并发送 `LOGIN` 数据帧 | 连接建立，触发 `TcpDeviceBoundEvent`，设备标识与长连接双向映射成功 |
| **02** | JT/T 808 国标复合分帧遥测 | `Jt808CompositeFrameCodec`<br>`@TcpMapping("JT808_LOCATION")`<br>`ChecksumUtil` | 仿真终端发送含 `0x7E`/`0x7D`/`BCC` 复合二进制帧 | 自动反转义、校验 BCC 异或码并更新实时定位，服务端自动回写 `JT808_LOCATION_ACK`；篡改 BCC 自动拦截 |
| **03** | 标准高频定位上报与自动 ACK | `@TcpMapping("LOCATION_REPORT")`<br>`@TcpDeviceId`<br>`AsyncContext` 异步隔离 | 仿真终端发送 JSON 格式经纬度、车速、发动机状态与流水号 | 异步受管线程池接管执行，更新车辆实时定位与在线状态，服务端自动回写 `LOCATION_ACK` 回执 |
| **04** | 终端长连接心跳保活 | `@TcpMapping("HEARTBEAT")` | 仿真终端发送 `HEARTBEAT` 心跳帧 | 服务端更新会话活跃时间戳，自动应答 `HEARTBEAT_ACK` |
| **05** | 远程单车控制指令下发 | `VehicleTelemetryScenarioService.sendControlCommand`<br>`TcpServerTemplate.requestToDevice` | 服务端向车机下发 `LOCK` / `UNLOCK` 指令 | 车机仿真终端拦截并执行，服务端同步收到执行回执 `VehicleControlAck` |
| **06** | 全双工同步 RPC 参数查询 | `VehicleTelemetryScenarioService.queryDeviceParams`<br>`TcpRequestCorrelationManager` | 服务端向车机下发 `PARAM_QUERY` | 车机仿真终端自动应答，服务端 3 秒内同步返回 `VehicleParamQueryAck`（含固件版本、电量等） |
| **07** | 车队分组跨节点集群组播 | `VehicleTelemetryScenarioService.sendFleetGroupAlert`<br>`TcpServerTemplate.sendGroupToCluster` | 将车机加入 `FLEET-ALPHA` 车队并发送组播预警 | 分组内车机成功收到调度预警，非该分组车机严格隔离收不到 |
| **08** | 全网广播限速预警通知 | `VehicleTelemetryScenarioService.broadcastSpeedLimitAlert`<br>`TcpServerTemplate.broadcast` | 服务端下发全网广播 `ALERT_BROADCAST` | 全网所有在线车机仿真终端均成功接收到预警内容 |
| **09** | 出站背压与通道健康感知 | `VehicleTelemetryScenarioService.isOutboundWritable`<br>`session.getPendingWriteBytes` | 探测出站通道可写状态与积压字节 | 返回通道健康可写标志与当前缓冲待发送字节数 |
| **10** | 网络重连与“踢旧留新”置换 | `TcpSessionManager.bind`<br>`CloseReason.KICK_OUT` | 启动新模拟器以相同 `deviceId` 登录 | 旧长连接被切断注销，新通道接管设备标识，防止僵尸连接 |
| **11** | 客户端后台自愈重连 | `TcpClientProperties.getReconnect` | 人为切断客户端底层连接 | 客户端指数退避重试，自动重连恢复通道 |
| **12** | Actuator 生产管控诊断 | `TcpActuatorEndpoint`<br>`/actuator/tcp` | 调用端点获取指标与会话详情 | 输出整体运行概览、活跃长连接数及单设备长连接通道状态诊断详情 |

---

## 3. 本地运行与自动化自验

### 执行端到端全场景实测套件

根据框架测试准则，直接执行端到端全场景集成测试：

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-tcp -am -T 1C -nsu -B
```

测试套件将全自动：
1. 动态分配空闲 TCP 端口启动服务端；
2. 启动多个嵌入式 `VehicleSimulator` 仿真终端并建立网络通道；
3. 闭环验证接入登录、JT/T 808 国标分帧、JSON 遥测、心跳、全双工 RPC、车队分组组播、广播、背压与自愈重连；
4. 100% 离线自洽通过断言，无外部网络强依赖，无环境悬挂。
