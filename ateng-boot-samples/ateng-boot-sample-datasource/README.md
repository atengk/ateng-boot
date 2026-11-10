# ateng-boot-sample-datasource 动态多数据源与物理隔离体验工程

> 专注于演示与自验 `ateng-boot-datasource` 核心特性在 SaaS 物理多租户分库、主从读写分离、写后读主防延迟、动态生命周期排空治理、全库容错巡检、连接池并发预热与事务假切源刚性守卫场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleDatasourceApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-datasource
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线嵌入式多 H2 内存数据库自洽配置（`master`, `slave`, `tenant_1001`, `tenant_1002`），无需启动任何外部依赖即可秒级启动运行与 100% 跑通测试；
- **Local 真实模式**：若需连接真实物理数据库，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实数据库连接串（需包含 `spring.config.activate.on-profile: local` 与激活 `local` profile），启动自动生效（该文件已被 Git 忽略，CI 零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或 HTTP 客户端直接发起调用体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **租户 1001 下单 (物理分库)** | `DatasourceTenantController` | `POST /sample/datasource/tenant/order` | `curl -X POST "http://localhost:8080/sample/datasource/tenant/order" -H "Content-Type: application/json" -H "X-Tenant-Id: 1001" -d '{"orderNo":"ORD-1001-001","productName":"SaaS 旗舰商品","amount":299.00}'` |
| **租户 1001 查询订单** | `DatasourceTenantController` | `GET /sample/datasource/tenant/order/{orderNo}` | `curl "http://localhost:8080/sample/datasource/tenant/order/ORD-1001-001" -H "X-Tenant-Id: 1001"` |
| **跨租户物理隔离核验 (返回空)** | `DatasourceTenantController` | `GET /sample/datasource/tenant/order/{orderNo}` | `curl "http://localhost:8080/sample/datasource/tenant/order/ORD-1001-001" -H "X-Tenant-Id: 1002"` |
| **主库下单 (激活写后读主防延迟)** | `DatasourceReadWriteController` | `POST /sample/datasource/read-write/order/master` | `curl -X POST "http://localhost:8080/sample/datasource/read-write/order/master" -H "Content-Type: application/json" -d '{"orderNo":"ORD-MASTER-001","productName":"主库直写商品","amount":666.00}'` |
| **从库读取 (窗口期内自动重定向主库)** | `DatasourceReadWriteController` | `GET /sample/datasource/read-write/order/get/{orderNo}` | `curl "http://localhost:8080/sample/datasource/read-write/order/get/ORD-MASTER-001"` |
| **租户从库群集群读取** | `DatasourceReadWriteController` | `GET /sample/datasource/read-write/tenant-group/order/{orderNo}` | `curl "http://localhost:8080/sample/datasource/read-write/tenant-group/order/ORD-1001-001?tenantId=1001"` |
| **模拟从库故障熔断隔离** | `DatasourceReadWriteController` | `POST /sample/datasource/read-write/failover/simulate-failure` | `curl -X POST "http://localhost:8080/sample/datasource/read-write/failover/simulate-failure?replicaKey=slave"` |
| **查询从库熔断黑名单** | `DatasourceReadWriteController` | `GET /sample/datasource/read-write/failover/blacklisted` | `curl "http://localhost:8080/sample/datasource/read-write/failover/blacklisted"` |
| **手动自愈恢复从库节点** | `DatasourceReadWriteController` | `POST /sample/datasource/read-write/failover/recover` | `curl -X POST "http://localhost:8080/sample/datasource/read-write/failover/recover?replicaKey=slave"` |
| **动态挂载新数据源并建表** | `DatasourceLifecycleController` | `POST /sample/datasource/lifecycle/add` | `curl -X POST "http://localhost:8080/sample/datasource/lifecycle/add" -H "Content-Type: application/json" -d '{"dsKey":"dynamic_user_db","url":"jdbc:h2:mem:dynamic_user_db;DB_CLOSE_DELAY=-1;MODE=MySQL","preWarm":true,"preWarmConnections":3}'` |
| **动态数据源业务写入** | `DatasourceLifecycleController` | `POST /sample/datasource/lifecycle/order/create` | `curl -X POST "http://localhost:8080/sample/datasource/lifecycle/order/create?dsKey=dynamic_user_db&orderNo=DYN-ORD-001"` |
| **优雅排空在途事务并安全注销** | `DatasourceLifecycleController` | `DELETE /sample/datasource/lifecycle/remove/{dsKey}` | `curl -X DELETE "http://localhost:8080/sample/datasource/lifecycle/remove/dynamic_user_db?drain=true"` |
| **全库批量容错巡检 (FAIL_SAFE)** | `DatasourceBatchController` | `POST /sample/datasource/batch/inspect-all` | `curl -X POST "http://localhost:8080/sample/datasource/batch/inspect-all?policy=FAIL_SAFE"` |
| **全租户订单数据统计遍历** | `DatasourceBatchController` | `POST /sample/datasource/batch/inspect-tenants` | `curl -X POST "http://localhost:8080/sample/datasource/batch/inspect-tenants"` |
| **连接池并发预热 (消除冷抖动)** | `DatasourceBatchController` | `POST /sample/datasource/batch/pre-warm` | `curl -X POST "http://localhost:8080/sample/datasource/batch/pre-warm?dsKey=master&connections=5"` |
| **查询连接池实时运行快照 (Active/Idle)** | `DatasourceOpsController` | `GET /sample/datasource/ops/pool-stats` | `curl "http://localhost:8080/sample/datasource/ops/pool-stats"` |
| **触发外部数据源智能三向差量同步** | `DatasourceOpsController` | `POST /sample/datasource/ops/sync-external` | `curl -X POST "http://localhost:8080/sample/datasource/ops/sync-external"` |
| **事务内违规切源 (演练刚性拦截阻断)** | `DatasourceGuardController` | `POST /sample/datasource/guard/simulate-false-switch` | `curl -X POST "http://localhost:8080/sample/datasource/guard/simulate-false-switch?masterOrderNo=M-001&tenantOrderNo=T-001"` |
| **独立事务安全切源 (REQUIRES_NEW)** | `DatasourceGuardController` | `POST /sample/datasource/guard/safe-switch-requires-new` | `curl -X POST "http://localhost:8080/sample/datasource/guard/safe-switch-requires-new?masterOrderNo=M-002&tenantOrderNo=T-002"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-datasource,ateng-boot-samples/ateng-boot-sample-datasource -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-datasource README](../../ateng-boot-datasource/README.md)。
