# ateng-boot-sample-sms 短信场景体验工程

> 专注于企业级多渠道短信通信、纯原生零第三方 SDK 驱动体系、统一发信门面与验证码全生命周期治理、大规模营销群发透明切片、滑动窗口防刷与出站黑名单拦截、本地事务提交感知（`sendAfterCommit` 防幽灵短信）、全链路发信审计事件、加权平滑轮询负载均衡与渠道健康度熔断、统一 Webhook 回调转译及双模公有云真实联调。

---

## 1. 端口与极速启动

样例工程基于标准 Spring Boot Web 架构构建，对外统一暴露 `/sample/sms/**` 业务领域接口与 `/ateng-sms/callback/**` 统一入站 Webhook 网关。

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
| **离线默认模式 (CI / 内存桩)** | 默认启动（未激活 `local` Profile） | 自动装载 `MockSmsProvider` 内存驱动与纯内存防刷/黑名单仓储，发信直接内存自洽并打印脱敏日志 | 本地日常脱网研发、单元测试、GitHub Actions CI 流水线 |
| **Local 真实公有云直连模式** | 激活 `-Dspring.profiles.active=local` | 探测读取本地 `application-local.yml` 真实凭证，直连阿里云、腾讯云、华为云、云片网、天翼云等云端物理网关 | 线上真实网络联调、运营商签名报备测试、真实短信收发回归 |

### 切换至真实公有云联调：
1. 从基准模板创建本地私有配置文件（物理路径已被 `.gitignore` 严格忽略，绝无凭据泄密风险）：
   ```bash
   cp ateng-boot-samples/ateng-boot-sample-sms/src/main/resources/application-local.example.yml ateng-boot-samples/ateng-boot-sample-sms/src/main/resources/application-local.yml
   ```
2. 编辑 `application-local.yml`，填写真实的云厂商凭据（如 `access-key-id`、`access-key-secret`、`sign-name` 等）；
3. 激活 `local` 模式启动或执行测试：
   ```bash
   mvn -pl ateng-boot-samples/ateng-boot-sample-sms spring-boot:run -Dspring-boot.run.profiles=local
   ```

---

## 3. 核心领域 Controller 接口与一键体验 cURL

样例工程依据单一职责原则将业务接口拆分为 5 大业务领域 Controller，统一遵循显式 `Result<T>` 强类型响应契约与 JSR-303 分层防卫：

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

#### (3) 本地物理事务感知发信 (`POST /sample/sms/send/tx`)
```bash
# 模拟业务正常完成，本地事务成功 COMMIT，触发真实物理投递
curl -X POST "http://localhost:8080/sample/sms/send/tx?shouldRollback=false" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13899990001",
    "templateId": "SMS_TX_COMMIT"
  }'

# 模拟业务异常回滚，本地事务 ROLLBACK，发信请求自动撤销（彻底防幽灵短信）
curl -X POST "http://localhost:8080/sample/sms/send/tx?shouldRollback=true" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13899990002",
    "templateId": "SMS_TX_ROLLBACK"
  }'
```

#### (4) 跨渠道模板逻辑标识映射发信 (`POST /sample/sms/send/mapping`)
```bash
curl -X POST "http://localhost:8080/sample/sms/send/mapping" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13812349999",
    "templateId": "LOGIN_AUTH",
    "params": {
      "code": "123456"
    }
  }'
```

---

### 3.2 批量营销与差异化群发领域 (`SmsBatchController` -> `/sample/sms/batch`)

#### (1) 同参海量短信自动分批切片群发 (`POST /sample/sms/batch/same`)
```bash
curl -X POST "http://localhost:8080/sample/sms/batch/same" \
  -H "Content-Type: application/json" \
  -d '{
    "phones": [
      "13800010001",
      "13800010002",
      "13800010003",
      "13800010004"
    ],
    "templateId": "SMS_BATCH_MARKETING",
    "params": {
      "discount": "80%"
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
      "13800020001": {
        "name": "Alice",
        "points": "100"
      },
      "13800020002": {
        "name": "Bob",
        "points": "200"
      }
    }
  }'
```

---

### 3.3 验证码全生命周期治理领域 (`SmsVerifyCodeController` -> `/sample/sms/verify-code`)

#### (1) 生成并发送业务验证码 (`POST /sample/sms/verify-code/send`)
> 具备夜间免打扰自动豁免与单号滑动窗口频控（1分钟/1小时/1天），生成后写入仓储并投递。
```bash
curl -X POST "http://localhost:8080/sample/sms/verify-code/send" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800139001",
    "bizType": "login"
  }'
```

#### (2) 验证码只读预检 (`POST /sample/sms/verify-code/check`)
> 验证有效性但**不物理自毁**、**不扣减重试次数**，适用于复杂多步表单预先校验。
```bash
curl -X POST "http://localhost:8080/sample/sms/verify-code/check" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800139001",
    "bizType": "login",
    "code": "123456"
  }'
```

#### (3) 验证码核验并原子自毁 (`POST /sample/sms/verify-code/verify`)
> 核验成功立即物理自毁（杜绝重放攻击）；核验失败扣减重试次数，超限自动冻结。
```bash
curl -X POST "http://localhost:8080/sample/sms/verify-code/verify" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800139001",
    "bizType": "login",
    "code": "123456"
  }'
```

