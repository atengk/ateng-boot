# ateng-boot-notification 协同机器人通知底座 Starter

> 面向生产就绪的 Spring Boot 3 企业协同与多端 Webhook 机器人通知基础设施 Starter。坚持纯原生零臃肿依赖与显式强类型契约设计，提供钉钉、企业微信、飞书与通用 Webhook 统一推送、统一跨平台交互卡片智能自适应转译、一等公民图文链接模型、客户端主动滑动窗口频控与飞书突发令牌治理、主备渠道顺延故障转移（Cascade Failover）、受管异步线程池四维上下文透传、本地事务感知安全发信（`sendAfterCommit` 防幽灵通知）、多命名渠道动态切源以及声明式 `@NotifyAlert` 异常告警切面与三阶防风暴聚合抑制引擎。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **核心发信门面** | 统一通知协作操作门面 | `NotificationTemplate` | `ateng.boot.notification.default-channel`<br>`ateng.boot.notification.connect-timeout`<br>`ateng.boot.notification.read-timeout` | `send(message)` / `send(channel, message)`；`sendAsync(message)` / `sendAsync(channel, message)`；`sendAfterCommit(message)` / `sendAfterCommit(channel, message)`；`sendTemplate(template, vars)` / `sendTemplate(channel, template, vars)`；`getChannelRegistry()` / `getRateLimiter()` / `getProperties()` | `NotificationTemplateTest`<br>`SampleNotificationIntegrationTest` |
| **五阶消息模型** | 跨端统一消息领域抽象 | `NotificationMessage`<br>`TextMessage`<br>`MarkdownMessage`<br>`CardMessage`<br>`ImageMessage`<br>`LinkMessage`<br>`RawMessage` | - | 继承自 `NotificationMessage`，链式绑定 `channel(name)` 与 `mention(target)`；`TextMessage.of(content)`；`MarkdownMessage.of(title, content)`；`CardMessage`: `title(t)` / `content(c)` / `headerColor(color)` / `btnOrientation(o)` / `button(btn)` / `mainImageUrl(url)`；`ImageMessage`: `ofBase64(b64, md5)` / `fromFile(file)` / `fromPath(path)`（标配零字节卫语句防卫）；`LinkMessage.of(title, text, messageUrl, picUrl)`；`RawMessage.of(json)` | `NotificationMessageTest`<br>`LinkMessageTest`<br>`ImageMessageTest`<br>`SampleNotificationIntegrationTest` |
| **统一@人抽象** | 跨平台 @人 与提醒领域模型 | `MentionTarget` | - | `MentionTarget.all()` 触发群 `@所有人`；`MentionTarget.ofMobiles(String...)` 按手机号 `@成员`；`MentionTarget.ofUserIds(String...)` 按工号/OpenID `@成员`；内部维护无序去重 `LinkedHashSet` 并由平台适配器智能映射 | `NotificationMessageTest`<br>`SampleNotificationIntegrationTest` |
| **跨端卡片转译** | 交互卡片多平台自适应转译 | `CardButton`<br>`CardColor`<br>`CardButtonOrientation`<br>`NotificationPayloadBuilders` | - | `CardButton.of(text, url)` / `primary(text, url)` / `danger(text, url)`；`CardColor` 提供 11 色工业主题枚举（含飞书官方 `WATHET` 浅蓝）；`CardButtonOrientation`（`VERTICAL("0")`、`HORIZONTAL("1")`）映射钉钉 `btnOrientation`；各平台 PayloadBuilder 智能自适应转译为钉钉 ActionCard、企微 TemplateCard、飞书 Interactive Card v2 或通用 Webhook 载荷 | `DingTalkPayloadBuilderTest`<br>`WeComPayloadBuilderTest`<br>`FeishuPayloadBuilderTest`<br>`GenericPayloadBuilderTest`<br>`SampleNotificationIntegrationTest` |
| **图文链接模型** | 跨平台图文链接自适应转译 | `LinkMessage`<br>`DingTalkPayloadBuilder`<br>`WeComPayloadBuilder`<br>`FeishuPayloadBuilder` | - | 跨端智能自适应映射：钉钉原生 `link` 消息（`title`/`text`/`messageUrl`/`picUrl`）、企业微信原生 `news` 单图文（`articles`）以及飞书官方交互式图文卡片（`interactive` + 顶层图片 + 跳转操作按钮）与通用 Webhook JSON 载荷 | `LinkMessageTest`<br>`SampleNotificationIntegrationTest` |
| **企微格式清洗** | 企微 Markdown 表格降级清洗 | `WeComMarkdownSanitizer` | - | `sanitize(String markdown)`：识别企微官方 Markdown 不支持原生表格的缺陷，自动将 GFM 表格正则解构重排为结构化优雅引用块（`> **列名**: 值`），清洗不兼容标签，防御格式破坏 | `WeComMarkdownSanitizerTest`<br>`SampleNotificationIntegrationTest` |
| **渠道管理中心** | 线程安全多命名渠道注册中心 | `NotificationChannel`<br>`NotificationChannelRegistry` | `ateng.boot.notification.channels.*.platform`<br>`ateng.boot.notification.channels.*.webhook`<br>`ateng.boot.notification.channels.*.secret`<br>`ateng.boot.notification.channels.*.backup-channels` | `NotificationChannel`: 强类型配置模型，封装 webhook、secret、connectTimeout、readTimeout、permitsPerMinute、backupChannels；`NotificationChannelRegistry`: `register(channel)` / `unregister(name)` / `get(name)` / `size()` / `getAll()`，静态预热加动态 SPI 混合驱动 | `NotificationChannelRegistryTest`<br>`SampleNotificationIntegrationTest` |
| **主备故障转移** | 多级主备渠道顺延降级容灾 | `NotificationTemplate`<br>`isTransientException` | `ateng.boot.notification.channels.*.backup-channels` | 智能分类瞬态异常（网络超时、连接拒绝、HTTP 5xx）与致命异常（配置缺失、凭据非法、HTTP 4xx）；主渠道瞬态故障时自动顺延至备用渠道降级重试；全链路耗尽保留完整因果链抛出受控业务异常 | `NotificationTemplateTest`<br>`SampleNotificationIntegrationTest` |
| **动态切源上下文**| 线程级通知渠道动态路由上下文 | `NotificationContextHolder` | - | `getChannel()` / `setChannel(name)` / `clear()`；`runWithChannel(name, runnable)` 与 `callWithChannel(name, callable)` 支持上下文多层深度嵌套并在 `finally` 块中严格恢复前驱现场，防线程污染 | `NotificationContextHolderTest`<br>`SampleNotificationIntegrationTest` |
| **动态渠道供给** | 外部介质渠道懒加载扩展 SPI | `NotificationChannelProvider` | - | `loadChannel(String channelName)`：支持业务按需从数据库、配置中心等介质懒加载渠道并自动写入注册表缓存 | `NotificationChannelRegistryTest`<br>`SampleNotificationIntegrationTest` |
| **动态渠道解析** | 运行时环境与租户推导 SPI | `NotificationChannelResolver` | - | `resolveChannelName()`：支持根据当前多租户、灰度标签或组织架构上下文动态解析路由渠道名称，无侵入重定向发信目标 | `NotificationChannelRegistryTest` |
| **客户端滑窗限流**| 滑动窗口频控与微退避治理 | `NotificationRateLimiter`<br>`InMemoryNotificationRateLimiter` | `ateng.boot.notification.rate-limit.enabled`<br>`ateng.boot.notification.rate-limit.max-wait-duration`<br>`ateng.boot.notification.default-feishu-burst-permits`<br>`ateng.boot.notification.channels.*.permits-per-minute` | 契约 `tryAcquire(channel, maxWaitDuration)`；基线默认值：钉钉 20条/分、企微 20条/分、飞书 100条/分、通用 60条/分；飞书突发令牌桶弹性调优（`defaultFeishuBurstPermits` 默认 5）；令牌耗尽时执行微退避排队等待，超时 Fail-Fast 阻断网络外呼，坚决杜绝触发官方 10 分钟拉黑封禁 | `InMemoryNotificationRateLimiterTest`<br>`NotificationPropertiesTest` |
| **声明式告警切面**| AOP 异常告警拦截与全链路采集 | `@NotifyAlert`<br>`NotifyAlertAspect` | `ateng.boot.notification.alert.enabled`<br>`ateng.boot.notification.alert.default-channel`<br>`ateng.boot.notification.alert.suppress-window`<br>`ateng.boot.notification.alert.include-args`<br>`ateng.boot.notification.alert.desensitize-keys` | 标注于类或方法；自动采集 MDC `traceId`、用户 ID、Top 10 精简堆栈；基于敏感关键字对方法入参 JSON 实施掩码脱敏；支持 `ignoreExceptions` 白名单跳过与 `includeExceptions` 定向捕获 | `NotifyAlertAspectTest`<br>`SampleNotificationIntegrationTest` |
| **防风暴聚合抑制**| 三阶防告警风暴聚合抑制引擎 | `AlertStormSuppressor`<br>`AlertContext` | `ateng.boot.notification.alert.suppress-window` | 算法 $\text{MD5}(\text{Exception} + ":" + \text{RootCause} + ":" + \text{Method} + ":" + \text{Line})$ 无碰撞指纹归因；三阶抗洪：阶段一首发即时推送红色卡片，阶段二窗口期原子计数拦截，阶段三到期补发橙色聚合汇总卡片；在高并发 500+ 瞬时异常下平稳抗洪 | `AlertStormSuppressorTest`<br>`SampleNotificationIntegrationTest` |
| **受管异步发信** | 四维上下文无损透传异步发信 | `NotificationTemplate`<br>`notificationTaskExecutor` | `ateng.boot.notification.async.enabled`<br>`ateng.boot.notification.async.core-pool-size`<br>`ateng.boot.notification.async.max-pool-size`<br>`ateng.boot.notification.async.queue-capacity`<br>`ateng.boot.notification.async.keep-alive` | 接入 Core 受管线程池 `notificationTaskExecutor`；`sendAsync` 返回 `CompletableFuture<NotificationSendResult>`；依托任务装饰器全链路无损透传 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ | `AtengNotificationAutoConfigurationTest`<br>`SampleNotificationIntegrationTest` |
| **事务感知发信** | 事务提交后发信（防幽灵通知）| `NotificationTemplate`<br>`TransactionSynchronization` | - | `sendAfterCommit(message)` / `sendAfterCommit(channel, message)`：挂载 Spring 物理事务同步器，仅在数据库事务成功 commit 后外呼发信；事务回滚自动取消，彻底根除幽灵通知 | `NotificationTemplateTest`<br>`SampleNotificationIntegrationTest` |
| **加签安全认证** | 出站请求自动加签与权威转译 | `NotificationSignUtil`<br>`DingTalkPayloadBuilder` | - | `signDingTalk(secret, timestamp)` 遵循 RFC 3986 规范（`%20`、`%2A`、`~` 转译）；`signFeishu(secret, timestamp)`（Body HMAC-SHA256 Base64）；`signHmacSha256Hex(secret, content)` 支持通用 Webhook 加签头 | `NotificationSignUtilTest`<br>`DingTalkPayloadBuilderTest` |
| **机密审计脱敏** | 凭据与敏感字段 100% 审计脱敏 | `NotificationDesensitizeUtil`<br>`WeComPayloadBuilder` | - | 企微误配 `secret` 自动净化剔除（防 40058 异常）；`maskWebhook(url)` 自动掩码 Token/Key；`maskSecret(secret)` 打星号；日志与 `toString()` 中 100% 安全掩码，杜绝机密泄露至运维日志或外发报文 | `NotificationDesensitizeUtilTest`<br>`WeComPayloadBuilderTest` |
| **失败降级补偿** | 发信失败补偿处理器 SPI | `NotificationSendFailureHandler` | - | `onFailure(channel, message, throwable)`：在受管异步或事务后发信失败时触发，支持接入死信落库、报警调度或多通道降级 | `SampleNotificationSendFailureHandler`<br>`SampleNotificationIntegrationTest` |
| **动态模板渲染** | 模板解耦动态发信 SPI | `NotificationTemplateRenderer`<br>`SimpleNotificationTemplateRenderer` | - | `render(templateName, variables)`：默认内联占位符解析，支持无缝扩展替换为 Thymeleaf 或 FreeMarker 引擎 | `NotificationTemplateTest` |
| **驱动通信门面** | 平台通信驱动底层 SPI | `NotificationProvider`<br>`AbstractNotificationProvider` | - | 内置 `DingTalkNotificationProvider`、`WeComNotificationProvider`、`FeishuNotificationProvider`、`GenericWebhookNotificationProvider`，统一耗时度量与异常因果链传递 | `DingTalkNotificationProviderIntegrationTest`<br>`WeComNotificationProviderIntegrationTest` |
| **因果异常体系** | 语义化异常与统一业务错误码 | `NotificationException`<br>`NotificationResultCode` | - | 转译网络超时、签名失败、频控拦截为语义化业务异常并保留完整原始 `cause`；统一状态码 `NOTIFICATION_SEND_ERROR(5701)` 至 `NOTIFICATION_RATE_LIMIT_EXCEEDED(5704)`、`NOTIFICATION_CHANNEL_NOT_FOUND(5705)` 等 | `NotificationExceptionTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-notification` 是面向企业级生产环境的工业级群机器人协同与告警通知 Starter：
- **纯原生零臃肿**：基于 JDK 17 原生能力与 Spring 6 `RestClient` 构建，严禁引入第三方臃肿 SDK；
- **显式契约与解耦**：发信出参统一采用强类型 `NotificationSendResult`，Controller 传输边界 100% 严禁暴露 ORM 实体，统一使用专用 DTO/Request 与 `Result<T>`；
- **全链路追踪与无状态设计**：MDC `traceId` 深度透传至每次发信与告警卡片；受管线程池驱动，所有公共服务与单例保持严格无状态。

