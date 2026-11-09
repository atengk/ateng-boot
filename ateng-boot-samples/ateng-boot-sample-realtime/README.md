# ateng-boot-sample-realtime 实时通信全双工与流式双模体验工程

> 专注于演示与自验 `ateng-boot-realtime` 核心特性在真实业务场景下的开箱使用，提供全双工 WebSocket、声明式双向路由、半双工 SSE 打字机流、分布式群组协同与离线消息重放能力。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **默认 WebSocket 端点**：`ws://localhost:8080/ws/realtime`
- **默认 SSE 端点**：`http://localhost:8080/sse/realtime`
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleRealtimeApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-realtime
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式 (`!local`)**：内置离线内存桩与自洽配置，无需启动外部 Redis 即可直接运行并跑通全部端到端场景测试；
- **Local 真实模式 (`local`)**：若需连接真实 Redis 中间件测试集群广播、分布式群组 Set 索引与 Redisson ZSet 离线消息持久化，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实连接配置（该文件已被 `.gitignore` 忽略，安全防泄漏），激活 `local` Profile 启动即可：
  ```bash
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-realtime -Dspring-boot.run.profiles=local
  ```

---

## 3. 核心 Controller 接口与一键体验

### 3.1 HTTP 控制面接口体验矩阵

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **模拟登录签发凭据** | `RealtimeAuthController` | `POST /sample/realtime/auth/login` | `curl -X POST "http://localhost:8080/sample/realtime/auth/login?userId=user1001"` |
| **用户单播消息** | `RealtimePushController` | `POST /sample/realtime/push/user` | `curl -X POST "http://localhost:8080/sample/realtime/push/user" -H "Content-Type: application/json" -d '{"userId":"user1001","event":"notice:order","content":"订单已支付成功"}'` |
| **定向 WS 专线单播** | `RealtimePushController` | `POST /sample/realtime/push/websocket` | `curl -X POST "http://localhost:8080/sample/realtime/push/websocket?userId=user1001&clientId=tab-01&message=专线指令"` |
| **定向 SSE 专线单播** | `RealtimePushController` | `POST /sample/realtime/push/sse` | `curl -X POST "http://localhost:8080/sample/realtime/push/sse?userId=user1001&clientId=tab-01&message=专线通知"` |
| **租户全域组播** | `RealtimePushController` | `POST /sample/realtime/push/tenant` | `curl -X POST "http://localhost:8080/sample/realtime/push/tenant?tenantId=1001&message=租户系统升级公告"` |
| **全服跨节点广播** | `RealtimePushController` | `POST /sample/realtime/push/broadcast` | `curl -X POST "http://localhost:8080/sample/realtime/push/broadcast" -H "Content-Type: application/json" -d '{"event":"system:announcement","content":"全服大促开启"}'` |
| **加入协同房间** | `RealtimeGroupController` | `POST /sample/realtime/group/join` | `curl -X POST "http://localhost:8080/sample/realtime/group/join?groupId=room-meeting-101&userId=user1001"` |
| **群组房间组播** | `RealtimeGroupController` | `POST /sample/realtime/group/send` | `curl -X POST "http://localhost:8080/sample/realtime/group/send" -H "Content-Type: application/json" -d '{"groupId":"room-meeting-101","event":"meeting:chat","content":"会议开始"}'` |
| **查询群组成员** | `RealtimeGroupController` | `GET /sample/realtime/group/members` | `curl -X GET "http://localhost:8080/sample/realtime/group/members?groupId=room-meeting-101"` |
| **退出协同房间** | `RealtimeGroupController` | `POST /sample/realtime/group/leave` | `curl -X POST "http://localhost:8080/sample/realtime/group/leave?groupId=room-meeting-101&userId=user1001"` |
| **解散房间并广播** | `RealtimeGroupController` | `POST /sample/realtime/group/dismiss` | `curl -X POST "http://localhost:8080/sample/realtime/group/dismiss?groupId=room-meeting-101"` |
| **本地在线连接指标** | `RealtimeSessionController` | `GET /sample/realtime/session/metrics` | `curl -X GET "http://localhost:8080/sample/realtime/session/metrics"` |
| **集群在线连接汇总** | `RealtimeSessionController` | `GET /sample/realtime/session/cluster-metrics` | `curl -X GET "http://localhost:8080/sample/realtime/session/cluster-metrics"` |
| **用户在线状态探测** | `RealtimeSessionController` | `GET /sample/realtime/session/user-online` | `curl -X GET "http://localhost:8080/sample/realtime/session/user-online?userId=user1001"` |
| **主动踢出用户下线** | `RealtimeSessionController` | `POST /sample/realtime/session/kickout-user` | `curl -X POST "http://localhost:8080/sample/realtime/session/kickout-user?userId=user1001"` |
| **按租户批量切断** | `RealtimeSessionController` | `POST /sample/realtime/session/kickout-tenant` | `curl -X POST "http://localhost:8080/sample/realtime/session/kickout-tenant?tenantId=1001"` |
| **发送持久化离线消息** | `RealtimeOfflineController` | `POST /sample/realtime/offline/send-persistent` | `curl -X POST "http://localhost:8080/sample/realtime/offline/send-persistent" -H "Content-Type: application/json" -d '{"userId":"user1001","event":"alert:urgent","content":"系统告警：CPU使用率超限"}'` |
| **查询未读离线消息数** | `RealtimeOfflineController` | `GET /sample/realtime/offline/count` | `curl -X GET "http://localhost:8080/sample/realtime/offline/count?userId=user1001"` |
| **只读窥探离线消息** | `RealtimeOfflineController` | `GET /sample/realtime/offline/messages` | `curl -X GET "http://localhost:8080/sample/realtime/offline/messages?userId=user1001"` |
| **拉取并排空离线消息** | `RealtimeOfflineController` | `POST /sample/realtime/offline/pull` | `curl -X POST "http://localhost:8080/sample/realtime/offline/pull?userId=user1001"` |
| **清空离线暂存箱** | `RealtimeOfflineController` | `DELETE /sample/realtime/offline/messages` | `curl -X DELETE "http://localhost:8080/sample/realtime/offline/messages?userId=user1001"` |
| **LLM 打字机流式推流** | `RealtimeSseStreamController` | `GET /sample/realtime/sse/stream-llm` | `curl -N "http://localhost:8080/sample/realtime/sse/stream-llm?prompt=解释实时通信架构"` |
| **Sa-Token 踢人下线** | `RealtimeAuthController` | `POST /sample/realtime/auth/kickout` | `curl -X POST "http://localhost:8080/sample/realtime/auth/kickout?userId=user1001"` |
| **Sa-Token 账号封禁** | `RealtimeAuthController` | `POST /sample/realtime/auth/disable` | `curl -X POST "http://localhost:8080/sample/realtime/auth/disable?userId=user1001&disableTime=86400"` |

