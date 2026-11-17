# ateng-boot-sample-notification 企业协同机器人通知体验工程

> 专注于演示与自验 `ateng-boot-notification` 多端 Webhook 机器人通知通信（钉钉、企业微信、飞书与通用 Webhook）、跨端卡片自适应转译、多渠道并发广播与单点故障隔离、客户端滑动窗口频控与分布式 Redis Lua 双窗口治理、主备渠道顺延故障转移（Cascade Failover）、事务感知防幽灵发信（`sendAfterCommit`）、动态切源上下文以及声明式 `@NotifyAlert` 异常告警拦截（含 `detailUrl` 链路跳转）与三阶防风暴聚合抑制引擎。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `application.yml` 中修改）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleNotificationApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-notification
  ```

---

## 2. 运行环境与双模切换

- **默认 / CI 离线自洽模式（`@Profile("!local")`）**：
  - 应用启动后自动装配内置离线仿真 Webhook 网关 `NotificationMockGatewayController`，`application.yml` 中的默认演示渠道自动指向本机 `http://localhost:${server.port:8080}/sample/notification/mock-gateway/{wecom,dingtalk,feishu,generic}`，无需任何外部网络或真实机器人凭据即可直接发起 HTTP 请求并验证各平台加签、通用防重放头与录制报文载荷；
  - 端到端集成测试 `SampleNotificationIntegrationTest` 采用 `RANDOM_PORT` + `MockMvc` + 内嵌 OkHttp `MockWebServer` 双重校验，执行 `mvn test` 即可 100% 离线自洽通过。
- **Local 真实联调模式**：若需直连真实群机器人验证，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实机器人的 Webhook 与加签 Secret（该文件已被 `.gitignore` 忽略，CI 零外泄风险），激活 `local` Profile 启动即可：
  ```bash
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-notification -Dspring-boot.run.profiles=local
  ```

---

## 3. 核心 Controller 接口与一键体验

样例工程已按领域完整解耦为 5 大独立 `@RestController`，所有业务接口均遵循 `/sample/notification/<domain>/<action>` 契约、使用专用 DTO 入参并返回强类型 `Result<T>`（其中 `NotificationMockGatewayController` 的 4 个 Webhook 接收端遵循平台官方协议格式返回，审计查询与清空接口返回 `Result<T>`）：

