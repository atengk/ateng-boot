# ateng-boot-sample-sms 短信通信自验工程

> 专注于企业级多渠道短信通信、纯原生零第三方 SDK 驱动体系、统一发信门面与验证码治理、大规模营销群发透明切片、滑动窗口防刷与黑名单防盗刷、本地事务提交感知（`sendAfterCommit`）、统一 Webhook 回调转译及双模公有云真实联调。

---

## 1. 场景痛点与功能定位

在企业级现代应用与分布式架构中，短信作为用户注册核验、异地登录、找回密码、业务动账通知及精准营销的核心触达通道，具有不可替代的重要性。然而传统短信整合方案往往面临诸多严峻痛点：
- **SDK 依赖爆炸与版本冲突**：传统集成方式依赖各云厂商（阿里云、腾讯云、华为云等）官方 SDK，引入大量旧版 HTTP 客户端、臃肿第三方工具包与隐形依赖漏洞，严重污染项目依赖树；
- **发信与业务物理事务脱钩**：在本地数据库事务尚未提交或发生异常回滚时，外围短信已提前发出，引发用户困扰与经济损失的“幽灵短信”事故；
- **盗刷与成本雪崩风险**：接口缺乏客户端滑动窗口频控与黑名单前置拦截，面对接口被恶意刷量容易迅速耗尽企业单日通信预算甚至引发百万级扣费事故；
- **批量群发超限与乱序并发**：各云厂商对批量单次上限要求不一（阿里 1000、华为 500、腾讯 200），上层业务代码被迫侵入切片分页逻辑；
- **回执与退订协议割裂**：各云厂商异步送达回执与用户上行回复（如“TD”退订）报文结构异构，上层监听代码难以复用；
- **离线研发无自洽沙箱**：本地脱网开发或 CI/CD 单元测试时过度依赖公网真实短信服务，极易因网络波动、配额耗尽导致测试失败。

`ateng-boot-sample-sms` 作为 1:1 对齐 `ateng-boot-sms` 的独立场景沙箱，具备以下工业级特性：
- **100% 离线自洽测试**：内置 `MockSmsProvider` 内存驱动沙箱，离线自洽毫秒级测试，完全无需外部物理公网网络；
- **显式契约与第一道防线**：Controller 统一声明强类型 `Result<T>` 返回契约，入参专用 DTO 挂载 JSR-303 格式校验 Fail-Fast；
- **本地事务感知安全网**：`sendAfterCommit` 严格挂载 Spring 事务同步器，仅在物理提交后发信，事务回滚自动取消杜绝幽灵短信；
- **防盗刷三重安全网**：滑动窗口限流频控、出站黑名单前置静默拦截、单日总配额硬熔断器（`DailyBudgetBreaker`）与 `SmsBudgetExceededEvent` 告警；
- **超大营销透明分批切片**：集成 Core 模块 `BatchSliceUtil`，按厂商阶梯上限自动切片，经由受管线程池受控并发投递并聚合结果；
- **统一 Webhook 回调转译**：统一接收各厂商异步送达报告与 MO 上行短信，自动标准化为 Spring 领域事件分发；
- **双模自适应架构**：默认模式 100% 离线自洽；激活 `local` Profile 时直连本地真实公有云（阿里云/腾讯云/华为云）执行真实发信验证。

---

## 2. 演示场景清单

