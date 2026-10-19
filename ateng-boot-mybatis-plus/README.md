# ateng-boot-mybatis-plus 持久层增强底座

> 提供实体五级阶梯体系、审计自动填充、行级多租户隔离、AST 声明式细粒度数据权限、原生高效批量插入、存储透明加密与视图动态脱敏。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **实体体系** | 泛型主键实体基类 | `BaseGenericEntity<ID>` | - | 规范实体主键类型定义与强类型 `getId()` / `setId(ID)` | `BaseEntityTest` / `UserDomainIntegrationTest` |
| **实体体系** | 雪花 ID 主键基类 | `BaseIdEntity` | `ateng.boot.mybatis-plus.snowflake.*` | 标配 64 位 Long 主键与 `@TableId(type = IdType.ASSIGN_ID)` | `BaseEntityTest` / `UserDomainIntegrationTest` |
| **实体体系** | 时间流转基类 | `BaseTimeEntity` | `ateng.boot.mybatis-plus.audit.*` | 提供 `createTime` (`FieldFill.INSERT`) 与 `updateTime` (`FieldFill.INSERT_UPDATE`) | `BaseEntityTest` / `UserDomainIntegrationTest` |
| **实体体系** | 四要素全审计业务基类 | `BaseEntity` | `ateng.boot.mybatis-plus.audit.*` | 提供 `createBy`、`updateBy`、`createTime`、`updateTime` 四要素全审计 | `BaseEntityTest` / `TenantOrderDomainIntegrationTest` |
| **实体体系** | 全要素状态业务基类 | `BaseFullEntity` | `ateng.boot.mybatis-plus.optimistic-locker.*` | 在审计基类上追加 `@Version private Integer version` (乐观锁) 与 `@TableLogic @JsonIgnore private Integer deleted` (逻辑删除) | `BaseEntityTest` / `UserDomainIntegrationTest` |
| **实体体系** | 唯一索引防冲突占位基类 | `BaseDeleteIdEntity` | - | 继承 `BaseFullEntity`，`deleted` 为 `BIGINT` 并在删除时以雪花 ID 占位，彻底杜绝联合唯一索引逻辑删除冲突 | `BaseDeleteIdEntityTest` / `BaseEntityTest` |
| **实体体系** | 多租户实体标记接口 | `TenantEntity` | `ateng.boot.mybatis-plus.tenant.*` | 声明 `getTenantId()` / `setTenantId(String)` 租户标识契约 | `TenantOrderDomainIntegrationTest` |
| **自动填充** | 防御性审计元数据填充 | `DefaultMetaObjectHandler` | `ateng.boot.mybatis-plus.audit.enabled`<br>`ateng.boot.mybatis-plus.audit.default-operator` | 插入/更新自动提取操作人并填充审计字段，若已显式赋值则绝不覆盖覆盖 | `DefaultMetaObjectHandlerTest` / `UserDomainIntegrationTest` |
| **审计贯通** | 当前操作人上下文提供者 | `CurrentUserProvider` | - | Core 统一操作人 SPI，驱动 `DefaultMetaObjectHandler` 解析操作人字符串/ID | `DefaultMetaObjectHandlerTest` / `UserDomainIntegrationTest` |
| **多租户隔离** | AST 动态行级多租户拦截器 | `AtengTenantLineInnerInterceptor` | `ateng.boot.mybatis-plus.tenant.enabled`<br>`ateng.boot.mybatis-plus.tenant.column`<br>`ateng.boot.mybatis-plus.tenant.ignore-tables` | 基于 JSqlParser 在 AST 语法树层级改写 SQL，自动拼接 `tenant_id = ?` 条件，支持多表 JOIN 与子查询精准注入 | `AtengTenantLineInnerInterceptorTest` / `TenantOrderDomainIntegrationTest` |
| **多租户解析** | 默认租户上下文处理器 | `DefaultTenantLineHandler` | `ateng.boot.mybatis-plus.tenant.column` | 桥接 `TenantContextHolder` 获取租户 ID，支持动态与静态表忽略过滤 | `DefaultTenantLineHandlerTest` / `TenantOrderDomainIntegrationTest` |
| **多租户绕过** | 声明式租户忽略注解 | `@IgnoreTenant` | - | 声明于 Mapper 方法或类上，自动绕过多租户行级过滤；支持与 `TenantContextHolder.runWithoutTenant(...)` 协同 | `AtengTenantLineInnerInterceptorTest` / `TenantOrderDomainIntegrationTest` |
| **数据权限** | AST 细粒度数据权限拦截器 | `AtengDataPermissionInnerInterceptor` | `ateng.boot.mybatis-plus.data-permission.enabled` | 基于 JSqlParser 在 AST 层级拦截改写 SQL，无缝拼接数据权限过滤表达式 | `AtengDataPermissionInnerInterceptorTest` / `UserDomainIntegrationTest` |
| **数据权限** | 动态数据权限处理器 | `DataPermissionHandler` / `DefaultDataPermissionHandler` | - | 数据权限 SPI 与默认处理器，结合上下文提取注入数据过滤 SQL 片段 | `AtengDataPermissionInnerInterceptorTest` / `UserDomainIntegrationTest` |
| **数据权限** | 声明式数据权限范围注解 | `@DataScope` | - | 细粒度声明式数据权限注解，支持按用户、部门或自定义表达式进行数据范围收敛 | `AtengDataPermissionInnerInterceptorTest` / `UserDomainIntegrationTest` |
| **数据权限** | 数据权限上下文保持器 | `DataPermissionContextHolder` | - | 线程安全透传数据权限上下文与过滤表达式，支持链式构建与自动现场清理 | `DataPermissionContextHolderTest` / `UserDomainIntegrationTest` |
| **原生批量操作** | 原生分批操作通用门面 | `BatchUtil` | `ateng.boot.mybatis-plus.batch.default-size` | `insertBatch` / `upsertBatch` / `updateBatch`（含 `*SomeColumn` 别名），内置切片分批（默认 1000）、内存防死锁升序排序与六要素轻量自愈补偿 | `BatchUtilTest` / `BatchUpsertTest` / `BatchUpdateTest` / `TenantOrderDomainIntegrationTest` |
| **原生批量操作** | 双方言批量 Upsert 注入器 | `UpsertBatchSomeColumn` / `@ConflictKeys` | - | 原生单条多值 Upsert SQL 注入器（MySQL `ON DUPLICATE KEY UPDATE` / PostgreSQL `ON CONFLICT DO UPDATE`），支持 `@ConflictKeys` 复合唯一键 | `BatchUpsertTest` / `TenantOrderDomainIntegrationTest` |
| **原生批量操作** | 原生单条批量更新注入器 | `UpdateBatchSomeColumn` | - | 原生 ANSI CASE WHEN 多值更新 SQL 注入器，内置 `AntiDeadlockSqlSource` 强制按主键 ID 升序防死锁，动态回退保护未提供字段 | `BatchUpdateTest` / `TenantOrderDomainIntegrationTest` |
| **原生批量操作** | 全局增强 SQL 注入器 | `AtengSqlInjector` | - | 自动向 MyBatis-Plus 注入 `InsertBatchSomeColumn`、`UpsertBatchSomeColumn` 与 `UpdateBatchSomeColumn` 原生批量操作方法 | `AtengSqlInjectorTest` / `TenantOrderDomainIntegrationTest` |
| **存储加解密** | 字段级透明存储加密注解 | `@FieldEncrypt` | `ateng.boot.mybatis-plus.crypto.secret-key` | 标注于实体属性上，声明该字段由 `EncryptTypeHandler` 执行持久化透明加解密 | `EncryptTypeHandlerTest` / `UserDomainIntegrationTest` |
| **存储加解密** | 存储透明加解密类型转换器 | `EncryptTypeHandler` | `ateng.boot.mybatis-plus.crypto.secret-key` | 持久化时入库 AES 密文，查询出库解密为真实明文，严格坚守“内存真明文”铁律 | `EncryptTypeHandlerTest` / `UserDomainIntegrationTest` |
| **乐观锁重试** | 乐观锁防冲突重试模板 | `OptimisticLockTemplate` | `ateng.boot.mybatis-plus.optimistic-locker.enabled` | `executeWithRetry(Supplier<Boolean>)` / `executeWithRetry(Supplier<Boolean>, int, Duration)`，高并发更新冲突自动退避重试 | `OptimisticLockTemplateTest` / `TenantOrderDomainIntegrationTest` |
| **分页契约** | 分页对象流式解耦与安全转译 | `PageUtil` | `ateng.boot.mybatis-plus.pagination.max-limit` | `toPage(PageQuery)` / `toPage(PageQuery, Class<T>)`（安全转译 sorts 并依托实体白名单过滤） / `toPageResult(IPage<T>)` / `toPageResult(IPage<S>, Function<S, T>)`，彻底解耦传输契约与持久实体（Zero-Entity-Leakage） | `PageUtilTest` |
| **分页拦截** | 原生分页拦截与防刷守护 | `PaginationInnerInterceptor` | `ateng.boot.mybatis-plus.pagination.enabled`<br>`ateng.boot.mybatis-plus.pagination.max-limit`<br>`ateng.boot.mybatis-plus.pagination.overflow`<br>`ateng.boot.mybatis-plus.pagination.db-type` | 原生分页拦截，注入单页最大条数限制（默认 500）与防刷超限防御，自动识别数据库方言 | `AtengMybatisPlusAutoConfigurationTest` |
| **安全阻断** | 防全表更新/删除拦截器 | `BlockAttackInnerInterceptor` | `ateng.boot.mybatis-plus.block-attack.enabled` | 拦截无 `WHERE` 条件的全表更新与删除操作，防止误操作摧毁生产库数据 | `AtengMybatisPlusAutoConfigurationTest` |
| **性能审计** | 慢 SQL 性能审计拦截器 | `SlowSqlInterceptor` | `ateng.boot.mybatis-plus.slow-sql.enabled`<br>`ateng.boot.mybatis-plus.slow-sql.threshold`<br>`ateng.boot.mybatis-plus.slow-sql.format-sql`<br>`ateng.boot.mybatis-plus.slow-sql.mask-sensitive` | 拦截 `StatementHandler` 执行，基于 `System.nanoTime()` 高精度快速比对放行；超时输出美化 SQL、真实脱敏参数及调用位置；严禁自动 EXPLAIN | `SlowSqlInterceptorTest` / `AtengMybatisPlusAutoConfigurationTest` |
| **告警通知** | 慢 SQL 告警通知 SPI | `SlowSqlAlertProvider` | - | 函数式 SPI 扩展契约，支持下游无缝对接飞书/钉钉群机器人或外部 APM 平台 | `SlowSqlInterceptorTest` |
| **审计事件** | 慢 SQL 审计事件模型 | `SlowSqlEvent` | - | 继承 Spring 原生 `ApplicationEvent`，发布执行耗时、格式化 SQL、脱敏参数、线程名与代码调用栈位置 | `SlowSqlInterceptorTest` |
| **发号器** | 分布式雪花算法发号器 | `DefaultIdentifierGenerator` | `ateng.boot.mybatis-plus.snowflake.enabled`<br>`ateng.boot.mybatis-plus.snowflake.worker-id`<br>`ateng.boot.mybatis-plus.snowflake.datacenter-id` | 提供趋势递增的 64 位全局唯一分布式 ID 发号支持 | `AtengMybatisPlusAutoConfigurationTest` / `UserDomainIntegrationTest` |
| **动态 SQL** | 外部动态 SQL 增强注册中心 | `DynamicSqlRegistry` | `ateng.boot.mybatis-plus.dynamic-sql.enabled`<br>`ateng.boot.mybatis-plus.dynamic-sql.locations` | 外部 SQL 注册、AST 投影字典解析提取与热加载中心，支持运行时动态注册/移除外部 XML 脚本，联动缓存失效通知 | `DynamicSqlRegistryTest` / `DynamicSqlExecutorDynamicTest` |
| **动态 SQL** | 外部动态 SQL 统一执行门面 | `DynamicSqlExecutor` | `ateng.boot.mybatis-plus.dynamic-sql.auto-script-wrap` | `selectPage` / `selectList` / `selectOne` / `count` / `insert` / `update` / `delete`，原生支持接收 `DynamicQueryRequest` 及业务 DTO，多表 AST 投影自愈与 ADR-0010 强类型 VO 映射 | `DynamicSqlExecutorTest` / `DynamicSqlExecutorDynamicTest` |
| **动态 SQL** | 外部 SQL 资源自动加载器 | `ExternalSqlLoader` / `ResourceExternalSqlLoader` | `ateng.boot.mybatis-plus.dynamic-sql.locations` | 外部 SQL 资源加载 SPI 与默认实现，启动时自动扫描指定路径并注册至 `DynamicSqlRegistry` | `ResourceExternalSqlLoaderTest` |
| **流式游标** | 原生游标 SQL 注入器 | `SelectCursor` | - | 自动向 Mapper 注入 `selectCursor` MappedStatement，底层强制设置 `fetchSize = 1000`，杜绝内存溢出 | `AtengSqlInjectorTest` / `TenantOrderDomainIntegrationTest` |
| **流式游标** | 受管流式游标查询模板 | `StreamingQueryTemplate` | - | `forEach` / `forEachBatch` / `forEachWhile` / `execute`，强制只读事务守护，`try-with-resources` 确定性闭合游标与连接归还，支持 Predicate 提前短路退出与不可变切片防卫 | `StreamingQueryTemplateTest` / `TenantOrderDomainIntegrationTest` |
| **多数据源协同** | 多数据源诊断探针与上下文守卫 | `DynamicDatasourceGuard` | `ateng.boot.mybatis-plus.guard.dynamic-datasource` | 启动期轻量感知 Spring 容器多数据源或动态路由特征，输出多租户与事务协同建议；提供 `validateContextIsolated()` 静态断言防卫活跃事务假切源与上下文污染 | `DynamicDatasourceGuardTest` / `AtengMybatisPlusAutoConfigurationTest` |
| **基础扩展** | 通用 BaseMapper 增强门面 | `AtengBaseMapper<T>` | - | 继承 MyBatis-Plus `BaseMapper`，标配挂载批量方法与 `Cursor<T> selectCursor(@Param(Constants.WRAPPER) Wrapper<T> queryWrapper)` 原生流式游标查询 | `AtengSqlInjectorTest` / `TenantOrderDomainIntegrationTest` |
| **基础扩展** | 通用 Service 增强实现基类 | `AtengBaseService<T>` / `AtengServiceImpl<M, T>` | - | 继承 MyBatis-Plus `IService`，提供零实体泄露基础 VO 投影（`getById` / `listByIds` / `list` / `getOne`）、批量写入、受管流式查询体系、单表/多表动态查询及存在性判断（`existsDynamic`），以及乐观锁高并发自旋重试（`updateWithOptimisticRetry`），支持严格 `throwEx` 快速失败、分批切片与短路退出 | `AtengBaseServiceBatchTest` / `AtengBaseServiceStreamTest` / `AtengBaseServiceDynamicTest` / `AtengBaseServiceOptimisticLockTest` / `TenantOrderDomainIntegrationTest` |
| **动态查询** | 通用受管执行模板门面 | `DynamicQueryTemplate` | `ateng.boot.mybatis-plus.dynamic-query.enabled` | `selectPage` / `selectList` / `selectOne` / `count` / `exists`，跨单表实体、Mapper XML 语句或外部动态 SQL 提供对称完备的强类型动态查询与单页物理短路直出，内建 AST 别名自愈与自适应条件追加 | `DynamicQueryTemplateTest` / `AtengBaseServiceMultiTableDynamicTest` |
| **动态查询** | Service 联表方法引用与短方法名直出 | `AtengBaseService` / `AtengServiceImpl` | `ateng.boot.mybatis-plus.dynamic-query.enabled` | `pageDynamic` / `listDynamic` / `getOneDynamic` / `countDynamic` / `existsDynamic`，支持基于方法引用（`mapper::method`）或短方法名执行多表联表极速直出与单页 `Page(1, 1, false)` 物理短路判定 | `AtengBaseServiceMultiTableDynamicTest` |
| **动态查询** | AST 多表别名与物理列投影自愈解析器 | `DynamicSqlProjectionResolver` | - | 基于 JSqlParser 解析 Mapper XML / 外部 SQL 投影字典，纳秒级缓存，运行时无感知将 VO 属性名与多表别名透明映射为底层物理列表达式（如 `deptName` -> `d.name`） | `DynamicSqlProjectionResolverTest` |
| **动态查询** | 联表方法引用契约与解析辅助 | `DynamicMapperPageMethod`, `DynamicMapperListMethod`, `DynamicLambdaHelper` | - | 原生 `SerializedLambda` 纳秒级提取 Mapper 联表方法名与所属类，函数式强类型接口规范 | `DynamicLambdaHelperTest` / `AtengBaseServiceMultiTableDynamicTest` |
| **动态查询** | 动态查询安全转译引擎门面 | `DynamicQueryHelper` | `ateng.boot.mybatis-plus.dynamic-query.enabled` | `buildWrapper(DynamicQueryRequest, Class<T>)` / `buildWrapper(String, Class<T>)` / `buildWrapper(Map, Class<T>)` / `buildWrapper(Object, Class<T>)`，纳秒级元数据白名单缓存与六重安全防卫编译 | `DynamicQueryHelperTest` / `UserDomainIntegrationTest` |
| **动态查询** | URL Query 与 Map 高阶语法解析器 | `DynamicQueryParser` | - | `parse(String)` / `parse(Map)` / `parseToWrapper(String, Class<T>)`，支持冒号操作符、波浪线 between 切分与 `or_组号` 作用域分组解析 | `DynamicQueryParserTest` |
| **动态查询** | 动态查询请求与条件契约 | `DynamicQueryRequest`, `QueryCondition`, `QueryOperator` | - | 13 种受控操作符与流式链式构建，支持 `or(orGroup, condition)` 独立嵌套 OR 逻辑块 | `DynamicQueryHelperTest` / `DynamicQueryParserTest` |
| **动态查询** | 字段映射与敏感防卫注解 | `@QueryField`, `@DynamicQueryIgnore` | - | `@QueryField(property, operator, ignoreNull)` 声明式 DTO 驱动转译；`@DynamicQueryIgnore` 实体敏感列防越权探测 | `DynamicQueryHelperTest` |
| **变更审计** | 声明式数据变更审计注解 | `@AuditChange` | - | 声明于实体类或 Mapper 接口/方法上，声明开启字段级 Diff 变更追踪与前后快照捕获，支持属性包含与排除 | `DataChangeAuditInterceptorTest` / `TenantOrderDomainIntegrationTest` |
| **变更审计** | 数据变更 Diff 审计拦截器 | `DataChangeAuditInterceptor` | `ateng.boot.mybatis-plus.audit-change.enabled`<br>`ateng.boot.mybatis-plus.audit-change.max-diff-rows`<br>`ateng.boot.mybatis-plus.audit-change.async`<br>`ateng.boot.mybatis-plus.audit-change.publish-event` | 拦截 MyBatis `Executor.update`，事务提交后（`afterCommit`）异步分发，集成超限熔断降级与缺 Provider 自动休眠 | `DataChangeAuditInterceptorTest` / `AtengMybatisPlusAutoConfigurationTest` / `TenantOrderDomainIntegrationTest` |
| **变更审计** | 数据变更审计消费 SPI | `DataChangeAuditProvider` | - | 函数式消费 SPI，供业务自由对接审计日志落盘、Elasticsearch 或消息队列 | `DataChangeAuditInterceptorTest` / `TenantOrderDomainIntegrationTest` |
| **变更审计** | 数据变更审计 Spring 事件 | `DataChangeEvent` | `ateng.boot.mybatis-plus.audit-change.publish-event` | 变更提交后发布 Spring 原生应用事件，供下游声明式 `@EventListener` 解耦监听 | `DataChangeAuditInterceptorTest` |
| **变更审计** | 数据变更审计上下文持有者 | `DataChangeAuditContextHolder` | - | 提供 `runWithoutAudit` 与 `callWithoutAudit` 编程式闭包，安全短路跳过当前线程的变更审计 | `DataChangeAuditInterceptorTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-mybatis-plus` 深度融合 MyBatis-Plus 官方体系，针对企业级 SaaS、多租户及高安全持久层场景提供深度增强。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-mybatis-plus</artifactId>
</dependency>
```

---

## 2. 实体五级继承阶梯体系 (Tiered Entity Hierarchy)

### 场景痛点
避免每个实体都重复定义主键、时间与审计字段，按业务复杂度提供五个阶梯基类按需继承：

```
BaseGenericEntity<ID>          (泛型主键基类)
  └── BaseIdEntity             (64位雪花 ID 主键基类，Long id)
        └── BaseTimeEntity     (含时间流转基类，createTime, updateTime)
              └── BaseEntity   (四要素全审计业务基类，createBy, updateBy, createTime, updateTime)
                    └── BaseFullEntity (全要素状态基类，追加乐观锁 version 与逻辑删除 deleted)
