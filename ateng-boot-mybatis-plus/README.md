# ateng-boot-mybatis-plus 持久层增强底座

> 提供实体五级阶梯体系、审计自动填充、行级多租户隔离、AST 声明式细粒度数据权限、原生高效批量插入、存储透明加密与视图动态脱敏。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **实体体系** | 泛型主键实体基类 | `BaseGenericEntity<ID>` | - | 规范实体主键类型定义与强类型 `getId()` / `setId(ID)` | `BaseEntityTest` |
| **实体体系** | 雪花 ID 主键基类 | `BaseIdEntity` | `ateng.boot.mybatis-plus.snowflake.*` | 标配 64 位 Long 主键与 `@TableId(type = IdType.ASSIGN_ID)` | `BaseEntityTest` / `SampleAppTest` |
| **实体体系** | 时间流转基类 | `BaseTimeEntity` | `ateng.boot.mybatis-plus.audit.*` | 提供 `createTime` (`FieldFill.INSERT`) 与 `updateTime` (`FieldFill.INSERT_UPDATE`) | `BaseEntityTest` |
| **实体体系** | 四要素全审计业务基类 | `BaseEntity` | `ateng.boot.mybatis-plus.audit.*` | 提供 `createBy`、`updateBy`、`createTime`、`updateTime` 四要素全审计 | `BaseEntityTest` / `SampleTenantTest` |
| **实体体系** | 全要素状态业务基类 | `BaseFullEntity` | `ateng.boot.mybatis-plus.optimistic-locker.*` | 在审计基类上追加 `@Version private Integer version` (乐观锁) 与 `@TableLogic @JsonIgnore private Integer deleted` (逻辑删除) | `BaseEntityTest` / `SampleAppTest` |
| **实体体系** | 唯一索引防冲突占位基类 | `BaseDeleteIdEntity` | - | 继承 `BaseFullEntity`，`deleted` 为 `BIGINT` 并在删除时以雪花 ID 占位，彻底杜绝联合唯一索引逻辑删除冲突 | `BaseDeleteIdEntityTest` / `BaseEntityTest` |
| **实体体系** | 多租户实体标记接口 | `TenantEntity` | `ateng.boot.mybatis-plus.tenant.*` | 声明 `getTenantId()` / `setTenantId(String)` 租户标识契约 | `SampleTenantTest` |
| **自动填充** | 防御性审计元数据填充 | `DefaultMetaObjectHandler` | `ateng.boot.mybatis-plus.audit.enabled`<br>`ateng.boot.mybatis-plus.audit.default-operator` | 插入/更新自动提取操作人并填充审计字段，若已显式赋值则绝不覆盖覆盖 | `DefaultMetaObjectHandlerTest` / `SampleAppTest` |
| **审计贯通** | 当前操作人上下文提供者 | `CurrentUserProvider` | - | Core 统一操作人 SPI，驱动 `DefaultMetaObjectHandler` 解析操作人字符串/ID | `DefaultMetaObjectHandlerTest` / `SampleSaTokenIntegrationTest` |
| **多租户隔离** | AST 动态行级多租户拦截器 | `AtengTenantLineInnerInterceptor` | `ateng.boot.mybatis-plus.tenant.enabled`<br>`ateng.boot.mybatis-plus.tenant.column`<br>`ateng.boot.mybatis-plus.tenant.ignore-tables` | 基于 JSqlParser 在 AST 语法树层级改写 SQL，自动拼接 `tenant_id = ?` 条件，支持多表 JOIN 与子查询精准注入 | `AtengTenantLineInnerInterceptorTest` / `SampleTenantTest` |
| **多租户解析** | 默认租户上下文处理器 | `DefaultTenantLineHandler` | `ateng.boot.mybatis-plus.tenant.column` | 桥接 `TenantContextHolder` 获取租户 ID，支持动态与静态表忽略过滤 | `DefaultTenantLineHandlerTest` / `SampleTenantTest` |
| **多租户绕过** | 声明式租户忽略注解 | `@IgnoreTenant` | - | 声明于 Mapper 方法或类上，自动绕过多租户行级过滤；支持与 `TenantContextHolder.runWithoutTenant(...)` 协同 | `AtengTenantLineInnerInterceptorTest` / `SampleTenantTest` |
| **数据权限** | AST 细粒度数据权限拦截器 | `AtengDataPermissionInnerInterceptor` | `ateng.boot.mybatis-plus.data-permission.enabled` | 基于 JSqlParser 在 AST 层级拦截改写 SQL，无缝拼接数据权限过滤表达式 | `AtengDataPermissionInnerInterceptorTest` / `SampleDataPermissionTest` |
| **数据权限** | 动态数据权限处理器 | `DataPermissionHandler` / `DefaultDataPermissionHandler` | - | 数据权限 SPI 与默认处理器，结合上下文提取注入数据过滤 SQL 片段 | `AtengDataPermissionInnerInterceptorTest` / `SampleDataPermissionTest` |
| **数据权限** | 声明式数据权限范围注解 | `@DataScope` | - | 细粒度声明式数据权限注解，支持按用户、部门或自定义表达式进行数据范围收敛 | `AtengDataPermissionInnerInterceptorTest` / `SampleDataPermissionTest` |
| **数据权限** | 数据权限上下文保持器 | `DataPermissionContextHolder` | - | 线程安全透传数据权限上下文与过滤表达式，支持链式构建与自动现场清理 | `DataPermissionContextHolderTest` / `SampleDataPermissionTest` |
| **原生批量操作** | 原生多值批量插入门面 | `BatchUtil` | `ateng.boot.mybatis-plus.batch.default-size` | `insertBatchSomeColumn(List<T>)` / `insertBatchSomeColumn(List<T>, int batchSize)`，生成原生多值 `INSERT INTO ... VALUES` 高性能 SQL | `BatchUtilTest` / `SampleBatchInsertTest` |
| **原生批量操作** | 原生批量 SQL 注入器 | `AtengSqlInjector` | - | 自动向 MyBatis-Plus 注入 `InsertBatchSomeColumn` 原生批量插入方法 | `AtengSqlInjectorTest` / `SampleBatchInsertTest` |
| **存储加解密** | 字段级透明存储加密注解 | `@FieldEncrypt` | `ateng.boot.mybatis-plus.crypto.secret-key` | 标注于实体属性上，声明该字段由 `EncryptTypeHandler` 执行持久化透明加解密 | `EncryptTypeHandlerTest` / `SampleCoreIntegrationTest` |
| **存储加解密** | 存储透明加解密类型转换器 | `EncryptTypeHandler` | `ateng.boot.mybatis-plus.crypto.secret-key` | 持久化时入库 AES 密文，查询出库解密为真实明文，严格坚守“内存真明文”铁律 | `EncryptTypeHandlerTest` / `SampleCoreIntegrationTest` |
| **乐观锁重试** | 乐观锁防冲突重试模板 | `OptimisticLockTemplate` | `ateng.boot.mybatis-plus.optimistic-locker.enabled` | `executeWithRetry(Supplier<Boolean>)` / `executeWithRetry(Supplier<Boolean>, int, Duration)`，高并发更新冲突自动退避重试 | `OptimisticLockTemplateTest` |
| **分页契约** | 分页对象流式解耦与安全转译 | `PageUtil` | `ateng.boot.mybatis-plus.pagination.max-limit` | `toPage(PageQuery)` / `toPage(PageQuery, Class<T>)`（安全转译 sorts 并依托实体白名单过滤） / `toPageResult(IPage<T>)` / `toPageResult(IPage<S>, Function<S, T>)`，彻底解耦传输契约与持久实体（Zero-Entity-Leakage） | `PageUtilTest` / `SampleAppTest` |
| **分页拦截** | 原生分页拦截与防刷守护 | `PaginationInnerInterceptor` | `ateng.boot.mybatis-plus.pagination.enabled`<br>`ateng.boot.mybatis-plus.pagination.max-limit`<br>`ateng.boot.mybatis-plus.pagination.overflow`<br>`ateng.boot.mybatis-plus.pagination.db-type` | 原生分页拦截，注入单页最大条数限制（默认 500）与防刷超限防御，自动识别数据库方言 | `AtengMybatisPlusAutoConfigurationTest` / `SampleAppTest` |
| **安全阻断** | 防全表更新/删除拦截器 | `BlockAttackInnerInterceptor` | `ateng.boot.mybatis-plus.block-attack.enabled` | 拦截无 `WHERE` 条件的全表更新与删除操作，防止误操作摧毁生产库数据 | `AtengMybatisPlusAutoConfigurationTest` |
| **性能审计** | 慢 SQL 性能审计拦截器 | `SlowSqlInterceptor` | `ateng.boot.mybatis-plus.slow-sql.enabled`<br>`ateng.boot.mybatis-plus.slow-sql.threshold`<br>`ateng.boot.mybatis-plus.slow-sql.format-sql`<br>`ateng.boot.mybatis-plus.slow-sql.mask-sensitive` | 拦截 `StatementHandler` 执行，基于 `System.nanoTime()` 高精度快速比对放行；超时输出美化 SQL、真实脱敏参数及调用位置；严禁自动 EXPLAIN | `SlowSqlInterceptorTest` / `AtengMybatisPlusAutoConfigurationTest` |
| **告警通知** | 慢 SQL 告警通知 SPI | `SlowSqlAlertProvider` | - | 函数式 SPI 扩展契约，支持下游无缝对接飞书/钉钉群机器人或外部 APM 平台 | `SlowSqlInterceptorTest` |
| **审计事件** | 慢 SQL 审计事件模型 | `SlowSqlEvent` | - | 继承 Spring 原生 `ApplicationEvent`，发布执行耗时、格式化 SQL、脱敏参数、线程名与代码调用栈位置 | `SlowSqlInterceptorTest` |
| **发号器** | 分布式雪花算法发号器 | `DefaultIdentifierGenerator` | `ateng.boot.mybatis-plus.snowflake.enabled`<br>`ateng.boot.mybatis-plus.snowflake.worker-id`<br>`ateng.boot.mybatis-plus.snowflake.datacenter-id` | 提供趋势递增的 64 位全局唯一分布式 ID 发号支持 | `AtengMybatisPlusAutoConfigurationTest` |
| **动态 SQL** | 外部动态 SQL 增强注册中心 | `DynamicSqlRegistry` | `ateng.boot.mybatis-plus.dynamic-sql.enabled`<br>`ateng.boot.mybatis-plus.dynamic-sql.locations` | 外部 SQL 注册、动态解析与热加载中心，支持运行时动态注册/移除外部 XML 脚本与白名单校验 | `DynamicSqlRegistryTest` / `SampleDynamicSqlTest` |
| **动态 SQL** | 外部动态 SQL 统一执行门面 | `DynamicSqlExecutor` | `ateng.boot.mybatis-plus.dynamic-sql.auto-script-wrap` | `selectList` / `selectOne` / `selectMaps` / `update` / `insert` / `delete`，提供对称完备的外部动态 SQL 执行能力 | `DynamicSqlExecutorTest` / `DynamicSqlIntegrationTest` / `SampleDynamicSqlTest` |
| **动态 SQL** | 外部 SQL 资源自动加载器 | `ExternalSqlLoader` / `ResourceExternalSqlLoader` | `ateng.boot.mybatis-plus.dynamic-sql.locations` | 外部 SQL 资源加载 SPI 与默认实现，启动时自动扫描指定路径并注册至 `DynamicSqlRegistry` | `ResourceExternalSqlLoaderTest` / `SampleDynamicSqlTest` |
| **流式游标** | 受管流式游标查询模板 | `StreamingQueryTemplate` | - | `forEach(Supplier<Cursor<T>>, Consumer<T>)` / `forEachBatch(Supplier<Cursor<T>>, int, Consumer<List<T>>)`，强制只读事务守护，`try-with-resources` 确定性闭合游标与连接归还，防 OOM 与句柄泄漏 | `StreamingQueryTemplateTest` / `AtengMybatisPlusAutoConfigurationTest` |
| **多数据源协同** | 多数据源诊断探针与上下文守卫 | `DynamicDatasourceGuard` | `ateng.boot.mybatis-plus.guard.dynamic-datasource` | 启动期轻量感知 Spring 容器多数据源或动态路由特征，输出多租户与事务协同建议；提供 `validateContextIsolated()` 静态断言防卫活跃事务假切源与上下文污染 | `DynamicDatasourceGuardTest` / `AtengMybatisPlusAutoConfigurationTest` |
| **基础扩展** | 通用 BaseMapper 增强门面 | `AtengBaseMapper<T>` | - | 继承 MyBatis-Plus `BaseMapper`，标配挂载 `insertBatchSomeColumn` 原生批量插入扩展 | `AtengSqlInjectorTest` / `SampleBatchInsertTest` |
| **基础扩展** | 通用 Service 增强实现基类 | `AtengBaseService<T>` / `AtengServiceImpl<M, T>` | - | 继承 MyBatis-Plus `IService`，封装通用 CRUD、批量插入与流式事务能力 | `SampleBatchInsertTest` |

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

