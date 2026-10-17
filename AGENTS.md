# ateng-boot 研发指南与 Agent 行为指引

## 1. 项目定位与技术基线 (Project Baseline)
`ateng-boot` 是面向生产就绪的 Spring Boot 3 工业级基础设施与全能中间件 Starter 套件，坚持纯原生、零臃肿依赖与显式强类型契约设计，兼顾单体应用极速起步与分布式系统核心底座支撑。

- **运行与编译**：JDK 17 LTS（字节码 17，兼容 21+ 运行）
- **核心基线**：Spring Boot `3.3.13`，MyBatis-Plus `3.5.17`（参见 [ADR-0006](./docs/adr/0006-dependency-baseline-and-upgrade-strategy.md)）
- **命名坐标**：GroupId `io.github.atengk`，基础包名 `io.github.atengk.boot.*`，配置前缀 `ateng.boot.*`

---

## 2. 核心架构与编码契约 (Architecture & Contracts)
- **显式响应契约 (Explicit Contract)**：Controller 统一显式声明返回 `Result<T>`，依托强类型契约自洽；严禁引入 `@ResponseBodyAdvice` 全局自动拦截包装（参见 [ADR-0002](./docs/adr/0002-explicit-result-contract-no-response-body-advice.md)）。
- **零重型外部依赖与核心原语收敛 (Zero-Bloat & Core-First Invariants)**：彻底禁止引入 `hutool-all`、`guava`、`commons-collections` 等重型第三方全量工具包；100% 依托 JDK 17 原生能力与 Spring 6 内置基础工具；工具类依运行时上下文就近内聚（`core` 通用算法、`web` Servlet/HTTP、`mybatis-plus` ORM 映射等），坚决杜绝“通用垃圾桶 (Junk Drawer)”模块（参见 [ADR-0011](./docs/adr/0011-zero-bloat-and-co-located-utilities.md)）；**核心底座第一与通用原语收敛**：所有与具体中间件解耦的跨模块契约、通用上下文（用户与租户）、序列化门面、数据脱敏注解及通用工具原语，强制统一沉淀并收敛于 `ateng-boot-core`，严禁在下游子模块私有化闭环或重复造轮子；同时恪守运行时就近原则，强依赖特定 Servlet/ORM 驱动的组件仍严格就近安置（参见 [ADR-0025](./docs/adr/0025-core-module-expansion-and-future-proof-middleware-foundations.md)）。
- **工业级防御性与稳定性 (Defensive Stability & Zero-OOM)**：公共单例服务强制保持无状态；高并发分布式协同强制防死锁与原子性；批量与流式数据处理强制实施内存防爆（防 OOM）并在消费后即时清理引用；未知根异常强制生产脱敏，内部堆栈与敏感 SQL 仅记录于后台日志，严禁生吞异常（参见 [ADR-0004](./docs/adr/0004-http-200-with-business-code-and-exception-masking.md)）。
- **数据安全与“内存真明文”不变量 (In-Memory Plaintext Invariant)**：业务层与实体对象在内存中始终保持 100% 原始真实明文，严禁在业务逻辑中直接对实体属性打星号；存储加密收敛于持久层（入库密文、出库明文），展示脱敏收敛于视图序列化/导出拦截器，彻底杜绝掩码脏数据写回数据库（参见 [ADR-0012](./docs/adr/0012-lifecycle-separated-data-security.md)）。
- **传输契约与持久层彻底解耦 (Transport Decoupling)**：Controller 传输层严禁泄露 ORM `Page<T>` 或数据持久层实体；分页查询统一入参 `PageQuery`，统一返回契约 `PageResult<VO>`，在 Service 边界完成纯净转换（参见 [ADR-0010](./docs/adr/0010-transport-pagination-contract-decoupling.md)）。
- **防御性自动装配 (Defensive Autoconfiguration)**：自动装配类统配 `@AutoConfiguration` 标注，统一且只能注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（严格遵循 Spring Boot 3 规范，严禁使用旧版 `spring.factories`）；装配类标配 `@ConditionalOnClass`、`@ConditionalOnProperty`、`@ConditionalOnMissingBean` 守卫；Jackson 定制统一注入 `Jackson2ObjectMapperBuilderCustomizer`，严禁覆盖 `ObjectMapper` Bean（参见 [ADR-0005](./docs/adr/0005-jackson-customizer-over-objectmapper-override.md)）。
- **BOM 依赖统管与向后兼容 (BOM-First & Backward Compatibility)**：对外暴露的公共 API 签名、核心注解属性与依赖基线严禁随意破坏向后兼容；任何新增依赖及其版本必须且只能在根 `pom.xml` 或 `ateng-boot-bom/pom.xml` 的 `<dependencyManagement>` 中集中纳管；各子模块 `pom.xml` 声明依赖时一律免配 `<version>` 标签（参见 [ADR-0003](./docs/adr/0003-dependency-management-via-bom-import.md)）。
- **空安全与前置卫语句 (Null Safety & Defensive Return)**：查询列表/集合无匹配时统一返回空集合（如 `Collections.emptyList()`），严禁返回 `null`；单实体查询无结果时调用方必须通过前置卫语句显式拦截；实体继承五级阶梯链（`BaseGenericEntity` -> `BaseIdEntity` -> `BaseTimeEntity` -> `BaseEntity` -> `BaseFullEntity`），按需选用，`BaseFullEntity` 的 `deleted` 字段标配 `@JsonIgnore`。
- **全链路追踪与四维上下文传递 (Tracing & 4D Context Propagation)**：Web 请求统一经由 `TraceFilter` 注入 MDC `traceId`；异步方法流转通过受管线程池、`AsyncContext` 与 `ContextCopyingTaskDecorator` 实现 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 四维快照的全链路透传并在任务完成后严格恢复现场，彻底杜绝多租户跨线程数据越权与线程池污染。
- **配置属性类与 IDE 元数据完备 (IDE-Friendly Properties)**：配置属性类统一定义于 `io.github.atengk.boot.*.properties`，标配 `@ConfigurationProperties(prefix = "ateng.boot.*")`，字段提供完整 JavaDoc 说明与默认值，由 `spring-boot-configuration-processor` 自动编译生成 IDE 智能补全元数据。
- **统一注释规范 (Doc Standards)**：新建源码与核心 API 标配清晰业务注释，类头部标注 `@author Ateng` 与 `@since YYYY-MM-DD`。

