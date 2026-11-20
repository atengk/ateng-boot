# ateng-boot-search

面向生产环境的企业级双引擎全文检索与流批分析 Starter 组件，基于 `Elasticsearch Java API Client 8.x` 官方强类型协议与 `SearchEngineDialect` 方言适配层，对 **Elasticsearch 8.x** 与 **OpenSearch** 提供统一门面契约。开箱集成强类型 `SearchTemplate`、`SearchIndexOperations`、`SearchQueryBuilder`、受管流式 `SearchBulkProcessor`、零停机原子别名切换与异步重索引生命周期治理、`CLIENT_RRF` 零许可证 AI 混合检索、多租户严格隔离闭环及 Micrometer 可观测指标体系。

---

## 0. 全局能力全景速查矩阵

### 0.1 核心门面与方法全景

| 核心组件 | 所属包路径 | 职责域与公开能力清单 |
| :--- | :--- | :--- |
| **`SearchTemplate`** | `io.github.atengk.boot.search.template` | 统一读写与检索门面：<br/>• **写入与幂等创建**：`save`（支持 `SearchRefreshPolicy` / `OccVersion`）、`createIfAbsent`（仅新增不覆盖）、`index`（全参支持 `ifSeqNo` / `ifPrimaryTerm` / 刷新策略）<br/>• **点查与批量主键拉取**：`getById` / `getHitById` / `existsById`（支持路由与 `STRICT` 隔离验证）、`getByIds` / `getHitsByIds`（`_mget`）<br/>• **局部更新、同步批量与删改**：`update`（`Map` / POJO / `docAsUpsert` / `OccVersion` / `SearchRefreshPolicy`）、`deleteById`、`indexBatch` / `indexBatchLenient`、`updateBatch` / `updateBatchLenient`、`deleteBatch` / `deleteBatchLenient`、`executeBulk`、`updateByQuery` / `updateByQueryAsync`、`deleteByQuery` / `deleteByQueryAsync`<br/>• **全文检索与视图投影**：`count`、`search(query, clazz)`、`search(query, indexEntityClass, resultVoClass)`（`@HighlightField` VO 安全投影）、`msearch`（`_msearch` 单次往返多路检索）、`hybridSearch`（BM25 + kNN，`CLIENT_RRF` / `CLIENT_WEIGHTED` / `NATIVE_RRF`）<br/>• **游标与一致快照翻页**：`searchAfter`、`openPointInTime`、`closePointInTime`、`searchAfterWithPit`、`executeWithPointInTime`<br/>• **原生脱壳与门面扩展**：`indices()`、`newBulkProcessorBuilder()`、`bulkProcessor()`、`execute(SearchCallback<R>)`、`getClient()`、`getSearchEngineClient()`、`getProperties()`、`getSearchMetrics()` / `setSearchMetrics(...)` |
| **`SearchIndexOperations`** | `io.github.atengk.boot.search.template` | 索引 DDL 与零停机生命周期管理：`exists` / `existsAlias` / `create` / `delete`、`putMapping` / `getMapping`、`putIndexTemplate(String, String)` / `getIndexTemplate` / `existsIndexTemplate` / `deleteIndexTemplate`、`putComponentTemplate` / `existsComponentTemplate` / `deleteComponentTemplate`、`getSettings` / `putSettings`、`addAlias(index, alias, isWriteIndex)` / `removeAlias` / `getAliases` / `swapAlias`、`refresh`、`reindex`（同步/按条件过滤）、`reindexAsync`、`getTaskStatus`、`waitForTask`、`cancelTask` |
| **`SearchQueryBuilder`** | `io.github.atengk.boot.search.query` | 流畅 DSL 安全构造器：<br/>• **精确与全文**：`term`、`terms`、`match`、`multiMatch`、`matchPhrase`、`matchPhrasePrefix`、`fuzzy`、`range` / `gt` / `gte` / `lt` / `lte`、`wildcard`、`prefix`、`exists`、`matchAll`、`nativeQuery`、`when`<br/>• **逻辑与复合**：`must`、`filter`、`should`、`mustNot`、`minimumShouldMatch`、`nested`（支持 `ChildScoreMode` 与 `InnerHits` 重载）、`geoDistance`、`knn`（支持 `filterConsumer` 预过滤）、`rescore`（二次精排重打分）<br/>• **建议器与高亮裁剪**：`highlight` / `highlightWithTags`、`completionSuggest`、`termSuggest`、`phraseSuggest`、`includes` / `excludes` / `fetchSource`、`collapse`（含 `innerHits`）、`trackTotalHits`、`routing`、`index` / `indices`<br/>• **排序与分页**：`orderByAsc` / `orderByDesc` / `orderByScore` / `orderByGeoDistance`、`page` / `from` / `size` / `searchAfter`<br/>• **多维统计与子聚合**：`termsAggregation`、`dateHistogramAggregation`（支持子聚合）、`filterAggregation`、`subAggregation`、`sumAggregation` / `avgAggregation` / `minAggregation` / `maxAggregation` / `cardinalityAggregation` / `statsAggregation` / `percentilesAggregation` |
| **`SearchBulkProcessor`** | `io.github.atengk.boot.search.bulk` | 实现 `SmartLifecycle`、`DisposableBean`、`AutoCloseable` 的生产级异步流式缓冲处理器：提供 `add` / `addAll` / `update` / `upsert` / `delete` / `addAction`、`flush`、`awaitClose`、`getQueueSize` / `getBufferBytes`，支持背压阻塞、瞬间错误指数退避重试（`maxRetries` / `retryBackoff`）、`BulkListener` 与 `BulkDeadLetterHandler` 死信分流 |
| **`SearchDataScopeHelper`** | `io.github.atengk.boot.search.util` | 数据权限 DSL 注入工具：提供 `applyScope(...)` 与 `applyUserOrDeptScope(...)` 将用户/部门维度行级权限规则静态安全织入 `SearchQueryBuilder` |