### 引入依赖
在业务模块的 `pom.xml` 中引入（版本由 `ateng-boot-bom` 统一纳管，免配 `<version>`）：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-notification</artifactId>
</dependency>
```

若需使用声明式 `@NotifyAlert` 异常告警切面拦截，需确保类路径包含 Spring AOP（Web 场景或 `spring-boot-starter-aop` 默认包含）：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

---

## 2. 最小起步体验 (Happy Path & Out-of-the-Box)

### 2.1 基础配置 (application.yml)
```yaml
ateng:
  boot:
    notification:
      enabled: true
      default-channel: ops-alert
      default-feishu-burst-permits: 5
      channels:
        # 企业微信机器人配置（企业微信官方 Webhook 机器人仅依赖 URL 中的 key 参数鉴权，无 secret 加签机制）
        ops-alert:
          platform: wecom
          webhook: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=YOUR_WECOM_KEY
          connect-timeout: 3s
          read-timeout: 5s
          permits-per-minute: 20
          backup-channels:
            - dingtalk-dev
            - feishu-ops
        # 钉钉机器人配置（支持安全密钥加签与 RFC 3986 权威编码）
        dingtalk-dev:
          platform: dingtalk
          webhook: https://oapi.dingtalk.com/robot/send?access_token=YOUR_DINGTALK_TOKEN
          secret: SEC_YOUR_DINGTALK_SECRET
          connect-timeout: 3s
          read-timeout: 5s
          permits-per-minute: 20
        # 飞书机器人配置（支持签名校验与突发令牌弹性治理）
        feishu-ops:
          platform: feishu
          webhook: https://open.feishu.cn/open-apis/bot/v2/hook/YOUR_FEISHU_TOKEN
          secret: SEC_YOUR_FEISHU_SECRET
          connect-timeout: 3s
          read-timeout: 5s
          permits-per-minute: 100
      rate-limit:
        enabled: true
        max-wait-duration: 2s
      alert:
        enabled: true
        default-channel: ops-alert
        suppress-window: 60s
        desensitize-keys:
          - password
          - secret
          - token
          - credential
          - idCard
          - mobile
      async:
        enabled: true
        core-pool-size: 2
        max-pool-size: 8
        queue-capacity: 200
        keep-alive: 60s
        thread-name-prefix: notif-async-
