# ateng-boot-mail 基础设施 Starter

> 企业级异步邮件通信与模板通知基础设施 Starter，支持多账号动态路由、受管异步线程池四维上下文透传、HTML 模板引擎解耦动态渲染、RFC 6266 大附件流式防爆装配、正交内联资源 (Inline CID)、本地事务感知发信（`sendAfterCommit` 防幽灵邮件）、全生命周期事件广播、发信拦截器 SPI 与三重失败补偿安全网。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **多账号路由** | 多通道隔离与动态路由 | `MailTemplate`<br>`MailAccountRegistry`<br>`MailAccountResolver` | `ateng.boot.mail.default-account`<br>`ateng.boot.mail.accounts.*` | `sendWithAccount(String, ...)` / `sendWithAccount(MailAccount, ...)` / `forDynamicAccount(MailAccount)`，支持租户上下文动态寻址与动态临时账号 LRU 物理复用 | `MailAccountRegistryTest`<br>`MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **纯文本发信** | 简单文本邮件同步发送 | `MailTemplate` | `ateng.boot.mail.accounts.*.host`<br>`ateng.boot.mail.accounts.*.port`<br>`ateng.boot.mail.accounts.*.ssl` | `sendText(to, subject, text)` / `sendText(account, to, subject, text)` / `sendText(Collection<String>, subject, text)` / `sendText(account, Collection<String>, subject, text)` | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **富文本发信** | HTML 富文本邮件同步发送 | `MailTemplate` | `ateng.boot.mail.accounts.*.from`<br>`ateng.boot.mail.accounts.*.from-name` | `sendHtml(to, subject, html)` / `sendHtml(account, to, subject, html)` / `sendHtml(Collection<String>, subject, html)` / `sendHtml(account, Collection<String>, subject, html)` | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **模板渲染** | 多引擎解耦动态模板发信 | `MailTemplate`<br>`MailTemplateRenderer` | `ateng.boot.mail.template.type`<br>`ateng.boot.mail.template.cache` | `sendTemplate(to, subject, templateName, Map<String, Object>)` 及 account/集合重载，自动集成 Thymeleaf / FreeMarker 或内联占位符引擎 | `ThymeleafMailTemplateRendererTest`<br>`FreemarkerMailTemplateRendererTest`<br>`SampleMailIntegrationTest` |
| **受管异步发信** | 非阻塞受管异步发信 | `MailTemplate`<br>`ThreadPoolTaskExecutor` | `ateng.boot.mail.async.core-pool-size`<br>`ateng.boot.mail.async.max-pool-size`<br>`ateng.boot.mail.async.queue-capacity`<br>`ateng.boot.mail.async.keep-alive`<br>`ateng.boot.mail.async.thread-name-prefix` | `sendAsync(MailMessage)` / `sendAsync(Consumer<MailMessage>)` / `sendTextAsync(...)` / `sendHtmlAsync(...)` / `sendTemplateAsync(...)`，四维上下文 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 自动透传 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **事务感知发信** | 事务提交后发信（防幽灵邮件）| `MailTemplate` | - | `sendAfterCommit(MailMessage)` / `sendAfterCommit(Consumer<MailMessage>)` / `sendAfterCommitWithAccount(...)` / `sendAsyncAfterCommit(...)`，Spring 物理事务 Commit 成功后触发，Rollback 自动安全取消 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **大附件流式挂载** | RFC 6266 标准流式附件 | `MailMessage`<br>`MailAttachment` | `ateng.boot.mail.max-attachment-size`<br>`ateng.boot.mail.max-total-attachments-size` | `MailAttachment.of(String, File)` / `of(File)` / `of(String, byte[])` / `of(String, InputStreamSource)`；`message.attach(MailAttachment)` / `message.attachment(MailAttachment)` / `attach(String, File)` / `attach(String, byte[])`，容量超限前置 Fail-Fast 防爆与流确定性关闭 | `MailAttachmentTest`<br>`MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **正交内联资源** | 正交内联资源 (Inline CID) | `MailMessage`<br>`MailInlineResource` | - | `MailInlineResource.of(contentId, File)` / `of(contentId, byte[])` / `of(contentId, Resource)`；`message.inline(MailInlineResource)` / `inline(contentId, File)` / `inline(contentId, byte[])`，支持 HTML 正文通过 `cid:...` 极速图文混排 | `MailInlineResourceTest`<br>`MailTemplateGreenMailTest` |
| **弹性指数重试** | 弹性网络重试退避 | `MailTemplate`<br>`RetryUtil` | `ateng.boot.mail.retry.enabled`<br>`ateng.boot.mail.retry.max-attempts`<br>`ateng.boot.mail.retry.delay`<br>`ateng.boot.mail.retry.multiplier`<br>`ateng.boot.mail.retry.max-delay`<br>`ateng.boot.mail.retry.jitter` | 智能识别 Socket 超时与瞬态连接抖动，基于指数退避加随机抖动重试，排除不可重试的认证失败与语法错误 | `MailTemplateGreenMailTest` |
| **发信拦截器** | 发信前置拦截与合规治理 SPI | `MailSendInterceptor` | - | `boolean preSend(MailMessage message)`，支持 `Ordered` 排序，实现收件人黑名单阻断、全局水印注入或动态合规标头（如 `X-Audit-Trace`）重写 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **生命周期事件** | 原生 Spring 领域事件广播 | `MailSendingEvent`<br>`MailSentEvent`<br>`MailSendFailedEvent` | - | 依托 `ApplicationEventPublisher` 在发信前、成功送达与终态失败时发布原生领域事件，便于生产审计、大盘监控与外部告警联动 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **三重失败补偿** | 失败安全网补偿处理 SPI | `MailSendFailureHandler`<br>`MailProperties` | - | 发信终态失败时触发：1. 脱敏脱密安全日志记录；2. 发布 `MailSendFailedEvent`；3. 回调已注入的 `MailSendFailureHandler` 列表执行业务落地兜底 | `MailTemplateGreenMailTest` |
| **确定性资源清理**| 句柄与流瞬态物理资源销毁 | `MailMessage`<br>`MailAttachment` | - | 实现 `AutoCloseable` 接口，在 `finally` 块中确定性释放附件流（`InputStream`）、临时文件与底层资源，杜绝磁盘膨胀与文件句柄泄露 | `MailMessageTest`<br>`MailAttachmentTest` |
| **因果异常体系** | 语义化异常与统一错误码 | `MailSendException`<br>`MailResultCode` | - | 转译底层 JavaMail / 连接异常为框架语义化 `MailSendException` 并 100% 传递原始 `cause`，枚举提供 `MAIL_CONNECT_TIMEOUT`、`MAIL_AUTHENTICATION_ERROR`、`MAIL_RECIPIENT_EMPTY` 等强类型错误码 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-mail` 是面向企业级生产环境的工业级邮件通信 Starter：
- **零臃肿标准**：基于 JDK 17、Spring Boot 3 与官方 `spring-boot-starter-mail` 构建，严禁引入第三方臃肿工具包；
- **传输与持久绝对解耦**：发信领域模型强类型封装（`MailMessage`），Controller 边界严格使用专用 DTO 与强类型 `Result<MailSendResult>` 返回契约；
- **全链路追踪与无状态设计**：MDC `traceId` 深度透传至每封邮件 Message-ID 与自定义标头；受管线程池驱动，单例服务保持严格无状态。

### 引入依赖
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-mail</artifactId>
</dependency>
```

