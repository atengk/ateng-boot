# ateng-boot 研发指南与 Agent 行为指引

## 1. 项目定位与技术基线 (Project Baseline)
`ateng-boot` 是面向生产就绪的 Spring Boot 3 工业级基础设施与全能中间件 Starter 套件，坚持纯原生、零臃肿依赖与显式强类型契约设计，兼顾单体应用极速起步与分布式系统核心底座支撑。

- **运行与编译**：JDK 17 LTS（字节码 17，兼容 21+ 运行）
- **核心基线**：Spring Boot `3.3.13`，MyBatis-Plus `3.5.17`（参见 [ADR-0006](./docs/adr/0006-dependency-baseline-and-upgrade-strategy.md)）
- **命名坐标**：GroupId `io.github.atengk`，基础包名 `io.github.atengk.boot.*`，配置前缀 `ateng.boot.*`

---

## 2. 核心架构与编码契约 (Architecture & Invariants)
- **显式响应契约 (Explicit Contract)**：Controller 统一显式声明返回强类型 `Result<T>`，依托强类型契约自洽；坚决杜绝引入 `@ResponseBodyAdvice` 全局自动拦截包装（参见 [ADR-0002](./docs/adr/0002-explicit-result-contract-no-response-body-advice.md)）。
- **零臃肿与底座优先 (Zero-Bloat & Core-First)**：
  - 100% 依托 JDK 17 与 Spring 6 原生能力，杜绝引入 `hutool-all`、`guava`、`commons-collections` 等重型全量第三方工具包；
  - 工具类就近内聚于归属模块（`core` 通用算法、`web` Servlet/HTTP、`mybatis-plus` ORM 映射等），坚决杜绝垃圾桶模块（参见 [ADR-0011](./docs/adr/0011-zero-bloat-and-co-located-utilities.md)）；
  - **通用原语底座收敛**：跨中间件解耦的通用上下文（用户/租户）、序列化门面、数据脱敏、退避重试、分批切片等通用原语，强制收敛于 `ateng-boot-core` 并向下游直接复用，杜绝在子模块重复造轮子（参见 [ADR-0021](./docs/adr/0021-core-baseline-convergence-and-zero-bloat-freeze-strategy.md)、[ADR-0025](./docs/adr/0025-core-module-expansion-and-future-proof-middleware-foundations.md)）。
- **分层双防校验体系 (Defense-in-Depth Validation)**：
  - **第一道防线（传输边界 / Controller）**：负责无状态语法与格式性校验，统一使用 JSR-303 注解声明以实现 Fail-Fast；批量列表入参强制使用 `ValidList<E>` 杜绝 Spring 默认漏检；分组校验统一使用 `ValidationGroups` 标准标记（参见 [ADR-0027](./docs/adr/0027-defense-in-depth-validation-and-standardized-primitives.md)）；
  - **第二道防线（业务边界 / Service）**：负责业务语义与状态不变量校验，统一推行 `BusinessAssert` 语义化断言；非 Web 边界消费使用 `ValidatorUtil.validate(obj)` 执行快速失败编程式防卫。
- **数据安全与“内存真明文”不变量 (In-Memory Plaintext Invariant)**：
  - 业务层与实体对象在内存中始终保持 100% 原始真实明文（严禁在业务逻辑中直接对实体属性打星号）；
  - 存储加密收敛于持久层（入库密文、出库明文），展示脱敏收敛于视图序列化/导出拦截器，彻底杜绝掩码脏数据写回数据库（参见 [ADR-0012](./docs/adr/0012-lifecycle-separated-data-security.md)、[ADR-0023](./docs/adr/0023-streaming-excel-engine-and-data-security-architecture.md)）。
- **传输契约与持久层彻底解耦 (Transport Decoupling)**：分页查询统一入参 `PageQuery`，统一返回契约 `PageResult<VO>`，在 Service 边界完成纯净流式转换；Controller 传输层严禁泄露 ORM `Page<T>` 或数据持久层实体（参见 [ADR-0010](./docs/adr/0010-transport-pagination-contract-decoupling.md)）。
- **无状态安全与设备治理 (Stateless Security & Device Governance)**：
  - 公共单例服务与 Sa-Token 会话强制保持无状态，严禁使用非线程安全类或成员变量共享可变业务状态；
  - 跨微服务内网调用标配 Same-Token 请求头校验以实现内网穿透防护；多端多设备登录统一推行独立账号与设备治理（参见 [ADR-0024](./docs/adr/0024-enterprise-satoken-authentication-and-security-architecture.md)）。