```

### 2.2 开箱即用三板斧代码

#### 第一斧：注入核心门面发送纯文本通知
```java
@Service
@RequiredArgsConstructor
public class AlertNotifyService {

    private final NotificationTemplate notificationTemplate;

    public void sendTextNotification() {
        TextMessage message = new TextMessage("【生产发布】ateng-boot v1.2.0 发布成功！");
        // 配置精准 @人 目标
        message.setMention(MentionTarget.ofMobiles("13800138000"));

        NotificationSendResult result = notificationTemplate.send(message);
        // 返回强类型结果
        System.out.println("发信状态: " + result.isSuccess() + ", 耗时: " + result.getCostDuration().toMillis() + "ms");
    }
}
```

#### 第二斧：跨端自适应交互卡片与横向排版（钉钉/企微/飞书多端自适应）
```java
public void sendCardNotification() {
    CardMessage card = new CardMessage();
    card.setTitle("集群高负载预警");
    card.setContent("节点 worker-01 CPU 使用率超过 **92%**，请运维团队及时跟进！");
    // 支持飞书官方浅蓝色 (WATHET) 或警示红色 (RED)
    card.setHeaderColor(CardColor.WATHET);
    // 支持按钮横向排列 (HORIZONTAL) 或纵向排列 (VERTICAL)
    card.setBtnOrientation(CardButtonOrientation.HORIZONTAL);
    card.button(CardButton.danger("立即排查", "https://monitor.example.com/nodes/worker-01"));

    // 推送至指定渠道（未传渠道名则自动路由至全局 default-channel）
    notificationTemplate.send("ops-alert", card);
}
```

#### 第三斧：声明式 `@NotifyAlert` 异常告警拦截与防风暴抑制
```java
@Service
public class OrderService {

