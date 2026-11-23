# BOM Context

`ateng-boot-bom` 是整个框架的集中式依赖物料清单（Bill of Materials），为单体多模块或外部微服务工程提供版本统管与按需引入能力。

## Language

**BillOfMaterials**:
纯 POM 依赖统管清单（纯 `<dependencyManagement>`），统一定义 `ateng-boot` 各子模块及推荐中间件版本，使下游应用在组装具体 Starter 时免除显式声明版本的配置耦合。
_Avoid_: ParentPom, UmbrellaPom, SuperPom

**DependencyImport**:
基于 Maven `<type>pom</type>` 与 `<scope>import</scope>` 机制引入物料清单的非侵入式依赖管理策略，彻底解耦项目单一继承树限制。
_Avoid_: ParentInheritance, DirectVersionCoupling

**ManagedDependencyBoundary**:
BOM 显式纳管清单边界原则。`ateng-boot-bom` 仅向外部下游承诺全部分发 Starter 及其直接面向业务/扩展适配的核心三方中间件版本；根工程构建专用的静态兼容注解（如 `tools.jackson`、`guava`）、测试替身框架（`MockFtpServer`、`greenmail-junit5`）及实验性可选扩展仅允许收敛在消费子模块或 `ateng-boot-samples` 内部，严禁经由父级 `<dependencyManagement>` 泄露至 BOM 有效 POM 污染下游依赖树。
_Avoid_: InternalTestLeakage, UnboundedRootInheritance, ShadowOverride