- **全链路追踪与四维上下文透传 (Tracing & 4D Context Propagation)**：
  - Web 请求统一经由 `TraceFilter` 注入 MDC `traceId`（基于无锁 `fastSimpleUUID`）；
  - 异步任务通过受管线程池、`AsyncContext` 与 `ContextCopyingTaskDecorator` 实现 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 四维快照的全链路透传并在任务完成后严格清理现场（参见 [ADR-0004](./docs/adr/0004-http-200-with-business-code-and-exception-masking.md)、[ADR-0026](./docs/adr/0026-production-hardening-and-foundations-unification.md)）。
- **实体继承阶梯链 (Entity Staircase)**：实体按需选用五级阶梯链（`BaseGenericEntity` -> `BaseIdEntity` -> `BaseTimeEntity` -> `BaseEntity` -> `BaseFullEntity`），`BaseFullEntity` 的 `deleted` 字段标配 `@JsonIgnore`。
- **防御性自动装配 (Defensive Autoconfiguration)**：
  - 自动装配类统配 `@AutoConfiguration` 标注，统一且只能注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（严格遵循 Spring Boot 3 规范，严禁使用旧版 `spring.factories`）；
  - 装配类标配 `@ConditionalOnClass`、`@ConditionalOnProperty`、`@ConditionalOnMissingBean` 守卫；
  - Jackson 定制统一注入 `Jackson2ObjectMapperBuilderCustomizer`，严禁覆盖 `ObjectMapper` Bean（参见 [ADR-0005](./docs/adr/0005-jackson-customizer-over-objectmapper-override.md)）。
- **BOM 依赖统管与向后兼容 (BOM-First & Backward Compatibility)**：
  - 依赖及其版本必须且只能在根 `pom.xml` 或 `ateng-boot-bom/pom.xml` 的 `<dependencyManagement>` 集中纳管；各子模块 `pom.xml` 声明依赖时一律免配 `<version>` 标签（参见 [ADR-0003](./docs/adr/0003-dependency-management-via-bom-import.md)）；
  - 对外暴露的公共 API 签名、核心注解属性与依赖基线严守向后兼容。
- **空安全与保底返回 (Null Safety & Defensive Return)**：
  - 查询列表/集合无匹配时保底返回空集合（如 `Collections.emptyList()`），严禁返回 `null`；
  - 单实体查询无结果时底层允许返回 `null`，但调用方必须立即通过前置卫语句显式拦截防御。
- **属性元数据与代码注释 (IDE-Friendly Properties & Doc Standards)**：
  - 配置属性类统一定义于 `io.github.atengk.boot.*.properties`，标配 `@ConfigurationProperties(prefix = "ateng.boot.*")`，字段提供完整 JavaDoc 说明与默认值，由 `spring-boot-configuration-processor` 自动编译生成 IDE 智能补全元数据；
  - 新建源码与核心 API 标配清晰业务注释，类头部标注 `@author Ateng` 与 `@since YYYY-MM-DD`。

---

## 3. 质量门禁与测试准出 (Quality Gates & Verification)
- **三阶梯测试准出与并行加速 (Three-Tier Test Gate & Parallel Build)**：
  根目录 `.mvn/maven.config` 默认注入 `-T 1C -nsu` 加速配置，自动化环境统一附加 `-B` 确保日志流式纯净；严禁每次改动在本地无脑执行全项目串行单测：
  1. **阶梯一：特性日常自验（秒级极速反馈）**：单个模块改动时，采用多线程依赖树精准定向测试：
     ```bash
     mvn test -pl <changed-module> -am -T 1C -nsu -B
     ```
  2. **阶梯二：本地提交准出验收（精准端到端集成）**：准备提交前，执行变更模块与对应场景样例工程（`ateng-boot-samples/ateng-boot-sample-<scenario>`，如 `basic`/`redis`/`satoken`/`excel`，重大底层重构联动 `smoke-test`）定向联动验证，杜绝无脑全量回归（参见 [ADR-0028](./docs/adr/0028-samples-modularization-and-incremental-integration-testing.md)）：
     ```bash
     mvn test -pl <changed-module>,ateng-boot-samples/ateng-boot-sample-<scenario> -am -T 1C -nsu -B
     ```
  3. **阶梯三：全反应堆多 JDK 矩阵终极守护 (Post-Push CI Gate)**：全反应堆所有模块（100% 模块全集）在 Linux/Windows 及 JDK 17 & 21 矩阵下的全量回归，100% 交由 GitHub Actions CI 云端并行工作流异步守护；本地推送后通过 `gh run watch <run-id>` 闭环跟踪至全绿。