```

### 实体选择决策指南
| 业务场景 | 推荐基类 | 包含的核心属性 |
| :--- | :--- | :--- |
| 关联映射表、配置映射表 | `BaseIdEntity` | `id` |
| 日志表、不可篡改流水表 | `BaseTimeEntity` | `id`, `createTime`, `updateTime` |
| 绝大部分标准业务表（默认） | `BaseEntity` | `id`, `createBy`, `updateBy`, `createTime`, `updateTime` |
| 核心交易、订单、资产流转表 | `BaseFullEntity` | 追加 `version` (乐观锁) 与 `deleted` (逻辑删除，标配 `@JsonIgnore`) |
| 多租户数据表 | 继承上述基类并实现 `TenantEntity` | 追加 `tenantId` |

### 核心实体代码示例
```java
@TableName(value = "sys_user", autoResultMap = true)
public class UserEntity extends BaseFullEntity {
    private String name;
    private Integer age;
    private String email;
}
```

---

## 3. 防御性审计自动填充 (`DefaultMetaObjectHandler`)

### 场景痛点
开发人员无需手动 `setCreateTime` 或获取操作人。
- 自动提取当前上下文操作人（由 `CurrentUserProvider` SPI 提供，支持字符串或 Long 用户 ID）；
- 插入时自动填充 `createTime`、`createBy`、`updateTime`、`updateBy`；
- 更新时自动填充 `updateTime`、`updateBy`；
- **防御性赋值**：若业务代码已经显式 `setCreateTime(...)`，自动填充策略绝不覆盖开发者显式赋值。

---

## 4. 数据安全生命周期分离：存储透明加密与视图动态脱敏

### 场景痛点与“内存真明文”铁律
传统做法直接在业务 Service 中给手机号打星号，导致“星号脏数据写回数据库”的数据损毁事故。`ateng-boot` 严格分离生命周期：
- **存储加密**：收敛于 MyBatis `TypeHandler`（入库为 AES/SM4 密文，出库反序列化为真实明文）；
- **内存状态**：Service 业务逻辑与实体对象在内存中始终保持 100% 原始真实明文；
- **视图脱敏**：依托 `ateng-boot-core` 底座提供的 `@FieldSensitive` 与 `SensitiveJsonSerializer`，仅在向前端响应输出 JSON 时动态掩码。

### 核心代码范式
```java
import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;

