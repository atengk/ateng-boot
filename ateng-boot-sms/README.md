# ateng-boot-sms

> 面向 Spring Boot 3 的**零第三方厂商 SDK 依赖、纯原生生产级多渠道短信通信与治理基础设施**：全面覆盖阿里云、腾讯云、华为云、云片网、天翼云、百度云、京东云、七牛云、网易云信、容联云、创蓝 253、中国移动云 MAS、合一（UniSMS）、助通科技与 Mock 沙箱共 **15 大内建驱动 + `CUSTOM` 多实例扩展**，集成异构模板参数位置映射、多租户动态路由与 Redis Pub/Sub 集群热驱逐、平滑加权轮询与故障顺延容灾、滑动窗口频控与日/总配额熔断、测试安全沙箱、双模可取消延迟调度（Memory / Redis ZSet Lua）、主动回执与只读余额探查、统一 Webhook 安全回调、低基数 Micrometer 指标及 K8s 安全健康探针。

> [!IMPORTANT]
> **零臃肿与纯原生坚持**：`ateng-boot-sms` 基于 JDK 17 与 Spring 6 `RestClient`、`Mac` / `MessageDigest` 自研全部 14 家厂商签名协议，**零**引入各家动辄数兆的官方 OpenAPI SDK 与额外 HTTP/JSON 传递依赖；全部并发控制采用 `ReentrantLock` / CAS / Redis Lua，消除 JDK 21+ 虚拟线程 Carrier Pinning。

---

## 0. 能力与接口全景速查

### 0.1 15 大服务商支持矩阵（`SmsSupplier`）