- **纯文档修改测试豁免 (Doc-Only Exemption)**：若本次变更仅涉及文档（如 `*.md`、纯文本或注释说明）而未改动任何业务源码、配置文件与工程依赖，一律免除执行本地 Maven 单元测试与场景准出验证，直接受控提交。

- **特性即文档与真理源不变量 (Doc-as-Feature & Evergreen Invariant)**：
  - **单工单文档同步闭环 (Single-Ticket Sync)**：代码产生的功能、配置与新 API 必须同步在相关文档中体现，严禁只堆砌代码缺失使用说明；所有特性与接口改造均遵循“单工单文档准出闭环”原则，在同一个工单与提交中必须同步交付文档；缺少文档与使用示例的改动一律视为未达验收标准，阻断准出；
  - **子模块就近内聚拓扑 (Submodule-Co-located Docs)**：根目录 `README.md` 保持轻量门户与各模块索引导航；各模块具体代码范式、YAML 配置项与核心能力 100% 强制内聚于所属模块根目录的专属 `README.md`（如 `ateng-boot-core/README.md`、`ateng-boot-excel/README.md` 等），确保开箱即查即用；复杂架构专题沉淀于 `docs/guides/`；
  - **四维生产就绪交付模型 (The 4D Production-Ready Invariant)**：子模块 `README.md` 面向真实生产接入者，严禁仅停留在简单 Demo 的自嗨罗列；任何新增或修改的功能特性，必须正交覆盖以下四个维度，缺一即阻断准出：
    1. **维度一：最小起步体验 (Happy Path & Out-of-the-Box)**：直击一句话痛点、最小可用配置与开箱即用的极简代码示例（即原“三板斧”）；
    2. **维度二：生产扩展接管 (Customization & Extensibility)**：当框架默认行为无法满足企业级生产定制时，开发者如何扩展或接管（如 SPI 自定义实现、`@ConditionalOnMissingBean` 覆盖、事件监听器、插件拦截器注入等，杜绝只教开箱不教定制）；
    3. **维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)**：显式声明该模块在极端、高并发或复杂事务场景下的底层行为机制（如持久层事务可见性与会话共享、MQ 消息幂等与重试死信、Redis 锁可重入与续期策略、网络超时熔断、内存防 OOM 等，杜绝非功能黑盒）；
    4. **维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)**：示例代码必跑通并锚定单测、YAML 配置逐字对齐属性类、Markdown 代码块严格闭合无破损，严禁格式事故与伪数据；
  - **“先测后文、文必有源”防臆造机制 (Zero-Hallucination & Traceability)**：
    - **代码源头锚定**：文档中展示的 Java 示例必须且只能直接提取自已跑通的单测类（`*Test.java`）或场景样例工程（`ateng-boot-sample-*`），严禁脱离代码在 Markdown 里凭空手敲；若某功能尚无现成范式，必须先在测试用例中写出并跑通后再提取至文档；
    - **配置源头锚定**：YAML/Properties 配置片段必须 100% 与当前模块的 `@ConfigurationProperties` 字段逐字对齐；
    - **预期呈现源头锚定**：控制台输出、实际 SQL 与响应结果必须直接截取自本地实际执行 `mvn test` 的真实控制台输出，严禁脑补拼接伪数据；
  - **重构防漂移与审查拦截 (Anti-Drift & Review Gate)**：修改核心 API 签名、类名或配置项时，必须全工程检索所属子模块 `README.md` 同步修正，杜绝陈旧失效代码；`/code-review` 强制将文档一致性、格式完整性与四维就绪度列为红牌必检项；
  - **四维职责清晰正交**：`README.md` 面向开发者，聚焦痛点与最小可用代码；`CONTEXT.md` 严格恪守“纯领域词汇表 (Glossary)”定位，只定义术语与 Avoid 负面词汇，严禁堆砌长篇实现细节；`CONTEXT-MAP.md` 专职维护系统拓扑与调用依赖；`docs/adr/` 专职沉淀重大架构选型；
  - **ADR 膨胀防线与合并收敛**：严格收紧独立 ADR 门槛，仅在孵化完整新中间件底座或重大协议契约重构时新建独立 ADR；存量模块优化一律优先在既有归属 ADR 增量修订。

---

