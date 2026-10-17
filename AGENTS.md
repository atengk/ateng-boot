# ateng-boot 研发指南与 Agent 行为指引

## 1. 项目定位与技术基线 (Project Baseline)
`ateng-boot` 是面向生产就绪的 Spring Boot 3 工业级基础设施与全能中间件 Starter 套件，坚持纯原生、零臃肿依赖与显式强类型契约设计，兼顾单体应用极速起步与分布式系统核心底座支撑。

- **运行与编译**：JDK 17 LTS（字节码 17，兼容 21+ 运行）
- **核心基线**：Spring Boot `3.3.13`，MyBatis-Plus `3.5.17`（参见 [ADR-0006](./docs/adr/0006-dependency-baseline-and-upgrade-strategy.md)）
- **命名坐标**：GroupId `io.github.atengk`，基础包名 `io.github.atengk.boot.*`，配置前缀 `ateng.boot.*`

---

## 2. 核心架构与编码契约 (Architecture & Contracts)
- **显式响应契约 (Explicit Contract)**：Controller 统一显式声明返回 `Result<T>`，依托强类型契约自洽；严禁引入 `@ResponseBodyAdvice` 全局自动拦截包装（参见 [ADR-0002](./docs/adr/0002-explicit-result-contract-no-response-body-advice.md)）。
- **零臃肿与底座优先 (Zero-Bloat & Core-First)**：
  - 100% 依托 JDK 17 与 Spring 6 原生能力，严禁引入 `hutool-all`、`guava`、`commons-collections` 等重型全量工具包；
  - 工具类就近内聚（`core` 通用算法、`web` Servlet/HTTP、`mybatis-plus` ORM 映射等），坚决杜绝垃圾桶模块（参见 [ADR-0011](./docs/adr/0011-zero-bloat-and-co-located-utilities.md)）；
  - **通用原语底座收敛**：跨中间件解耦的通用上下文（用户/租户）、序列化门面、数据脱敏与基础算法原语，强制收敛于 `ateng-boot-core`，严禁在下游子模块重复造轮子（参见 [ADR-0025](./docs/adr/0025-core-module-expansion-and-future-proof-middleware-foundations.md)）。
- **分层双防校验体系 (Defense-in-Depth Validation)**：
  - **第一道防线（传输边界 / Controller）**：负责无状态语法与格式性校验，统一使用 JSR-303 注解声明以实现 Fail-Fast；批量列表入参强制使用 `ValidList<E>` 杜绝 Spring 默认漏检；分组校验统一使用 `ValidationGroups` 标准标记（参见 [ADR-0027](./docs/adr/0027-defense-in-depth-validation-and-standardized-primitives.md)）；
  - **第二道防线（业务边界 / Service）**：负责业务语义与状态不变量校验，统一推行 `BusinessAssert` 语义化断言；非 Web 边界消费使用 `ValidatorUtil.validate(obj)` 执行快速失败编程式防卫。
- **数据安全与“内存真明文”不变量 (In-Memory Plaintext Invariant)**：
  - 业务层与实体对象在内存中始终保持 100% 原始真实明文，严禁在业务逻辑中直接对实体属性打星号；
  - 存储加密收敛于持久层（入库密文、出库明文），展示脱敏收敛于视图序列化/导出拦截器，彻底杜绝掩码脏数据写回数据库（参见 [ADR-0012](./docs/adr/0012-lifecycle-separated-data-security.md)）。
- **传输契约与持久层彻底解耦 (Transport Decoupling)**：Controller 传输层严禁泄露 ORM `Page<T>` 或数据持久层实体；分页查询统一入参 `PageQuery`，统一返回契约 `PageResult<VO>`，在 Service 边界完成纯净流式转换（参见 [ADR-0010](./docs/adr/0010-transport-pagination-contract-decoupling.md)）。
- **实体继承五级阶梯链 (Entity Staircase)**：实体继承五级阶梯链（`BaseGenericEntity` -> `BaseIdEntity` -> `BaseTimeEntity` -> `BaseEntity` -> `BaseFullEntity`），按需选用，`BaseFullEntity` 的 `deleted` 字段标配 `@JsonIgnore`。
- **防御性自动装配 (Defensive Autoconfiguration)**：
  - 自动装配类统配 `@AutoConfiguration` 标注，统一且只能注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（严格遵循 Spring Boot 3 规范，严禁使用旧版 `spring.factories`）；
  - 装配类标配 `@ConditionalOnClass`、`@ConditionalOnProperty`、`@ConditionalOnMissingBean` 守卫；
  - Jackson 定制统一注入 `Jackson2ObjectMapperBuilderCustomizer`，严禁覆盖 `ObjectMapper` Bean（参见 [ADR-0005](./docs/adr/0005-jackson-customizer-over-objectmapper-override.md)）。