| 枚举值 (`code`) | 厂商名称 | 鉴权协议 | 单次批上限 (`batchLimit`) | 纯文本直发 (`supportsPlainText`) | 主动状态回查 (`querySendStatus`) | 账户余额探查 (`queryBalance`) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ALIYUN` (`aliyun`) | 阿里云短信 Dysmsapi | POP HMAC-SHA1 v1 | `1000` | `false`（纯模板） | 支持（`QuerySendDetails`） | 降级 `supported=false` |
| `TENCENT` (`tencent`) | 腾讯云短信 SMS 3.0 | TC3-HMAC-SHA256 v3 | `200` | `false`（纯模板） | 支持（`PullSmsSendStatus`） | 降级 `supported=false` |
| `HUAWEI` (`huawei`) | 华为云短信 MSGSMS | X-WSSE SHA256 Digest | `500` | `false`（纯模板） | 降级 `supported=false` | 降级 `supported=false` |
| `YUNPIAN` (`yunpian`) | 云片网短信 Yunpian | APIKey Form POST | `1000` | `true`（模板/纯文本） | 降级 `supported=false` | 支持（`/v2/user/get.json`） |
| `CTYUN` (`ctyun`) | 天翼云短信 CTYun | CTYun HMAC-SHA256 | `1000` | `false` | 降级 `supported=false` | 降级 `supported=false` |
| `BAIDU` (`baidu`) | 百度智能云 BCE SMS v3 | BCE-AUTH-V1 HMAC-SHA256 | `1000` | `false` | 降级 `supported=false` | 降级 `supported=false` |
| `JDCLOUD` (`jdcloud`) | 京东云短信 JD Cloud | JDCLOUD2-HMAC-SHA256 | `100` | `false` | 降级 `supported=false` | 降级 `supported=false` |
| `QINIU` (`qiniu`) | 七牛云短信 Qiniu | Qiniu AccessToken HMAC-SHA1 | `1000` | `false` | 降级 `supported=false` | 降级 `supported=false` |
| `NETEASE` (`netease`) | 网易云信短信 Netease | CheckSum SHA1 + CurTime | `100` | `false` | 降级 `supported=false` | 降级 `supported=false` |
| `CLOOPEN` (`cloopen`) | 容联云短信 Cloopen | MD5 SigParameter + Base64 Auth | `200` | `false` | 降级 `supported=false` | 降级 `supported=false` |
| `CHUANGLAN` (`chuanglan`) | 创蓝 253 云通讯 | JSON Account / Password | `1000` | `true` | 降级 `supported=false` | 降级 `supported=false` |
| `MAS` (`mas`) | 中国移动云 MAS | ecName / apId / secretKey MD5 | `1000` | `true` | 降级 `supported=false` | 降级 `supported=false` |
| `UNISMS` (`unisms`) | 合一短信 UniSMS | Simple / HMAC-SHA256 Sign | `1000` | `false` | 降级 `supported=false` | 降级 `supported=false` |
| `ZHUTONG` (`zhutong`) | 助通科技短信 Zhutong | Timestamp + MD5 tKey | `1000` | `true` | 降级 `supported=false` | 支持（`/v2/balance`） |
| `MOCK` (`mock`) | 本地内存测试沙箱 | 内存桩（零网络开销） | `1000` | `true` | 支持（内存记录查询） | 支持（可设桩余额） |
| `CUSTOM` (`custom`) | 自定义多驱动 SPI | `providerCode` 多实例路由 | 默认 `1000`（可覆写） | 默认 `false`（可覆写） | 默认降级 `supported=false` | 默认降级 `supported=false` |

### 0.2 门面与注册表全量公开 API 速查（100% 覆盖）

| 门面 Bean | 方法签名 | 核心行为说明 |
| :--- | :--- | :--- |
| **`SmsTemplate`** | `send(SmsRequest)` / `sendAsync(SmsRequest)` | 单条同步 / 受管异步发信（全链路校验、沙箱、防卫、容灾、指标） |
| **`SmsTemplate`** | `sendAfterCommit(SmsRequest)` / `sendAsyncAfterCommit(SmsRequest)` | 绑定 Spring 本地物理事务：仅 `COMMIT` 后投递，`ROLLBACK` 自动撤销防幽灵短信 |
| **`SmsTemplate`** | `sendBatch(BatchSmsRequest)` / `sendBatchAsync(BatchSmsRequest)` | 同参大批量分片及个性化千人千面群发；事务感知版本 `sendBatchAfterCommit` / `sendBatchAsyncAfterCommit` |
| **`SmsTemplate`** | `sendByLoadBalance(SmsRequest)` / `sendByLoadBalanceAsync(SmsRequest)` | 基于权重池执行平滑加权轮询（SWRR）选路与熔断避让投递 |
| **`SmsTemplate`** | `sendDelayed(SmsRequest, Duration)` / `sendAt(SmsRequest, Instant)` | 两阶段校验延迟/定时单条调度，返回带可取消句柄的 `SmsScheduledReceipt` |
| **`SmsTemplate`** | `sendBatchDelayed(BatchSmsRequest, Duration)` / `sendBatchAt(BatchSmsRequest, Instant)` | 两阶段校验延迟/定时批量调度（支持 Memory 与 Redis ZSet Lua 双模持久化） |
| **`SmsTemplate`** | `cancelScheduled(String taskId)` | 幂等撤销处于待触发态的延迟/定时调度任务 |
| **`SmsTemplate`** | `querySendStatus(String channelName, String phone, String bizId, LocalDate sendDate)` | 主动查询单条短信投递回执；不支持厂商平滑返回 `supported=false` |
| **`SmsTemplate`** | `queryBalance(String channelName)` | 只读探查云厂商账户余额或剩余条数，绝不发送计费短信 |
| **`SmsTemplate`** | `registerChannel(SmsChannel)` / `unregisterChannel(String)` | 运行时向注册中心动态注册或注销渠道 |
| **`SmsTemplate`** | `evictChannelCache(String channelName)` / `evictAllChannelCache()` | 主动驱逐动态渠道 LRU 缓存、清理负载权重与熔断计数并触发 Redis 集群广播 |
| **`SmsTemplate`** | `resolveChannelName(String)` / `resolveChannel(String)` / `resolveBatchLimit(SmsSupplier)` / `resolveChannelBatchLimit(SmsChannel)` / `getProvider(SmsSupplier)` / `getProvider(String)` | 解析当前有效渠道（上下文/租户/默认）、计算生效批大小与获取底层驱动实例 |
| **`SmsTemplate`** | `getChannelQuotaUsed(String)` / `resetChannelQuota(String)` | 查询或手动清零指定渠道的周期已用发信配额 |
| **`SmsTemplate`** | `isChannelOpen(String)` / `resetChannelCircuitBreaker(String)` / `resetAllChannelCircuitBreakers()` / `resetLoadBalancer()` | 检视熔断开关态、一键复位渠道熔断器及重建平滑加权轮询状态 |
| **`SmsTemplate`** | `addBlacklist(String)` / `addBlacklist(String, String)` / `removeBlacklist(String)` / `isBlacklisted(String)` / `getBlacklist()` | 运行时增删改查出站手机号黑名单 |
| **`SmsTemplate`** | `isInQuietHours()` / `isInQuietHours(LocalTime)` | 判断当前或指定时刻是否落入夜间免打扰窗口 |
| **`SmsVerifyCodeTemplate`** | `generateAndSend(phone, bizType)` 及 `templateId` / `channel` / `signName` / `ip` 重载 | 冷却校验 -> 独立 `verify:` 滑窗频控 -> 安全随机生成 -> 强制 `urgent=true` 投递 |
| **`SmsVerifyCodeTemplate`** | `check(phone, code, bizType)` / `verify(phone, code, bizType)` / `invalidate(phone, bizType)` | 只读预检（不销毁不扣次）/ 原子核销自毁（超错冻结）/ 主动作废 |
| **`SmsChannelRegistry`** | `register(SmsChannel)` / `unregister(String)` / `get(String)` / `getAll()` / `size()` / `dynamicSize()` / `evictDynamic(String)` / `evictAllDynamic()` | 静态 YAML 与动态 LRU 双轨注册管理与集群热驱逐 |
| **`SmsChannelCircuitBreaker`** | `getState(String)` / `reset(String)` / `resetAll()` / `getConsecutiveFailures(String)` | 查询与重置渠道滑窗熔断状态（`CLOSED` / `OPEN` / `HALF_OPEN`） |

### 0.3 配置属性族速查

| 配置前缀 | 属性类 | 核心职责 |
| :--- | :--- | :--- |
| `ateng.boot.sms` | `SmsProperties` | 总开关 `enabled`、默认渠道 `default-channel` 与渠道字典 `channels.<name>` |
| `ateng.boot.sms.channels.<name>` | `SmsChannelProperties` | 厂商 `supplier` / `provider-code`、凭据、签名/扩展码、`failover-channel`、重试/超时、`international-enabled`、`max-quota` / `quota-reset-cycle`、`proxy.*`、`extras.*`、`templates.*` |
| `ateng.boot.sms.verify-code` | `SmsVerifyCodeProperties` | 验证码长度、`ttl`、`resend-interval`、`max-retry-attempts`、`char-type`、`default-template-id` |
| `ateng.boot.sms.rate-limit` | `SmsRateLimitProperties` | 滑动窗口 `per-minute` / `per-hour` / `per-day` |
| `ateng.boot.sms.guard` | `SmsGuardProperties` | `daily-max-quota`、黑名单、`circuit-breaker-*`、幂等 `idempotent-*`、免打扰 `quiet-hours-*`、退订自动加黑、`notify-rate-limit-enabled`、`log-desensitize-content` |
| `ateng.boot.sms.sandbox` | `SmsSandboxProperties` | `enabled`、`log-only`、通配符白名单 `allowed-phones`、重定向测试号 `redirect-to` |
| `ateng.boot.sms.registry` | `SmsRegistryProperties` | 动态渠道有界 LRU `dynamic-cache-*`、Redis Pub/Sub 跨节点热驱逐 `evict-pub-sub-enabled` / `evict-topic` |
| `ateng.boot.sms.delay` | `SmsDelayProperties` | `mode` (`MEMORY` / `REDIS`)、`pool-size`、`await-termination`、`redis-poll-interval`、`redis-batch-size`、`redis-key-prefix`、`max-status-history` |
| `ateng.boot.sms.callback` | `SmsCallbackProperties` | Webhook 开关 `enabled`、`path-prefix`、`token`、`allow-query-token`、`allowed-cidrs`、`signature-required` |
| `ateng.boot.observability.health.sms` | `ObservabilityProperties.SmsHealthProperties` | `SmsSafeHealthIndicator` 开关 `enabled`、60s 防刷缓存 `cache-ttl`、只读余额探查开关 `probe-balance` |

### 0.4 Spring 领域事件与异常体系速查

| 分类 | 组件类 / 枚举 | 触发时机与语义契约 |
| :--- | :--- | :--- |
| **领域事件** | `SmsSendAuditEvent` | 每次单条/批量发信后发布脱敏审计快照（受 `log-desensitize-content` 遮罩） |
| **领域事件** | `SmsDeliveryReportEvent` | Webhook 接收云厂商异步送达回执后解析并广播 |
| **领域事件** | `SmsMoMessageEvent` | Webhook 接收用户上行短信回复后广播；含 `TD`/`退订`/`T` 时可自动写入黑名单 |
| **领域事件** | `SmsBudgetExceededEvent` | 触发单日总预算熔断（`daily-max-quota`）或渠道周期配额耗尽时发布告警事件 |
| **领域事件** | `SmsChannelEvictEvent` | 调用 `evictDynamic` / `evictAllDynamic` 或收到 Redis Pub/Sub 集群驱逐消息后本地广播 |
| **异常体系** | `SmsException`（基类） | 承载 `SmsResultCode` 与原始 `cause` 链，归属异常矩阵 `5801~5809` |
| **异常子类** | `SmsSendException` (`5801`) | 厂商协议拒绝、网络故障或沙箱未配置重定向白名单阻断 |
| **异常子类** | `SmsRateLimitException` (`5802`) | 触发重发冷却或 `verify:` / `notify:` 滑动窗口频控防刷拦截 |
| **异常子类** | `SmsVerifyCodeException` (`5803` / `5804`) | `SMS_VERIFY_CODE_INVALID`（`5803` 错误或过期）/ `SMS_VERIFY_CODE_FROZEN`（`5804` 超次锁定） |
| **异常子类** | `SmsBudgetExceededException` (`5805`) | 突破全系统单日总预算或单渠道周期配额上限 |
| **异常子类** | `SmsBlacklistException` (`5806`) | 命中出站黑名单拦截 |
| **异常子类** | `SmsIdempotentException` (`5809`) | 相同 `bizId` 在 `idempotent-window` 内重复提交被防重拦截 |

### 0.5 扩展 SPI 与可观测组件速查

| 组件类别 | 核心类 / 接口 | 默认实现 / 可选增强说明 |
| :--- | :--- | :--- |
| **渠道驱动 SPI** | `SmsProvider` | 15 家内建实现（含 `AbstractHttpSmsProvider` 代理/重试基类）；支持多 `CUSTOM` + `providerCode` 并存 |
| **渠道解析与供给** | `SmsChannelResolver` / `SmsChannelProvider` | 默认 `TenantSmsChannelResolver`（优先级：请求 -> `SmsContextHolder` -> `TenantContextHolder` -> `defaultChannel`）；支持 DB 动态加载 |
| **频控与验证码仓储** | `SmsRateLimiter` / `SmsVerifyCodeRepository` | 默认 `InMemory*`；存在 `StringRedisTemplate` 时自动装配分布式 Lua `Redis*` |
| **黑名单与幂等守卫** | `SmsBlacklistProvider` / `SmsIdempotentGuard` | 默认 `InMemory*`（有界防 OOM）；有 Redis 时自动升级为分布式 `Redis*` |
| **预算与渠道配额** | `DailyBudgetBreaker` / `SmsQuotaGuard` | 全局日配额 + 渠道 `DAILY`/`TOTAL` 周期配额硬熔断 |
| **拦截与失败回调** | `SmsSendInterceptor` / `SmsSendFailureHandler` | 拦截链含默认顺序的 `SmsSandboxInterceptor`；最终失败回调含 `LoggingSmsSendFailureHandler` |
| **延迟调度引擎** | `SmsDelayScheduler` | `MemorySmsDelayScheduler`（`ThreadPoolTaskScheduler`）或 `RedisSmsDelayScheduler`（ZSet + Lua） |
| **Webhook 验签** | `SmsCallbackVerifier` | 默认 `DefaultSmsCallbackVerifier`（支持 CIDR + 恒定时间 Header Token + HMAC 签名） |
| **低基数 Micrometer 指标** | `SmsMetrics`（实现 `SmsMetricsRecorder`） | 指标：`ateng.sms.send.count`、`ateng.sms.send.duration`、`ateng.sms.retry.count`、`ateng.sms.failover.count`、`ateng.sms.circuit_breaker.transition.count`、`ateng.sms.rate_limit.rejected.count`、`ateng.sms.intercepted.count` 及静态/动态渠道 Gauge |
| **K8s 安全健康探针** | `SmsSafeHealthIndicator`（`ateng-boot-observability`） | 仅检查静态渠道熔断态与可选只读余额；动态租户缓存剔除；默认 `60s` 缓存；`UNKNOWN` 空静态池处理 |

---

## 1. 引入依赖（Maven / BOM）

### 1.1 方式 A：推荐通过 BOM 统一纳管版本

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.atengk</groupId>
            <artifactId>ateng-boot-bom</artifactId>
            <version>1.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- 核心多渠道短信基础设施（零第三方云厂商 SDK 依赖） -->
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-sms</artifactId>
    </dependency>

    <!-- 可选：需要 Redis 分布式防刷/配额/幂等/PubSub驱逐/Redis ZSet延迟调度时引入 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>

    <!-- 可选：需要 Micrometer 指标与 K8s SmsSafeHealthIndicator 安全健康探针时引入 -->
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-observability</artifactId>
    </dependency>
</dependencies>
```