#### (4) 验证码主动作废 (`POST /sample/sms/verify-code/invalidate`)
> 用户主动取消流程或会话超时重置时主动注销。
```bash
curl -X POST "http://localhost:8080/sample/sms/verify-code/invalidate" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800139001",
    "bizType": "login"
  }'
```

---

### 3.4 渠道治理、负载均衡与熔断运维领域 (`SmsChannelController` -> `/sample/sms/channel`)

#### (1) 查询已注册渠道安全脱敏视图 (`GET /sample/sms/channel/list`)
```bash
curl -X GET "http://localhost:8080/sample/sms/channel/list"
```

#### (2) 运行时动态注册/热更新渠道 (`POST /sample/sms/channel/register`)
```bash
curl -X POST "http://localhost:8080/sample/sms/channel/register" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "custom-dynamic-ali",
    "supplier": "MOCK",
    "signName": "动态测试签名",
    "weight": 2,
    "templates": {
      "LOGIN_AUTH": "SMS_REAL_001"
    }
  }'
```

#### (3) 运行时动态注销渠道 (`POST /sample/sms/channel/unregister`)
```bash
curl -X POST "http://localhost:8080/sample/sms/channel/unregister?channelName=custom-dynamic-ali"
```

#### (4) 平滑加权轮询负载均衡发信 (`POST /sample/sms/channel/load-balance`)
```bash
curl -X POST "http://localhost:8080/sample/sms/channel/load-balance" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800055555",
    "templateId": "SMS_LB_001",
    "params": {
      "code": "666666"
    }
  }'
```

#### (5) 渠道健康度熔断状态查询与一键重置 (`GET/POST /sample/sms/channel/circuit-breaker*`)
```bash
# 查询各渠道当前熔断状态（OPEN / CLOSED）
curl -X GET "http://localhost:8080/sample/sms/channel/circuit-breaker"

# 一键重置指定渠道熔断状态为 CLOSED
curl -X POST "http://localhost:8080/sample/sms/channel/circuit-breaker/reset?channelName=mock"

# 重置全部渠道熔断状态
curl -X POST "http://localhost:8080/sample/sms/channel/circuit-breaker/reset"
```

---

### 3.5 出站防卫、幂等防重与审计事件领域 (`SmsGuardAndCallbackController` -> `/sample/sms/guard`)

#### (1) 出站黑名单全生命周期管理 (`GET/POST /sample/sms/guard/blacklist*`)
```bash
# 查询当前出站黑名单号码
curl -X GET "http://localhost:8080/sample/sms/guard/blacklist"

# 动态添加号码至出站黑名单
curl -X POST "http://localhost:8080/sample/sms/guard/blacklist/add" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13999998888",
    "reason": "疑似恶意高频轰炸号码"
  }'

# 从出站黑名单移除号码
curl -X POST "http://localhost:8080/sample/sms/guard/blacklist/remove?phone=13999998888"

# 清空出站黑名单
curl -X POST "http://localhost:8080/sample/sms/guard/blacklist/clear"
```

#### (2) 业务防重幂等拦截发信 (`POST /sample/sms/guard/idempotent`)
> 携带唯一业务流水号 `bizId`，在幂等窗口（默认 3 分钟）内重复发起将直接拦截并返回业务错误码 `5809`。
```bash
curl -X POST "http://localhost:8080/sample/sms/guard/idempotent" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13811112222",
    "templateId": "SMS_IDEM_001",
    "bizId": "PAY_NOTIFY_9876543210"
  }'
```

#### (3) 夜间免打扰静默与紧急放行 (`POST /sample/sms/guard/quiet-hours`)
> 若当前处于夜间免打扰时段（如 22:00-08:00），普通营销发信将被静默拦截；若标配 `urgent: true` 则允许紧急放行。
```bash
curl -X POST "http://localhost:8080/sample/sms/guard/quiet-hours" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800001111",
    "templateId": "SMS_ALERT_001",
    "urgent": true
  }'
```

#### (4) 全链路出站发信审计事件流水查询 (`GET /sample/sms/guard/audit-events`)
```bash
curl -X GET "http://localhost:8080/sample/sms/guard/audit-events"
```

#### (5) 统一入站 Webhook 回调转译端点 (`POST /ateng-sms/callback/{channel}`)
> 统一接收云厂商异步送达回执与用户上行回复（如“TD”退订），自动解析并广播 Spring 领域事件。
```bash
# 模拟云厂商送达回执回调
curl -X POST "http://localhost:8080/ateng-sms/callback/mock" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "phone_number": "13800088888",
      "send_time": "2026-11-07 12:00:00",
      "report_time": "2026-11-07 12:00:02",
      "success": true,
      "err_code": "DELIVRD",
      "err_msg": "用户接收成功",
      "sms_size": "1",
      "biz_id": "MOCK_CB_001"
    }
  ]'
```

---

## 4. 全真自动化回归命令

遵循单测试套件双模原则，只需一条命令即可完成模块与场景样例工程的自动化回归：

```bash
mvn test -pl ateng-boot-sms,ateng-boot-samples/ateng-boot-sample-sms -am -T 1C -nsu -B
```

控制台输出全绿（138 Tests, 0 Failures, 0 Errors, BUILD SUCCESS），满足工业级生产就绪准出标准。
