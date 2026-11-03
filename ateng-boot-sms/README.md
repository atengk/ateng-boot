# ateng-boot-sms 基础设施 Starter

> 企业级多渠道短信通信与高韧性治理 Starter，依托 Spring 6 RestClient 与 JDK 17 HMAC 原生实现主流公有云短信协议（阿里云 POP RPC、腾讯云 TC3-HMAC-SHA256、华为云 WSSE 规范，纯原生零第三方 SDK 依赖）；提供统一操作门面 `SmsTemplate` 与验证码全生命周期治理门面 `SmsVerifyCodeTemplate`、流式 `SmsParam` 参数推导、大规模营销群发透明切片（`BatchSliceUtil`）与受管并发聚合、防盗刷三重安全网（单号滑动窗口频控 + 出站黑名单拦截 + 单日总额度硬熔断 `DailyBudgetBreaker`）、多命名渠道注册中心与动态切源、多租户寻址与自动容灾降级链（Failover）、本地物理事务提交感知发信（`sendAfterCommit` 防幽灵短信）与失败补偿 SPI、统一异步送达回执与上行退订 Webhook 事件网关及 100% 离线自洽 Mock 测试沙箱。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.sms.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **基础发信门面** | 单条短信同步投递 | `SmsTemplate` | `enabled`<br>`default-channel`<br>`channels.*` | `SmsResult send(SmsRequest request)`；三级级联寻址（显式渠道 $\rightarrow$ 多租户上下文 $\rightarrow$ 默认渠道），前置黑名单与预算熔断防护，顺延容灾降级 | `SmsTemplateTest`<br>`SampleSmsIntegrationTest` |
| **异步非阻塞** | 受管异步发信与四维上下文透传 | `SmsTemplate`<br>`AsyncContext` | `enabled` | `CompletableFuture<SmsResult> sendAsync(SmsRequest request)`；绑定 Core 受管异步线程池，全链路透传 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 快照 | `SmsTemplateTest`<br>`SampleSmsIntegrationTest` |
| **事务感知防幽灵** | 本地物理事务提交感知发信 | `SmsTemplate`<br>`TransactionSynchronization` | `enabled` | `void sendAfterCommit(SmsRequest request)`；挂载 Spring 本地事务同步器，仅在物理事务成功 COMMIT 后真实投递，事务 ROLLBACK 时自动取消杜绝幽灵短信；无事务环境安全同步降级 | `SmsTransactionalAndCallbackTest`<br>`SampleSmsIntegrationTest` |
| **营销批量切片** | 大规模群发透明分批与并发聚合 | `SmsTemplate`<br>`BatchSliceUtil` | `enabled` | `BatchSmsResult sendBatch(BatchSmsRequest request)`；按云厂商阶梯上限（阿里 1000/华为 500/腾讯 200）底层透明分批切片，经由受管线程池并发投递并聚合结果 | `SmsBatchAndGuardTest`<br>`SampleSmsIntegrationTest` |
| **验证码全生命周期** | 验证码生成、发信、核验与原子自毁 | `SmsVerifyCodeTemplate`<br>`SmsVerifyCodeRepository` | `verify-code.length`<br>`verify-code.ttl`<br>`verify-code.resend-interval`<br>`verify-code.max-retry-attempts`<br>`verify-code.char-type` | `String generateAndSend(phone, bizType)` / `boolean verify(phone, code, bizType)` / `boolean check(phone, code, bizType)` / `void invalidate(phone, bizType)`；成功核验立即物理销毁防重放，连续输错扣减重试次数超限自动冻结 | `SmsVerifyCodeTemplateTest`<br>`SampleSmsIntegrationTest` |
| **滑动窗口频控** | 客户端单号滑动窗口频控防刷 | `SmsRateLimiter`<br>`SmsVerifyCodeTemplate` | `rate-limit.per-minute`<br>`rate-limit.per-hour`<br>`rate-limit.per-day` | `void checkRateLimit(phone)`；多阶梯滑动窗口频控算法，拦截高频发信轰炸；自适应弱依赖 Redis，无 Redis 自动保底纯内存滑动窗口自洽 | `SmsVerifyCodeTemplateTest`<br>`SampleSmsIntegrationTest` |
| **出站黑名单拦截** | 出站黑名单前置静默熔断拦截 | `SmsBlacklistProvider`<br>`SmsTemplate` | `guard.blacklist-enabled`<br>`guard.blacklist` | `boolean isBlacklisted(phone)` / `String getReason(phone)`；发信前置静默拦截（返回 `BLOCKED`，0 发信），杜绝向高危/恶意号码调用公网 API 扣费 | `SmsBatchAndGuardTest`<br>`SampleSmsIntegrationTest` |
| **单日配额硬熔断** | 全系统成本控制与单日总额度熔断 | `DailyBudgetBreaker`<br>`SmsBudgetExceededEvent` | `guard.daily-max-quota` | `void checkAndIncrement(count)`；超限触发 `SmsBudgetExceededException` 阻断发信，向容器广播 `SmsBudgetExceededEvent`，可联动企业协同机器人高危预警 | `SmsBatchAndGuardTest`<br>`SampleSmsIntegrationTest` |
| **多渠道动态注册** | 命名渠道注册中心与热插拔管理 | `SmsChannelRegistry`<br>`SmsContextHolder` | `channels.*` | `register(SmsChannel)` / `unregister(channelName)` / `get(channelName)`；支持运行期动态接入、修改或注销渠道；`SmsContextHolder.setChannel(name)` 线程级动态切源 | `SmsChannelRegistryTest`<br>`SampleSmsIntegrationTest` |
| **多租户动态寻址** | 基于多租户上下文的渠道解析 SPI | `SmsChannelResolver`<br>`TenantSmsChannelResolver` | - | `SmsChannel resolve(SmsRequest)`；支持接入层实现 SPI，依据租户 ID、业务场景或地域特征动态解析匹配的专属短信渠道 | `SmsChannelRegistryTest`<br>`SampleSmsIntegrationTest` |
| **顺延容灾降级** | 高可用通道故障自动转移降级 | `SmsFailoverChain`<br>`SmsChannel` | `channels.*.failover-channel` | 遇网络超时、欠费或云厂商服务端异常时，自动顺延切换至备用渠道重试；防环路探测防死循环 | `SmsChannelRegistryTest`<br>`SampleSmsIntegrationTest` |
| **失败补偿扩展** | 发信失败后置兜底与补偿 SPI | `SmsSendFailureHandler`<br>`LoggingSmsSendFailureHandler` | - | `void handle(SmsRequest, SmsChannel, Exception)`；渠道顺延容灾全部失败后触发兜底回调，默认打印脱敏告警，支持扩展持久化死信表或告警通知 | `SmsTransactionalAndCallbackTest`<br>`SampleSmsIntegrationTest` |
| **统一回调网关** | 运营商异步送达回执与上行短信 Webhook | `SmsCallbackController`<br>`SmsDeliveryReportEvent`<br>`SmsMoMessageEvent` | `callback.enabled`<br>`callback.path-prefix` | 统一入站路由 `${path-prefix}/{channel}`，自动识别转译阿里、腾讯、华为格式报文，广播 `SmsDeliveryReportEvent` 与 `SmsMoMessageEvent` 并响应云厂商 ACK | `SmsTransactionalAndCallbackTest`<br>`SampleSmsIntegrationTest` |
| **原生云厂商驱动** | 阿里云 POP RPC 原生协议驱动 | `AliyunSmsProvider` | `channels.*.supplier=ALIYUN` | 纯原生规范化查询串 + CanonicalizedQueryString + HMAC-SHA1 签名与 POP RPC 协议 | `AliyunSmsProviderTest` |
| **原生云厂商驱动** | 腾讯云 TC3-HMAC-SHA256 驱动 | `TencentSmsProvider` | `channels.*.supplier=TENCENT` | 纯原生 TC3-HMAC-SHA256 规范化请求头、Timestamp、CredentialScope 与 RestClient 调用 | `TencentSmsProviderTest` |
| **原生云厂商驱动** | 华为云 WSSE 原生规范驱动 | `HuaweiSmsProvider` | `channels.*.supplier=HUAWEI` | 纯原生 WSSE 标头认证规范（UsernameToken、Nonce、Created、PasswordDigest SHA256 Base64） | `HuaweiSmsProviderTest` |
| **离线测试沙箱** | 纯内存离线测试 Mock 驱动 | `MockSmsProvider` | `channels.*.supplier=MOCK` | 纯内存记录发信请求并打印结构化脱敏审计日志，白板与 CI/CD 100% 离线自洽 | `MockSmsProviderTest`<br>`SampleSmsIntegrationTest` |
| **参数流式推导** | 流式参数与异构占位符自适应推导 | `SmsParam` | - | `SmsParam.of("k", "v").and("k2", "v2")`；自动推导阿里云 JSON 键值对、腾讯云位置参数序号数组、华为云动态变量列表 | `SmsParamTest` |
| **因果异常体系** | 语义化四阶异常与错误码 | `SmsSendException`<br>`SmsVerifyCodeException`<br>`SmsBudgetExceededException`<br>`SmsResultCode` | - | 完整保留原始因果链（`cause`）；错误码枚举提供 `SMS_SEND_FAILED`、`VERIFY_CODE_INVALID`、`DAILY_BUDGET_EXCEEDED` 等规范编码 | `SmsTransactionalAndCallbackTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-sms` 是面向生产就绪的工业级多渠道短信通信基础设施 Starter：
- **纯原生零第三方 SDK**：100% 依托 Spring 6 `RestClient` 与 JDK 17 `Mac` (HMAC)，杜绝引入各云厂商笨重且存在依赖冲突的官方 SDK；
- **防盗刷与成本安全网**：内置出站黑名单前置拦截、客户端滑动窗口频控与单日系统成本预算硬熔断；
- **传输与持久绝对解耦**：发信领域模型强类型封装（`SmsRequest` / `BatchSmsRequest`），Controller 边界严格使用专用 DTO 与强类型 `Result<T>` 显式响应契约；
- **无状态安全与内存真明文**：业务层与对象内存保持 100% 原始真实明文，日志打印统一经由 `DesensitizeUtil.mobilePhone` 脱敏；单例服务保持严格无状态。