### 1.2 方式 B：独立显式版本号引入

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-sms</artifactId>
    <version>1.2.0</version>
</dependency>
```

---

## 2. 核心架构与不可破坏不变量

### 2.1 分层防卫与全链路调度管道

```
[业务 Service / 事务提交]
    │
    ├─ 实时发信: send / sendAsync / sendAfterCommit / sendBatch / sendByLoadBalance
    ├─ 延迟调度: sendDelayed / sendAt  ──▶ [阶段1 Fail-Fast: 仅校验渠道存在性与模板/正文]
    │                                         └──▶ Memory / Redis ZSet (到点恢复4D+SmsContextHolder上下文)
    ▼
[阶段2 完整出站治理链]
  1. 渠道路由与热加载   ── SmsChannelResolver (请求 -> SmsContextHolder -> TenantContextHolder -> defaultChannel) + Bounded LRU
  2. 测试安全沙箱       ── SmsSandboxInterceptor (log-only 虚拟回执 / 通配符白名单 / 测试号重定向)
  3. 前置与通知频控     ── 黑名单拦截 -> 夜间免打扰 (urgent 放行) -> notify: 命名空间滑窗频控
  4. 预算配额与防重幂等 ── DailyBudgetBreaker -> SmsQuotaGuard (DAILY/TOTAL) -> SmsIdempotentGuard (bizId)
  5. 异构参数与号段归一 ── SmsPhoneNormalizer (E.164) + SmsParam 位置参数顺序自动推导
  6. 熔断、重试与容灾   ── SmsChannelCircuitBreaker -> RestClient (代理 + maxRetries) -> Failover 备用渠道
  7. 观测指标与审计闭环 ── SmsMetrics (低基数 Tag) + SmsSendAuditEvent (内容/敏感键自动脱敏)
