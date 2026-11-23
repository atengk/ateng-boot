# 69. 邮件全场景工业级加固、批量邮件合并、多模测试沙箱、国际化多语言与连通性治理架构决策 (Mail Full-Scenario Industrial Hardening, Batch Mail Merge, Multi-Mode Sandbox, i18n & Connection Governance Architecture)

## 状态
`Accepted`

---

## 背景与痛点 (Context & Problem Statement)

在 [ADR-0042](./0042-industrial-grade-asynchronous-mail-and-template-architecture.md) 中，`ateng-boot-mail` 确立了面向企业级高并发发信与模板通知的工业级底座，构建了多命名账号静态注册表、多租户动态解析（`TenantMailAccountResolver`）、外部数据源懒加载（`MailAccountProvider`）、双轨防张冠李戴安全网、RFC 6266 大附件流式防爆与本地事务感知发信（`sendAfterCommit`）。

然而，随着微服务集群深水区业务下沉、海外多语言出海业务扩展以及企业对安全合规和非生产环境防误发的刚性要求，原邮件基础设施暴露出五大核心业务与生产痛点：

1. **批量个性化发信（邮件合并）缺乏受控高阶抽象 (Batch Mail Merge & Connection Bomb Void)**：
   业务高频面临“批量工资条发放”、“月度个性化账单”、“会员专属营销”等场景，每个收件人具有专属的正文变量乃至独立的 PDF 对账单附件。原门面仅支持单封邮件多收件人群发；若业务层通过循环同步调用，网络耗时巨大且单封失败即熔断后续；若直接无界抛入 `@Async`，海量并发连接瞬间触发各大 SMTP 邮件网关的防刷连接限制（`421 Too many concurrent connections`）甚至导致系统线程池排队溢出。
2. **非生产环境（Dev/UAT）真实用户邮件误发风险 (Dev/Test Mail Leakage & Lack of Sandbox Guard)**：
   在开发与测试环境中，测试数据库往往残留真实客户邮箱。当测试人员执行注册、审批或密码重置流程时，极易因疏忽将测试数据或验证码真实外发给外部用户，引发灾难性生产客诉与品牌信誉损失。原底座缺乏一等公民的测试安全沙箱拦截保护。
3. **模板动态渲染引擎缺失国际化多语言支持 (Monolingual Template SPI & Missing Locale Resolution)**：
   跨国电商与出海业务要求向不同国家/语言偏好的用户投递对应语言的邮件（如 `verify-code_en_US.html`）。原 `MailTemplateRenderer.render(templateName, variables)` 签名缺失 `Locale` 传参，无法触发 Thymeleaf/Freemarker 的级联语言解析，亦无法与 Spring 的 `LocaleContextHolder` 自动协同。
4. **管理端动态账号缺乏真实连通性健康探测 (Missing Connection Health Probe & Blind Operations)**：
   系统管理员在运营后台新增或修改 SMTP 账号（或轮换授权码）后，无法以安全、无副作用的方式进行一键连通性自验。原系统缺乏连通性探测门面，调用方被迫以真实发送垃圾邮件的方式进行连通性验证。
5. **合规归档审计密送与邮件协议重要性标头规范缺失 (Monolithic BCC Archiving & Untyped Headers)**：
   金融、政企合规要求外发邮件必须自动归档留底一份至企业统一审计邮箱（Compliance Archive）。原体系要求业务调用点处处手动调用 `message.bcc(...)`，极易因开发疏漏遗留违规隐患；同时紧急运维告警缺乏强类型 `MailPriority` 邮件重要性标头与阅读回执支持。

---

## 决策内容与架构拓扑 (Decision & Architecture)

经由深度场景对标与全场景推演，正式确立并落地 **“邮件全场景工业级加固、批量邮件合并、多模测试沙箱、国际化多语言与连通性治理”** 架构演进：

