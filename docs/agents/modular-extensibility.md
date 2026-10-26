# 模块孵化与扩展通用标准 (Modular Extensibility SOP)

本指南规范 `ateng-boot` 孵化新模块与扩展既有生态的标准流程（0 到 1 阶段），确保所有衍生模块具备统一的工程质量、架构一致性与纯净度。

*(注：新模块孵化完成后，后续的存量加固、深度重构与全场景功能补齐，请遵循 [存量模块加固与全场景演进规范](./module-hardening-and-expansion.md)。)*

---

## 1. 工业级六件套闭环门禁 (The 6-Piece Gate)

无论扩展何种新功能模块（如 Storage、MQ、Job、AI 等），一律遵循“工业级六件套”闭环门禁：

1. **行业全场景前置对标 (Scenario Benchmarking Gate)**：
   - 孵化新模块前，必须对标该技术领域业界成熟标杆（如 AWS S3/OSS、Redisson、EasyExcel 等），梳理出排名前 80%~90% 的企业级典型高频业务场景；
   - 明确新模块首期需要支撑的核心场景矩阵与扩展点 SPI，杜绝孵化出只有一两个玩具方法的“半残” Starter。
2. **BOM 统管版本 (BOM-First Invariant)**：
   - 在根 `pom.xml` 与 `ateng-boot-bom/pom.xml` 的 `<dependencyManagement>` 集中纳管新模块及其外部依赖版本；
   - 子模块声明依赖严禁配置裸 `<version>` 标签（参见 [ADR-0003](../adr/0003-dependency-management-via-bom-import.md)）。
3. **专属自包含文档 (Sample-First 逆向提取)**：
   - 子模块根目录标配专属 `README.md`，文档中的所有 Java 代码与 YAML 示例必须 100% 逆向提取自对应 Sample 中已跑通的真实端到端场景；
   - 严格遵循“五维生产就绪交付模型”，核心显要位置 100% 收录全景速查矩阵（收录所有门面、公开方法、注解与配置项）。
4. **领域边界映射 (Domain Mapping)**：
   - 子模块配备独立的 `CONTEXT.md`（严格恪守纯领域词汇表定位，定义核心概念与 Avoid 负面词汇，严禁堆砌长篇实现代码）；
   - 在全局 `CONTEXT-MAP.md` 中注册清晰的上下文关系与依赖拓扑。
5. **全自动自验用例与 1:1 场景工程 (100% Feature Coverage)**：
   - 在 `ateng-boot-samples/ateng-boot-sample-<scenario>` 配套专属 1:1 对应的场景工程，新模块的所有公开 API、核心注解与配置项必须 100% 在 Sample 中具备端到端可运行用例（参见 [ADR-0028](../adr/0028-samples-modularization-and-incremental-integration-testing.md)）。
6. **底座原语优先复用 (Core-First Reuse Gate)**：
   - 新增中间件模块涉及容器查找、方法引用推导、JSON 序列化、分批切片、退避重试或路径清洗等需求时，强制 100% 优先复用 `ateng-boot-core` 已就绪的基础设施与标准件；
   - 坚决杜绝在子模块内私造工具类或引入第三方重复全量依赖（参见 [ADR-0025](../adr/0025-core-module-expansion-and-future-proof-middleware-foundations.md)）。

---

## 2. 架构契约与自动装配规范

1. **九道成熟度防线**：新模块编码必须全面对齐全局 [`AGENTS.md`](../../AGENTS.md) 确立的九道成熟度防线（对称语义完备、确定性资源清理、因果异常体系、严格无状态并发等）。
2. **防御性自动装配**：
   - 装配类统配 `@AutoConfiguration`，统一且只能注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；
   - 对外暴露的公共 `@Bean` 必须标配 `@ConditionalOnMissingBean`；
   - 配置属性统配 `Duration`（时间）与 `DataSize`（容量），嵌套配置标配 `@NestedConfigurationProperty`。

---

## 3. 场景化样例工程设计准则 (Sample Design Standards)

为保障多模块演化时的极速本地测试准出与环境无关性，样例工程遵循以下准则：

- **1:1 独立场景沙箱**：每个业务 Starter 严格对应一个专属的 `ateng-boot-sample-<scenario>`（如 `core`、`web`、`mybatis-plus`、`redis`、`excel`、`satoken`、`storage`），彻底杜绝跨 Starter 混合的胖样例；
- **Web / 非 Web 分层分明**：
  - **Web 契约层模块**（`web`、`satoken`、`openapi` 等）：编写规范的 `@RestController` 并配套 `MockMvc` / `TestRestTemplate` 真实测试；
  - **非 Web 底座/驱动模块**（`redis`、`storage`、`excel`、`kafka` 等）：专职编写 `ScenarioService` 并配套 `@SpringBootTest` 跑通真实业务流程，**严禁为了演示纯底座能力生造无意义的 Controller**；
- **业务场景命名驱动**：测试与组件类以实际业务痛点场景命名（如 `ChunkedUploadResumeScenario`、`OrderTimeoutDelayQueueScenario`）；
- **纯内存端自验**：默认依托 H2 内存数据库、纯内存 Mock 或嵌入式组件运行端到端测试，严禁依赖外部真实物理中间件环境，保障 `mvn test` 开箱即过；
- **自包含与零共享**：各场景工程完全独立自洽，严禁在样例工程之间建立依赖，严禁抽取跨样例共享代码；
- **非分发属性声明**：样例工程自身标配声明 `<maven.deploy.skip>true</maven.deploy.skip>`，且根发布插件协同配置 `<excludeArtifacts>` 排除，杜绝将测试工程封包发布至 Maven Central。

---

## 4. 极速本地构建验证

新模块开发完成后，必须执行多线程精准加速命令执行端到端自验，保障 100% PASS：

```bash
mvn test -pl ateng-boot-<new-module>,ateng-boot-samples/ateng-boot-sample-<scenario> -am -T 1C -nsu -B
```
