# ateng-boot-sample-mybatis-plus 持久层增强双领域模型场景自验工程

> 专一 `ateng-boot-mybatis-plus` 持久层增强底座自验：实体五级阶梯链、审计自动填充防御、逻辑删除、存储透明加密与内存真明文、行级多租户隔离、AST 细粒度数据权限、URL 冒号动态查询、原生批量插入、双方言原生 Upsert、防死锁批量更新、乐观锁 CAS 重试、受管流式游标与事务后数据变更 Diff 审计。

---

## 1. 场景痛点与功能定位

`ateng-boot-mybatis-plus` 是面向生产就绪的 MyBatis-Plus 工业级基础设施与持久层增强 Starter，坚持纯原生无反射侵入、零实体泄露与显式强类型设计。`ateng-boot-sample-mybatis-plus` 专注于双领域模型下的全特性端到端场景实测：
- **纯内存数据库自洽运行（H2 MySQL Mode）**：依托嵌入式 H2 数据库模拟真实生产 MySQL 语法与约束，无需外部物理网络或容器服务，极速秒级回归；
- **双领域实体正交验证**：
  - **用户领域模型 (`sys_user`)**：继承 `BaseFullEntity`，验证雪花主键、四要素审计自动填充、乐观锁、逻辑删除物理留存、`@FieldEncrypt` 存储透明加密与内存真明文、`@DataScope` AST 部门数据权限、`DynamicQueryHelper` URL 冒号语法安全转译；
  - **租户订单领域模型 (`tenant_order`)**：继承 `BaseEntity` 并实现 `TenantEntity`，验证行级多租户注入与 `@IgnoreTenant` 豁免、`@ConflictKeys` 双方言原生 Upsert、`insertBatchSomeColumn` 原生批量插入、`updateBatchSomeColumn` 防死锁批量更新、`OptimisticLockTemplate` 业务 CAS 重试、`StreamingQueryTemplate` 游标分批拉取与 `@AuditChange` 事务提交后字段级 Diff 审计捕获。

---

## 2. 双领域模型架构与自验全景

工程内聚两大领域模型、Mapper、Service 及 SPI 扩展实现，100% 覆盖持久层增强核心门面与特性：

| 领域模型 / 组件 | 覆盖核心门面 / 注解 / SPI | 对应端到端测试 (`src/test`) |
| :--- | :--- | :--- |
| **`UserEntity`** (`sys_user`) | `BaseFullEntity`, `DefaultMetaObjectHandler`, `@TableLogic`, `@FieldEncrypt`, `EncryptTypeHandler`, `@DataScope`, `DynamicQueryHelper` | `UserDomainIntegrationTest` (7 项全要素自验) |
| **`TenantOrderEntity`** (`tenant_order`) | `BaseEntity`, `TenantEntity`, `AtengTenantLineInnerInterceptor`, `@IgnoreTenant`, `TenantContextHolder`, `@ConflictKeys`, `BatchUtil`, `OptimisticLockTemplate`, `StreamingQueryTemplate`, `@AuditChange` | `TenantOrderDomainIntegrationTest` (8 项全要素自验) |
| **`MemoryDataChangeAuditProvider`** | `DataChangeAuditProvider` SPI 内存收集实现 | `TenantOrderDomainIntegrationTest.shouldCaptureDataChangeDiffOnTransactionCommit` |
| **`SampleDataPermissionHandler`** | `DataPermissionHandler` 部门数据权限 SPI 实现 | `UserDomainIntegrationTest.shouldFilterByDataScopeDepartmentPermission` |

---

## 3. 核心场景实测代码范式

### 1. 存储透明加密与内存真明文铁律 (`@FieldEncrypt`)
```java
// 实体声明密文字段并绑定 EncryptTypeHandler
@FieldEncrypt
@TableField(typeHandler = EncryptTypeHandler.class)
private String email;

@FieldEncrypt
@TableField(typeHandler = EncryptTypeHandler.class)
private String phone;
```
- **入库透明加密**：底层数据库表存储为强安全 AES 密文（如 `v1:Yd33MOmvM7qc/oCX...`），杜绝明文落盘泄露；
- **出库真明文恢复**：业务层与实体对象在内存中 100% 还原为原始明文（如 `13812345678`），严禁在业务逻辑中打掩码污染数据。

### 2. 行级多租户隔离与全自动 SQL 改写 (`TenantEntity` & `@IgnoreTenant`)
```java
// 实体实现 TenantEntity 标记接口
public class TenantOrderEntity extends BaseEntity implements TenantEntity {
    private String tenantId;
    // ...
}

// Mapper 声明 @IgnoreTenant 豁免或通过编程式闭包放行
public interface TenantOrderMapper extends AtengBaseMapper<TenantOrderEntity> {
    @IgnoreTenant
    List<TenantOrderEntity> selectGlobalOrders(Wrapper<TenantOrderEntity> queryWrapper);
}
```
- **日常业务查询**：当上下文设定 `TenantContextHolder.setTenantId("tenant_A")` 时，所有 CRUD 自动注入 `WHERE tenant_id = 'tenant_A'`；
- **平台超管与放行**：通过 `@IgnoreTenant` 注解或 `TenantContextHolder.runWithoutTenant(() -> ...)` 闭包实现零租户过滤。

### 3. 双方言原生 Upsert 与主键防死锁更新 (`@ConflictKeys` & `BatchUtil`)
```java
// 实体声明业务唯一键冲突判定列
@ConflictKeys({"order_no"})
public class TenantOrderEntity extends BaseEntity implements TenantEntity { ... }

// Service 调用高性能批处理
BatchUtil.upsertBatch(tenantOrderMapper, orderList, 500);
```
- **MySQL 方言**：自动生成 `INSERT INTO tenant_order (...) VALUES (...) ON DUPLICATE KEY UPDATE order_title = VALUES(order_title), ...`；
- **PostgreSQL 方言**：自动切换生成 `ON CONFLICT (order_no) DO UPDATE SET ...`；
- **防死锁批量更新**：`BatchUtil.updateBatch(tenantOrderMapper, list)` 在入库前强制对实体集合按主键升序重新排序，根除并发批量更新锁争抢死锁（Deadlock）。

### 4. 声明式数据变更 Diff 审计与事务后消费 (`@AuditChange` & SPI)
```java
// 实体声明变更追踪
@AuditChange(description = "订单变更审计")
public class TenantOrderEntity extends BaseEntity implements TenantEntity { ... }

// 业务在事务内执行更新
transactionTemplate.execute(status -> {
    TenantOrderEntity toUpdate = tenantOrderService.getById(order.getId());
    toUpdate.setOrderTitle("已变更订单标题");
    toUpdate.setAmount(new BigDecimal("600.00"));
    tenantOrderService.updateById(toUpdate);
    return true;
});
```
- **一级缓存安全防护**：拦截器在捕获前置快照前执行 `clearLocalCache()`，防御事务内同一实体对象复用导致的脏快照；
- **事务提交后（`afterCommit`）分发**：主事务提交成功后精确捕获并提取 `FieldDiff(propertyName=orderTitle, oldValue=原始订单标题, newValue=已变更订单标题)`，分发至 `DataChangeAuditProvider` SPI 与 Spring 原生 `DataChangeEvent`。

---

## 4. 定向集成测试准出

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-mybatis-plus -am -T 1C -nsu -B
```
全量测试用例（16 项端到端集成测试）基于纯内存 H2 独立运行，测试执行耗时在 5 秒以内，100% 绿灯准出。
