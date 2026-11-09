# 32. 运行时安全与流量治理架构决策 (Web Runtime Audit, Safe Payload Sampling & Traffic Governance Architecture)

## 状态
`Accepted`

---

## 背景与痛点 (Context & Problem Statement)

在企业级微服务与现代化云原生单体架构演进中，Web 接入层作为流量接入与安全防护的第一道防线，面临日益复杂的运行时治理挑战。传统的实现方案多存在散乱、脆弱与隐性高危缺陷：

1. **ServletInputStream 阅后即焚与多次消费冲突 (Single-Read Stream & Pipeline Starvation)**：
   Servlet 规范中的原生 `ServletInputStream` 为一次性流，调用方读取后即耗尽。在实际生产场景中，API 签名校验（`@ApiSign`）、HTTP Payload 采样日志（`PayloadLoggingFilter`）以及安全审计往往需要多次重复消费请求体。若无统一底座可重读包装管道，各过滤器不得不各自为政或因流枯竭导致管道断裂；若采用无边界的粗暴内存缓存，在大文件上传或恶意超大载荷下极易直接引发 JVM OOM 崩溃。
2. **操作审计切面与业务紧密耦合及数据越界 (Coupled Audit & In-Memory Entity Tampering)**：
   传统操作审计日志通常直接硬编码侵入 Controller/Service 业务逻辑，或死绑定在特定关系型数据库上，缺乏异步存储解耦 SPI；审计元数据缺乏全链路四维上下文（TraceId、UserId、Username、TenantId、ClientIp）的自动穿透与结构化对齐；更致命的是，部分实现为了实现出参数据脱敏，在业务层直接修改内存实体的敏感属性打星号，严重违背 [ADR-0012](./0012-lifecycle-separated-data-security.md) 确立的“内存真明文”不变量，导致被掩码污染的脏数据意外持久化回写至数据库。
3. **K8s 云原生缩容/滚动更新发布断流与 502/504 故障 (K8s PreStop Drain Lag & 502 Bad Gateway)**：
   在 Kubernetes 容器化部署中，当 Pod 发生缩容或版本滚动更新销毁时，kubelet 会并行向 Pod 发送 `SIGTERM` 终止信号并将 Pod 从 Service Endpoints 中摘除。然而，由于 kube-proxy 与 Ingress 网关路由表同步存在客观的网络传播与刷新延迟（通常需 1~5 秒），若 Spring Boot 容器在收到信号后立即关闭端口或终止线程池，上游路由仍在转发的在途 HTTP 请求将被直接连接重置，造成高频的 HTTP 502 Bad Gateway 与 504 Gateway Timeout 生产事故。
4. **隐蔽长耗时慢请求难以定位现场与误报干扰 (Undetected Slow Requests & Long-Connection False Alarms)**：
   线上生产环境中，偶发性慢 SQL、外部第三方 API 延迟抖动或深层循环计算会导致部分 HTTP 接口响应时间显著恶化，甚至耗尽 Servlet 容器线程池。传统做法依赖 APM 系统或排查整体日志，往往缺乏毫秒级就近在请求出口处输出带 TraceId、客户端 IP 与耗时上下文的主动告警；同时，若缺乏智能识别机制，容易将 WebSocket 升级长连接、Server-Sent Events（SSE）长推事件、文件分块下载以及异步挂起任务等正常长耗时场景误判为慢请求，引发严重的误报风暴。
5. **HTTP Payload 打印的 OOM 崩溃、凭据泄露与客户端挂起 (Payload Logging Vulnerabilities)**：
   为了排查接口集成交互故障与外部三方回调，开发与运维人员迫切需要在日志中打印完整的请求与响应报文。然而直接使用传统的 Payload 过滤器隐藏着三大高危陷阱：
   - **OOM 崩溃风险**：将大文件上传（Multipart）或二进制流无脑转为全量字符串读取，极易瞬间撑爆堆内存；
   - **凭据合规泄露**：请求入参中的登录密码、API Token、私钥及 Authorization 请求头若以明文落盘日志，将直接违反网络安全法与数据安全红线；
   - **响应截留挂起**：使用 `ContentCachingResponseWrapper` 拦截响应体后，若在异常或降级分支遗漏调用 `copyBodyToResponse()`，客户端将永久挂起或接收到空响应。