---

## 3. 质量门禁与文档自洽 (Quality Gates & Docs)
- **新模块孵化与扩展通用标准 (Modular Extensibility SOP)**：未来无论扩展何种新功能模块，一律遵循“工业级五件套”闭环门禁：
  1. **BOM 统管版本**：在根 `pom.xml` 与 `ateng-boot-bom` 统一纳管版本，子模块声明依赖免配 `<version>`；
  2. **专属自包含文档**：子模块专属 `README.md` 严格遵循“一句话场景痛点、最小可用代码块、预期控制台/JSON/表格呈现”三板斧规范；
  3. **领域边界映射**：子模块配备独立的 `CONTEXT.md` 并在全局 `CONTEXT-MAP.md` 中注册清晰的上下文关系与依赖拓扑；
  4. **全自动自验用例**：在 `ateng-boot-samples` 中配套纯内存端（如 H2/Mock）的端到端自动化测试验证用例；
  5. **底座原语优先复用 (Core-First Reuse Gate)**：新增中间件模块（如 Storage、MQ、Job、AI 等）涉及容器查找、方法引用推导、JSON 序列化、分批切片、退避重试或路径清洗等需求时，强制 100% 优先复用 `ateng-boot-core` 已就绪的基础设施与标准件，坚决杜绝在子模块内私造工具类或引入第三方重复全量依赖。

- **反应堆单测准出门禁 (Reactor-Safe Test Gate)**：特性验收均以 `ateng-boot-samples` 模块执行 `mvn clean test` 100% 绿色通过作为唯一准出标准；多模块联动调试时必须在根目录执行 `mvn test` 或追加依赖构建 `-pl <module> -am`，杜绝单模块测试意外拉取 Maven Central 旧版 Jar。
- **特性即文档与单工单闭环门禁 (Doc-as-Feature & Single Ticket Closure)**：代码产生的功能与配置必须在相关文档中同步体现，严禁只堆砌代码而缺失使用说明；所有特性与接口改造均遵循“单工单文档准出闭环”原则，在同一个工单与提交中必须同步更新代码、根 README 与子模块 README；重大架构与设计权衡同步在 `docs/adr/` 沉淀对应 ADR；缺少文档与使用示例的改动一律视为未达验收标准。

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
  3. **单测准出与代码评审**：执行 `mvn test` 验证通过后，调用 `/code-review` 开展 Standards 与 Spec 双轴审查；
  4. **受控提交与验证回写**：在收到显式提交指令后，调用 `scripts/commit.sh` 精准提交并推送；
  5. **流水线守护与闭环关闭**：通过 `gh run list -L 1` 获取最新工作流 Run ID，执行 `gh run watch <run-id>` 跟踪 CI 流水线直至 100% 绿色通过；通过 `gh issue comment <n> -F <file>` 回写验收清单、提交哈希与 CI 结果；执行 `gh issue close <n>` 关闭工单，完成闭环。
- **分诊标签 (Triage Labels)**：采用 5 种标准分诊角色标签（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。详见 [docs/agents/triage-labels.md](./docs/agents/triage-labels.md)。
- **领域词汇与 ADR 沉淀 (Domain Docs)**：本项目采用多上下文结构。探索代码库前必须读取全局上下文映射表 [CONTEXT-MAP.md](./CONTEXT-MAP.md) 以及各模块独立的 `CONTEXT.md`；架构决策遵循 [docs/adr/](./docs/adr/) 下的 ADR 记录。规约详见 [docs/agents/domain.md](./docs/agents/domain.md)。

