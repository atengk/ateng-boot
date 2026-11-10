# ateng-boot-sample-notification 企业协同机器人通知体验工程

> 专注于演示与自验 `ateng-boot-notification` 多端 Webhook 机器人通知通信（钉钉、企业微信、飞书与通用 Webhook）、跨端卡片自适应转译、客户端滑动窗口频控与飞书突发令牌弹性治理、主备渠道顺延故障转移（Cascade Failover）、事务感知防幽灵发信（`sendAfterCommit`）、动态切源上下文以及声明式 `@NotifyAlert` 异常告警拦截与三阶防风暴聚合抑制引擎。

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

- **默认 / CI 离线自洽模式**：样例工程单测内嵌 OkHttp `MockWebServer` 模拟企业微信、钉钉与飞书 Webhook 官方网关，零外部网络与凭据依赖，执行 `mvn test` 即可秒级 100% 离线自洽回归；
- **Local 真实联调模式**：若需直连真实群机器人验证，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实机器人的 Webhook 与加签 Secret（该文件已被 `.gitignore` 彻底忽略，CI 零外泄风险），激活 `local` Profile 启动即可：
  ```bash
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-notification -Dspring-boot.run.profiles=local
  ```

---

## 3. 核心 Controller 接口与一键体验

样例工程已按领域完整解耦为 4 大独立 `@RestController`，对外暴露 13 条标准业务端点，所有接口均遵循 `/sample/notification/<domain>/<action>` 契约并返回强类型 `Result<T>`：

| 领域分类 | Controller 类 | 请求方式与路径 | 核心业务场景与快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/text` | **纯文本通知发送**（支持 `@所有人` 与手机号精准 `@人`）：<br>``curl -X POST "http://localhost:8080/sample/notification/message/text" -H "Content-Type: application/json" -d '{"channel":"ops-alert","content":"【发布通报】ateng-boot v1.2.0 发布成功","atAll":false,"atMobiles":["13800138000"]}'`` |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/markdown` | **富文本 Markdown 发信**（自动执行企微表格向引用块优雅降级清洗）：<br>``curl -X POST "http://localhost:8080/sample/notification/message/markdown" -H "Content-Type: application/json" -d '{"channel":"ops-alert","title":"系统巡检汇总","content":"### 巡检摘要\n| 服务名 | 状态 | 延迟 |\n| :--- | :--- | :--- |\n| Gateway | 正常 | 12ms |\n| Auth | 正常 | 8ms |"}'`` |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/card` | **跨端自适应交互卡片**（支持 10 色主题与横纵多按钮排版）：<br>``curl -X POST "http://localhost:8080/sample/notification/message/card" -H "Content-Type: application/json" -d '{"channel":"ops-alert","title":"集群高负载预警","content":"节点 worker-01 CPU 使用率超过 **92%**","headerColor":"RED","btnOrientation":"HORIZONTAL","buttons":[{"title":"查看监控","url":"https://monitor.example.com","type":"PRIMARY"},{"title":"立即重启","url":"https://ops.example.com/restart","type":"DANGER"}]}'`` |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/link` | **图文链接消息**（自适应转译钉钉 Link、企微 News 单图文与飞书图文卡片）：<br>``curl -X POST "http://localhost:8080/sample/notification/message/link" -H "Content-Type: application/json" -d '{"channel":"dingtalk-dev","title":"项目里程碑通报","text":"v1.2.0 已完成端到端测试与五维文档闭环","messageUrl":"https://github.com/atengk/ateng-boot","picUrl":"https://img.example.com/banner.png"}'`` |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/image` | **图片消息**（支持 Base64 内容或本地文件安全防卫发信）：<br>``curl -X POST "http://localhost:8080/sample/notification/message/image" -H "Content-Type: application/json" -d '{"channel":"ops-alert","base64":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="}'`` |
| **多模态消息** | `NotificationMessageController` | `POST /sample/notification/message/raw` | **原生特化报文透传**（直接传入官方原生 JSON 逃生舱）：<br>``curl -X POST "http://localhost:8080/sample/notification/message/raw" -H "Content-Type: application/json" -d '{"channel":"dingtalk-dev","rawJson":"{\"msgtype\":\"text\",\"text\":{\"content\":\"原生报文透传测试\"}}"}'`` |
| **渠道治理** | `NotificationChannelController` | `POST /sample/notification/channel/switch` | **线程级临时切源**（`callWithChannel` 动态切源并在 `finally` 严格还原现场）：<br>``curl -X POST "http://localhost:8080/sample/notification/channel/switch" -H "Content-Type: application/json" -d '{"temporaryChannel":"dingtalk-dev","content":"动态切源发信测试"}'`` |
| **渠道治理** | `NotificationChannelController` | `POST /sample/notification/channel/template-render` | **动态占位符模板渲染发信**（变量插值解耦业务消息组装）：<br>``curl -X POST "http://localhost:8080/sample/notification/channel/template-render" -H "Content-Type: application/json" -d '{"channel":"dingtalk-dev","templateName":"服务 ${appName} 瞬时 QPS 达到 ${qps}，当前健康状态: ${status}","variables":{"appName":"order-service","qps":"12500","status":"WARNING"}}'`` |
| **渠道治理** | `NotificationChannelController` | `POST /sample/notification/channel/failover` | **顺延故障转移 Failover**（主渠道异常自动无缝切换备用渠道重试）：<br>``curl -X POST "http://localhost:8080/sample/notification/channel/failover" -H "Content-Type: application/json" -d '{"primaryChannel":"sample-failover-primary","title":"故障转移演练","content":"主渠道模拟故障触发级联重试"}'`` |
| **可靠事务** | `NotificationTransactionController` | `POST /sample/notification/transaction/send-after-commit` | **本地事务感知发信**（严格在数据库事务物理提交后外呼，防止幽灵通知）：<br>``curl -X POST "http://localhost:8080/sample/notification/transaction/send-after-commit" -H "Content-Type: application/json" -d '{"channel":"ops-alert","content":"【事务通知】订单 ORD-9988 结算完成，已成功入库"}'`` |
| **可靠事务** | `NotificationTransactionController` | `POST /sample/notification/transaction/send-async` | **受管异步非阻塞发信**（托管于受管线程池并无损透传 MDC TraceId 快照）：<br>``curl -X POST "http://localhost:8080/sample/notification/transaction/send-async" -H "Content-Type: application/json" -d '{"channel":"ops-alert","content":"【异步通知】批量对账任务已后台派发"}'`` |
| **异常告警** | `NotificationAlertController` | `POST /sample/notification/alert/trigger` | **声明式 `@NotifyAlert` 切面拦截**（自动提取 Top 10 堆栈并将敏感密码脱敏为 `******`）：<br>``curl -X POST "http://localhost:8080/sample/notification/alert/trigger" -H "Content-Type: application/json" -d '{"orderId":"ORD-ALERT-9999","password":"SUPER_SECRET_PASSWORD_123","errorType":"npe"}'`` |
| **异常告警** | `NotificationAlertController` | `POST /sample/notification/alert/storm-suppress` | **告警风暴三阶抑制抗洪**（并发 10 次：首发推送 1 次红色卡片、静默拦截 9 次、2 秒后补发 1 次橙色聚合卡片）：<br>``curl -X POST "http://localhost:8080/sample/notification/alert/storm-suppress" -H "Content-Type: application/json" -d '{"orderId":"ORD-STORM-1001","password":"STORM_SECRET_PWD","count":10}'`` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-notification,ateng-boot-samples/ateng-boot-sample-notification -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-notification README](../../ateng-boot-notification/README.md)。
