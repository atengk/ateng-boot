# ateng-boot-mail 基础设施 Starter

> 企业级异步邮件通信与模板通知基础设施 Starter，支持多账号动态路由、批量邮件合并与受控并发、多模测试安全沙箱、国际化多语言级联渲染、SMTP 物理连通性健康探针、强类型优先级标头、合规归档密送、受管异步线程池四维上下文透传、大附件流式防爆装配、正交内联资源 (Inline CID)、本地事务感知发信（`sendAfterCommit` 防幽灵邮件）、全生命周期事件广播、发信拦截器 SPI 与三重失败补偿安全网。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **多账号路由** | 多通道隔离与动态路由 | `MailTemplate`<br>`MailAccountRegistry`<br>`MailAccountResolver` | `ateng.boot.mail.default-account`<br>`ateng.boot.mail.accounts.*`<br>`ateng.boot.mail.max-dynamic-accounts` | `MailAccountRegistry`: `register(account)` / `unregister(name)` / `get(name)` / `getDynamic(name)` / `getOrCreateDynamic(account, props)`；`MailTemplate`: `sendWithAccount(MailAccount, ...)` / `forDynamicAccount(MailAccount)`，支持租户上下文动态寻址与动态临时账号 LRU 物理复用 | `MailAccountRegistryTest`<br>`MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **动态账号供给** | 外部介质动态加载发信账号 SPI | `MailAccountProvider`<br>`MailTemplate`<br>`MailAccountRegistry` | `ateng.boot.mail.max-dynamic-accounts` | `MailAccountProvider`: `loadAccount(String accountName)`；`MailTemplate`: `send(MailMessage)` 执行静态表 $\rightarrow$ 动态 LRU $\rightarrow$ Provider 懒加载并池化 Sender；落实双轨防张冠李戴防线（显式指定未命中 Fail-Fast 抛出 `MAIL_ACCOUNT_NOT_FOUND`，隐式推断未命中平滑回退至默认账号）；支持 `message.from` / `fromName` 动态别名覆盖 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **缓存热驱逐** | 动态账号缓存注销与领域事件 | `MailAccountRegistry`<br>`MailTemplate`<br>`MailAccountEvictEvent`<br>`MailAccountEvictListener` | - | `MailAccountRegistry`: `evictDynamic(name)` / `clearDynamic()`；`MailTemplate`: `evictDynamicAccount(name)` / `clearDynamicAccounts()`；Spring 原生事件 `MailAccountEvictEvent` 驱动单机或分布式集群消息广播联动热注销 | `MailAccountRegistryTest`<br>`MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **批量邮件合并** | 受控并发批量个性化发信与聚合回执 | `MailTemplate`<br>`DynamicMailSession`<br>`BatchMailSendResult` | `ateng.boot.mail.batch.concurrency` | `MailTemplate`: `sendBatch(List<MailMessage>)` / `sendBatchAsync(List<MailMessage>)`；`DynamicMailSession`: `sendBatch(...)` / `sendBatchAsync(...)`；基于 `Semaphore` 受控并发切片防网关封禁，Fail-Safe 单信独立容错聚合 | `MailBatchSendTest`<br>`MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **测试安全沙箱** | 多模邮件测试安全沙箱拦截链 | `MailSandboxInterceptor`<br>`MailSandboxProperties` | `ateng.boot.mail.sandbox.enabled`<br>`ateng.boot.mail.sandbox.mode`<br>`ateng.boot.mail.sandbox.allowed-recipients`<br>`ateng.boot.mail.sandbox.redirect-to`<br>`ateng.boot.mail.sandbox.subject-prefix` | 开箱支持 `ALLOWLIST`（白名单放行/阻断）、`REDIRECT`（统一定向捕获并清空真实 CC/BCC）、`LOG_ONLY`（纯日志模拟零网络开销），支持 AntPath 与精确/域名通配 | `MailSandboxInterceptorTest`<br>`MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **国际化渲染** | 模板渲染引擎 SPI 国际化多语言演进 | `MailTemplate`<br>`MailTemplateRenderer`<br>`MailMessage` | - | `MailTemplateRenderer`: `render(name, model, Locale)`；支持 Thymeleaf、FreeMarker 与 Simple 级联多语言变体寻址（如 `welcome_en_US.html` $\rightarrow$ `welcome_en.html` $\rightarrow$ `welcome.html`）；`MailMessage`: `locale(Locale)`；`MailTemplate`: `sendTemplate*(..., Locale)` 显式传参或自动兜底 `LocaleContextHolder` | `MailTemplateRendererI18nTest`<br>`MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **物理连通探针** | 零发信 SMTP 物理握手与连通性健康探测 | `MailTemplate`<br>`MailAccountRegistry`<br>`DynamicMailSession` | `ateng.boot.mail.connection-timeout`<br>`ateng.boot.mail.read-timeout` | `boolean testConnection(...)`（快速布尔探测）与 `void testConnectionOrThrow(...)`（异常探测）；底层调用 `JavaMailSenderImpl.testConnection()` 实施真实 TCP 握手 + TLS 协商 + AUTH 凭据校验，零真实发信且 `finally` 确定性释放连接 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **合规审计归档** | 合规审计归档密送底单自动追加 | `MailTemplate`<br>`MailProperties`<br>`MailAccountProperties` | `ateng.boot.mail.archive-bcc`<br>`ateng.boot.mail.accounts.*.archive-bcc` | 在发信前置链路自动将配置的 `archive-bcc` 追加至 `message.bcc` 列表；采用大小写无关集合去重合并，防止显式 Bcc 与归档 Bcc 重复产生冗余投递；记录脱敏审计日志 | `MailPriorityAndArchiveBccTest`<br>`MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **强类型优先级** | 强类型邮件优先级与已读回执标头 | `MailPriority`<br>`MailMessage` | - | `MailPriority`（`HIGH(1)`, `NORMAL(3)`, `LOW(5)`）；`MailMessage`: `priority(MailPriority)` 自动映射 `X-Priority`、`Importance`、`X-MSMail-Priority` 标头；`readReceiptTo(email)` / `requestReadReceipt(true)` 自动映射 RFC 3798 `Disposition-Notification-To` 回执标头 | `MailPriorityAndArchiveBccTest`<br>`MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **纯文本发信** | 简单文本邮件同步发送 | `MailTemplate` | `ateng.boot.mail.accounts.*.host`<br>`ateng.boot.mail.accounts.*.port`<br>`ateng.boot.mail.accounts.*.ssl` | `sendText(to, subject, text)` / `sendText(account, to, subject, text)` / `sendText(Collection<String>, subject, text)` / `sendText(account, Collection<String>, subject, text)` | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **富文本发信** | HTML 富文本邮件同步发送 | `MailTemplate` | `ateng.boot.mail.accounts.*.from`<br>`ateng.boot.mail.accounts.*.from-name` | `sendHtml(to, subject, html)` / `sendHtml(account, to, subject, html)` / `sendHtml(Collection<String>, subject, html)` / `sendHtml(account, Collection<String>, subject, html)` | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **受管异步发信** | 双模自适应受管异步发信 | `MailTemplate`<br>`mailTaskExecutor`<br>`Executor` | `ateng.boot.mail.async.enabled`<br>`ateng.boot.mail.async.core-pool-size`<br>`ateng.boot.mail.async.max-pool-size`<br>`ateng.boot.mail.async.queue-capacity`<br>`ateng.boot.mail.async.keep-alive`<br>`ateng.boot.mail.async.thread-name-prefix` | 专职 `@Bean("mailTaskExecutor")`，解除虚拟线程类型窄化；依据配置自适应构建平台线程池或虚拟线程（Java 21+），自动挂载四维上下文透传装饰器；`sendAsync(MailMessage)` / `sendTextAsync(...)` / `sendHtmlAsync(...)` / `sendTemplateAsync(...)` 自动透传 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ | `AtengMailAutoConfigurationTest`<br>`MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **事务感知发信** | 事务提交后发信（防幽灵邮件）| `MailTemplate` | - | `sendAfterCommit(MailMessage)` / `sendAfterCommit(Consumer<MailMessage>)` / `sendAfterCommitWithAccount(...)` / `sendAsyncAfterCommit(...)`，Spring 物理事务 Commit 成功后触发，Rollback 自动安全取消 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **大附件流式挂载** | RFC 6266 标准流式附件防爆装配 | `MailMessage`<br>`MailAttachment` | `ateng.boot.mail.max-attachment-size`<br>`ateng.boot.mail.max-total-size` | `MailAttachment.of(String, File)` / `of(File)` / `of(String, byte[])` / `of(String, InputStreamSource)`；`message.attach(MailAttachment)` / `message.attachment(MailAttachment)`；容量双重前置 Fail-Fast 防爆与流确定性关闭 | `MailAttachmentTest`<br>`MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **正交内联资源** | 正交内联资源 (Inline CID) | `MailMessage`<br>`MailInlineResource` | - | `MailInlineResource.of(contentId, File)` / `of(contentId, byte[])` / `of(contentId, Resource)`；`message.inline(MailInlineResource)`，支持 HTML 正文通过 `cid:...` 极速图文混排 | `MailInlineResourceTest`<br>`MailTemplateGreenMailTest` |
| **弹性指数重试** | 弹性网络重试退避 | `MailTemplate`<br>`RetryUtil` | `ateng.boot.mail.retry.enabled`<br>`ateng.boot.mail.retry.max-attempts`<br>`ateng.boot.mail.retry.delay`<br>`ateng.boot.mail.retry.multiplier`<br>`ateng.boot.mail.retry.max-delay`<br>`ateng.boot.mail.retry.jitter` | 智能识别 Socket 超时与瞬态连接抖动，基于指数退避加随机抖动重试，排除不可重试的认证失败与语法错误 | `MailTemplateGreenMailTest` |
| **发信拦截器** | 发信前置拦截与合规治理 SPI | `MailSendInterceptor` | - | `boolean preSend(MailMessage message)`，支持 `Ordered` 排序，实现收件人黑名单阻断、全局水印注入或动态合规标头（如 `X-Audit-Trace`）重写 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **生命周期事件** | 原生 Spring 领域事件广播 | `MailSendingEvent`<br>`MailSentEvent`<br>`MailSendFailedEvent` | - | 依托 `ApplicationEventPublisher` 在发信前、成功送达与终态失败时发布原生领域事件，便于生产审计、大盘监控与外部告警联动 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **三重失败补偿** | 失败安全网补偿处理 SPI | `MailSendFailureHandler`<br>`MailProperties` | - | 发信终态失败时触发：1. 脱敏脱密安全日志记录；2. 发布 `MailSendFailedEvent`；3. 回调已注入的 `MailSendFailureHandler` 列表执行业务落地兜底 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |
| **确定性资源清理**| 句柄与流瞬态物理资源销毁 | `MailMessage`<br>`MailAttachment` | - | 实现 `AutoCloseable` 接口，在 `finally` 块中确定性释放附件流（`InputStream`）、临时文件与底层资源，杜绝磁盘膨胀与文件句柄泄露 | `MailMessageTest`<br>`MailAttachmentTest` |
| **因果异常体系** | 语义化异常与统一错误码 | `MailSendException`<br>`MailResultCode` | - | 转译底层 JavaMail / 连接异常为框架语义化 `MailSendException` 并 100% 传递原始 `cause`，枚举提供 `MAIL_CONNECT_TIMEOUT`、`MAIL_AUTHENTICATION_ERROR`、`MAIL_ACCOUNT_NOT_FOUND` 等强类型错误码 | `MailTemplateGreenMailTest`<br>`SampleMailIntegrationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-mail` 是面向企业级生产环境的工业级邮件通信 Starter：
- **零臃肿标准**：基于 JDK 17、Spring Boot 3 与官方 `spring-boot-starter-mail` 构建，纯原生零臃肿依赖；
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

### 2.1 生产级配置全景 (application.yml)
```yaml
ateng:
  boot:
    mail:
      enabled: true
      default-account: default
      max-dynamic-accounts: 100
      archive-bcc: compliance@example.com         # 全局合规审计归档密送邮箱
      max-attachment-size: 20MB                   # 单附件大小上限
      max-total-size: 50MB                        # 附件与正文总大小上限
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
      batch:
        concurrency: 4                            # 批量合并发信受控并发度
      sandbox:
        enabled: false                            # 测试安全沙箱开关（非生产环境建议开启）
        mode: REDIRECT                            # ALLOWLIST / REDIRECT / LOG_ONLY
        redirect-to: trap@example.com             # 重定向模式接收端
        subject-prefix: "[TEST-SANDBOX] "
      retry:
        enabled: true
        max-attempts: 3
        delay: 100ms
        multiplier: 2.0
      async:
        enabled: true
        core-pool-size: 2
        max-pool-size: 8
        queue-capacity: 100
        keep-alive: 60s
        thread-name-prefix: mail-async-