- **BOM 依赖统管与向后兼容 (BOM-First & Backward Compatibility)**：
  - 对外暴露的公共 API 签名、核心注解属性与依赖基线严禁随意破坏向后兼容；
  - 任何新增依赖及其版本必须且只能在根 `pom.xml` 或 `ateng-boot-bom/pom.xml` 的 `<dependencyManagement>` 集中纳管；各子模块 `pom.xml` 声明依赖时一律免配 `<version>` 标签（参见 [ADR-0003](./docs/adr/0003-dependency-management-via-bom-import.md)）。
- **空安全与无状态设计 (Null Safety & Defensive Return)**：
  - 查询列表/集合无匹配时统一返回空集合（如 `Collections.emptyList()`），严禁返回 `null`；单实体查询无结果时调用方必须通过前置卫语句显式拦截；
  - 公共单例服务强制保持无状态，严禁使用非线程安全类或普通成员变量共享可变业务状态。
- **全链路追踪与四维上下文传递 (Tracing & 4D Context Propagation)**：
  - Web 请求统一经由 `TraceFilter` 注入 MDC `traceId`（基于无锁 `fastSimpleUUID`）；
  - 异步任务通过受管线程池、`AsyncContext` 与 `ContextCopyingTaskDecorator` 实现 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 四维快照的全链路透传并在任务完成后严格清理现场（参见 [ADR-0004](./docs/adr/0004-http-200-with-business-code-and-exception-masking.md)、[ADR-0026](./docs/adr/0026-production-hardening-and-foundations-unification.md)）。
- **配置属性类与 IDE 元数据完备 (IDE-Friendly Properties)**：配置属性类统一定义于 `io.github.atengk.boot.*.properties`，标配 `@ConfigurationProperties(prefix = "ateng.boot.*")`，字段提供完整 JavaDoc 说明与默认值，由 `spring-boot-configuration-processor` 自动编译生成 IDE 智能补全元数据。
- **统一注释规范 (Doc Standards)**：新建源码与核心 API 标配清晰业务注释，类头部标注 `@author Ateng` 与 `@since YYYY-MM-DD`。

---

## 3. 质量门禁与文档自洽 (Quality Gates & Docs)
- **新模块孵化与扩展通用标准 (Modular Extensibility SOP)**：未来无论扩展何种新功能模块，一律遵循“工业级五件套”闭环门禁：
  1. **BOM 统管版本**：在根 `pom.xml` 与 `ateng-boot-bom` 统一纳管版本，子模块声明依赖免配 `<version>`；
  2. **专属自包含文档**：子模块专属 `README.md` 严格遵循“一句话场景痛点、最小可用代码块、预期控制台/JSON/表格呈现”三板斧规范；
  3. **领域边界映射**：子模块配备独立的 `CONTEXT.md`（严格恪守纯领域词汇表定位，严禁堆砌长篇实现代码）并在全局 `CONTEXT-MAP.md` 中注册清晰的上下文关系与依赖拓扑；
  4. **全自动自验用例**：在 `ateng-boot-samples` 中配套纯内存端（如 H2/Mock）的端到端自动化测试验证用例；
  5. **底座原语优先复用 (Core-First Reuse Gate)**：新增中间件模块（如 Storage、MQ、Job、AI 等）涉及容器查找、方法引用推导、JSON 序列化、分批切片、退避重试或路径清洗等需求时，强制 100% 优先复用 `ateng-boot-core` 已就绪的基础设施与标准件，坚决杜绝在子模块内私造工具类或引入第三方重复全量依赖。