### 3.2 WebSocket 与 SSE 数据面长连接监听与交互

| 通信协议 | 场景描述 | 连接或监听命令 / 客户端体验步骤 |
| :--- | :--- | :--- |
| **WebSocket 全双工连接** | 建立长连接并携带 Token 认证（URL Query） | `wscat -c "ws://localhost:8080/ws/realtime?token=YOUR_TOKEN&clientId=client-01&deviceType=WEB"` |
| **WebSocket 双向上行** | 发送 `@RealtimeAction` 请求并接收回执帧 | 在已建立的 WebSocket 连接中发送：`{"id":"req-001","event":"order:query","data":{"orderId":"ORD-888"}}`<br>服务端自动回送：`{"id":"req-001","event":"order:query:reply","data":{"code":200,"data":{...}}}` |
| **SSE 实时流监听** | 建立 SSE 长连接并持续监听事件流 | `curl -N "http://localhost:8080/sse/realtime?token=YOUR_TOKEN&clientId=sse-client-01"` |
| **SSE 断点续传** | 携带 `Last-Event-ID` 重连只读重放历史离线消息 | `curl -N -H "Last-Event-ID: evt-001" "http://localhost:8080/sse/realtime?token=YOUR_TOKEN&clientId=sse-client-01"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-realtime,ateng-boot-samples/ateng-boot-sample-realtime -am -T 1C -nsu -B
```

> 详细架构原理、生产边界机制与高阶扩展定制，请参阅核心 Starter 文档：[ateng-boot-realtime README](../../ateng-boot-realtime/README.md)。