若需使用 Thymeleaf 模板动态渲染 HTML 邮件，可按需引入（模块已具备可选依赖与条件自动装配）：
```xml
<dependency>
    <groupId>org.thymeleaf</groupId>
    <artifactId>thymeleaf-spring6</artifactId>
</dependency>
```

---

## 2. 最小起步体验 (Happy Path & Out-of-the-Box)

### 2.1 基础配置 (application.yml)
```yaml
ateng:
  boot:
    mail:
      enabled: true
      default-account: default
      accounts:
        default:
          host: smtp.example.com
          port: 465
          ssl: true
          username: YOUR_EMAIL_ACCOUNT@example.com
          password: YOUR_SMTP_AUTH_CODE
          from: YOUR_EMAIL_ACCOUNT@example.com
          from-name: Ateng官方通知
          properties:
            mail.smtp.auth: "true"
            mail.smtp.starttls.enable: "false"
        ops:
          host: smtp.example.com
          port: 465
          ssl: true
          username: ops-alert@example.com
          password: YOUR_SMTP_AUTH_CODE
          from: ops-alert@example.com
          from-name: Ateng运维监控
      retry:
        enabled: true
        max-attempts: 3
        delay: 100ms
        multiplier: 2.0
      async:
        core-pool-size: 2
        max-pool-size: 8
        queue-capacity: 100
        thread-name-prefix: mail-async-
```

