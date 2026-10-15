# Context Map: ateng-boot

`ateng-boot` 遵循多上下文（Multi-context）架构设计，各模块边界清晰、高内聚且低耦合。本映射表定义所有上下文的边界职责与其协作关系。

## Contexts

- [Core 核心底座](./ateng-boot-core/CONTEXT.md) — 提供全局统一 API 响应契约、异常体系、业务断言、用户审计 SPI 与异步上下文传递工具。
- [Web 运行时增强](./ateng-boot-web/CONTEXT.md) — 提供基于 Filter 的链路追踪、全局异常防御拦截、Jackson 防失真与时间格式化，以及 CORS 自动化配置。
- [MyBatis-Plus 持久层增强](./ateng-boot-mybatis-plus/CONTEXT.md) — 提供通用实体基类、审计填充器、多方言分页插件、防全表更新删除、乐观锁与分布式 ID 扩展。
- [All 聚合全家桶](./ateng-boot-all/CONTEXT.md) — 面向单体或快速起步业务，提供类 Hutool 风格的一键依赖打包交付。
- [Samples 验证工程](./ateng-boot-samples/CONTEXT.md) — 采用 H2 内存库提供内测验证用例与工程化最佳实践 Demo。

## Relationships

- **Web → Core**：Web 模块依赖 Core 模块的 `Result`、`BusinessException` 与 `IResultCode`，全局异常拦截器将未捕获异常转换为标准 `Result`，并联动 MDC 注入 `traceId`。
- **MyBatis-Plus → Core**：MyBatis-Plus 模块依赖 Core 模块的 `CurrentUserProvider` SPI 与 `UserContextHolder`，在数据持久化自动填充时动态获取当前操作人。
- **All → Core / Web / MyBatis-Plus**：All 模块为纯依赖聚合工程，向下游调用端透传三大核心 Starter。
- **Samples → All**：Samples 工程作为下游消费端样例，验证所有模块组合后的开箱即用自动装配与端到端运行表现。