## 4. 工程协同与 Git 流水线 (Git Workflow & Release)
- **双分支开发铁律 (Dual-Branch Policy: dev for Active Coding & main for Pure Release)**：本项目严格遵循 `main`（只读发布）与 `dev`（活跃开发）双分支演进模型；Agent 开展任何编码或修复工作前，必须确认当前处于 `dev` 分支；若检测到处于 `main`，必须主动执行 `git checkout dev` 切换至开发分支，严禁在 `main` 直接编辑业务代码；发版时严格收口于 `main` 分支（参见 [ADR-0009](./docs/adr/0009-dual-branch-workflow-main-release-and-dev-active.md)）。
- **精准路径暂存 (Safe Path Staging)**：暂存必须显式、精准指定具体的变更文件相对路径（如 `git add pom.xml scripts/commit.sh`），严禁使用 `.` 或 `-A` 全量通配，严禁在任何会话答复或建议命令中推介 `git add .`；从源头杜绝未跟踪的临时文件、本地私密配置（如 `.env`）或未经审阅的变更误入暂存区。
- **受控提交与脚本门面 (Explicit Git & Script Facade)**：仅当收到用户明确的“提交/commit/保存”指令时方可操作 Git，修改默认停留在工作区供审阅；执行提交时统一使用项目门面脚本：
  ```bash
  bash scripts/commit.sh -t <type> -s <scope> -m "<主说明>" [-l "<细项1>" -l "<细项2>"] [-c <issue>] [-n] [-p] -y
  ```
  单次包含多项改动时须在正文中以 `-l` 逐项罗列；涉及工单闭环时使用 `-c <issue>` 自动生成 `Closes #N`，支持单/多工单；可用 `-n` 演练提交信息。
- **推送后流水线守护 (Post-Push CI Monitoring Gate)**：代码推送到远程后必须对 GitHub Actions CI 流水线实施闭环监控；通过 `gh run list -L 1` 获取最新工作流 Run ID 并执行 `gh run watch <run-id>` 持续跟踪，直到全量构建与单元测试矩阵（JDK 17 & 21）100% 绿色通过；若检测到流水线失败，必须立即分析排查、修复本地代码并重新提交推送监控，直至流水线彻底成功后方可交付。
- **主干发布与运维解耦 (Trunk-Based Release & Decoupling)**：执行版本发布或排查发版流水线时，遵循 [docs/agents/release-workflow.md](./docs/agents/release-workflow.md)；版本发布必须且只能在 `main` 主干分支发起，前置要求工作区 100% 干净且与远端同步，统一通过 `bash scripts/release.sh` 调度。

---

## 5. Agent 协议与工单流转规范 (Agent Protocols & SOP)
- **官方 CLI 与工单生命周期闭环 (CLI & Ticket Lifecycle)**：
  GitHub Issues、PR 审阅与 CI 交互一律通过官方 `gh` 命令行闭环。工单生命周期严格遵循五阶段状态机：
  1. **认领**：查阅工单并指派当前开发者（`gh issue edit <n> --add-assignee "@me"`）；
  2. **TDD 与文档同步实现**：测试驱动编码，满足架构契约；同步在所属子模块 `README.md` 中按“四维生产就绪交付模型”补齐开箱代码、扩展定制接入、核心生产边界与实测输出契约；
  3. **准出与双轴审查**：执行第 3 节阶梯二准出测试（纯文档改动免测），通过后调用 `/code-review` 开展审查（文档真实性、代码块闭合性与四维完整度为红牌必检项）；
  4. **受控提交**：按第 4 节执行受控提交并推送（代码与文档同工单同提交原子交付）；
  5. **闭环**：守护 CI 监控至全绿，通过文件回写验收清单与提交哈希（`gh issue comment <n> -F <file>`），关闭工单（`gh issue close <n>`）。
- **工单操作与 CLI 避坑 (Issue Tracker & CLI)**：操作 GitHub Issues / PR 或编写 PowerShell 交互命令时，遵循 [docs/agents/issue-tracker.md](./docs/agents/issue-tracker.md)（含特殊字符引号包裹、`-F` 文件回写防截断及 Wayfinding 映射）。
- **分诊标签规范 (Triage Labels)**：分诊或标记 Issue 状态时，遵循 [docs/agents/triage-labels.md](./docs/agents/triage-labels.md)（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。
- **领域词汇与架构决策 (Domain Docs)**：探索代码库、设计新领域实体或查阅架构决策时，阅读全局映射 [CONTEXT-MAP.md](./CONTEXT-MAP.md) 及各模块独立的 `CONTEXT.md`，架构选型遵循 [docs/adr/](./docs/adr/)，规约详见 [docs/agents/domain.md](./docs/agents/domain.md)。
- **模块孵化与扩展通用标准 (Modular Extensibility)**：孵化新模块或创建场景样例工程时，遵循 [docs/agents/modular-extensibility.md](./docs/agents/modular-extensibility.md)（“工业级五件套”与端到端纯内存自验门禁）。