- **三阶梯测试准出与构建并行加速铁律 (Three-Tier Test Gate & Parallel Build)**：
  面对日益增多的框架模块，严禁在每次改动时无脑在本地执行全项目串行单测（避免陷入耗时膨胀泥潭），实行三阶梯智能分流与并行加速：
  1. **阶梯一：特性日常自验（秒级极速反馈）**：单个模块改动时，采用多线程依赖树精准定向测试：`mvn test -pl <changed-module> -am -T 1C`（利用多核 CPU 并发编译上游依赖，仅执行当前变更模块单测）；
  2. **阶梯二：本地提交准出验收（精准端到端集成）**：准备提交前，若涉及底座（`core`）或核心中间件改动，执行变更模块与集成示例工程联动验证：`mvn test -pl <changed-module>,ateng-boot-samples -am -T 1C`，由 `ateng-boot-samples` 纯内存端到端集成测试完成关键跨模块链路验收，严禁无脑回归互不相干的隔离兄弟模块（如改动 `redis` 绝不执行沉重的 `excel` 或 `satoken` 单测）；
  3. **阶梯三：全反应堆多 JDK 矩阵终极守护 (Post-Push CI Gate)**：全反应堆所有模块（100% 模块全集）在 Linux/Windows 及 JDK 17 & 21 矩阵下的全量回归，100% 交由 GitHub Actions CI 云端并行工作流异步守护；本地推送后通过 `gh run watch <run-id>` 闭环跟踪至全绿。

- **特性即文档与四维正交文档生态 (Doc-as-Feature & Orthogonal Docs)**：
  - **四维职责清晰正交**：`README.md` 面向开发者，聚焦痛点与最小可用代码；`CONTEXT.md` 严格恪守“纯领域词汇表 (Glossary)”定位，只定义术语与 Avoid 负面词汇，严禁堆砌长篇实现细节；`CONTEXT-MAP.md` 专职维护系统拓扑与调用依赖；`docs/adr/` 专职沉淀重大架构选型。
  - **ADR 膨胀防线与合并收敛铁律 (Anti-ADR-Inflation Gate)**：严格收紧独立 ADR 门槛，仅在“新孵化完整中间件底座（如 Kafka/OSS/Job）”、“重大协议契约重构”或“颠覆性技术路线取舍”时才新建独立编号 ADR；针对存量模块的基线加固、性能优化、API 增强与微小标准件扩充，**一律优先在既有归属 ADR 中以增量章节修订**，坚决杜绝因微小类新增而碎片化滥立 ADR。
  - **文档真理源不变量 (Evergreen Code-Docs Invariant)**：文档中呈现的所有配置与代码示例，必须 100% 源自 `ateng-boot-samples` 或单元测试中实际编译运行通过的真实代码片段，坚决杜绝纯手工臆造“未经测试验证的伪代码”写入文档。

---

## 4. Git 协同与提交流水线 (Git Workflow & Release)
- **双分支开发铁律 (Dual-Branch Policy: dev for Active Coding & main for Pure Release)**：本项目严格遵循 `main`（只读发布）与 `dev`（活跃开发）双分支演进模型；Agent 开展任何编码、新增特性或 Bug 修复工作前，必须确认当前处于 `dev` 分支；若检测到处于 `main`，必须主动执行 `git checkout dev` 切换至开发分支，严禁在 `main` 直接编辑业务代码；发版时严格收口于 `main` 分支（参见 [ADR-0009](./docs/adr/0009-dual-branch-workflow-main-release-and-dev-active.md)）。
- **精准路径暂存 (Safe Path Staging)**：暂存必须显式、精准指定具体的变更文件相对路径（如 `git add pom.xml scripts/commit.sh`），严禁使用 `.` 或 `-A` 全量通配，严禁在任何会话答复或建议命令中推介 `git add .`；从源头杜绝未跟踪的临时文件、本地私密配置（如 `.env`）或未经审阅的变更误入暂存区。
- **受控提交与脚本门面 (Explicit Git & Script Facade)**：仅当收到用户明确的“提交/commit/保存”指令时方可操作 Git，修改默认停留在工作区供审阅；执行提交时优先使用项目统一门面 `bash scripts/commit.sh -t <type> -s <scope> -m "<主说明>" [-l "<细项1>" -l "<细项2>"] [-c <issue>] [-n] [-p] -y` 确保提交完全符合 Conventional Commits 规约并受本地钩子守护（单次包含多项改动时须在正文中以 `-l` 逐项罗列；涉及工单闭环时使用 `-c <issue>` 自动生成 `Closes #N`，支持单/多工单；可用 `-n` 演练提交信息）；发版统一通过 `bash scripts/release.sh` 调度。
- **推送后流水线守护 (Post-Push CI Monitoring Gate)**：代码推送到远程后必须对 GitHub Actions CI 流水线实施闭环监控；通过 `gh run list -L 1` 获取最新工作流 Run ID 并执行 `gh run watch <run-id>` 持续跟踪，直到全量构建与单元测试矩阵（JDK 17 & 21）100% 绿色通过；若检测到流水线失败，必须立即分析排查、修复本地代码并重新提交推送监控，直至流水线彻底成功后方可交付。
- **主干发布铁律与分支防呆 (Trunk-Based Release & Branch Guard)**：版本发布有严格规范，必须且只能在 `main` 主干分支发起，严禁从任何未经合入的特性分支或孤立提交发版；发版前置要求工作区 100% 干净且与远端同步；CI 流水线针对网页调度与 Tag 推送实施双重祖先谱系校验（`git merge-base --is-ancestor`）确保发布产物 100% 源自主干（参见 [ADR-0008](./docs/adr/0008-automated-release-changelog-and-evergreen-docs.md)）。
- **部署隔离与 Central 解耦 (Publishing Decoupling)**：非分发模块（如 `ateng-boot-samples`）自身标配声明 `<maven.deploy.skip>true</maven.deploy.skip>`，且根发布插件协同配置 `<excludeArtifacts>` 排除，杜绝多模块反应堆封包缺 POM 导致 Central 拒收；发布插件使用 `<waitUntil>validated</waitUntil>` 极速异步解耦（参见 [ADR-0007](./docs/adr/0007-maven-publishing-and-clean-ci-pipeline.md)）。
- **文档自驱与单一版本流 (Clean Docs & Single Version Stream)**：文档接入依赖坚持展示纯净具体版本（如 `0.0.5`）供开发者一键复制即用，严禁在 XML 中插入机器注释；发版由 `scripts/release.sh` 内置原生 `sed -i` 原地直接流式修改同步更新文档与 POM；发版提交由 CI 内置智能网关识别 `chore(release):` 秒级短路放行，由 Release 工作流全权闭环；主干始终保持在最新稳定正式版，彻底消除 SNAPSHOT 冗余提交与双重 CI 干扰（参见 [ADR-0008](./docs/adr/0008-automated-release-changelog-and-evergreen-docs.md)）。

