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