## 7. 原生高性能分批批量插入引擎 (`AtengBaseMapper` & `AtengBaseService`)

### 场景痛点
MyBatis-Plus 原生 `saveBatch` 底层使用 `SqlSession` 循环遍历逐条单发 Insert 语句，网络通信 RTT 极高。

### 解决方案
- 挂载 `insertBatchSomeColumn` 单条多值 SQL（形如 `INSERT INTO t (a,b) VALUES (1,2),(3,4)...`）；
- 自动过滤逻辑删除列，支持全字段批量写入；
- `BatchUtil` 自动切片分批（默认 1000 批次），避免数据库网络包溢出与占位符数量打爆；
- `AtengBaseService` 强事务守护，任一批次失败全量回滚。

### 核心代码范式
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
userService.insertBatch(users); // 自动按 1000 条分批切片插入
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

## 12. 模块配置属性参考 (`application.yml`)

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
```

---

## 13. 外部动态 SQL 引擎与运行时热更新 (`DynamicSqlEngine`)

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
        // 1. 查询单行记录并自动映射为强类型 VO 或 Map
        UserVO user = dynamicSqlExecutor.selectOne(
                "user.findActiveUsers",
                Map.of("keyword", "Ateng"),
                UserVO.class
        );

        // 2. 深度融合 ADR-0010 分页契约：物理方言分页并流式转换为前端标准 PageResult<VO>
        PageQuery query = new PageQuery(1L, 10L);
        PageResult<UserVO> pageResult = dynamicSqlExecutor.selectPage(
                "user.findActiveUsers",
                Map.of("status", 1),
                query,
                UserVO.class
        );

        // 3. 动态 INSERT / UPDATE / DELETE 编程式执行
        int affected = dynamicSqlExecutor.update(
                "user.updateDynamicStatus",
                Map.of("id", 1001L, "status", 2)
        );
    }
}
```