---

## 5. Agent 技能与工单流转规范 (Agent Skills & Ticket SOP)
- **GitHub CLI (gh) 闭环管控铁律 (Mandatory gh Command Line Usage)**：
  - 本项目严禁脱离终端命令行进行任何手工网页点击操作或盲目猜测远端状态，所有与 GitHub 仓库的交互（Issues 工单流转、PR 审阅、Actions 流水线监控与 Release 检查）**必须且只能通过官方 `gh` 命令行工具进行全生命周期闭环管控**；
  - **跨平台参数防转义**：Windows PowerShell 环境下执行 `gh` 命令时，含特殊字符（如 `@me`）或换行文本的参数务必显式使用双引号包裹（如 `--add-assignee "@me"`），长文本回写强制使用 `-F <file>` 避免 shell 转义截断。
- **标准工单生命周期 SOP (End-to-End Ticket Lifecycle)**：
  1. **检索与认领**：使用 `gh issue view <n> --comments` 获取上下文；认领统一执行 `gh issue edit <n> --add-assignee "@me"`；
  2. **TDD 测试驱动实现**：在最高切入点编写验证用例，遵循架构与编码契约完成特性编码；
  3. **单测准出与代码评审**：执行阶梯二准出测试（`mvn test -pl <changed-module>,ateng-boot-samples -am -T 1C`）验证通过后，调用 `/code-review` 开展 Standards 与 Spec 双轴审查；
  4. **受控提交与验证回写**：在收到显式提交指令后，调用 `scripts/commit.sh` 精准提交并推送；
  5. **流水线守护与闭环关闭**：通过 `gh run list -L 1` 获取最新工作流 Run ID，执行 `gh run watch <run-id>` 跟踪 CI 流水线直至 100% 绿色通过；通过 `gh issue comment <n> -F <file>` 回写验收清单、提交哈希与 CI 结果；执行 `gh issue close <n>` 关闭工单，完成闭环。
- **分诊标签 (Triage Labels)**：采用 5 种标准分诊角色标签（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。详见 [docs/agents/triage-labels.md](./docs/agents/triage-labels.md)。
- **领域词汇与 ADR 沉淀 (Domain Docs)**：本项目采用多上下文结构。探索代码库前必须读取全局上下文映射表 [CONTEXT-MAP.md](./CONTEXT-MAP.md) 以及各模块独立的 `CONTEXT.md`；架构决策遵循 [docs/adr/](./docs/adr/) 下的 ADR 记录。规约详见 [docs/agents/domain.md](./docs/agents/domain.md)。