---

## 决策内容与架构拓扑 (Decision & Architecture)

经系统性推演与高可用基线设计，`ateng-boot` 正式确立 **Web 运行时安全与流量治理架构体系 (Web Runtime Audit, Safe Payload Sampling & Traffic Governance Architecture)**，全面内聚于 `ateng-boot-web` 模块，并与 `ateng-boot-core` 共享通用契约。

```mermaid
flowchart TD
    subgraph Client["调用方 / K8s 控制平面"]
        K8sPreStop["K8s preStop 钩子 / SIGTERM 信号"]
        HttpClient["HTTP 客户端 / Ingress 网关"]
    end

    subgraph WebFilters["ateng-boot-web 过滤器执行流水线"]
        TraceFilter["TraceFilter (ORDER)\n提取/生成 TraceId, 注入 MDC"]
        ContentCachingFilter["ContentCachingFilter (ORDER+1)\n包装 CachedBodyHttpServletRequest (可重读 + 防 OOM)"]
        PayloadLoggingFilter["PayloadLoggingFilter (ORDER+2)\nMIME过滤 + 4KB截断 + 敏感掩码 + 单行聚合 [HTTP_PAYLOAD]"]
        SlowRequestFilter["SlowRequestFilter (ORDER+3)\n耗时统计 + 智能长连接放行 + [SLOW_REQUEST] WARN"]
    end

    subgraph DrainLifecycle["云原生就绪探针排空与生命周期治理"]
        PreStopEndpoint["/actuator/pre-stop (Actuator 端点)"]
        PreStopController["/pre-stop (MVC 降级端点)"]
        ShutdownHandler["AtengGracefulShutdownHandler\n- AvailabilityChangeEvent(REFUSING_TRAFFIC)\n- drain-timeout 静默排空 (默认 10s)\n- AtomicBoolean 状态机防重入\n- SmartLifecycle 优先级最高兜底"]
    end

    subgraph MVCDispatcher["Spring MVC 调度与切面"]
        Dispatcher["DispatcherServlet -> Controller"]
        OperateLogAspect["@OperateLog 切面 (OperateLogAspect)\n- 关联四维全链路上下文 (Trace/User/Tenant/IP)\n- 出参联动 @FieldSensitive 视图脱敏 (真明文不变量)\n- 异常记录因果链 100% 重抛"]
    end

    subgraph SPIExt["扩展点与输出目标"]
        OperateLogSPI["OperateLogStorageProvider SPI"]
        DefaultStorage["DefaultOperateLogStorageProvider\n(SLF4J 格式化 JSON 保底)"]
        CustomStorage["用户自定义存储实现\n(ES / Kafka / 数据库)"]
    end

    HttpClient --> TraceFilter
    TraceFilter --> ContentCachingFilter
    ContentCachingFilter --> PayloadLoggingFilter
    PayloadLoggingFilter --> SlowRequestFilter
    SlowRequestFilter --> Dispatcher
    Dispatcher --> OperateLogAspect
    OperateLogAspect --> OperateLogSPI
    OperateLogSPI --> DefaultStorage
    OperateLogSPI -.-> CustomStorage

    K8sPreStop --> PreStopEndpoint
    K8sPreStop --> PreStopController
    K8sPreStop --> ShutdownHandler
    PreStopEndpoint --> ShutdownHandler
    PreStopController --> ShutdownHandler
```

