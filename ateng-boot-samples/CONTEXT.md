# Samples Context

`ateng-boot-samples` 是框架内部的真实自验工程，以最小外部依赖（H2 内存库）完成端到端自动化测试验证与最佳实践示范。

## Language

**VerificationSuite**:
基于 SpringBootTest、MockMvc 与 H2 内存库构建的全自动化内测验证套件，作为版本交付与发布的准出门禁。
_Avoid_: DemoApp, QuickstartOnly

**NonPublishableModule**:
仅用于本地验证与 CI 质量门禁、严禁向公共中央仓库部署发布的演示与自验模块。通过标配 `<maven.deploy.skip>true</maven.deploy.skip>` 与根插件排除守卫实现生命周期与 Central 封包的双重隔离（参见 [ADR-0007](../docs/adr/0007-maven-publishing-and-clean-ci-pipeline.md)）。
_Avoid_: PublicArtifact, StagedModule, DistributableJar
