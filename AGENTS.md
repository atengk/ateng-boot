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
- **传输契约与持久层绝对解耦 (Transport Decoupling & Zero-Entity-Leakage)**：
  - Controller 传输边界 **100% 严禁接收或直接返回 ORM 实体（Entity）**；
  - 入参统一使用专用 Request / DTO / Command（挂载 JSR-303 格式校验），出参统一使用专用 VO / Response（分页统一入参 `PageQuery`，统一返回 `PageResult<VO>`）；
  - 实体与传输对象间严禁直接泄露未授权持久化字段或引发序列化漏洞；解耦转换由开发者根据具体场景选用合适的映射方式，并在 Service / 接入边界完成纯净流式解耦（参见 [ADR-0010](./docs/adr/0010-transport-pagination-contract-decoupling.md)）。
- **无状态安全与设备治理 (Stateless Security & Device Governance)**：
  - 公共单例服务与 Sa-Token 会话强制保持无状态，严禁使用非线程安全类或成员变量共享可变业务状态；
  - 跨微服务内网调用标配 Same-Token 请求头校验以实现内网穿透防护；多端多设备登录统一推行独立账号与设备治理（参见 [ADR-0024](./docs/adr/0024-enterprise-satoken-authentication-and-security-architecture.md)）。
- **全链路追踪与四维上下文透传 (Tracing & 4D Context Propagation)**：
  - Web 请求统一经由 `TraceFilter` 注入 MDC `traceId`（基于无锁 `fastSimpleUUID`）；
  - 异步任务通过受管线程池、`AsyncContext` 与 `ContextCopyingTaskDecorator` 实现 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 四维快照的全链路透传并在任务完成后严格清理现场（参见 [ADR-0004](./docs/adr/0004-http-200-with-business-code-and-exception-masking.md)、[ADR-0026](./docs/adr/0026-production-hardening-and-foundations-unification.md)）。
- **实体继承阶梯链 (Entity Staircase)**：实体按需选用五级阶梯链（`BaseGenericEntity` -> `BaseIdEntity` -> `BaseTimeEntity` -> `BaseEntity` -> `BaseFullEntity`），`BaseFullEntity` 的 `deleted` 字段标配 `@JsonIgnore`。
- **防御性自动装配四道铁律 (Defensive Autoconfiguration Invariants)**：
  - **标准规范注册**：自动装配类统配 `@AutoConfiguration` 标注，统一且只能注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（严格遵循 Spring Boot 3 规范，严禁使用旧版 `spring.factories`）；
  - **缺配置平滑探测**：装配类标配 `@ConditionalOnClass` 与 `@ConditionalOnProperty` 守卫，必要时结合探测机制，严禁在未配置关键外部连接信息时盲目初始化导致启动崩溃；
  - **`@Bean` 级别缺省守卫**：每个对外暴露的公共 `@Bean` 方法必须显式标配 `@ConditionalOnMissingBean`，保障下游开发者声明同名或同类型 Bean 时能够平滑无缝接管；
  - **装配时序显式声明**：存在隐式依赖关系的自动装配类，必须显式标注 `@AutoConfigureAfter` 或 `@AutoConfigureBefore`，防御 Bean 初始化时序错乱；
  - Jackson 定制统一注入 `Jackson2ObjectMapperBuilderCustomizer`，严禁覆盖 `ObjectMapper` Bean（参见 [ADR-0005](./docs/adr/0005-jackson-customizer-over-objectmapper-override.md)）。
- **BOM 依赖统管与向后兼容 (BOM-First & Backward Compatibility)**：
  - 依赖及其版本必须且只能在根 `pom.xml` 或 `ateng-boot-bom/pom.xml` 的 `<dependencyManagement>` 集中纳管；各子模块 `pom.xml` 声明依赖时一律免配 `<version>` 标签（参见 [ADR-0003](./docs/adr/0003-dependency-management-via-bom-import.md)）；
  - 对外暴露的公共 API 签名、核心注解属性与依赖基线严守向后兼容。
- **空安全与保底返回 (Null Safety & Defensive Return)**：
  - 查询列表/集合无匹配时保底返回空集合（如 `Collections.emptyList()`），严禁返回 `null`；
  - 单实体查询无结果时底层允许返回 `null`，但调用方必须立即通过前置卫语句显式拦截防御。