@TableName(value = "sys_user", autoResultMap = true) // 必须开启 autoResultMap
public class UserEntity extends BaseEntity {

    // 1. 数据库存储透明加密（入库自动 AES 加密，查询自动解密）
    @FieldEncrypt
    @TableField(typeHandler = EncryptTypeHandler.class)
    // 2. 接口对外返回时动态脱敏打码（内存与数据库依然保持真明文，来自 ateng-boot-core）
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String phone;

    @FieldEncrypt
    @TableField(typeHandler = EncryptTypeHandler.class)
    @FieldSensitive(SensitiveType.ID_CARD)
    private String idCard;

    @FieldSensitive(SensitiveType.ADDRESS)
    private String address;
}
```
**数据库存储呈现**：`8a1d7c4b...`（加密密文）  
**Service 业务计算取值**：`user.getPhone()` -> `"13812345678"`（真实明文）  
**前端接口响应呈现**：`"phone": "138****5678"`（安全掩码）

---

## 5. 基于 JSqlParser AST 的行级多租户安全隔离 (`@IgnoreTenant`)

### 场景痛点
SaaS 多租户系统最忌讳数据越权访问。采用 AST 动态改写 SQL，无需开发者手工在每条 SQL 中手写 `AND tenant_id = ?`。

### 核心机制
- 无论查询、更新或删除，自动解析 SQL 语法树并在 WHERE 与 JOIN 关联中注入租户条件；
- 支持字符串租户与纯数字租户自适应推导；
- 全局表白名单配置（如字典表、系统配置表免隔离）。

### 忽略多租户注解与闭包放行
```java
// 多租户上下文统一来自 ateng-boot-core 底座
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.mybatisplus.annotation.IgnoreTenant;

// 1. Mapper 接口方法级显式忽略多租户（如跨租户统计）
public interface UserMapper extends BaseMapper<UserEntity> {
    @IgnoreTenant
    List<UserEntity> selectGlobalUsers();
}

// 2. 编程式闭包受控忽略（执行完毕后自动安全恢复）
TenantContextHolder.runWithoutTenant(() -> {
    sysDictService.initSystemCache();
});
```

---

## 6. 基于 JSqlParser AST 的声明式细粒度数据权限 (`@DataScope`)

### 场景痛点
根据当前登录用户的部门权限、本人数据权限等动态过滤 SQL，传统拼接 SQL 极易引发 SQL 注入漏洞。

### 核心代码范式
```java
public interface DocumentMapper extends BaseMapper<DocumentEntity> {

    // 声明数据权限过滤：自动在 SQL 注入部门列与本人列过滤条件
    @DataScope(
        deptColumn = "dept_id",
        userColumn = "create_by",
        alias = "d" // 针对多表 JOIN 场景限定目标表别名
    )
    List<DocumentVO> queryAccessibleDocs(@Param("ew") Wrapper<DocumentEntity> wrapper);
}
```

### 超管放行与编程式闭包
```java
// 超级管理员身份自动秒级放行，跳过 AST 语法树解析
DataPermissionContextHolder.runAsSuperAdmin(() -> {
    documentMapper.selectList(null);
});

// 临时跳过数据权限限制
DataPermissionContextHolder.runWithoutPermission(() -> {
    documentMapper.selectList(null);
});
```

---

## 7. 原生高性能批量操作引擎 (批量 Insert / Upsert / Update)

### 场景痛点
MyBatis-Plus 原生 `saveBatch` 或 `updateBatchById` 底层使用 `SqlSession` 循环遍历逐条单发预编译语句，网络通信 RTT 极高且在并发更新时容易因行锁顺序不一致引发数据库死锁。

`ateng-boot-mybatis-plus` 提供纯原生单条 SQL 批量操作三剑客（`insertBatch`、`upsertBatch`、`updateBatch`），配合 `BatchUtil` 智能切片分批与防死锁排序守护。

---

### 7.1 原生多值批量插入 (`insertBatch`)

- **底层 SQL**：原生单条多值 `INSERT INTO t (col1, col2) VALUES (1, 2), (3, 4)...`；
- **智能过滤**：自动过滤逻辑删除列，支持全字段批量写入；
- **智能分批**：`BatchUtil` 自动切片分批（默认 1000 批次），避免数据库网络包溢出与占位符数量超限；
- **自愈补偿**：插入前自动补齐雪花主键 ID、创建时间、创建人、多租户 ID 等。

```java
// 1. Mapper 继承 AtengBaseMapper
@Mapper
public interface UserMapper extends AtengBaseMapper<UserEntity> {
}

// 2. Service 继承 AtengBaseService 与 AtengServiceImpl
public interface UserService extends AtengBaseService<UserEntity> {
}

@Service
public class UserServiceImpl extends AtengServiceImpl<UserMapper, UserEntity> implements UserService {
}

// 3. 业务层极速批量插入（万级数据秒级入库）
List<UserEntity> users = generateBatchUsers(20000);
// 方式一：通过 BatchUtil 门面分批插入（推荐）
BatchUtil.insertBatch(userMapper, users); // 自动按 1000 条切片

// 方式二：通过 AtengBaseService 注入执行（带事务保障）
userService.insertBatch(users);
```

---

### 7.2 双方言原生批量 Upsert 增强 (`upsertBatch` & `@ConflictKeys`)

- **双方言适配**：MySQL 生成 `ON DUPLICATE KEY UPDATE`，PostgreSQL 生成 `ON CONFLICT (...) DO UPDATE SET`；
- **复合唯一键声明**：支持实体上标注 `@ConflictKeys({"user_id", "role_code"})` 指定 PostgreSQL 冲突目标列；
- **审计安全**：自动排除主键列与创建审计列（`create_time` / `create_by`），更新时不冲刷创建信息。

```java
// 批量 Upsert：冲突时原地更新非主键字段，保持 create_time 与 create_by 不变
// 方式一：通过 BatchUtil 门面分批执行
BatchUtil.upsertBatch(userMapper, users);

// 方式二：通过 AtengBaseService 注入执行（受声明式事务守护）
userService.upsertBatch(users);

// 方式三：单实体极简 Upsert
userService.upsert(singleUser); // 或 userMapper.upsertSomeColumn(singleUser);
```

---

### 7.3 原生批量更新与主键排序防死锁引擎 (`updateBatch` & Anti-Deadlock)

- **ANSI SQL 标准**：生成单条 `UPDATE table SET col = CASE id WHEN ? THEN ? ELSE col END WHERE id IN (?)`，100% 兼容 MySQL、PostgreSQL 与 H2；
- **并发防死锁铁律（Anti-Deadlock Invariant）**：
  在执行批量更新 SQL 前，强制在内存中对实体集合按照主键 ID 升序（Ascending）排序，确保不同并发事务获取行锁的顺序绝对一致，从物理源头彻底杜绝行级锁交叉死锁；双轨制保障：即使用户绕过 `BatchUtil` 直接调用 `mapper.updateBatchSomeColumn`，底层挂载的 `AntiDeadlockSqlSource` 也会在预编译阶段强制重排；
- **选择性更新与空值保护**：
  每列内嵌动态 `<trim prefix="col = CASE id " suffix="ELSE col END,">`，仅针对非 null 属性生成 `WHEN` 判定；若某行未设置该列则命中 `ELSE col` 保护已有值不被覆盖；若整个批次全为 null 则整列从 `SET` 剔除；
- **更新审计自愈**：
  前置自动执行 `autoHealForUpdate`，为缺失的 `updateTime` 填充当前系统时间、为缺失的 `updateBy` 填充当前上下文操作人，且绝对不覆盖 `createTime` 与 `createBy`。

```java
// 1. 构造待批量更新的实体数据（支持乱序传入）
UserUpdateEntity u1 = new UserUpdateEntity();
u1.setId(1L);
u1.setUsername("Alice_Updated");
u1.setAge(21);
u1.setEmail(null); // 留空为 null，自动命中 ELSE email 保护数据库原值

UserUpdateEntity u2 = new UserUpdateEntity();
u2.setId(2L);
u2.setEmail("bob_new@test.com"); // 仅更新 email，其余字段自动保留

UserUpdateEntity u3 = new UserUpdateEntity();
u3.setId(3L);
u3.setUsername("Charlie_Updated");
u3.setEmail("charlie_new@test.com");
u3.setAge(35);

// 2. 乱序传入执行批量更新：自动按 ID 升序重排 [1, 2, 3] 并执行 ANSI CASE WHEN 单条 SQL
// 方式一：通过 BatchUtil 门面执行
int affectedRows = BatchUtil.updateBatch(userMapper, List.of(u3, u1, u2));

// 方式二：通过 AtengBaseService 注入执行（受声明式事务守护）
userService.updateBatch(List.of(u3, u1, u2));
```

---

## 8. 持久层发号器大一统桥接

模块自动桥接并注册 `IdentifierGenerator` 适配器，将 MyBatis-Plus 默认主键生成策略委托至 `ateng-boot-core` 的原生雪花算法引擎与 `IdUtil`。
- 实体类只需标准声明 `@TableId(type = IdType.ASSIGN_ID)` 即可自动享受 2026 纪元与时钟回拨保护；
- 彻底终结多套发号器时间戳割裂与节点冲突问题。

---

## 9. 方言插件链与防全表误操作

模块随启动自动装配以下必备防护插件：
- **多方言分页插件**：自动识别 MySQL、PostgreSQL、Oracle 等，内置单页最大 `500` 条软上限防 OOM；
- **防全表更新删除插件 (`BlockAttackInnerInterceptor`)**：对没有任何 WHERE 条件的 `UPDATE` 或 `DELETE` 语句强制拦截抛错；
- **CAS 乐观锁插件 (`OptimisticLockerInnerInterceptor`)**：自动感知实体的 `@Version` 字段执行版本比对更新。

---

## 10. 乐观锁并发自旋重试模板 (`OptimisticLockTemplate`)

### 场景痛点
在高并发库存扣减、账户余额更新或状态机流转场景下，MyBatis-Plus 的 `@Version` 乐观锁常因并发冲突导致更新影响行数为 0。业务层如果不做自旋重试就会频繁报错，而手工编写 `while` 循环容易产生死锁或缺少指数退避。

### 核心代码范式
依托 `ateng-boot-core` 纯原生 `RetryUtil` 引擎，提供开箱即用的乐观锁并发重试闭包：
```java
import io.github.atengk.boot.mybatisplus.util.OptimisticLockTemplate;

