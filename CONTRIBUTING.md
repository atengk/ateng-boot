# 贡献指南 (Contributing Guide)

感谢你关注并愿意为 `ateng-boot` 贡献代码！作为面向现代化 Spring Boot 3 与 JDK 17 的基础框架与中间件 Starter 套件，我们欢迎并鼓励社区的各种形式贡献——无论是报告缺陷、完善文档还是提交新特性。

为保障代码库高质量交付与团队协同顺畅，请在提交前查阅本指南。

---

## 1. 行为准则 (Code of Conduct)

参与本项目的所有贡献者均须遵守友善、包容与互相尊重的基本原则。严禁任何形式的骚扰、人身攻击或歧视性言行。

---

## 2. 研发准备与环境基线

- **开发工具**：IntelliJ IDEA 或 VS Code
- **运行基线**：JDK 17 LTS（字节码 17，兼容 JDK 21+ 运行）
- **核心依赖**：Spring Boot 3.3.13、MyBatis-Plus 3.5.17
- **工程构建**：Apache Maven 3.6.3+

在本地克隆代码后，执行以下命令验证构建环境并激活本地提交守门钩子：

```bash
git clone https://github.com/atengk/ateng-boot.git
cd ateng-boot

# 激活本地 Git 提交守门钩子 (毫秒级拦截非标提交)
git config core.hooksPath .githooks

# 执行全工程测试验证构建环境
mvn clean test
```

---

## 3. 分支与工作流管理

- **主干分支 (`main`)**：受保护分支，**唯一合规发版来源**，仅接受通过 CI 门禁检查的 Pull Request 合并；
- **特性分支 (`feat/*`)**：基于 `main` 拉出，用于新功能研发；
- **修复分支 (`fix/*`)**：基于 `main` 拉出，用于 Bug 修复；
- **主干发布铁律 (Trunk-Based Release)**：严格禁止在任何特性分支或临时分支发版！CI 流水线针对网页调度与 Tag 推送实施双重祖先谱系校验（`git merge-base --is-ancestor`）确保发布产物 100% 源自主干；
- **精准路径暂存 (Safe Path Staging)**：暂存时必须显式指定具体修改文件的相对路径（如 `git add pom.xml scripts/commit.sh`），严禁使用 `git add .` 或 `git add -A` 通配，从源头杜绝未跟踪的临时文件或敏感配置误入暂存区。

```bash
# 从最新 main 创建特性分支
git checkout main
git pull origin main
git checkout -b feat/your-feature-name
```

---

## 4. 提交信息规范 (Conventional Commits)

本项目强制遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/) 规范，CI 流水线将对 PR 标题与提交进行自动化拦截校验：

```text
<type>(<scope>): <清晰中文描述>
```

### 推荐首选：使用交互式提交助手 (`scripts/commit.sh`)

为避免人工拼写类型错误或格式遗漏，**强烈推荐使用项目内置的提交助手脚本**。当单次提交包含多个细化功能或模块改动时，须在提交信息正文中逐项罗列说明：

```bash
# 交互向导模式 (纯中文提示选择类型、输入 scope、描述与多行列表详情，并可自动暂存与推送)
bash scripts/commit.sh

# 非交互式参数模式 (支持 -l / --item 多项改动清晰罗列)
bash scripts/commit.sh -t feat -s web -m "增强 TraceFilter 链路追踪能力" \
  -l "支持通过配置排除静态资源路径过滤" \
  -l "规范响应头 X-Trace-Id 自动回写" \
  -p -y
```

### 常用类型 (`type`)
- `feat`: 新增功能特性（如新 Starter、新注解、新断言）
- `fix`: 缺陷修复
- `docs`: 文档变更（如 README、ADR 记录）
- `style`: 代码格式调整（不影响业务逻辑）
- `refactor`: 代码重构（非新功能、非修复）
- `perf`: 性能优化
- `test`: 单元测试或集成测试新增/重构
- `build`: 构建配置或外部依赖变动（如 pom.xml 升级）
- `ci`: CI/CD 自动化流水线变动（如 GitHub Actions、.cliff.toml）
- `chore`: 其他常规工程维护与杂项
- `revert`: 代码回滚

### 影响范围 (`scope`)
建议优先标注具体的子模块名（如 `core`、`web`、`mybatis-plus`、`all`、`samples`、`bom`），或特定工程维度的功能领域。

### 常用研发场景推荐搭配速查表

为避免提交类型选择模糊，针对日常高频研发场景，推荐遵循以下搭配范式：

