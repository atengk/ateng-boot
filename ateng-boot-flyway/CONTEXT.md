# Flyway 数据库版本化迁移底座

`ateng-boot-flyway` 是面向单数据源、动态多数据源与 SaaS 物理多租户场景的数据库版本化 Schema 迁移基础设施，基于 Flyway 10.x（`10.10.0`）与 Spring Boot 3 原生体系构建，提供生产防误操作硬守卫、隔离式 DDL 迁移连接治理、多数据源/动态租户库自动迁移协同、三态运行模式、对称编程式门面 `FlywayTemplate` 及启动期持久层顺序屏障。

## Language

**Flyway Template (版本化迁移统一门面)**:
对外暴露的对称编程式迁移操作门面，提供单库与多目标库维度的迁移（`migrate`）、校验（`validate`）、待执行脚本预检（`pending`）、历史信息探查（`info`）、基线初始化（`baseline`）、修复（`repair`）、受守卫控制的清理（`clean`）及最近一次迁移状态快照查询能力，并聚合输出结构化批处理回执。
_Avoid_: FlywayHelper, FlywayUtil, MigrationService, DbMigrator

**Migration Target (迁移目标数据源)**:
经路由规则解析后待执行版本化迁移的独立物理数据库单元，由数据源标识 `dsKey`、底层直连物理 `DataSource`（或隔离式 DDL 凭据）及其专属脚本路径、历史表名、Baseline 与隔离策略组成；读写分离只读从库默认被永久排除于迁移目标之外。
_Avoid_: TargetDb, MigrationNode, DbEndpoint

**Migration Target Resolver (迁移目标路由器)**:
从单数据源、动态路由数据源（`AtengDynamicRoutingDataSource`）或 `TenantDataSourceProvider` 中发现并归类迁移目标的 SPI 契约，负责剔除只读从库与未纳管的第三方辅助库，并隔离平台主库与物理租户库的脚本目录。
_Avoid_: TargetScanner, DbFilter, MigrationDiscovery, TargetRouter

**Production Safety Guard (生产防误操作硬守卫)**:
在受保护运行环境（如生产 Profile）下对破坏性操作（`clean`）及高危隐式清库配置（如 `clean-on-validation-error=true`）实施 Fail-Fast 物理熔断的安全防线，无视低级别开关误配置。
_Avoid_: CleanBlocker, SafetyFilter, DropGuard, ProductionCleanGuard

**Migration Execution Mode (迁移执行模式)**:
控制应用启动及受管触发迁移行为的三态枚举契约，包含 `MIGRATE`（自动执行版本迁移）、`VALIDATE_ONLY`（仅校验数据库状态与脚本 Checksum 一致性，严禁变更表结构）与 `DISABLED`（跳过自动迁移仅保留编程式门面）。
_Avoid_: FlywayMode, MigrationStrategy, RunMode

**Isolated DDL Credential (隔离式 DDL 迁移凭据)**:
专用于 Schema 迁移的高权限数据库连接配置（支持复用 `CryptoUtil` 解密 SM4/AES 密文），仅在迁移执行期间构造短生命周期物理连接并在迁移完成后确定性关闭，严禁向 Spring 容器注册持久化业务 `DataSource` Bean 或暴露闭池 `Flyway` Bean。
_Avoid_: FlywayDataSourceBean, AdminDataSource, MigrationPool

**Tenant Migration Router (物理租户迁移路由器)**:
联动 `ateng-boot-datasource` 物理多租户拓扑与生命周期监听器，将静态或动态挂载的租户物理库自动映射至独立于公共主库的租户专用迁移脚本目录，并按 `BatchExecutionPolicy` 容错策略隔离各租户迁移执行。
_Avoid_: TenantMigrator, MultiDbSync, TenantSchemaRunner

**Dynamic Mount Failure Policy (动态挂载迁移失败策略)**:
运行时通过生命周期监听器自动迁移新挂载租户库发生失败时的处置策略，包含 `KEEP_AND_REPORT`（默认，保留数据源并记录错误与失败事件便于重试补迁）与 `ROLLBACK_DATASOURCE`（同一生命周期锁内主动摘除未建表数据源以防流量误入）。
_Avoid_: MountErrorPolicy, TenantRollbackMode, DynamicFailStrategy

**Context Placeholder (上下文迁移占位符)**:
执行目标库迁移时自动注入的只读上下文占位符（`ateng_ds_key` 与 `ateng_tenant_id`），用于共享脚本识别当前物理目标且绝不覆盖用户显式声明的同名占位符。
_Avoid_: TenantSqlVar, DynamicVar, ScriptMacro

**Batch Migration Result (批量迁移聚合回执)**:
承载多数据源或 SaaS 多租户批量迁移执行后的结构化汇总回执，包含成功/跳过/失败计数、各目标耗时、已脱敏的失败明细列表及失败策略执行结果。
_Avoid_: MigrationReport, MultiMigrateRes, BatchMigrateInfo

**Flyway Lock Provider (分布式并发迁移锁 SPI)**:
多实例并发启动或动态挂载迁移时的外部协调锁扩展契约，缺省委托 Flyway 原生数据库表级/咨询锁保证并发安全，按需支持接入自定义分布式互斥锁实现。
_Avoid_: MigrationMutex, ClusterLock, DbUpgradeLock

**Database Initialization Barrier (持久层初始化顺序屏障)**:
在 Spring 容器启动阶段声明并强制保障 Flyway 版本化迁移严格早于 MyBatis-Plus `SqlSessionFactory`、`SqlSessionTemplate`、外部动态 SQL 预热及 DDL Runner 初始化的启动依赖屏障。
_Avoid_: StartupOrderHack, BeanOrderFix, InitWaiter

**Migration Health Indicator (迁移健康探针)**:
对接 Spring Boot Actuator 的轻量健康检查组件，基于最近一次迁移执行快照与可控缓存探测暴露各目标库迁移就绪状态与待执行摘要，避免高频轮询直接压垮数据库。
_Avoid_: FlywayProbe, MigrationCheck, SchemaHealth

**Flyway Exception (迁移专有业务异常)**:
继承 `BusinessException` 并绑定 `FlywayResultCode`（`IResultCode`）的受管异常，完整保留底层 JDBC 与 Flyway 原生异常因果链（`cause`），并对连接 URL 与凭据执行脱敏处理。
_Avoid_: MigrationError, DbUpgradeException, FlywayRuntimeError
