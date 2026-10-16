# ateng-boot 研发指南与 Agent 行为指引

## 1. 项目定位与技术基线 (Project Baseline)
`ateng-boot` 是面向企业级的高内聚、轻量级、开箱即用的 Spring Boot 3 & JDK 17 基础设施与中间件 Starter 套件，坚持纯原生、零臃肿依赖设计，兼顾单体应用极速起步与未来微服务生态（`ateng-cloud`）底座支撑。

- **运行与编译**：JDK 17 LTS（字节码 17，兼容 21+ 运行）
- **核心基线**：Spring Boot `3.3.13`，MyBatis-Plus `3.5.17`（参见 [ADR-0006](./docs/adr/0006-dependency-baseline-and-upgrade-strategy.md)）
- **命名坐标**：GroupId `io.github.atengk`，基础包名 `io.github.atengk.boot.*`，配置前缀 `ateng.boot.*`

---

## 2. 核心架构与编码契约 (Architecture & Contracts)
- **显式响应契约 (Explicit Contract)**：Controller 统一显式声明返回 `Result<T>`，依托强类型契约自洽；严禁引入 `@ResponseBodyAdvice` 全局自动拦截包装（参见 [ADR-0002](./docs/adr/0002-explicit-result-contract-no-response-body-advice.md)）。
- **零重型外部依赖 (Zero-Bloat Invariant)**：彻底禁止引入 `hutool-all`、`guava`、`commons-collections` 等重型第三方全量工具包；100% 依托 JDK 17 原生能力与 Spring 6 内置基础工具；工具类依运行时上下文深度内聚（`core` 通用算法、`web` Servlet/HTTP、`mybatis-plus` ORM 映射），坚决杜绝“通用垃圾桶 (Junk Drawer)”模块（参见 [ADR-0011](./docs/adr/0011-zero-bloat-and-co-located-utilities.md)）。
- **传输契约与持久层彻底解耦 (Transport Decoupling)**：Controller 传输层严禁泄露 ORM `Page<T>` 或数据持久层实体；分页查询统一入参 `PageQuery`，统一返回契约 `PageResult<VO>`，在 Service 边界通过 `PageUtil` 或 `.map()` 完成转换（参见 [ADR-0010](./docs/adr/0010-transport-pagination-contract-decoupling.md)）。
- **数据安全与“内存真明文”不变量 (In-Memory Plaintext Invariant)**：Service 业务逻辑与实体对象在内存中始终保持 100% 原始真实明文，严禁在业务逻辑中直接对实体属性打星号；存储加密收敛于 MyBatis `TypeHandler`（入库密文、出库明文），展示脱敏收敛于 Jackson 视图序列化器（输出 JSON 动态掩码），杜绝掩码脏数据写回数据库（参见 [ADR-0012](./docs/adr/0012-lifecycle-separated-data-security.md)）。
- **防御性自动装配 (Defensive Autoconfiguration)**：自动装配类统配 `@AutoConfiguration` 标注，统一且只能注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（严格遵循 Spring Boot 3 规范，严禁使用旧版 `spring.factories`）；装配类标配 `@ConditionalOnClass`、`@ConditionalOnProperty`、`@ConditionalOnMissingBean` 守卫；Jackson 定制统一注入 `Jackson2ObjectMapperBuilderCustomizer`，严禁覆盖 `ObjectMapper` Bean（参见 [ADR-0005](./docs/adr/0005-jackson-customizer-over-objectmapper-override.md)）。
- **BOM 依赖统管与子模块零版本 (BOM-First Dependency Governance)**：任何新增依赖及其版本必须且只能在根 `pom.xml` 或 `ateng-boot-bom/pom.xml` 的 `<dependencyManagement>` 中集中纳管；各子模块 `pom.xml` 声明依赖时一律免配 `<version>` 标签（保持纯净继承，杜绝版本割裂）（参见 [ADR-0003](./docs/adr/0003-dependency-management-via-bom-import.md)）。
- **空安全与前置卫语句 (Null Safety & Defensive Return)**：查询列表/集合无匹配时统一返回空集合（如 `Collections.emptyList()`），严禁返回 `null`；单实体查询无结果时调用方必须通过前置卫语句显式拦截；实体继承五级阶梯链（`BaseGenericEntity` -> `BaseIdEntity` -> `BaseTimeEntity` -> `BaseEntity` -> `BaseFullEntity`），按需选用，`BaseFullEntity` 的 `deleted` 字段标配 `@JsonIgnore`。
- **全链路追踪与生产脱敏 (Tracing & Exception Sanitization)**：Web 请求统一经由 `TraceFilter` 注入 MDC `traceId`，异步方法流转通过受管线程池与 `ContextCopyingTaskDecorator` 透传；全局异常处理器对未知根异常强制生产脱敏，内部堆栈与敏感 SQL 仅记录于后台日志（参见 [ADR-0004](./docs/adr/0004-http-200-with-business-code-and-exception-masking.md)）。
- **配置属性类与 IDE 元数据完备 (IDE-Friendly Properties)**：配置属性类统一定义于 `io.github.atengk.boot.*.properties`，标配 `@ConfigurationProperties(prefix = "ateng.boot.*")`，字段提供完整 JavaDoc 说明与默认值，由 `spring-boot-configuration-processor` 自动编译生成 IDE 智能补全元数据。
- **统一注释规范 (Doc Standards)**：新建源码与核心 API 标配清晰业务注释，类头部标注 `@author Ateng` 与 `@since YYYY-MM-DD`。

