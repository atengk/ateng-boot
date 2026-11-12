# Samples Context

`ateng-boot-samples` 是框架内部的真实自验工程与工程化最佳实践沙箱矩阵，采用二级 Parent POM 聚合解耦架构，与各业务 Starter 严格 1:1 映射，通过离线内存桩与本地真实中间件双模自适应架构完成端到端自动化验证与高内聚 Web 交互体验。

## Language

**SampleAggregator**:
二级 Parent POM 聚合工程（`<packaging>pom</packaging>`），统一纳管各场景自验子模块生命周期、通用测试依赖与 Maven Central 发布排除配置。
_Avoid_: FatSampleModule, MonolithicSample

**SampleScenarioModule**:
专一场景自验子模块（命名严格遵循 `ateng-boot-sample-<module>`，与根工程下每个业务 Starter `ateng-boot-<module>` 1:1 精确对齐，全量清单见根 `pom.xml` 与 `CONTEXT-MAP.md`），100% 自包含并恪守跨样例零共享代码（Zero-Shared-Code）不变量。
_Avoid_: SharedCommonSample, CrossSampleCoupling, SampleBasicMixed

**MultiControllerExperience**:
多控制器领域交互体验端。每个 Sample 工程统一引入 `ateng-boot-web`，将模块能力按业务领域拆分为多个独立的 `@RestController`（路由遵循 `/sample/<module>/<feature>/<action>`，出参统一返回强类型 `Result<T>`），并配套四段式极简体验卡 `README.md`，支持启动即通过 HTTP/cURL 交互验证。
_Avoid_: FatSingleController, HeadlessOnlySample, BloatedSampleReadme

**SampleCoverageInvariant**:
样例场景 100% 覆盖不变量。业务模块实现的每一个对外公开功能（包括所有公开门面及方法、核心注解 `@*` 及属性、配置属性类 `@ConfigurationProperties` 的配置项、扩展 SPI），必须 100% 在归属的 `ateng-boot-sample-<module>` 中提供真实、自洽、端到端可运行的业务场景用例。
_Avoid_: PartialSampleCoverage, ToyStubScenario, UntestedFeatureInSample

**SampleFirstPipeline**:
样例先行逆向研发流水线不变量。在编写或更新子模块 `README.md` 前，必须先完成对应 Sample 场景组件、多 Controller 接口与端到端测试的编写并验证全绿；Starter 文档中的 Java 代码、YAML 配置与输出结果必须 100% 逆向提取自已跑通的 Sample 工程。
_Avoid_: DocBeforeSample, HandcraftedCodeInDoc, UntestedDocSnippet

**DualModeVerificationSuite**:
双模自适应单测试套件架构。同一套 `@SpringBootTest` / `MockMvc` 业务断言同时支撑两种模式：默认/CI 模式（未激活 `local` Profile）自动加载 `@Profile("!local")` 离线内存桩/H2/嵌入式服务，保证 100% 离线自洽；Local 真实模式（激活 `local` Profile 并读取 Git 忽略的 `application-local.yml`，基准模板为 `application-local.example.yml`）直连真实物理中间件并在 `@AfterEach` 清理现场。
_Avoid_: DuplicateTestSuites, HardcodedExternalDependency, CommittedLocalSecrets

**SampleSmokeTest**:
`ateng-boot-all` 的 1:1 配套自验工程兼全域冒烟准出门禁子模块（`ateng-boot-sample-smoke-test`），基于 `ateng-boot-all` 与典型按需 Starter（`ateng-boot-openapi`、`storage`、`http`、`realtime`、`encrypt`、`statemachine`、`tcp`）共存装配，按领域拆分 3 个 `@RestController`（统一 `/sample/smoke/...` 路由与 DTO/VO 契约），在离线 H2 + Redis 双模下秒级验证无冲突启动与跨模块协同主链路。
_Avoid_: SlowFullIntegration, FlakyTest, SingleFatSmokeController, LegacyKnife4jSmoke

**NonPublishableModule**:
仅用于本地验证与 CI 质量门禁、严禁向公共中央仓库部署发布的演示与自验模块。通过根 POM `-DskipSamples` Profile 隔离、二级 POM `<maven.deploy.skip>true</maven.deploy.skip>` 声明与根发布插件 `<excludeArtifacts>` 构成三重防发布防线（参见 [ADR-0007](../docs/adr/0007-maven-publishing-and-clean-ci-pipeline.md)、[ADR-0028](../docs/adr/0028-samples-modularization-and-incremental-integration-testing.md)、[ADR-0058](../docs/adr/0058-samples-reactor-isolation-and-publishing-quota-governance.md)）。
_Avoid_: PublicArtifact, StagedModule, DistributableJar
