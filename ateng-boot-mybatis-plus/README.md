# ateng-boot-mybatis-plus 持久层增强底座

> 提供实体五级阶梯体系、审计自动填充、行级多租户隔离、AST 声明式细粒度数据权限、原生高效批量插入、存储透明加密与视图动态脱敏。

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
userService.saveBatchFast(users); // 自动按 1000 条分批切片事务插入
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

## 10. 持久层与传输契约解耦工具 (`PageUtil`)

### 核心代码范式
```java
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.boot.core.model.PageResult;

@Service
public class UserServiceImpl implements UserService {

    @Override
    public PageResult<UserVO> queryUserPage(PageQuery pageQuery) {
        // 1. 将 PageQuery 转换为 MyBatis-Plus Page 对象
        Page<UserEntity> page = PageUtil.toPage(pageQuery);

        // 2. 执行持久层分页查询
        userMapper.selectPage(page, new LambdaQueryWrapper<UserEntity>().like(UserEntity::getName, "张"));

        // 3. 流式转换为前端契约 PageResult<UserVO>，抹平 ORM 内部属性
        return PageUtil.toPageResult(page, UserVO::fromEntity);
    }
}
```

---

## 11. 模块配置属性参考 (`application.yml`)

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
```
