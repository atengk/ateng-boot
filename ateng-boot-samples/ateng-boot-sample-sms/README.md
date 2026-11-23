# ateng-boot-sample-sms 短信场景体验工程

> 专注于企业级多渠道短信通信、15 渠道（14 家公有云/企业网关 + Mock）纯原生零第三方 SDK 驱动体系、统一发信门面与验证码全生命周期治理、双模可取消延迟/定时调度（Memory / Redis ZSet）、主动回执与只读余额探查、纯文本直发与 E.164 国际短信、配额周期上限与安全沙箱防误发、本地事务提交感知（`sendAfterCommit`）、低基数 Micrometer 指标与安全健康探针，以及双模公有云真实联调（参见 [ateng-boot-sms 文档](../../ateng-boot-sms/README.md) 与 [ADR-0110](../../docs/adr/0110-sms-enterprise-evolution-and-observability-architecture.md)）。

---

## 1. 端口与极速启动

样例工程基于标准 Spring Boot Web 架构构建，对外统一暴露 `/sample/sms/**` 六大业务领域 REST 接口与 `/ateng-sms/callback/**` 统一入站 Webhook 网关：

- **默认端口**：`8080`
- **一键极速启动命令**：

```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-sms spring-boot:run
```

---

## 2. 环境与双模切换

工程支持**单测试套件与双模自适应架构**，兼顾脱网研发极速反馈与线上真实环境全真联调：

| 运行模式 | 激活方式 | 核心行为与基础设施状态 | 适用场景 |
| :--- | :--- | :--- | :--- |
| **离线默认模式 (CI / 内存桩)** | 默认启动（未激活 `local` Profile） | 自动装载 `MockSmsProvider` 内存驱动与纯内存防刷/黑名单/配额/延迟调度仓储，零公网访问与零计费 | 本地日常脱网研发、单元测试、GitHub Actions CI 流水线 |
| **Local 真实公有云直连模式** | 激活 `-Dspring.profiles.active=local` | 通过 `spring.config.import: "optional:classpath:application-local.yml"` 加载本地真实凭据，直连阿里云、腾讯云、华为云、云片网等 14 家网关 | 线上真实网络联调、运营商签名报备测试、主动回执/余额查询回归 |

### 切换至真实公有云联调
1. 从基准模板复制本地私有配置文件（物理路径已纳入 `.gitignore`，绝不入库）：
   ```bash
   cp ateng-boot-samples/ateng-boot-sample-sms/src/main/resources/application-local.example.yml ateng-boot-samples/ateng-boot-sample-sms/src/main/resources/application-local.yml
   ```
2. 编辑 `application-local.yml`，将 `YOUR_*` 占位符替换为对应云厂商真实凭据；
3. 激活 `local` Profile 启动或运行集成测试（测试结束会在 `@AfterEach` 清理测试数据）：
   ```bash
   mvn -pl ateng-boot-samples/ateng-boot-sample-sms spring-boot:run -Dspring-boot.run.profiles=local
   ```

---

## 3. 核心领域 Controller 接口与一键体验 cURL

样例工程依据单一职责原则将业务接口拆分为 **6 大业务领域 Controller**，统一遵循显式 `Result<T>` 强类型响应契约、DTO/VO 传输隔离与 JSR-303 分层防卫：

| 领域控制器 | 路由前缀 | 核心演练能力 |
| :--- | :--- | :--- |
| `SmsSendController` | `/sample/sms/send` | 单条同步、受管异步、`sendAfterCommit` 事务提交后发信/回滚撤销、异步事务感知、异构参数位置映射 |
| `SmsBatchController` | `/sample/sms/batch` | 海量同参分片群发、千人千面个性化差异批次群发 |
| `SmsVerifyCodeController` | `/sample/sms/verify-code` | 验证码生成投递、只读预检（不销毁不扣次）、核验自毁、主动作废 |
| `SmsChannelController` | `/sample/sms/channel` | 渠道脱敏列表、动态注册/注销、平滑加权轮询、熔断状态与重置、缓存热驱逐 `/evict`、配额查询 `/quota` 与重置 |
| `SmsGuardAndCallbackController` | `/sample/sms/guard` | 出站黑名单 CRUD、`bizId` 幂等防重、夜间免打扰与紧急放行、安全沙箱演练 `/sandbox`、通知频控 `/notify`、审计流水、Webhook 回调 |
| `SmsDelayAndQueryController` | `/sample/sms` | 双模延迟 `/delay/schedule`、绝对定时 `/delay/at`、取消调度 `/delay/cancel`、纯文本/国际短信、主动回执查询、只读余额探查、零外发沙箱预演 |

### 3.1 单条与事务发信领域 (`SmsSendController` -> `/sample/sms/send`)

#### (1) 单条短信同步发信 (`POST /sample/sms/send/single`)
```bash
curl -X POST "http://localhost:8080/sample/sms/send/single" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13812345678",
    "templateId": "SMS_TPL_001",
    "params": {
      "code": "888888"
    }
  }'
```