### 0.2 注解、强类型模型与配置/SPI 清单

| 类别 | 组件 / 标识 | 说明 |
| :--- | :--- | :--- |
| **文档映射注解** | `@SearchIndex`、`@SearchId`、`@SearchRouting` | 声明物理索引名（支持 `#{...}` SpEL 动态时间分表）、分片/副本数、主键字段与分片路由字段 |
| **视图与租户注解** | `@HighlightField`、`@IgnoreTenant` | `@HighlightField` 将命中高亮片段投影至响应 VO（严守持久实体明文不污染）；`@IgnoreTenant` 用于类/方法级显式放行全局租户过滤（支持透传至异步线程池） |
| **强类型模型** | `SearchQuery`、`SearchPage<T>`、`SearchHit<T>`、`SearchScrollResult<T>`、`SearchPointInTime`、`SearchBulkAction`（含 `ActionType`）、`SearchBulkResult`、`SearchBulkFailure`、`SearchBulkFailureItem`、`SearchTaskStatus`、`SearchIndexSettings`、`SearchIndexTemplate`、`SearchBucket`、`SearchNumericStats`、`SearchPercentilesResult`、`SearchSuggestResult`、`SearchInnerHit`、`OccVersion`、`HybridSearchOptions` | 100% 与底层 ES 客户端协议解耦：内置类型化聚合读取器（`getStats`、`getPercentiles`、`getTermsBuckets`、`getDateHistogramBuckets`、`getFilterBucket`、`getSuggestions`、`getInnerHits`）、`toPageResult()` 与 `.map()` 视图投影 |
| **核心枚举与异常** | `SearchEngineType`、`TenantIsolationMode`、`SearchRefreshPolicy`、`SearchHybridMode`、`SearchResultCode`（`60001~60017`）、`SearchException`、`SearchBulkException`、`SearchVersionConflictException`、`SearchPaginationExceededException` | 覆盖底层引擎切换（`ELASTICSEARCH` / `OPENSEARCH`）、多租户策略（`STRICT` / `FILTER_ONLY`）、刷新策略（`NONE` / `WAIT_UNTIL` / `IMMEDIATE`）、混合模式及保留因果 `cause` 链的受管业务异常体系 |
| **配置属性** | `AtengSearchProperties`（前缀 `ateng.boot.search.*`） | 强类型配置：`enabled`、`engine`、`uris`、`username`、`password`、`apiKey`、`pathPrefix`、`connectTimeout`、`socketTimeout`、`connectionRequestTimeout`、`maxConnections`、`maxConnectionsPerRoute`、`keepAlive`、`ssl.*`、`multiTenancy.*`、`pagination.*`、`bulk.*`、`observability.*` |
| **SPI 与可观测** | `SearchEngineClient`、`RestClientBuilderCustomizer`、`SearchInterceptor`、`BulkListener`、`BulkDeadLetterHandler`、`SearchCallback<R>`、`SearchHealthIndicator`、`SearchMetrics` | 全扩展面均标配 `@ConditionalOnMissingBean` 或集合注入；自动暴露 Actuator `/actuator/health` 集群绿黄红探测与 Micrometer `ateng.search.operation.time` / `query.time` / `bulk.time` |

---

## 1. 定位与安装引入

`ateng-boot-search` 对业务层彻底屏蔽底层 RestClient 与 `co.elastic.clients` DSL 细节：业务实体保持纯净 POJO，所有多租户行级隔离、OCC 版本并发冲突识别、慢查询脱敏日志、流式背压批量写以及 Elasticsearch 8.x / OpenSearch 协议差异（如 OpenSearch 下 `_pit`、`_msearch` 兼容与方言分流）均内聚在底座层统一治理。

### 方式一：通过 `ateng-boot-bom` 统一版本管理（推荐）

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.atengk</groupId>
            <artifactId>ateng-boot-bom</artifactId>
            <version>1.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-search</artifactId>
    </dependency>
</dependencies>
```

### 方式二：独立显式声明 Starter 版本

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-search</artifactId>
    <version>1.2.0</version>
</dependency>
```

---

## 2. 核心架构与入门骨架

### 2.1 自动装配与双引擎架构链路

1. `AtengSearchAutoConfiguration` 依据 `ateng.boot.search.engine` 与目标连接池/SSL 参数创建受管 `RestClient` 传输客户端，允许所有自定义 `RestClientBuilderCustomizer` Bean 介入（如 AWS IAM 签名、自定义代理、额外 Header）。
2. 根据引擎类型自动选择 `SearchEngineDialect`（`ELASTICSEARCH` 或 `OPENSEARCH`），构建核心 `SearchEngineClient` 并注入 `SearchTemplate`、`SearchMetrics` 与默认单例 `SearchBulkProcessor`（遵循 `SmartLifecycle` 优雅停机排空）。
3. **跨模块能力**：所有核心读、写、聚合与 DDL 调用统一经过 `SearchInterceptor` 链、异常分类转译器与上下文管理器，异步线程自动透传 `TenantContextHolder` 与 `@IgnoreTenant` 状态。