    /**
     * 业务方法抛出未受控异常时自动捕获，提取 MDC traceId、Top 10 调用栈，
     * 自动对入参 password 字段打星号脱敏，并在 10 秒窗口内防风暴聚合通报
     */
    @NotifyAlert(
            channel = "ops-alert",
            title = "订单履约业务未捕获异常",
            suppressWindow = 10,
            includeArgs = true,
            ignoreExceptions = {IllegalArgumentException.class}
    )
    public void processOrder(OrderPayRequest request) {
        // 当发生空指针或网络超时异常时，自动向群机器人推送红色紧急告警卡片
        throw new NullPointerException("支付网关回调凭据缺失");
    }
}
```

#### 进阶场景：一等公民图文链接与本地图片防卫发信
```java
public void sendAdvancedNotification() {
    // 1. 图文链接发信（自动跨端自适应转译钉钉 link、企微 news 单图文与飞书 interactive 卡片）
    LinkMessage link = LinkMessage.of(
            "版本发布周报",
            "ateng-boot 完成协同通知官方 API 对齐与高可用容灾加固",
            "https://github.com/atengk/ateng-boot",
            "https://img.example.com/cover.png"
    );
    notificationTemplate.send("dingtalk-dev", link);

    // 2. 本地图片安全发信（前置零字节强拦截与路径存在性安全防卫）
    ImageMessage image = ImageMessage.fromPath(Path.of("/data/reports/chart.png"));
    notificationTemplate.send("feishu-ops", image);
}
```

---

## 3. 生产扩展与接管 (Customization & Extensibility)

### 3.1 扩展一：实现 `NotificationChannelProvider` 外部介质渠道供给 SPI
当应用需要支持在管理后台动态新增或修改通知机器人配置时，无需重启应用，实现 SPI 接口即可：

```java
@Component
@RequiredArgsConstructor
public class DatabaseNotificationChannelProvider implements NotificationChannelProvider {

