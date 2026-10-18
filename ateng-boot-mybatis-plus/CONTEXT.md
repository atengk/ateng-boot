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
声明在实体或 VO 属性上的出库数据动态脱敏注解，定义于 `ateng-boot-core`，基于 Jackson `SensitiveJsonSerializer` 在序列化为 JSON 响应时动态掩码，确保 Java 内存中始终保持 100% 原始真实明文。
_Avoid_: MaskField, Desensitize, SensitiveData

**SensitiveJsonSerializer**:
基于 Jackson `ContextualSerializer` 动态感知 `@FieldSensitive` 配置的动态脱敏序列化器，定义于 `ateng-boot-core`，委托 `DesensitizeUtil` 执行高性能掩码转换。
_Avoid_: MaskSerializer, DesensitizeSerializer

**SensitiveType**:
脱敏策略枚举类，定义于 `ateng-boot-core`，包含 `CHINESE_NAME`、`MOBILE_PHONE`、`FIXED_PHONE`、`ID_CARD`、`EMAIL`、`BANK_CARD`、`PASSWORD`、`ADDRESS`、`CAR_LICENSE`、`IPV4`、`IPV6`、`SOCIAL_CREDIT_CODE`、`KEEP_HEAD_TAIL`、`CUSTOM` 与 `NONE`。
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

**DataPermissionContextHolder**:
细粒度数据权限上下文持有者，维护当前线程忽略标志与超管标志，提供受控编程式闭包（`runWithoutPermission` 与 `runAsSuperAdmin`）。
_Avoid_: DataScopeContextHolder, PermissionContext

**AtengDataPermissionInnerInterceptor**:
增强型细粒度数据权限拦截器，继承 MyBatis-Plus `DataPermissionInterceptor`，集成 `@DataScope` 纳秒级元数据缓存、多表别名校验与超管/闭包秒级短路放行。
_Avoid_: CustomDataPermissionInterceptor, DataScopeInterceptor

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

**OptimisticLockTemplate**:
基于 core 底座 RetryUtil 实现的乐观锁并发自旋重试模板门面，针对 @Version 并发冲突（更新行数 = 0）提供自动重新加载最新版本实体与自旋退避守护。
_Avoid_: OptimisticLockHelper, VersionRetryTemplate

**ExternalSqlLoader**:
外部 SQL 脚本加载器 SPI 契约接口，定义从外部媒介（数据库配置表、分布式配置中心、远程 HTTP 或文件系统）拉取与监听动态 SQL 元数据的标准扩展规范。
_Avoid_: SqlFetcher, SqlProvider, ExternalSqlReader

**DynamicSqlDefinition**:
外部动态 SQL 元数据模型契约，用于承载 SQL 唯一标识（StatementId）、MyBatis XML 脚本片段、命令类型（SELECT/INSERT/UPDATE/DELETE）与目标结果映射类型。
_Avoid_: SqlModel, SqlEntity, DynamicSqlMeta

**DynamicSqlRegistry**:
动态 SQL 运行时编排与热替换注册中心，负责将 `DynamicSqlDefinition` 编译并原子注入或刷新至 MyBatis `Configuration` 的 `MappedStatement` 映射容器中。
_Avoid_: StatementManager, DynamicSqlManager, SqlRegistry

**DynamicSqlExecutor**:
通用编程式动态 SQL 执行门面组件，依托 `SqlSession` 与已注册的 `MappedStatement`，提供免建 Mapper 接口的增删改查以及符合 ADR-0010 规范的标准分页执行能力。
_Avoid_: UniversalSqlExecutor, CommonDao, DynamicSqlDao

**BaseDeleteIdEntity**:
针对高频逻辑删除复合唯一索引冲突表设计的受管业务实体抽象基类，继承 BaseEntity，追加乐观锁版本号与以 Snowflake ID 占位删除的 Long 型删除标记（未删除为 0，删除时 `deleted = id`）。
_Avoid_: BaseDeleteTimeEntity, SoftDeleteEntity, DeleteModel, TimestampEntity

**StreamingQueryTemplate**:
受管游标流式查询模板门面，结合 MyBatis Cursor 与声明式只读事务，提供逐条流式或分批遍历消费能力，彻底防御堆内存 OOM 与数据库连接池句柄悬挂泄漏。
_Avoid_: CursorHelper, StreamUtil, BatchQueryUtil

**SlowSqlInterceptor**:
慢 SQL 性能审计拦截器，基于 MyBatis Statement 拦截器精确统计包含网络 IO 的真实执行耗时，捕获美化 SQL、真实绑定参数与调用栈行号，预留告警 SPI。
_Avoid_: SqlMonitor, QueryProfiler, PerformanceInterceptor

**SlowSqlAlertProvider**:
慢 SQL 告警通知 SPI 契约，支持下游自由扩展推送到钉钉、飞书、企业微信或外部 APM 系统。
_Avoid_: SqlNotifier, SlowSqlHandler, SlowSqlConsumer

**DynamicDatasourceGuard**:
多数据源环境协同守卫探针，校验与防御跨数据源切换时租户与数据权限上下文的隔离有效性。
_Avoid_: MultiDataSourceHelper, RoutingGuard

**DynamicQueryRequest**:
标准动态查询请求契约对象，继承 PageQuery，承载前端或服务间传递的结构化过滤条件、受控操作符与排序字段。
_Avoid_: UniversalQuery, SearchParam, QueryDto

**DynamicQueryHelper**:
动态查询与排序安全转译门面，基于实体元数据物理白名单将动态条件安全构建为 MyBatis-Plus LambdaQueryWrapper，杜绝 SQL 注入与未授权列探测。
_Avoid_: QueryBuilder, WrapperHelper, AutoQuery

**QueryField**:
声明在业务特定 DTO 字段上的动态条件注解，用于显式映射目标实体属性与操作符。
_Avoid_: SearchColumn, CriteriaField, QueryCondition

**DynamicQueryIgnore**:
声明在实体敏感属性上的安全防卫注解，显式禁止外部动态查询条件与排序访问该字段。
_Avoid_: SkipQuery, NoSearch, ForbidQuery

**DataChangeRecord**:
数据变更增量记录实体，承载实体在 UPDATE/DELETE 时的变更前/后快照与字段级 Diff 明细。
_Avoid_: AuditLog, ChangeHistory, DiffRecord

**AuditChange**:
声明在实体类或 Mapper 方法上的变更追踪审计注解，声明触发快照对比与 Diff 抓取。
_Avoid_: TrackChange, DiffAudit, LogChange

**DataChangeAuditProvider**:
数据变更审计日志消费与异步持久化 SPI 契约接口，支持业务自由对接存盘、ES 或消息总线。
_Avoid_: AuditConsumer, ChangeHandler, DiffListener

**ConflictKeys**:
声明在实体类或关键属性上的冲突键注解，用于 PostgreSQL 原生 Upsert `ON CONFLICT (keys)` 复合唯一索引匹配。
_Avoid_: ConflictColumn, UniqueConflict, UpsertKeys

**DataChangeAuditContextHolder**:
数据变更审计上下文持有者，提供编程式闭包（`runWithoutAudit`）用于临时绕过变更快照抓取与 Diff 计算。
_Avoid_: AuditContext, ChangeSession, AuditHolder