### 1. 通用请求体可重读管道下沉与向后兼容 (`ContentCachingFilter`)
- **下沉归位与条件激活**：将原散落在加签模块内部的流缓存逻辑提升为通用 Web 底座，命名为 `ContentCachingFilter` 与 `CachedBodyHttpServletRequest`。引入条件装配 `ContentCachingCondition`：仅当配置了加签（`ateng.boot.web.sign.enabled=true`）或 Payload 日志（`ateng.boot.web.payload-log.enabled=true`）时才自动激活；两者均未开启时跳过装配，实现运行时零额外包装与零性能损耗。
- **可重复消费与防 OOM 不变量**：
  - `CachedBodyHttpServletRequest` 将请求体安全缓冲至受控的 `byte[]` 中，并发重写 `getInputStream()` 与 `getReader()`，支持后续所有中间件、切面与业务 Controller 无限次重复消费；
  - 严格设立强类型载荷上限 `max-body-size: 2MB`（`DataSize`），若 `Content-Length` 请求头或实际流式读取字节数超出阈值，立即抛出受控的业务异常 `BusinessException(CommonResultCode.REQUEST_BODY_TOO_LARGE)` 实施快速失败，从根本上杜绝恶意构造的超大请求体打爆 JVM 堆内存；
  - 智能过滤非目标请求：仅对 `POST`/`PUT`/`PATCH` 且为文本/JSON 类型实施包装，文件上传（`multipart/form-data`）与表单（`application/x-www-form-urlencoded`）自动放行不予缓存；
  - 原加签模块的 `RequestBodyCachingFilter` 声明 `@Deprecated`，内部完全委托至通用 `ContentCachingFilter`，实现 100% 向后兼容。

### 2. 声明式结构化操作审计切面与存储解耦 SPI (`@OperateLog` & `OperateLogStorageProvider`)
- **企业级标准化契约**：在 `ateng-boot-core` 下沉 `OperateLogModel` 标准审计实体与 `OperateTypeEnum` 操作类型枚举，确立跨模块解耦的标准元数据契约。
- **声明式 AOP 切面**：在 `ateng-boot-web` 落地 `@OperateLog` 注解与 `OperateLogAspect`。标注于 Controller 方法上，支持定义业务模块（`module`）、操作类型（`type`）、操作描述（`description`）、入参/出参保存开关（`saveParams`/`saveResult`）及敏感参数排除列表（`excludeParams`）。
- **四维上下文全链路对齐**：切面执行时自动穿透并提取全链路元数据快照：MDC `traceId`、当前操作人 ID/姓名（`UserContextHolder`）、当前租户 ID（`TenantContextHolder`）及真实客户端 IP（`IpUtil.getClientIp()`），实现全维审计追溯。
- **坚守 ADR-0012“内存真明文”不变量**：
  - 出参记录时，自动联动实体上的 `@FieldSensitive` 注解完成视图层脱敏转换；
  - 严格使用脱敏后的克隆/序列化副本记录至审计模型，业务实体对象在内存中始终保持 100% 原始真实明文，彻底消除传统方案中将脱敏星号写回数据库造成数据损毁的灾难性隐患。
- **存储解耦 SPI 架构**：对外抽象 `OperateLogStorageProvider` SPI 扩展点接口，开箱内置 `DefaultOperateLogStorageProvider`（基于受管 SLF4J 异步格式化输出 JSON），用户可通过实现接口平滑接管投递至 Kafka、Elasticsearch 或关系型数据库。
- **严格遵循成熟度因果异常红线**：业务方法若抛出异常，切面在捕获后准确标记操作状态为失败（`status = 1`）并记录异常摘要，在 `finally` 块完成异步事件投递后 **100% 原样重抛原始异常**，严禁吞咽异常或二次包装破坏因果调用链。