    private final ChannelConfigMapper channelConfigMapper;

    @Override
    public NotificationChannel loadChannel(String channelName) {
        ChannelConfigDO config = channelConfigMapper.selectByName(channelName);
        if (config == null) {
            return null;
        }
        return NotificationChannel.builder()
                .name(config.getName())
                .platform(Platform.valueOf(config.getPlatform()))
                .webhook(config.getWebhookUrl())
                .secret(config.getSecret())
                .backupChannels(config.getBackupChannelsList())
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()))
                .permitsPerMinute(config.getPermitsPerMinute())
                .build();
    }
}
```

### 3.2 扩展二：实现 `NotificationChannelResolver` 动态切源推导 SPI
支持基于当前请求的用户上下文、多租户或灰度标识动态推导发信渠道：

```java
@Component
public class TenantAwareNotificationChannelResolver implements NotificationChannelResolver {

    @Override
    public String resolveChannelName() {
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            return "tenant-" + tenantId + "-robot";
        }
        return null; // 回退全局默认渠道
    }
}
```

### 3.3 扩展三：覆盖 `NotificationRateLimiter` 接入分布式 Redis 令牌桶
框架默认注入单机内存滑动窗口限流器 `InMemoryNotificationRateLimiter`。若应用在微服务集群多节点部署，可通过声明同名 Bean 平滑接管：

```java
@Configuration
public class CustomRateLimiterConfiguration {

