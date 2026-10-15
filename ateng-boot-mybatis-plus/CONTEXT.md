# MyBatis-Plus Context

`ateng-boot-mybatis-plus` 为持久层提供标准化实体基类、智能审计填充、多数据库方言自适应拦截器链与分布式主键扩展能力。

## Language

**BaseEntity**:
提供雪花长整型主键、自动审计时间与操作人、以及在 JSON 中自动忽略的逻辑删除标记的实体抽象基类。
_Avoid_: Model, SuperEntity, CommonEntity, RootEntity

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