### 3. K8s 云原生就绪探针排空与容器生命周期治理 (`AtengGracefulShutdownHandler`)
- **主动反转就绪探针**：收到容器下线信号时，第一时间通过 Spring 核心事件发布机制广播 `AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC)`。Kubernetes 的 readinessProbe 探针即刻探测到就绪失效，驱动外部 Ingress / kube-proxy 立即将该 Pod 从 Endpoints 列表中摘除，不再下发任何新流量。
- **受管静默排空与在途保活**：探针反转后，线程受控执行静默排空等待（可配置 `drain-timeout: 10s`，强类型 `Duration`），留出充裕的缓冲时间供外部路由表同步刷新完成，并确保已到达的在途请求顺利执行完毕，彻底终结发布与缩容过程中的 502/504 偶发报错。
- **双模主动触发端点支持**：
  - **Actuator 环境**：自动装配 `@Endpoint(id = "preStop")`，暴露 `/actuator/pre-stop`（支持 HTTP GET 与 POST），与 K8s Pod Lifecycle preStop 钩子标准化联动；
  - **无 Actuator 轻量环境**：条件降级自动注册轻量级 Spring MVC 控制器 `PreStopController`，暴露 `/pre-stop`（支持 HTTP GET 与 POST），确保零额外依赖下开箱即用。
- **`SmartLifecycle` 兜底保护不变量**：实现 Spring `SmartLifecycle` 接口，显式声明优先级为最高值 `getPhase() = Integer.MAX_VALUE`（早于嵌入式 WebServer 停机执行）。即使 K8s Deployment 未配置 preStop 脚本直接下发 `SIGTERM` 信号，也能在 Spring 容器关闭前自动拦截触发就绪探针拒绝与静默排空保护。
- **并发原子幂等状态机**：内部设立 `AtomicBoolean` 状态标记，无论是 preStop 钩子先行调用还是与 SIGTERM 信号并发到达，均保证排空流程仅执行一次，杜绝延时重复叠加。

### 4. 长耗时慢请求性能主动告警拦截器 (`SlowRequestFilter`)
- **出口统一精准耗时统计**：过滤器注册优先级固定为 `TraceFilter.ORDER + 3`，在请求出口 `finally` 阶段精确统计全生命周期处理耗时。
- **强类型阈值判定与 WARN 告警**：耗时超过配置的强类型阈值 `threshold: 3s`（`Duration`，支持 `500ms`, `3s` 等）时，输出规范化单行 WARN 日志，完整聚合 MDC 全链路 `traceId`、HTTP 方法、请求 URI、耗时（ms）、阈值（ms）及客户端真实 IP。
- **智能长连接与流式免打扰放行**：
  - 自动识别并放行请求/响应头中的 `Upgrade` 握手标头（WebSocket 升级协议）；
  - 自动识别并放行 `text/event-stream`（Server-Sent Events 长推流）；
  - 自动识别并放行 `application/octet-stream`（大文件与数据流分块下载）；
  - 自动识别并放行 `request.isAsyncStarted()` 异步挂起任务；
  - 杜绝上述正常长耗时场景产生无意义误告警。
- **白名单精准排除与因果链透明**：支持通过 `exclude-urls`（Ant 风格路径匹配）排除监控；若下游控制器抛出异常，在 `finally` 记录慢请求告警后 100% 原样重抛原始异常。

### 5. HTTP Payload 双向安全采样打印与防 OOM 不变量 (`PayloadLoggingFilter`)
- **执行优先级对齐**：注册优先级固定为 `TraceFilter.ORDER + 2`，位于 `ContentCachingFilter` 之后，确保能够稳定复用已缓冲的请求体。
- **单行聚合日志呈现**：采用结构化单行输出，包含 `traceId`、`method`、`uri`、`status`、`cost`、`reqBody` 与 `respBody`，彻底解决传统分段多行日志在并发环境下相互穿插错乱的痛点。
- **MIME 智能过滤防卫**：仅针对文本与结构化媒介（`application/json`、`application/xml`、`text/plain`、`application/x-www-form-urlencoded` 等）进行安全采样；遇到 `multipart/*`、`application/octet-stream`、`image/*` 等二进制流时自动跳过并填充 `[BINARY_STREAM]` 占位，坚决杜绝大文件上传下载导致的 OOM 风险。
- **强类型截断防卫**：严格遵循 `max-payload-size: 4KB`（强类型 `DataSize`），请求体与响应体超出阈值部分强制截断并追加 ` ... [TRUNCATED]` 标识。
- **敏感凭据纯原生正则掩码**：对请求体与响应体中出现的 `password`、`secret`、`token`、`authorization` 等字段，内置高效率纯原生正则表达式自动掩码替换为 `***`，筑牢合规防线。
- **响应确定性释放不变量 (Deterministic Cleanup)**：使用 `ContentCachingResponseWrapper` 拦截响应体并在 `finally` 嵌套块中 **100% 确保调用 `responseWrapper.copyBodyToResponse()`**，从底层架构上消除因异常中断导致客户端挂起的恶性 Bug。
- **状态码自适应提升**：HTTP 响应状态码 $\ge 400$ 时，单行聚合日志级别自动由 `INFO` 提升为 `WARN`，提升运维感知灵敏度。