```mermaid
flowchart TD
    subgraph ClientLayer["应用调用接入层 (Caller / Business Services)"]
        direction TB
        CMD_SINGLE["单邮件命令式/DSL: send / sendAsync / sendAfterCommit"]
        CMD_BATCH["批量个性化发信: sendBatch / sendBatchAsync (Mail Merge)"]
        CMD_PROBE["连通性探测门面: testConnection / testConnectionOrThrow"]
    end

    subgraph MailTemplateFacades["MailTemplate 核心编排门面"]
        direction TB
        INTERCEPTOR_CHAIN["拦截器执行链 (MailSendInterceptor)"]
        SANDBOX_INTERCEPTOR["MailSandboxInterceptor (HIGHEST_PRECEDENCE)<br/>1. 白名单通配匹配 (allowed-recipients)<br/>2. 强制重定向 + 自动清空 CC/BCC (redirect-to)<br/>3. 纯日志拦截模拟模式 (log-only)"]
        COMPLIANCE_BCC["合规归档密送注入<br/>(archive-bcc 追加并大小写无关去重)"]
        RATE_LIMITER["客户端平滑限流治理"]
        TRACE_ID_INJECT["RFC 5322 Message-ID 追踪标头注入"]
    end

    subgraph BatchEngine["受控并发批量邮件合并引擎 (Batch Engine)"]
        direction TB
        CONCURRENCY_CTRL["受控并发切片器 (ateng.boot.mail.batch.concurrency=4)"]
        FAIL_SAFE_DISPATCH["Fail-Safe 独立派发与失败安全网触发"]
        AGGREGATE_RESULT["BatchMailSendResult 聚合回执<br/>(total, success, failure, duration, items)"]
    end

    subgraph RenderingEngine["国际化模板渲染引擎 (i18n Template SPI)"]
        direction TB
        SPI_LOCALE["MailTemplateRenderer.render(name, vars, Locale)"]
        LOCALE_DETECT["显式指定 Locale -> LocaleContextHolder 自动探测"]
        TPL_THYMELEAF["Thymeleaf 原生 Locale 级联寻址"]
        TPL_FREEMARKER["Freemarker 原生 Locale 级联寻址"]
        TPL_SIMPLE["SimpleMailTemplateRenderer 级联语言文件探测与占位符保底"]
    end

    subgraph ProbeGovernance["物理 SMTP 连通性探针"]
        direction TB
        TRANSPORT_CONNECT["物理 Transport.connect()<br/>(TCP三次握手 + TLS协商 + AUTH认证)"]
        PROBE_SAFE_CLOSE["确定性 Transport.close()<br/>(零发信副作用，毫秒级快速返回)"]
    end

    ClientLayer --> MailTemplateFacades
    MailTemplateFacades --> SANDBOX_INTERCEPTOR
    MailTemplateFacades --> COMPLIANCE_BCC
    MailTemplateFacades --> INTERCEPTOR_CHAIN
    CMD_BATCH --> BatchEngine
    BatchEngine --> MailTemplateFacades
    MailTemplateFacades --> RenderingEngine
    CMD_PROBE --> ProbeGovernance
```

### 1. 批量个性化发信与邮件合并引擎 (Batch Mail Merge Engine)
- **受控并发与网关防刷保护**：
  提供 `BatchMailSendResult sendBatch(List<MailMessage> messages)` 与 `CompletableFuture<BatchMailSendResult> sendBatchAsync(List<MailMessage> messages)`；
  引入配置项 `ateng.boot.mail.batch.concurrency`（默认 4，范围 1~32），基于受控分片并发池投递，杜绝海量邮件瞬间打爆 SMTP 服务商并发上限（`421 Too many connections`）或引发线程池排队溢出；
- **Fail-Safe 独立容错与聚合回执**：
  单封邮件失败不阻断批次内其他邮件投递，每封邮件独立执行指数退避重试并触发全生命周期事件与补偿 SPI；聚合返回强类型 `BatchMailSendResult`，记录总数、成功数、失败数、总耗时以及有序的 `List<MailSendResultItem>` 明细。

### 2. 多模测试沙箱防护体系 (Multi-Mode Mail Sandbox Guard)
- **前置最高优先级拦截**：
  引入 `ateng.boot.mail.sandbox` 配置属性与内置 `MailSandboxInterceptor`，设定为最高优先级（`Ordered.HIGHEST_PRECEDENCE`），确保在所有业务拦截器之前生效；
