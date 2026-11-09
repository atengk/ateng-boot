# ateng-boot 研发指南与 Agent 行为指引

## 1. 项目定位与技术基线
`ateng-boot` 是面向生产就绪的 Spring Boot 3 工业级基础设施与中间件 Starter 套件，坚持纯原生、零臃肿依赖与显式强类型契约设计。

- **运行与编译**：JDK 17 LTS（字节码 17，兼容 JDK 21+ 虚拟线程运行时）
- **核心基线**：Spring Boot `3.3.13`，MyBatis-Plus `3.5.17`（ADR-0006；全量索引见 [`docs/adr/README.md`](./docs/adr/README.md)）
- **命名坐标**：GroupId `io.github.atengk`，基础包名 `io.github.atengk.boot.*`，配置前缀 `ateng.boot.*`

---

## 2. 核心架构与编码契约
- **显式响应契约**：Controller 统一显式返回强类型 `Result<T>`；严禁引入 `@ResponseBodyAdvice` 全局自动包装（ADR-0002）。
- **零臃肿与底座优先**：100% 依托 JDK 17 与 Spring 6 原生能力，严禁引入 `hutool-all`、`guava`、`commons-collections` 等重型工具包；工具类就近内聚于归属模块（ADR-0011）；跨模块原语（`UserContextHolder`、`TenantContextHolder`、`JsonUtil`、`DesensitizeUtil`、`RetryUtil`、`BatchSliceUtil`、`IdUtil`、`FileUtil`）强制收敛于 `ateng-boot-core` 并向下游复用（ADR-0021、ADR-0025）。
- **分层双防校验（ADR-0027）**：第一道防线（Controller）使用 JSR-303 注解 Fail-Fast，批量列表强制使用 `ValidList<E>`，分组校验使用 `ValidationGroups`；第二道防线（Service/非 Web 消费）使用 `BusinessAssert` 业务断言与 `ValidatorUtil.validate(obj)` 编程式校验。
- **数据安全与“内存真明文”不变量**：业务层与实体在内存中始终保持 100% 原始明文（严禁在业务逻辑中直接对属性打星号）；存储加密收敛于持久层（`@FieldEncrypt` 入库密文、出库明文），展示脱敏收敛于 Jackson（`@Sensitive`）与导出拦截器（`@ExcelSensitive`，ADR-0012、ADR-0023）。
- **传输契约与持久层绝对解耦（Zero-Entity-Leakage）**：Controller 边界 **100% 严禁接收或返回 ORM 实体（Entity）**；入参统一使用 Request/DTO/Command，出参使用 VO/Response（分页入参 `PageQuery`，返回 `PageResult<VO>`，ADR-0010）。
- **强类型通用枚举契约**：业务枚举统一实现 `IBaseEnum<T>`，配合 `EnumUtil`、`@InEnum` 校验、`@EnumAlias` Web 字典暴露与 Jackson 强类型反序列化（ADR-0060）。
- **无状态安全与设备治理**：公共单例与 Sa-Token 会话保持无状态；跨微服务标配 Same-Token 请求头防内网穿透；多端多账号登录经由 `LoginHelper` 治理（ADR-0024、ADR-0059）。
- **全链路追踪、四维上下文与虚拟线程防 Pinning**：Web 请求经 `TraceFilter` 注入无锁 MDC `traceId`（`IdUtil.fastSimpleUUID`）；异步任务通过 Core 受管线程池、`AsyncContext` 与 `ContextCopyingTaskDecorator` 透传 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 四维快照并在 `finally` 清理（ADR-0004、ADR-0026）；**涉及 I/O 或耗时临界区的同步统一使用 `ReentrantLock` 替代 `synchronized`，杜绝 JDK 21+ 虚拟线程 Carrier Pinning（ADR-0029）**。
- **实体继承阶梯链**：持久层实体按需继承 `BaseGenericEntity` -> `BaseIdEntity` -> `BaseTimeEntity` -> `BaseEntity` -> `BaseFullEntity`，`BaseFullEntity.deleted` 标配 `@JsonIgnore`。
- **防御性自动装配四铁律**：① 统配 `@AutoConfiguration` 并唯一注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（严禁 `spring.factories`）；② 标配 `@ConditionalOnClass`/`@ConditionalOnProperty` 守卫，缺配置平滑待命；③ 公共 `@Bean` 标配 `@ConditionalOnMissingBean`；④ 显式标注 `@AutoConfigureAfter`/`@AutoConfigureBefore`；Jackson 定制统一注入 `Jackson2ObjectMapperBuilderCustomizer`，严禁覆盖 `ObjectMapper`（ADR-0005）。
- **BOM 统管与向后兼容**：依赖版本唯一在根 `pom.xml` 或 `ateng-boot-bom/pom.xml` 的 `<dependencyManagement>` 纳管，子模块 `pom.xml` 免配 `<version>`（ADR-0003）；公共 API 与注解严守向后兼容。
- **空安全与强类型配置**：查询集合无匹配保底返回空集合（严禁 `null`），单对象查询返回 `null` 时调用方前置判空；配置属性定义于 `io.github.atengk.boot.*.properties`，标配 `@ConfigurationProperties(prefix = "ateng.boot.*")` 与 JavaDoc；**时间/超时/TTL 强制使用 `java.time.Duration`，容量强制使用 `org.springframework.util.unit.DataSize`**，嵌套对象标配 `@NestedConfigurationProperty`；新建类头标配 `@author Ateng` 与真实日期 `@since YYYY-MM-DD`。