### 2.2 基础接入配置与领域模型声明

**配置片段 (`application.yml`)：**

```yaml
ateng:
  boot:
    search:
      enabled: true
      engine: elasticsearch
      uris:
        - https://YOUR_SEARCH_HOST:9200
      username: YOUR_SEARCH_USERNAME
      password: ${SEARCH_PASSWORD:YOUR_SEARCH_PASSWORD}
      connect-timeout: 5s
      socket-timeout: 30s
      connection-request-timeout: 3s
      multi-tenancy:
        enabled: true
        column: tenantId
        isolation-mode: STRICT
```

**核心领域实体与视图 VO 模型（提取自 `ateng-boot-sample-search`）：**

```java
// 1. 静态分片 + 路由实体：ProductDoc
@Data
@SearchIndex(name = "product_index", shards = 3, replicas = 1)
public class ProductDoc {
    @SearchId
    private String id;
    private String title;
    private String description;
    private String category;
    @SearchRouting
    private String brand;          // 同品牌文档写入同一物理分片，加速路由点查
    private BigDecimal price;
    private Integer stock;
    private GeoPoint location;
    private List<Float> embedding; // AI 稠密向量特征
    private String tenantId;
    private LocalDateTime createTime;
    private List<SkuItem> skus;
}

// 2. SpEL 动态按天分表时序日志实体：AccessLogDoc
@Data
@SearchIndex(name = "access_log_#{T(java.time.LocalDate).now().format('yyyyMMdd')}")
public class AccessLogDoc {
    @SearchId
    private String id;
    private String path;
    private String clientIp;
    private String tenantId;
    private LocalDateTime timestamp;
}

// 3. 视图级 @HighlightField 安全投影响应 VO：严禁反向污染持久实体内存明文
@Data
public class ProductResponse {
    private String id;
    private String title;
    @HighlightField(target = "title")
    private String highlightedTitle;
    @HighlightField(target = "description")
    private String highlightedDescription;
    private Double score;
    private List<Object> sortValues;
}
```

---

## 3. 文档读写、安全并发与条件批量变更

### 3.1 写入幂等、刷新策略、OCC 乐观锁与点查防越权

**配置片段 (`application.yml`)：** 默认 `multi-tenancy.isolation-mode: STRICT` 保障点查与删改跨租户零越权；写后可见性可通过 `SearchRefreshPolicy`（`NONE` / `WAIT_UNTIL` / `IMMEDIATE`）按调用粒度显式控制。

```yaml
ateng:
  boot:
    search:
      multi-tenancy:
        enabled: true
        column: tenantId
        isolation-mode: STRICT
```

**核心代码（提取自 `ProductCatalogSearchScenario` 与 `SearchTemplate` 门面契约）：**

```java
// 1. 提取自 ProductCatalogSearchScenario：全量索引、HEAD 存在探测、路由主键点查与 _mget 批量拉取
searchTemplate.save(product);
boolean exists = searchTemplate.existsById(ProductDoc.class, id, routing);
ProductDoc doc = searchTemplate.getById(ProductDoc.class, id, routing);
List<ProductDoc> batch = searchTemplate.getByIds(ProductDoc.class, ids, routing);

// 2. 提取自 ProductCatalogSearchScenario：局部更新库存 & 基于 OccVersion 的乐观并发安全改价
searchTemplate.update(ProductDoc.class, id, routing, Map.of("stock", newStock));
searchTemplate.update(ProductDoc.class, id, routing, Map.of("price", newPrice), occVersion);

// 3. SearchTemplate 幂等仅创建防覆盖写入 (op_type=create + 周期刷新可见)
searchTemplate.createIfAbsent(product, SearchRefreshPolicy.WAIT_UNTIL);
```

**边界与机制：**
- **OCC 版本凭据获取**：通过 `searchTemplate.getHitById(...)` 或搜索结果 `hit.getOccVersion()` 提取 `seqNo` 与 `primaryTerm`，写入或局改碰撞版本时底层精确转译并抛出 `SearchVersionConflictException`。
- **STRICT 隔离闭环**：开启 `STRICT`（默认）时，单点 `getById` 验证返回文档所属租户；`existsById` 自动降级为带租户过滤条件的轻量 `_count` 探测；单点 `update` / `deleteById` 前置执行 `_mget` 归属核验，彻底阻断 IDOR 横向越权。

### 3.2 参数化 UpdateByQuery 与异步条件清理

**配置片段：** 支持同步阻塞（`updateByQuery` / `deleteByQuery`）与异步非阻塞（`updateByQueryAsync` / `deleteByQueryAsync`），`SearchRefreshPolicy.WAIT_UNTIL` 自动转换为端点兼容的布尔 `refresh=true`。

**核心代码（提取自 `LogAnalyticsAggScenario`）：**

```java
// 参数化 Painless Script updateByQuery + WAIT_UNTIL 写后立即刷新可见，天然免疫脚本注入
SearchQuery filter = SearchQueryBuilder.create().term("path", path).build();
long updatedCount = searchTemplate.updateByQuery(
        filter,
        Map.of("clientIp", newClientIp),
        AccessLogDoc.class,
        SearchRefreshPolicy.WAIT_UNTIL);

// 按时间边界批量清理过期日志（自动叠加当前上下文租户过滤）
SearchQuery purgeFilter = SearchQueryBuilder.create().lt("timestamp", cutoffTime).build();
long deletedCount = searchTemplate.deleteByQuery(purgeFilter, AccessLogDoc.class);
```