```

### 2.2 开箱即用核心场景代码

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

#### 第二斧：批量个性化对账单合并发送（受控并发与 Fail-Safe 容错）
```java
public BatchMailSendResult sendMonthlyStatements(List<UserStatement> statements) {
    List<MailMessage> messages = statements.stream()
            .map(stmt -> new MailMessage()
                    .to(stmt.getEmail())
                    .subject("十月对账单 - " + stmt.getUsername())
                    .text(stmt.getContent()))
            .toList();

    // 内部采用 Semaphore 受控并发，单信失败不阻断批次内其余邮件
    return mailTemplate.sendBatch(messages);
}
```

#### 第三斧：安全沙箱极速接入（防测试邮件泄漏至真实用户）
在测试或预发环境的 `application-test.yml` 启用沙箱：
```yaml
ateng:
  boot:
    mail:
      sandbox:
        enabled: true
        mode: REDIRECT
        redirect-to: qa-team@example.com
        allowed-recipients:
          - "*@internal.corp"
```
代码无需任何改动，业务发信自动透明拦截：非白名单收件人安全重定向至 `qa-team@example.com`，并彻底清空真实 CC/BCC。

---

## 3. 生产扩展接管 (Customization & Extensibility)

### 3.1 模板渲染引擎 SPI 国际化多语言扩展 (`MailTemplateRenderer`)
框架内置轻量级占位符渲染器（`SimpleMailTemplateRenderer`）、`ThymeleafMailTemplateRenderer` 与 `FreemarkerMailTemplateRenderer`，均支持多语言变体级联寻址：

```java
// 显式传入目标 Locale 或由上下文探测（如 LocaleContextHolder）
mailTemplate.sendTemplate("user@example.com", "Verification Notice", "mail/verify-code", model, Locale.US);
```
- 当传入 `Locale.US` 时，渲染器依次自动探测：`verify-code_en_US.html` $\rightarrow$ `verify-code_en.html` $\rightarrow$ `verify-code.html`；
- 若需对接专有模板引擎（如 Velocity、Beetl 等），只需实现 `MailTemplateRenderer` 接口并声明为 Spring Bean 即可无缝接管：
```java
@Component
public class CustomMailTemplateRenderer implements MailTemplateRenderer {