### 2.2 开箱即用三板斧代码

#### 第一斧：发送纯文本邮件
```java
@Service
@RequiredArgsConstructor
public class SimpleMailService {

    private final MailTemplate mailTemplate;

    public MailSendResult sendWelcome(String to) {
        return mailTemplate.sendText(to, "欢迎加入 Ateng Boot", "恭喜您成功注册并开启高可用微服务之旅！");
    }
}
```

#### 第二斧：多账号通道发信
```java
public MailSendResult sendOpsAlert(String to, String alertContent) {
    // 显式指定路由至 ops 账号通道
    return mailTemplate.sendText("ops", to, "集群告警预警", alertContent);
}
```

#### 第三斧：动态 Thymeleaf HTML 模板渲染发信
在 `src/main/resources/templates/mail/verify-code.html` 放置模板文件，通过模板门面极速发送：
```java
public MailSendResult sendVerifyCode(String to, String username, String verifyCode) {
    Map<String, Object> model = new HashMap<>();
    model.put("username", username);
    model.put("code", verifyCode);
    return mailTemplate.sendTemplate(to, "账户安全验证码", "mail/verify-code", model);
}
```

---

## 3. 生产扩展接管 (Customization & Extensibility)

### 3.1 自定义发信拦截器 SPI (`MailSendInterceptor`)
实现 `MailSendInterceptor` 接口并注入为 Spring Bean，即可对每封即将发送的邮件进行前置审计、非法收件人阻断或动态注入合规标头：

```java
@Component
public class AuditMailInterceptor implements MailSendInterceptor, Ordered {

    public static final String BLOCKED_EMAIL = "blocked@example.com";
    public static final String AUDIT_HEADER_KEY = "X-Audit-Trace";

    @Override
    public boolean preSend(MailMessage message) {
        // 1. 黑名单前置阻断防卫
        if (message.getTo() != null && message.getTo().contains(BLOCKED_EMAIL)) {
            return false; // 阻断发信，框架将安全抛出 MailSendException
        }

        // 2. 动态注入全链路审计与合规标头
        message.header(AUDIT_HEADER_KEY, "audited-" + System.currentTimeMillis());
        return true; // 放行发信
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
```

### 3.2 监听邮件全生命周期领域事件
无需侵入发信核心链路，通过 Spring 原生 `@EventListener` 异步或同步收集发信度量与审计事件：

```java
@Component
public class MailMetricsEventListener {

    private static final Logger log = LoggerFactory.getLogger(MailMetricsEventListener.class);

    @EventListener
    public void onMailSending(MailSendingEvent event) {
        log.info("[发信前置] 主题: [{}], 目标: {}", event.getMessage().getSubject(), event.getMessage().getTo());
    }

    @EventListener
    public void onMailSent(MailSentEvent event) {
        log.info("[发信成功] 耗时: {} ms, MessageId: [{}]",
                event.getResult().getDuration().toMillis(), event.getResult().getMessageId());
    }

    @EventListener
    public void onMailSendFailed(MailSendFailedEvent event) {
        log.error("[发信失败] 目标: {}, 根因: {}",
                event.getMessage().getTo(), event.getCause().getMessage(), event.getCause());
    }
}
```

### 3.3 失败补偿处理 SPI (`MailSendFailureHandler`)
实现 `MailSendFailureHandler` 接入企业级落库兜底、重试死信队列或钉钉/企业微信群通知：

```java
@Component
public class DatabaseBackupFailureHandler implements MailSendFailureHandler {

    @Override
    public void handleFailure(MailMessage message, Throwable cause) {
        // 将发信失败的邮件持久化至 mail_failed_record 补偿表，由定时任务对账重试
    }
}
```

### 3.4 动态租户账号解析器 (`MailAccountResolver`)
框架默认提供 `TenantMailAccountResolver`（基于 `TenantContextHolder` 自动解析租户发信账号）。若业务存在定制寻址策略，可自行声明覆盖：