**边界与机制：**
- `updateByQuery` 在底座将 `Map<String, Object>` 键值对统一装配为参数化 `ctx._source[k] = params[k]` Painless 脚本，严禁外部拼接原始脚本字符串；在大体量数据订正时建议使用 `updateByQueryAsync` 结合 `indices().waitForTask(...)` 治理耗时。

---

## 4. DSL 全文检索、VO 安全投影与 AI 混合重排

### 4.1 多字段全文高亮、`@HighlightField` VO 安全投影与 `_source` 裁剪

**配置片段：** 无需额外配置；在响应 VO 字段标注 `@HighlightField(target = "fieldName")` 即可解耦持久实体与富文本标签输出。

**核心代码（提取自 `ProductCatalogSearchScenario`）：**

```java
// 1. 视图级 @HighlightField 安全投影：查询 ProductDoc 索引并直接映射填充 ProductResponse
SearchQuery voQuery = SearchQueryBuilder.create()
        .multiMatch(keyword, "title", "description")
        .highlight("title", "description")
        .build();
SearchPage<ProductResponse> page = searchTemplate.search(voQuery, ProductDoc.class, ProductResponse.class);
List<ProductResponse> items = page.getHits().stream().map(hit -> {
    ProductResponse vo = hit.getContent();
    if (vo != null) {
        vo.setScore(hit.getScore());
        if (hit.getSortValues() != null) {
            vo.setSortValues(new ArrayList<>(hit.getSortValues()));
        }
    }
    return vo;
}).filter(Objects::nonNull).toList();

// 2. _source 字段白名单与高维 embedding 向量字段剔除，大幅降低反序列化与网络带宽开销
SearchQuery trimmedQuery = SearchQueryBuilder.create()
        .when(keyword != null && !keyword.isBlank(), q -> q.multiMatch(keyword, "title", "description"))
        .when(keyword == null || keyword.isBlank(), SearchQueryBuilder::matchAll)
        .includes("id", "title", "description", "category", "brand", "price", "stock", "status")
        .excludes("embedding")
        .build();
```

**边界与机制：**
- **内存真明文不变量**：`search(query, ProductDoc.class, ProductResponse.class)` 在反射回填高亮 `<em>...</em>` 碎片时严格作用于 `ProductResponse` 视图对象，确保 `ProductDoc` 原始字段不被任何 HTML 标记污染。

### 4.2 Nested 嵌套 SKU、字段折叠、`InnerHits`、`Rescore` 与 Geo 搜索

**配置片段 (`application.yml`)：** 若需在日志中隐匿高维特征向量防日志刷屏，确认开启 `mask-vector-in-logs: true`（默认开启）。

```yaml
ateng:
  boot:
    search:
      observability:
        mask-vector-in-logs: true
```

**核心代码（提取自 `ProductCatalogSearchScenario`）：**

```java
// 1. Nested 嵌套独立作用域检索（可携带 InnerHits 名称抓取命中子项）
SearchQuery nestedQuery = SearchQueryBuilder.create()
        .nested("skus", ChildScoreMode.Avg, sub -> sub
                .term("skus.color", color)
                .gte("skus.price", minPrice))
        .build();

// 2. 字段折叠去重 + 价格降序（提取自 ProductCatalogSearchScenario.searchWithCollapse）
SearchQueryBuilder builder = SearchQueryBuilder.create();
if (brand != null && !brand.isBlank()) {
    builder.term("brand", brand);
}
SearchQuery collapseQuery = builder
        .collapse(collapseField)
        .orderByDesc("price")
        .build();

// 3. Geo 地理空间半径筛选并按距中心点由近及远排序
SearchQuery geoQuery = SearchQueryBuilder.create()
        .geoDistance("location", lat, lon, distance)
        .orderByGeoDistance("location", lat, lon)
        .build();
```

**边界与机制：**
- Nested 内部命中可通过 `hit.getInnerHits("skus")` 返回强类型 `List<SearchInnerHit>`（保底返回空集合），配合 `rescore(windowSize, rescoreConsumer)` 可对 TopN 粗排候选执行精排二次重打分。

### 4.3 kNN 向量检索、`CLIENT_RRF` 零许可证 AI 混合重排与 `_msearch`

**配置片段：** 针对社区版/基础许可证 Elasticsearch 8.x 集群，默认 `SearchHybridMode.CLIENT_RRF` 通过单次 `_msearch` 并发发起 BM25 词法与 kNN 向量两路召回，在内存端执行 RRF 倒数排名融合，**零付费许可证依赖**。

```yaml
ateng:
  boot:
    search:
      observability:
        slow-query-threshold: 1s
        log-slow-query-dsl: true
        mask-vector-in-logs: true
```

**核心代码（提取自 `ProductCatalogSearchScenario`）：**

```java
// 1. AI 混合检索融合 (BM25 + kNN)
SearchQuery textQuery = SearchQueryBuilder.create()
        .multiMatch(keyword, "title", "description")
        .highlight("title", "description")
        .excludes("embedding")
        .build();
HybridSearchOptions options = HybridSearchOptions.builder()
        .mode(SearchHybridMode.CLIENT_RRF) // 默认值，亦可选 CLIENT_WEIGHTED 或 NATIVE_RRF
        .rrfRankConstant(60)
        .topK(safeK)
        .build();
SearchPage<ProductDoc> hybridPage = searchTemplate.hybridSearch(
        textQuery, "embedding", vector, safeK, Math.max(20, safeK * 4), options, ProductDoc.class);

// 2. _msearch 单次 HTTP 往返批量异构检索（如第一路商品明细 + 第二路聚合计数）
List<SearchPage<ProductDoc>> multiPages = searchTemplate.msearch(
        List.of(keywordQuery, categoryStatQuery), ProductDoc.class);
```