#### (2) 单条短信受管异步发信 (`POST /sample/sms/send/async`)
```bash
curl -X POST "http://localhost:8080/sample/sms/send/async" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13866668888",
    "templateId": "SMS_ASYNC_001",
    "params": {
      "name": "Ateng"
    }
  }'
```

#### (3) 本地物理事务感知发信 (`POST /sample/sms/send/tx` & `/tx-async`)
```bash
# 本地事务 COMMIT 时触发投递
curl -X POST "http://localhost:8080/sample/sms/send/tx?shouldRollback=false" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13899990001",
    "templateId": "SMS_TX_COMMIT"
  }'

# 本地事务 ROLLBACK 时自动撤销，防幽灵短信
curl -X POST "http://localhost:8080/sample/sms/send/tx?shouldRollback=true" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13899990002",
    "templateId": "SMS_TX_ROLLBACK"
  }'

# 事务提交后转交 Core 受管线程池异步发信
curl -X POST "http://localhost:8080/sample/sms/send/tx-async?shouldRollback=false" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13899990003",
    "templateId": "SMS_TX_ASYNC_COMMIT"
  }'
```

#### (4) 异构服务商位置参数自动映射 (`POST /sample/sms/send/mapping`)
```bash
curl -X POST "http://localhost:8080/sample/sms/send/mapping" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13877776666",
    "templateId": "ORDER_NOTIFY",
    "params": {
      "orderNo": "ORD202611210001",
      "amount": "299.00"
    }
  }'
```

---

### 3.2 批量营销与差异化群发领域 (`SmsBatchController` -> `/sample/sms/batch`)

#### (1) 同参海量短信自动切片群发 (`POST /sample/sms/batch/same`)
```bash
curl -X POST "http://localhost:8080/sample/sms/batch/same" \
  -H "Content-Type: application/json" \
  -d '{
    "phones": ["13800010001", "13800010002", "13800010003"],
    "templateId": "SMS_PROMO_001",
    "params": {
      "activity": "双十一特惠"
    }
  }'
```

#### (2) 千人千面个性化差异批次群发 (`POST /sample/sms/batch/differential`)
```bash
curl -X POST "http://localhost:8080/sample/sms/batch/differential" \
  -H "Content-Type: application/json" \
  -d '{
    "templateId": "SMS_DIFF_BATCH",
    "phoneParams": {
      "13800020001": { "name": "Alice", "points": "100" },
      "13800020002": { "name": "Bob", "points": "200" }
    }
  }'
```

---

### 3.3 验证码全生命周期治理领域 (`SmsVerifyCodeController` -> `/sample/sms/verify-code`)

```bash
# 1. 生成并发送验证码（夜间免打扰自动紧急放行 + 独立 verify: 滑窗频控）
curl -X POST "http://localhost:8080/sample/sms/verify-code/send" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13800139001", "bizType": "login" }'

# 2. 只读预检（不物理销毁、不扣减重试次数）
curl -X POST "http://localhost:8080/sample/sms/verify-code/check" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13800139001", "bizType": "login", "code": "123456" }'

# 3. 核验并原子自毁（错误超限自动冻结）
curl -X POST "http://localhost:8080/sample/sms/verify-code/verify" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13800139001", "bizType": "login", "code": "123456" }'

# 4. 主动作废
curl -X POST "http://localhost:8080/sample/sms/verify-code/invalidate" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13800139001", "bizType": "login" }'
```

---

### 3.4 渠道治理、配额与熔断运维领域 (`SmsChannelController` -> `/sample/sms/channel`)

```bash
# 1. 查询脱敏渠道列表（ak/sk/token 仅返回安全掩码）
curl -X GET "http://localhost:8080/sample/sms/channel/list"

# 2. 动态注册或热更新渠道
curl -X POST "http://localhost:8080/sample/sms/channel/register" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "custom-dynamic-ali",
    "supplier": "MOCK",
    "signName": "动态测试签名",
    "weight": 2,
    "templates": { "LOGIN_AUTH": "SMS_REAL_001" }
  }'

# 3. 动态注销渠道
curl -X POST "http://localhost:8080/sample/sms/channel/unregister?channelName=custom-dynamic-ali"

# 4. 主动驱逐动态渠道缓存（不传 channelName 全量清空）
curl -X POST "http://localhost:8080/sample/sms/channel/evict?channelName=custom-dynamic-ali"

# 5. 平滑加权轮询负载均衡发信
curl -X POST "http://localhost:8080/sample/sms/channel/load-balance" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13800055555", "templateId": "SMS_LB_001", "params": { "code": "666666" } }'

# 6. 查询与重置渠道熔断状态
curl -X GET "http://localhost:8080/sample/sms/channel/circuit-breaker"
curl -X POST "http://localhost:8080/sample/sms/channel/circuit-breaker/reset?channelName=mock"

# 7. 查询与重置渠道配额使用状态
curl -X GET "http://localhost:8080/sample/sms/channel/quota?channelName=mock"
curl -X POST "http://localhost:8080/sample/sms/channel/quota/reset?channelName=mock"
```