```

### 2.2 四项不可破坏工程不变量

1. **延迟调度两阶段校验（Two-Phase Validation）**：调用 `sendDelayed` / `sendAt` 提交任务瞬间**绝不执行**黑名单核查、单日总预算扣减或渠道配额预扣（避免未来撤单造成配额永久泄漏且规避入队前无效拒单）；当任务真正到期触发瞬间再重入实时 `send`/`sendBatch` 执行全量黑名单、沙箱、免打扰、配额与熔断校验。
2. **内存真明文与边界脱敏**：`SmsRequest` 在内存流转及发送至云厂商协议层期间始终保持原始明文（确保业务可正确发出通知/验证码）；仅在日志输出、`SmsSendAuditEvent` 审计事件及 Web VO 层通过 `SmsDesensitizeSupport` 与 `DesensitizeUtil.mobilePhone` 对手机号及敏感键（`code`、`password` 等）执行掩码。
3. **动态渠道标签有界化（防指标基数爆炸）**：`SmsMetrics` 对 YAML 静态配置的渠道保留原始 `channel` 标签名；对通过 `SmsChannelProvider` 按租户动态供给的海量渠道一律收敛为 `channel="dynamic"`；独立实例化未绑定注册中心时超过 64 个静态基数自动折叠为 `"other"`。严禁将手机号或租户 ID 写入 Metric Tag。
4. **虚拟线程零 Pinning 与无状态并发**：渠道注册表读写锁、熔断计数滑窗、内存调度器轨迹维护与指标追踪全部采用 `ReentrantLock` 或无锁 CAS，杜绝 `synchronized` 在 JDK 21+ 虚拟线程下引发载体线程 Pinning；Web Controller 严禁直接透出或接收基础设施底层核心配置实体，必须经由 DTO/VO 与 `Result<T>` 隔离（详见 [`ateng-boot-samples/ateng-boot-sample-sms`](../ateng-boot-samples/ateng-boot-sample-sms/README.md)）。

---

## 3. 15 家服务商渠道配置与多协议接管

### 3.1 国内公有云、纯内网 HTTP 代理与多协议切换

- **专属 YAML 配置**：

```yaml
ateng:
  boot:
    sms:
      enabled: true
      default-channel: aliyun-primary
      channels:
        aliyun-primary:
          supplier: ALIYUN
          access-key-id: ${ALIYUN_SMS_AK:YOUR_ALIYUN_ACCESS_KEY_ID}
          access-key-secret: ${ALIYUN_SMS_SK:YOUR_ALIYUN_ACCESS_KEY_SECRET}
          sign-name: 阿腾科技
          failover-channel: tencent-backup
          weight: 3
          max-retries: 1
          retry-interval: 1s
          timeout: 10s
          max-quota: 50000
          quota-reset-cycle: DAILY
          international-enabled: true
          default-region-code: "+86"
          templates:
            ORDER_NOTIFY:
              template-id: SMS_100020003
        tencent-backup:
          supplier: TENCENT
          access-key-id: ${TENCENT_SMS_SECRET_ID:YOUR_TENCENT_SECRET_ID}
          access-key-secret: ${TENCENT_SMS_SECRET_KEY:YOUR_TENCENT_SECRET_KEY}
          app-id: "1400000000"
          region: ap-guangzhou
          sign-name: 阿腾科技
          weight: 1
          templates:
            ORDER_NOTIFY:
              template-id: "1987654"
              param-order: ["orderNo", "amount"]
        yunpian-intl:
          supplier: YUNPIAN
          access-key-secret: ${YUNPIAN_API_KEY:YOUR_YUNPIAN_API_KEY}
          sign-name: 【阿腾科技】
          international-enabled: true
        # 纯内网隔离应用可按渠道配置正向 HTTP 代理（支持可选 Basic Auth）
        zhutong-proxied:
          supplier: ZHUTONG
          access-key-id: ${ZHUTONG_USER:YOUR_ZHUTONG_USERNAME}
          access-key-secret: ${ZHUTONG_PWD:YOUR_ZHUTONG_PASSWORD}
          sign-name: 【阿腾科技】
          proxy:
            enabled: true
            host: proxy.internal.corp
            port: 3128
            username: ${SMS_PROXY_USER:YOUR_PROXY_USER}
            password: ${SMS_PROXY_PWD:YOUR_PROXY_PWD}