**边界与机制：**
- `SearchHybridMode.NATIVE_RRF` 依赖 Elasticsearch 8.8+ 商业许可证；社区/免费版集群或 OpenSearch 请保持默认 `CLIENT_RRF` 或使用 `CLIENT_WEIGHTED`。`msearch` 严格校验各子请求所属租户，结果列表顺序与请求入参完全对齐。

---

## 5. 多维聚合、智能建议器与一致快照深翻页

### 5.1 强类型聚合大盘（Stats / Percentiles / Cardinality / DateHistogram 子聚合）

**配置片段：** 无需额外配置。所有聚合解析均通过 `SearchAggregationConverter` 收敛于强类型模型，100% 消除业务代码中 `co.elastic.clients` 原生类型强转。

**核心代码（提取自 `ProductCatalogSearchScenario`、`LogAnalyticsAggScenario` 与 `SearchSampleConvertHelper`）：**

```java
// 1. 商品价格统计与类目嵌套子聚合（Cardinality + Stats + Percentiles P50/P95/P99）
SearchQuery dashboardQuery = SearchQueryBuilder.create()
        .cardinalityAggregation("unique_brands", "brand")
        .statsAggregation("price_stats", "price")
        .percentilesAggregation("price_pct", "price", 50.0, 95.0, 99.0)
        .termsAggregation("by_category", "category", 10, sub -> sub
                .statsAggregation("cat_price_stats", "price")
                .percentilesAggregation("cat_price_pct", "price", 50.0, 95.0))
        .size(0)
        .build();
SearchPage<ProductDoc> page = searchTemplate.search(dashboardQuery, ProductDoc.class);

// 强类型结果读取（保底非空集合，无数据不返 null 集合）
Double uniqueBrands = page.getMetricValue("unique_brands");
SearchNumericStats priceStats = page.getStats("price_stats");
SearchPercentilesResult pricePct = page.getPercentiles("price_pct");
List<SearchBucket> categoryBuckets = page.getTermsBuckets("by_category");

// 2. 日志按日直方图 + 每日独立 IP 嵌套子聚合读取
List<SearchBucket> dailyBuckets = logPage.getDateHistogramBuckets("daily_traffic");
```

**边界与机制：**
- 无数据时 `getTermsBuckets` / `getDateHistogramBuckets` / `getSuggestions` 保底返回不可变空列表；`getStats` / `getPercentiles` / `getMetricValue` 可能为 `null`，业务转换层需前置判空（参考 `SearchSampleConvertHelper`）。

### 5.2 智能建议器（Prefix / Completion / Term / Phrase Suggester）

**配置片段：** 无需额外配置；建议项统一封装为 `SearchSuggestResult`（包含 `text`、`score`、`freq`、`highlighted`、`id`）。

**核心代码（提取自 `ProductCatalogSearchScenario`）：**

```java
SearchQuery suggestQuery = SearchQueryBuilder.create()
        .termSuggest("title_term", "title", text, safeSize)
        .phraseSuggest("title_phrase", "title", text, safeSize)
        .size(0)
        .build();
Map<String, List<SearchSuggestResult>> suggestions =
        searchTemplate.search(suggestQuery, ProductDoc.class).getSuggestions();
```

**边界与机制：**
- `termSuggest` 与 `phraseSuggest` 直接复用标准 `text` 字段完成单字纠错与短语纠错；`completionSuggest` 前缀极速联想需要目标索引字段 Mapping 为 `completion` 类型，并支持配置 `skipDuplicates` 去重；未命中建议词时 `page.getSuggestions()` 对应列表保底返回空集合。

### 5.3 浅分页防 OOM 熔断、`Search-After` 与 `PIT` 一致性快照遍历

**配置片段（防 OOM 安全阈值配置）：**

```yaml
ateng:
  boot:
    search:
      pagination:
        max-result-window: 10000     # 传统浅分页 from + size 硬熔断上限
        max-search-after-size: 1000  # 单次游标分页最大批量限制
```

**核心代码（提取自 `ProductCatalogSearchScenario`）：**

```java
// 1. 浅分页自动适配标准 PageResult<T>
PageResult<ProductDoc> pageResult = searchTemplate.search(
        SearchQueryBuilder.create().matchAll().page(page, size).orderByDesc("createTime").build(),
        ProductDoc.class).toPageResult();

// 2. 基于 Point-In-Time (PIT) 一致性快照安全全量遍历，executeWithPointInTime 在 finally 确定性关闭 PIT
SearchQuery pitQuery = SearchQueryBuilder.create().matchAll().orderByAsc("id").build();
List<SearchHit<ProductDoc>> collected = searchTemplate.executeWithPointInTime(
        ProductDoc.class, Duration.ofMinutes(1), pit -> {
            List<SearchHit<ProductDoc>> all = new ArrayList<>();
            List<String> cursor = null;
            int guardLoops = 0;
            while (guardLoops < 20) {
                guardLoops++;
                SearchScrollResult<ProductDoc> batch =
                        searchTemplate.searchAfterWithPit(pit, pitQuery, cursor, safeSize, ProductDoc.class);
                if (batch.getHits() != null) {
                    all.addAll(batch.getHits());
                }
                List<String> nextCursor = batch.getNextSortValues();
                if (!batch.hasNext() || nextCursor == null || nextCursor.isEmpty() || nextCursor.equals(cursor)) {
                    break;
                }
                cursor = nextCursor;
            }
            return all;
        });
```