### 引入依赖
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-sms</artifactId>
</dependency>
```

若需开启分布式 Redis 验证码仓储与全局滑动窗口防刷，可按需引入（模块自适应弱依赖，未引入时自动平滑回退至纯内存自洽实现）：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

---

## 2. 最小起步体验 (Happy Path & Out-of-the-Box)

### 2.1 生产级配置全景 (application.yml)
```yaml
ateng:
  boot:
    sms:
      enabled: true
      default-channel: mock                     # 默认发信渠道
      channels:
        mock:
          supplier: MOCK
          sign-name: 官方测试
        aliyun-notice:
          supplier: ALIYUN
          access-key-id: YOUR_ALIYUN_AK
          access-key-secret: YOUR_ALIYUN_SK
          sign-name: 阿里云测试
          failover-channel: tencent-backup      # 阿里云异常自动顺延容灾至腾讯云
          timeout: 5s
        tencent-backup:
          supplier: TENCENT
          access-key-id: YOUR_TENCENT_SECRET_ID
          access-key-secret: YOUR_TENCENT_SECRET_KEY
          app-id: "1400000001"
          sign-name: 腾讯云测试
          timeout: 5s
        huawei-marketing:
          supplier: HUAWEI
          access-key-id: YOUR_HUAWEI_APP_KEY
          access-key-secret: YOUR_HUAWEI_APP_SECRET
          app-id: "88200000001"
          endpoint: https://smsapi.cn-north-4.myhuaweicloud.com:443
          sign-name: 华为云测试
      verify-code:
        length: 6
        ttl: 5m                                 # 强类型 Duration，验证码有效时长
        resend-interval: 60s                    # 重发冷却时间
        max-retry-attempts: 5                   # 输错冻结上限
        char-type: NUMERIC                      # 纯数字
      rate-limit:
        per-minute: 1                           # 客户端单号 1 分钟上限 1 条
        per-hour: 5                             # 客户端单号 1 小时上限 5 条
        per-day: 10                             # 客户端单号 1 天上限 10 条
      guard:
        daily-max-quota: 1000                   # 单日全系统发信总配额硬熔断
        blacklist-enabled: true                 # 出站黑名单拦截
        blacklist:
          - "13800000000"
          - "13900000000"
      callback:
        enabled: true                           # 开启统一入站回执与上行短信 Webhook
        path-prefix: /ateng-sms/callback