// 1. 默认自旋重试（最多 3 次，初始等待 50ms，1.5 倍指数退避）
Account account = OptimisticLockTemplate.execute(
        () -> accountMapper.selectById(accountId), // 每次自旋重试重新加载最新版本实体
        acc -> {
            if (acc.getBalance() < deductionAmount) {
                throw new BusinessException(400, "账户余额不足");
            }
            acc.setBalance(acc.getBalance() - deductionAmount);
            // 执行带 @Version 的乐观锁更新，影响行数 > 0 返回 true，版本冲突返回 false 自动触发自旋重试
            return accountMapper.updateById(acc) > 0;
        }
);

// 2. 自定义重试次数与初始退避时长
Account updated = OptimisticLockTemplate.execute(
        () -> accountMapper.selectById(accountId),
        acc -> {
            acc.setStatus("PROCESSED");
            return accountMapper.updateById(acc) > 0;
        },
        5,                          // 最多重试 5 次
        Duration.ofMillis(100)      // 初始退避 100ms
);
```

### 服务层开箱即用门面 (`AtengBaseService.updateWithOptimisticRetry`)
业务 Service 继承 `AtengServiceImpl` 即可直接调用 `updateWithOptimisticRetry`，由实体主键与修改函数驱动，无需手动拼装 `selectById` 与 `updateById`：
```java
@Service
public class AccountServiceImpl extends AtengServiceImpl<AccountMapper, Account> implements AccountService {

    public void deductBalance(Long accountId, int amount) {
        // 1. 默认自旋重试（最多 3 次，初始退避 50ms）
        Account updated = updateWithOptimisticRetry(accountId, account -> {
            if (account.getBalance() < amount) {
                throw new BusinessException(400, "账户余额不足");
            }
            account.setBalance(account.getBalance() - amount);
        });

        // 2. 自定义重试策略（支持自定义最大尝试次数与退避时长）
        Account custom = updateWithOptimisticRetry(accountId, account -> {
            account.setStatus("ACTIVE");
        }, 5, Duration.ofMillis(100));
    }
}
```

> [!IMPORTANT]
> **关键架构约束 (非全局事务编排 + 独立单条更新事务)**：
> `updateWithOptimisticRetry` 方法本身**严禁标注全局 `@Transactional`**。在 MySQL `REPEATABLE READ` 默认隔离级别下，若由外层开启全局事务，事务内多次查询默认使用首个快照视图（MVCC Snapshot Read），导致循环重试中 `getById(id)` 永远读不到其他已提交事务修改的新版本号，陷入死循环冲突；本门面采用无全局事务编排，每次自旋重试独立调用 `getById(id)` 读取最新已提交快照，由单次 `updateById(entity)` 承载独立原子更新，彻底消除旧读陷阱。

---

## 11. 持久层与传输契约解耦工具 (`PageUtil`)

### 核心代码范式
```java
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.boot.core.model.PageResult;

@Service
public class UserServiceImpl implements UserService {

