# 依赖管理采用 BOM Import 模式而非继承 parent

外部业务工程若必须继承框架的 parent POM 会剥夺其自主配置构建插件、定制多继承与指定根属性的灵活性。我们决定根工程与对外交付的 `ateng-boot-bom` 统一采用 `<dependencyManagement>` 的 `<scope>import</scope>` 模式分发依赖版本，赋予下游工程最大的依赖组合自由度。

---

## BOM 有效边界与构建期隔离决策 (2026-11-12 修订)

1. **Maven 3 反应堆约束下的 `<parent>` 权衡**：`ateng-boot-bom/pom.xml` 在仓内构建期间仍保留 `<parent>io.github.atengk:ateng-boot</parent>`，以确保根工程 `release` Profile、Maven Central 发布插件配置及 `mvn versions:set` 单一版本流（ADR-0008）能够统一覆盖并推进 BOM 版本号，避免 Maven 3 在全新工作区反应堆解析 sibling `import` 时报 `Non-resolvable import POM` 错误。
2. **ManagedDependencyBoundary 物理防泄漏**：正因 `ateng-boot-bom` 继承根 POM，根 POM 的 `<dependencyManagement>` 严禁声明任何仅服务于单模块底层兼容（如 `tools.jackson`、`com.fasterxml.jackson.core:jackson-annotations` 特供覆盖、`guava`、`commons-collections4`）、仅用于单元测试（`MockFtpServer`、`greenmail-junit5`）或尚未公开发行的可选扩展坐标；此类内部依赖版本必须下沉至对应 Starter POM 或 `ateng-boot-samples/pom.xml` 收敛管理，并通过 `mvn help:effective-pom -f ateng-boot-bom/pom.xml` 持续保障外部 BOM 有效清单 100% 纯净零泄漏。