```

### 2.2 开箱发信“三板斧”示例

#### 第一斧：发送单条短信
```java
@Service
@RequiredArgsConstructor
public class UserService {

    private final SmsTemplate smsTemplate;

    public void sendWelcomeNotice(String phone, String username) {
        // 构建发信请求（流式参数自适应推导）
        SmsRequest request = SmsRequest.of(phone, "SMS_WELCOME_001", SmsParam.of("username", username));

        // 同步发送（自动根据默认渠道路由与顺延容灾）
        SmsResult result = smsTemplate.send(request);
        if (!result.isSuccess()) {
            throw new BusinessException("短信发送失败: " + result.getRawMessage());
        }
    }
}
```

#### 第二斧：受管异步发送
```java
public CompletableFuture<SmsResult> sendAsyncNotice(String phone, String orderNo) {
    SmsRequest request = SmsRequest.of(phone, "SMS_ORDER_PAID", SmsParam.of("orderNo", orderNo));
    // 四维上下文自动透传，非阻塞响应
    return smsTemplate.sendAsync(request);
}
```

#### 第三斧：本地物理事务提交感知发信（防幽灵短信）
```java
@Transactional(rollbackFor = Exception.class)
public void registerUserWithTransaction(UserRegisterDTO dto) {
    // 1. 业务数据持久化
    userRepository.save(dto);

    // 2. 注册事务提交后投递钩子（事务 COMMIT 真实发信，ROLLBACK 自动取消）
    SmsRequest request = SmsRequest.of(dto.getPhone(), "SMS_REG_SUCCESS", SmsParam.of("name", dto.getName()));
    smsTemplate.sendAfterCommit(request);
}
```

---

## 3. 生产扩展接管 (Customization & Extensibility)

### 3.1 扩展自定义云厂商驱动 SPI (`SmsProvider`)
当企业接入私有运营商专线或新兴云厂商时，可实现 `SmsProvider` 接口完成无侵入接入：
```java
@Component
public class CustomTelecomSmsProvider implements SmsProvider {

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.GENERIC;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        // 依托 Spring 6 RestClient 执行专网通信
        // 成功构建 SmsResult.success(...)，失败构建 SmsResult.failure(...)
        return SmsResult.builder()
                .success(true)
                .channel(channel.getName())
                .supplier(getSupplier())
                .bizId("CUSTOM_BIZ_" + System.currentTimeMillis())
                .chargeCount(1)
                .build();
    }
}
```

### 3.2 动态切源与多租户寻址 (`SmsContextHolder` & `SmsChannelResolver`)
```java
// 1. 手动线程级切源
SmsContextHolder.setChannel("huawei-marketing");
try {
    smsTemplate.send(request);
} finally {
    SmsContextHolder.clear();
}