    @Override
    public PageResult<UserVO> queryUserPage(PageQuery pageQuery) {
        // 1. 将 PageQuery 转换为 MyBatis-Plus Page 对象（默认限制最大不超过 500 条防 OOM）
        Page<UserEntity> page = PageUtil.toPage(pageQuery);

        // 2. 执行持久层分页查询
        userMapper.selectPage(page, new LambdaQueryWrapper<UserEntity>().like(UserEntity::getName, "张"));

        // 3. 流式转换为前端契约 PageResult<UserVO>，抹平 ORM 内部属性
        return PageUtil.toPageResult(page, UserVO::fromEntity);
    }
}
```

---

## 12. 外部动态 SQL 引擎与运行时热更新 (`DynamicSqlEngine`)

### 场景痛点
传统 MyBatis 必须在编译期将 XML 固化打包于应用 JAR 包内部，任何 SQL 调整均需重新编译发版。
`ateng-boot-mybatis-plus` 提供了开箱即用的外部动态 SQL 引擎：
1. **全语法动态支持**：原生支持 `<script>`、`<where>`、`<if>`、`<trim>`、`<choose>`、`<foreach>` 等全部 MyBatis 动态标签；
2. **零停机原子热替换 (Zero-Gap Safe Put)**：基于底层反射原子覆盖，彻底消除 MyBatis 原生 `StrictMap` 冲突，杜绝先删后加带来的毫秒级并发读穿透空洞；
3. **沙箱语法校验与防灾回滚**：新 SQL 在沙箱隔离预编译，若存在语法错误自动拦截并阻断更新，保留旧版稳定运行；
4. **拦截器链 100% 透明穿透**：外部动态注入的语句自动享有行级多租户隔离、细粒度数据权限改写、防全表误操作阻断与敏感字段透明加解密；
5. **双模调用生态**：既支持与已有 Java Mapper 接口无缝透明绑定，又提供全功能编程式执行器 `DynamicSqlExecutor`（原生融入 ADR-0010 分页契约）；
6. **事务与会话完全统一**：底层统一桥接 Spring 受管的 `SqlSessionTemplate`，与 MyBatis-Plus 的 `ServiceImpl` / `BaseMapper` 100% 共享相同事务上下文与数据库连接。

### 1. 外部 XML 脚本编写示例
在 `src/main/resources/dynamic-sql/user-dynamic.xml` 中定义外部 SQL：
```xml
<?xml version="1.0" encoding="UTF-8"?>
<dynamic-sql>
    <select id="user.findActiveUsers" resultType="java.util.Map" description="动态查询活跃用户列表">
        SELECT id, name, email, status
        FROM sys_user
        <where>
            <if test="status != null">
                AND status = #{status}
            </if>
            <if test="keyword != null and keyword != ''">
                AND name LIKE CONCAT('%', #{keyword}, '%')
            </if>
        </where>
        ORDER BY id DESC
    </select>
</dynamic-sql>
```

### 2. 编程式全功能调用示例 (`DynamicSqlExecutor`)
```java
@Service
public class DynamicReportService {

    @Autowired
    private DynamicSqlExecutor dynamicSqlExecutor;

    public void executeReports() {
        // 1. 结构化动态条件分页查询（原生融合 DynamicQueryRequest，AST 别名自愈与强类型 VO 映射）
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(10L);
        request.addCondition(QueryCondition.like("userName", "Ateng"));
        request.addCondition(QueryCondition.ge("age", 20));
        request.addSort(SortItem.desc("age"));

        PageResult<UserDeptVO> dynamicPage = dynamicSqlExecutor.selectPage(
                "exec.userWithDept",
                request,
                UserDeptVO.class
        );

        // 2. 业务查询 DTO 驱动分页查询（支持 @QueryField 或继承 PageQuery）
        UserQueryDTO dto = new UserQueryDTO();
        dto.setCurrent(1L);
        dto.setSize(10L);
        dto.setUserName("Ateng");
        dto.setDeptName("研发部");

        PageResult<UserDeptVO> dtoPage = dynamicSqlExecutor.selectPage(
                "exec.userWithDept",
                dto,
                UserDeptVO.class
        );

        // 3. 动态列表与记录数统计
        List<UserDeptVO> list = dynamicSqlExecutor.selectList("exec.userWithDept", request, UserDeptVO.class);
        long totalCount = dynamicSqlExecutor.count("exec.userWithDept", request);

        // 4. 深度融合 ADR-0010 传统入参分页：物理方言分页并流式转换为前端标准 PageResult<VO>
        PageQuery query = new PageQuery(1L, 10L);
        PageResult<UserVO> pageResult = dynamicSqlExecutor.selectPage(
                "user.findActiveUsers",
                Map.of("status", 1),
                query,
                UserVO.class
        );

        // 5. 动态 INSERT / UPDATE / DELETE 编程式执行
        int affected = dynamicSqlExecutor.update(
                "user.updateDynamicStatus",
                Map.of("id", 1001L, "status", 2)
        );
    }
}
```

### 3. 运行时零缝隙原子热更新与 AST 投影自愈 (`DynamicSqlRegistry`)
```java
@Component
public class SqlHotReloadManager {

    @Autowired
    private DynamicSqlRegistry dynamicSqlRegistry;

    public void updateSqlRuntime(String sqlId, String newXmlScript) {
        DynamicSqlDefinition definition = DynamicSqlDefinition.builder()
                .id(sqlId)
                .sqlScript(newXmlScript)
                .commandType(SqlCommandType.SELECT)
                .resultType(UserVO.class)
                .autoScriptWrap(true) // 自动容错包裹 <script>
                .build();

        // 1. 预编译语法校验与 AST 投影字典解析：自动从 SQL 中提取别名字典挂载至 definition.getProjections()
        // 2. 原子覆盖：语法错误时返回 false 并自动保留旧版 SQL，成功则瞬间无感生效
        // 3. 缓存多级协同失效：自动联动清空 DynamicSqlProjectionResolver 与 DynamicQueryTemplate 本地缓存
        boolean success = dynamicSqlRegistry.register(definition);
        if (!success) {
            throw new BusinessException("SQL 语法校验失败，热更新已自动回滚");
        }
    }
}
```

### 4. 接入自定义外部 SQL 加载器 SPI (`ExternalSqlLoader`)
若需从数据库表、Nacos 配置中心、Redis 或远程 HTTP 端点动态拉取 SQL 定义，只需实现 `ExternalSqlLoader` 并注册为 Spring Bean，框架自动装配的 `@ConditionalOnMissingBean` 会无感优先采用业务自定义实现：

```java
@Component
public class DatabaseExternalSqlLoader implements ExternalSqlLoader {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public List<DynamicSqlDefinition> loadAll() {
        // 从自定义配置表读取动态 SQL 元数据
        String sql = "SELECT sql_id, sql_content, command_type FROM sys_dynamic_sql WHERE enabled = 1";
        return jdbcTemplate.query(sql, (rs, rowNum) -> DynamicSqlDefinition.builder()
                .id(rs.getString("sql_id"))
                .sqlScript(rs.getString("sql_content"))
                .commandType(SqlCommandType.valueOf(rs.getString("command_type")))
                .autoScriptWrap(true)
                .build());
    }
}
```

### 5. 事务与 SqlSession 会话一致性机制
- **共享同一事务会话**：`DynamicSqlExecutor` 底层使用的是 Spring 容器受管的 `SqlSessionTemplate`。当业务方法标注 `@Transactional` 时，MyBatis-Plus 的 `ServiceImpl` / `BaseMapper` 与 `DynamicSqlExecutor` 自动绑定到同一个底层数据库事务与连接；
- **读写立即可见**：在同一个 `@Transactional` 方法内，使用 Service 更新实体后紧接着调用 `DynamicSqlExecutor` 查询，可 100% 读到未提交事务内的最新变更；
- **异常原子回滚**：动态 SQL 执行的任何更新、插入或删除操作，均严格遵循 Spring 事务边界，抛出异常时与业务数据一同原子回滚。

### 6. 真实控制台输出与执行契约
```text
10:47:24.730 [main] INFO  i.g.a.b.m.d.DynamicSqlRegistry - 外部动态 SQL [user.findActiveUsers] 成功注册/热更新至持久层容器
==>  Preparing: SELECT id, name, email, status FROM sys_user WHERE (tenant_id = ?) AND (status = ?) AND (name LIKE CONCAT('%', ?, '%')) ORDER BY id DESC LIMIT ?
==> Parameters: 100(Long), 1(Integer), Ateng(String), 10(Integer)
<==      Total: 1
```

---

## 13. 慢 SQL 性能审计与告警通知 (`SlowSqlInterceptor`)

### 1. 痛点场景与最小起步 (Happy Path & Out-of-the-Box)

在高并发生产环境中，由于缺少索引、数据倾斜或全表扫描引发的慢 SQL 是导致数据库连接池打满甚至服务雪崩的核心诱因。传统做法往往依赖运维晚间巡检或盲目开启 `EXPLAIN` 导致数据库负担雪上加霜。

`ateng-boot-mybatis-plus` 原生交付 `SlowSqlInterceptor` 慢 SQL 性能审计拦截器：
- 零侵入开箱即用：基于 MyBatis 原生 `StatementHandler` 精准捕获包含底层 JDBC 交互与网络 IO 的真实执行耗时；
- 零损耗纳秒比对：依托 `System.nanoTime()` 高精度时钟，未超时 SQL 差值比对直接快速放行，零额外对象分配；
- 真实脱敏与美化：超时慢 SQL 触发时自动进行 SQL 多行缩进美化、参数列表脱敏掩码以及首个业务调用栈行号精准定位；
- 生产防雪崩铁律：**严禁在生产线上自动执行 `EXPLAIN`**，仅在告警中输出测试库排查指引与防雪崩提示。

#### 核心配置 (`application.yml`)
```yaml
ateng:
  boot:
    mybatis-plus:
      slow-sql:
        enabled: true           # 是否启用慢 SQL 拦截器，默认 true
        threshold: 1s           # 慢 SQL 判定耗时阈值（支持 500ms、1s 等强类型 Duration），默认 1s
        format-sql: true        # 是否对 SQL 文本进行多行缩进美化，默认 true
        mask-sensitive: true    # 是否对敏感字段与密码凭据进行掩码脱敏，默认 true
```

#### 真实告警日志输出示例
当 SQL 执行耗时超过设定阈值时，自动在控制台打印标准审计告警日志（截取自 `SlowSqlInterceptorTest` 真实运行结果）：

```text
22:42:30.127 [main] WARN  i.g.a.b.m.s.SlowSqlInterceptor - ====== [MyBatis-Plus 慢 SQL 性能审计告警] ======
MappedStatement : io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptorTest$TestUserMapper.insert
实际执行耗时   : 1250 ms (判定阈值: 1000 ms)
代码调用位置   : com.example.service.UserService.createUser(UserService.java:42)
执行线程       : http-nio-8080-exec-1
执行 SQL 语句  :
INSERT INTO t_user ( id, username, password, phone, email ) 
  VALUES ( ?, ?, ?, ?, ? )
绑定参数列表   : [2001, developer, ******, ******, dev@company.com]
排查建议       : 严禁在线上直接执行 EXPLAIN；建议在测试库分析索引命中与扫描行数
===============================================
```

---

### 2. 生产扩展与接管 (Customization & Extensibility)

#### 方案一：实现 `SlowSqlAlertProvider` SPI（推荐用于对接企业群机器人/APM）
下游只需实现 `SlowSqlAlertProvider` 函数式接口并注入为 Spring Bean，即可将慢 SQL 告警事件投递到飞书群机器人、钉钉、企业微信或外部 APM 系统。框架支持注册多个告警提供者并链式遍历通知：

```java
@Component
public class FeishuSlowSqlAlertProvider implements SlowSqlAlertProvider {

    private static final Logger log = LoggerFactory.getLogger(FeishuSlowSqlAlertProvider.class);

    @Override
    public void onSlowSql(SlowSqlEvent event) {
        log.warn("发送飞书慢 SQL 告警通知: StatementId={}, 耗时={}ms, 线程={}",
                event.getStatementId(),
                event.getExecutionTime().toMillis(),
                event.getThreadName());
        // 调用企业飞书机器人 Webhook 投递卡片消息...
    }
}
```

#### 方案二：监听 `SlowSqlEvent` Spring 原生事件（解耦异步分析）
`SlowSqlEvent` 继承 Spring 原生 `ApplicationEvent`，可直接利用 Spring 事件机制进行异步消费与指标统计：

```java
@Component
public class SlowSqlMetricsListener {

    @Async
    @EventListener(SlowSqlEvent.class)
    public void onSlowSqlEvent(SlowSqlEvent event) {
        // 记录 PromQL / Micrometer 慢查询指标计数器或上报数据仓库
        String sql = event.getSql();
        Duration elapsed = event.getExecutionTime();
        String callLocation = event.getCallLocation();
    }
}
```

#### 方案三：完全接管 `SlowSqlInterceptor` Bean
装配类标配 `@ConditionalOnMissingBean(SlowSqlInterceptor.class)`，下游若有特殊代理或深度包装需求，可自由声明自定义 `SlowSqlInterceptor` 实例实现 100% 接管覆盖。

---

### 3. 核心生产边界与非功能语义 (Production Boundary & Invariants)

1. **高精度纳秒时钟与零额外对象损耗**：
   拦截器在 `intercept` 方法内部采用 `System.nanoTime()` 计算纳秒级耗时。正常执行未超时的 SQL 仅在 `finally` 块执行纳秒差值与阈值的原子长整型比较（`elapsedNanos >= thresholdNanos`），**未超时请求绝不触发任何参数提取、正则美化、反射解析与对象分配**，保障高并发交易链路毫秒级放行零开销。
2. **敏感数据自动掩码防线**：
   - 凡实体属性标注持久层加密注解 `@FieldEncrypt`；
   - 参数 TypeHandler 声明为 `EncryptTypeHandler`；
   - 或参数名包含敏感关键字（`password`、`pwd`、`secret`、`token`、`credential`、`privatekey` 等）；
   参数列表一律自动脱敏为 `******`，从源头彻底杜绝明文凭据外泄至日志或告警卡片。
3. **恪守防雪崩铁律严禁自动 `EXPLAIN`**：
   拦截器绝不在慢 SQL 发生时向数据库盲目自动发起 `EXPLAIN ...`。当数据库已处于高负载或死锁排队时，自动 `EXPLAIN` 会加剧连接池衰竭；框架仅以结构化日志给出清晰的测试库分析建议。
4. **业务调用栈精准穿透**：
   内置智能框架类过滤逻辑，主动穿透过滤 JDK 内部、MyBatis 驱动、Tomcat 容器、Spring 代理及测试执行器，精准定位到首个业务 Service 或 Controller 代码行号。
5. **异常容错与弹性降级**：
   告警提供者 SPI 与 Spring 事件发布均处于独立的防卫 `try-catch` 沙箱中。若第三方 Webhook 超时或网络异常，绝不向上抛出异常或阻断业务事务的正常提交。

---

## 14. 原生游标注入与受管流式查询体系 (`SelectCursor` & `StreamingQueryTemplate`)

### 1. 痛点场景与最小起步 (Happy Path & Out-of-the-Box)

在百万级大表导出（如对接 `ateng-boot-excel`）或大规模批处理拉取全量集合时，传统 `selectList` 会一次性将全量数据读入 JVM 堆内存，极易诱发致命的 `OutOfMemoryError` (OOM)；而传统的物理分页（`LIMIT offset, size`）在进入深分页后会导致底层扫描行数爆炸，引发严重的数据库 IO 与慢查询瓶颈。

MyBatis 原生提供了 `Cursor<T>` 游标流式查询能力，但其致命痛点在于：**必须由外层事务维持底层 JDBC 连接在整个迭代期间持续开启**；若开发者忘记显式开启事务、或在业务异常时未闭合游标，极易造成数据库连接池句柄悬挂泄漏与死锁雪崩。

`ateng-boot-mybatis-plus` 原生交付贯穿 **Mapper 注入器 -> BaseMapper -> StreamingQueryTemplate -> BaseService** 的完整受管流式查询体系：
- **原生游标 SQL 注入 (`SelectCursor`)**：无需开发者手写 XML 或 `@Select` 注解，`AtengBaseMapper` 自动获得 `selectCursor(Wrapper<T>)` 方法，底层由框架强制注入 `fetchSize = 1000`，避免 JDBC 客户端缓冲区内存溢出；
- **声明式只读事务守护**：内部由 `TransactionTemplate` 自动开启 `readOnly = true` 事务（`PROPAGATION_REQUIRED`），自适应加入外层事务，确保遍历期间连接有效；
- **确定性生命周期清理**：借助 `try-with-resources` 机制，无论是正常消费完毕、条件提前退出还是下游业务消费器抛出异常，游标 100% 确定性关闭并安全释放物理连接；
- **全方位消费契约**：提供逐条流式（`stream`）、分批切片（`streamBatch`）与提前短路退出（`streamWhile`）；
- **传输层实体隔离 (Zero-Entity-Leakage)**：支持一步直出投影为强类型 VO，彻底杜绝持久层实体泄露至传输层；
- **动态查询游标直出**：原生支持传入 `DynamicQueryRequest` 或业务 DTO 动态条件进行游标流式遍历（`streamDynamic*`）。

#### 方式一：直接使用 `AtengBaseService` 原生受管流式门面（推荐）
继承 `AtengServiceImpl` 的业务 Service 自动获得受管流式查询能力：

```java
@Service
public class UserServiceImpl extends AtengServiceImpl<UserMapper, UserEntity> implements UserService {

    // 1. 逐条流式消费（内存占用仅单对象级别）
    public void exportUsers() {
        stream(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getStatus, 1),
                user -> {
                    // 业务逻辑处理当前用户实体
                }
        );
    }

    // 2. 分批切片消费（默认或指定批次大小，传出 Collections.unmodifiableList 防卫脏写）
    public void pushUsersInBatches() {
        streamBatch(
                Wrappers.<UserEntity>lambdaQuery().gt(UserEntity::getAge, 18),
                1000,
                batchList -> {
                    // 分批次批量推送或远程 RPC 调用，单批消费完毕立即 GC
                }
        );
    }

    // 3. 提前短路退出（当满足特定业务条件时提前中断遍历，返回 false 立即停止迭代）
    public void scanUntilFound() {
        streamWhile(
                Wrappers.<UserEntity>lambdaQuery().orderByAsc(UserEntity::getId),
                user -> {
                    if ("TARGET_CODE".equals(user.getCode())) {
                        log.info("已命中目标用户: {}", user.getId());
                        return false; // 提前退出游标遍历，立即触发资源清理
                    }
                    return true; // 继续下一条遍历
                }
        );
    }
}
```

#### 方式二：零实体泄露的强类型 VO 投影流式查询
严格遵循 [ADR-0010](./docs/adr/0010-transport-pagination-contract-decoupling.md) 契约，直接流式直出强类型 VO：

```java
// 1. 强类型 VO 逐条流式
userService.stream(
        Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getStatus, 1),
        UserExportVO.class,
        vo -> excelStreamWriter.write(vo)
);