| 领域分类 | Controller 类 | 请求方式与路径 | 核心业务场景与快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/text` | **纯文本通知发送**（支持 `@所有人` 与手机号精准 `@人`）：<br>`curl -X POST "http://localhost:8080/sample/notification/message/text" -H "Content-Type: application/json" -d '{"channel":"ops-alert","content":"【发布通报】ateng-boot v1.2.0 发布成功","atAll":false,"atMobiles":["13800138000"]}'` |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/markdown` | **富文本 Markdown 发信**（自动执行企微表格向引用块优雅降级清洗）：<br>`curl -X POST "http://localhost:8080/sample/notification/message/markdown" -H "Content-Type: application/json" -d '{"channel":"ops-alert","title":"系统巡检汇总","content":"### 巡检摘要\n\| 服务名 \| 状态 \| 延迟 \|\n\| :--- \| :--- \| :--- \|\n\| Gateway \| 正常 \| 12ms \|"}'` |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/card` | **跨端自适应交互卡片**（支持 10 色主题与横纵多按钮排版）：<br>`curl -X POST "http://localhost:8080/sample/notification/message/card" -H "Content-Type: application/json" -d '{"channel":"ops-alert","title":"集群高负载预警","content":"节点 worker-01 CPU 使用率超过 **92%**","headerColor":"RED","btnOrientation":"HORIZONTAL","buttons":[{"title":"查看监控","url":"https://monitor.example.com","type":"PRIMARY"},{"title":"立即重启","url":"https://ops.example.com/restart","type":"DANGER"}]}'` |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/link` | **图文链接消息**（自适应转译钉钉 Link、企微 News 单图文与飞书图文卡片）：<br>`curl -X POST "http://localhost:8080/sample/notification/message/link" -H "Content-Type: application/json" -d '{"channel":"dingtalk-dev","title":"项目里程碑通报","text":"v1.2.0 已完成端到端测试与五维文档闭环","messageUrl":"https://github.com/atengk/ateng-boot","picUrl":"https://img.example.com/banner.png"}'` |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/image` | **跨端图片消息**（支持 Base64、本地路径防卫或公网 `imageUrl` 跨端转发）：<br>`curl -X POST "http://localhost:8080/sample/notification/message/image" -H "Content-Type: application/json" -d '{"channel":"dingtalk-dev","imageUrl":"https://img.example.com/alert-banner.png"}'` |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/raw` | **原生特化报文透传**（直接传入官方原生 JSON 载荷逃生舱）：<br>`curl -X POST "http://localhost:8080/sample/notification/message/raw" -H "Content-Type: application/json" -d '{"channel":"dingtalk-dev","payload":{"msgtype":"text","text":{"content":"原生报文透传测试"}}}'` |
| **渠道治理** | `NotificationChannelController` | `POST /sample/notification/channel/switch` | **线程级临时切源**（`callWithChannel` 动态切源并在 `finally` 严格还原现场）：<br>`curl -X POST "http://localhost:8080/sample/notification/channel/switch" -H "Content-Type: application/json" -d '{"targetChannel":"dingtalk-dev","content":"动态切源发信测试"}'` |
| **渠道治理** | `NotificationChannelController` | `POST /sample/notification/channel/template` | **动态占位符模板渲染发信**（变量插值解耦业务消息组装）：<br>`curl -X POST "http://localhost:8080/sample/notification/channel/template" -H "Content-Type: application/json" -d '{"channel":"dingtalk-dev","templateContent":"服务 ${appName} 瞬时 QPS 达到 ${qps}","variables":{"appName":"ateng-pay","qps":12500}}'` |
| **渠道治理** | `NotificationChannelController` | `POST /sample/notification/channel/failover` | **顺延故障转移 Failover**（主渠道异常自动无缝切换备用渠道重试）：<br>`curl -X POST "http://localhost:8080/sample/notification/channel/failover" -H "Content-Type: application/json" -d '{"primaryChannel":"sample-failover-primary","content":"主渠道模拟故障触发级联重试"}'` |
| **渠道治理** | `NotificationChannelController` | `POST /sample/notification/channel/broadcast` | **多渠道并发广播与单点隔离**（`sendBroadcast`/`sendBroadcastAsync` 向多平台并发推送并保序返回各渠道结果）：<br>`curl -X POST "http://localhost:8080/sample/notification/channel/broadcast" -H "Content-Type: application/json" -d '{"channels":["ops-alert","dingtalk-dev","feishu-ops","generic-monitor"],"content":"【全网广播】核心支付网关已完成灰度切流","async":false}'` |
| **可靠事务** | `NotificationTransactionController` | `POST /sample/notification/transaction/send-after-commit` | **本地事务感知发信**（严格在数据库事务物理提交后外呼，防止幽灵通知）：<br>`curl -X POST "http://localhost:8080/sample/notification/transaction/send-after-commit" -H "Content-Type: application/json" -d '{"channel":"ops-alert","content":"【事务通知】订单 ORD-9988 结算完成，已成功入库"}'` |
| **可靠事务** | `NotificationTransactionController` | `POST /sample/notification/transaction/send-async` | **受管异步非阻塞发信**（托管于 Core 受管线程池并无损透传四维上下文快照）：<br>`curl -X POST "http://localhost:8080/sample/notification/transaction/send-async" -H "Content-Type: application/json" -d '{"channel":"ops-alert","content":"【异步通知】批量对账任务已后台派发"}'` |
| **异常告警** | `NotificationAlertController` | `POST /sample/notification/alert/trigger` | **声明式 `@NotifyAlert` 拦截与 `detailUrl` 跳转**（自动提取堆栈、敏感密码脱敏为 `******` 并插值 `${traceId}`）：<br>`curl -X POST "http://localhost:8080/sample/notification/alert/trigger" -H "Content-Type: application/json" -d '{"orderId":"ORD-ALERT-9999","password":"SUPER_SECRET_PASSWORD_123","errorType":"npe"}'` |
| **异常告警** | `NotificationAlertController` | `POST /sample/notification/alert/storm-suppress` | **告警风暴三阶抑制抗洪**（并发 10 次：首发推送 1 次红色卡片、静默拦截 9 次、2 秒后补发 1 次橙色聚合卡片）：<br>`curl -X POST "http://localhost:8080/sample/notification/alert/storm-suppress" -H "Content-Type: application/json" -d '{"orderId":"ORD-STORM-1001","password":"STORM_SECRET_PWD","count":10}'` |
| **离线仿真网关** | `NotificationMockGatewayController` | `GET /sample/notification/mock-gateway/records` | **审计查询仿真网关录制报文**（`@Profile("!local")` 生效，支持 `?platform=DINGTALK` 过滤）：<br>`curl "http://localhost:8080/sample/notification/mock-gateway/records"` |
| **离线仿真网关** | `NotificationMockGatewayController` | `DELETE /sample/notification/mock-gateway/records` | **清空仿真网关已录制报文**：<br>`curl -X DELETE "http://localhost:8080/sample/notification/mock-gateway/records"` |