| 研发业务场景 | 推荐 Type 与 Scope 组合 | 典型提交说明示例 |
| :--- | :--- | :--- |
| **新增功能特性** | `feat(<模块名>)` | `feat(core): 支持函数式 CurrentUserProvider 扩展` |
| **缺陷与 Bug 修复** | `fix(<模块名>)` | `fix(web): 修复未知异常脱敏时堆栈丢失问题` |
| **安全漏洞应急修复** | `fix(security)` | `fix(security): 升级高危 CVE 传输协议依赖` |
| **依赖与插件版本升级** | `build(deps)` | `build(deps): 升级 Spring Boot 补丁版至 3.3.13` |
| **工程构建与脚手架调整** | `build(pom)` / `build(mvn)` | `build(pom): 优化中央仓库发布插件配置` |
| **CI/CD 流水线修改** | `ci(workflow)` / `ci(release)` | `ci(release): 完善 GPG 签名与多架构构建流水线` |
| **架构演进与代码重构** | `refactor(<模块名>)` | `refactor(mp): 重构自动填充处理器提升执行效率` |
| **性能调优与瓶颈突破** | `perf(<模块名>)` | `perf(web): 优化 TraceFilter 链路头提取吞吐量` |
| **文档与架构决策变更** | `docs` / `docs(adr)` | `docs(adr): 沉淀 ADR-0007 发布解耦架构决策` |
| **单测与自验套件增强** | `test(<模块名>)` | `test(samples): 补充乐观锁 CAS 并发冲突测试用例` |
| **发版准备与版本管理** | `chore(release)` | `chore(release): 准备发布 v0.0.1 正式版本` |
| **代码格式与排版微调** | `style` | `style: 优化 JavaDoc 排版与无用导入清理` |
| **异常变更回滚** | `revert(<模块名>)` | `revert(web): 回滚破坏向后兼容性的响应包装逻辑` |

---

## 5. 核心代码规范红线

在编写或修改源码时，请严格遵循以下准则：

1. **显式契约 (Explicit Contract)**：Controller 统一显式声明返回 `Result<T>`；禁止引入 `@ResponseBodyAdvice` 自动拦截包装（参见 [ADR-0002](./docs/adr/0002-explicit-result-contract-no-response-body-advice.md)）。
2. **防御性装配 (Defensive Autoconfiguration)**：自动装配标配 `@ConditionalOnClass` / `@ConditionalOnProperty` 守卫；Jackson 定制统一通过 `Jackson2ObjectMapperBuilderCustomizer` 注入，严禁覆盖 `ObjectMapper` Bean（参见 [ADR-0005](./docs/adr/0005-jackson-customizer-over-objectmapper-override.md)）。
3. **空安全与防御断言**：列表/集合查询无匹配时统一返回空集合，严禁返回 `null`；单实体查询无结果时通过 `BusinessAssert.notNull` 前置拦截；`BaseEntity` 的 `deleted` 字段标配 `@JsonIgnore`。
4. **日志与脱敏**：Web 请求统一经由 `TraceFilter` 注入 MDC `traceId`；统一使用受管 SLF4J 日志，严禁在正式代码中使用 `System.out.println` 或裸 `e.printStackTrace()`；全局异常处理对未知根异常强制生产脱敏（参见 [ADR-0004](./docs/adr/0004-http-200-with-business-code-and-exception-masking.md)）。
5. **部署隔离与 Central 解耦**：非分发模块标配声明 `<maven.deploy.skip>true</maven.deploy.skip>`，且根发布插件协同配置 `<excludeArtifacts>` 排除；发布插件使用 `<waitUntil>validated</waitUntil>` 极速异步解耦（参见 [ADR-0007](./docs/adr/0007-maven-publishing-and-clean-ci-pipeline.md)）。
6. **文档自驱与单一版本流**：文档接入依赖坚持展示纯净具体版本（如 `0.0.4`）供开发者一键复制即用；发版由 `scripts/release.sh` 内置原生 `sed -i` 原地直接流式修改同步更新文档与 POM，采用单一正式版本流并通过 CI 智能网关识别发版提交短路放行，杜绝冗余构建（参见 [ADR-0008](./docs/adr/0008-automated-release-changelog-and-evergreen-docs.md)）。
7. **统一注释规范**：新建源码头部标注 `@author Ateng` 与 `@since YYYY-MM-DD`，首段直接陈述业务或技术职责。

---

## 6. 分支模型与贡献流程 (Branching & Workflow)

本项目采用经典的 **`main` + `dev` 双分支演进模型**（参见 [ADR-0009](./docs/adr/0009-dual-branch-workflow-main-release-and-dev-active.md)）：

1. **开发主场**：日常功能扩展、新 Starter 引入与 Bug 修复统一在 `dev` 分支开展，严禁直接向 `main` 提交业务代码；
2. **测试全覆盖**：所有新增特性或修复均需在对应子模块编写单元测试，并在 `ateng-boot-samples` 落地集成验证；以 `mvn clean test` 100% 绿色通过为准入准出标准；
3. **CI 持续守护**：推送至 `origin/dev` 后，GitHub Actions 会自动触发跨 Java 17 & 21 LTS 矩阵的自动化编译与测试，实时把关代码质量；
4. **阶段合并与发版**：阶段性功能积累完备后，将 `dev` 合并至 `main` 主干，并严格在 `main` 执行 `bash scripts/release.sh` 完成全自动发版与 Central 分发。