---

### 3.5 出站防卫、沙箱、通知频控与回调领域 (`SmsGuardAndCallbackController` -> `/sample/sms/guard`)

```bash
# 1. 黑名单 CRUD
curl -X GET "http://localhost:8080/sample/sms/guard/blacklist"
curl -X POST "http://localhost:8080/sample/sms/guard/blacklist/add" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13999998888", "reason": "疑似高频轰炸" }'
curl -X POST "http://localhost:8080/sample/sms/guard/blacklist/remove?phone=13999998888"
curl -X POST "http://localhost:8080/sample/sms/guard/blacklist/clear"

# 2. bizId 幂等防重与夜间免打扰紧急放行
curl -X POST "http://localhost:8080/sample/sms/guard/idempotent" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13811112222", "templateId": "SMS_IDEM_001", "bizId": "PAY_NOTIFY_9876543210" }'
curl -X POST "http://localhost:8080/sample/sms/guard/quiet-hours" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13800001111", "templateId": "SMS_ALERT_001", "urgent": true }'

# 3. 测试安全沙箱演练与 notify: 非验证码通知独立频控
curl -X POST "http://localhost:8080/sample/sms/guard/sandbox" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13800138000", "templateId": "SMS_SANDBOX_001", "params": { "code": "112233" } }'
curl -X POST "http://localhost:8080/sample/sms/guard/notify" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13800002222", "templateId": "SMS_NOTIFY_001", "skipRateLimit": false }'

# 4. 审计事件流水与模拟 Webhook 回调
curl -X GET "http://localhost:8080/sample/sms/guard/audit-events"
curl -X POST "http://localhost:8080/ateng-sms/callback/mock" \
  -H "Content-Type: application/json" \
  -d '[{"phone_number":"13800088888","send_time":"2026-11-21 12:00:00","report_time":"2026-11-21 12:00:02","success":true,"err_code":"DELIVRD","err_msg":"用户接收成功","sms_size":"1","biz_id":"MOCK_CB_001"}]'
```

---

### 3.6 延迟定时调度、纯文本/国际短信与主动回执/余额查询领域 (`SmsDelayAndQueryController` -> `/sample/sms`)

```bash
# 1. 提交相对延迟发送任务（入队 Fail-Fast 仅校验参数/渠道，到点走完整实时治理链）
curl -X POST "http://localhost:8080/sample/sms/delay/schedule" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13800138000", "templateId": "SMS_TPL_001", "params": { "code": "654321" }, "delaySeconds": 5 }'

# 2. 提交绝对时刻定时发送任务（ISO-8601 Instant）
curl -X POST "http://localhost:8080/sample/sms/delay/at" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13800138000", "templateId": "SMS_TPL_001", "triggerAt": "2026-12-31T23:59:59Z" }'

# 3. 撤销取消尚未触发的延迟/定时任务
curl -X POST "http://localhost:8080/sample/sms/delay/cancel?taskId=YOUR_TASK_ID"

# 4. 纯文本无模板直发 / E.164 国际区号短信发送
curl -X POST "http://localhost:8080/sample/sms/delay/plain-intl" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "+85291234567", "content": "[Ateng] 您的跨境订单已发货。", "channel": "mock" }'

# 5. 主动查询单条短信送达回执（返回手机号脱敏 VO）
curl -X GET "http://localhost:8080/sample/sms/query/status?channel=mock&phone=13800138000&bizId=YOUR_BIZ_ID&sendDate=2026-11-21"

# 6. 只读探查云厂商账户余额/剩余条数（零计费短信消耗）
curl -X GET "http://localhost:8080/sample/sms/query/balance?channel=mock"

# 7. 非生产安全沙箱零外发模拟预演
curl -X POST "http://localhost:8080/sample/sms/delay/sandbox-preview" \
  -H "Content-Type: application/json" \
  -d '{ "phone": "13800138000", "templateId": "SMS_SANDBOX_PREVIEW", "params": { "code": "999999" } }'
```

---

## 4. 全真自动化回归命令

遵循单测试套件双模原则，一条命令即可完成短信核心模块、可观测探针与场景样例工程的联合回归：

```bash
mvn test -pl ateng-boot-sms,ateng-boot-observability,ateng-boot-samples/ateng-boot-sample-sms -am -T 1C -nsu -B
```

若本机已在 `application-local.yml` 配置真实云厂商凭据，可追加 `-Dspring.profiles.active=local` 进行真实联调验证；CI 环境下以纯内存离线模式运行至 `0 Failures, 0 Errors, BUILD SUCCESS`。
