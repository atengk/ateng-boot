# ateng-boot-sample-mail 邮件通信自验工程

> 专注于工业级异步邮件通信、多账号动态路由、HTML 模板引擎解耦渲染、流式附件挂载、本地事务感知发信（`sendAfterCommit`）、发信拦截器 SPI 与全生命周期审计事件广播。

---

## 1. 场景痛点与功能定位

在企业级应用开发中，邮件发送是账户注册验证、异地登录预警、系统告警通告、账单对账回执等核心链路的标准能力。然而传统实现通常面临多重严峻挑战：
- **多租户/多场景隔离困难**：缺乏动态发信账号路由体系，多个业务系统共用单一 SMTP 账号，容易触发频率配额超限或通道阻塞；
- **发信与业务事务割裂**：在本地数据库事务未提交甚至发生异常回滚时，外围邮件已提前发出，引发令人困扰的“幽灵邮件”事故；
- **异步发信上下文丢失**：使用未经治理的裸线程池异步发信，丢失 MDC `traceId`、操作用户与多租户上下文，导致排查困难；
- **模板与引擎硬编码绑定**：邮件正文拼接繁琐且难以热更新，换用模板引擎需要大面积重构；
- **单测依赖真实物理服务器**：缺乏离线嵌入式测试方案，CI/CD 流水线因真实 SMTP 网络波动频繁偶发失败。

`ateng-boot-sample-mail` 作为 1:1 对齐 `ateng-boot-mail` 的独立场景沙箱，具备以下工业级特性：
- **100% 离线自洽测试**：内置嵌入式 GreenMail 邮件服务器，完全无需公网 SMTP，毫秒级快速单测；
- **显式契约与第一道防线**：Controller 统一声明强类型 `Result<MailSendResult>` 返回契约，入参专用 DTO 挂载 JSR-303 格式校验 Fail-Fast；
- **全生命周期事件与拦截器**：提供 `MailSendingEvent`、`MailSentEvent`、`MailSendFailedEvent` 原生事件监听与 `MailSendInterceptor` 链路防卫；
- **本地事务感知安全网**：`sendAfterCommit` 严格挂载事务同步器，仅在物理提交后发信，事务回滚自动取消；
- **多账号与动态路由**：支持 YAML 声明式多账号配置及基于多租户上下文或业务场景的自适应路由。

---

## 2. 演示场景清单

| 演示场景 | 核心门面 / 注解 / SPI | 业务类与端点 | 核心特性与行为 |
| :--- | :--- | :--- | :--- |
| **纯文本邮件发送** | `MailTemplate.sendText` | `POST /api/mail/simple` | 默认账号通道发信，统一 `Result` 契约，自动追加主题前缀 |
| **Thymeleaf 模板邮件** | `MailTemplate.sendTemplate` | `POST /api/mail/template` | 动态加载 HTML 模板并安全填充变量（验证码、用户名等） |
| **运维通道多账号路由** | `MailTemplate.sendText("ops", ...)` | `POST /api/mail/ops` | 隔离路由至运维告警账号，独立发信人与告警前缀 |
| **受管异步发信** | `MailTemplate.sendTextAsync` | `POST /api/mail/async` | 异步 CompletableFuture 非阻塞响应，Core 受管线程池与四维上下文透传 |
| **流式附件装配** | `MailTemplate.send` + `MailAttachment` | `MailScenarioService.sendWithAttachment` | 挂载内存或流式文件附件，RFC 6266 标准编码防中文乱码 |
| **安全合规拦截器** | `MailSendInterceptor` | `SampleAuditMailInterceptor` | 黑名单前置阻断防卫，放行邮件自动注入全链路审计标头 `X-Audit-Trace` |
| **全生命周期事件广播** | `@EventListener` | `SampleMailEventListener` | 监听发信前、成功、失败领域事件，对接监控告警与发信大盘 |
| **事务感知发信防幽灵** | `MailTemplate.sendAfterCommit` | `MailScenarioService.sendTransactional` | 严格绑定 Spring 物理事务提交，事务异常回滚时自动取消防幽灵邮件 |

---

## 3. 本地自验与自动化测试

### 3.1 执行场景端到端自动化测试（推荐）
```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-mail -am -T 1C -nsu -B
```

### 3.2 启动本地服务自验
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-mail spring-boot:run
```

#### 发送纯文本邮件示例
```bash
curl -X POST "http://localhost:8080/api/mail/simple" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "dev@example.com",
    "subject": "开箱自验测试",
    "content": "恭喜！ateng-boot-mail 服务已成功就绪并完成接入。"
  }'
```

#### 发送 HTML 动态模板邮件示例
```bash
curl -X POST "http://localhost:8080/api/mail/template" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "dev@example.com",
    "subject": "验证码通知",
    "content": "占位",
    "username": "Ateng",
    "verifyCode": "668899"
  }'
```