### 3. 运行时零缝隙原子热更新 (`DynamicSqlRegistry`)
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

        // 沙箱语法检验与原子覆盖：语法错误时返回 false 并自动保留旧版 SQL，成功则瞬间无感生效
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

## 11. 慢 SQL 性能审计与告警通知 (`SlowSqlInterceptor`)

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

## 12. 受管游标流式查询模板与防 OOM 门面 (`StreamingQueryTemplate`)

### 1. 痛点场景与最小起步 (Happy Path & Out-of-the-Box)

在百万级大表导出（如对接 `ateng-boot-excel`）或大规模批处理拉取全量集合时，传统 `selectList` 会一次性将全量数据读入 JVM 堆内存，极易诱发致命的 `OutOfMemoryError` (OOM)；而传统的物理分页（`LIMIT offset, size`）在进入深分页后会导致底层扫描行数爆炸，引发严重的数据库 IO 与慢查询瓶颈。

MyBatis 原生提供了 `Cursor<T>` 游标流式查询能力，但其致命痛点在于：**必须由外层事务维持底层 JDBC 连接在整个迭代期间持续开启**；若开发者忘记显式开启事务、或在业务异常时未闭合游标，极易造成数据库连接池句柄悬挂泄漏与死锁雪崩。

`ateng-boot-mybatis-plus` 原生交付 `StreamingQueryTemplate` 门面：
- **声明式只读事务守护**：内部由 `TransactionTemplate` 自动开启 `readOnly = true` 事务（`PROPAGATION_REQUIRED`），自适应加入外层事务，确保遍历期间连接有效；
- **确定性生命周期清理**：借助 `try-with-resources` 机制，无论是正常消费完毕还是下游业务消费器抛出异常，游标 100% 确定性关闭并释放物理连接；
- **双消费契约自由选用**：提供逐条流式（`forEach`）与分批切片（`forEachBatch`）消费门面，开箱即用。