// 2. 强类型 VO 分批流式
userService.streamBatch(
        Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getStatus, 1),
        500,
        UserExportVO.class,
        voBatchList -> thirdPartyClient.batchSync(voBatchList)
);
```

#### 方式三：动态请求直出流式遍历 (`streamDynamic*`)
配合 `DynamicQueryRequest` 或挂载 `@QueryField` 的业务查询 DTO，一步直出：

```java
DynamicQueryRequest request = new DynamicQueryRequest();
request.addCondition("status", QueryOperator.EQ, 1);
request.addAsc("id");

// 动态条件流式直出 VO
userService.streamDynamic(
        request,
        UserExportVO.class,
        vo -> processExport(vo)
);
```

#### 方式四：直接使用 Mapper 与底层 `StreamingQueryTemplate`
若在非 Service 上下文或自定义复杂 Mapper XML 查询中，可直接调用 `StreamingQueryTemplate`：

```java
@Autowired
private StreamingQueryTemplate streamingQueryTemplate;
@Autowired
private UserMapper userMapper;

public void process() {
    streamingQueryTemplate.forEach(
            () -> userMapper.selectCursor(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getStatus, 1)),
            entity -> handle(entity)
    );
}
```

---

### 2. 生产扩展与接管 (Customization & Extensibility)

#### 方案一：执行自定义聚合计算 (`execute`)
当需要对游标数据执行自定义聚合、求和或带返回值的复杂流式计算时，可使用通用 `execute` 契约：

```java
Long maxId = streamingQueryTemplate.execute(
        () -> userMapper.selectCursor(Wrappers.emptyWrapper()),
        cursor -> {
            long max = -1L;
            for (UserEntity item : cursor) {
                if (item.getId() > max) {
                    max = item.getId();
                }
            }
            return max;
        }
);
```

#### 方案二：基于独立只读数据源或自定义 `TransactionTemplate` 接管
自动装配标配 `@ConditionalOnMissingBean(StreamingQueryTemplate.class)`，下游若需指定专用的只读从库事务管理器或自定义事务传播配置，可直接声明 Bean 覆盖：

```java
@Configuration
public class CustomStreamingConfig {

    @Bean
    public StreamingQueryTemplate customStreamingQueryTemplate(
            @Qualifier("readOnlyTransactionManager") PlatformTransactionManager readOnlyTm) {
        return new StreamingQueryTemplate(readOnlyTm);
    }
}
```

---

### 3. 核心生产边界与非功能语义 (Production Boundary & Invariants)

1. **确定性生命周期与零连接悬挂**：
   无论下游业务 `Consumer` 正常执行结束、`Predicate` 提前返回 `false` 中断，还是中途抛出受控/非受控异常，`StreamingQueryTemplate` 的 `try-with-resources` 守卫均保证在第一时间内调用 `Cursor.close()`，紧接着由事务管理器归还底层物理连接至连接池，彻底消灭连接泄露隐患。
2. **提前短路退出安全性 (`streamWhile` / `forEachWhile`)**：
   业务判断满足终止条件时返回 `false`，迭代器立即 break 中断循环并触发 `finally` 中的游标关闭与连接归还，绝不残留未读完的 JDBC 游标会话。
3. **因果异常传递与原子回滚**：
   当下游消费逻辑抛出业务异常（如 `BusinessException` 或其他 `RuntimeException`）时，模板内部在 `catch` 中显式调用 `status.setRollbackOnly()` 触发只读事务安全回滚，并**原样向外透传原始异常**，绝不吞咽异常或二次包装污染因果链。
4. **JDBC FetchSize 强制注入防内存溢出**：
   `SelectCursor` 注入器生成 `MappedStatement` 时强制注入 `fetchSize = 1000`。底层 JDBC 驱动按批次从网络流中拉取数据，避免千万级记录一次性加载到网络 Socket 缓冲区造成物理机器内存打满。
5. **切片数据不可变性防卫**：
   `streamBatch` / `forEachBatch` 传出的每批 `List<T>` 均经由 `Collections.unmodifiableList` 封装，防止下游误操作对其执行 `add` / `remove` 导致数据脏读，并在每批消费完毕后立即解除引用协助 JVM 极速 GC。
6. **未装配快速失败防卫**：
   若业务环境中未注入 `StreamingQueryTemplate`（例如未启用 Spring 事务管理器或配置排除了装配），调用 `AtengBaseService` 流式方法时立即抛出语义清晰的 `IllegalStateException`，Fail-Fast 阻断潜在运行期风险。

---

## 15. 多数据源协同诊断探针与上下文守卫 (`DynamicDatasourceGuard`)

### 1. 痛点场景与最小起步 (Happy Path & Out-of-the-Box)

在引入第三方动态切源框架（如 `dynamic-datasource-spring-boot-starter` 或自定义 `AbstractRoutingDataSource`）的场景下，业务系统常面临以下致命痛点：
1. **活跃事务假切源串库**：Spring 声明式事务在开启后会固定绑定物理连接，若在 `@Transactional` 事务内部切换数据源，切源会被底层物理连接无声覆盖导致更新操作写错数据源；
2. **多租户越权/报错**：多租户拦截器默认改写所有数据源 SQL，导致公共库或报表库被强行拼接 `tenant_id = ?` 报错；
3. **数据权限跨库穿透**：上一数据源未及时清理的超管状态（`isSuperAdmin=true`）在切换到新库后引发越权穿透。

`ateng-boot-mybatis-plus` 原生交付 `DynamicDatasourceGuard`：
- **启动期无感健康诊断探针**：基于 Spring 原生 `SmartInitializingSingleton` 自动感知多数据源环境，输出持久层协同自检建议；
- **编程式上下文隔离断言**：提供 `validateContextIsolated()` 静态断言门面，供切源切面或业务边界进行 Fail-Fast 快速防卫。

#### 核心配置 (`application.yml`)
```yaml
ateng:
  boot:
    mybatis-plus:
      guard:
        dynamic-datasource: true   # 是否启用多数据源协同诊断探针，默认 true
```

#### 启动期健康诊断日志输出示例
当检测到容器中存在多个 `DataSource` 或动态路由数据源时，自动在控制台打印标准诊断排查建议：

```text
23:09:25.120 [main] INFO  i.g.a.b.m.g.DynamicDatasourceGuard - ====== [ateng-boot-mybatis-plus 多数据源协同诊断探针] ======
23:09:25.120 [main] INFO  i.g.a.b.m.g.DynamicDatasourceGuard - 环境特征: 检测到 Spring 容器中注册了 2 个 DataSource Bean: [masterDataSource, slaveDataSource]
23:09:25.120 [main] INFO  i.g.a.b.m.g.DynamicDatasourceGuard - 协同自检建议:
23:09:25.120 [main] INFO  i.g.a.b.m.g.DynamicDatasourceGuard - 1. [行级多租户] 若启用多租户拦截器，请核查非业务库（如公共字典库、定时任务库、报表从库）是否已通过 ignore-tables 或 @IgnoreTenant 声明放行；
23:09:25.120 [main] INFO  i.g.a.b.m.g.DynamicDatasourceGuard - 2. [事务切源禁忌] Spring 声明式事务在开启后会固定绑定物理连接，严禁在 @Transactional 事务方法内部执行切源操作，否则切源将静默失效；
23:09:25.120 [main] INFO  i.g.a.b.m.g.DynamicDatasourceGuard - 3. [上下文隔离] 跨数据源访问前，建议调用 DynamicDatasourceGuard.validateContextIsolated() 校验当前线程事务与数据权限上下文纯净性。
23:09:25.120 [main] INFO  i.g.a.b.m.g.DynamicDatasourceGuard - ==============================================================
```

---

### 2. 生产扩展与接管 (Customization & Extensibility)

#### 方案一：在切源切面或业务边界主动调用静态断言
在自定义切源切面（如处理 `@DS` 注解的前置通知）或跨库 RPC 边界中，直接调用断言防卫：

```java
@Aspect
@Component
public class DynamicDataSourceAspect {

    @Before("@annotation(ds)")
    public void beforeSwitchDataSource(JoinPoint point, DS ds) {
        // 1. 切换数据源前，校验当前线程是否处于隔离纯净状态
        DynamicDatasourceGuard.validateContextIsolated();

        // 2. 若需严格匹配当前租户与期望租户，可传入期望租户 ID 进行强校验
        // DynamicDatasourceGuard.validateContextIsolated(expectedTenantId);

        // 3. 执行数据源切换上下文绑定...
    }
}
```

#### 方案二：完全接管 `DynamicDatasourceGuard` Bean
装配类标配 `@ConditionalOnMissingBean(DynamicDatasourceGuard.class)`，下游若有特殊多数据源探针逻辑，可自由声明自定义 Bean 实现 100% 接管覆盖。

---

### 3. 核心生产边界与非功能语义 (Production Boundary & Invariants)

1. **零臃肿与不重造轮子**：
   本组件恪守纯原生与底座优先原则，绝不引入或重造多数据源路由轮子；纯粹依托 Spring 原生 `SmartInitializingSingleton` 与 `TransactionSynchronizationManager`，针对持久层在多源环境下的痛点实施外围防卫与诊断。
2. **活跃事务假切源主动阻断**：
   当检测到 `TransactionSynchronizationManager.isActualTransactionActive() == true` 时，`validateContextIsolated()` 立即抛出 `IllegalStateException`，杜绝调用方在事务内无意识切源导致假切源和串库更新。
3. **数据权限未闭合残留防卫**：
   若当前线程残留有 `DataPermissionContextHolder.isSuperAdmin() == true` 或 `isIgnore() == true`（通常是前置任务异常且未在 `finally` 块清理导致），在跨数据源操作时立即触发阻断，防御越权风险。
4. **显式污染标记与安全恢复**：
   支持通过 `DynamicDatasourceGuard.markContextTainted(reason)` 与 `clearTaintedContext()` 对线程上下文实施受控标记与恢复，方便在分布式链路或拦截器管道中传递上下文异常状态。

---

## 16. 动态查询安全转译引擎与 URL 冒号高阶语法解析器 (`DynamicQueryHelper` & `DynamicQueryParser`)

### 1. 痛点场景与最小起步 (Happy Path & Out-of-the-Box)

在后台管理系统与数据检索场景中，前端检索表单往往高度动态（多字段组合、范围筛选、模糊匹配、多列 OR 联合过滤、排序定制）。传统开发面临两难困境：
1. **XML / 硬编码冗长脆弱**：手写海量 `<if test="...">` 或在 Service 层不断 `wrapper.eq().like()`，不仅样板代码泛滥，且字段增减极易引发遗漏；
2. **SQL 注入与敏感探测漏洞**：若直接透传前端拼接的 SQL 片段，极易产生 SQL 注入；若直接通过未经白名单校验的反射，前端可通过遍历字段试探系统内存与敏感字段（如 `password`、`secretKey` 等）。

`ateng-boot-mybatis-plus` 原生提供 **工业级动态查询安全转译引擎与 URL Query 冒号高阶语法解析器**：
- **六重安全防卫 (The 6-Layer Security Guard)**：
  1. 字段白名单强校验：仅允许目标实体显式声明的持久化属性，未声明属性直接安全忽略并记录警告；
  2. 敏感黑名单拦截：严格阻断 `@DynamicQueryIgnore` 标注字段、`@FieldEncrypt` 加密字段、`password` / `secret` 等敏感列及基类逻辑删除字段；
  3. 13 种受控操作符收敛：仅开放 `EQ`、`NE`、`GT`、`GE`、`LT`、`LE`、`LIKE`、`LIKE_LEFT`、`LIKE_RIGHT`、`IN`、`NOT_IN`、`BETWEEN`、`IS_NULL`、`IS_NOT_NULL` 白名单操作符；
  4. 语法字符与 SQL 注入过滤：字段名必须完全符合 `[a-zA-Z0-9_]+` 安全标识符规范，坚决拦截 `;`、`--`、`'`、`()`、空格等非法注入字符；
  5. 数组与范围边界深度防穿透：`BETWEEN` 强制校验双边界有效性（优先采用 `~` 波浪线防错切分，避免时间格式空格冒号歧义），`IN` 自动过滤空元素与非法字符；
  6. 空值安全保底：单实体或无匹配查询严格保底，杜绝产生畸形 SQL。
