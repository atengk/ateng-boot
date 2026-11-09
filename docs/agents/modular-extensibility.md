# 模块孵化与扩展通用标准 (Modular Extensibility SOP)

本指南规范 `ateng-boot` 从 0 到 1 孵化全新中间件 Starter 模块及其配套 Sample 沙箱的标准流程，确保所有新增模块与既有生态保持 100% 架构一致性。

*(注：若对既有存量模块进行 1 到 N 的深度体检、场景扩充与加固重构，请遵循 [存量模块加固与全场景演进规范](./module-hardening-and-expansion.md)。)*

---

## 1. 工业级六件套闭环门禁 (The 6-Piece Incubation Gate)

孵化任何新模块 `ateng-boot-<module>`，必须一次性交付以下“工业级六件套”，缺一不可：

1. **行业全场景前置对标 (Scenario Benchmarking Gate)**：
   - 孵化前对标该技术领域业界成熟标杆（如 AWS S3、Redisson、Spring Kafka、LangChain4j 等），梳理排名前 80%~90% 的企业级高频生产场景；
   - 明确首期支撑的对称门面（`*Template`）、声明式注解与定制扩展点 SPI，杜绝孵化只有一两个玩具方法的半残模块。
2. **反应堆与 BOM 版本统管 (Reactor & BOM-First Gate)**：
   - 在根 `pom.xml` 的 `<modules>` 注册 `ateng-boot-<module>`；
   - 在根 `pom.xml` 与 `ateng-boot-bom/pom.xml` 的 `<dependencyManagement>` 集中纳管新模块及第三方驱动版本；子模块 `pom.xml` 一律免配裸 `<version>` 标签（参见 [ADR-0003](../adr/0003-dependency-management-via-bom-import.md)）。
3. **底座原语复用与防御性自动装配 (Core-First & Autoconfiguration Gate)**：
   - 100% 遵循全局 [`AGENTS.md`](../../AGENTS.md) 第 2、3 节的**防御性自动装配四道铁律**与**九道成熟度防线**；
   - 凡涉及上下文透传（`AsyncContext`）、受管线程池、JSON 序列化（`JsonUtil`）、脱敏（`DesensitizeUtil`）、分布式 ID（`IdUtil`）、退避重试（`RetryUtil`）、分批切片（`BatchSliceUtil`）、格式校验（`ValidatorUtil`）或路径安全清洗（`FileUtil`），强制 100% 复用 `ateng-boot-core`。
4. **1:1 独立场景沙箱与双模实测 (1:1 Sample & Dual-Mode Gate)**：
   - 在 `ateng-boot-samples/` 下创建 `ateng-boot-sample-<module>` 并注册至 `ateng-boot-samples/pom.xml`（标配 `<maven.deploy.skip>true</maven.deploy.skip>`，严禁跨 Sample 共享代码）；
   - 全面遵循 [`documentation-specification.md`](./documentation-specification.md) 第 3 节：引入 `ateng-boot-web`、按业务领域拆分暴露多 `@RestController` 接口、实现 `@Profile("!local")` 离线内存桩与 `application-local.example.yml` 双模自适应测试套件，100% 覆盖新模块所有公开 API、注解、配置项与 SPI。
5. **Sample-First 五维生产文档 (5D Documentation Gate)**：
   - 待 Sample 与对抗性单测 100% 跑通后，严格按照 [`documentation-specification.md`](./documentation-specification.md) 第 2、3 节，从已跑通的 Sample 中 1:1 逆向提取代码与配置，编写 Starter 五维 `README.md`（含 100% 全景速查矩阵与三要素就近闭环）及 Sample 四段式极简体验卡 `README.md`。
6. **领域边界映射与 ADR 沉淀 (Domain & ADR Gate)**：
   - 在新模块根目录创建纯领域词汇表 `ateng-boot-<module>/CONTEXT.md`，并在全局 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md) 的 `## Contexts` 与 `## Relationships` 中注册上下文拓扑（遵循 [`domain.md`](./domain.md)）；
   - 在 `docs/adr/` 下新建架构决策记录 `NNNN-<module>-architecture.md` 并登记至 [`docs/adr/README.md`](../adr/README.md)。

---

## 2. 极速本地构建与准出验收

新模块与配套 Sample 编码完成后，执行多线程定向验证并对照 [`documentation-specification.md`](./documentation-specification.md) 第 4 节《12 项自检清单》完成准出核验：

```bash
mvn test -pl ateng-boot-<module>,ateng-boot-samples/ateng-boot-sample-<module> -am -T 1C -nsu -B
```