- **强类型配置属性与元数据 (IDE-Friendly Properties & Strong Typing)**：
  - 配置属性类统一定义于 `io.github.atengk.boot.*.properties`，标配 `@ConfigurationProperties(prefix = "ateng.boot.*")`，字段提供完整 JavaDoc 说明与生产合理默认值，由 `spring-boot-configuration-processor` 自动编译生成 IDE 智能补全元数据；
  - **强类型时间与容量规范**：凡涉及超时、间隔与缓存 TTL 必须统一声明为 `java.time.Duration`；凡涉及文件、内存与缓冲容量必须统一声明为 `org.springframework.util.unit.DataSize`，彻底取缔无量纲裸 `Long`/`Integer`，消除单位歧义；
  - **嵌套配置显式注解**：复杂嵌套配置对象必须标配 `@NestedConfigurationProperty`，确保 IDE 补全元数据与属性层级绑定 100% 生效；
  - 新建源码与核心 API 标配清晰业务注释，类头部标注 `@author Ateng` 与 `@since YYYY-MM-DD`。

---

## 3. 工业级代码成熟度九道防线与审查红线 (The 9 Robustness Invariants & Review Redlines)
立足于框架全局抽象与面向未来无限扩展新模块的工业级基准，确立以下九道不可逾越的通用代码成熟度防线。任何一条违规直接触发代码审查红牌阻断（One-Vote Red Card Block）：

1. **对称语义完备性 (Symmetric Facades Invariant)**：
   - 任何中间件底座或抽象执行门面（`*Template` / `*Executor` / `*Manager` 等），操作语义必须具备完备性与对称性（读写对称、增删对称、加解密对称、注册与注销对称、出入栈对称）；
   - 严禁设计“只管查不管改”或“只管分配不管回收”的半残门面，杜绝调用方被迫穿透底层 SPI 自行拼装缺失基础方法的低成熟度代码。
2. **分层防卫与快速失败 (Defense-in-Depth & Fail-Fast Invariant)**：
   - 传输层外围边界强制使用 JSR-303 注解实施无状态格式校验与 Fail-Fast；
   - 核心内部业务逻辑与驱动交互前，必须通过显式前置卫语句（断言非空、非负、边界超限、非法路径与非法状态）实施严密防卫，严禁放任脏数据与边界异常穿透至深层链路。
3. **确定性生命周期与资源清理 (Deterministic Lifecycle & Cleanup Invariant)**：
   - 凡涉及流（InputStream/OutputStream）、网络与数据库连接、临时文件句柄、Multipart 分片会话、ThreadLocal 上下文等瞬态物理资源，必须且只能在 `try-with-resources` 或 `finally` 块中完成绝对可靠的释放与清理；
   - 当遇到异常中止或取消时，必须执行确定性的补偿与清理逻辑，坚决杜绝句柄泄露、未闭合会话悬挂与磁盘临时膨胀。