### 6. 全景 Web 过滤器与 AOP 确定性时序天梯 (Deterministic Filter & AOP Ordering Ladder)
为了彻底根除多中间件叠加时的执行时序混乱、Filter 相互遮蔽与 AOP 切面抢占问题，`ateng-boot-web` 确立全景时序天梯基线：

#### 9 大过滤器时序阶梯 (Filter Chain Ladder, 步长 10)
所有 Filter 均基于 Spring `Ordered.HIGHEST_PRECEDENCE`（最高优先级）按步长 10 显式递增注册，确保安全上下文最前置、响应修改紧随其后：

```mermaid
flowchart LR
    F1["1. TraceFilter<br>(ORDER: +10)"] --> F2["2. IpAccessControlFilter<br>(ORDER: +20)"]
    F2 --> F3["3. ContentCachingFilter<br>(ORDER: +30)"]
    F3 --> F4["4. PayloadLoggingFilter<br>(ORDER: +40)"]
    F4 --> F5["5. SlowRequestFilter<br>(ORDER: +50)"]
    F5 --> F6["6. SecurityHeadersFilter<br>(ORDER: +60)"]
    F6 --> F7["7. ShallowEtagHeaderFilter<br>(ORDER: +70)"]
    F7 --> F8["8. XssFilter<br>(ORDER: +80)"]
    F8 --> F9["9. CorsFilter<br>(ORDER: +90)"]
    F9 --> Dispatcher["DispatcherServlet / Controller"]
```

1. **`TraceFilter` (`ORDER = HIGHEST_PRECEDENCE + 10`)**：全链路根基，提取或生成无锁 MDC `traceId`，向出站响应头写入 `X-Trace-Id`，并在 `finally` 确定性清理；
2. **`IpAccessControlFilter` (`ORDER = HIGHEST_PRECEDENCE + 20`)**：URL 路径级 IP 防火墙，优先在未读取请求体前实施快速无状态阻断；
3. **`ContentCachingFilter` (`ORDER = HIGHEST_PRECEDENCE + 30`)**：请求体可重复读包装，拦截超限载荷并实施 HTTP 200 + 413 优雅降级；
4. **`PayloadLoggingFilter` (`ORDER = HIGHEST_PRECEDENCE + 40`)**：双向安全采样日志，复用可重读请求体，敏感信息脱敏与单行聚合；
5. **`SlowRequestFilter` (`ORDER = HIGHEST_PRECEDENCE + 50`)**：出口耗时统计与长连接/流式/异步免打扰告警；
6. **`SecurityHeadersFilter` (`ORDER = HIGHEST_PRECEDENCE + 60`)**：注入 OWASP/等保三级推荐安全响应头并抹除容器指纹；
7. **`ShallowEtagHeaderFilter` (`ORDER = HIGHEST_PRECEDENCE + 70`)**：协商缓存计算 ETag，客户端命中直接返回 304 节省网络带宽；
8. **`XssFilter` (`ORDER = HIGHEST_PRECEDENCE + 80`)**：包装 `XssHttpServletRequestWrapper`，清洗 Query/Form 参数与非排除 Header；
9. **`CorsFilter` (`ORDER = HIGHEST_PRECEDENCE + 90`)**：基于 `DynamicCorsConfigurationSource` 与 `CorsOriginProvider` 实施安全跨域校验与凭据反射。