#### 步骤一：在 Mapper 中定义返回 `Cursor<T>` 的查询方法
```java
public interface StreamingTestMapper {

    @Select("SELECT id, name, amount FROM test_streaming_item ORDER BY id ASC")
    @Options(fetchSize = 1000)
    Cursor<StreamingItem> selectAllCursor();
}
```

#### 步骤二：使用 `StreamingQueryTemplate` 进行逐条或分批消费
```java
@Service
public class StreamingExportService {

    @Autowired
    private StreamingQueryTemplate streamingQueryTemplate;

    @Autowired
    private StreamingTestMapper testMapper;

    // 1. 逐条流式消费（适用于逐行导出或流式处理）
    public void exportSingleItem() {
        streamingQueryTemplate.forEach(
                testMapper::selectAllCursor,
                item -> {
                    // 业务逻辑处理当前单条记录（内存占用仅单对象级别）
                }
        );
    }

    // 2. 分批切片消费（默认 1000 条切片或自定义切片大小，适用于批量 RPC 或下游微服务分批推送）
    public void exportInBatches() {
        streamingQueryTemplate.forEachBatch(
                testMapper::selectAllCursor,
                1000,
                batchList -> {
                    // batchList 为 unmodifiableList 不可变切片，消费完成后立即由 GC 回收
                }
        );
    }
}
```

