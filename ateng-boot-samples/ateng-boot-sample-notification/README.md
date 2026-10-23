# ateng-boot-sample-notification 企业协同机器人通知自验工程

> 专注于工业级多端 Webhook 机器人通知通信（钉钉、企业微信、飞书与通用 Webhook）、卡片自适应跨端转译、客户端主动滑动窗口频控、本地事务感知发信（`sendAfterCommit`）、动态切源与声明式 `@NotifyAlert` 异常告警切面。

---

## 1. 场景痛点与功能定位

在现代化企业应用研发与运维中，群机器人是系统发布通报、业务指标播报、离线任务对账、致命故障预警的第一触达通道。然而传统实现往往面临如下严峻痛点：
- **各厂商 Webhook 协议严重割裂**：企微使用 `msgtype: template_card`，钉钉使用 `actionCard`，飞书使用 `interactive card v2`，开发人员不得不为不同群写大量冗余转译代码；
- **平台频控封禁风险巨大**：各厂商严格限制发信频率（如钉钉 20 条/分钟，企微 20 条/分钟），无控制的高并发推送极易导致群机器人被官方封禁 10 分钟乃至封号；
- **发信与业务本地事务割裂**：在本地数据库事务未物理提交甚至发生回滚时，外围通知已提前外呼推送，引发“幽灵通知”事故；
- **异常告警瞬间风暴刷屏**：下游依赖故障或网络抖动时，数千个并发请求在几十秒内倾泻数千条告警，彻底淹没有效信息并打垮企业通讯群；
- **敏感凭据与机密泄漏**：告警堆栈与入参打印时未脱敏，导致包含真实用户密码、密钥、身份证号的数据明文推送至公共群聊。

`ateng-boot-sample-notification` 作为 1:1 对齐 `ateng-boot-notification` 的独立场景沙箱，具备以下工业级特性：
- **100% 离线自洽测试**：内嵌 OkHttp `MockWebServer` 模拟各大平台服务端，毫秒级快速单测，零外部网络依赖；
- **显式强类型契约**：Controller 统一声明返回强类型 `Result<NotificationSendResult>`，入参 DTO 挂载 JSR-303 格式校验 Fail-Fast；
- **多端卡片自适应转译**：统一声明 `CardMessage`，由各平台 PayloadBuilder 智能自适应转译为钉钉 ActionCard、企微 TemplateCard、飞书卡片或通用 Webhook；
- **本地事务感知安全网**：`sendAfterCommit` 严格挂载事务同步器，仅在物理提交后发信，事务回滚自动取消；
- **三阶防告警风暴抑制**：声明式 `@NotifyAlert` 切面拦截未捕获异常，MD5 确定性指纹归因，首发即时推送红色卡片，窗口期静默原子计数，到期聚合通报；
- **敏感凭证 100% 审计脱敏**：出站 Webhook Token、Key、Secret 以及告警方法入参中的敏感字段（如 `password`）全自动打星号掩码。

---

## 2. 演示场景清单

| 演示场景 | 核心门面 / 注解 / SPI | 业务类与端点 | 核心特性与行为 |
| :--- | :--- | :--- | :--- |
| **纯文本通知发送** | `NotificationTemplate.send` | `POST /api/v1/notification/text` | 默认渠道发信，统一 `Result` 契约，支持 `@所有人` 与手机号/工号精准 `@人` |
| **富文本 Markdown 发信** | `NotificationTemplate.send` | `POST /api/v1/notification/markdown` | 企微 Markdown 表格自动安全降级为引用块，防语法破坏；钉钉/飞书原生渲染 |
| **跨端自适应交互卡片** | `NotificationTemplate.send` | `POST /api/v1/notification/card` | 10 色主题卡片顶栏（`CardColor`）与跳转操作按钮（`CardButton`），多平台自适应转译 |
| **受管异步非阻塞发信** | `NotificationTemplate.sendAsync` | `POST /api/v1/notification/async` | 异步 CompletableFuture 非阻塞响应，Core 受管线程池与四维上下文透传 |
| **事务感知发信防幽灵** | `NotificationTemplate.sendAfterCommit` | `POST /api/v1/notification/transactional` | 严格绑定 Spring 物理事务提交，事务异常回滚时自动取消防幽灵通知 |
| **动态切源与上下文隔离** | `NotificationContextHolder.callWithChannel` | `POST /api/v1/notification/switch-channel` | 线程级通知切源，支持多层嵌套并在 `finally` 严格还原现场 |
| **声明式异常告警切面** | `@NotifyAlert` | `POST /api/v1/alert/trigger` | 捕获未受控异常，自动提取 MDC `traceId`、Top 10 精简堆栈及敏感入参安全脱敏 |
| **三阶防告警风暴抑制** | `AlertStormSuppressor` | `POST /api/v1/alert/trigger` | 500+ 并发瞬时异常首发即时通报，窗口期静默原子计数，周期末补发聚合卡片 |
| **外部动态渠道供给 SPI** | `NotificationChannelProvider` | `SampleCustomNotificationChannelProvider` | 模拟从外部配置源动态按需懒加载通知渠道配置 |
| **发信失败补偿处理 SPI** | `NotificationSendFailureHandler` | `SampleNotificationSendFailureHandler` | 接管发信失败事件，支持死信落库、告警调度与降级补偿 |

---

## 3. 本地自验与自动化测试

### 3.1 执行场景端到端自动化测试（推荐）
```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-notification -am -T 1C -nsu -B
```

### 3.2 启动本地服务自验
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-notification spring-boot:run
```

#### 发送纯文本通知示例
```bash
curl -X POST "http://localhost:8080/api/v1/notification/text" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "ops-alert",
    "content": "【生产发布】ateng-boot v1.0.0 发布成功！",
    "atAll": false,
    "atMobiles": ["13800138000"],
    "atUserIds": ["ops-admin"]
  }'
```

#### 发送富文本 Markdown 示例（企微自动表格清洗）
```bash
curl -X POST "http://localhost:8080/api/v1/notification/markdown" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "ops-alert",
    "title": "系统巡检汇总",
    "content": "### 巡检摘要\n| 服务名 | 状态 | 延迟 |\n| :--- | :--- | :--- |\n| Gateway | 正常 | 12ms |\n| Auth | 正常 | 8ms |"
  }'
```

#### 发送交互卡片通知示例
```bash
curl -X POST "http://localhost:8080/api/v1/notification/card" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "ops-alert",
    "title": "集群高负载预警",
    "content": "节点 worker-01 CPU 使用率超过 **92%**",
    "headerColor": "RED",
    "buttonTitle": "立即排查",
    "buttonUrl": "https://monitor.example.com/nodes/worker-01"
  }'
```

#### 触发声明式异常告警切面与参数脱敏自验
```bash
curl -X POST "http://localhost:8080/api/v1/alert/trigger" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "ORD-ALERT-9999",
    "password": "SUPER_SECRET_PASSWORD_123",
    "errorType": "npe"
  }'
```