**边界与机制：**
- 当浅分页 `from + size > maxResultWindow` 或单次游标 `size > maxSearchAfterSize` 时，底座直接在构造执行前 Fail-Fast 抛出 `SearchPaginationExceededException`。
- `searchAfter` / `searchAfterWithPit` 必须包含唯一决定性排序字段（推荐追加 `id` 作为 Tie-Breaker）；`executeWithPointInTime` 在 `finally` 块自动调用 `closePointInTime` 释放服务端快照句柄，防止堆外内存泄漏。

---

## 6. 严宽批量写入、流式缓冲与零停机索引运维生命周期

### 6.1 严格/宽容批量 (`indexBatch` / `indexBatchLenient`) 与受管 `SearchBulkProcessor`

**配置片段 (`application.yml`)：**

```yaml
ateng:
  boot:
    search:
      bulk:
        enabled: true
        batch-size: 1000
        flush-interval: 1s
        buffer-size: 5MB
        concurrent-requests: 1
        max-queue-capacity: 10000
        await-close-timeout: 10s
        max-retries: 2
        retry-backoff: 200ms
```

**核心代码（提取自 `BulkIngestionScenario`）：**

```java
// 1. 同步严格/宽容批量写入（indexBatch 任一失败即抛 SearchBulkException；indexBatchLenient 收敛局部失败明细）
SearchBulkResult strictResult = searchTemplate.indexBatch(products);
SearchBulkResult lenientResult = searchTemplate.indexBatchLenient(products);

// 2. 受管异步流式缓冲写入、带路由删除与显式排空
searchBulkProcessor.add(product);
searchBulkProcessor.addAction(SearchBulkAction.builder()
        .type(SearchBulkAction.ActionType.INDEX)
        .index(SearchMetadataHelper.resolveIndexName(ProductDoc.class))
        .id(id)
        .routing(routing)
        .document(fields)
        .entityClass(ProductDoc.class)
        .build());
searchBulkProcessor.delete(id, routing, ProductDoc.class);
searchBulkProcessor.flush();
```

**边界与机制：**
- **瞬时重试与死信分流**：遇到网络抖动或集群 `429 Too Many Requests` / `503` 瞬态错误时，`SearchBulkProcessor` 自动按 `maxRetries` 与指数退避 `retryBackoff` 执行可控重试；重试耗尽或遇到映射类型冲突等不可恢复错误时，精确下发至 `BulkDeadLetterHandler`（支持局部单条 `onItemFailure` 与整批 `onBatchFailure`）。
- **优雅停机与并发限制**：实现 Spring `SmartLifecycle`，应用关闭自动触发排空（最长等待 `awaitCloseTimeout`），严禁应用自行创建野线程驱动调度。

### 6.2 模板 Governance、运行期 Settings 热改、原子别名与异步 Reindex Task 治理

**配置片段：** 无需额外配置，直接通过 `searchTemplate.indices()` 获取 `SearchIndexOperations` 门面操作集群元数据。

**核心代码（提取自 `ZeroDowntimeReindexScenario` 与样例测试契约）：**

```java
SearchIndexOperations indices = searchTemplate.indices();

// 1. 组件模板 & 索引模板标准化治理
indices.putComponentTemplate("sample_base_settings", "{\"template\":{\"settings\":{\"number_of_replicas\":0}}}");
indices.putIndexTemplate("sample_log_template",
        "{\"index_patterns\":[\"access_log_*\"],\"template\":{\"mappings\":{\"properties\":{\"path\":{\"type\":\"keyword\"}}}}}");

// 2. 运行期 Settings 读取（零 ES 原生类泄漏）与动态热更新
SearchIndexSettings settings = indices.getSettings("product_index");
indices.putSettings("product_index", "{\"index\":{\"refresh_interval\":\"5s\"}}");

// 3. 异步 Reindex + Task 进度轮询等待与精细别名原子热切换 (swapAlias)
String taskId = indices.reindexAsync("product_index", "product_index_v2");
SearchTaskStatus status = indices.waitForTask(taskId, Duration.ofSeconds(30), Duration.ofMillis(100));
indices.addAlias("product_index_v2", "product_active", true);
indices.swapAlias("product_active", "product_index", "product_index_v2");
```

**边界与机制：**
- `swapAlias(aliasName, oldIndex, newIndex)` 在底层映射为单一原子 `_aliases` `UpdateAliasesRequest`，在单个请求事务内同时完成旧索引解绑与新索引挂载，实现 0ms 查询空窗；亦可通过 `indices.getIndexTemplate(templateName)` 查询模板元数据，或调用 `indices.cancelTask(taskId)` 中止不再需要的后台迁移任务。

---

## 7. SPI 扩展、多租户安全网与可观测性

### 7.1 SPI 扩展矩阵（`SearchInterceptor` / `RestClientBuilderCustomizer` / `BulkListener` / `BulkDeadLetterHandler`）