```

- **Sample Java 业务调用片段**（提炼自 `SmsScenarioService` & `SmsDelayAndQueryController`）：

```java
// 1. 通过国际 E.164 号段与无模板纯文本直发跨厂商投递（适用 YUNPIAN/CHUANGLAN/MAS/ZHUTONG/MOCK）
SmsRequest intlPlain = SmsRequest.builder()
        .phone("+85291234567")
        .content("[Ateng] 您的跨境订单已发货。")
        .channel("yunpian-intl")
        .build();
SmsResult intlResult = smsTemplate.send(intlPlain);
```

- **机制与边界说明**：
  - 所有 14 家外部云厂商及 `MOCK` 驱动由静态 YAML 自动实例化；若同一应用接入多家自定义网关，可在各自定义驱动 `SmsProvider#getProviderCode()` 返回唯一编码并在配置设置 `supplier: CUSTOM` + `provider-code: <唯一标识>`；
  - 对纯模板厂商（如 `ALIYUN`、`TENCENT`、`HUAWEI`）传入纯文本而不指定 `templateId` 时，触发参数阶段即抛出 `SmsResultCode.SMS_PARAM_INVALID`（`5808`）Fail-Fast；代理口令标记 `@JsonIgnore` 与 `@ToString(exclude = "password")`，绝不落入日志。

---

## 4. 基础发信、事务感知与海量分片群发

- **场景与专属 YAML 配置**：单条/异步投递、本地事务提交感知（回滚自动撤销防幽灵短信）、异构位置参数推导及大批量透明分片并发群发：

```yaml
ateng:
  boot:
    sms:
      default-channel: mock
      guard:
        batch-timeout: 30s      # 批量分片并发聚合超时上限
        log-desensitize-content: true
```

- **Sample Java 业务调用片段**（提炼自 `SmsScenarioService` & `SmsSendController` / `SmsBatchController`）：

```java
// 1. 单条同步发信与位置参数自动推导（腾讯云按 templates.ORDER_NOTIFY.param-order 自动投射数组）
SmsRequest single = SmsRequest.builder()
        .phone("13877776666")
        .templateId("ORDER_NOTIFY")
        .params(SmsParam.create()
                .add("orderNo", "ORD202611210001")
                .add("amount", "299.00"))
        .build();
SmsResult result = smsTemplate.send(single);

// 2. 本地事务提交后受管异步投递（TransactionSynchronization.afterCommit，回滚时零投递）
@Transactional(rollbackFor = Exception.class)
public void confirmOrderAndNotify(String phone, String orderNo) {
    SmsRequest txReq = SmsRequest.builder()
            .phone(phone)
            .templateId("ORDER_NOTIFY")
            .params(SmsParam.create().add("orderNo", orderNo).add("amount", "299.00"))
            .build();
    smsTemplate.sendAsyncAfterCommit(txReq);
}

// 3. 千人千面个性化差异批量群发（超限自动按 SmsSupplier.getBatchLimit() 切片）
BatchSmsRequest batchReq = BatchSmsRequest.builder()
        .templateId("SMS_DIFF_BATCH")
        .phoneParams(Map.of(
                "13800020001", SmsParam.create().add("name", "Alice").add("points", "100"),
                "13800020002", SmsParam.create().add("name", "Bob").add("points", "200")
        ))
        .build();
BatchSmsResult batchResult = smsTemplate.sendBatch(batchReq);
```

- **机制与边界说明**：
  - `sendAsync` / `sendAsyncAfterCommit` 与批量分片并发全量依托 `ateng-boot-core` 的受管线程池与 `AsyncContext` 四维上下文传播器，绝不创建野线程；
  - `sendAfterCommit` 在非活跃事务状态下会立即降级执行以保持向后兼容，批量请求受 `guard.batch-timeout` 限制防止局部慢响应拖垮聚合线程。

---

## 5. 验证码全生命周期治理与防爆破

- **场景与专属 YAML 配置**：登录/注册场景编排“重发冷却检查 -> 独立 `verify:` 滑窗频控 -> 安全随机生成 -> 强制 `urgent=true` 绕过夜间免打扰 -> 核验原子自毁 / 输错冻结”：

```yaml
ateng:
  boot:
    sms:
      verify-code:
        length: 6
        ttl: 5m
        resend-interval: 60s
        max-retry-attempts: 5
        char-type: NUMERIC
        default-template-id: SMS_VERIFY_CODE
      rate-limit:
        per-minute: 1
        per-hour: 5
        per-day: 10
```

- **Sample Java 业务调用片段**（提炼自 `SmsScenarioService`）：

```java
// 1. 生成验证码并投递（自动附加 60s 重发冷却与手机号+IP协同防刷）
smsVerifyCodeTemplate.generateAndSend("13800139001", "login", null, null, null, clientIp);

// 2. 多步骤表单只读预检（注意参数顺序：phone, code, bizType；不销毁验证码也不扣减错误次数）
boolean preValid = smsVerifyCodeTemplate.check("13800139001", inputCode, "login");

// 3. 最终核验并原子自毁（成功立即物理删除防重放；连续输错 5 次触发 5804 冻结锁定）
boolean passed = smsVerifyCodeTemplate.verify("13800139001", inputCode, "login");
```