| 演示场景 | 核心门面 / 注解 / SPI | 业务类与端点 | 核心特性与行为 |
| :--- | :--- | :--- | :--- |
| **单条短信同步发信** | `SmsTemplate.send` | `POST /api/sms/send` | 统一 `Result<SmsResult>` 契约，自动级联路由、手机号脱敏与结果封装 |
| **JSR-303 参数校验** | `@Valid` + `@Pattern` | `SendSmsRequest` | Controller 传输边界第一道防线，手机号与模板不合规直接 Fail-Fast |
| **受管异步非阻塞发信** | `SmsTemplate.sendAsync` | `POST /api/sms/send-async` | `CompletableFuture<SmsResult>` 异步响应，Core 受管线程池与四维上下文透传 |
| **本地事务提交感知发信** | `SmsTemplate.sendAfterCommit` | `POST /api/sms/send-tx` | 严格绑定 Spring 本地事务提交，事务回滚时自动安全取消，彻底防幽灵短信 |
| **大规模营销透明切片** | `SmsTemplate.sendBatch` | `POST /api/sms/batch` | 按厂商阶梯上限（阿里1000/华为500/腾讯200）透明分批切片，并发投递并聚合结果 |
| **验证码全生命周期治理** | `SmsVerifyCodeTemplate` | `POST /api/sms/verify-code/*` | 验证码生成、限流发信、输错重试扣减、正确核验、原子自毁防重放 |
| **滑动窗口频控防刷** | `SmsVerifyCodeTemplate` | `POST /api/sms/verify-code/send` | 基于滑动窗口的单号 1分钟/1小时 频控拦截，防止接口被高频恶意轰炸 |
| **出站黑名单静默拦截** | `SmsBlacklistProvider` | `POST /api/sms/send` | 命中出站黑名单手机号静默拦截（返回 `BLOCKED`），0 发信防盗刷 |
| **统一 Webhook 回调网关** | `SmsCallbackController` | `POST /ateng-sms/callback/{channel}` | 统一入站端点，自动转译各厂商送达报告与上行短信为标准 Spring 领域事件 |
| **全生命周期领域事件** | `@EventListener` | `SampleSmsEventListener` | 监听 `SmsDeliveryReportEvent`、`SmsMoMessageEvent` 与 `SmsBudgetExceededEvent` |
| **发信失败后置补偿** | `SmsSendFailureHandler` | `SampleSmsFailureHandler` | 容灾降级后最终失败触发扩展补偿 SPI，支持持久化死信表或企业微信告警 |
| **渠道脱敏视图与动态注册** | `SmsChannelRegistry` | `GET /api/sms/channel/list`<br>`POST /api/sms/channel/dynamic` | 渠道信息脱敏视图 `SmsChannelVO`（绝不外泄秘钥），`DynamicChannelRegisterRequest` 动态热插拔 |
| **双模公有云真实联调** | `local` Profile | `SampleSmsIntegrationTest` | 单测试套件双模自适应，配置 `application-local.yml` 真实凭证后一键全真贯通三大公有云发信 |

---

## 3. 本地自验与自动化测试

### 3.1 执行场景端到端自动化测试（离线自洽推荐）
```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-sms -am -T 1C -nsu -B
```

### 3.2 启动本地服务自验
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-sms spring-boot:run
```

#### 场景一：发送单条短信
```bash
curl -X POST "http://localhost:8080/api/sms/send" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13812345678",
    "templateId": "SMS_TPL_001",
    "params": {
      "code": "888888"
    }
  }'
```

#### 场景二：受管异步发送短信
```bash
curl -X POST "http://localhost:8080/api/sms/send-async" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13866668888",
    "templateId": "SMS_ASYNC_001",
    "params": {
      "name": "Ateng"
    }
  }'
```

#### 场景三：大规模营销短信批量群发
```bash
curl -X POST "http://localhost:8080/api/sms/batch" \
  -H "Content-Type: application/json" \
  -d '{
    "phones": [
      "13800010001",
      "13800010002",
      "13800010003"
    ],
    "templateId": "SMS_BATCH_MARKETING",
    "params": {
      "discount": "80%"
    }
  }'
```

#### 场景四：发送与核验验证码（含防重放核销自毁）
```bash
# 1. 发送验证码
curl -X POST "http://localhost:8080/api/sms/verify-code/send" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800139001",
    "bizType": "login"
  }'

# 2. 核验验证码（核验成功后立即原子自毁）
curl -X POST "http://localhost:8080/api/sms/verify-code/verify" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800139001",
    "bizType": "login",
    "code": "123456"
  }'
```

#### 场景五：查询渠道列表与动态注册渠道
```bash
# 1. 查询渠道列表（已脱敏，无 accessKeySecret）
curl -X GET "http://localhost:8080/api/sms/channel/list"

# 2. 动态注册渠道
curl -X POST "http://localhost:8080/api/sms/channel/dynamic" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "custom-dynamic",
    "supplier": "MOCK",
    "signName": "动态签名"
  }'
```

---

## 4. 双模真实公有云联调指南（可选）

若需要在本地联调阿里云、腾讯云或华为云真实发信能力：

1. 复制模板文件生成本地专属配置（该文件已被 `.gitignore` 严格忽略，绝无明文泄密风险）：
   ```bash
   cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
   ```
2. 在 `application-local.yml` 中填写真实的各厂商 `access-key-id`、`access-key-secret`、`sign-name` 等凭据；
3. 执行激活 `local` Profile 的真实发信集成测试：
   ```bash
   mvn test -pl ateng-boot-samples/ateng-boot-sample-sms -Dspring.profiles.active=local -am -T 1C -nsu -B
   ```
4. 观察控制台输出与目标测试手机真实接收到的短信。