// 2. 自定义多租户寻址解析器
@Component
public class CustomTenantSmsChannelResolver implements SmsChannelResolver {

    @Override
    public SmsChannel resolve(SmsRequest request) {
        String tenantId = TenantContextHolder.getTenantId();
        // 根据不同租户分配独立短信通道
        return channelRegistry.get("channel-tenant-" + tenantId);
    }
}
```

### 3.3 失败后置补偿处理器 SPI (`SmsSendFailureHandler`)
```java
@Component
@Slf4j
public class DeadLetterSmsFailureHandler implements SmsSendFailureHandler {

    @Override
    public void handle(SmsRequest request, SmsChannel targetChannel, Exception cause) {
        log.error("[SmsFailureHandler] 短信全部容灾通道均告失败，触发落库死信兜底 -> 号码: {}, 模板: {}, 错误: {}",
                DesensitizeUtil.mobilePhone(request.getPhone()), request.getTemplateId(), cause.getMessage());
        // 落地持久化至死信对账表或推送企业微信/钉钉告警
    }
}
```

### 3.4 监听短信异步回执与上行退订事件
```java
@Component
@Slf4j
public class SmsEventListener {

    /**
     * 监听运营商送达回执报告
     */
    @EventListener
    public void onDeliveryReport(SmsDeliveryReportEvent event) {
        log.info("[回执监听] 接收短信回执 -> 渠道: {}, 号码: {}, 状态: {}, 结果码: {}",
                event.getChannel(), DesensitizeUtil.mobilePhone(event.getPhone()),
                event.isSuccess() ? "送达成功" : "送达失败", event.getResultCode());
    }