#### 6 大 AOP 切面时序阶梯 (Aspect Chain Ladder, 步长 100)
切面优先级通过 `@Order` 显式按步长 100 编排，保障安全防御在先、业务审计紧贴真实逻辑：

```mermaid
flowchart TD
    A1["1. IpAccessControlAspect (@Order 100)<br>方法/类级精细化 IP 防火墙拦截"] --> A2["2. RateLimiterAspect (@Order 200)<br>接口频控令牌桶防刷与超限阻断"]
    A2 --> A3["3. IdempotentAspect (@Order 300)<br>接口防重幂等原子抢占与锁定"]
    A3 --> A4["4. ApiSignAspect (@Order 400)<br>接口双向加签验签与防篡改/防重放"]
    A4 --> A5["5. WebLogAspect (@Order 500)<br>基础 Web 访问审计与入参出参打印"]
    A5 --> A6["6. OperateLogAspect (@Order 600)<br>结构化业务操作审计，紧贴业务执行与出参脱敏"]
    A6 --> Biz["业务 Controller 方法体"]
```

### 7. 虚拟线程友好防 Carrier Pinning 与受管并发 (Virtual Thread Carrier Pinning Defense)
- **零野线程与受管收敛**：全面取缔内部任何私自使用 `Executors` 或 `new Thread()` 创建未受管线程池的行为，全部异步投递与事件广播统一接入 `ateng-boot-core` 的受管线程池（`AtengAsyncAutoConfiguration`）；
- **全面替换 `synchronized` 为 `ReentrantLock`**：在单机内存组件（`InMemoryIdempotentKeyStore`、`InMemoryRateLimiterStore` 等）的并发临界区中，100% 消除 Java 原生 `synchronized` 关键字，统一重构为 JUC `ReentrantLock`。彻底消除 JDK 21+ 虚拟线程在 `synchronized` 临界区内可能引发的 Carrier Thread Pinning（宿主平台线程锁死），保障虚拟线程在高并发场景下的无损调度与高吞吐；
- **锁释放状态安全防御**：临界区锁释放严格遵循前置持有判定（`if (lock.isHeldByCurrentThread()) { lock.unlock(); }`）范式并在 `finally` 块中执行，杜绝非法状态异常（`IllegalMonitorStateException`）。

### 8. 对称存储契约与高可用灾备架构 (Symmetric Store SPIs & Fail-Open Resilient Governance)
- **对称语义完备性**：
  - `IdempotentKeyStore` SPI 完备提供原子抢占（`tryLock`）、主动释放（`unlock`）与状态探针（`isLocked(key)`）；
  - `RateLimiterStore` SPI 完备提供原子尝试获取（`tryAcquire`）、动态重置与状态清理（`reset(key)`）；
- **`fail-open` 存储容灾与平滑降级**：
  - 当分布式存储后端（如 Redis / Redisson）出现网络断连、连接池耗尽或超时中断时，若开启 `fail-open: true`，切面平滑放行保证核心业务主流程不中断，同时输出格式化 WARN 告警；
  - 若开启 `fail-open: false`，则由切面统一拦截底层 SDK 异常并转译为标准 `BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, ...)`，**100% 完整保留原始底层 `cause` 异常链**，杜绝吞咽异常与 Log-and-Throw。

