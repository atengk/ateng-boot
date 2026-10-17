# Context Map: ateng-boot

`ateng-boot` 遵循多上下文（Multi-context）架构设计，各模块边界清晰、高内聚且低耦合。本映射表定义所有上下文的边界职责与其协作关系。

## Contexts

- [BOM 管控中心](./ateng-boot-bom/CONTEXT.md) — 依赖版本统管清单（纯 dependencyManagement），为微服务与按需精细化工程提供无侵入版本管控。
- [Core 核心底座](./ateng-boot-core/CONTEXT.md) — 提供全局统一 API 响应契约、异常体系、业务断言、用户审计 SPI、异步上下文传递工具、全场景敏感数据脱敏与纯原生分布式 ID 发号引擎。
- [Web 运行时增强](./ateng-boot-web/CONTEXT.md) — 提供基于 Filter 的链路追踪、全局异常防御拦截、Jackson 防失真与时间格式化，以及 CORS 自动化配置。
- [MyBatis-Plus 持久层增强](./ateng-boot-mybatis-plus/CONTEXT.md) — 提供通用实体基类、审计填充器、行级多租户安全隔离、声明式细粒度数据权限引擎、原生高性能分批批量插入引擎、多方言分页插件、防全表更新删除、乐观锁与分布式 ID 扩展。
- [All 聚合全家桶](./ateng-boot-all/CONTEXT.md) — 面向单体或快速起步业务，提供零配置开箱即用的一键依赖打包交付。
- [Redis 基础设施与分布式协同](./ateng-boot-redis/CONTEXT.md) — 提供双核驱动 Redis 基础设施（Spring Data Redis + Redisson 原生客户端）、纯净无 @class 污染序列化、全能 RedisUtil（强制游标扫描）、四阶声明式分布式锁 @DistributedLock、双模轻量延时队列、Pub/Sub 广播及业务流控计数。
- [Excel 轻量流式处理](./ateng-boot-excel/CONTEXT.md) — 提供基于 Apache Fesod 的轻量流式 Excel 处理引擎，深度融合 ADR-0012 内存真明文与导出动态脱敏（@ExcelSensitive）、JSR-303 行级校验与红字批注错误回写（BatchValidationListener / ExcelFeedbackUtil）、智能防呆模板生成（@ExcelDropdown / @ExcelPrompt）、双向业务转义（@ExcelEnum / @ExcelDict SPI）、复杂报表填充、单元格合并与 Fluent 多 Sheet 编排（ExcelBuilder）。
- [Sa-Token 安全鉴权与会话管控](./ateng-boot-satoken/CONTEXT.md) — 面向企业级应用的高性能轻量鉴权与分布式会话治理底座，提供细粒度权限控制、双轨拦截防御、自适应存储桥接、强类型 LoginUser/LoginHelper 载荷、MyBatis-Plus 审计全链路贯通、两级权限缓存与 Spring 原生事件广播（参见 [ADR-0024](./docs/adr/0024-enterprise-satoken-authentication-and-security-architecture.md)）。
- [Samples 验证工程](./ateng-boot-samples/CONTEXT.md) — 采用 H2 内存库提供内测验证用例与工程化最佳实践 Demo。

## Relationships