    @Override
    public String render(String templateName, Map<String, Object> model, Locale locale) {
        // 自定义国际化模板渲染逻辑
        return renderEngine.render(templateName, model, locale);
    }
}
```

### 3.2 管理端 SMTP 物理连通性一键测试探针
提供管理后台或运维脚手架所需的物理链路连通性检测能力：
```java
@RestController
@RequestMapping("/api/admin/mail")
@RequiredArgsConstructor
public class MailAdminController {

    private final MailTemplate mailTemplate;

    @PostMapping("/test-connection")
    public Result<Boolean> testConnection(@RequestParam String accountName) {
        // 异常型强校验：失败时抛出 MAIL_AUTHENTICATION_ERROR / MAIL_CONNECT_TIMEOUT 等语义化异常
        mailTemplate.testConnectionOrThrow(accountName);
        return Result.success(true);
    }
}
```

### 3.3 强类型邮件优先级与已读回执标头
在需要发送紧急运维告警、合同签署确认通知时，使用链式 DSL 注入标准标头：
```java
MailMessage message = new MailMessage()
        .to("duty-ops@example.com")
        .subject("线上数据库故障 P0 告警")
        .text("数据库连接池耗尽，请立即处置！")
        .priority(MailPriority.HIGH)           // 自动映射 X-Priority: 1 与 Importance: High
        .readReceiptTo("audit@example.com");   // 自动映射 RFC 3798 Disposition-Notification-To