4. **环境宿主中立与弹性防御 (Platform-Agnostic & Host Resilience Invariant)**：
   - 严禁硬编码操作系统相关的特定路径分隔符（如写死 `\` 或 `/`），必须使用标准路径抽象或安全清洗原语；
   - 主动防御并过滤不同宿主操作系统的隐性系统文件（如 Windows 的 `desktop.ini`、macOS 的 `.DS_Store`）及特殊路径字符，确保多端运行的一致性与弹性。
5. **严格无状态与并发纪律 (Strict Statelessness & Concurrency Safety Invariant)**：
   - 单例服务（`@Service`、`*Manager`、`*Template`）必须保持严格无状态，严禁使用非线程安全容器（如普通 `HashMap`、`ArrayList`、`SimpleDateFormat`）或实例成员变量共享可变业务状态；
   - **受管线程池铁律**：严禁私自使用 `Executors` 或 `new Thread()` 创建野线程；多线程并发必须且只能接入 Core 受管异步线程池设施（`AtengAsyncAutoConfiguration` / `ThreadPoolTaskExecutor`），统一挂载上下文装饰器；
   - **防御性锁释放范式**：使用并发锁（本地锁或分布式锁）时，必须严格遵循状态判断释放范式（仅当当前线程真实持有锁时才在 `finally` 触发释放），严禁无脑释放未持有的锁导致产生次生异常掩盖真实业务异常。
6. **生产就绪配置与防无界悬挂 (Safe Config & Production Defaults Invariant)**：
   - 所有配置属性必须提供符合高并发生产标准的合理默认值；
   - 凡涉及网络通信、连接池分配、消息重试与队列等待处，必须显式配置超时阈值（Timeout）与重试上限，严禁无超时无限挂起导致应用线程耗尽。
7. **因果异常体系与底层驱动解耦 (Causal Exceptions & SDK Decoupling Invariant)**：
   - 严禁吞咽异常（禁止空的 `catch` 块或仅打印不处理）；
   - 子模块对外暴露的公共 API 必须在模块边界完成底层驱动异常的受控拦截，将其转译为框架受管的语义化业务异常（`BusinessException`）并赋予清晰的错误码（`IResultCode`）；
   - **链式异常传递铁律**：转译异常时必须完整保留原始异常因果链（必须传递原始 `cause`）；恪守“要么就地降级补偿，要么转换为业务异常向外抛出”，严禁 Log-and-Throw 双重污染。
8. **原生优先与零臃肿底座收敛 (Zero-Bloat & Core Convergence Invariant)**：
   - 坚持纯原生标准库与 Spring 6 原生能力优先，坚决杜绝引入第三方全量工具包；
   - 跨中间件通用原语（上下文透传、序列化门面、敏感脱敏、安全工具、格式校验、重试退避等）强制优先复用 `ateng-boot-core`，严禁在子模块重复造轮子。
9. **对抗性极限用例守护 (Adversarial & Edge-Case Testing Invariant)**：
   - 单元测试严禁仅覆盖 Happy Path 玩具场景；
   - 核心逻辑必须设计对抗性测试用例（Adversarial Testing），全真覆盖空入参、边界溢出、异常分支、失败补偿清理、并发竞争与配置开闭测试用例。

- **审查红线阻断机制 (Review Redlines Gate)**：
  在代码审查（`/code-review`）中，以上九道防线与传输实体隔离（Zero-Entity-Leakage）被列为一票否决红线项。凡发现触碰任一红线，审查 Agent 直接出具 Red Card 阻断本次提交准出。

---

## 4. 质量门禁与全维文档闭环 (Quality Gates & Documentation Invariants)
- **三阶梯测试准出与并行加速 (Three-Tier Test Gate & Parallel Build)**：
  根目录 `.mvn/maven.config` 默认注入 `-T 1C -nsu` 加速配置，自动化环境统一附加 `-B` 确保日志流式纯净；严禁每次改动在本地无脑执行全项目串行单测：
  1. **阶梯一：特性日常自验（秒级极速反馈）**：单个模块改动时，采用多线程依赖树精准定向测试：
     ```bash
     mvn test -pl <changed-module> -am -T 1C -nsu -B
     ```
  2. **阶梯二：本地提交准出验收（精准端到端集成）**：准备提交前，执行变更模块与对应场景样例工程（`ateng-boot-samples/ateng-boot-sample-<scenario>`，如 `basic`/`redis`/`satoken`/`excel`/`storage`，重大底层重构联动 `smoke-test`）定向联动验证，杜绝无脑全量回归（参见 [ADR-0028](./docs/adr/0028-samples-modularization-and-incremental-integration-testing.md)）：
     ```bash
     mvn test -pl <changed-module>,ateng-boot-samples/ateng-boot-sample-<scenario> -am -T 1C -nsu -B
     ```
  3. **阶梯三：全反应堆多 JDK 矩阵终极守护 (Post-Push CI Gate)**：全反应堆所有模块（100% 模块全集）在 Linux/Windows 及 JDK 17 & 21 矩阵下的全量回归，100% 交由 GitHub Actions CI 云端并行工作流异步守护；本地推送后通过 `gh run watch <run-id>` 闭环跟踪至全绿。
- **纯文档修改测试豁免 (Doc-Only Exemption)**：若本次变更仅涉及文档（如 `*.md`、纯文本或注释说明）而未改动任何业务源码、配置文件与工程依赖，一律免除执行本地 Maven 单元测试与场景准出验证，直接受控提交。

- **全自洽单测三项铁律 (The 3 Hermetic Test Rules)**：
  1. **临时资源隔离**：单测涉及文件与磁盘 IO 时，强制使用 JUnit 5 原生 `@TempDir` 注入临时目录，测试结束后由引擎自动物理销毁，严禁在工程或系统磁盘残留临时文件；
  2. **上下文零污染**：任何涉及修改上下文（如 `UserContextHolder`、`TenantContextHolder`、`SecurityContext`、MDC 等）的测试用例，必须在 `@AfterEach` 中无条件执行重置与清理，杜绝跨测试用例的线程脏读；
  3. **100% 离线自洽**：单测必须具备完全离线独立运行能力（纯内存库 H2、嵌入式模式或纯 Mock），绝对严禁依赖外部真实物理网络服务与云环境。

- **五维生产就绪交付模型 (The 5D Production-Ready Invariant)**：
  子模块 `README.md` 面向真实生产接入者，严禁仅停留在简单 Demo 的自嗨罗列；任何新增或修改的功能特性，必须正交覆盖以下五个维度，缺一即阻断准出：
  1. **维度零：特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)**：
     - 必须在子模块 `README.md` 核心显要位置提供标准速查矩阵表格：
       ```markdown
       | 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
       ```
     - **100% 全覆盖刚性铁律**：模块对外暴露的所有公共门面类（`*Template` / `*Executor` / `*Util` / `*Manager` 等）及 Public 方法、声明式核心注解（`@*`）及关键属性、配置属性类（`@ConfigurationProperties`）所有配置项、扩展点接口（SPI / Callback / Listener / Provider），**必须 100% 在全景矩阵中收录**，彻底杜绝任何“代码写了但文档未记录”的隐形死代码；
  2. **维度一：最小起步体验 (Happy Path & Out-of-the-Box)**：直击一句话痛点、最小可用配置与开箱即用的极简代码示例（即原“三板斧”）；
  3. **维度二：生产扩展接管 (Customization & Extensibility)**：当框架默认行为无法满足企业级生产定制时，开发者如何扩展或接管（如 SPI 自定义实现、`@ConditionalOnMissingBean` 覆盖、事件监听器、插件拦截器注入等，杜绝只教开箱不教定制）；
  4. **维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)**：显式声明该模块在极端、高并发或复杂事务场景下的底层行为机制（如持久层事务可见性与会话共享、MQ 消息幂等与重试死信、Redis 锁可重入与续期策略、网络超时熔断、内存防 OOM 等，杜绝非功能黑盒）；
  5. **维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)**：示例代码必跑通并锚定单测、YAML 配置逐字对齐属性类、Markdown 代码块严格闭合无破损，严禁格式事故与伪数据。

- **“先测后文、文必有源”与排版防裂守卫 (Zero-Hallucination & Markdown Integrity)**：
  - **代码源头锚定**：文档中展示的 Java 示例必须且只能直接提取自已跑通的单测类（`*Test.java`）或场景样例工程（`ateng-boot-sample-*`），严禁脱离代码在 Markdown 里凭空手敲；若某功能尚无现成范式，必须先在测试用例中写出并跑通后再提取至文档；
  - **配置源头锚定**：YAML/Properties 配置片段必须 100% 与当前模块的 `@ConfigurationProperties` 字段逐字对齐；
  - **预期呈现源头锚定**：控制台输出、实际 SQL 与响应结果必须直接截取自本地实际执行 `mvn test` 的真实控制台输出，严禁脑补拼接伪数据；
  - **文档排版防裂守卫 (Markdown Integrity Guard)**：
    1. 表格单元格内若涉及泛型类型（如 `Map<String, Object>`）、管道符或位运算（`|`），必须使用行内代码反引号包裹（如 `` `Map<String, Object>` ``）或转义（`\|`），严禁导致表格破损错位；
    2. 嵌套代码块严格遵循反引号阶梯（外四反引号 ```` 嵌套内三反引号 ```）；
    3. 文档互链 100% 使用相对路径，严禁包含本地绝对路径（如 `D:/...`、`file:///`）与死链。

