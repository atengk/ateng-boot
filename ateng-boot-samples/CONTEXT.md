# Samples Context

`ateng-boot-samples` 是框架内部的真实自验工程与工程化最佳实践 Demo 矩阵，采用二级 Parent POM 聚合解耦架构，与各业务 Starter 严格 1:1 映射，以最小外部依赖（H2 内存库 / Mock / 纯内存模式）完成端到端自动化测试验证与高内聚场景示范。

## Language

**SampleAggregator**:
二级 Parent POM 聚合工程（`<packaging>pom</packaging>`），统一纳管各场景自验子模块生命周期、通用测试依赖与 Central 发布排除配置。
_Avoid_: FatSampleModule, MonolithicSample

**SampleScenarioModule**:
专一场景自验子模块（`ateng-boot-sample-core`、`ateng-boot-sample-web`、`ateng-boot-sample-mybatis-plus`、`ateng-boot-sample-redis`、`ateng-boot-sample-excel`、`ateng-boot-sample-satoken`、`ateng-boot-sample-storage`），与各 Starter 严格 1:1 映射，100% 自包含并恪守零共享代码（Zero-Shared-Code）不变量。
_Avoid_: SharedCommonSample, CrossSampleCoupling, SampleBasicMixed

**SampleCoverageInvariant**:
样例场景 100% 覆盖不变量。业务模块实现的每一个对外公开功能（包括所有公开门面及方法、核心注解 `@*` 及属性、配置属性类 `@ConfigurationProperties` 的配置项、扩展 SPI），必须 100% 在归属的 `ateng-boot-sample-<scenario>` 中提供真实、自洽、端到端可运行的演示用例。
_Avoid_: PartialSampleCoverage, MockOnlyCoverage, UntestedFeatureInSample

**SampleFirstPipeline**:
样例先行研发流水线不变量。在编写或更新子模块 `README.md` 前，必须先完成对应 Sample 场景用例的编写与实测验证全绿；文档中的 Java 代码、YAML 配置与输出结果必须 100% 逆向提取自跑通的 Sample 工程。
_Avoid_: DocBeforeSample, HandcraftedCodeInDoc, UntestedDocSnippet

**SampleSmokeTest**:
CI 全家桶冒烟准出门禁子模块（`ateng-boot-sample-smoke-test`），聚合依赖 `ateng-boot-all`，秒级完成全 Starter 无冲突自动装配与核心端到端主链路自验。
_Avoid_: SlowFullIntegration, FlakyTest

**VerificationSuite**:
基于 SpringBootTest、MockMvc 与轻量内存模式（H2 / Mock）构建的全自动化内测验证套件，作为版本交付与发布的准出门禁。
_Avoid_: DemoApp, QuickstartOnly, ExternalServerDependentTest

**NonPublishableModule**:
仅用于本地验证与 CI 质量门禁、严禁向公共中央仓库部署发布的演示与自验模块。通过二级 POM 标配 `<maven.deploy.skip>true</maven.deploy.skip>` 与根插件排除守卫实现生命周期与 Central 封包的双重隔离（参见 [ADR-0007](../docs/adr/0007-maven-publishing-and-clean-ci-pipeline.md)、[ADR-0028](../docs/adr/0028-samples-modularization-and-incremental-integration-testing.md)）。
_Avoid_: PublicArtifact, StagedModule, DistributableJar