### 实测响应示例（基于本地仿真网关与 `MockMvc` 端到端实测）

- **多渠道并发广播单点故障隔离（`POST /sample/notification/channel/broadcast`）**：
  ```json
  {
    "code": 200,
    "msg": "操作成功",
    "data": {
      "ops-alert": {
        "messageId": "4f8c2a1b9e3d4c7f8a12b34c56d78e90",
        "channel": "ops-alert",
        "platform": "WECOM",
        "success": true,
        "costDuration": "PT0.015S",
        "rawResponse": "{\"errcode\":0,\"errmsg\":\"ok\"}",
        "errorMessage": null
      },
      "feishu-ops": {
        "messageId": "7a9b1c2d3e4f5a6b7c8d9e0f1a2b3c4d",
        "channel": "feishu-ops",
        "platform": "FEISHU",
        "success": false,
        "costDuration": "PT0.012S",
        "rawResponse": "{\"code\":500,\"msg\":\"Feishu Gateway Error\"}",
        "errorMessage": "协同平台 HTTP 请求异常 [status=500, target=http://127.0.0.1:8080/webhook/feishu]: 500 Internal Server Error: \"{\"code\":500,\"msg\":\"Feishu Gateway Error\"}\""
      }
    },
    "success": true
  }
  ```

- **仿真网关录制报文审计查询（`GET /sample/notification/mock-gateway/records?platform=DINGTALK`）**：
  ```json
  {
    "code": 200,
    "msg": "操作成功",
    "data": [
      {
        "id": "9f8e7d6c5b4a3210fedcba9876543210",
        "platform": "DINGTALK",
        "path": "/sample/notification/mock-gateway/dingtalk",
        "queryString": "timestamp=1794909840123&sign=vQ2xYz9kM0pL1nR4sT7uW8aB3cD5eF6gH7iJ8kL9mN0%3D",
        "headers": {
          "content-type": "application/json",
          "host": "localhost:8080"
        },
        "payload": {
          "msgtype": "actionCard",
          "actionCard": {
            "title": "钉钉网关卡片",
            "text": "### 钉钉网关卡片\n\n验证非空 singleURL 与无二次编码加签",
            "singleTitle": "查看详情",
            "singleURL": "https://open.dingtalk.com"
          }
        },
        "signatureVerified": true,
        "detailMessage": "钉钉报文与签名校验通过",
        "receivedAt": "2026-11-17T21:40:12.345"
      }
    ],
    "success": true
  }
  ```

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-notification,ateng-boot-samples/ateng-boot-sample-notification -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-notification README](../../ateng-boot-notification/README.md)。
