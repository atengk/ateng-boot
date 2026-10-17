# 模块孵化与扩展通用标准 (Modular Extensibility SOP)

本指南规范 `ateng-boot` 孵化新模块与扩展既有生态的标准流程，确保所有衍生模块具备统一的工程质量、架构一致性与纯净度。

## 1. 工业级五件套闭环门禁

无论扩展何种新功能模块（如 Storage、MQ、Job、AI 等），一律遵循“工业级五件套”闭环门禁：

1. **BOM 统管版本**：在根 `pom.xml` 与 `ateng-boot-bom/pom.xml` 的 `<dependencyManagement>` 集中纳管新模块及其外部依赖版本；子模块声明依赖免配 `<version>` 标签（参见 [ADR-0003](../adr/0003-dependency-management-via-bom-import.md)）。
2. **专属自包含文档**：子模块根目录标配专属 `README.md`，严格遵循“一句话场景痛点、最小可用代码块、预期控制台/JSON/表格呈现”三板斧规范。
3. **领域边界映射**：子模块配备独立的 `CONTEXT.md`（严格恪守纯领域词汇表定位，定义核心概念与 Avoid 负面词汇，严禁堆砌长篇实现代码），并在全局 `CONTEXT-MAP.md` 中注册清晰的上下文关系与依赖拓扑。
4. **全自动自验用例**：在 `ateng-boot-samples/ateng-boot-sample-<scenario>` 配套专属纯内存端（如 H2/Mock）的端到端自动化测试验证用例，恪守零共享代码与自包含原则（参见 [ADR-0028](../adr/0028-samples-modularization-and-incremental-integration-testing.md)）。
5. **底座原语优先复用 (Core-First Reuse Gate)**：新增中间件模块涉及容器查找、方法引用推导、JSON 序列化、分批切片、退避重试或路径清洗等需求时，强制 100% 优先复用 `ateng-boot-core` 已就绪的基础设施与标准件，坚决杜绝在子模块内私造工具类或引入第三方重复全量依赖（参见 [ADR-0025](../adr/0025-core-module-expansion-and-future-proof-middleware-foundations.md)）。

---

## 2. 场景化样例工程设计准则 (Sample Design Standards)

为保障多模块演化时的极速本地测试准出与环境无关性，样例工程遵循以下准则：

- **纯内存端自验**：默认依托 H2 内存数据库、纯内存 Mock 或嵌入式组件运行端到端测试，严禁依赖外部真实物理中间件环境，保障 `mvn test` 开箱即过；
- **自包含与零共享**：各场景工程（如 `ateng-boot-sample-basic`、`ateng-boot-sample-redis`）完全独立自洽，严禁在样例工程之间建立依赖，严禁抽取跨样例共享代码；
- **非分发属性声明**：样例工程自身标配声明 `<maven.deploy.skip>true</maven.deploy.skip>`，且根发布插件协同配置 `<excludeArtifacts>` 排除，杜绝将测试工程封包发布至 Maven Central。