### 9. 载荷超限 413 优雅降级与快速失败屏障 (ContentCachingFilter 413 Fallback Barrier)
- `CachedBodyHttpServletRequest` 在构造或流读取过程中探测到请求体超出阈值（`max-body-size: 2MB`）时，抛出 `PAYLOAD_TOO_LARGE` (413) 业务异常；
- `ContentCachingFilter` 就近捕获该异常，在过滤器出口实施快速失败屏障：
  - 自动从 MDC 提取并回写 `X-Trace-Id` 响应头；
  - 严守显式响应契约（ADR-0002 与 ADR-0004），使用 `ServletUtil.writeJson` 向响应流直接回写 HTTP 200 + `Result.fail(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), ...)` 的 JSON 报文并刷新关闭；
  - 阻断超限报文继续流入后续过滤器或打爆 Spring MVC 分发层，兼顾安全性与客户端体验。

### 10. 反向代理受信链与防伪造清洗 (Trusted Proxies Anti-Spoofing & Reverse Hop Cleaning)
- `IpUtil` 引入 `trustedProxies` 可信反向代理列表入参；
- 在多级反向代理（如 CDN -> SLB -> Ingress -> Pod）场景下，客户端极易通过伪造请求头（如 `X-Forwarded-For: 127.0.0.1, 10.0.0.1`）实施 IP 伪造攻击；
- `IpUtil.getClientIp(request, trustedProxies)` 采用逆向清洗算法：从代理链最右侧（直接上游）开始向左倒序遍历，跳过所有命中的可信反向代理 IP，取第一个不受信任的 IP 作为真实客户端 IP；若全链均为可信代理，则保底使用最左侧原始 IP，彻底免疫 IP 伪造逃逸；
- 兼顾 IPv4 与 IPv6 规整化（如规整 `0:0:0:0:0:0:0:1` 为 `127.0.0.1`）。

### 11. 全景双轨 XSS 防御与请求头清洗 (XSS Header Cleaning & Pseudo-Protocol Stripping)
- `WebProperties.XssProperties` 新增 `clean-headers`（默认开启）与 `exclude-headers`（支持排除特定合规头）；
- `XssHttpServletRequestWrapper` 拦截 `getHeader` 与 `getHeaders`，对非排除标头实施安全清洗；
- `XssCleaner` 原生引擎增强伪协议防御，自动剔除 `javascript:` 与 `vbscript:` 等危险脚本协议；
- 配合 Jackson `XssStringDeserializer` 与 `@XssIgnore`，实现请求参数、请求头与 JSON 请求体的三维全景防护。

---

## 核心配置与类拓扑速查 (Configuration & Architecture Map)

