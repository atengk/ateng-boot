# 架构决策记录看板 (Architectural Decision Records)

本目录记录 `ateng-boot` 基础框架演进过程中的所有关键技术与架构决策（ADR）。每一篇记录均遵循统一的背景、决策内容、后果分析与权衡考量，作为团队协作与长期演进的权威准绳。

---

## 架构决策矩阵 (Decision Matrix)

| 编号 | 决策标题 | 状态 | 核心收益与权衡 | 核心关联模块 | 详细文档 |
| :---: | :--- | :---: | :--- | :--- | :---: |
| **0001** | 顶级品牌多仓解耦与通用基础设施底座 | `Accepted` | 确立 `ateng` 顶级品牌，`ateng-boot` 作为单体与微服务通用底座 | 全工程 | [查看](./0001-multi-repo-brand-matrix.md) |
| **0002** | 显式统一返回契约，严禁全局拦截包装 | `Accepted` | Controller 显式声明 `Result<T>`，彻底消除 `@ResponseBodyAdvice` 对 String、下载及 Swagger 的侵入 | `ateng-boot-core` | [查看](./0002-explicit-result-contract-no-response-body-advice.md) |
| **0003** | 基于 BOM Import 机制的无侵入依赖统管 | `Accepted` | 采用 `<type>pom</type><scope>import</scope>`，彻底摆脱单继承父 POM 锁定枷锁 | `ateng-boot-bom` | [查看](./0003-dependency-management-via-bom-import.md) |
| **0004** | 传输层 HTTP 200 与业务错误脱敏拦截 | `Accepted` | HTTP 协议层保持 200，内部通过业务状态码区分；未知根异常强制生产脱敏并关联 `traceId` | `ateng-boot-web` | [查看](./0004-http-200-with-business-code-and-exception-masking.md) |
| **0005** | 基于 Jackson Customizer 注入全局序列化 | `Accepted` | 统一通过 `Jackson2ObjectMapperBuilderCustomizer` 注入雪花 Long 转 String 与时间格式化，严禁覆盖 `ObjectMapper` | `ateng-boot-web` | [查看](./0005-jackson-customizer-over-objectmapper-override.md) |
| **0006** | 技术依赖基线锁定与安全升级策略 | `Accepted` | 字节码锁定 JDK 17，Spring Boot 锁定 3.3.13，MyBatis-Plus 锁定 3.5.17，全局屏蔽破坏性大版本 | 全工程 | [查看](./0006-dependency-baseline-and-upgrade-strategy.md) |
| **0007** | Maven Central 官方发布解耦与轻量极速 CI | `Accepted` | 非分发模块 `<maven.deploy.skip>true</maven.deploy.skip>`，异步校验 `<waitUntil>validated</waitUntil>` 极速解耦 | 全工程 | [查看](./0007-maven-publishing-and-clean-ci-pipeline.md) |
| **0008** | 自动化发版、单一版本流与常青文档决策 | `Accepted` | 彻底告别传统 SNAPSHOT 快照流，主干停留在最新稳定版；CI 智能短路放行；全链路防呆强熔断 | 全工程 / Scripts | [查看](./0008-automated-release-changelog-and-evergreen-docs.md) |
| **0009** | main 稳定发布与 dev 活跃开发双分支架构 | `Accepted` | 解耦生产发布与日常编码，日常活跃开发收口于 `dev`，正式发版严格收口于只读受保护的 `main` | 全工程 / Git 协作 | [查看](./0009-dual-branch-workflow-main-release-and-dev-active.md) |
| **0010** | 传输契约与持久层解耦与流式转换工具 | `Accepted` | 传输层解耦持久层 ORM，提供纯净 `PageResult<T>`、`PageQuery` 与 `PageUtil` 流式转换门面 | `core` / `mybatis-plus` | [查看](./0010-transport-pagination-contract-decoupling.md) |
| **0011** | 零重型外部依赖与上下文就近内聚工具集 | `Accepted` | 彻底杜绝全量外部工具包，100% 依托 JDK 17 与 Spring 6，依运行时就近内聚 | `core` / `web` / `mybatis-plus` | [查看](./0011-zero-bloat-and-co-located-utilities.md) |
| **0012** | 数据安全生命周期分离：存储加密与视图脱敏 | `Accepted` | 确立“内存真明文”不变量，存储加密收敛于 TypeHandler，展示脱敏收敛于 Jackson | `core` / `mybatis-plus` | [查看](./0012-lifecycle-separated-data-security.md) |
| **0013** | 基于 SPI 抽象的混合式接口幂等防重引擎 | `Accepted` | 确立两级存储 SPI 自适应装配，内聚内存防爆与 Redis 无缝升级，全生命周期锁语义与 SpEL 支持 | `ateng-boot-web` | [查看](./0013-spi-based-hybrid-idempotent-engine.md) |
| **0014** | 基于三级自适应 SPI 架构的接口限流防刷引擎 | `Accepted` | 统一令牌桶模型，纯 JDK 原子无锁单机兜底与 Redis/Redisson 集群自适应，复合维度与 HTTP 429/Retry-After | `ateng-boot-web` | [查看](./0014-three-tier-adaptive-rate-limiter-engine.md) |
| **0015** | 基于 SPI 扩展与凭证安全反射的动态 CORS 架构 | `Accepted` | 深度集成 Spring 原生 CorsConfigurationSource，支持动态来源 SPI 与带凭据安全反射 Vary 响应头 | `ateng-boot-web` | [查看](./0015-dynamic-cors-and-credentials-safe-origin-reflection.md) |
| **0016** | 双向 API 数据加签验签与防重放引擎 | `Accepted` | 纯 JDK 17 原生零膨胀签名 SPI，标准化待签串，复用 IdempotentKeyStore 防重放，AOP 切面遵守 ADR-0002 | `ateng-boot-web` | [查看](./0016-dual-direction-api-signature-and-anti-replay-engine.md) |
| **0017** | 企业级行级多租户安全隔离与自动填充架构 | `Accepted` | 深度集成 MP 3.5.17 TenantLineInnerInterceptor，数字列索引保护，@IgnoreTenant 与闭包短路，智能插入填充 | `ateng-boot-mybatis-plus` | [查看](./0017-multi-tenant-row-level-isolation-architecture.md) |
| **0018** | 基于 JSqlParser AST 的声明式细粒度数据权限引擎架构 | `Accepted` | 声明式 @DataScope 注解，多表别名校验，DataPermissionHandler SPI 解耦，超管与闭包秒级短路，分页 count 自动保护 | `ateng-boot-mybatis-plus` | [查看](./0018-declarative-data-permission-engine-architecture.md) |
| **0019** | 原生高性能分批批量插入引擎架构决策 | `Accepted` | 挂载 InsertBatchSomeColumn 单条多值 SQL，过滤逻辑删除列，BatchUtil 智能切片与 AtengBaseService 强事务守护 | `ateng-boot-mybatis-plus` | [查看](./0019-native-high-performance-batch-insert-engine.md) |
| **0020** | 零膨胀分布式 ID 引擎与全场景敏感数据脱敏架构决策 | `Accepted` | 纯 JDK 17 原生 Snowflake/IdUtil 引擎，四级自适应节点，全场景脱敏策略扩展与持久层发号器大一统 | `core` / `mybatis-plus` | [查看](./0020-zero-bloat-distributed-id-and-mask-strategy.md) |
| **0021** | 存量核心基线收敛与零冗余封版战略决策 | `Accepted` | 宣布三大核心模块功能完备，横向冻结新模块分裂，微创补强 ServletUtil 流式下载，极限测试加固与文档大一统 | `core` / `web` / `mybatis-plus` | [查看](./0021-core-baseline-convergence-and-zero-bloat-freeze-strategy.md) |
| **0022** | 双核驱动 Redis 基础设施与企业级分布式协同架构决策 | `Accepted` | 双核驱动融合（Spring Data Redis + Redisson 原生），纯净 JSON 序列化防 @class 污染，@DistributedLock 4 阶模式与慢锁审计，延时队列与全能 RedisUtil | `ateng-boot-redis` | [查看](./0022-dual-core-redis-and-distributed-coordination-architecture.md) |
| **0023** | 企业级轻量流式 Excel 引擎与数据安全/校验/防呆一体化架构决策 | `Accepted` | 锁定 Apache Fesod 流式基线，导出动态脱敏（@ExcelSensitive 守卫 ADR-0012 内存真明文），JSR-303 行级校验与红字批注回写，防呆模板下拉提示，双向枚举/字典转义，复杂报表填充合并与多 Sheet 编排 | `ateng-boot-excel` | [查看](./0023-streaming-excel-engine-and-data-security-architecture.md) |
| **0024** | 企业级 Sa-Token 鉴权生态与安全防御/分布式会话一体化架构决策 | `Accepted` | 独立 Starter，双轨拦截防御，自适应存储桥接，强类型 LoginUser/LoginHelper，底座全链路贯通，两级权限缓存与 Spring 原生事件广播 | `ateng-boot-satoken` | [查看](./0024-enterprise-satoken-authentication-and-security-architecture.md) |
| **0025** | Core 核心底座全方位扩建与未来中间件孵化地基架构决策 | `Accepted` | 序列化门面 JsonUtil 下沉、脱敏/租户上下文彻底归位、四维异步透传闭环、函数式 Result 演化与 SpringUtil/LambdaUtil/RetryUtil 等未来孵化原语矩阵 | `ateng-boot-core` / 全工程 | [查看](./0025-core-module-expansion-and-future-proof-middleware-foundations.md) |
| **0026** | 生产级基线加固与底层设施大一统架构决策 | `Accepted` | 全域 JSON 100% 收敛 JsonUtil、双维上下文镜像对称与 Sa-Token 自动无感桥接、TraceFilter 无锁原语、异步线程池下沉 core、底座原语复用与延时队列 MDC 追踪 | `core` / `web` / `satoken` / `redis` / `excel` | [查看](./0026-production-hardening-and-foundations-unification.md) |
| **0027** | 分层双防数据校验体系与标准化原语架构决策 | `Accepted` | 确立分层双防铁律，沉淀 ValidationGroups 标准分组契约与 ValidList 批量级联容器，扩展 ValidatorUtil POJO 编程式校验与无 EL 优雅降级 | `core` / `web` | [查看](./0027-defense-in-depth-validation-and-standardized-primitives.md) |