---

## 3. 工业级代码成熟度九道防线与审查红线
以下九道防线与传输实体隔离（Zero-Entity-Leakage）为 `/code-review` **一票否决红牌阻断项（Red Card Block）**：

1. **对称语义完备性**：抽象门面（`*Template`/`*Executor`/`*Manager`）必须具备完备对称语义（读写、增删、加解密、注册注销、申请释放对称），严禁半残门面。
2. **分层防卫与快速失败**：传输边界 JSR-303 校验；核心内部方法与驱动交互前必须通过前置卫语句（判空、判负、超限、非法路径与非法状态）Fail-Fast。
3. **确定性资源清理**：流、网络连接、临时文件、分片会话、ThreadLocal/MDC 等瞬态资源，必须在 `try-with-resources` 或 `finally` 中释放；异常中止须执行补偿清理。
4. **环境宿主中立**：严禁硬编码系统路径分隔符，必须使用标准路径抽象与 `FileUtil` 清洗，过滤 `desktop.ini`、`.DS_Store` 等隐性文件及路径穿越字符。
5. **严格无状态与受管并发**：单例严禁使用非线程安全容器或可变实例字段；**严禁私自使用 `Executors` 或 `new Thread()` 创建野线程**，并发必须接入 Core 受管线程池；锁释放必须遵循“当前线程持有状态判断后在 `finally` 释放”范式。
6. **生产就绪配置与防悬挂**：配置提供高并发合理默认值；网络通信、连接池、重试与队列等待必须显式配置超时（`Duration`）与重试上限，严禁无界挂起。
7. **因果异常与驱动解耦**：严禁吞咽异常或 Log-and-Throw；子模块边界拦截底层 SDK 异常并转译为 `BusinessException(IResultCode, message, cause)`，**100% 保留原始 `cause` 链**。
8. **原生优先与底座收敛**：坚持 JDK 17 与 Spring 6 原生算力优先，跨模块通用原语强制复用 `ateng-boot-core`。
9. **对抗性极限单测**：除 Happy Path 外，单测必须全真覆盖空入参、边界溢出、异常分支、失败回滚清理、并发竞争与配置开闭场景。

---

## 4. 质量门禁、双模测试与文档/样例闭环
- **三阶梯测试准出**（纯文档 `*.md` 修改豁免执行 Maven 测试）：
  1. **阶梯一（日常自验）**：`mvn test -pl <changed-module> -am -T 1C -nsu -B`
  2. **阶梯二（提交前准出）**：`mvn test -pl <changed-module>,ateng-boot-samples/ateng-boot-sample-<module> -am -T 1C -nsu -B`（底层核心改动追加 `ateng-boot-sample-smoke-test`，ADR-0028）
  3. **阶梯三（推送后 CI 守护）**：推送后通过 `gh run watch <run-id>` 监控 Linux/Windows 及 JDK 17 & 21 全矩阵回归至全绿。
- **全自洽单测三铁律**：① 文件 IO 强制使用 JUnit 5 `@TempDir`；② 修改 `UserContextHolder`、`TenantContextHolder`、MDC 等上下文必须在 `@AfterEach` 清理；③ 默认模式依托 H2、嵌入式服务或内存桩 100% 离线自洽。
- **远程服务双模联调**：① Sample 复用单套测试断言，离线桩 `@TestConfiguration` 标注 `@Profile("!local")`（CI 离线自洽，激活 `local` 直连真实服务）；② `.gitignore` 忽略 `**/application-local*.yml`，Sample 标配大写占位符模板 `application-local.example.yml` 与 `spring.config.import: "optional:classpath:application-local.yml"`；③ 本地已配 `application-local.yml` 时须在 `local` 模式测通并在 `@AfterEach` 清理数据（无环境须在验收清单标记 `[豁免：无可用环境]`）。
- **Sample 1:1 正交与 Sample-First 五维文档铁律**（完整规范与 **12 项准出自检清单** 详见 **[`docs/agents/documentation-specification.md`](./docs/agents/documentation-specification.md)**）：
  - **1:1 沙箱与非分发隔离**：每个 Starter `ateng-boot-<module>` 严格对应 `ateng-boot-samples/ateng-boot-sample-<module>`（`ateng-boot-all` 对应 `ateng-boot-sample-smoke-test`，全量拓扑见根 `pom.xml` 与 [`CONTEXT-MAP.md`](./CONTEXT-MAP.md)）；Sample 间严格零共享代码（Zero-Shared-Code），标配 `<maven.deploy.skip>true</maven.deploy.skip>` 与 `-DskipSamples` 隔离（ADR-0058）；
  - **多 Controller 与 Sample-First 五维 `README.md`**：Sample 统一引入 `ateng-boot-web` 并按领域拆分多个 `@RestController`（路由 `/sample/<module>/<feature>/<action>`，返回 `Result<T>`），Sample `README.md` 采用四段式体验卡；必须先完成真实业务语义 Sample 实测全绿，再 1:1 逆向提取至 Starter 五维 `README.md`（100% 全景速查矩阵、三要素就近闭环、能力大章收敛、排版防裂）。