| SPI 接口 | 注册方式与默认行为 | 典型应用场景与回调签名 |
| :--- | :--- | :--- |
| **`RestClientBuilderCustomizer`** | 注册为 Spring Bean（支持多个，按序执行） | 注入 AWS SigV4 签名器、自定义 SSL/Proxy、公共请求头 |
| **`SearchInterceptor`** | 注册为 Spring Bean（集合链路执行） | 审计合规记录、慢查询报警、`beforeExecute` / `afterExecute` / `onError` 自定义横切拦截 |
| **`BulkListener`** | 注册为 Spring Bean 或 `Builder.listener(...)`（默认 `NOOP`） | 批次前后监控：`beforeBulk(execId, count)`、`afterBulk(execId, count, SearchBulkResult)`、`afterBulk(execId, count, Throwable)` |
| **`BulkDeadLetterHandler`** | 注册为 Spring Bean（默认 `LOGGING`） | 死信转储入库 / 投递 MQ：`onBatchFailure(execId, actions, failure)`、`onItemFailure(execId, SearchBulkFailureItem)` |
| **`SearchCallback<R>`** | 传参给 `searchTemplate.execute(...)` | 直接获取原生 `ElasticsearchClient` 执行自定义复合协议操作并继承异常转译与耗时观测 |

**死信处理器核心代码（提取自 `SampleBulkDeadLetterRecorder`）：**

```java
@Slf4j
@Component
public class SampleBulkDeadLetterRecorder implements BulkDeadLetterHandler {

    @Override
    public void onBatchFailure(long executionId, List<SearchBulkAction> actions, Throwable failure) {
        int count = actions != null ? actions.size() : 0;
        String summary = String.format("BATCH[exec=%d, actions=%d, error=%s]",
                executionId, count, failure != null ? failure.getMessage() : "unknown");
        log.warn("[SampleDeadLetterRecorder] {}", summary);
    }

    @Override
    public void onItemFailure(long executionId, SearchBulkFailureItem item) {
        if (item == null || item.getFailure() == null) {
            return;
        }
        SearchBulkFailure f = item.getFailure();
        String actionType = item.getAction() != null && item.getAction().getType() != null
                ? item.getAction().getType().name()
                : "UNKNOWN";
        String summary = String.format("ITEM[exec=%d, action=%s, index=%s, id=%s, status=%s, reason=%s]",
                executionId, actionType, f.getIndex(), f.getId(), f.getStatus(), f.getErrorReason());
        log.warn("[SampleDeadLetterRecorder] {}", summary);
    }
}
```

### 7.2 `@IgnoreTenant` 租户豁免与 `SearchDataScopeHelper` 数据权限

**配置片段 (`application.yml`)：** 默认 `enabled: true` 与 `isolation-mode: STRICT` 全局生效，可结合 `@IgnoreTenant` 或 `SearchDataScopeHelper` 实现细粒度豁免与行级数据授权。

```yaml
ateng:
  boot:
    search:
      multi-tenancy:
        enabled: true
        column: tenantId
        isolation-mode: STRICT
```

**核心代码：**

```java
// 1. 服务方法级 @IgnoreTenant 显式放行全局租户过滤（由 searchIgnoreTenantAdvisor AOP 接管并还原上下文）
@IgnoreTenant
public long countGlobalProducts() {
    return searchTemplate.count(ProductDoc.class);
}

// 2. 检索构建阶段织入“用户本人 OR 所属部门”行级数据权限过滤（自动落入 filter 分支享受查询缓存）
SearchQueryBuilder builder = SearchQueryBuilder.create().match("title", keyword);
SearchDataScopeHelper.applyUserOrDeptScope(builder, "createBy", currentUserId, "deptId", allowedDeptIds);
SearchPage<ProductDoc> scopedPage = searchTemplate.search(builder.build(), ProductDoc.class);
```

**边界与机制：**
- **双模豁免与异步透传**：`@IgnoreTenant`（包 `io.github.atengk.boot.search.annotation.IgnoreTenant`）支持标注在搜索实体类（静态元数据免隔离）或服务类/方法（AOP 包裹 `TenantContextHolder.runWithoutTenant(...)` 并确定性还原），在入队 `SearchBulkProcessor` 时自动捕获豁免标记向下透传。
- **空权限防御**：当 `allowedValues` 或 `deptIds` 为空且无 `userId` 时，`SearchDataScopeHelper` 安全不追加条件或按调用方前置判断收敛，避免空集合拼接无效 `terms` 子句。

### 7.3 可观测性与非功能边界

**配置片段 (`application.yml`)：**

```yaml
management:
  health:
    search:
      enabled: true
  metrics:
    enable:
      search: true
ateng:
  boot:
    search:
      observability:
        slow-query-threshold: 1s
        log-slow-query-dsl: true
        mask-vector-in-logs: true
```

**机制说明：**
- **Actuator 健康检查 (`SearchHealthIndicator`)**：受 `management.health.search.enabled`（默认 `true`）控制，通过 `/actuator/health` 暴露集群状态（`GREEN`/`YELLOW` 为 `UP`，`RED`/连通异常为 `DOWN`）及节点数、分片活跃率。
- **Micrometer 全指标 (`SearchMetrics`)**：受 `management.metrics.enable.search`（默认 `true`）控制，自动采录 Timer 指标：`ateng.search.operation.time`（含 `operation`、`outcome` 标签，精确统计每类读写与 DDL）、`ateng.search.query.time` 与 `ateng.search.bulk.time`。
- **虚拟线程防 Pinning 与慢查询脱敏防泄漏**：内部连接、缓冲同步临界区使用 `ReentrantLock` 替代 `synchronized`，适配 JDK 21+ 虚拟线程；慢查询触发 `slow-query-threshold` 打印 DSL 时自动脱敏高维向量数组（`mask-vector-in-logs=true`）。