- **作用域 OR 嵌套分组**：支持 `or_组号` 语法前缀，同组条件自动聚合为独立嵌套的 `AND (col1 = ? OR col2 = ?)` 块，多组间自然以 `AND` 并列；
- **纯原生无第三方依赖**：100% 依托 JDK 17 与 MyBatis-Plus 原生条件构造器，零臃肿依赖。

#### 场景一：直接解析 HTTP URL Query 冒号高阶语法 (Controller 极速体验)

```java
@GetMapping("/search")
public Result<List<UserVO>> search(HttpServletRequest request) {
    // 1. 传入 URL Query 字符串（如 "name:like=张&status:eq=1&age:between=20~30"）
    QueryWrapper<UserEntity> wrapper = DynamicQueryParser.parseToWrapper(
            request.getQueryString(), 
            UserEntity.class
    );

    // 2. 传递给 Mapper / Service 执行原生安全查询
    List<UserEntity> list = userMapper.selectList(wrapper);
    return Result.success(UserVO.fromList(list));
}
```

#### 场景二：Map 参数驱动构建

```java
Map<String, String[]> parameterMap = request.getParameterMap();
DynamicQueryRequest queryRequest = DynamicQueryParser.parse(parameterMap);
QueryWrapper<UserEntity> wrapper = DynamicQueryHelper.buildWrapper(queryRequest, UserEntity.class);
```

#### 场景三：作用域 OR 分组语法 (`or_组号:`)

当前端需要跨字段关键字检索时（例如：同时匹配姓名或邮箱，且必须满足账户处于激活状态）：
- 请求参数：`or_1:name:like=张&or_1:email:like=zhang&status:eq=1`
- 自动编译生成的标准 SQL：
  ```sql
  WHERE (name LIKE ? OR email LIKE ?) AND status = ?
  ```

---

### 2. 生产扩展与接管 (Customization & Extensibility)

#### 方案一：业务 DTO 声明式驱动 (`@QueryField`)

除了直接解析 URL Query 外，系统支持通过业务 DTO 挂载 `@QueryField` 声明式转译：

```java
@Data
public class UserSearchDTO {

    @QueryField(operator = QueryOperator.LIKE)
    private String name;

    @QueryField(property = "userAge", operator = QueryOperator.GE)
    private Integer minAge;

    @QueryField(operator = QueryOperator.IN)
    private List<Integer> status;
}

// Service 层一行代码安全转译：
QueryWrapper<UserEntity> wrapper = DynamicQueryHelper.buildWrapper(dto, UserEntity.class);
```

#### 方案二：编程式链式与作用域 OR 构建

```java
DynamicQueryRequest request = new DynamicQueryRequest()
        .eq("status", 1)
        .or("g1", "name", QueryOperator.LIKE, "张")
        .or("g1", "email", QueryOperator.LIKE, "zhang");

QueryWrapper<UserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, UserEntity.class);
```

---

---

### 3. 通用受管执行门面与 Mapper XML 联表方法引用直出 (`DynamicQueryTemplate` & Method References)

在实际企业级业务中，查询往往涉及多表联表（如用户关联部门、角色、机构）或既有历史 Mapper XML。传统方式需要开发者手写 Wrapper 并手动调用 `PageUtil.toPageResult` 逐条转换 VO。

`ateng-boot-mybatis-plus` 原生提供 **通用受管执行门面 `DynamicQueryTemplate`** 并深度赋能 `AtengBaseService`，支持方法引用强类型直出与 AST 别名自愈：

#### 场景一：基于自定义 Mapper 联表方法引用极速直出 (强烈推荐)

```java
// 1. Mapper 接口定义多表联表分页方法（支持在 XML 中编写多表 JOIN）
public interface UserMapper extends AtengBaseMapper<UserEntity> {
    IPage<UserDeptVO> selectUserDeptPage(IPage<UserDeptVO> page, @Param(Constants.WRAPPER) Wrapper<UserDeptVO> wrapper);
    List<UserDeptVO> selectUserDeptList(@Param(Constants.WRAPPER) Wrapper<UserDeptVO> wrapper);
}

// 2. Service 业务层通过方法引用一行直出（纯 POJO 零注解 VO 投影与物理分页）
@Service
public class UserServiceImpl extends AtengServiceImpl<UserMapper, UserEntity> implements UserService {

    public PageResult<UserDeptVO> queryUserDeptPage(DynamicQueryRequest request) {
        // 自动完成：AST 投影别名自愈（如 deptName -> d.name） + 物理分页 + VO 强类型投影返回
        return pageDynamic(baseMapper::selectUserDeptPage, request, UserDeptVO.class);
    }

    public List<UserDeptVO> queryUserDeptList(DynamicQueryRequest request) {
        return listDynamic(baseMapper::selectUserDeptList, request, UserDeptVO.class);
    }
}
```

#### 场景二：基于短方法名直接调用

```java
// 支持直接通过短方法名执行动态分页/列表查询
PageResult<UserDeptVO> page = userService.pageDynamic("selectUserDeptPage", request, UserDeptVO.class);
List<UserDeptVO> list = userService.listDynamic("selectUserDeptList", request, UserDeptVO.class);
UserDeptVO one = userService.getOneDynamic("selectUserDeptList", request, UserDeptVO.class);
```

#### 场景三：注入 `DynamicQueryTemplate` 编程式门面调用

```java
@Autowired
private DynamicQueryTemplate dynamicQueryTemplate;

// 1. 单表实体分页直出并投影为 VO
PageResult<UserVO> page = dynamicQueryTemplate.selectPage(request, UserEntity.class, UserVO.class);

// 2. 业务 DTO 驱动的单表实体分页
PageResult<UserVO> pageByDto = dynamicQueryTemplate.selectPage(searchDto, UserEntity.class, UserVO.class);

// 3. 基于 StatementId 的外部/XML SQL 统计与单条查询
UserDeptVO vo = dynamicQueryTemplate.selectOne("selectUserDeptList", request, UserDeptVO.class);
long activeCount = dynamicQueryTemplate.count("selectUserDeptList", request);

// 4. 基于 StatementId 与单表实体的物理短路存在性判断
boolean hasUser = dynamicQueryTemplate.exists("selectUserDeptList", request);
boolean hasAdmin = dynamicQueryTemplate.exists(request, UserEntity.class);
```

#### 场景四：动态查询存在性判断与单页物理短路 (`existsDynamic`)

在很多业务前置校验中（如注册排重、状态流转校验、关联引用前置探测），只需知道是否存在满足条件的记录，传统 `count > 0` 会迫使数据库全表扫描执行高昂的 `COUNT(*)`。

`ateng-boot-mybatis-plus` 原生提供单表与多表复杂联表的物理短路判定：
1. **单表存在性 (`existsDynamic`)**：底层安全编译为 `QueryWrapper` 并调用 `baseMapper.exists`，生成原生 `SELECT 1 ... LIMIT 1` 物理短路；
2. **多表复杂联表存在性 (`existsDynamic`)**：支持基于方法引用（`baseMapper::selectUserDeptPage`）或短方法名（`"selectUserDeptPage"`），通过单页 `Page(1, 1, false)`（关闭 count 查询并追加物理 `LIMIT 1`）瞬间判定是否存在匹配记录，零多余 IO。

```java
@Service
public class UserServiceImpl extends AtengServiceImpl<UserMapper, UserEntity> implements UserService {

    // 1. 单表动态请求存在性判断（SELECT 1 FROM t_user WHERE status = 1 LIMIT 1）
    public boolean checkUserActive(DynamicQueryRequest request) {
        return existsDynamic(request);
    }

    // 2. 单表业务 DTO 驱动存在性判断
    public boolean checkUserExists(UserSearchDTO dto) {
        return existsDynamic(dto);
    }

    // 3. 多表联表方法引用存在性判断（单页物理短路 LIMIT 1，免除 COUNT(*) 慢查询）
    public boolean checkUserDeptExists(DynamicQueryRequest request) {
        return existsDynamic(baseMapper::selectUserDeptPage, request);
    }

    // 4. 多表短方法名与 DTO 驱动存在性判断
    public boolean checkUserDeptExistsByDto(UserSearchDTO dto) {
        return existsDynamic("selectUserDeptPage", dto);
    }
}
```

#### 场景五：零持久层实体泄露基础查询 VO 投影与严格单条查询

严格遵循 [ADR-0010](./docs/adr/0010-transport-pagination-contract-decoupling.md) 传输契约与持久层解耦铁律，`AtengBaseService` 为基础主键与单条查询提供一步直出强类型 VO 门面，彻底杜绝持久层实体（Entity）穿透至传输层：

```java
// 1. 主键单条查询一步直出 VO（查无记录或 id 为 null 时安全保底返回 null）
UserVO userVo = userService.getById(1L, UserVO.class);

// 2. 主键批量查询一步直出 VO 列表（查无记录或入参为空时保底返回 Collections.emptyList()，严禁返回 null）
List<UserVO> userVoList = userService.listByIds(List.of(1L, 2L, 3L), UserVO.class);

// 3. Wrapper 条件列表查询一步直出 VO 列表
List<UserVO> activeUsers = userService.list(
        Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getStatus, 1),
        UserVO.class
);

// 4. 严格单条查询（throwEx = true：多条记录匹配时严正抛出 TooManyResultsException 快速失败）
UserVO singleVo = userService.getOneDynamic(request, UserVO.class, true);
UserVO singleDtoVo = userService.getOneDynamic(dto, UserVO.class, true);

// 5. 宽松单条查询（throwEx = false：多条记录匹配时宽容返回首条）
UserVO firstVo = userService.getOneDynamic(request, UserVO.class, false);
```

#### 场景六：Mapper XML 占位符原生绑定与 JSqlParser AST 自适应兼容

1. **显式占位符（推荐）**：在 Mapper XML 中声明 `${ew.customSqlSegment}`，框架自动完成安全的参数化绑定：
   ```xml
   <select id="selectUserDeptPage" resultType="io.github.atengk.boot.sample.vo.UserDeptVO">
       SELECT u.id, u.username, u.age, d.name AS dept_name, d.code AS dept_code
       FROM t_user u
       LEFT JOIN t_dept d ON u.dept_id = d.id
       ${ew.customSqlSegment}
   </select>
   ```
