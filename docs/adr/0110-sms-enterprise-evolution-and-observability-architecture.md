# ADR-0110: 短信多厂商生态、双模调度、安全防卫与可观测全面强化架构

- **状态 (Status)**: `Accepted`
- **日期 (Date)**: `2026-11-21`
- **关联模块 (Modules)**: `ateng-boot-sms`、`ateng-boot-observability`、`ateng-boot-core`、`ateng-boot-samples/ateng-boot-sample-sms`
- **演进溯源 (Supersedes / Extends)**: 在 [ADR-0094](./0094-enterprise-multi-channel-sms-architecture.md) 零 SDK 依赖多渠道短信基线之上完成全量演进与跨模块观测闭环（对应重构演进：`#1145` / `#1146` / `#1148` / `#1149`）。

---

## 1. 背景与痛点 (Context)

`ateng-boot-sms` 在 ADR-0094 初版落地后面临以下生产级扩容与治理缺口：
1. **厂商生态覆盖不足与扩展单点瓶颈**：仅覆盖阿里/腾讯/华为/天翼/云片/Mock，且早期的 `CUSTOM` 仅靠单一枚举路由无法并存多个自定义 HTTP 网关驱动，同时缺少渠道级 HTTP 正向代理、E.164 国际区号归一化与纯文本直发能力。
2. **动态租户运维与出站成本安全缺失**：动态加载渠道缺少有界 LRU 与 Redis Pub/Sub 集群热驱逐同步；缺少按渠道 `DAILY`/`TOTAL` 周期计费配额硬熔断；非生产环境缺乏测试安全沙箱导致数据库导流测试易误发真实客户手机号；普通通知与验证码共用或混杂频控策略缺乏清晰命名空间边界；日志与 `SmsSendAuditEvent` 易打印明文验证码。
3. **异步延时调度与对账巡检能力空白**：电商超时催付、预约通知场景缺少可取消的延迟/定时调度，且纯内存定时任务集群重启易丢失；投递后缺少统一门面主动回查送达状态与只读探查账户余额，且大量仅支持发信的网关缺少平滑降级契约。
4. **可观测与防高基数爆表缺位**：缺少与 `ateng-boot-observability` 对接的低基数 Micrometer 指标体系及 K8s 安全健康探针（尤其动态多租户渠道不能作为标签直接透出，且健康检查绝不能消耗计费短信）。

---

## 2. 决策内容与落地设计 (Decision)

### 2.1 纯原生 15 家驱动矩阵与 `CUSTOM` 多 `providerCode` 路由

- 全量扩展至 **15 大内建驱动**（新增百度云 `BAIDU`、京东云 `JDCLOUD`、七牛云 `QINIU`、网易云信 `NETEASE`、容联云 `CLOOPEN`、创蓝 253 `CHUANGLAN`、中国移动云 MAS `MAS`、合一 `UNISMS`、助通科技 `ZHUTONG`），全部继续坚守 **0 第三方厂商 SDK**、纯 JDK 17 + Spring 6 `RestClient` 实现。
- `SmsProvider#getProviderCode()` 支持在同一 JVM 内并行注册多个 `SmsSupplier.CUSTOM` 驱动并用 `providerCode` 区分；沉淀 `AbstractHttpSmsProvider` 统一收口渠道级正向 HTTP 代理（含 Basic Auth）、瞬态网络异常按渠道 `maxRetries`/`retryInterval` 退避重试及重试打点。
- 通过 `SmsPhoneNormalizer`（支持 E.164 `+` 前缀与国内默认区域号补齐）和 `SmsSupplier#plainTextSupported` 精准裁决无模板纯文本正文直发。

### 2.2 动态渠道治理、周期配额、安全沙箱与隐私防泄漏

- **动态渠道供给与热驱逐**：引入 `SmsChannelProvider` SPI、带 TTL 的有界 `BoundedLruMap` 动态渠道缓存与 Redis Pub/Sub 跨节点热驱逐广播（`RedisSmsChannelEvictBroadcaster` -> `SmsChannelEvictListener`）；调用 `evictChannelCache` 联动清理负载权重及熔断状态并广播 `SmsChannelEvictEvent`。
- **渠道周期配额与安全沙箱**：引入 `SmsQuotaGuard`（`DAILY` 自然日清零 / `TOTAL` 总量包干，默认内存 + Redis 条件升级）；引入 `SmsSandboxInterceptor` 支持 `log-only` 零物理外发空跑、通配符 `allowedPhones` 穿透、非白名单 `redirectTo` 重定向及缺省阻断。
- **双命名空间频控与日志掩码**：将非验证码通知限流独立归口于 `notify:` 命名空间（`notifyRateLimitEnabled` 控制且 `skipRateLimit` 豁免），与 `SmsVerifyCodeTemplate` 使用的 `verify:` 严格互不干扰；`SmsDesensitizeSupport` 默认对日志与审计快照中的敏感键名及内容执行脱敏遮罩（严守 ADR-0012 内存真明文）。
- **虚拟线程合规**：全模块（注册表锁、滑窗熔断器、有界 LRU、内存调度轨迹、指标统计）100% 采用 `ReentrantLock` 或 CAS，杜绝 `synchronized` 引起的虚拟线程 Carrier Pinning（ADR-0029）。

