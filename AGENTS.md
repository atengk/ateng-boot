# ateng-boot 研发指南与 Agent 行为指引

## 1. 项目定位与技术基线
`ateng-boot` 是基于 Spring Boot 3 与 JDK 17 的基础框架与中间件 Starter 套件，对标 Hutool 的精炼、高内聚心智，兼顾单体应用开箱即用与未来微服务生态（`ateng-cloud`）底座支撑。

- **运行与编译**：JDK 17 LTS（字节码 17，兼容 21+ 运行）
- **核心基线**：Spring Boot `3.3.13`，MyBatis-Plus `3.5.17`（参见 [ADR-0006](./docs/adr/0006-dependency-baseline-and-upgrade-strategy.md)）
- **命名坐标**：GroupId `io.github.atengk`，基础包名 `io.github.atengk.boot.*`，配置前缀 `ateng.boot.*`

## 2. 核心工程准则 (Core Principles)
- **显式契约 (Explicit Contract)**：Controller 统一显式声明返回 `Result<T>`；禁止引入 `@ResponseBodyAdvice` 自动拦截包装（参见 [ADR-0002](./docs/adr/0002-explicit-result-contract-no-response-body-advice.md)）。
- **防御性装配 (Defensive Autoconfiguration)**：自动装配标配 `@ConditionalOnClass` / `@ConditionalOnProperty` 守卫；Jackson 定制统一通过 `Jackson2ObjectMapperBuilderCustomizer` 注入，严禁覆盖 `ObjectMapper` Bean（参见 [ADR-0005](./docs/adr/0005-jackson-customizer-over-objectmapper-override.md)）。
- **空安全与前置拦截 (Null Safety)**：列表/集合查询无匹配时统一返回空集合，严禁返回 `null`；单实体查询无结果时通过前置卫语句拦截；`BaseEntity` 的 `deleted` 字段标配 `@JsonIgnore`。
- **全链路追踪与脱敏 (Tracing & Sanitization)**：Web 请求统一经由 `TraceFilter` 注入 MDC `traceId`；统一使用受管 SLF4J 日志；全局异常处理对未知根异常强制生产脱敏，内部堆栈与敏感 SQL 仅记录于后台日志（参见 [ADR-0004](./docs/adr/0004-http-200-with-business-code-and-exception-masking.md)）。
- **统一注释规范 (Doc Standards)**：新建源码与核心 API 标配清晰业务注释，类头部标注 `@author Ateng` 与 `@since YYYY-MM-DD`。
- **完成判据与单测门禁 (Completion Criterion)**：所有代码改动与特性落地，均以 `ateng-boot-samples` 模块执行 `mvn clean test` 100% 绿色通过作为准出标准。
- **精准路径暂存 (Safe Path Staging)**：暂存必须显式、精准指定具体的变更文件相对路径（如 `git add pom.xml scripts/commit.sh`），严禁使用 `.` 或 `-A` 全量通配，严禁在任何会话答复或建议命令中推介 `git add .`；从源头杜绝未跟踪的临时文件、本地私密配置（如 `.env`）或未经审阅的变更误入暂存区。
- **部署隔离与 Central 解耦 (Publishing Decoupling)**：非分发模块（如 `ateng-boot-samples`）自身标配声明 `<maven.deploy.skip>true</maven.deploy.skip>`，且根发布插件协同配置 `<excludeArtifacts>` 排除，杜绝多模块反应堆封包缺 POM 导致 Central 拒收；发布插件使用 `<waitUntil>validated</waitUntil>` 极速异步解耦（参见 [ADR-0007](./docs/adr/0007-maven-publishing-and-clean-ci-pipeline.md)）。
- **文档自驱与单一版本流 (Clean Docs & Single Version Stream)**：文档接入依赖坚持展示纯净具体版本（如 `0.0.4`）供开发者一键复制即用，严禁在 XML 中插入机器注释；发版由 `scripts/release.sh` 内置原生 `sed -i` 原地直接流式修改同步更新文档与 POM；发版提交由 CI 内置智能网关识别 `chore(release):` 秒级短路放行，由 Release 工作流全权闭环；主干始终保持在最新稳定正式版，彻底消除 SNAPSHOT 冗余提交与双重 CI 干扰（参见 [ADR-0008](./docs/adr/0008-automated-release-changelog-and-evergreen-docs.md)）。
- **主干发布铁律与分支防呆 (Trunk-Based Release & Branch Guard)**：版本发布有严格规范，必须且只能在 `main` 主干分支发起，严禁从任何未经合入的特性分支或孤立提交发版；发版前置要求工作区 100% 干净且与远端同步；CI 流水线针对网页调度与 Tag 推送实施双重祖先谱系校验（`git merge-base --is-ancestor`）确保发布产物 100% 源自主干（参见 [ADR-0008](./docs/adr/0008-automated-release-changelog-and-evergreen-docs.md)）。
- **受控提交与脚本门面 (Explicit Git & Script Facade)**：仅当收到用户明确的“提交/commit/保存”指令时方可操作 Git，修改默认停留在工作区供审阅；执行提交时优先使用项目统一门面 `bash scripts/commit.sh -t <type> -s <scope> -m "<主说明>" [-l "<细项1>" -l "<细项2>"] [-p] -y` 确保提交完全符合 Conventional Commits 规约并受本地钩子守护（单次包含多项改动时须在正文中以 `-l` 逐项罗列）；发版统一通过 `bash scripts/release.sh` 调度。

## 3. Agent 技能与上下文指引 (Agent Skills)

### Issue 跟踪器 (Issue tracker)
本项目的 Issue 与特性需求均托管于 GitHub Issues，使用 `gh` CLI 执行检索、认领与流转。详见 [docs/agents/issue-tracker.md](./docs/agents/issue-tracker.md)。

### 分诊标签 (Triage labels)
采用 5 种标准分诊角色标签（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。详见 [docs/agents/triage-labels.md](./docs/agents/triage-labels.md)。

### 领域文档与 ADR (Domain docs)
本项目采用多上下文结构。探索代码库前必须读取全局上下文映射表 [CONTEXT-MAP.md](./CONTEXT-MAP.md) 以及各模块独立的 `CONTEXT.md`；架构决策遵循 [docs/adr/](./docs/adr/) 下的 ADR 记录。规约详见 [docs/agents/domain.md](./docs/agents/domain.md)。