```java
@Bean
@ConditionalOnMissingBean(MailAccountResolver.class)
public MailAccountResolver customAccountResolver() {
    return message -> {
        // 基于业务类型、VIP 等级或域名动态计算发信通道名称
        return "vip-channel";
    };
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 本地事务感知发信（`sendAfterCommit` 防幽灵邮件）
在转账、订单确认或用户注册等关键业务中，若在数据库事务内直接调用 `sendText`，一旦后续业务逻辑抛出异常导致数据库回滚，邮件却已无法追回，引发灾难性的“幽灵邮件”。

`ateng-boot-mail` 提供确定性的事务感知发信原语：
```java
@Transactional(rollbackFor = Exception.class)
public void registerUser(UserRegisterDTO dto) {
    // 1. 业务数据入库
    userMapper.insert(user);

    // 2. 挂载事务提交后发信
    mailTemplate.sendAfterCommit(message -> message
            .to(dto.getEmail())
            .subject("注册成功通知")
            .text("恭喜注册成功！"));

    // 若此处发生异常，Spring 事务回滚，MailTemplate 自动捕获 status != STATUS_COMMITTED 并取消发信！
}
```
- **事务激活感知**：当且仅当当前 Spring 物理事务 Commit 成功后才会触发物理发信；
- **回滚安全防卫**：事务回滚时自动取消防幽灵邮件，并在日志中打印掩码脱敏取消通告；
- **无事务平滑降级**：若调用环境处于非事务状态或未引入 `spring-tx`，框架安全探测并平滑就地执行。

### 4.2 Core 受管线程池与四维上下文透传
异步发信方法（`sendAsync`、`sendTextAsync`、`sendHtmlAsync`、`sendTemplateAsync`）依托 Core 模块受管线程池，并结合 `AsyncContext` 与 `ContextCopyingTaskDecorator`：
- **四维快照透传**：$\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 自动无损快照透传至子线程；
- **确定性现场清理**：异步任务完成后，`ContextCopyingTaskDecorator` 严格清理子线程 ThreadLocal 与 MDC，杜绝跨任务线程污染；
- **杜绝野线程**：严禁私自使用 `new Thread()` 或裸 `Executors`，线程池参数由 YAML 统一纳管。

### 4.3 RFC 6266 大附件流式防爆与资源确定性销毁
- **容量双重前置防爆**：单附件上限（`max-attachment-size`，默认 10MB）与总附件上限（`max-total-attachments-size`，默认 30MB）前置校验，超限即刻 Fail-Fast，杜绝内存 OOM；
- **确定性资源清理**：`MailMessage` 与 `MailAttachment` 均实现 `AutoCloseable`，在最外层 `finally` 块中执行 `message.close()`，确定性释放临时文件句柄与流资源。

### 4.4 100% 离线自洽单测与 GreenMail 集成
单测严禁依赖外部公网真实的 SMTP 服务器。推荐使用轻量级嵌入式 GreenMail：
```java
@SpringBootTest
@AutoConfigureMockMvc
class SampleMailIntegrationTest {

    private static final GreenMail greenMail;
    private static final int smtpPort;

    static {
        ServerSetup setup = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);
        greenMail = new GreenMail(setup)
                .withConfiguration(GreenMailConfiguration.aConfig()
                        .withUser("default@example.com", "secret-password-placeholder")
                        .withUser("ops@example.com", "secret-password-placeholder"));
        greenMail.start();
        smtpPort = greenMail.getSmtp().getPort();
    }

    @AfterAll
    static void tearDownAll() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @DynamicPropertySource
    static void configureMailProperties(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.mail.accounts.default.port", () -> smtpPort);
        registry.add("ateng.boot.mail.accounts.ops.port", () -> smtpPort);
    }

    @BeforeEach
    @AfterEach
    void cleanState() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
    }
}
```

---

## 5. 场景自验工程

本项目配套提供 1:1 独立端到端自验工程：[`ateng-boot-samples/ateng-boot-sample-mail`](../ateng-boot-samples/ateng-boot-sample-mail)：
- 包含 REST 契约控制器、Service 业务场景、HTML 模板、审计拦截器与事件监听器；
- 运行自验命令：
  ```bash
  mvn test -pl ateng-boot-samples/ateng-boot-sample-mail -am -T 1C -nsu -B
  ```