- **子模块与样例工程职责正交 (Submodule & Samples Decoupling)**：
  - 子模块 `README.md` 是该模块唯一权威的**“全功能契约真理源”**，承载完备的全景矩阵与五维交付规范；
  - 场景样例工程（`ateng-boot-samples/ateng-boot-sample-*`）是面向开发者的**“最小可运行沙箱与开箱体验工程”**，其代码专职作为文档代码片段的真实测试源头，两者代码 1:1 锚定；样例工程自身的 README 仅需保持轻量（聚焦启动步骤与体验验证）。

- **重构防漂移与审查拦截 (Anti-Drift & Review Gate)**：修改核心 API 签名、类名或配置项时，必须全工程检索所属子模块 `README.md` 同步修正，杜绝陈旧失效代码；`/code-review` 强制将文档一致性、全景矩阵覆盖率与格式完整度列为红牌必检项。
- **四维职责清晰正交**：`README.md` 面向开发者，聚焦痛点与最小可用代码；`CONTEXT.md` 严格恪守“纯领域词汇表 (Glossary)”定位，只定义术语与 Avoid 负面词汇，严禁堆砌长篇实现细节；`CONTEXT-MAP.md` 专职维护系统拓扑与调用依赖；`docs/adr/` 专职沉淀重大架构选型。
- **ADR 膨胀防线与合并收敛**：严格收紧独立 ADR 门槛，仅在孵化完整新中间件底座或重大协议契约重构时新建独立 ADR；存量模块优化一律优先在既有归属 ADR 增量修订。