- **三模防护策略**：
  1. **白名单放行**：通过 `allowed-recipients`（如 `*@internal.example.com`、`developer@example.com`）精准放行；
  2. **强制重定向与防泄密清场**：非白名单邮件自动将 `to` 替换为 `redirect-to`（如 `mail-trap@example.com`），**同时绝对清空原邮件的 `cc` 与 `bcc`**，杜绝任何外部人员泄露，并在主题追加 `[SANDBOX-REDIRECT to: original@...]` 标头；
  3. **纯日志模拟 (`log-only=true`)**：不发起任何物理网络连接，直接在控制台输出脱敏完整邮件报文并返回虚拟成功回执（Message-ID 标识为 `<sandbox-log-${traceId}@domain>`）。

### 3. 模板渲染引擎 SPI 国际化演化 (i18n Template SPI)
- **SPI 平滑扩展**：
  在 `MailTemplateRenderer` 接口中以默认方法演进：
  ```java
  default String render(String templateName, Map<String, Object> variables, Locale locale) {
      return render(templateName, variables);
  }
  ```
- **双轨语言寻址与自动探测**：
  `MailMessage` 挂载 `Locale locale` 字段；`MailTemplate` 增加支持 `Locale` 的发信重载；未显式传参时自动探测 Spring `LocaleContextHolder.getLocale()`；
- **保底引擎级联寻址**：
  Thymeleaf 与 Freemarker 原生根据 `Locale` 加载语言变体；零依赖保底引擎 `SimpleMailTemplateRenderer` 实现 `template_en_US.html` $\rightarrow$ `template_en.html` $\rightarrow$ `template.html` 级联探测与占位符替换。

### 4. 物理 SMTP 连通性健康探测 (Connection Health Probe)
- **真实握手与零发信副作用**：
  在 `MailTemplate` 与 `MailAccountRegistry` 暴露 `boolean testConnection(String accountName)` 与 `void testConnectionOrThrow(String accountName)`；
  底层调用 `JavaMailSenderImpl.getSession().getTransport()` 执行 `connect()`，真实走完 TCP 握手、TLS 协商与 AUTH 账号密码校验，随后立即 `close()`，不发送任何实际邮件；
- **双模 API 支撑**：
  布尔方法用于监控心跳与快速探测，异常方法在失败时完整抛出附带原始因果链的 `MailSendException`，支撑管理后台 UI 精准弹窗。

### 5. 合规密送归档与强类型邮件标头 (Compliance Archiving & Typed Headers)
- **配置驱动的自动合规归档**：
  支持全局与账号级 `archive-bcc: compliance@example.com`，前置编排链自动追加至 `message.bcc` 列表，执行大小写无关的 `Set` 去重，并在脱敏日志中安全留痕；
- **强类型邮件优先级与回执**：
  引入强类型枚举 `MailPriority`（`HIGH`, `NORMAL`, `LOW`）；`MailMessage` 提供链式 DSL：`priority(MailPriority.HIGH)` 自动注入 `X-Priority: 1` 与 `Importance: High`；提供 `readReceiptTo(email)` 与 `requestReadReceipt(true)` 自动注入 RFC 3798 `Disposition-Notification-To` 回执请求标头。

---

## 增量演进：2026-11 全场景生产加固 (Production Hardening Addendum)

为彻底补全多租户集群协同、企业网关协议合规、主备高可用与云原生可观测闭环，在保持既有 ADR-0069 主体架构 100% 向后兼容的前提下，于同一演进基线下增量落地六项生产级治理能力：

1. **三阶出站防御（限流 / 日配额熔断 / 业务幂等）与双模实现**：
   - 新增 `MailRateLimiter`（发信账号维度令牌桶 + 单收件人维度滑动窗口，按去重实际物理收件人次计费，超限抛出 `MAIL_RATE_LIMITED`）、`MailDailyQuotaBreaker`（自然日去重收件人次硬熔断，超限抛出 `MAIL_QUOTA_EXCEEDED` 并同步发布 `MailQuotaExceededEvent` 解耦事件）以及 `MailIdempotentGuard`（默认 `require-biz-id=true` 仅对显式携带 `bizId` 的消息抢占防重锁，物理发信失败主动执行 `releaseLock` 支持补偿重试）；
   - 全系防卫策略默认 `enabled=false` 保持零侵入，且同时内置单机内存 LRU 实现（`InMemory*`）与 Redis Lua 原子分布式实现（`Redis*`，标配 `fail-open-on-redis-error` 容错开关，无 Redis 时自动平滑降级）。