- **机制与边界说明**：
  - 验证码防刷严格隔离于 `verify:` 命名空间，与业务通知 `notify:` 限流互不挤占额度；冷却未过优先快速拒单，避免虚耗每分钟滑动窗口计数；
  - 存在 `StringRedisTemplate` 时自动装载基于 Lua 脚本的 `RedisSmsVerifyCodeRepository` 与 `RedisSmsRateLimiter` 保障集群原子性，无 Redis 自动降级至有界内存实现（受 `in-memory-max-entries` 约束）。

---

## 6. 多租户动态切源、平滑负载均衡与容灾熔断

- **场景与专属 YAML 配置**：SaaS 租户动态加载渠道并在后台轮换密钥时触发 Redis Pub/Sub 集群热驱逐，叠加平滑加权轮询与熔断冷却自动恢复：

```yaml
ateng:
  boot:
    sms:
      registry:
        dynamic-cache-enabled: true
        dynamic-cache-max-size: 1024
        dynamic-cache-ttl: 30m
        evict-pub-sub-enabled: true
        evict-topic: ateng:sms:channel:evict
      guard:
        circuit-breaker-enabled: true
        circuit-breaker-failure-threshold: 5
        circuit-breaker-open-duration: 60s
```

- **Sample Java 业务调用片段**（提炼自 `SmsChannelController` & `SmsContextHolder`）：

```java
// 1. 运行时动态注册/更新并用 SmsContextHolder 定向切源发信（执行完毕自动清理 ThreadLocal）
smsTemplate.registerChannel(SmsChannel.builder()
        .name("tenant-1001-sms")
        .supplier(SmsSupplier.ALIYUN)
        .accessKeyId("YOUR_TENANT_AK")
        .accessKeySecret("YOUR_TENANT_SK")
        .signName("租户1001签名")
        .weight(2)
        .build());

SmsResult scopedResult = SmsContextHolder.runWithChannel("tenant-1001-sms",
        () -> smsTemplate.send(singleRequest));

// 2. 密钥变更后触发全集群动态渠道热驱逐（同步清空对应负载权重与熔断计数）
smsTemplate.evictChannelCache("tenant-1001-sms");

// 3. 平滑加权轮询跨多静态渠道均衡投递（自动避让 OPEN 熔断或配额已满渠道）
SmsResult lbResult = smsTemplate.sendByLoadBalance(singleRequest);
```

- **机制与边界说明**：
  - 渠道路由解析严格遵从：`SmsRequest#channel` -> `SmsContextHolder` -> `TenantContextHolder` 关联渠道 -> `defaultChannel`；
  - `evict-pub-sub-enabled` 注意中划线拼写（`pub-sub` 之间有连字符），存在 Redis 时自动通过 Pub/Sub 同步广播至全部应用实例，联动广播本地 `SmsChannelEvictEvent`。

---

## 7. 出站防卫、配额周期熔断与测试安全沙箱

- **场景与专属 YAML 配置**：非生产环境开启测试安全沙箱杜绝误发客户真实手机，配合 `notify:` 通知频控、业务 `bizId` 幂等与渠道 `DAILY`/`TOTAL` 周期配额硬防线：

```yaml
ateng:
  boot:
    sms:
      sandbox:
        enabled: true
        log-only: false
        allowed-phones:
          - "13800138*"
        redirect-to: "13900139000"
      guard:
        daily-max-quota: 10000
        blacklist-enabled: true
        notify-rate-limit-enabled: true
        idempotent-enabled: true
        idempotent-window: 3m
        quiet-hours-enabled: true
        quiet-hours-start: "22:00"
        quiet-hours-end: "08:00"
        auto-blacklist-on-unsubscribe: true
```

- **Sample Java 业务调用片段**（提炼自 `SmsGuardAndCallbackController` & `SmsChannelController`）：

```java
// 1. 携带 bizId 的业务幂等防重通知与夜间免打扰应急控制
SmsRequest notifyReq = SmsRequest.builder()
        .phone("13811112222")
        .templateId("SMS_IDEM_001")
        .bizId("PAY_NOTIFY_9876543210")
        .urgent(true)          // 夜间免打扰时段紧急放行
        .skipRateLimit(false)  // 为 true 时可豁免非验证码 notify: 滑动窗口限流
        .build();
smsTemplate.send(notifyReq);

// 2. 动态黑名单维护与渠道周期配额运维监控
smsTemplate.addBlacklist("13999998888", "高频投诉号码");
long usedQuota = smsTemplate.getChannelQuotaUsed("aliyun-primary");
smsTemplate.resetChannelQuota("aliyun-primary");
```

- **机制与边界说明**：
  - 若 `sandbox.enabled=true` 且目标手机不匹配 `allowed-phones`：当配置了 `redirect-to` 时统一改道测试号投递（同时原发信日志中手机号仍严格脱敏）；若未配置 `redirect-to` 且 `log-only=false` 则直接抛出 `SmsSendException` Fail-Fast 阻断；
  - 触发单日总预算上限时抛出 `SmsBudgetExceededException`（`5805`）并异步广播 `SmsBudgetExceededEvent`。

---

## 8. 双模可取消延迟调度、主动回执/余额探查与 Webhook 回调

- **场景与专属 YAML 配置**：预约通知、支付超时提醒支持 `MEMORY`（轻量受管线程池）与 `REDIS`（ZSet + Lua 原子认领跨重启持久化）双模调度，结合主动状态/余额探查及安全回调验签：

```yaml
ateng:
  boot:
    sms:
      delay:
        mode: REDIS               # MEMORY 单机调度 | REDIS 分布式持久化轮询
        pool-size: 2
        await-termination: 15s
        redis-poll-interval: 1s
        redis-batch-size: 100
        redis-key-prefix: "ateng:sms:delay:"
        max-status-history: 10000
      callback:
        enabled: true
        path-prefix: "/ateng-sms/callback"
        token: ${SMS_CALLBACK_TOKEN:YOUR_WEBHOOK_TOKEN}
        allow-query-token: false
        signature-required: true
        allowed-cidrs:
          - "10.0.0.0/8"
```