| 组件 / SPI | 核心配置项 (`ateng.boot.web.*`) | 默认值 | 作用与职责 |
| :--- | :--- | :--- | :--- |
| `TraceFilter` | `trace.enabled` | `true` | 全链路追踪无锁 MDC 写入与 `X-Trace-Id` 响应头流转 (ORDER: +10) |
| `IpAccessControlFilter` | `ip-access-control.enabled`<br>`ip-access-control.trusted-proxies` | `true`<br>`[]` | URL 路径级 IP 防火墙，支持可信代理逆向清洗 (ORDER: +20) |
| `ContentCachingFilter` | `body-cache.max-body-size` | `2MB` | 通用请求体可重读管道，超限 413 优雅降级与快速失败截断防 OOM (ORDER: +30) |
| `PayloadLoggingFilter` | `payload-log.enabled`<br>`payload-log.max-payload-size`<br>`payload-log.exclude-urls` | `false`<br>`4KB`<br>`[]` | HTTP 请求与响应双向安全采样，内建 MIME 过滤、正则掩码与防挂起 (ORDER: +40) |
| `SlowRequestFilter` | `slow-request.enabled`<br>`slow-request.threshold`<br>`slow-request.exclude-urls` | `true`<br>`3s`<br>`[]` | 长耗时慢请求性能告警，智能放行 WebSocket/SSE/下载/异步任务 (ORDER: +50) |
| `SecurityHeadersFilter` | `security-headers.enabled` | `true` | OWASP / 等保三级推荐安全标头注入并抹除容器版本指纹 (ORDER: +60) |
| `ShallowEtagHeaderFilter` | `etag.enabled`<br>`etag.write-weak-etag` | `false`<br>`false` | 基于响应内容哈希的条件协商缓存与 304 节省带宽 (ORDER: +70) |
| `XssFilter` | `xss.enabled`<br>`xss.clean-headers`<br>`xss.exclude-headers` | `true`<br>`true`<br>`["Authorization", "Content-Type", ...]` | XSS 安全清洗过滤器，清洗 Query/Form 与请求头 (ORDER: +80) |
| `DynamicCorsConfigurationSource` | `cors.enabled`<br>`cors.allowed-origins`<br>`cors.allowed-origin-patterns` | `true`<br>`[]`<br>`[]` | 动态跨域校验源，联动 `CorsOriginProvider` SPI 实施安全凭据反射 (ORDER: +90) |
| `IdempotentKeyStore` | `idempotent.enabled`<br>`idempotent.fail-open` | `true`<br>`true` | 对称幂等存储 SPI，完备提供 `tryLock`、`unlock` 与 `isLocked` 探针 |
| `RateLimiterStore` | `rate-limiter.enabled`<br>`rate-limiter.fail-open` | `true`<br>`true` | 对称限流存储 SPI，完备提供 `tryAcquire` 与状态重置 `reset` |
| `OperateLogAspect` | `operate-log.enabled` | `true` | 声明式结构化操作审计切面，自动关联四维上下文，守卫内存真明文 (Order: 600) |
| `OperateLogStorageProvider` | - | `DefaultOperateLogStorageProvider` | 操作审计存储解耦 SPI 扩展点，开箱提供 SLF4J JSON 保底 |
| `AtengGracefulShutdownHandler` | `shutdown.enabled`<br>`shutdown.drain-timeout`<br>`shutdown.smart-lifecycle-enabled` | `true`<br>`10s`<br>`true` | K8s 云原生就绪探针主动反转与静默排空，SmartLifecycle 兜底 |
| `PreStopEndpoint` / `PreStopController` | `shutdown.actuator-endpoint-enabled`<br>`shutdown.mvc-endpoint-enabled` | `true`<br>`true` | `/actuator/pre-stop` 与 `/pre-stop` 双模停机排空端点 |

---

## 后果与权衡 (Consequences & Trade-offs)

### 正向收益 (Positive Impacts)
1. **零断流平滑发布 (Zero-Downtime Deployment)**：彻底消除容器滚动更新期间因为网关路由同步滞后造成的 502/504 报错，大幅提升生产高可用指标。
2. **零 OOM 崩溃与全真排障 (Safe Observability)**：请求体缓存（2MB）与采样截断（4KB）双层强类型防爆守卫，在提供详尽报文观测能力的同时，确保极端流量下 JVM 堆内存绝对安全。
3. **安全合规与内存真明文 (Data Security Compliance)**：操作审计与 Payload 采样均遵循凭据掩码规范，且审计切面严格保持“内存真明文”，彻底切断掩码脏数据回写持久层的隐患。
4. **低侵入与高扩展 (Extensibility & Zero-Bloat)**：全量能力基于 Spring 6 与 JDK 17 原生能力实现，无任何三方重型工具包；操作审计、停机端点均支持 SPI 与 `@Bean` 覆盖接管。

### 妥协成本与运维规范 (Operational Invariants & Caveats)
1. **停机延迟延长**：开启优雅停机排空后，Pod 停止运行将增加 `drain-timeout`（默认 10 秒）的静默等待时间。K8s Deployment 必须配套将 `terminationGracePeriodSeconds` 设置为大于排空时长（建议 $\ge 30$ 秒），防止 Pod 被 kubelet 强制超时 SIGKILL 强杀。
2. **条件内存开销**：`ContentCachingFilter` 与 `PayloadLoggingFilter` 会在请求处理期间占用微量的堆内存缓冲区。因此，框架坚持“按需装配”原则，仅在显式开启对应特性时才装配生效。