- **多上下文领域映射与 ADR 防膨胀**：根目录唯一维护 [`CONTEXT-MAP.md`](./CONTEXT-MAP.md)（严禁建根 `CONTEXT.md`），子模块维护纯词汇表 `ateng-boot-*/CONTEXT.md`；仅在孵化新模块或重大协议重构时新建 ADR，存量优化优先在既有 ADR 增量修订。

---

## 5. 工程协同与 Git 流水线
- **双分支与显式两级授权**：`main` 只读发布，`dev` 活跃开发（若在 `main` 须主动 `git checkout dev`，ADR-0009）；**未收到用户明确的“提交/commit”或“推送/push”指令前，修改默认停留在工作区，严禁擅自提交或推送**；暂存必须显式指定文件相对路径（严禁 `git add .` 或 `-A`）；提交统一执行：`bash scripts/commit.sh -t <type> -s <scope> -m "<主说明>" [-l "<细项>"] [-c <issue>] [-n] [-p] -y`。
- **推送后 CI 守护与发版**：推送后必须通过 `gh run list -L 1` 与 `gh run watch <run-id>` 跟踪 CI 至 100% 全绿；发版只能在干净同步的 `main` 分支通过 `bash scripts/release.sh` 发起。

---

## 6. Agent 执行状态机与专职规约路由表
- **双模执行状态机与停止判据**：① **阶段 1~3（认领/探查 → TDD 与 Sample-First 实现 → 阶梯二测试、12 项自检与 `/code-review` 双绿）**：若用户未明确授权自动提交推送，完成阶段 3 后**必须停留在工作区汇报结果**；② **阶段 4~5（`scripts/commit.sh` 提交推送 → `gh run watch` 守护全绿 → `gh issue comment -F` 回写关单）**：仅在收到用户显式指令时触发。
- **专职规约前置唤醒路由表 (Mandatory Context Pointers)**：触及以下任一分支时，**必须在操作前主动调用 `read_file` 读取对应规约**：

| 触发分支 / 任务场景 (When) | 必读权威规约文档 (Read Before Acting) | 核心完成判据 (Completion Criteria) |
| :--- | :--- | :--- |
| **编写/修改任何 `README.md` 或 Sample 工程** | **[`docs/agents/documentation-specification.md`](./docs/agents/documentation-specification.md)** | 多 Controller 全绿、1:1 逆向提取、全景矩阵 100% 覆盖、通过 **12 项准出自检清单** |
| **从 0 到 1 孵化全新 Starter 模块** | **[`docs/agents/modular-extensibility.md`](./docs/agents/modular-extensibility.md)** | 交付“工业级六件套”（标杆对标、BOM 统管、Core 复用、1:1 双模 Sample、五维文档、Domain/ADR） |
| **对存量模块执行 1 到 N 体检、扩容或重构** | **[`docs/agents/module-hardening-and-expansion.md`](./docs/agents/module-hardening-and-expansion.md)** | 执行四阶段流水线，阶段一输出 `module-health-check.md` 后**必须停下等待人工审批** |
| **探索模块边界、命名领域概念或查阅/新增 ADR** | **[`docs/agents/domain.md`](./docs/agents/domain.md)** & **[`CONTEXT-MAP.md`](./CONTEXT-MAP.md)** | 对齐子模块 `CONTEXT.md` 术语与 `_Avoid_` 词表，同步维护 `CONTEXT-MAP.md` 与 [`docs/adr/README.md`](./docs/adr/README.md) |
| **操作 GitHub Issue / PR 或执行 `gh` 命令** | **[`docs/agents/issue-tracker.md`](./docs/agents/issue-tracker.md)** | PowerShell `@me` 双引号包裹、长文本强制 `-F <file>` 回写、遵守五阶段状态机与 Git 授权边界 |
| **评估、分诊或修改 Issue 状态标签** | **[`docs/agents/triage-labels.md`](./docs/agents/triage-labels.md)** | 准确映射 `needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix` |
| **执行版本发布或排查发版/Central 流水线** | **[`docs/agents/release-workflow.md`](./docs/agents/release-workflow.md)** | 确认 `main` 分支纯净同步，使用 `bash scripts/release.sh` 与三重非分发模块隔离防线 |
