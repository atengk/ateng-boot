# ateng-boot-sample-flyway 数据库版本化迁移与多数据源协同体验工程

> 专注于演示与自验 `ateng-boot-flyway` 核心特性在 L1 主从锁主库迁移、L2 静态多业务库隔离迁移、L3 SaaS 物理多租户有界并发与动态挂载自动建表、MyBatis-Plus 启动初始化顺序屏障、破坏性 `clean`/DDL 硬守卫熔断、SM4 瞬态加密 DDL 凭据、对称泛型批处理回执（`BatchOperationResult<T>`）以及 Actuator/Prometheus 低基数可观测场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `src/main/resources/application.yml` 中修改）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleFlywayApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-flyway
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式（`!local` 离线自洽）**：内置 5 套 H2 内存数据库（MySQL 兼容模式：`master`、`slave`、`log_db`、`tenant_1001`、`tenant_1002`，示例配置中的 `sa` 与空密码仅用于离线 H2 内存验证），启动自动隔离执行各自 SQL 脚本与 Spring Bean `V2__SeedPlatformPresetTenantMigration`（`slave` 只读从库保持零表、零 `flyway_schema_history` 入侵），并在 Flyway 迁移彻底就绪后再放行 MyBatis-Plus 初始化。
- **Local 真实模式（`local` 外部联调）**：如需连接真实 MySQL 或 PostgreSQL 实例，复制 `src/main/resources/application-local.example.yml` 为 `src/main/resources/application-local.yml`，填入真实数据库 URL 与账号密码（支持 `enc:sm4:` / `enc:aes:` 加密高权限 DDL 凭据），通过 `-Dspring-boot.run.profiles=local` 启动即自动生效（`application-local.yml` 已被 `.gitignore` 忽略，CI 零凭据泄漏风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 `curl` 命令直接体验单库/静态多库迁移与预检、SaaS 物理租户全量治理与动态挂载、破坏性 DDL 与 `clean` 硬守卫及多目标低基数可观测端点（所有接口均显式返回强类型 `Result<T>`）：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **读取平台租户注册表 (MP 屏障校验)** | `FlywayMigrationController` | `GET /sample/flyway/migration/platform/tenants` | `curl "http://localhost:8080/sample/flyway/migration/platform/tenants"` |
| **读取独立审计库 `log_db` 日志** | `FlywayMigrationController` | `GET /sample/flyway/migration/audit-logs` | `curl "http://localhost:8080/sample/flyway/migration/audit-logs"` |
| **预检单库待执行脚本 (`pending`)** | `FlywayMigrationController` | `GET /sample/flyway/migration/pending` | `curl "http://localhost:8080/sample/flyway/migration/pending?targetKey=master"` |
| **批量预检多目标待迁移脚本 (`pendingTargets`)** | `FlywayMigrationController` | `POST /sample/flyway/migration/pending-targets` | `curl -X POST "http://localhost:8080/sample/flyway/migration/pending-targets" -H "Content-Type: application/json" -d '{"targetKeys":["master","log_db"],"policy":"FAIL_SAFE"}'` |
| **只读一致性校验 (`validate`)** | `FlywayMigrationController` | `POST /sample/flyway/migration/validate` | `curl -X POST "http://localhost:8080/sample/flyway/migration/validate" -H "Content-Type: application/json" -d '{"targetKey":"log_db"}'` |
| **触发指定目标库迁移 (`migrate`)** | `FlywayMigrationController` | `POST /sample/flyway/migration/execute` | `curl -X POST "http://localhost:8080/sample/flyway/migration/execute" -H "Content-Type: application/json" -d '{"targetKey":"master"}'` |
| **批量迁移静态库 (`master` + `log_db`)** | `FlywayMigrationController` | `POST /sample/flyway/migration/execute-static` | `curl -X POST "http://localhost:8080/sample/flyway/migration/execute-static"` |
| **全租户批量版本化迁移 (`tenant_*`)** | `FlywayTenantController` | `POST /sample/flyway/tenant/migrate-all` | `curl -X POST "http://localhost:8080/sample/flyway/tenant/migrate-all"` |
| **全租户批量只读一致性校验** | `FlywayTenantController` | `POST /sample/flyway/tenant/validate-all` | `curl -X POST "http://localhost:8080/sample/flyway/tenant/validate-all"` |
| **全租户批量待迁移脚本预检** | `FlywayTenantController` | `GET /sample/flyway/tenant/pending-all` | `curl "http://localhost:8080/sample/flyway/tenant/pending-all?policy=FAIL_SAFE"` |
| **全租户容错状态聚合探查** | `FlywayTenantController` | `GET /sample/flyway/tenant/info-all` | `curl "http://localhost:8080/sample/flyway/tenant/info-all?policy=FAIL_SAFE"` |
| **全租户批量修复历史表 (`repairAllTenants`)** | `FlywayTenantController` | `POST /sample/flyway/tenant/repair-all` | `curl -X POST "http://localhost:8080/sample/flyway/tenant/repair-all" -H "Content-Type: application/json" -d '{"policy":"FAIL_FAST"}'` |
| **全租户批量基线初始化 (`baselineAllTenants`)** | `FlywayTenantController` | `POST /sample/flyway/tenant/baseline-all` | `curl -X POST "http://localhost:8080/sample/flyway/tenant/baseline-all" -H "Content-Type: application/json" -d '{"version":"1","description":"tenant-batch-baseline","policy":"FAIL_SAFE"}'` |
| **动态挂载新租户库并自动建表 (`tenant_1003`)** | `FlywayTenantController` | `POST /sample/flyway/tenant/mount` | `curl -X POST "http://localhost:8080/sample/flyway/tenant/mount" -H "Content-Type: application/json" -d '{"tenantId":"1003","tenantName":"新锐智算科技有限公司"}'` |
| **向新挂载租户库写入订单** | `FlywayTenantController` | `POST /sample/flyway/tenant/orders` | `curl -X POST "http://localhost:8080/sample/flyway/tenant/orders" -H "Content-Type: application/json" -d '{"tenantId":"1003","orderNo":"ORD-1003-LIVE-888","productName":"云原生分布式网关授权","amount":4999.00}'` |
| **查询指定租户订单 (核验物理隔离)** | `FlywayTenantController` | `GET /sample/flyway/tenant/orders` | `curl "http://localhost:8080/sample/flyway/tenant/orders?tenantId=1003"` |
| **查询单库完整迁移历史视图 (`info`)** | `FlywayGovernanceController` | `GET /sample/flyway/governance/info` | `curl "http://localhost:8080/sample/flyway/governance/info?targetKey=log_db"` |
| **聚合查询全目标库迁移历史列表 (`infoAll`)** | `FlywayGovernanceController` | `GET /sample/flyway/governance/info-all` | `curl "http://localhost:8080/sample/flyway/governance/info-all"` |
| **容错聚合查询全目标库泛型回执 (`infoAll(policy)`)** | `FlywayGovernanceController` | `GET /sample/flyway/governance/info-batch` | `curl "http://localhost:8080/sample/flyway/governance/info-batch?policy=FAIL_SAFE"` |
| **指定多目标容错状态聚合 (`infoTargets`)** | `FlywayGovernanceController` | `POST /sample/flyway/governance/info-targets` | `curl -X POST "http://localhost:8080/sample/flyway/governance/info-targets" -H "Content-Type: application/json" -d '{"targetKeys":["master","log_db"],"policy":"FAIL_SAFE"}'` |
| **查询最近一次全局迁移汇总摘要** | `FlywayGovernanceController` | `GET /sample/flyway/governance/summary` | `curl "http://localhost:8080/sample/flyway/governance/summary"` |
| **修复指定目标库历史表 Checksum (`repair`)** | `FlywayGovernanceController` | `POST /sample/flyway/governance/repair` | `curl -X POST "http://localhost:8080/sample/flyway/governance/repair" -H "Content-Type: application/json" -d '{"targetKey":"master"}'` |
| **批量修复指定多目标历史表 (`repairTargets`)** | `FlywayGovernanceController` | `POST /sample/flyway/governance/repair-targets` | `curl -X POST "http://localhost:8080/sample/flyway/governance/repair-targets" -H "Content-Type: application/json" -d '{"targetKeys":["master","log_db"]}'` |
| **单目标基线初始化 (已迁移库返回 `62005`)** | `FlywayGovernanceController` | `POST /sample/flyway/governance/baseline` | `curl -X POST "http://localhost:8080/sample/flyway/governance/baseline" -H "Content-Type: application/json" -d '{"targetKey":"log_db","version":"1","description":"sample-baseline"}'` |
| **批量多目标基线初始化 (`baselineTargets`)** | `FlywayGovernanceController` | `POST /sample/flyway/governance/baseline-targets` | `curl -X POST "http://localhost:8080/sample/flyway/governance/baseline-targets" -H "Content-Type: application/json" -d '{"targetKeys":["master","log_db"],"version":"1","description":"batch-baseline","policy":"FAIL_FAST"}'` |
| **演示 `clean` 守卫强熔断 (返回 `62003`)** | `FlywayGovernanceController` | `POST /sample/flyway/governance/clean` | `curl -X POST "http://localhost:8080/sample/flyway/governance/clean" -H "Content-Type: application/json" -d '{"targetKey":"master"}'` |
| **演示 SM4 加密隔离 DDL 凭据与零泄漏关池** | `FlywayGovernanceController` | `POST /sample/flyway/governance/isolated-credential/demo` | `curl -X POST "http://localhost:8080/sample/flyway/governance/isolated-credential/demo" -H "Content-Type: application/json" -d '{"targetKey":"log_db","username":"sa","plainPassword":""}'` |
| **演示高危破坏性 DDL 静态预检 (`62010` 与注释豁免)** | `FlywayGovernanceController` | `POST /sample/flyway/governance/destructive-ddl-guard/demo` | `curl -X POST "http://localhost:8080/sample/flyway/governance/destructive-ddl-guard/demo" -H "Content-Type: application/json" -d '{"sqlScript":"-- @ateng-allow-destructive-ddl\nCREATE TABLE t_audit (id BIGINT); DROP TABLE t_audit;","allowExemptionAnnotation":true}'` |
| **查询多目标缓存感知 Flyway 健康快照** | `FlywayGovernanceController` | `GET /sample/flyway/governance/health` | `curl "http://localhost:8080/sample/flyway/governance/health"` |
| **查看 Actuator 多目标健康详情 (`flyway`)** | `HealthEndpoint` (Spring Actuator) | `GET /actuator/health` | `curl "http://localhost:8080/actuator/health"` |
| **查看低基数租户归一化迁移计数器 (`target=tenant`)** | `MetricsEndpoint` (Spring Actuator) | `GET /actuator/metrics/ateng.flyway.migrations.total` | `curl "http://localhost:8080/actuator/metrics/ateng.flyway.migrations.total"` |
| **查看受控作用域待执行脚本总数 Gauge** | `MetricsEndpoint` (Spring Actuator) | `GET /actuator/metrics/ateng.flyway.migrations.pending` | `curl "http://localhost:8080/actuator/metrics/ateng.flyway.migrations.pending"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-flyway,ateng-boot-samples/ateng-boot-sample-flyway -am -T 1C -nsu -B
```

> 详细原理机制、全景 API 速查矩阵、生产防线与完整 YAML 配置附录，请参阅核心 Starter 文档：[ateng-boot-flyway README](../../ateng-boot-flyway/README.md)。