2. **零改动自适应兼容**：对于未声明 `${ew.customSqlSegment}` 的既有普通 XML SQL，框架通过 JSqlParser AST 自适应改写动态注册，无需修改历史 XML 即可享受动态查询与表别名自愈。

---

### 4. 核心生产边界与非功能语义 (Production Boundary & Invariants)

1. **纳秒级元数据与 AST 投影双缓存**：
   - 目标实体的白名单字段、`@DynamicQueryIgnore` 忽略标记、加密注解解析结果永久驻留于 `ConcurrentHashMap`；
   - Mapper XML SQL 语句经 JSqlParser 解析生成的物理列映射字典（`DynamicSqlProjectionResolver`）以 StatementId 为主键进行纳秒级缓存，彻底杜绝重复 AST 语法树解析开销。
2. **纯 POJO 零注解与“零实体泄露”铁律 (Zero-Entity-Leakage)**：
   传输层请求（`DynamicQueryRequest` / DTO）与目标 VO 均为 100% 纯 POJO，无需在 VO 上堆砌任何 `@TableField` 或别名映射注解；底层通过 AST 解析自动抹平表别名差异，出参严格返回 `PageResult<VO>`，严禁 ORM 实体越界穿透至 Controller。
3. **零次生异常与空安全保底契约 (Null Safety & Defensive Return)**：
   - `DynamicQueryParser` 对任何畸形语法字符、非法操作符均由前置卫语句安全忽略，绝不触发未捕获异常导致 HTTP 500；
   - 查询列表或无匹配记录时统一返回保底空集合（`Collections.emptyList()`）或空分页（`PageResult.empty()`），严禁返回 `null`。
4. **波浪线 `~` 语法防错保障**：
   针对时间字符串范围查询（如 `2026-01-01 00:00:00~2026-12-31 23:59:59`），推行波浪线 `~` 作为 Between 边界切分符，彻底消除时分秒空格与冒号歧义。
5. **与 `@DataScope` 数据权限及行级多租户拦截器链正交协同机制**：
   - **拦截器时序与 AST 语法树层级叠加**：动态查询编译生成的 `Wrapper` / 自适应改写 SQL 进入 MyBatis 执行链路时，与 `AtengTenantLineInnerInterceptor`（行级租户）及 `AtengDataPermissionInnerInterceptor`（数据权限）按规范时序正交协同；
   - **自适应 MappedStatement 与注解防丢失**：针对动态自适应代理方法（`$dynamicAdaptive`）与分页统计方法（`_mpCount`），拦截器通过方法名清洗策略剥离动态后缀并安全穿透反射提取原始 Mapper 接口上的 `@DataScope` 注解；租户隔离条件与用户/部门数据权限条件以 `AND (...)` 形式无缝注入 AST 根节点，绝不发生条件覆盖、短路或 SQL 语法断裂（已在 `SampleDynamicQueryIntegrationTest` 全真端到端闭环验证）。

---

## 17. 数据变更 Diff 审计拦截器与三级开关治理 (`AuditChange` & `DataChangeAuditProvider`)

### 场景痛点
在金融、电商交易及核心业务系统中，追踪“谁在什么时间把什么数据的哪个字段从什么值改成了什么值”是合规审计的核心诉求。传统方案要么依赖应用层手写比对侵入业务，要么依赖数据库 CDC（Canal/Debezium）无法捕获当前登录操作人、租户 ID 与全链路 TraceId。

`ateng-boot-mybatis-plus` 提供纯原生 MyBatis 拦截器 `DataChangeAuditInterceptor` 与声明式 `@AuditChange` 注解，捕获 UPDATE/DELETE 前置快照并自动生成字段级 Diff 增量记录。

---

### 1. 最小起步体验 (Happy Path & Out-of-the-Box)

#### 步骤一：实体声明 `@AuditChange`
```java
@Getter
@Setter
@TableName("t_user")
@AuditChange(description = "用户资料变更")
public class UserEntity extends BaseEntity {
    private String name;
    private String email;
    private Integer age;
}
```

#### 步骤二：启用变更审计配置
```yaml
ateng:
  boot:
    mybatis-plus:
      audit-change:
        enabled: true             # 启用变更审计拦截器
        max-diff-rows: 100        # 单次更新最大 Diff 计算行数阈值（默认 100 行）
```

#### 步骤三：注册审计消费 SPI（或监听 Spring 事件）
```java
@Component
public class ConsoleDataChangeAuditProvider implements DataChangeAuditProvider {

    private static final Logger log = LoggerFactory.getLogger(ConsoleDataChangeAuditProvider.class);

    @Override
    public void onDataChanged(List<DataChangeRecord> records) {
        for (DataChangeRecord record : records) {
            log.info("捕获数据变更: 表名={}, ID={}, 操作人={}, 变动字段数={}",
                    record.getTableName(), record.getEntityId(), record.getOperator(), record.getDiffs().size());
            for (FieldDiff diff : record.getDiffs()) {
                log.info("  字段 [{}] 由 [{}] 变更为 [{}]", diff.getPropertyName(), diff.getOldValue(), diff.getNewValue());
            }
        }
    }
}
```

---

### 2. 生产扩展与接管 (Customization & Extensibility)

#### 方案一：字段级包含与排除微调
对于频繁更新的高频时间戳、操作人或敏感密码凭据，可通过 `includeFields` 与 `excludeFields` 显式控制：
```java
@AuditChange(
    includeFields = {"email", "phone"},  // 仅关心核心联系方式变动
    excludeFields = {"updateTime", "updateBy"} // 排除审计字段干扰
)
public class UserSecurityEntity {
    private String email;
    private String phone;
    private LocalDateTime updateTime;
    private String updateBy;
}
```

#### 方案二：基于 Spring ApplicationEvent 解耦广播
除了 SPI 接口外，拦截器在变更提交后默认发布 `DataChangeEvent`，下游可在任意 Spring Bean 中通过 `@EventListener` 异步消费：
```java
@Component
public class AuditEventListener {

    @EventListener
    public void handleDataChange(DataChangeEvent event) {
        List<DataChangeRecord> records = event.getRecords();
        // 投递至 Elasticsearch、消息队列或日志审计中心
    }
}
```

---

### 3. 核心生产边界与非功能语义 (Production Boundary & Invariants)

1. **三级立体开关治理与零开销放行**：
   - **第一级（全局配置）**：`audit-change.enabled=false` 时拦截器完全不生效；若容器中缺 `DataChangeAuditProvider` 且未开启 `publishEvent` 时自动休眠，绝不发起任何前置快照查询；
   - **第二级（类与方法注解）**：仅对标注了 `@AuditChange` 的实体或 Mapper 方法生效，普通未标注实体零快照开销直接 `proceed()` 放行；
   - **第三级（编程式闭包）**：业务批量洗数据、系统数据初始化或系统内部补偿时，可通过 `DataChangeAuditContextHolder.runWithoutAudit(() -> { ... })` 线程安全跳过审计抓取。
2. **防爆熔断守卫 (Max Diff Rows Circuit Breaker)**：
   若批量更新或条件更新受影响行数超过配置阈值 `max-diff-rows`（默认 100 行），拦截器立即停止逐行抓取快照与逐行 Diff 比对，自动熔断降级为摘要记录（`degraded = true`，记录表名、影响行数与操作人），从根源杜绝深层复杂 SQL 批量更新导致内存爆表 OOM。
3. **事务提交后投递不变量 (Commit-Only Dispatch)**：
   通过 Spring 原生 `TransactionSynchronizationManager` 注册 `afterCommit` 回调，确保变更记录仅在当前数据库事务**真正提交成功后**才投递至 SPI 或发布事件；若业务事务发生异常回滚（Rollback），审计记录自动作废丢弃，绝不向消费端投递“幻影变更数据”。
4. **旁路容错不扰业务 (Non-Intrusive Isolation)**：
   变更审计属于系统旁路保障功能，拦截器内部包含严密的前置卫语句与全局异常兜底拦截。即使前置快照因特殊表结构失败，拦截器仅打印告警日志并安全降级，绝不影响主业务事务的正常执行与持久化。

---

## 18. 模块配置属性参考 (`application.yml`)

```yaml
ateng:
  boot:
    mybatis-plus:
      enabled: true               # 是否启用持久层增强装配（默认 true）
      batch:
        default-size: 1000        # 原生批量插入默认单批次切片容量（默认 1000）
      audit:
        default-operator: "SYSTEM"# 无法获取操作人时的审计兜底操作人标识
      pagination:
        enabled: true             # 是否装配通用分页插件（默认 true）
        max-limit: 500            # 单页允许查询的最大记录上限（默认 500）
        overflow: false           # 溢出总页数后是否进行处理（默认 false，继续请求）
        db-type: ""               # 数据库方言类型（默认留空，框架自动推导）
      block-attack:
        enabled: true             # 是否装配防全表更新删除插件（默认 true）
      optimistic-locker:
        enabled: true             # 是否装配乐观锁插件（默认 true）
      tenant:
        enabled: false            # 是否启用行级多租户插件（默认 false，按需开启）
        column: "tenant_id"       # 数据库租户列字段名（默认 tenant_id）
        ignore-tables:            # 全局免多租户过滤的静态数据表名列表
          - "sys_dict"
          - "sys_config"
      data-permission:
        enabled: false            # 是否启用细粒度数据权限拦截器（默认 false，按需开启）
      crypto:
        secret-key: "YourSecretKey" # AES 存储透明加密密钥（可选，未配时自适应默认内置密钥）
      snowflake:
        enabled: true             # 是否启用雪花发号桥接（默认 true）
        worker-id: 1              # 工作节点编号（0~31，未配置时四级自适应推导）
        datacenter-id: 1          # 数据中心编号（0~31，未配置时四级自适应推导）
      dynamic-sql:
        enabled: true             # 是否启用外部动态 SQL 增强引擎（默认 false，按需开启）
        auto-script-wrap: true    # 是否自动为未包裹 <script> 根标签的动态 SQL 容错包裹（默认 true）
        locations:                # 默认外部 XML 资源扫描路径
          - "classpath*:dynamic-sql/**/*.xml"
      slow-sql:
        enabled: true             # 是否启用慢 SQL 审计拦截器（默认 true）
        threshold: 1s             # 慢 SQL 判定耗时阈值（默认 1s）
        format-sql: true          # 是否对 SQL 文本进行多行美化缩进（默认 true）
        mask-sensitive: true      # 是否对敏感字段与密码凭据进行掩码脱敏（默认 true）
      guard:
        dynamic-datasource: true   # 是否启用多数据源协同诊断探针（默认 true）
      dynamic-query:
        enabled: true             # 是否启用动态查询转译引擎（默认 true）
      audit-change:
        enabled: false            # 是否启用数据变更 Diff 审计拦截器（默认 false，按需开启）
        max-diff-rows: 100        # 单次更新最大 Diff 计算行数阈值（默认 100 行），超限时自动熔断降级
        async: true               # 是否在事务提交后异步调度投递审计记录（默认 true）
        publish-event: true       # 是否发布 Spring DataChangeEvent 事件（默认 true）
```





