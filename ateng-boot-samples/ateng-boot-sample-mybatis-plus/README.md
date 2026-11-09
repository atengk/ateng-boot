# ateng-boot-sample-mybatis-plus 持久层增强底座体验工程

> 专注于演示与自验 `ateng-boot-mybatis-plus` 核心特性在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleMybatisPlusApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-mybatis-plus
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线 H2 内存数据库（`MODE=MySQL`）与 `schema.sql` 自动建表，无需启动任何外部物理数据库即可启动运行与跑通全量测试；
- **Local 真实模式**：若需连接本地或远端真实 MySQL / PostgreSQL 实例联调，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并按需修改连接参数，启动自动生效（该文件已被 Git 忽略，CI 零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验（各读取端点均内置按需幂等种子数据初始化，零前置准备即可直接调用）：

### 3.1 实体五级阶梯、生命周期自动填充与逻辑删除闭环 (`MpEntityLifecycleController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **创建用户与审计自动填充** | `MpEntityLifecycleController` | `POST /sample/mybatis-plus/lifecycle/user` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/lifecycle/user" -H "Content-Type: application/json" -d "{\"name\":\"王五\",\"age\":26,\"email\":\"wangwu@example.com\",\"phone\":\"13812345678\",\"operator\":\"auditor-alice\"}"` |
| **验证显式审计值不覆盖** | `MpEntityLifecycleController` | `POST /sample/mybatis-plus/lifecycle/user` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/lifecycle/user" -H "Content-Type: application/json" -d "{\"name\":\"陈七\",\"age\":31,\"email\":\"chenqi@example.com\",\"phone\":\"13987654321\",\"operator\":\"auditor-alice\",\"explicitCreateBy\":\"manual-creator\"}"` |
| **查询有效用户安全视图列表** | `MpEntityLifecycleController` | `GET /sample/mybatis-plus/lifecycle/users` | `curl "http://localhost:8080/sample/mybatis-plus/lifecycle/users"` |
| **商品 SKU 联合唯一索引循环软删除** | `MpEntityLifecycleController` | `POST /sample/mybatis-plus/lifecycle/sku/cycle-soft-delete` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/lifecycle/sku/cycle-soft-delete?skuCode=SKU-999"` |
| **商品 SKU 回收站穿透分页查询** | `MpEntityLifecycleController` | `GET /sample/mybatis-plus/lifecycle/sku/recycle-bin` | `curl "http://localhost:8080/sample/mybatis-plus/lifecycle/sku/recycle-bin?current=1&size=10"` |
| **按主键 ID 恢复误删商品 SKU** | `MpEntityLifecycleController` | `POST /sample/mybatis-plus/lifecycle/sku/{id}/restore` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/lifecycle/sku/1/restore?operator=ops-director"` |
| **按 SKU 编码批量恢复（空条件防呆拦截）** | `MpEntityLifecycleController` | `POST /sample/mybatis-plus/lifecycle/sku/restore-by-code` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/lifecycle/sku/restore-by-code?skuCode=SKU-999"` |
| **商品 SKU 回收站穿透总数统计** | `MpEntityLifecycleController` | `GET /sample/mybatis-plus/lifecycle/sku/recycle-bin/count` | `curl "http://localhost:8080/sample/mybatis-plus/lifecycle/sku/recycle-bin/count"` |
| **商品 SKU 回收站强类型 VO 分页投影** | `MpEntityLifecycleController` | `GET /sample/mybatis-plus/lifecycle/sku/recycle-bin/vo-page` | `curl "http://localhost:8080/sample/mybatis-plus/lifecycle/sku/recycle-bin/vo-page?current=1&size=10"` |
| **商品 SKU 彻底物理硬删除粉碎** | `MpEntityLifecycleController` | `POST /sample/mybatis-plus/lifecycle/sku/{id}/purge` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/lifecycle/sku/1/purge"` |

### 3.2 字段级透明加解密、视图动态脱敏与受管 JSON 映射 (`MpSecurityAndJsonController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **数据安全三态生命周期对比写入** | `MpSecurityAndJsonController` | `POST /sample/mybatis-plus/security-json/crypto-compare` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/security-json/crypto-compare" -H "Content-Type: application/json" -d "{\"name\":\"周八\",\"age\":29,\"email\":\"zhouba@example.com\",\"phone\":\"13812345678\"}"` |
| **一键体验数据安全三态对比 (GET)** | `MpSecurityAndJsonController` | `GET /sample/mybatis-plus/security-json/crypto-compare/demo` | `curl "http://localhost:8080/sample/mybatis-plus/security-json/crypto-compare/demo"` |
| **创建会员画像与深层泛型收货地址** | `MpSecurityAndJsonController` | `POST /sample/mybatis-plus/security-json/member-profile` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/security-json/member-profile" -H "Content-Type: application/json" -d "{\"memberName\":\"林九\",\"tags\":[\"架构\",\"云原生\"],\"roles\":[\"VIP\"],\"extraInfo\":{\"city\":\"Shenzhen\"},\"bio\":\"资深技术专家\",\"level\":8,\"shippingAddresses\":[{\"label\":\"公司\",\"city\":\"深圳市\",\"detailAddress\":\"南山区科技园\",\"defaultAddress\":true}]}"` |
| **查询会员画像详情 (支持种子自愈)** | `MpSecurityAndJsonController` | `GET /sample/mybatis-plus/security-json/member-profile/1` | `curl "http://localhost:8080/sample/mybatis-plus/security-json/member-profile/1"` |
| **验证 JSON 集合列 NULL 空安全保底** | `MpSecurityAndJsonController` | `POST /sample/mybatis-plus/security-json/member-profile/null-safety` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/security-json/member-profile/null-safety?memberName=空集合保底用户"` |

### 3.3 多租户隔离、AST 数据权限与多源守卫 (`MpTenantAndPermissionController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **租户订单行级自动隔离查询** | `MpTenantAndPermissionController` | `GET /sample/mybatis-plus/governance/tenant/orders` | `curl "http://localhost:8080/sample/mybatis-plus/governance/tenant/orders?tenantId=tenant_A"` |
| **多租户隔离与双轨豁免全量对比** | `MpTenantAndPermissionController` | `GET /sample/mybatis-plus/governance/tenant/compare` | `curl "http://localhost:8080/sample/mybatis-plus/governance/tenant/compare?currentTenantId=tenant_A"` |
| **@DataScope 部门与本人数据权限过滤** | `MpTenantAndPermissionController` | `GET /sample/mybatis-plus/governance/data-permission/users` | `curl "http://localhost:8080/sample/mybatis-plus/governance/data-permission/users?deptIds=10&selfOperator=creator-bob"` |
| **数据权限过滤、超管短路与闭包豁免对比** | `MpTenantAndPermissionController` | `GET /sample/mybatis-plus/governance/data-permission/compare` | `curl "http://localhost:8080/sample/mybatis-plus/governance/data-permission/compare?deptIds=10"` |
| **多数据源上下文守卫纯净校验** | `MpTenantAndPermissionController` | `POST /sample/mybatis-plus/governance/datasource-guard/check` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/governance/datasource-guard/check?mode=clean"` |
| **多数据源活跃事务内假切源阻断验证** | `MpTenantAndPermissionController` | `POST /sample/mybatis-plus/governance/datasource-guard/check` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/governance/datasource-guard/check?mode=active-tx"` |

### 3.4 动态查询安全转译、多表别名自愈与外部动态 SQL (`MpDynamicQueryAndSqlController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **PageUtil 白名单安全排序与流式 VO 分页** | `MpDynamicQueryAndSqlController` | `GET /sample/mybatis-plus/query/page-safe` | `curl "http://localhost:8080/sample/mybatis-plus/query/page-safe?current=1&size=10&sort=age:desc,internalRemark:asc,phone:desc"` |
| **URL 冒号高阶语法（含 ~ 与 or_组号:）检索** | `MpDynamicQueryAndSqlController` | `GET /sample/mybatis-plus/query/dynamic/url` | `curl "http://localhost:8080/sample/mybatis-plus/query/dynamic/url?current=1&size=10&age:between=20~32&or_1:name:like=张&or_1:deptId:eq=20&internalRemark:eq=hack&sort=age:asc"` |
| **@QueryField 业务检索 DTO 驱动分页** | `MpDynamicQueryAndSqlController` | `POST /sample/mybatis-plus/query/dynamic/dto` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/query/dynamic/dto" -H "Content-Type: application/json" -d "{\"current\":1,\"size\":10,\"minAge\":28,\"maxAge\":36,\"deptIds\":[20,30],\"internalRemark\":\"试图探测受限字段\"}"` |
| **多表 JOIN 方法引用分页与 AST 别名自愈** | `MpDynamicQueryAndSqlController` | `GET /sample/mybatis-plus/query/multi-table/page` | `curl "http://localhost:8080/sample/mybatis-plus/query/multi-table/page?current=1&size=10&deptName:like=研发&age:gte=26&sort=userId:asc"` |
| **多表 JOIN 方法引用列表查询** | `MpDynamicQueryAndSqlController` | `GET /sample/mybatis-plus/query/multi-table/list` | `curl "http://localhost:8080/sample/mybatis-plus/query/multi-table/list?deptName:eq=运营中心"` |
| **多表 existsDynamic 单页物理短路判定** | `MpDynamicQueryAndSqlController` | `GET /sample/mybatis-plus/query/multi-table/exists` | `curl "http://localhost:8080/sample/mybatis-plus/query/multi-table/exists?deptName:eq=研发二部&age:gte=30"` |
| **外部动态 SQL 免 Mapper 分页查询** | `MpDynamicQueryAndSqlController` | `GET /sample/mybatis-plus/query/dynamic-sql/page` | `curl "http://localhost:8080/sample/mybatis-plus/query/dynamic-sql/page?name=张&minAge=20&current=1&size=10"` |
| **外部动态 SQL 零停机热替换与执行验证** | `MpDynamicQueryAndSqlController` | `POST /sample/mybatis-plus/query/dynamic-sql/hot-swap` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/query/dynamic-sql/hot-swap" -H "Content-Type: application/json" -d "{\"statementId\":\"custom.webHotSwapDemo\",\"sqlScript\":\"SELECT id, name, age FROM sys_user WHERE id = #{id}\",\"description\":\"运行时热替换示例\",\"sampleParams\":{\"id\":1001}}"` |

### 3.5 原生高性能批量操作、受管流式游标与并发锁控制 (`MpBatchStreamAndLockController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **原生单条多值批量插入 (BatchUtil.insertBatch)** | `MpBatchStreamAndLockController` | `POST /sample/mybatis-plus/high-perf/batch/insert` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/high-perf/batch/insert" -H "Content-Type: application/json" -d "[{\"skuCode\":\"SKU-CURL-001\",\"skuName\":\"键盘\",\"price\":299.00,\"stock\":50},{\"skuCode\":\"SKU-CURL-002\",\"skuName\":\"鼠标\",\"price\":199.00,\"stock\":100}]"` |
| **双方言原生批量 Upsert (@ConflictKeys)** | `MpBatchStreamAndLockController` | `POST /sample/mybatis-plus/high-perf/batch/upsert` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/high-perf/batch/upsert" -H "Content-Type: application/json" -d "[{\"skuCode\":\"SKU-CURL-001\",\"skuName\":\"机械键盘Pro\",\"price\":399.00,\"stock\":80}]"` |
| **主键排序防死锁批量更新 (updateBatch & CASE WHEN)** | `MpBatchStreamAndLockController` | `POST /sample/mybatis-plus/high-perf/batch/update` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/high-perf/batch/update" -H "Content-Type: application/json" -d "[{\"id\":1001,\"price\":350.00,\"stock\":70}]"` |
| **受管流式游标分批大表导出 (StreamingQueryTemplate)** | `MpBatchStreamAndLockController` | `GET /sample/mybatis-plus/high-perf/stream/export` | `curl "http://localhost:8080/sample/mybatis-plus/high-perf/stream/export?minStock=10"` |
| **流式游标提前短路退出 (streamWhile)** | `MpBatchStreamAndLockController` | `GET /sample/mybatis-plus/high-perf/stream/while` | `curl "http://localhost:8080/sample/mybatis-plus/high-perf/stream/while?targetSkuCode=SKU-CURL-001"` |
| **CAS 乐观锁并发退避自旋重试扣减 (updateWithOptimisticRetry)** | `MpBatchStreamAndLockController` | `POST /sample/mybatis-plus/high-perf/lock/optimistic/deduct` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/high-perf/lock/optimistic/deduct?skuId=1001&deductAmount=50.00"` |
| **强类型悲观排队跳锁抢单 (LockMode.SKIP_LOCKED)** | `MpBatchStreamAndLockController` | `POST /sample/mybatis-plus/high-perf/lock/pessimistic/grab` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/high-perf/lock/pessimistic/grab?workerId=worker-node-1"` |
| **防全表误更新删除安全拦截 (BlockAttackInnerInterceptor)** | `MpBatchStreamAndLockController` | `POST /sample/mybatis-plus/high-perf/security/block-attack` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/high-perf/security/block-attack"` |

### 3.6 持久层全链路可观测：SQL 诊断看板、慢 SQL 告警与数据变更 Diff 审计 (`MpObservabilityAndAuditController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **查看最近结构化可执行 SQL 审计日志** | `MpObservabilityAndAuditController` | `GET /sample/mybatis-plus/observability/sql-log/recent` | `curl "http://localhost:8080/sample/mybatis-plus/observability/sql-log/recent?limit=5"` |
| **@IgnoreSqlLog 心跳探测排除日志与存储验证** | `MpObservabilityAndAuditController` | `GET /sample/mybatis-plus/observability/sql-log/ignore-probe` | `curl "http://localhost:8080/sample/mybatis-plus/observability/sql-log/ignore-probe"` |
| **模拟触发慢 SQL (耗时超 500ms 阈值触发告警)** | `MpObservabilityAndAuditController` | `POST /sample/mybatis-plus/observability/slow-sql/trigger` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/observability/slow-sql/trigger?sleepMs=600"` |
| **拉取慢 SQL 告警事件记录 (SlowSqlAlertProvider SPI)** | `MpObservabilityAndAuditController` | `GET /sample/mybatis-plus/observability/slow-sql/events` | `curl "http://localhost:8080/sample/mybatis-plus/observability/slow-sql/events"` |
| **订单业务更新触发 @AuditChange 字段级 Diff** | `MpObservabilityAndAuditController` | `POST /sample/mybatis-plus/observability/audit-change/update-order` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/observability/audit-change/update-order" -H "Content-Type: application/json" -d "{\"id\":2001,\"orderStatus\":\"PAID\",\"paymentAmount\":6999.00,\"remark\":\"用户已通过支付宝完成支付\"}"` |
| **runWithoutAudit 闭包旁路安全跳过变更审计** | `MpObservabilityAndAuditController` | `POST /sample/mybatis-plus/observability/audit-change/without-audit` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/observability/audit-change/without-audit" -H "Content-Type: application/json" -d "{\"id\":2001,\"orderStatus\":\"SHIPPED\",\"paymentAmount\":6999.00,\"remark\":\"系统自动化批量履约\"}"` |
| **拉取数据变更 Diff 审计记录 (DataChangeAuditProvider SPI)** | `MpObservabilityAndAuditController` | `GET /sample/mybatis-plus/observability/audit-change/records` | `curl "http://localhost:8080/sample/mybatis-plus/observability/audit-change/records"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-mybatis-plus,ateng-boot-samples/ateng-boot-sample-mybatis-plus -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-mybatis-plus README](../../ateng-boot-mybatis-plus/README.md)。