    /**
     * 监听用户上行回复（如退订短信 TD）
     */
    @EventListener
    public void onMoMessage(SmsMoMessageEvent event) {
        log.info("[上行监听] 收到用户上行回复 -> 渠道: {}, 号码: {}, 内容: {}",
                event.getChannel(), DesensitizeUtil.mobilePhone(event.getPhone()), event.getContent());
        if ("TD".equalsIgnoreCase(event.getContent())) {
            // 自动将用户手机号加入退订免打扰列表
        }
    }

    /**
     * 监听单日总预算超限硬熔断事件
     */
    @EventListener
    public void onBudgetExceeded(SmsBudgetExceededEvent event) {
        log.error("[成本熔断] 触发短信单日成本硬熔断！当前发信量: {}, 限额: {}",
                event.getCurrentSent(), event.getDailyQuota());
        // 发送群机器人高危告警
    }
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 纯原生零第三方 SDK 与安全认证
- **阿里云 POP RPC**：原生计算规范化查询串（`CanonicalizedQueryString`），经由 HMAC-SHA1 Base64 计算签名，直接通过 `RestClient` 发起 `GET/POST` 请求，彻底摆脱 `aliyun-java-sdk-core`；
- **腾讯云 TC3-HMAC-SHA256**：原生拼接 `CanonicalRequest` 与 `StringToSign`，多级派生签名密钥，挂载 `Authorization: TC3-HMAC-SHA256 ...` 标头，取缔 `tencentcloud-sdk-java`；
- **华为云 WSSE 认证规范**：原生生成 `Nonce` 与 ISO-8601 `Created` 时间戳，Base64 计算 `PasswordDigest = Base64(SHA256(Nonce + Created + Password))`，挂载 `X-WSSE` 标头。

### 4.2 本地事务感知发信（`sendAfterCommit`）防幽灵短信机制
在电商下单、充值对账或密码修改业务中，如果发信动作与数据库事务割裂，事务回滚时短信却已无法撤回。
`SmsTemplate.sendAfterCommit` 依托 `TransactionSynchronizationManager`：
1. **活动事务检测**：探测到当前线程处于 Spring 本地物理事务中时，仅注册 `afterCommit` 钩子；
2. **提交触发**：仅当数据库物理事务成功 `COMMIT` 后，触发真实发信；
3. **回滚自动取消**：若业务抛出异常导致事务 `ROLLBACK`，同步器自动销毁，发信请求被静默取消，彻底防御幽灵短信；
4. **无事务保底**：无事务上下文时平滑降级为直接同步发送。

### 4.3 出站防盗刷三重安全网
1. **滑动窗口频控**：`SmsRateLimiter` 默认限制单号 1 分钟 1 条、1 小时 5 条、1 天 10 条，从源头封堵客户端恶意轰炸；
2. **出站黑名单静默拦截**：`SmsBlacklistProvider` 识别黑名单手机号，执行前置拦截并返回 `BLOCKED` 状态（不调用底层云 API、不扣费、不抛出业务异常），防止攻击者试探系统响应；
3. **单日成本预算硬熔断**：`DailyBudgetBreaker` 记录单日全系统发信总量，达到 `daily-max-quota` 时立即熔断阻断发信并广播 `SmsBudgetExceededEvent`，防止因漏洞导致单日产生巨额通信账单。

### 4.4 大规模营销群发透明切片与并发聚合
各云厂商对批量单次号码数量存在严格限制：
- 阿里云单次上限 1000 个号码；
- 华为云单次上限 500 个号码；
- 腾讯云单次上限 200 个号码。

调用 `smsTemplate.sendBatch(request)` 时：
- 底层联动 Core 模块 `BatchSliceUtil`，根据目标渠道的云厂商类型自动执行切片；
- 结合受管异步线程池受控并发投递各个批次，失败批次顺延容灾渠道重试；
- 最终汇聚所有批次为 `BatchSmsResult`，包含总数、成功数、失败数、黑名单拦截数与各批次明细。

---

## 5. 物理排版与代码全真验证 (Evergreen & Zero-Glitch)

本项目所有示例代码、配置片段均来自已 100% 跑通的单测与 `ateng-boot-sample-sms` 场景样例工程。

### 验证命令
```bash
# 执行短信 Starter 与场景样例工程全套单元与集成测试
mvn test -pl ateng-boot-sms,ateng-boot-samples/ateng-boot-sample-sms -am -T 1C -nsu -B
```
控制台构建结果均保持 100% 通过（BUILD SUCCESS），具备工业级生产就绪品质。
