# 84. 全文检索与时序分析全场景工业级加固架构决策 (Search Full-Scenario Hardening and Resilient Analytics Architecture)

## 状态
已采纳 (Accepted)

## 背景与痛点
在 [ADR-0053](./0053-enterprise-dual-engine-search-and-analytics-architecture.md) 中，`ateng-boot-search` 确立了以 Elasticsearch 8.x 为主核、解耦 OpenSearch 客户端 SPI 的统一检索底座，并实现了 `SearchTemplate` 基础 CRUD、OCC 乐观并发控制、双模 Bulk 写入、受管 `SearchBulkProcessor` 与 Search-After 深度分页熔断。然而，随着海量电商商品检索、时序日志审计、大数据中台与大模型 RAG 知识库检索等企业级复杂业务的深入推进，底座暴露了以下六大高频场景短板：

1. **轻量探测与轻量统计缺失导致严重网络与内存浪费**：
   业务在判断文档是否存在时，由于缺少 HEAD 探测门面，被迫调用 `getById != null` 拉取整条 `_source` 报文并在本地反序列化；在仅需统计符合条件的文档数量时，缺少轻量 `_count` 接口，被迫发起带排序与打分的完整 `_search` 请求。
2. **批量按主键检索 (MGet) 缺失引发 N+1 级联 I/O 劣化**：
   在购物车多商品查询、缓存失效多键批量回源场景下，缺少对称的 `_mget` 批量查询门面，业务端被迫循环发起单文档 `getById` 请求，网络延迟成倍放大。
3. **条件驱动物理清理 (Delete-By-Query) 缺失与多租户越权隐患**：
   面对过期时序日志清理或订单注销场景，缺少 `_delete_by_query` 门面；若业务自行绕行执行，极易在多租户环境下遗漏租户隔离条件，造成灾难性的跨租户数据误删。
4. **多规格 SKU 独立匹配失效 (Cross-Object False Match)**：
   在商品多规格检索中，常规平铺 Object 数组会导致跨 SKU 条件交叉误命中（如搜索 `颜色=白色 AND 尺码=XL` 时误命中包含了“白色M”与“黑色XL”的商品），缺少 Fluent `nested` 嵌套查询算子隔离子对象作用域。
5. **搜索结果霸屏与时序趋势统计缺失**：
   缺少 `collapse` 字段折叠去重算子（如按品牌或 SPU 去重）；缺少 `dateHistogramAggregation` 时序直方图与 `min`/`max` 对称度量算子，无法直接构建日志监控走势图。
6. **零停机索引重建生命周期在第二步断层**：
   虽然实现了 `swapAlias` 原子别名切换，但缺少服务端的 `reindex` 复制门面与 `refresh` 物理刷新，导致完整的蓝绿无停机索引迁移链路无法在 Starter 内部自洽闭环。

---

## 核心架构与决策内容

### 1. 数据面 DML 完备性对称补齐与租户绝对防线
- **轻量存在性检查 (`existsById`)**：提供 `existsById(Class<?> clazz, String id)` 及带 `routing` 与 `index` 的对称重载，底层映射 HTTP HEAD `_exists` 原生端点，实现 0 响应体毫秒级探测；
- **轻量精准计数 (`count`)**：提供 `count(SearchQuery query, Class<?> clazz)` 与 `count(String index, SearchQuery query)`，底层直接对接 ES `_count` 轻量端点，免除文档加载与相关度计算开销；
- **批量多主键检索 (`getByIds` / `getHitsByIds`)**：提供 `getByIds(Class<T> clazz, Collection<String> ids)`，底层映射 `_mget` 批量端点，单次网络往返拉取；强制过滤 `null` 结果项，保底返回非空纯净集合，彻底杜绝 NPE；
- **条件驱动批量物理清理 (`deleteByQuery`) 与多租户安全网**：
  - 提供 `deleteByQuery(SearchQuery query, Class<?> clazz)` 与 `deleteByQuery(String index, SearchQuery query)`；
  - **多租户强制防线**：当开启多租户（`ateng.boot.search.multi-tenancy.enabled = true`）且当前线程存在有效 `TenantContextHolder.getTenantId()` 时，强制在 `deleteByQuery` 的顶层 Bool 查询 `filter` 分支中隐式注入租户隔离条件（标注 `@IgnoreTenant` 除外），杜绝误删他租户数据；
  - **并发冲突容错**：默认配置 `conflicts("proceed")`，防止并发小范围写冲突导致整批次大规模清理中断，最终返回实际删除成功的文档数 `long deletedCount`。

