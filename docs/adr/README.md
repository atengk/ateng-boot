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

---

## ADR 撰写与维护规范

新增或修订架构决策时，请严格遵守以下准则：

1. **触发门槛 (Three Prerequisites)**：
   - **难以逆转 (Hard to reverse)**：推翻重来的技术或沟通成本较高；
   - **脱敏需要解释 (Surprising without context)**：未来的开发者查阅代码时会产生“为什么这么写”的疑问；
   - **权衡折衷产物 (Real trade-off)**：在多个有明确利弊的备选方案中经过推演做出的取舍。
2. **编号与命名**：统一采用 4 位递增序号与短横线命名，如 `0009-distributed-lock-redis-integration.md`。
3. **结构要素**：必须包含【背景与痛点】、【决策内容与落地设计】及【后果与权衡（正向收益与妥协成本）】。