2. **主备账号顺延容灾 (`backupAccounts`) 与 SPF/DMARC 域对齐**：
   - 在账号重试耗尽遇到瞬态网络超时、连接拒绝或 SMTP 网关 421/5xx 可用性故障时，按 `backupAccounts` 有序队列自动切换至备用账号重试并记录 `Failover` 指标；遇认证失败（含令牌刷新后仍失败）、非法收件人地址、拦截器阻断及出站防护拒绝等确定性错误时立即 Fail-Fast，绝不触发备用账号切换；
   - 通过 `ateng.boot.mail.failover.override-from-on-switch`（默认 `true`）自动将外发信封与 `From` 标头对齐至备用账号所属发件邮箱，并将原主账号发件地址自动写入 `Reply-To` 保留业务回复链路，避免严格 SPF/DMARC 域策略拒信。
3. **认证扩展 (`AuthMode` + `XOAUTH2` + `sslTrust`) 与退信/退订合规协议**：
   - 确立强类型 `AuthMode` 枚举（`AUTO` / `PASSWORD` / `NONE` / `XOAUTH2`），搭配 `MailAccessTokenProvider` SPI 在初次鉴权失败时执行单次受锁刷新重试，并支持内网自签名网关配置 `ssl-trust`；
   - `MailMessage` 新增单封隔离的 SMTP Envelope `returnPath`（绝不污染共享 `Session`）、RFC 2369 `listUnsubscribe` 与 RFC 8058 `listUnsubscribePost`（清洗 CR/LF/NUL 防头部注入），并在 `SimpleMailTemplateRenderer` 增加 `ateng.boot.mail.template.html-escape` 可选防 XSS 转义开关。
4. **分片批量合并升级（片内 `Transport` 复用 + `sendBatchAfterCommit` + 整批超时熔断）**：
   - 重构批量引擎 `MailBatchSupport`，基于 `ateng-boot-core` 的 `BatchSliceUtil` 按 `batch.slice-size`（默认 50）切分批处理片区，在启用 `batch.reuse-transport=true` 时按路由账号片内复用同一物理 `Transport` 连续投递并强制收敛至 `batch.timeout`（默认 5 分钟）总超时，在超时后按 Fail-Safe 标为失败并确定性关闭遗留句柄；同时补齐 `MailTemplate` 与 `DynamicMailSession` 的 `sendBatchAfterCommit*` / `sendBatchAsyncAfterCommit*` 对称契约。
5. **低基数 Micrometer 指标与防假死静态账号探针 (`MailSafeHealthIndicator`)**：
   - `ateng-boot-mail` 通过弱依赖 `MailMetricsRecorder` / `MailMetrics` 暴露 `ateng.mail.send.count`、`ateng.mail.send.duration`、`ateng.mail.retry.count`、`ateng.mail.failover.count` 及静态/动态账号 `Gauge`，强制将动态租户账号收敛为 `dynamic`（独立运行上限 64 截断为 `other`），严禁收件人/主题进入标签；
   - `ateng-boot-observability` 协同落地继承自 `AbstractSafeHealthIndicator` 的 `MailSafeHealthIndicator`，仅对静态账号列表执行物理探活（跳过动态账号且默认启用 `60s` `health.mail.cache-ttl` 防刷防封号，异常细节强制脱敏）。
6. **双模式顶级单账号快捷配置**：
   - `MailProperties` 新增 `allow-empty-accounts` 与顶级单账号扁平属性（`ateng.boot.mail.host/port/.../backup-accounts`）及其别名字段对齐，兼顾单账号微服务极简配置与多账号映射治理。

---

## 影响与收益 (Consequences)

1. **企业高频场景 100% 覆盖**：批量工资条/账单合并分发、国际化多语言邮件、非生产沙箱防误发、合规密送全面开箱即用；
2. **高可用与安全性全维加固**：受控并发消灭网关限频封禁隐患，沙箱强力阻断测试邮件外泄，深度握手探针赋能运维实时排查；
3. **100% 向后兼容与零侵入升级**：所有既有 API 签名完全保留，新增特性配置默认为关闭状态，保障老应用无感平滑升级。