    @Bean
    public NotificationRateLimiter notificationRateLimiter(StringRedisTemplate redisTemplate) {
        return new DistributedRedisNotificationRateLimiter(redisTemplate);
    }
}
```

### 3.4 扩展四：实现 `NotificationSendFailureHandler` 失败降级补偿 SPI
当事务提交后发信或受管异步发信因网络物理故障终态失败时，框架自动回调失败处理器：

```java
@Component
@Slf4j
public class CustomNotificationSendFailureHandler implements NotificationSendFailureHandler {

    @Override
    public void onFailure(NotificationChannel channel, NotificationMessage message, Throwable throwable) {
        String channelName = channel != null ? channel.getName() : "unknown";
        log.error("协同机器人发信终态失败，执行业务降级补偿 [channel={}, error={}]", channelName, throwable.getMessage());
        // 生产实践：写入失败重试死信表，触发定时补偿任务，或调用备用 SMS/邮件 通道
    }
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 客户端滑动窗口频控与微退避超时熔断
- **官方封禁防御**：钉钉官方限制同一机器人每分钟最多 20 条，超额直接触发 **封号 10 分钟** 处罚。
- **算法模型**：`InMemoryNotificationRateLimiter` 按渠道隔离维护滑动窗口。当令牌不足时，在 `maxWaitDuration`（默认 2 秒）内执行纳秒级微退避排队；
- **熔断机制**：排队超时仍无法获得配额时，**立即抛出 `NotificationRateLimitException` 快速失败**，坚决不向平台强推报文，有效保护群机器人凭据资产。
- **飞书突发令牌弹性**：通过 `default-feishu-burst-permits`（默认 5）支持飞书机器人突发并发容量调节，防范高并发突发警报被本地限流硬熔断。

### 4.2 本地事务感知发信（`sendAfterCommit`）防幽灵通知
- **痛点**：若在 `@Transactional` 方法内直接网络外呼，万一下游数据库在物理 `commit` 时发生锁超时或唯一键冲突回滚，通知却已提前推送，造成不可逆的业务误报；
- **安全保障**：`notificationTemplate.sendAfterCommit(message)` 严格挂载 Spring `TransactionSynchronization`。当无活动事务时就地发送；在事务中仅在 `afterCommit` 钩子中触发网络调用；若事务回滚则自动销毁发送钩子，100% 根除幽灵通知。

### 4.3 企微 Markdown 表格安全降级清洗
- **平台缺陷**：企业微信 Webhook 机器人的 Markdown 解析引擎不支持 GFM 管道表格语法（`| a | b |`），直接推送会导致表格符号严重错位与排版破损；
- **清洗机制**：`WeComMarkdownSanitizer` 在发送前自动拦截并提取表格头与行数据，平滑转译为美观的 Markdown 引用块（`> **列名**: 值`），确保多端一致可读性。

### 4.4 三阶防告警风暴聚合抑制算法
面对下游集群故障时的瞬时海量异常，`AlertStormSuppressor` 实施严格的三阶抗洪调度：

```
[业务异常抛出] ──> [计算无碰撞指纹] ──> [检查活动抑制窗口]
                                      │
            ┌─────────────────────────┴─────────────────────────┐
       [无活动窗口 (首发)]                                [已有活动窗口 (重复)]
            │                                                   │
   [阶段一：立即外呼推送]                               [阶段二：静默拦截]
   [推送红色紧急告警卡片]                               [AtomicInteger 原子累加]
   [注册到期汇总调度任务]                               [零网络外呼，杜绝刷屏]
            │                                                   │
            └─────────────────────────┬─────────────────────────┘
                                      │
                              [窗口到期调度触发]
                                      │
                                [count > 0 ?]
                               ┌──────┴──────┐
                             [是]           [否]
                              │              │
                   [阶段三：补发汇总]   [平滑关闭窗口]
                   [推送橙色聚合卡片]   [无冗余消息]
```

- **确定性指纹计算**：
  $$\text{Fingerprint} = \text{MD5}(\text{ExceptionClassName} + ":" + \text{RootCauseClassName} + ":" + \text{MethodSignature} + ":" + \text{LineNumber})$$
  精准归纳同源错误，不同参数但同一错误行号归纳为相同指纹。
- **极限抗洪能力**：经多线程单测全真验证，在 **500+ 并发瞬时异常倾泻下，首发红色卡片严格仅 1 次，被拦截静默计数严格为 499 次，窗口期满补发橙色汇总卡片严格仅 1 次**。

### 4.5 多级主备渠道顺延故障转移 (Cascade Failover)
- **多级容灾契约**：渠道通过 `backup-channels` 配置指定备用渠道有序链表（例如：企业微信主告警挂掉时，顺延降级至钉钉与飞书）；
- **瞬态与致命异常智能分类 (`isTransientException`)**：
  - **瞬态网络与服务端故障**：网络连接超时、Socket 读超时、连接拒绝、HTTP 5xx（如 500、502、503、504 服务不可用）自动触发顺延重试；
  - **致命配置与凭据错误**：HTTP 4xx 业务鉴权失败、渠道未注册、参数校验错误立即阻断熔断，严禁污染备用渠道；
- **全链路保真与异常审计**：若全部主备渠道依次耗尽依然失败，保留完整因果链抛出受控 `NotificationException`，绝不静默吞咽异常。

### 4.6 敏感信息审计脱敏与“零明文不变量”
- **出站密钥脱敏**：出站 Webhook URL 中的 Token、Secret 在写入日志与执行 `toString()` 时，由 `NotificationDesensitizeUtil` 自动保留前 6 位与后 4 位，中间全部打星号掩码；
- **告警入参脱敏**：`NotifyAlertAspect` 在序列化业务方法入参时，依据 `desensitize-keys` 配置（默认包含 `password`, `secret`, `token`, `credential`, `idcard`, `mobile` 等），全自动对 JSON 键值执行掩码脱敏（`"******"`），彻底消除明文凭证推至公网聊天群的重大安全隐患；
- **企微 Secret 纯净脱敏**：彻底废除企微 Webhook 拼接 `secret` 签名参数，规避官方 `40058 (bad parameter)` 拒绝服务；
- **钉钉 RFC 3986 权威签名转译**：加签 URL 百分号编码严格转译字符（`%20`、`%2A`、`~`），规避极端边界下 `310000 sign not match`。

---

## 5. 常见问题排查与避坑指南 (Troubleshooting)

### Q1: 钉钉/飞书发信报错“签名校验失败 (5703)”？
- **检查要点**：
  1. 钉钉安全设置中若勾选了“加签”，必须在 `secret` 属性中填入以 `SEC` 开头的签名密钥（严禁将 AccessToken 当做 Secret）；
  2. 飞书安全设置中若勾选了“签名校验”，必须将飞书后台生成的密钥填入 `secret`；
  3. 检查服务器系统时间与网络标准时间是否同步（时间漂移超过 1 小时会导致钉钉时间戳签名失效）。

### Q2: 提示“未找到指定的通知渠道配置 (5705)”？
- **检查要点**：
  1. YAML 配置中渠道名称必须与代码调用的 `channel` 字符串逐字匹配；
  2. 配置中的属性名必须为 `webhook`（如 `ateng.boot.notification.channels.<name>.webhook`），配置了有效的 Webhook 地址该渠道才会被装配至注册表；
  3. 若通过 `NotificationContextHolder` 切源，确认切源名称正确并在同一个线程内调用。

### Q3: 频繁触发“通知发送频控超限 (5704)”？
- **排查建议**：
  1. 检查是否存在瞬时批量高并发调用，建议在业务上使用 `AlertStormSuppressor` 或批量汇总后再发信；
  2. 适当放宽 `ateng.boot.notification.rate-limit.max-wait-duration` 等待时间（如调大至 `3s`）；
  3. 若为飞书平台，可根据实际配额上调 `ateng.boot.notification.default-feishu-burst-permits`（如设置为 `10`）；
  4. 若为内部私有通用 Webhook，可根据自建服务承载能力调大配置中的 `permits-per-minute`。