---

## ADR 撰写与维护规范

新增或修订架构决策时，请严格遵守以下准则：

1. **触发门槛 (Three Prerequisites)**：
   - **难以逆转 (Hard to reverse)**：推翻重来的技术或沟通成本较高；
   - **脱敏需要解释 (Surprising without context)**：未来的开发者查阅代码时会产生“为什么这么写”的疑问；
   - **权衡折衷产物 (Real trade-off)**：在多个有明确利弊的备选方案中经过推演做出的取舍。
2. **编号与命名**：统一采用 4 位递增序号与短横线命名，如 `0009-distributed-lock-redis-integration.md`。
3. **结构要素**：必须包含【背景与痛点】、【决策内容与落地设计】及【后果与权衡（正向收益与妥协成本）】。
4. **膨胀防线与合并收敛 (Anti-Inflation & Consolidation)**：
   - **严禁碎片化滥立 ADR**：单个工具类微调、常规方法扩展、业务小修补坚决禁止新建独立 ADR；
   - **优先增量修订既有篇目**：针对已就绪模块的功能增强与基线加固，优先在既有归属 ADR（如基线加固并入 ADR-0026、校验设施并入 ADR-0027、核心底座原语并入 ADR-0025）中以增量章节演进；仅在孵化完整新中间件底座、重大跨模块协议契约重构或颠覆性路线变革时，方可分配新编号立篇。