---

### 2. 生产扩展与接管 (Customization & Extensibility)

#### 方案一：执行自定义聚合计算 (`execute`)
当需要对游标数据执行自定义聚合、求和或带返回值的复杂流式计算时，可使用通用 `execute` 契约：

```java
Long maxId = streamingQueryTemplate.execute(testMapper::selectAllCursor, cursor -> {
    long max = -1L;
    for (StreamingItem item : cursor) {
        if (item.getId() > max) {
            max = item.getId();
        }
    }
    return max;
});
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
   无论下游业务 `Consumer` 正常执行结束，还是中途抛出受控/非受控异常，`StreamingQueryTemplate` 的 `try-with-resources` 守卫均保证在第一时间内调用 `Cursor.close()`，紧接着由事务管理器归还底层物理连接至连接池，彻底消灭连接泄露隐患。
2. **因果异常传递与原子回滚**：
   当下游消费逻辑抛出业务异常（如 `BusinessException` 或其他 `RuntimeException`）时，模板内部在 `catch` 中显式调用 `status.setRollbackOnly()` 触发只读事务安全回滚，并**原样向外透传原始异常**，绝不吞咽异常或二次包装污染因果链。
3. **JDBC FetchSize 与客户端缓冲区防溢出**：
   MyBatis 流式游标建议配合 `@Options(fetchSize = 1000)` 使用（框架推荐默认值 `DEFAULT_FETCH_SIZE = 1000`）。避免底层 JDBC 驱动将数据库千万级记录一次性加载到网络 Socket 缓冲区造成物理机器内存打满。
4. **切片数据不可变性防卫**：
   `forEachBatch` 传出的每批 `List<T>` 均经由 `Collections.unmodifiableList` 封装，防止下游误操作对其执行 `add` / `remove` 导致数据脏读，并在每批消费完毕后立即解除引用协助 JVM 极速 GC。

---

## 13. 多数据源协同诊断探针与上下文守卫 (`DynamicDatasourceGuard`)

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