- **Sample Java 业务调用片段**（提炼自 `SmsDelayAndQueryController` & `SmsScenarioService`）：

```java
// 1. 提交相对延迟发送任务（入队仅校验合法性，自动绑定当前四维上下文与 SmsContextHolder）
SmsScheduledReceipt receipt = smsTemplate.sendDelayed(singleRequest, Duration.ofMinutes(30));

// 2. 业务逻辑完结前随时原子撤销尚未触发的延迟任务
boolean cancelled = smsTemplate.cancelScheduled(receipt.getTaskId());

// 3. 主动回查单条短信送达回执（不支持主动查询的厂商平滑返回 supported=false，不抛 NPE）
SmsDeliveryQueryResult delivery = smsTemplate.querySendStatus(
        "aliyun-primary", "13800138000", receipt.getTaskId(), LocalDate.now()
);

// 4. 只读探查云厂商账户余额或剩余包量（支持云片/助通/Mock，触发 0 条计费短信）
SmsBalanceResult balance = smsTemplate.queryBalance("yunpian-intl");
```

- **机制与边界说明**：
  - 遵循两阶段校验语义：如果用户在延迟期间退订拉黑，之前已入队的任务将在到点触发瞬间被实时黑名单拦截而不会穿透发出，且未到点撤销的任务 0 消耗预算与渠道配额；
  - Webhook 启用时默认强制通过请求头 `X-Callback-Token` 恒定时间比对，避免 Query 明文泄露；收到用户上行 `TD`/`退订`/`T` 且开启 `auto-blacklist-on-unsubscribe=true` 时自动沉淀至出站黑名单。

---

## 9. 可观测性：Micrometer 低基数指标与 K8s 安全探针

- **场景与专属 YAML 配置**：自动向 Prometheus/Micrometer 暴露低基数吞吐、耗时、重试、熔断切换与拦截指标；并在引入 `ateng-boot-observability` 时注册 `SmsSafeHealthIndicator`：

```yaml
ateng:
  boot:
    observability:
      health:
        sms:
          enabled: true
          cache-ttl: 60s        # 默认 60s 防刷缓存，0s 关闭缓存
          probe-balance: false  # 开启后仅对支持只读余额的静态渠道探测，绝不发送计费短信
```

- **指标提取与健康端点验证片段**：

```java
// 1. 通过 MeterRegistry 观测低基数指标（channel 动态渠道统一为 "dynamic"，绝无手机号标签）
Counter sentCounter = meterRegistry.find("ateng.sms.send.count")
        .tag("channel", "aliyun-primary")
        .counter();
Timer latencyTimer = meterRegistry.find("ateng.sms.send.duration").timer();
```

```bash
# 查看 K8s Readiness 中的短信基础设施健康状态
curl -X GET "http://localhost:8080/actuator/health/sms"
```

- **机制与边界说明**：
  - **严格低基数**：仅包含静态渠道名 / `dynamic` / `other`（独立模式上限 64）、`supplier`、`outcome` 等有限 Tag；
  - **探针安全与 0 计费**：仅检查 `SmsChannelRegistry#getAll()` 静态配置渠道，空白静态池返回 `UNKNOWN` 防止多租户全动态模式下 K8s 误杀重启；全部静态渠道熔断 `OPEN` 或凭据缺失时上报 `DOWN`；所有异常细节中的 AK/SK、代理密码及 `Bearer` Token 均强制掩码脱敏。

---

## 10. 扩展定制（SPI 全览）

所有核心能力接口在自动装配阶段均标配 `@ConditionalOnMissingBean`，业务工程只需注册自定义 Spring `@Bean` 即可无缝接管：

1. **接入自定义多厂商网关**：实现 `SmsProvider` 并覆写 `getSupplier()`（返回 `SmsSupplier.CUSTOM`）与 `getProviderCode()`（如 `"acme-gateway"`），配置侧指定 `channels.my-sms.provider-code: acme-gateway` 即可多实例并行，不互踩。
2. **动态租户渠道数据源**：实现 `SmsChannelProvider#loadChannel(String channelName)` 对接数据库/Nacos，与有界 LRU + `evictChannelCache` 无缝配合。
3. **自定义发送拦截链**：实现 `SmsSendInterceptor`（`Ordered` 排序），可在发送前后扩展企业敏感词审查、营销标签追加或独立计费上报。
4. **自定义异步最终失败补偿**：实现 `SmsSendFailureHandler` 在所有渠道与重试耗尽后执行钉钉/飞书告警、落盘补偿表重发。
5. **自定义调度与限流仓储**：按需替换 `SmsDelayScheduler`、`SmsRateLimiter`、`SmsVerifyCodeRepository`、`SmsBlacklistProvider`、`SmsQuotaGuard`、`DailyBudgetBreaker` 与 `SmsCallbackVerifier`。

---

## 11. 全量配置属性参考（YAML 完整清单）

