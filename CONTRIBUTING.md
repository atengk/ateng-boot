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

- **主干分支 (`main`)**：受保护分支，所有生产发布均源自主干，仅接受通过 CI 门禁检查的 Pull Request 合并；
- **特性分支 (`feat/*`)**：基于 `main` 拉出，用于新功能研发；
- **修复分支 (`fix/*`)**：基于 `main` 拉出，用于 Bug 修复。

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

为避免人工拼写类型错误或格式遗漏，**强烈推荐使用项目内置的提交助手脚本**：

```bash
# 交互向导模式 (纯中文提示选择类型、输入 scope 与描述，并可自动暂存与推送)
bash scripts/commit.sh

# 非交互式参数模式 (适合熟练开发者或自动化工具链)
bash scripts/commit.sh -t feat -s web -m "增强 TraceFilter 支持可配置排除静态资源路径" -p -y
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
建议标注具体的子模块名，例如：`core`、`web`、`mybatis-plus`、`all`、`samples`、`bom`。

---

## 5. 核心代码规范红线

在编写或修改源码时，请严格遵循以下准则：

1. **显式契约 (Explicit Contract)**：Controller 统一显式声明返回 `Result<T>`；禁止引入 `@ResponseBodyAdvice` 自动拦截包装（参见 [ADR-0002](./docs/adr/0002-explicit-result-contract-no-response-body-advice.md)）。
2. **防御性装配 (Defensive Autoconfiguration)**：自动装配标配 `@ConditionalOnClass` / `@ConditionalOnProperty` 守卫；Jackson 定制统一通过 `Jackson2ObjectMapperBuilderCustomizer` 注入，严禁覆盖 `ObjectMapper` Bean（参见 [ADR-0005](./docs/adr/0005-jackson-customizer-over-objectmapper-override.md)）。
3. **空安全与防御断言**：列表/集合查询无匹配时统一返回空集合，严禁返回 `null`；单实体查询无结果时通过 `BusinessAssert.notNull` 前置拦截；`BaseEntity` 的 `deleted` 字段标配 `@JsonIgnore`。
4. **日志与脱敏**：Web 请求统一经由 `TraceFilter` 注入 MDC `traceId`；统一使用受管 SLF4J 日志，严禁在正式代码中使用 `System.out.println` 或裸 `e.printStackTrace()`；全局异常处理对未知根异常强制生产脱敏。
5. **统一注释规范**：新建源码头部标注 `@author Ateng` 与 `@since YYYY-MM-DD`，首段直接陈述业务或技术职责。

---

## 6. Pull Request 流程

1. **关联 Issue**：提交 PR 前请确保存在对应追踪的 GitHub Issue，并在 PR 正文中标注（如 `Closes #10`）。
2. **测试全覆盖**：所有新增特性或 Bug 修复均需在对应子模块编写单元测试，并在 `ateng-boot-samples` 落地集成验证；以 `mvn clean test` 100% 绿色通过为准入准出标准。
3. **CI 终审绿灯**：提交 PR 后，GitHub Actions 会自动触发跨 Java 17 & 21 LTS 矩阵的自动化编译与测试。确保所有检查项通过。
4. **审查合并**：维护团队完成 Code Review 后，通常采用 **Squash and merge** 合入主干。
