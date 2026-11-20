# Milvus 向量数据库与多向量混合检索底座

面向高并发企业级向量检索、ANN 相似度计算与多向量混合查询的工业级 Starter，基于官方 MilvusClientV2 现代驱动与双核穿透构建，提供三层立体抽象门面 MilvusTemplate、轻量双模实体映射（POJO 注解与动态 Map 字典）、多向量混合检索 hybridSearch 融合 RRF/Weighted 重排、SaaS 多租户透明安全织入与 PartitionKey 物理分区加速、LangChain4j 标准 SPI 契约 MilvusEmbeddingStore 条件装配弱依赖解耦、全链路因果异常与云原生健康探针。

## Language

**MilvusTemplate (向量统一操作门面)**:
对外暴露的高内聚统一向量操作门面，覆盖集合运维生命周期（DDL）、极简向量与标量数据操作（DML/DQL）、多向量混合检索（hybridSearch）、游标流式遍历（streamingQuery）及底层客户端逃生舱（getClient）。
_Avoid_: MilvusService, MilvusUtil, VectorTemplate, MilvusManager

**MilvusClientV2 (官方现代原生驱动主核)**:
Milvus 官方提供的现代化高层客户端实现，原生支持动态 Schema、多向量列、统一请求响应模型，作为本底座的底层核心网络与协议驱动。
_Avoid_: MilvusServiceClient, MilvusV1Client, MilvusRpcClient

**MilvusConsistencyLevel (读写一致性级别)**:
实现 `IBaseEnum<String>`（标配 `@JsonValue` 与大小写无关反序列化 `@JsonCreator`）的强类型枚举契约，精准对齐 `milvus-sdk-java` 2.4.4 原生 `ConsistencyLevel` 三级模型：`STRONG`（强一致/写后即查）、`BOUNDED`（有界时限一致，默认）与 `EVENTUALLY`（最终一致）。
_Avoid_: ConsistencyType, ReadConsistency, WriteLevel

**MilvusClientCustomizer (客户端连接配置定制器 SPI)**:
面向底层 `ConnectConfig` 的函数式扩展点 SPI（`@FunctionalInterface`），允许业务方注册自定义 Bean 无损调整 RPC 超时、SSL 上下文或 KeepAlive 等参数，而无需整套覆盖 `AtengMilvusAutoConfiguration` 默认装配的 `MilvusClientV2`。
_Avoid_: MilvusConfigCustomizer, ConnectCustomizer, MilvusClientHook

**MilvusException (向量业务专有异常)**:
继承自 `BusinessException` 的受管异常类，统一承接底层 gRPC 与 SDK 异常，100% 携带原始因果链（cause）向上抛出。
_Avoid_: MilvusSdkException, VectorDatabaseException, RpcException

**MilvusResultCode (向量业务状态码枚举)**:
实现 `IResultCode` 的错误码标准枚举，覆盖客户端初始化失败、集合未找到、集合已存在、未加载到内存、向量维度不匹配、分区未找到与多租户缺失等高频典型场景。
_Avoid_: MilvusErrorCode, VectorResultEnum, StatusCode

**MilvusHealthIndicator (云原生存活就绪健康探针)**:
对接 Spring Boot Actuator 的健康检查组件，执行无损探测 `testConnection()` / `getServerVersion()`，向 `/actuator/health` 暴露连通状态与端点详情。
_Avoid_: MilvusProbe, MilvusCheck, VectorHealth

**MilvusTenantHelper (多租户防御性织入器)**:
自动联动 `TenantContextHolder` 的多租户防护组件，明确区分“逻辑租户隔离列（`@MilvusField(tenantField = true)` 或 `@MilvusPartitionKey(tenantField = true)`，缺省回退全局 `ateng.boot.milvus.tenant-field-name`）”、“物理哈希分区键（`@MilvusPartitionKey`）”与“租户切库路由（`tenant-database-routing`）”三者正交语义；在查询端自动织入防御性括号表达式 `(${expr}) and (${tenant_field} == 'xxx')` 防范运算符逃逸，并在写入端自动提取填充租户标识。
_Avoid_: TenantFilter, MilvusTenantUtil, TenantInjector

**MilvusHit (向量检索命中载荷)**:
包含命中实体主键 ID、相似度得分（Score / Distance）、反序列化业务实体或动态字典属性的强类型标准化命中结果模型。
_Avoid_: SearchResultItem, VectorRecord, HitWrapper

**MilvusHybridSearchRequest (多向量混合检索请求模型)**:
支持聚合多个向量通道（稠密向量 + 稀疏/关键词向量）并挂载 RRF 或加权评分融合重排算法（ReRanker）的 Fluent 请求构造器。
_Avoid_: MultiSearchRequest, HybridQuery, MixedSearch

**MilvusEmbeddingStore (LangChain4j 知识库存储 SPI 适配器)**:
实现 LangChain4j `EmbeddingStore<TextSegment>` 标准接口的适配器，基于条件装配弱依赖解耦，为大模型 RAG 提供知识切片持久化与语义检索底层。
_Avoid_: MilvusVectorStore, LangChainMilvusStore, RagStorage

**MilvusSearchRequest (单向量高级检索请求模型)**:
单向量 ANN 相似度检索的高内聚 Fluent 请求构造器，支持标量过滤、GroupBy 去重聚合、Radius 与 RangeFilter 相似度绝对区间过滤、Offset 浅分页与专属检索超参数。
_Avoid_: SingleSearchRequest, SearchQuery, VectorQueryReq

**MilvusAlias (集合别名与蓝绿平滑切表机制)**:
将业务逻辑别名绑定至实际物理向量集合的动态指针抽象，用于实现全量数据离线重建后的毫秒级流量切换、蓝绿发布与零停机冷热平滑切表。
_Avoid_: CollectionPointer, VectorAlias, TableAlias