```yaml
ateng:
  boot:
    sms:
      enabled: true                                # 是否启用短信底座，默认 true
      default-channel: mock                        # 默认发信渠道名称，默认 mock

      verify-code:
        length: 6                                  # 验证码位数，默认 6
        ttl: 5m                                    # 有效时长，默认 5m
        resend-interval: 60s                       # 同场景重发冷却时间，默认 60s
        max-retry-attempts: 5                      # 最大连续输错冻结上限，默认 5
        char-type: NUMERIC                         # NUMERIC | ALPHA_NUMERIC | UPPER_NUMERIC
        default-template-id:                       # 默认验证码模板逻辑标识

      rate-limit:
        per-minute: 1                              # 单号每分钟上限，默认 1
        per-hour: 5                                # 单号每小时上限，默认 5
        per-day: 10                                # 单号每天上限，默认 10

      guard:
        daily-max-quota: 0                         # 全系统日发信总上限（0 不限制），默认 0
        blacklist-enabled: true                    # 是否启用黑名单静默拦截，默认 true
        blacklist: []                              # 初始静态黑名单列表
        circuit-breaker-enabled: true              # 渠道健康熔断开关，默认 true
        circuit-breaker-failure-threshold: 5       # 连续失败熔断阈值，默认 5
        circuit-breaker-open-duration: 60s         # 熔断冷却时长，默认 60s
        idempotent-enabled: false                  # bizId 出站幂等开关，默认 false
        idempotent-window: 3m                      # 幂等去重窗口，默认 3m
        quiet-hours-enabled: false                 # 夜间免打扰拦截开关，默认 false
        quiet-hours-start: "22:00"                 # 免打扰起始时刻 (HH:mm)
        quiet-hours-end: "08:00"                   # 免打扰结束时刻 (HH:mm)
        auto-blacklist-on-unsubscribe: true        # 上行退订自动加黑，默认 true
        in-memory-max-entries: 50000               # 纯内存防护容器最大条目，默认 50000
        batch-timeout: 30s                         # 批量并发分片超时上限，默认 30s
        notify-rate-limit-enabled: false           # 非验证码通知 notify: 命名空间限流，默认 false
        log-desensitize-content: true              # 日志与审计事件内容掩码，默认 true
        sensitive-param-keys:                      # 脱敏敏感参数键名清单
          - code
          - captcha
          - pwd
          - password
          - pin
          - token
          - secret

      sandbox:
        enabled: false                             # 安全沙箱总开关，默认 false
        log-only: false                            # 纯日志空跑模拟（零物理发信），默认 false
        allowed-phones: []                         # 允许穿透白名单（支持 * 通配符）
        redirect-to:                               # 非白名单重定向测试号（为空时强阻断）

      registry:
        dynamic-cache-enabled: true                # 动态渠道 LRU 缓存开关，默认 true
        dynamic-cache-max-size: 1024               # 动态 LRU 最大条目上限，默认 1024
        dynamic-cache-ttl: 30m                     # 动态缓存 TTL，默认 30m
        evict-pub-sub-enabled: true                # Redis Pub/Sub 跨节点热驱逐广播，默认 true
        evict-topic: ateng:sms:channel:evict       # 热驱逐广播频道名

      delay:
        mode: MEMORY                               # MEMORY | REDIS
        pool-size: 2                               # 调度工作线程数，默认 2
        await-termination: 15s                     # 优雅停机排空超时，默认 15s
        redis-poll-interval: 1s                    # REDIS 模式轮询间隔，默认 1s
        redis-batch-size: 100                      # REDIS 模式单批认领上限，默认 100
        redis-key-prefix: "ateng:sms:delay:"       # REDIS 模式调度键前缀
        max-status-history: 10000                  # 状态轨迹最大保留条数，默认 10000

      callback:
        enabled: false                             # 统一 Webhook 网关开关，默认 false
        path-prefix: "/ateng-sms/callback"         # Webhook 路由前缀
        token:                                     # X-Callback-Token 鉴权密钥
        allow-query-token: false                   # 是否允许 URL Query 传 Token，默认 false
        allowed-cidrs: []                          # 回调源 IP CIDR 白名单
        signature-required: false                  # 是否强制开启云厂商签名校验，默认 false

      channels:
        aliyun-primary:
          supplier: ALIYUN                         # 服务商枚举（见 0.1 表）
          provider-code:                           # 自定义驱动覆写路由码
          access-key-id: YOUR_ACCESS_KEY_ID
          access-key-secret: YOUR_ACCESS_KEY_SECRET
          sign-name: 阿腾科技
          sign-id:                                 # 签名编码（部分厂商需要）
          ext-code:                                # 通道扩展码
          app-id:                                  # 腾讯 SmsSdkAppId / 华为云通道号
          region: cn-hangzhou
          endpoint:                                # 自定义网关端点 URL
          failover-channel:                        # 故障顺延备用渠道名
          timeout: 10s                             # HTTP 通信超时，默认 10s
          default-template-id:                     # 渠道默认模板编码
          status-callback:                         # 状态报告回调地址
          weight: 1                                # 负载均衡权重，默认 1
          max-retries: 0                           # 单渠道瞬态重试次数，默认 0
          retry-interval: 1s                       # 重试间隔，默认 1s
          international-enabled: false             # 是否开启 E.164 国际区号归一化，默认 false
          default-region-code: "+86"               # 默认国家码，默认 +86
          max-quota: 0                             # 渠道周期配额上限（0 不限制），默认 0
          quota-reset-cycle: DAILY                 # DAILY | TOTAL
          proxy:
            enabled: false
            host:
            port: 0
            username:
            password:
          extras: {}                               # 厂商私有扩展属性映射
          templates:
            LOGIN_AUTH:
              template-id: SMS_123456
              sign-name:                           # 覆盖渠道默认签名
              param-order: []                      # 位置参数推导顺序
```

---

## 12. 生产部署与演进参考

- **交互体验端与 cURL 实战**：参见 6 控制器场景样例 [`ateng-boot-samples/ateng-boot-sample-sms/README.md`](../ateng-boot-samples/ateng-boot-sample-sms/README.md)
- **领域通用语言与边界禁忌**：参见 [`CONTEXT.md`](./CONTEXT.md)
- **架构演进决策记录（ADR）**：
  - 基线架构：[`ADR-0094 企业级多渠道零依赖短信通信与治理架构`](../docs/adr/0094-enterprise-multi-channel-sms-architecture.md)
  - 全面强化与观测闭环：[`ADR-0110 短信多厂商生态、双模调度、安全防卫与可观测全面强化架构`](../docs/adr/0110-sms-enterprise-evolution-and-observability-architecture.md)