- **BOM → Core / Web / MyBatis-Plus / Redis / Excel / SaToken / All**：BOM 模块作为顶层依赖物料清单，统一定义并对齐所有可分发构件的版本号，下游通过 import 即可按需引入且免配版本（参见 [ADR-0003](./docs/adr/0003-dependency-management-via-bom-import.md)）。
- **SaToken → Core / Web / Redis**：Sa-Token 模块自动装配 `SaTokenCurrentUserProvider` 接入 Core 模块的 `CurrentUserProvider` SPI，全自动打通当前操作人至持久层审计与数据权限；与 Web 模块全局统一异常及动态 CORS（ADR-0015）深度自洽，细粒度转译为标准 `Result` 契约；自适应桥接 Redis 模块提供纯净 JSON 分布式会话，并在无 Redis 环境下优雅降级为内存存储（参见 [ADR-0024](./docs/adr/0024-enterprise-satoken-authentication-and-security-architecture.md)）。
- **Redis → Core**：Redis 模块依赖 Core 模块的统一异常契约（`BusinessException`）、结果码接口（`IResultCode`）与上下文透传；基于纯净 JSON 序列化保证实体对象在 Redis 存储中免受类路径迁移或安全 CVE 困扰；与 `ateng-boot-web` 保持正交，为其基于 SPI 的两级幂等与限流存储提供无缝底层支持（参见 [ADR-0022](./docs/adr/0022-dual-core-redis-and-distributed-coordination-architecture.md)）。
- **Excel → Core / Web**：Excel 模块依赖 Core 模块的 `DesensitizeUtil` 实现导出动态脱敏，恪守 ADR-0012 内存真明文原则；结合 Web 模块的 Servlet API 实现极简 Web 文件流导出、模板下载与错误反馈回写；提供双向枚举与字典 SPI 扩展解耦业务转义（参见 [ADR-0023](./docs/adr/0023-streaming-excel-engine-and-data-security-architecture.md)）。
- **Web → Core**：Web 模块依赖 Core 模块的 `Result`、`BusinessException` 与 `IResultCode`，全局异常拦截器将未捕获异常转换为标准 `Result`，并联动 MDC 注入 `traceId`；通过 `ContextCopyingTaskDecorator` 包装 `AsyncContext` 实现跨线程上下文透传；基于 SPI 抽象与两级存储自适应提供接口防重幂等引擎 `@Idempotent`（参见 [ADR-0013](./docs/adr/0013-spi-based-hybrid-idempotent-engine.md)）；提供三级自适应令牌桶限流防刷引擎 `@RateLimiter`（参见 [ADR-0014](./docs/adr/0014-three-tier-adaptive-rate-limiter-engine.md)）；提供基于 SPI 扩展与凭据安全反射的动态 CORS 方案（参见 [ADR-0015](./docs/adr/0015-dynamic-cors-and-credentials-safe-origin-reflection.md)）；提供双向数据加签验签与防重放引擎 `@ApiSign`（参见 [ADR-0016](./docs/adr/0016-dual-direction-api-signature-and-anti-replay-engine.md)）；同时遵循零重型外部依赖哲学，就近内聚 `IpUtil` 与 `ServletUtil`（参见 [ADR-0011](./docs/adr/0011-zero-bloat-and-co-located-utilities.md)）。
- **MyBatis-Plus → Core**：MyBatis-Plus 模块依赖 Core 模块的 `CurrentUserProvider` SPI 与 `UserContextHolder` 获取操作人；依赖 `CryptoUtil` 与 `DesensitizeUtil` 实现持久层 `@FieldEncrypt` 透明加密与 `@FieldSensitive` 视图脱敏（参见 [ADR-0012](./docs/adr/0012-lifecycle-separated-data-security.md)）；桥接 Core 模块纯原生 `Snowflake` 统一持久层分布式主键发号引擎（参见 [ADR-0020](./docs/adr/0020-zero-bloat-distributed-id-and-mask-strategy.md)）；基于 JSqlParser AST 自适应语法树构建企业级行级多租户安全隔离体系 `@IgnoreTenant`（参见 [ADR-0017](./docs/adr/0017-multi-tenant-row-level-isolation-architecture.md)）与声明式细粒度数据权限引擎 `@DataScope`（参见 [ADR-0018](./docs/adr/0018-declarative-data-permission-engine-architecture.md)）；提供原生高性能分批批量插入引擎 `AtengBaseMapper`、`AtengBaseService` 与 `BatchUtil`（参见 [ADR-0019](./docs/adr/0019-native-high-performance-batch-insert-engine.md)）；并通过 `PageUtil` 实现 ORM `IPage` 到纯净 `PageResult` 的流式转换（参见 [ADR-0010](./docs/adr/0010-transport-pagination-contract-decoupling.md)）。
- **All → Core / Web / MyBatis-Plus / Redis / Excel**：All 模块为纯依赖聚合工程，向下游调用端透传全套核心 Starter。
- **Samples → All**：Samples 工程作为下游消费端样例，验证所有模块组合后的开箱即用自动装配与端到端运行表现。

## Architectural Decisions

全生命周期系统级架构演进与关键权衡详见 [架构决策记录看板 (ADR)](./docs/adr/README.md)。
