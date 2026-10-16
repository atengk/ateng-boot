# MyBatis-Plus Context

`ateng-boot-mybatis-plus` 为持久层提供标准化实体基类、智能审计填充、多数据库方言自适应拦截器链与分布式主键扩展能力。

## Language

**BaseGenericEntity**:
泛型主键实体抽象顶层基类，允许业务自定义主键类型。
_Avoid_: RootEntity, GenericModel

**BaseIdEntity**:
标配雪花长整型主键的极简实体抽象基类，适用于关联映射表与无审计日志表。
_Avoid_: IdModel, PrimaryEntity

**BaseTimeEntity**:
提供雪花主键与自动审计创建/修改时间的实体抽象基类，适用于无需操作人记录的轻量表。
_Avoid_: TimeEntity, TimestampModel

**BaseEntity**:
主力业务实体抽象基类，提供雪花主键与完整的创建/修改时间及操作人审计字段。
_Avoid_: Model, SuperEntity, CommonEntity, RootEntity

**BaseFullEntity**:
全要素受管业务实体抽象基类，在 BaseEntity 基础上追加 CAS 乐观锁版本号与在 JSON 中自动忽略的逻辑删除标记。
_Avoid_: FullModel, AllInOneEntity

**AuditMetaObjectHandler**:
在实体持久化插入与更新时智能感知字段存在性，并防御性自动填充时间与当前操作人的元对象处理器。
_Avoid_: FieldFiller, AutoFillHandler, MetaHandler

**AdaptivePaginationInterceptor**:
支持动态探测底层数据库方言（杜绝硬编码 MySQL），并带有单页 500 条上限内存防爆保护的分页内拦截器。
_Avoid_: MybatisPaging, PageHelper

**BlockAttackInterceptor**:
自动阻断无 WHERE 条件的全表更新与全表物理删除的防灾安全拦截器。
_Avoid_: SafeDeleteInterceptor, TableAttackBlocker

**OptimisticLockerInterceptor**:
基于实体标记的版本号字段实现并发原子递增与 CAS 防护的乐观锁拦截器。
_Avoid_: VersionInterceptor, ConcurrencyInterceptor

**FieldEncrypt**:
声明在实体敏感属性上的持久化透明加密注解，在落盘写入数据库时自动加密为 AES 密文，在查询读取时自动解密为真实明文。配合 `@TableField(typeHandler = EncryptTypeHandler.class)` 使用。
_Avoid_: SecretField, CryptoField, EncryptField

**EncryptTypeHandler**:
继承 MyBatis `BaseTypeHandler<String>` 的透明加解密类型处理器，采用 AES 对称算法，前置 16 字节随机 IV 并进行 Base64 组合编码，具备自包含解密与防御性历史明文兼容能力。
_Avoid_: AesTypeHandler, SecretHandler, CryptoHandler

**FieldSensitive**:
声明在实体或 VO 属性上的出库数据动态脱敏注解，基于 Jackson `SensitiveJsonSerializer` 在序列化为 JSON 响应时动态掩码，确保 Java 内存中始终保持 100% 原始真实明文。
_Avoid_: MaskField, Desensitize, SensitiveData

**SensitiveJsonSerializer**:
基于 Jackson `ContextualSerializer` 动态感知 `@FieldSensitive` 配置的动态脱敏序列化器，委托 `DesensitizeUtil` 执行高性能掩码转换。
_Avoid_: MaskSerializer, DesensitizeSerializer

**SensitiveType**:
脱敏策略枚举类，包含 `CHINESE_NAME`、`MOBILE_PHONE`、`FIXED_PHONE`、`ID_CARD`、`EMAIL`、`BANK_CARD`、`PASSWORD`、`CUSTOM` 与 `NONE`。
_Avoid_: MaskType, DesensitizeType

**PageUtil**:
用于将持久层 IPage 转换为标准 PageResult，以及将 PageQuery 转换为 MyBatis-Plus Page 的双向映射工具类。
_Avoid_: PageHelper, PaginationUtil, MpPageUtil

**TenantContextHolder**:
维护当前线程租户上下文与租户忽略标志位的上下文持有者，提供受控编程式闭包与跨线程透传能力。
_Avoid_: TenantManager, TenantHolder, TenantSession

**TenantLineHandler**:
MyBatis-Plus 行级租户隔离 SPI 契约处理器，负责根据当前上下文动态生成租户过滤 SQL 表达式与判定忽略表。
_Avoid_: TenantHandler, TenantFilter

**IgnoreTenant**:
声明在 Mapper 接口或方法上的注解，用于细粒度短路绕过行级租户 SQL 拦截改写。
_Avoid_: SkipTenant, NoTenant, DisableTenant

**DataScope**:
声明在 Mapper 接口或方法上的数据权限控制注解，声明目标数据表别名、部门与人员归属字段。
_Avoid_: DataFilter, PermissionScope, DataAuth

**DataPermissionHandler**:
数据权限规则 SPI 契约处理器，负责将业务 RBAC 权限范围转化为 JSqlParser 表达式树安全改写 SQL。
_Avoid_: PermissionHandler, DataScopeHandler

**AtengBaseMapper**:
继承 MyBatis-Plus `BaseMapper` 的通用增强持久层接口，扩展原生单条多值高性能批量插入（`insertBatchSomeColumn`）方法。
_Avoid_: SuperMapper, EnhancedMapper, CustomBaseMapper

**AtengBaseService**:
继承 MyBatis-Plus `IService` 的通用业务增强接口，扩展自定义分片的原生批量插入契约。
_Avoid_: SuperService, EnhancedService, CustomBaseService

**AtengServiceImpl**:
继承 MyBatis-Plus `ServiceImpl` 并实现 `AtengBaseService` 的通用实现基类，提供声明式事务守护的分片多值批量插入。
_Avoid_: SuperServiceImpl, EnhancedServiceImpl

**BatchUtil**:
批量操作静态门面工具类，提供无侵入的原生多值批量插入切片工具方法。
_Avoid_: BatchHelper, BulkUtil

**TenantEntity**:
可选的租户实体标记接口，提供类型安全的租户读写契约。
_Avoid_: TenantModel, HasTenant