---

## 5. 工程协同与 Git 流水线 (Git Workflow & Release)
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

## 6. Agent 协议与工单流转规范 (Agent Protocols & SOP)
- **官方 CLI 与工单生命周期闭环 (CLI & Ticket Lifecycle)**：
  GitHub Issues、PR 审阅与 CI 交互一律通过官方 `gh` 命令行闭环。工单生命周期严格遵循五阶段状态机：
  1. **认领**：查阅工单并指派当前开发者（`gh issue edit <n> --add-assignee "@me"`）；
  2. **TDD 与文档同步实现**：测试驱动编码，全面遵循第 3 节“工业级代码成熟度九道防线”；同步在所属子模块 `README.md` 中按“五维生产就绪交付模型”补齐全景矩阵、开箱代码、扩展定制接入、核心生产边界与实测输出契约；
  3. **准出、自检与双轴审查**：
     - 执行第 4 节阶梯二准出测试（纯文档改动免测）；
     - **《交付前成熟度与文档双向对齐清单 (Audit Checklist)》强制自检卡点**：
       - [ ] **全景速查矩阵**：模块新增/修改的所有公共 API 方法、配置项、核心注解与 SPI 是否 100% 入表？
       - [ ] **代码-文档真理一致性**：文档中展示的代码与 YAML 是否直接截取自已跑通的单测/Sample？方法名与参数签名是否完全对齐？
       - [ ] **九道成熟度防线自检**：是否通过对称门面、边界 Fail-Fast、确定性清理、超时防悬挂、驱动异常转译等全部 9 项检查？
       - [ ] **单测环境隔离**：是否使用 `@TempDir`、在 `@AfterEach` 中清理上下文、100% 离线自洽？
       - [ ] **传输契约隔离**：Controller 传输边界是否 100% 杜绝持久层实体（Entity）泄露？
     - **审查红牌阻断 (Red Card Gate)**：调用 `/code-review` 开展双轴审查；若审查发现文档漏写公开功能、文档代码与源码签名不符，或触碰成熟度红线，一律出具红牌阻断，强制返修直至双绿；
  4. **受控提交**：按第 5 节执行受控提交并推送（代码与文档同工单同提交原子交付）；
  5. **闭环**：守护 CI 监控至全绿，通过文件回写验收清单与提交哈希（`gh issue comment <n> -F <file>`），关闭工单（`gh issue close <n>`）。
- **工单操作与 CLI 避坑 (Issue Tracker & CLI)**：操作 GitHub Issues / PR 或编写 PowerShell 交互命令时，遵循 [docs/agents/issue-tracker.md](./docs/agents/issue-tracker.md)（含特殊字符引号包裹、`-F` 文件回写防截断及 Wayfinding 映射）。
- **分诊标签规范 (Triage Labels)**：分诊或标记 Issue 状态时，遵循 [docs/agents/triage-labels.md](./docs/agents/triage-labels.md)（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。
- **领域词汇与架构决策 (Domain Docs)**：探索代码库、设计新领域实体或查阅架构决策时，阅读全局映射 [CONTEXT-MAP.md](./CONTEXT-MAP.md) 及各模块独立的 `CONTEXT.md`，架构选型遵循 [docs/adr/](./docs/adr/)，规约详见 [docs/agents/domain.md](./docs/agents/domain.md)。
- **模块孵化与扩展通用标准 (Modular Extensibility)**：孵化新模块或创建场景样例工程时，遵循 [docs/agents/modular-extensibility.md](./docs/agents/modular-extensibility.md)（“工业级五件套”与端到端纯内存自验门禁）。