---

## 3. 质量门禁与文档自洽 (Quality Gates & Docs)
- **反应堆单测准出门禁 (Reactor-Safe Test Gate)**：特性验收均以 `ateng-boot-samples` 模块执行 `mvn clean test` 100% 绿色通过作为唯一准出标准；多模块联动调试时必须在根目录执行 `mvn test` 或追加依赖构建 `-pl <module> -am`，杜绝单模块测试意外拉取 Maven Central 旧版 Jar。
- **特性即文档与子模块内聚门禁 (Doc-as-Feature & Submodule-Co-located Sync)**：代码产生的功能与配置必须在相关文档中同步体现，严禁只堆砌代码而缺失使用说明；所有特性与接口改造均遵循“单工单文档准出闭环”原则，在同一个工单与提交中必须同步更新文档；采用“子模块内聚”文档拓扑——根目录 `README.md` 保持轻量门户与各模块索引导航，各模块具体代码范式与配置属性完整收敛沉淀于对应子模块专属的 `README.md`（如 `ateng-boot-core/README.md`、`ateng-boot-web/README.md`、`ateng-boot-mybatis-plus/README.md`），确保功能详实可查可复制；复杂架构专题沉淀于 `docs/guides/`；文档示例严格遵循“三板斧”质量规范：① 一句话场景痛点、② 可直接复制粘贴的最小 Java/YAML 代码块、③ 预期控制台/SQL/JSON 契约呈现；缺少文档与使用示例的改动一律视为未达验收标准。

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
- **标准工单生命周期 SOP (End-to-End Ticket Lifecycle)**：
  1. **检索与认领**：使用 `gh issue view <n> --comments` 获取上下文；认领统一执行 `gh issue edit <n> --add-assignee "@me"`（PowerShell 环境下参数务必加双引号）；
  2. **TDD 测试驱动实现**：在最高切入点编写验证用例，遵循架构与编码契约完成特性编码；
  3. **单测准出与代码评审**：执行 `mvn test` 验证通过后，调用 `/code-review` 开展 Standards 与 Spec 双轴审查；
  4. **受控提交与验证回写**：在收到显式提交指令后，调用 `scripts/commit.sh` 精准提交并推送；
  5. **流水线守护与闭环关闭**：执行 `gh run watch` 跟踪 CI 流水线直至 100% 绿色通过；通过 `gh issue comment <n> -F <file>` 回写验收清单、提交哈希与 CI 结果；执行 `gh issue close <n>` 关闭工单，完成闭环。
- **分诊标签 (Triage Labels)**：采用 5 种标准分诊角色标签（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。详见 [docs/agents/triage-labels.md](./docs/agents/triage-labels.md)。
- **领域词汇与 ADR 沉淀 (Domain Docs)**：本项目采用多上下文结构。探索代码库前必须读取全局上下文映射表 [CONTEXT-MAP.md](./CONTEXT-MAP.md) 以及各模块独立的 `CONTEXT.md`；架构决策遵循 [docs/adr/](./docs/adr/) 下的 ADR 记录。规约详见 [docs/agents/domain.md](./docs/agents/domain.md)。