### 2. 流式查询构造器 (Fluent `SearchQueryBuilder`) 高阶算子扩展
- **搜索联想与精确短语**：扩展 `matchPhrase(field, text)` 与 `matchPhrasePrefix(field, text, maxExpansions)`，原生支持搜索输入框自动补全与严格保序词组检索；
- **拼写纠错模糊检索**：扩展 `fuzzy(field, value)` 与 `fuzzy(field, value, fuzziness)`，支持用户输入手误召回；
- **嵌套对象作用域隔离 (`nested`)**：扩展 `nested(String path, Consumer<SearchQueryBuilder> nestedConsumer)`，在独立 nested 上下文内部组装布尔条件，彻底消除多 SKU 跨属性交叉误判；
- **原生字段折叠去重 (`collapse`)**：扩展 `collapse(String field)`，基于原生 field collapsing 特性实现搜索结果按商户、品牌或 SPU 折叠去重，防止热门品牌垄断首屏；
- **对称度量与时序直方图聚合**：
  - 补齐对称度量算子 `minAggregation(name, field)` 与 `maxAggregation(name, field)`；
  - 补齐时序直方图算子 `dateHistogramAggregation(name, field, calendarInterval)`，开箱即用支持按天、按小时分桶度量分析。

### 3. 结构面 DDL 零停机平滑迁移与生命周期闭环 (`SearchIndexOperations`)
- **全量/增量重索引 (`reindex`)**：
  - 提供 `reindex(String sourceIndex, String targetIndex)`；
  - 提供 `reindex(String sourceIndex, String targetIndex, SearchQuery filterQuery)` 支持带条件的冷热分流与增量重索引；
  - 与现有 `swapAlias(alias, oldIndex, newIndex)` 协同，形成：`创建新索引` ➔ `reindex 复制数据` ➔ `swapAlias 原子切源` ➔ `delete 旧索引` 的 100% 生产无感平滑迁移闭环；
- **即时物理刷新 (`refresh`)**：提供 `refresh(String index)` 与 `refresh(Class<?> clazz)`，显式触发 Lucene Segment 物理刷新，支撑写后即读场景与单测断言；
- **模板对称删除**：提供 `deleteIndexTemplate(String templateName)`，补齐模板维护生命周期的对称完备性。

### 4. 样例工程架构场景化正交解耦 (1:1 Modular Scenarios)
废除单一单体服务类，将 `ateng-boot-sample-search` 重构为以业务场景命名的独立组件矩阵（零 Controller 污染）：
1. `ProductCatalogSearchScenario`：电商商品多维检索、多字段高亮、Nested 嵌套 SKU 检索、搜索联想补全、Collapse 折叠去重与 Search-After 游标分页；
2. `LogAnalyticsAggScenario`：SpEL 动态时序分表、DateHistogram 直方图聚合走势与 `deleteByQuery` 过期清理；
3. `ZeroDowntimeReindexScenario`：索引 DDL 创建、Mapping 更新、带条件 `reindex` 数据复制与 `swapAlias` 零停机切源；
4. `BulkIngestionScenario`：海量流水数据非阻塞高吞吐流式批量缓冲写入与优雅停机排空安全网。

---

## 权衡与后果 (Consequences & Trade-offs)

### 正向收益
1. **API 语义完备对称**：消灭了单查有而批查无（`getByIds`）、单删有而条件删无（`deleteByQuery`）、取数据有而轻量判断无（`existsById`/`count`）的不对称缺陷；
2. **I/O 与计算性能大幅提升**：`existsById` 零响应体开销，`count` 免除打分排序，`getByIds` 单次往返消除 N+1 查询，`collapse` 原生折叠性能提升数倍；
3. **多租户安全绝对加固**：`deleteByQuery` 严格遵循四维上下文自动挂载租户隔离，杜绝跨租户灾难性误删；
4. **复杂电商与时序分析全面覆盖**：`nested` 解决多 SKU 交叉误判，`dateHistogram` 原生支撑时序监控大盘；
5. **100% 向后兼容与零臃肿**：所有新增接口皆为纯增量式扩展，完全依托 Spring Boot 3 纳管的标准官方驱动，无任何第三方包膨胀。