### 2.3 双模可取消延迟/定时调度与两阶段校验不变量

- `SmsDelayScheduler` 提供双模式切换（`ateng.boot.sms.delay.mode=MEMORY|REDIS`）：
  - **MEMORY 模式**：基于 Spring `ThreadPoolTaskScheduler` 托管线程执行，`shutdown` 时遵循 `awaitTermination` 优雅排空；任务封装 `AsyncContext.wrap` 确保触发瞬间完整还原 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 四维上下文及 `SmsContextHolder` 渠道快照并在 `finally` 恢复。
  - **REDIS 模式**：使用 Redis ZSet 存储到期时间戳并通过 `CLAIM_DUE_LUA` / `CANCEL_LUA` 脚本原子认领或撤销，持久化载荷显式平铺字段并携带上下文快照，确保多副本分布式互斥消费与容器重启防丢失。
- **两阶段校验不变量**：`sendDelayed` / `sendAt` / `sendBatchDelayed` / `sendBatchAt` 入队瞬间仅校验参数基础合规、渠道存在性与模板/纯文本能力（Fail-Fast），**绝不提前执行**黑名单核查、日预算扣减或渠道配额扣减；到期真正投递时重新进入实时 `send` / `sendBatch` 执行全套防卫链。

### 2.4 主动送达回查、只读余额探查与 `supported=false` 契约降级

- `SmsTemplate#querySendStatus` 与 `SmsProvider#querySendStatus` 标准化单条状态回查：阿里云采用 `QuerySendDetails` POP HMAC-SHA1、腾讯云采用 `PullSmsSendStatus` TC3-HMAC-SHA256、Mock 驱动支持内存记录回查。
- `SmsTemplate#queryBalance` 与 `SmsProvider#queryBalance` 标准化只读余额探查（仅发送只读 HTTP 查询，绝不消费任何计费短信）：云片采用 `/v2/user/get.json`、助通采用 `/v2/balance`、Mock 驱动支持配置桩余额。
- 未提供相应查询接口的厂商驱动默认返回 `supported=false` 契约对象（`SmsDeliveryQueryResult.unsupported(...)` / `SmsBalanceResult.unsupported(...)`），彻底消灭 `UnsupportedOperationException` 或 `NullPointerException`。

### 2.5 低基数 `SmsMetrics` 与零计费 `SmsSafeHealthIndicator`

- `SmsMetrics` 仅在引入 `micrometer-core` 时条件装配，输出 `ateng.sms.send.*`、`ateng.sms.retry.count`、`ateng.sms.failover.count`、`ateng.sms.circuit_breaker.transition.count`、`ateng.sms.rate_limit.rejected.count`、`ateng.sms.intercepted.count` 及静态/动态渠道 Gauge；核心采用双层高基数熔断护栏（静态 YAML 渠道保留名 -> 动态供给渠道统一收敛为 `channel="dynamic"`；独立使用上限 64 个基数 -> 溢出折叠为 `"other"`）。
- `ateng-boot-observability` 弱依赖引入 `ateng-boot-sms` 并装配 `SmsSafeHealthIndicator`（继承 `AbstractSafeHealthIndicator`）：
  1. **静态池限定与空白态 `UNKNOWN`**：仅检视 `registry.getAll()` 中的静态渠道，剔除动态租户缓存，无静态渠道时返回 `UNKNOWN` 避免误伤 Pod 就绪。
  2. **熔断状态与可选只读余额**：全部静态渠道熔断 `OPEN` 时上报 `DOWN`；当 `ateng.boot.observability.health.sms.probe-balance=true` 时仅对支持只读余额接口的渠道执行探查，100% 保证触发 0 条计费短信，日志/详情绝不回显凭据；内置默认 `60s` 防刷缓存（`cache-ttl`）。

---

## 3. 后果与权衡 (Consequences & Trade-offs)

### 正向收益
1. **覆盖面与安全性跃升**：从 6 驱动扩展至全国内主流 14 云商 + Mock + 多实例 `CUSTOM`，并通过测试沙箱、周期配额与防高基数指标打通从测试到生产的最后一公里安全防线。
2. **体验端与文档 1:1 闭环**：`ateng-boot-sample-sms` 解耦至 6 大领域 Controller（含新增的 `SmsDelayAndQueryController`）及 DTO/VO 严格隔离，`ateng-boot-sms/README.md` 与 Sample 文档通过 Sample-First 逆向提取对齐所有真实 YAML 键名（消除历史文档 `circuit-breaker.*` 等漂移键名）。

### 妥协成本
1. **未支持查询接口厂商契约降级**：部分云厂商未开放或未统一提供单条实时回执/只读余额 REST API（如纯网关类通道或仅推送 Webhook 通道），当前选择通过 `supported=false` 结构化返回值显式告知上层而非强行模拟或抛异常，调用方需以 Webhook (`SmsDeliveryReportEvent`) 为主流对账手段、以主动查询为补充兜底。
2. **14 家协议签名自研维保**：自研各厂商专有 HMAC/WSSE/MD5 算法代码内聚于各 `SmsProvider` 实现类且由 MockWebServer 覆盖协议级单测，后续若单厂商升级鉴权版本需在对应单类同步维护，换取了零三方 SDK 传递依赖碰撞的长治久安。
