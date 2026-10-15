# Context Map: ateng-boot

`ateng-boot` 遵循多上下文（Multi-context）架构设计，各模块边界清晰、高内聚且低耦合。本映射表定义所有上下文的边界职责与其协作关系。

## Contexts

- [BOM 管控中心](./ateng-boot-bom/CONTEXT.md) — 依赖版本统管清单（纯 dependencyManagement），为微服务与按需精细化工程提供无侵入版本管控。
- [Core 核心底座](./ateng-boot-core/CONTEXT.md) — 提供全局统一 API 响应契约、异常体系、业务断言、用户审计 SPI 与异步上下文传递工具。
- [Web 运行时增强](./ateng-boot-web/CONTEXT.md) — 提供基于 Filter 的链路追踪、全局异常防御拦截、Jackson 防失真与时间格式化，以及 CORS 自动化配置。
- [MyBatis-Plus 持久层增强](./ateng-boot-mybatis-plus/CONTEXT.md) — 提供通用实体基类、审计填充器、多方言分页插件、防全表更新删除、乐观锁与分布式 ID 扩展。
- [All 聚合全家桶](./ateng-boot-all/CONTEXT.md) — 面向单体或快速起步业务，提供类 Hutool 风格的一键依赖打包交付。
- [Samples 验证工程](./ateng-boot-samples/CONTEXT.md) — 采用 H2 内存库提供内测验证用例与工程化最佳实践 Demo。

## Relationships

- **BOM → Core / Web / MyBatis-Plus / All**：BOM 模块作为顶层依赖物料清单，统一定义并对齐所有可分发构件的版本号，下游通过 import 即可按需引入且免配版本（参见 [ADR-0003](./docs/adr/0003-dependency-management-via-bom-import.md)）。
- **Web → Core**：Web 模块依赖 Core 模块的 `Result`、`BusinessException` 与 `IResultCode`，全局异常拦截器将未捕获异常转换为标准 `Result`，并联动 MDC 注入 `traceId`。
- **MyBatis-Plus → Core**：MyBatis-Plus 模块依赖 Core 模块的 `CurrentUserProvider` SPI 与 `UserContextHolder`，在数据持久化自动填充时动态获取当前操作人。
- **All → Core / Web / MyBatis-Plus**：All 模块为纯依赖聚合工程，向下游调用端透传三大核心 Starter。
- **Samples → All**：Samples 工程作为下游消费端样例，验证所有模块组合后的开箱即用自动装配与端到端运行表现。

## Architectural Decisions

全生命周期系统级架构演进与关键权衡详见 [架构决策记录看板 (ADR)](./docs/adr/README.md)。
