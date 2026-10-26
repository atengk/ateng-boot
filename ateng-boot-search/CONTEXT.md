# Search 双引擎全文检索与流批分析底座

面向海量数据搜索、商品与工单组合筛选、高亮聚合查询的大数据全文检索基础设施，坚持纯原生与零臃肿依赖哲学，提供以 Elasticsearch 8.x 为主核并兼顾 OpenSearch 驱动解耦的统一操作门面 SearchTemplate、强类型 SearchHit 载荷、流式安全 DSL 构造器、多字段高亮提取、Search-After 深度分页防 OOM 治理、受管流式 BulkProcessor、地理空间/AI 向量检索与 100% 离线自洽测试沙箱。

## Language

**SearchTemplate (检索统一门面)**:
对外暴露的统一高内聚检索数据面操作门面，提供索引文档 CRUD、局部更新与 Upsert、批量写入、多字段高亮、游标分页检索、地理/向量查询及底层客户端逃生舱能力。
_Avoid_: SearchService, ElasticsearchUtil, EsTemplate, SearchManager

**SearchIndexOperations (索引结构管理门面)**:
通过 `searchTemplate.indices()` 暴露的专职索引结构面（DDL）操作门面，提供索引存在性检查、创建、删除、Mapping 变更、索引模板维护及原子别名热切换能力。
_Avoid_: IndexManager, EsIndexUtil, DdlTemplate, SchemaService

**SearchBulkProcessor (受管流式批量处理器)**:
基于文档数量、时间窗口与内存容量多维阈值触发自动异步刷盘的高性能批量写入缓冲处理器，挂载受管线程池与优雅停机安全排空机制。
_Avoid_: BulkQueue, BatchWriter, AsyncFlusher, BulkManager

**SearchEngineClient (搜索引擎客户端 SPI)**:
解耦底层具体搜索引擎驱动（Elasticsearch 8.x / OpenSearch）的协议接口，为上层门面提供底层网络与请求转发支持。
_Avoid_: ClientWrapper, SearchDriver, EsClient, EngineAdapter

**SearchHit (检索命中载荷)**:
包含反序列化业务实体、多字段富文本高亮切片、匹配相关度得分、版本凭据（_seq_no/_primary_term）与排序游标凭据的强类型检索结果容器。
_Avoid_: SearchResultItem, DocumentHit, HitWrapper, RecordItem

**SearchQueryBuilder (流式查询构建器)**:
用于链式组装 Term、Match、Range、Bool、Sort、Highlight、Aggregation、GeoDistance 及 KNN 向量检索等常用检索算子的轻量强类型查询构造器。
_Avoid_: EsQueryWrapper, QueryHelper, DslBuilder, CriterionBuilder

**Search-After (游标深度分页)**:
利用上一页末尾文档的全局唯一排序字段值（Sort Values）作为下一次查询起点的高性能游标分页机制，彻底避免深层跳页导致的集群内存溢出。
_Avoid_: Scroll, DeepPaging, 滚动查询, 游标翻页

**SearchBulkException (批量写入局部失败异常)**:
在严格快速失败模式下，当批量写入操作中任一文档由于映射冲突或版本冲突导致失败时抛出的强类型受管异常，内含失败文档快照与错误因果链。
_Avoid_: BulkError, BatchWriteException, PartialFailureException

**SearchBulkResult (批量写入结果载荷)**:
在宽容模式下执行批量写入返回的结构化执行统计凭据，包含成功计数、失败计数与各失败项明细列表。
_Avoid_: BulkSummary, BatchResponse, WriteResult

**SearchVersionConflictException (乐观并发控制版本冲突异常)**:
当基于 `_seq_no` 与 `_primary_term` 执行条件更新或带版本的写入时，若服务端当前版本与预期不符触发 HTTP 409 冲突所抛出的强类型业务异常。
_Avoid_: VersionException, ConcurrencyException, OccConflict

**SearchIndex (检索索引元数据)**:
逻辑上承载同类型文档集合及其分片、副本与映射配置的命名实体，支持动态 SpEL 表达式与时序后缀推导。
_Avoid_: DocumentTable, Collection, IndexMapping, 索引表

**SearchRouting (文档分片路由标记)**:
用于在实体字段上声明自定义物理分片哈希路由键的注解，保障 CRUD 与定向检索全生命周期路由一致性。
_Avoid_: ShardKey, PartitionKey, RouteId, 分片键

**HighlightField (高亮字段映射)**:
用于声明将检索结果中特定字段的高亮切片富文本自动回填至对应 VO 属性的字段级契约注解。
_Avoid_: TextHighlight, EmTagField, MarkField, 高亮切片

**SearchDataScopeHelper (检索数据权限辅助器)**:
用于快速向当前查询流式构建器注入多租户隔离与部门/个人数据范围过滤条件的上下文装配辅助器。
_Avoid_: EsPermissionUtil, SearchFilterHelper, DataScopeAppender