mailTemplate.send(message);
```

### 3.4 自定义发信拦截器 SPI (`MailSendInterceptor`)
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

### 3.5 失败补偿处理 SPI (`MailSendFailureHandler`)
实现 `MailSendFailureHandler` 接入企业级落库兜底、重试死信队列或钉钉/企业微信群通知：

```java
@Component
public class DatabaseBackupFailureHandler implements MailSendFailureHandler {

    @Override
    public void onFailure(MailMessage message, Throwable cause) {
        // 将发信失败的邮件持久化至 mail_failed_record 补偿表，由定时任务对账重试
    }
}
```

### 3.6 外部介质动态发件账号供给 SPI (`MailAccountProvider`)
当发信账号存储在数据库、分布式配置中心或秘密管理器中时，实现 `MailAccountProvider` 并注入为 Spring Bean。业务方在持久层读取密文后自行解密并向框架传递真明文凭据（捍卫 [ADR-0012](../docs/adr/0012-lifecycle-separated-data-security.md) 内存真明文契约）：

```java
@Component
public class DatabaseMailAccountProvider implements MailAccountProvider {

    @Autowired
    private SysMailAccountRepository accountRepository;

    @Override
    public MailAccount loadAccount(String accountName) {
        SysMailAccountEntity entity = accountRepository.findByAccountName(accountName);
        if (entity == null) {
            return null;
        }

        // 业务层自主解密存储密文，框架内始终保持内存真明文
        String plainPassword = CryptoUtil.aesDecrypt(entity.getEncryptedPassword(), secretKey);

        return MailAccount.builder()
                .name(entity.getAccountName())
                .host(entity.getHost())
                .port(entity.getPort())
                .username(entity.getUsername())
                .password(plainPassword)
                .ssl(entity.isSsl())
                .defaultFrom(entity.getDefaultFrom())
                .defaultFromName(entity.getDefaultFromName())
                .build();
    }
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 批量发信受控并发与网关防封禁
- **受控信号量并发**：`sendBatch` 依托 `Semaphore` 控制最大并发请求数（由 `ateng.boot.mail.batch.concurrency` 纳管，生产默认 4，上限 32），杜绝瞬间并发洪峰被各大邮件运营商（网易、腾讯、Gmail 等）识别为垃圾邮件攻击而遭到 SMTP 网关封禁；
- **Fail-Safe 独立容错聚合**：单封邮件发生网络超时或地址错误时，错误仅记录于对应 `MailSendResultItem.failure(...)`，绝对不阻断批次内其余合法邮件的正常投递。

### 4.2 测试安全沙箱防御机制
- **重定向安全隔离**：在 `REDIRECT` 模式下，框架将邮件真实收件人替换为指定的捕获邮箱，并**强制清空 CC 与 BCC**，彻底杜绝测试邮件因抄送/密送外泄至真实用户的生产安全事故；
- **纯日志零网络开销**：在 `LOG_ONLY` 模式下，系统仅在控制台与日志中打印完整的脱敏邮件信息并模拟生成消息 ID，100% 避免建立外部网络连接，适合断网 CI 环境与快速功能验证。

### 4.3 合规归档密送去重防卫
- 发信编排链在物理投递前将账号或全局配置的 `archive-bcc` 安全追加至 `message.bcc`；
- 采用 `TreeSet<>(String.CASE_INSENSITIVE_ORDER)` 对大小写不敏感进行严格去重，业务代码显式调用 `message.bcc(...)` 与自动归档完全正交合并，绝不产生重复邮件。

### 4.4 本地事务感知发信（`sendAfterCommit` 防幽灵邮件）
在转账、订单确认或用户注册等关键业务中，若在数据库事务内直接调用 `sendText`，一旦后续业务逻辑抛出异常导致数据库回滚，邮件却已无法追回，引发灾难性的“幽灵邮件”。

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

### 4.5 100% 离线自洽单测与 GreenMail 集成
单测严禁依赖外部公网真实的 SMTP 服务器。使用轻量级嵌入式 GreenMail：
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
}
```

---

## 5. 场景自验工程

本项目配套提供 1:1 独立端到端自验工程：[`ateng-boot-samples/ateng-boot-sample-mail`](../ateng-boot-samples/ateng-boot-sample-mail)：
- 包含 REST 契约控制器、Service 业务场景、HTML 多语言模板、批量邮件合并、连通性探测、沙箱拦截与事件监听器；
- 运行自验命令：
  ```bash
  mvn test -pl ateng-boot-samples/ateng-boot-sample-mail -am -T 1C -nsu -B
  ```
