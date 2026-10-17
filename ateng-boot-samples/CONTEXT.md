# Samples Context

`ateng-boot-samples` 是框架内部的真实自验工程与工程化最佳实践 Demo 矩阵，采用二级 Parent POM 聚合解耦架构，以最小外部依赖（H2 内存库 / Mock）完成端到端自动化测试验证与高内聚场景示范。

## Language

**SampleAggregator**:
二级 Parent POM 聚合工程（`<packaging>pom</packaging>`），统一纳管各场景自验子模块生命周期、通用测试依赖与 Central 发布排除配置。
_Avoid_: FatSampleModule, MonolithicSample

**SampleScenarioModule**:
专一场景自验子模块（如 `ateng-boot-sample-basic`、`ateng-boot-sample-redis`、`ateng-boot-sample-excel`、`ateng-boot-sample-satoken`），100% 自包含并恪守零共享代码（Zero-Shared-Code）不变量。
_Avoid_: SharedCommonSample, CrossSampleCoupling

**SampleSmokeTest**:
CI 全家桶冒烟准出门禁子模块（`ateng-boot-sample-smoke-test`），聚合依赖 `ateng-boot-all`，秒级完成全 Starter 无冲突自动装配与核心端到端主链路自验。
_Avoid_: SlowFullIntegration, FlakyTest

**VerificationSuite**:
基于 SpringBootTest、MockMvc 与轻量内存模式（H2 / Mock）构建的全自动化内测验证套件，作为版本交付与发布的准出门禁。
_Avoid_: DemoApp, QuickstartOnly, ExternalServerDependentTest

**NonPublishableModule**:
仅用于本地验证与 CI 质量门禁、严禁向公共中央仓库部署发布的演示与自验模块。通过二级 POM 标配 `<maven.deploy.skip>true</maven.deploy.skip>` 与根插件排除守卫实现生命周期与 Central 封包的双重隔离（参见 [ADR-0007](../docs/adr/0007-maven-publishing-and-clean-ci-pipeline.md)、[ADR-0028](../docs/adr/0028-samples-modularization-and-incremental-integration-testing.md)）。
_Avoid_: PublicArtifact, StagedModule, DistributableJar