---

## 8. 完整 YAML 配置项全表（`AtengSearchProperties`）

| 配置项全路径 (`ateng.boot.search.*`) | 类型 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `enabled` | `boolean` | `true` | 是否启用 Search 自动装配 |
| `engine` | `SearchEngineType` | `ELASTICSEARCH` | 底层搜索引擎方言（`ELASTICSEARCH` / `OPENSEARCH`） |
| `uris` | `List<String>` | `["http://localhost:9200"]` | 集群节点地址列表 |
| `username` | `String` | `null` | Basic Auth 用户名（可选，与 `apiKey` 二选一） |
| `password` | `String` | `null` | Basic Auth 密码（支持环境变量占位符注入） |
| `api-key` | `String` | `null` | Base64 编码的 API Key 鉴权令牌 |
| `path-prefix` | `String` | `null` | 反向代理统一路径前缀（如 `/es-proxy`） |
| `connect-timeout` | `Duration` | `5s` | HTTP 建连超时时间 |
| `socket-timeout` | `Duration` | `30s` | Socket 读写超时时间 |
| `connection-request-timeout` | `Duration` | `3s` | 从连接池借用连接等待超时时间 |
| `max-connections` | `int` | `100` | HTTP 连接池最大总连接数 |
| `max-connections-per-route` | `int` | `50` | 单节点（路由）最大并发连接数 |
| `keep-alive` | `Duration` | `30s` | 空闲长连接最大保活时间 |
| `ssl.enabled` | `boolean` | `false` | 是否启用 HTTPS 传输加密 |
| `ssl.trust-store-path` | `String` | `null` | JKS / PKCS12 信任库路径（支持 `classpath:` 与文件路径） |
| `ssl.trust-store-password` | `String` | `null` | 信任库访问密码 |
| `ssl.insecure-skip-verify` | `boolean` | `false` | 是否跳过证书链校验（仅限本地/内网自签，生产严禁开启） |
| `multi-tenancy.enabled` | `boolean` | `true` | 是否开启多租户自动隔离 |
| `multi-tenancy.column` | `String` | `tenant_id` | 租户隔离物理字段名（实体若使用驼峰 `tenantId` 需保持对齐） |
| `multi-tenancy.isolation-mode` | `TenantIsolationMode` | `STRICT` | 多租户隔离模式（`STRICT` 严格防越权闭环 / `FILTER_ONLY` 仅写注入与检索过滤） |
| `pagination.max-result-window` | `int` | `10000` | 传统浅分页 `from + size` 最大安全返回窗口 |
| `pagination.max-search-after-size` | `int` | `1000` | `Search-After` 单次深度滚动拉取最大条数 |
| `bulk.enabled` | `boolean` | `true` | 是否自动装配默认全局 `SearchBulkProcessor` 单例 |
| `bulk.batch-size` | `int` | `1000` | 单批次触发提交的最大文档数阈值 |
| `bulk.flush-interval` | `Duration` | `1s` | 定时异步排空缓冲间隔 |
| `bulk.buffer-size` | `DataSize` | `5MB` | 单批次序列化载荷最大字节阈值 |
| `bulk.concurrent-requests` | `int` | `1` | 批量写入并行工作线程并发上限 |
| `bulk.max-queue-capacity` | `int` | `10000` | 阻塞缓冲队列最大背压容量 |
| `bulk.await-close-timeout` | `Duration` | `10s` | 停机/关闭前排空剩余缓冲的最长等待超时 |
| `bulk.max-retries` | `int` | `2` | 遇到网络或 429/503 瞬态失败时最大重试次数 |
| `bulk.retry-backoff` | `Duration` | `200ms` | 指数退避重试初始间隔 |
| `observability.slow-query-threshold` | `Duration` | `1s` | 慢查询告警与 DSL 打印阈值 |
| `observability.log-slow-query-dsl` | `boolean` | `true` | 触发慢查询时是否打印请求 DSL 日志 |
| `observability.mask-vector-in-logs` | `boolean` | `true` | 日志输出 DSL 时是否自动遮蔽高维向量数组载荷 |

---

## 9. 可运行 Sample 与领域文档闭环

- **1:1 官方全场景沙箱工程**：请直接运行或参阅 **[ateng-boot-sample-search](../ateng-boot-samples/ateng-boot-sample-search/README.md)**，内置 4 个领域 REST Controller 与离线/真实集群双模自动化验证。
- **领域统一词汇表与架构决策记录**：
  - 词汇契约：**[ateng-boot-search/CONTEXT.md](./CONTEXT.md)**（全系统拓扑见 **[CONTEXT-MAP.md](../CONTEXT-MAP.md)**）
  - 演进 ADR：**[ADR-0053 双引擎检索与分析底座](../docs/adr/0053-enterprise-dual-engine-search-and-analytics-architecture.md)**、**[ADR-0084 全场景深化加固与弹性分析架构](../docs/adr/0084-search-full-scenario-hardening-and-resilient-analytics-architecture.md)**、**[ADR-0106 Multi-Controller 沙箱与五维文档规范](../docs/adr/0106-search-sample-multi-controller-and-documentation-specification-architecture.md)**

```bash
# 快速运行 Search 核心单元测试与 Sample Web 全控制器离线集成回归
mvn test -pl ateng-boot-search,ateng-boot-samples/ateng-boot-sample-search -am -T 1C -nsu -B
```
