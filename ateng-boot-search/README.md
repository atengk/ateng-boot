# ateng-boot-search 基础设施 Starter

> 工业级双引擎全文检索与流批分析基础设施 Starter，基于 Elasticsearch 8.x 官方原生 Java 客户端深度封装，提供直觉强类型操作门面与双轨分页模型，内置乐观并发控制 (OCC)、双模批量写入治理、流式非阻塞 BulkProcessor、声明式高亮富文本自动回填、多租户行级隐式隔离、条件驱动批量清理、短语与前缀联想、Nested SKU 精准嵌套作用域、Collapse 字段折叠去重、DateHistogram 时序直方图分析、服务端 Reindex 平滑数据迁移、Lucene 段物理刷新、原子别名热切换、云原生 Actuator 2 秒超时熔断健康探针与 Micrometer 黄金度量指标体系。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.search.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **文档新增/替换** | 单文档索引保存 | `SearchTemplate` | `ateng.boot.search.enabled` | `index(T doc)` / `index(String, T)` / `save(T doc)` / `save(String, T)`，自动解析 `@SearchIndex` 索引名与 `@SearchId` 文档 ID，自动提取路由键 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **条件更新/OCC** | 乐观并发控制条件更新 | `SearchTemplate`<br>`OccVersion` | - | `index(T, OccVersion)` / `update(Class<?>, String, String, Map<String, Object>, OccVersion)`，基于 `seq_no` 与 `primary_term` 防写覆盖，版本冲突抛出 `SearchVersionConflictException` | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **轻量存在探测** | 主键存在性探测 (HEAD) | `SearchTemplate` | - | `existsById(Class<?>, String)` / `existsById(Class<?>, String, String)` / `existsById(String, String, String)`，映射 HTTP HEAD 协议端点，0 报文体开销快速判定文档存在性 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **精确计数统计** | 条件文档计数 (Count) | `SearchTemplate` | - | `count(SearchQuery, Class<?>)` / `count(String, SearchQuery)`，直达 ES `_count` 专用端点，免除全文打分与文档正文载荷加载 | `LogAnalyticsAggScenario`<br>`LogAnalyticsController` |
| **精准主键检索** | 主键实体查询 | `SearchTemplate` | - | `getById(Class<T>, String)` / `getById(Class<T>, String, String)` / `getById(String, Class<T>, String, String)`，根据文档 ID 与分片路由键精准定位文档实体，不存在返回 `null`；路由防漏 Fail-Fast 拦截 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **完整命中载荷** | 元数据命中文档模型 | `SearchTemplate`<br>`SearchHit<T>` | - | `getHitById(Class<T>, String)` / `getHitById(Class<T>, String, String)` / `getHitById(String, Class<T>, String, String)`，返回包含打分、版本元数据、高亮与排序凭据的 `SearchHit<T>` | `SearchTemplateMockServerTest` |
| **批量主键检索** | 批量实体拉取 (MGet) | `SearchTemplate` | - | `getByIds(Class<T>, Collection<String>)` / `getByIds(Class<T>, Collection<String>, String)` / `getByIds(String, Class<T>, Collection<String>, String)`，直达 `_mget` 批量端点消除 N+1 网络往返，严格过滤 null 元素，保底返回非空列表；支持分片路由与防漏校验 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **批量元数据检索** | 批量元数据拉取 (MGet Hits) | `SearchTemplate`<br>`SearchHit<T>` | - | `getHitsByIds(Class<T>, Collection<String>)` / `getHitsByIds(Class<T>, Collection<String>, String)` / `getHitsByIds(String, Class<T>, Collection<String>, String)`，单次网络往返批量拉取包含 `_version`、`_seq_no`、`_primary_term` 的完整元数据模型列表；支持分片路由与防漏校验 | `SearchTemplateMockServerTest` |
| **局部增量更新** | 字段级 Partial Update | `SearchTemplate` | - | `update(Class<?>, String, Map<String, Object>)` / `update(Class<?>, String, String, Map<String, Object>)`，局部更新指定字段，支持 `docAsUpsert` | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **主键物理删除** | 单文档物理删除 | `SearchTemplate` | - | `deleteById(Class<?>, String)` / `deleteById(Class<?>, String, String)` / `deleteById(String, String, String)`，物理删除单条文档，路由防漏守卫 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **条件批量清理** | 条件物理清理 (Delete-By-Query) | `SearchTemplate` | - | `deleteByQuery(SearchQuery, Class<?>)` / `deleteByQuery(String, SearchQuery)`，直达 `_delete_by_query` 端点，强制隐式织入多租户过滤，默认配置 `conflicts = "proceed"` 并发容错，返回删除文档计数 | `LogAnalyticsAggScenario`<br>`LogAnalyticsController` |
| **严格批量写入** | 严格快速失败批量操作 | `SearchTemplate`<br>`SearchBulkResult` | - | `indexBatch(Collection<T>)` / `updateBatch(Collection<T>)` / `deleteBatch(Class<?>, Collection<String>)`，任一文档失败立即抛出强类型 `SearchBulkException` 阻断脏数据扩散 | `BulkIngestionScenario`<br>`BulkIngestionController` |
| **宽容批量写入** | 容错宽容批量操作 | `SearchTemplate`<br>`SearchBulkResult` | - | `indexBatchLenient(...)` / `updateBatchLenient(...)` / `deleteBatchLenient(...)`，返回包含成功总数与失败明细快照的 `SearchBulkResult`，专供死信隔离与补偿重试 | `BulkIngestionScenario`<br>`BulkIngestionController` |
| **流式缓冲写入** | 异步非阻塞批量处理器 | `SearchBulkProcessor` | `ateng.boot.search.bulk.enabled`<br>`ateng.boot.search.bulk.batch-size`<br>`ateng.boot.search.bulk.flush-interval`<br>`ateng.boot.search.bulk.buffer-size` | `add(T doc)` / `addAll(Collection<T> docs)` / `flush()` / `flushAsync()`，基于数量、时间间隔与内存容量三重阈值异步后台刷盘，停机安全排空 | `BulkIngestionScenario`<br>`BulkIngestionController` |
| **标准浅分页** | 浅分页与刚性熔断 | `SearchTemplate`<br>`SearchPage<T>` | `ateng.boot.search.pagination.max-result-window` | `search(SearchQuery, Class<T>)` / `search(String, SearchQuery, Class<T>)`，返回标准分页载荷 `SearchPage<T>`（支持 `.toPageResult()` 转为 Core 标准模型），超阈值自动抛出 `SearchPaginationExceededException` | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **深度滚动分页** | Search-After 游标滚动 | `SearchTemplate`<br>`SearchScrollResult<T>` | - | `searchAfter(SearchQuery, Object[], int, Class<T>)` / `searchAfter(String, SearchQuery, Object[], int, Class<T>)`，无内存膨胀深翻页，透传末尾排序凭据 `nextSortValues` | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **短语精准匹配** | Match Phrase 短语检索 | `SearchQueryBuilder` | - | `matchPhrase(String field, Object query)`，严格保证词项顺序与间隔邻近度，杜绝分词零散命中 | `SearchTemplateMockServerTest` |
| **前缀即时补全** | Match Phrase Prefix 联想 | `SearchQueryBuilder` | - | `matchPhrasePrefix(String field, Object query)` / `matchPhrasePrefix(String, Object, int)`，构建搜索输入框打字即联想补全提示 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **拼写模糊容错** | Fuzzy 模糊检索 | `SearchQueryBuilder` | - | `fuzzy(String field, String value)` / `fuzzy(String, String, String)`，基于 Levenshtein 编辑距离进行拼写手误容错查询 | `SearchTemplateMockServerTest` |
| **嵌套规格匹配** | Nested 独立作用域检索 | `SearchQueryBuilder` | - | `nested(String path, Consumer<SearchQueryBuilder>)` / `nested(String, ChildScoreMode, Consumer<SearchQueryBuilder>)`，多 SKU 子对象独立作用域精准匹配，杜绝跨对象交叉命中 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **结果折叠去重** | Field Collapsing 字段折叠 | `SearchQueryBuilder` | - | `collapse(String field)`，原生结果折叠去重，防止热门品牌/分类垄断搜索首屏，每个分组展示 Top 1 高分文档 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **声明式高亮** | @HighlightField 自动回填 | `SearchTemplate`<br>`@HighlightField` | - | 检索时指定 `.highlight(...)`，自动将匹配命中切片富文本回填至实体对应字段中，视图层开箱即用 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **多租户隔离** | 行级多租户隐式过滤 | `SearchTemplate`<br>`@SearchIgnoreTenant` | `ateng.boot.search.multi-tenancy.enabled`<br>`ateng.boot.search.multi-tenancy.column` | 自动结合 `TenantContextHolder` 注入 Term 过滤；支持实体级别声明 `@SearchIgnoreTenant` 或上下文跳过 | `SearchTemplateMockServerTest`<br>`LogAnalyticsController` |
| **地理空间计算** | Geo 距离筛选与排序 | `SearchQueryBuilder` | - | `.geoDistance("location", lat, lon, "5km")`<br>`.orderByGeoDistance("location", lat, lon)`，构建地理范围过滤与距离排序 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **AI 向量检索** | KNN 密集向量近邻召回 | `SearchQueryBuilder` | - | `.knn("embedding", vector, k, numCandidates)`，声明式构建稠密向量检索，赋能大模型 RAG 知识检索 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **度量聚合算子** | 统计与极值度量 | `SearchQueryBuilder` | - | `.termsAggregation(...)` / `.sumAggregation(...)` / `.avgAggregation(...)` / `.minAggregation(...)` / `.maxAggregation(...)`，链式声明基础统计指标 | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **时序直方图聚合** | DateHistogram 时间分桶 | `SearchQueryBuilder` | - | `.dateHistogramAggregation("daily_traffic", "timestamp", "1d")`，按日历时间间隔分桶统计流量走势，赋能时序审计大盘 | `LogAnalyticsAggScenario`<br>`LogAnalyticsController` |
| **复合布尔检索** | Fluent 风格查询构造器 | `SearchQueryBuilder` | - | `create()`、`.term()`、`.terms()`、`.match()`、`.multiMatch()`、`.range()`、`.gte()`、`.lte()`、`.matchAll()`，链式组装复杂 DSL | `ProductCatalogSearchScenario`<br>`ProductSearchController` |
| **结构面运维** | 索引与映射结构管理 | `SearchIndexOperations` | - | `create(index)` / `create(clazz)` / `exists(index)` / `exists(clazz)` / `delete(index)` / `delete(clazz)` / `putMapping(...)`，专职 DDL 操作 | `ZeroDowntimeReindexScenario`<br>`IndexLifecycleController` |
| **索引模板管理** | 时序模板与组件生命周期 | `SearchIndexOperations` | - | `putIndexTemplate(templateName, indexPattern, mappings)` / `deleteIndexTemplate(templateName)`，索引模板对称建立与销毁 | `ZeroDowntimeReindexScenario`<br>`IndexLifecycleController` |
| **服务端重索引** | 平滑数据复制 (Reindex) | `SearchIndexOperations` | - | `reindex(sourceIndex, targetIndex)` / `reindex(sourceIndex, targetIndex, SearchQuery filterQuery)`，服务端全量或增量条件数据复制 | `ZeroDowntimeReindexScenario`<br>`IndexLifecycleController` |
| **物理段刷新** | Lucene Segment 刷新 (Refresh) | `SearchIndexOperations` | - | `refresh(String index)` / `refresh(Class<?> clazz)`，显式触发 Lucene 物理段合并与刷新，提供写后即读确定性保证 | `ZeroDowntimeReindexScenario`<br>`IndexLifecycleController` |
| **原子别名切换** | 生产原子别名热切换 | `SearchIndexOperations` | - | `swapAlias(alias, oldIndex, newIndex)`，单次底层请求原子执行 remove + add，实现零空窗切源与零停机索引重建 | `ZeroDowntimeReindexScenario`<br>`IndexLifecycleController` |
| **动态时序分表** | SpEL 表达式时序索引 | `@SearchIndex` | - | `@SearchIndex(name = "access_log_#{T(java.time.LocalDate).now().format('yyyyMMdd')}")`，写入时自动计算目标物理索引 | `LogAnalyticsAggScenario`<br>`LogAnalyticsController` |
| **流式批处理监听** | 异步刷盘扩展点 (SPI) | `BulkListener` | - | `beforeBulk(long, int)` / `afterBulk(long, int, ...)` / `afterBulkError(long, int, Throwable)`，流式批处理全生命周期监听扩展 | `SearchBulkProcessorTest` |
| **原生底层逃生舱** | 官方原生 Client 执行回调 | `SearchTemplate`<br>`SearchCallback<R>` | - | `searchTemplate.execute(client -> ...)`，遇到官方极限复杂特性时可无损直达原生 API，不留死角 | `SearchTemplateMockServerTest` |
| **Actuator 探针** | 云原生健康状态探测 | `SearchHealthIndicator` | `management.health.search.enabled` | 探查集群健康度（GREEN/YELLOW 返回 UP，RED 返回 DOWN），内置 2 秒硬超时熔断机制防御假死 | `SearchHealthIndicatorTest`<br>`SampleSearchIntegrationTest` |
| **黄金度量指标** | Micrometer 核心度量采集 | `SearchMetrics` | `management.metrics.enable.search` | 采集检索耗时 `ateng.search.query.time` 与批量写入耗时 `ateng.search.bulk.time` Timer 指标 | `SearchMetricsTest`<br>`SampleSearchIntegrationTest` |
| **配置元数据** | 强类型配置属性与元数据 | `AtengSearchProperties` | `ateng.boot.search.*` | 标配 `Duration` 超时与 `DataSize` 缓冲容量强类型规范，提供合理默认值与 IDE 智能补全 | `AtengSearchAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-search` 是面向现代企业级生产环境设计的高内聚、纯原生全文检索与流批分析 Starter：
- **纯原生依赖与底座优先**：依托 JDK 17 与 Spring Boot 3 官方依赖管理纳管的 `co.elastic.clients:elasticsearch-java 8.13.4`，零引入任何臃肿第三方工具包；
- **操作门面对称完备**：彻底终结官方客户端复杂的链式嵌套与 Lambda 逃逸地狱，提供高可用、对称完备的 `SearchTemplate` 读写门面与 `SearchIndexOperations` 结构运维门面；
- **数据面与检索算子完备**：提供零报文开销的 `existsById` 探测、无打分开销的 `count` 统计、消除 N+1 的 `getByIds` 批量拉取、内置租户隔离的 `deleteByQuery` 批量清理，以及 `matchPhrase`、`matchPhrasePrefix`、`fuzzy`、`nested`、`collapse`、`min`、`max` 与 `dateHistogram` 丰富算子；
- **生产安全刚性守卫**：内置 10,000 条浅分页刚性熔断机制（`max-result-window`），支持 `Search-After` 深度游标分页，从根本上防御深翻查询打垮 ES 集群并引发 JVM OOM；
- **零停机迁移与时序运维**：支持全量与增量 `reindex` 数据复制、显式物理段 `refresh`、索引模板管理以及单次底层请求原子 `swapAlias` 别名热切，支撑无缝版本升级；
- **双模写入与流式处理**：严格快速失败阻断脏数据扩散，宽容模式提供失败快照以供重试与死信隔离；内置高吞吐非阻塞 `SearchBulkProcessor` 满足海量流水入库需求；
- **全链路追踪与可观测性**：全链路打通 MDC `traceId` 标头注入，内置 2 秒硬超时熔断的 Actuator 健康探针与 Micrometer 性能度量监控。

### Maven 坐标引入

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-search</artifactId>
</dependency>
```

---

## 2. 核心架构与起步体验 (Happy Path & Entity)

### 2.1 基础配置范式 (`application.yml`)

```yaml
ateng:
  boot:
    search:
      enabled: true
      engine: elasticsearch
      uris:
        - http://127.0.0.1:9200
      connect-timeout: 5s                 # 连接建立超时（强类型 Duration）
      socket-timeout: 30s                 # 网络套接字读取超时
      bulk:
        enabled: true
        batch-size: 50                    # 批量操作条数阈值
        flush-interval: 500ms             # 批量操作最大等待间隔
        buffer-size: 1MB                  # 内存缓冲容量阈值（强类型 DataSize）
      multi-tenancy:
        enabled: true                     # 开启多租户行级隐式隔离
        column: tenantId                  # 租户字段列名
      pagination:
        max-result-window: 10000          # 浅分页刚性熔断阈值
```

### 2.2 实体模型声明 (Entity)

依托声明式注解轻松绑定文档主键、物理索引、分片路由键、多规格 SKU 嵌套结构与高亮回填字段：

```java
package io.github.atengk.boot.sample.search.model;

import io.github.atengk.boot.search.annotation.HighlightField;
import io.github.atengk.boot.search.annotation.SearchId;
import io.github.atengk.boot.search.annotation.SearchIndex;
import io.github.atengk.boot.search.annotation.SearchRouting;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SearchIndex(name = "product_index", shards = 3, replicas = 1)
public class ProductDoc {

    @SearchId
    private String id;

    @HighlightField
    private String title;

    @HighlightField
    private String description;

    private String category;

    @SearchRouting
    private String brand;

    private List<String> tags;

    private BigDecimal price;

    private Integer stock;

    private String status;

    private GeoPoint location;

    private List<Float> embedding;

    private List<SkuItem> skus;

    private String tenantId;

    private LocalDateTime createTime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkuItem {
        private String skuId;
        private String color;
        private BigDecimal price;
        private Integer stock;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoPoint {
        private Double lat;
        private Double lon;
    }
}
```

---

## 3. 电商商品多维检索与高阶搜索套件 (Product Catalog & Advanced Search)

本套件聚焦电商、内容资讯与本地生活等典型场景，提供从高频 CRUD、存在性探测、MGet 批量拉取到高阶高亮、前缀补全、Nested SKU 精准匹配、Collapse 结果折叠去重与深浅分页的全套能力。

### 3.1 单文档 CRUD、存在性探测与 MGet 批量拉取

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      connect-timeout: 5s
      socket-timeout: 30s
```

#### 实战业务 Java 代码
```java
// 1. 保存/覆盖商品文档
public void saveProduct(ProductDoc product) {
    searchTemplate.save(product);
}

// 2. HTTP HEAD 轻量探测文档是否存在（0 响应体开销）
public boolean existsProduct(String id, String routing) {
    return searchTemplate.existsById(ProductDoc.class, id, routing);
}

// 3. 主键精准获取单条商品详情
public ProductDoc getProduct(String id, String routing) {
    return searchTemplate.getById(ProductDoc.class, id, routing);
}

// 4. 批量主键获取商品列表（_mget 端点，消除 N+1 网络往返）
public List<ProductDoc> getProductsBatch(Collection<String> ids, String routing) {
    return searchTemplate.getByIds(ProductDoc.class, ids, routing);
}
```

#### 核心机制与边界防御
- `existsById` 映射底层 HTTP HEAD 请求，仅返回 HTTP 状态码（200 / 404），免除传输与反序列化整个文档载荷，极大减轻网络带宽消耗；
- `getByIds` 直达 ES 原生 `_mget` 批量端点，单次网络往返即可并发检索多个分片，严格过滤 null 元素，保底返回非空列表。

---

### 3.2 局部增量更新与 OCC 乐观并发控制价格更新

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      socket-timeout: 10s
```

#### 实战业务 Java 代码
```java
// 1. 局部增量更新商品库存
public boolean updateProductStock(String id, String routing, int newStock) {
    Map<String, Object> fields = Map.of("stock", newStock);
    return searchTemplate.update(ProductDoc.class, id, routing, fields);
}

// 2. 基于 OCC (seq_no + primary_term) 乐观并发控制安全调价
public boolean updateProductPriceOcc(String id, String routing, BigDecimal newPrice, long seqNo, long primaryTerm) {
    Map<String, Object> fields = Map.of("price", newPrice);
    OccVersion occVersion = OccVersion.of(seqNo, primaryTerm);
    return searchTemplate.update(ProductDoc.class, id, routing, fields, occVersion);
}
```

#### 核心机制与边界防御
- 针对并发写覆盖（Lost Updates）风险，框架支持基于 Lucene 序列号 `seq_no` 与主分片周期 `primary_term` 的 OCC 乐观锁校验；
- 若并发更新时版本不匹配，底层直接捕获 409 Conflict 并转译抛出语义清晰的 `SearchVersionConflictException`，调用方可选择直接提示用户或自动重试。

---

### 3.3 多字段高亮全文检索与搜索框前缀联想补全

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      pagination:
        max-result-window: 10000
```

#### 实战业务 Java 代码
```java
// 1. 关键字全文多字段高亮检索（命中切片自动回填至标记 @HighlightField 的字段）
public List<SearchHit<ProductDoc>> searchMultiMatchWithHighlight(String keyword) {
    SearchQuery query = SearchQueryBuilder.create()
            .multiMatch(keyword, "title", "description")
            .highlight("title", "description")
            .build();
    SearchPage<ProductDoc> page = searchTemplate.search(query, ProductDoc.class);
    return page.getHits();
}

// 2. 搜索框前缀即时联想补全 (Match Phrase Prefix)
public List<SearchHit<ProductDoc>> searchPrefixSuggestion(String prefix) {
    SearchQuery query = SearchQueryBuilder.create()
            .matchPhrasePrefix("title", prefix)
            .build();
    return searchTemplate.search(query, ProductDoc.class).getHits();
}
```

#### 核心机制与边界防御
- **内存真明文原则**：高亮 HTML 标签（如 `<em>...</em>`）仅安全回填至返回给前端视图层的命中模型字段中，持久化存储与实体内存中始终保持 100% 原始真实明文；
- `matchPhrasePrefix` 限制前缀展开的最大扩展词数量，防止极短前缀引发前缀倒排树全展开导致的 CPU 假死。

---

### 3.4 Nested SKU 精准嵌套作用域检索

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      enabled: true
```

#### 实战业务 Java 代码
```java
// Nested 嵌套 SKU 规格独立匹配（杜绝跨 SKU 颜色与价格交叉误判）
public List<SearchHit<ProductDoc>> searchNestedSkus(String color, BigDecimal minPrice) {
    SearchQuery query = SearchQueryBuilder.create()
            .nested("skus", ChildScoreMode.Avg, sub -> sub
                    .term("skus.color", color)
                    .gte("skus.price", minPrice))
            .build();
    return searchTemplate.search(query, ProductDoc.class).getHits();
}
```

#### 核心机制与边界防御
- ES 默认将对象数组打平为独立标量列，会导致“红色 + 5000元”错误命中“包含红色SKU但售价8000元，以及白色SKU售价5000元”的混合商品；
- `nested` 算子指示底层将每个 SKU 建立为独立隐藏子文档，并在指定的子作用域闭包内计算匹配，确保同一子对象内部各项属性联合满足方可命中。

---

### 3.5 结果折叠去重 (Collapse) 与首屏霸屏防御

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      pagination:
        max-result-window: 10000
```

#### 实战业务 Java 代码
```java
// 检索结果字段折叠去重 (Field Collapsing，防止单一热门品牌垄断首屏)
public SearchPage<ProductDoc> searchWithCollapse(String brand, String collapseField) {
    SearchQueryBuilder builder = SearchQueryBuilder.create();
    if (brand != null && !brand.isBlank()) {
        builder.term("brand", brand);
    }
    SearchQuery query = builder
            .collapse(collapseField)
            .orderByDesc("price")
            .build();
    return searchTemplate.search(query, ProductDoc.class);
}
```

#### 核心机制与边界防御
- 在热门类目下，头部商户或单一品牌可能占据前几页全部搜索位置。通过 `collapse("brand.keyword")`，ES 服务端在分片聚合时即对每个去重键仅保留 Top 1 最优文档，极大提升搜索生态多样性。

---

### 3.6 地理空间周边搜索与 kNN 向量近邻召回

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      socket-timeout: 10s
```

#### 实战业务 Java 代码
```java
// 1. 地理空间周边距离筛选与就近排序
public SearchPage<ProductDoc> searchNearbyGeo(double lat, double lon, String distance) {
    SearchQuery query = SearchQueryBuilder.create()
            .geoDistance("location", lat, lon, distance)
            .orderByGeoDistance("location", lat, lon)
            .build();
    return searchTemplate.search(query, ProductDoc.class);
}

// 2. kNN 密集向量近似度近邻检索 (赋能大模型 RAG 知识检索)
public List<SearchHit<ProductDoc>> searchKnnVector(List<Float> vector, int k) {
    SearchQuery query = SearchQueryBuilder.create()
            .knn("embedding", vector, k, 100)
            .build();
    return searchTemplate.search(query, ProductDoc.class).getHits();
}
```

#### 核心机制与边界防御
- `geoDistance` 采用高精度的双精度经纬度计算模型，配合 `orderByGeoDistance` 实现由近及远自然排序；
- `knn` 算子声明式构建 HNSW 稠密向量检索，原生打通文本混合检索与向量检索双轨协同。

---

### 3.7 浅分页熔断与 Search-After 深度游标滚动分页

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      pagination:
        max-result-window: 10000          # 浅分页硬熔断阈值
```

#### 实战业务 Java 代码
```java
// 1. 标准浅分页（支持 .toPageResult() 转换为 Core 标准契约）
public PageResult<ProductDoc> searchShallowPaging(int page, int size) {
    SearchQuery query = SearchQueryBuilder.create()
            .matchAll()
            .page(page, size)
            .orderByDesc("createTime")
            .build();
    return searchTemplate.search(query, ProductDoc.class).toPageResult();
}

// 2. Search-After 深度游标滚动分页（恒定内存开销跨越万级熔断门槛）
public SearchScrollResult<ProductDoc> searchCursorPaging(Object[] sortValues, int size) {
    SearchQuery query = SearchQueryBuilder.create()
            .matchAll()
            .orderByAsc("id")
            .build();
    return searchTemplate.searchAfter(query, sortValues, size, ProductDoc.class);
}
```

#### 核心机制与边界防御
- **浅分页刚性熔断**：当 `(page - 1) * size + size > maxResultWindow` 时，框架前置抛出 `SearchPaginationExceededException` 快速失败，彻底消除深翻引发的集群数据节点内存打爆与 OOM 风险；
- **深度滚动分页**：当需要全量导出或深度向下浏览时，透传末尾排序凭据 `nextSortValues`，调用 `searchAfter` 实现轻量无状态游标滚动。

---

## 4. 日志审计与时序分析大盘套件 (Log Analytics & Time-Series Pipeline)

本套件针对海量时序日志、审计流水与监控事件，提供 SpEL 动态分表写入、`_count` 轻量统计、DateHistogram 时间分桶直方图走势聚合以及多租户行级安全清理。

### 4.1 SpEL 动态分表时序写入

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      enabled: true
```

#### 实战业务 Java 代码
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SearchIndex(name = "access_log_#{T(java.time.LocalDate).now().format('yyyyMMdd')}", shards = 1, replicas = 0)
public class AccessLogDoc {

    @SearchId
    private String id;
    private String path;
    private String clientIp;
    private String tenantId;
    private LocalDateTime timestamp;
}

// 写入时序审计日志（SpEL 动态路由至当天物理索引 access_log_YYYYMMDD）
public void recordAccessLog(AccessLogDoc logDoc) {
    searchTemplate.save(logDoc);
}
```

#### 核心机制与边界防御
- 框架内置 Spring SpEL 表达式引擎，在写入时动态计算目标物理索引名称，实现无需外部调度器干预的原生按日/按月自动分表分片治理。

---

### 4.2 访问量轻量精确统计 (_count)

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      socket-timeout: 10s
```

#### 实战业务 Java 代码
```java
// 精确统计指定路径访问总量 (_count 专用端点)
public long countLogsByPath(String path) {
    SearchQuery query = SearchQueryBuilder.create()
            .term("path", path)
            .build();
    return searchTemplate.count(query, AccessLogDoc.class);
}
```

#### 核心机制与边界防御
- `count` 方法直达 Elasticsearch 原生 `_count` 专用协议端点，跳过评分（Scoring）、文档抓取（Fetch）与高亮回填阶段，网络传输载荷与耗时仅为常规检索的数十分之一。

---

### 4.3 DateHistogram 日历时间分桶直方图走势聚合

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      socket-timeout: 30s
```

#### 实战业务 Java 代码
```java
// 按日历间隔（如 "1d", "1h"）分桶统计流量走势 (DateHistogram)
public SearchPage<AccessLogDoc> aggregateTrafficTrend(String index, String calendarInterval) {
    SearchQuery query = SearchQueryBuilder.create()
            .dateHistogramAggregation("daily_traffic", "timestamp", calendarInterval)
            .size(0)
            .build();
    return searchTemplate.search(index, query, AccessLogDoc.class);
}
```

#### 核心机制与边界防御
- 设置 `.size(0)` 仅返回聚合统计结果，不抓取底层原始明细文档；
- 支持日历自然周期（年、月、周、日、时），底层自动处理跨月天数差异与时区转换。

---

### 4.4 DeleteByQuery 条件历史清理与多租户行级安全网

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      multi-tenancy:
        enabled: true                     # 开启多租户行级隐式安全隔离
        column: tenantId                  # 租户列名
```

#### 实战业务 Java 代码
```java
// 条件驱动批量物理清理指定截止时间之前的过期历史日志 (deleteByQuery)
public long purgeHistoricalLogsBefore(LocalDateTime cutoffTime) {
    SearchQuery query = SearchQueryBuilder.create()
            .lt("timestamp", cutoffTime)
            .build();
    return searchTemplate.deleteByQuery(query, AccessLogDoc.class);
}
```

#### 核心机制与边界防御
- **多租户安全网**：当开启多租户隔离时，框架在向 ES 发送 `_delete_by_query` 请求前，强制从 `TenantContextHolder` 提取租户 ID 并注入 Term 过滤条件，严禁跨租户误删；
- **并发版本容错**：底层默认配置 `conflicts = "proceed"`，若在清理过程中遇到并发写入导致的版本冲突，批处理不会整体熔断崩溃，而是继续执行并返回真实删除的文档总数。

---

## 5. 海量流水批量摄取与流式缓冲套件 (High-Throughput Bulk Ingestion)

本套件专为高吞吐数据导入、CDC 数据同步（Canal / Flink）与异步日志流水设计，提供严格快速失败、宽容死信隔离以及受管异步非阻塞 `SearchBulkProcessor`。

### 5.1 严格模式批量写入 (Fail-Fast)

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      socket-timeout: 30s
```

#### 实战业务 Java 代码
```java
// 严格批量写入（遇任何单条失败立即抛出强类型 SearchBulkException 快速失败）
public SearchBulkResult ingestBatchStrict(List<ProductDoc> products) {
    return searchTemplate.indexBatch(products);
}
```

#### 核心机制与边界防御
- 任一文档发生字段映射错误、类型不匹配或版本冲突时，立即阻断后续执行并抛出 `SearchBulkException`，附带全部失败项详细原因，杜绝上层业务静默丢失数据。

---

### 5.2 宽容模式批量写入与死信隔离 (Lenient)

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      socket-timeout: 30s
```

#### 实战业务 Java 代码
```java
// 宽容批量写入（自动容错并返回包含成功总数与失败项快照的 SearchBulkResult）
public SearchBulkResult ingestBatchLenient(List<ProductDoc> products) {
    return searchTemplate.indexBatchLenient(products);
}
```

#### 核心机制与边界防御
- 无论底层出现多少条文档写入失败，批处理均不会抛出异常中断执行；
- 返回值完整记录成功计数（`successfulCount`）与失败项明细快照（包含错误码、错误原因与原始 ID），供调用方投递至 MQ 死信队列实施局部按项重试。

---

### 5.3 基于 SearchBulkProcessor 的异步流式缓冲投递

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      bulk:
        enabled: true                     # 启用受管 BulkProcessor Bean
        batch-size: 1000                  # 批量提交条数阈值
        flush-interval: 1s                # 定时强制刷盘时间间隔（强类型 Duration）
        buffer-size: 5MB                  # 缓冲估算字节容量阈值（强类型 DataSize）
        concurrent-requests: 1            # 最大并发执行的 Flush 请求数
        max-queue-capacity: 10000         # 缓冲队列最大文档积压容量
```

#### 实战业务 Java 代码
```java
@Autowired
private SearchBulkProcessor searchBulkProcessor;

// 异步流式批量缓冲写入（单条非阻塞微秒级投递）
public void streamIngest(ProductDoc product) {
    searchBulkProcessor.add(product);
}
```

#### 核心机制与边界防御
- **三重自适应触发器**：文档积压达到 `batch-size`、时间达到 `flush-interval` 或内存估算达到 `buffer-size` 时，自动触发异步刷盘；
- **微退避背压防爆**：当待刷队列超过 `max-queue-capacity` 时，生产者线程触发毫秒级微退避等待，彻底防止内存无限膨胀导致 JVM OOM。

---

### 5.4 显式排空与 Spring 生命周期优雅停机 (Graceful Shutdown)

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      bulk:
        await-close-timeout: 10s          # 优雅停机排空最大等待超时
```

#### 实战业务 Java 代码
```java
// 显式手动排空当前流式批量缓冲区
public void flushStream() {
    searchBulkProcessor.flush();
}
```

#### 核心机制与边界防御
- `SearchBulkProcessor` 深度集成 Spring `SmartLifecycle` 规范。在应用关闭或接收到 JVM 停机信号（`SIGTERM`）时，自动触发优雅排空（Drain），并在配置的 `awaitCloseTimeout` 期限内等待在途网络请求投递完成，确保缓冲池内文档零丢失。

---

## 6. 索引生命周期运维与平滑热迁移套件 (Index Lifecycle & Migration)

本套件收敛在专职运维门面 `SearchIndexOperations`（通过 `searchTemplate.indices()` 获取），提供结构创建、模板生命周期、服务端重索引、物理段刷新与原子别名热切。

### 6.1 索引结构 DDL 创建与映射定义

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      enabled: true
```

#### 实战业务 Java 代码
```java
// 1. 根据索引名称与 Mapping JSON 声明创建物理索引
public boolean createIndex(String indexName, String mappingJson) {
    return searchTemplate.indices().create(indexName, mappingJson);
}

// 2. 检查索引是否存在
public boolean existsIndex(String indexName) {
    return searchTemplate.indices().exists(indexName);
}

// 3. 物理删除指定索引
public boolean deleteIndex(String indexName) {
    return searchTemplate.indices().delete(indexName);
}
```

#### 核心机制与边界防御
- 提供基于索引名称或文档实体 Class 对象的对称 DDL 操作，前置校验索引名称合法性，杜绝无效网络往返。

---

### 6.2 时序索引模板动态管理

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      enabled: true
```

#### 实战业务 Java 代码
```java
// 1. 创建或应用时序索引模板 (Put Index Template)
public boolean applyIndexTemplate(String templateName, String templateJson) {
    return searchTemplate.indices().putIndexTemplate(templateName, templateJson);
}

// 2. 物理删除指定索引模板 (Delete Index Template)
public boolean deleteIndexTemplate(String templateName) {
    return searchTemplate.indices().deleteIndexTemplate(templateName);
}
```

#### 核心机制与边界防御
- 索引模板支持通配符模式匹配（如 `access_log_*`）。新索引生成时自动继承模板中的 Settings、Mappings 与 Aliases 配置，免去手动重复配置映射的运维成本。

---

### 6.3 服务端数据重索引 (Reindex)

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      socket-timeout: 60s
```

#### 实战业务 Java 代码
```java
// 1. 服务端全量平滑重索引 (Reindex All)
public boolean reindexAll(String sourceIndex, String targetIndex) {
    return searchTemplate.indices().reindex(sourceIndex, targetIndex);
}

// 2. 服务端带条件过滤增量重索引 (Reindex with filter)
public boolean reindexIncremental(String sourceIndex, String targetIndex, LocalDateTime minTime) {
    SearchQuery filterQuery = SearchQueryBuilder.create()
            .gte("createTime", minTime)
            .build();
    return searchTemplate.indices().reindex(sourceIndex, targetIndex, filterQuery);
}
```

#### 核心机制与边界防御
- 重索引完全在 Elasticsearch 集群服务端内部节点之间高速同步复制，不经由应用客户端内存中转，杜绝客户端发生网络拥堵与 OOM。

---

### 6.4 Lucene 段显式物理刷新 (Refresh)

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      enabled: true
```

#### 实战业务 Java 代码
```java
// 显式刷新索引（使物理段写入即时对搜索可见）
public boolean refreshIndex(String indexName) {
    return searchTemplate.indices().refresh(indexName);
}
```

#### 核心机制与边界防御
- Elasticsearch 默认具有 1 秒近实时（NRT）延迟。在数据刚写入且后续步骤需要立即检索时（如单测或严格业务流水确认），显式调用 `refresh` 可获得“写后即读”确定性保证。

---

### 6.5 零停机原子别名热切换 (Swap Alias)

#### 专属 YAML 配置
```yaml
ateng:
  boot:
    search:
      enabled: true
```

#### 实战业务 Java 代码
```java
// 生产原子别名热切换 (单次底层请求原子执行 remove + add，零查询空窗期)
public boolean switchTrafficAlias(String aliasName, String oldIndex, String newIndex) {
    return searchTemplate.indices().swapAlias(aliasName, oldIndex, newIndex);
}
```

#### 核心机制与边界防御
- 蓝绿切换时，若先调用 `remove` 再调用 `add`，中间会出现微秒级“别名不存在”的空窗期，导致线上查询报错；
- `swapAlias` 在单次底层 HTTP 报文中原子组合 `remove` 与 `add` 动作，确保查询请求无缝从旧索引平滑倒流至新索引。

---

## 7. 生产扩展接管与核心非功能边界 (Customization & Production Boundary)

### 7.1 流式批处理 SPI 扩展监听 (`BulkListener`)

当默认的日志打印无法满足生产指标上报、Canal 同步审计或特定业务监控诉求时，开发者可通过向 Spring 容器注册自定义 `BulkListener` 实现全生命周期接管：

```java
@Component
public class CustomSearchBulkAuditListener implements BulkListener {

    private static final Logger log = LoggerFactory.getLogger(CustomSearchBulkAuditListener.class);

    @Override
    public void beforeBulk(long executionId, int numberOfActions) {
        log.info("[Bulk-Audit] 即将提交批次 executionId={}, 操作文档数={}", executionId, numberOfActions);
    }

    @Override
    public void afterBulk(long executionId, int numberOfActions, SearchBulkResult result) {
        log.info("[Bulk-Audit] 批次提交完成 executionId={}, 成功={}, 失败={}",
                executionId, result.getSuccessfulCount(), result.getFailedCount());
    }

    @Override
    public void afterBulkError(long executionId, int numberOfActions, Throwable failure) {
        log.error("[Bulk-Audit] 批次执行系统异常 executionId={}, failure={}", executionId, failure.getMessage(), failure);
    }
}
```

---

### 7.2 官方原生客户端逃生舱 (`SearchCallback`)

当遇到极其前沿、小众或极度复杂的 Elasticsearch 原生特性时，开发者可直接使用 `searchTemplate.execute(...)` 逃生舱，无损直达原生官方 `ElasticsearchClient`：

```java
public Long getExactDocCountNative(String indexName) {
    return searchTemplate.execute(client -> {
        return client.count(c -> c.index(indexName)).count();
    });
}
```

---

### 7.3 行级多租户隐式隔离与上下文绕过机制

- **隐式织入原理**：当配置 `ateng.boot.search.multi-tenancy.enabled=true` 时，框架在构造检索与清理请求时，自动提取 `TenantContextHolder.getTenantId()`，通过前置过滤器（Filter）强行织入 `term("tenantId", tenantId)`；
- **实体级跳过**：在实体类上标注 `@SearchIgnoreTenant`，该实体对应的所有检索操作将自动豁免租户过滤；
- **上下文编程式跳过**：
  ```java
  TenantContextHolder.runWithIgnoreTenant(() -> {
      // 在此闭包内执行的搜索将全量检索所有租户数据（常用于超管大盘）
      return searchTemplate.search(query, ProductDoc.class);
  });
  ```

---

### 7.4 核心非功能边界与防故障机制

1. **浅分页刚性熔断机制**：`max-result-window` 生产默认 10,000，超出前置快速失败抛出 `SearchPaginationExceededException`，从源头杜绝 JVM OOM；
2. **Actuator 探针 2 秒硬超时自愈**：健康探针受独立线程池与 2 秒硬超时保护，当 ES 假死或状态为 RED 时平滑标记为 DOWN，联动 K8s 流量隔离；
3. **Micrometer 黄金度量体系**：默认输出 `ateng.search.query.time` 与 `ateng.search.bulk.time`，直通 Prometheus / Grafana 大盘；
4. **虚拟线程安全**：底层关键临界区采用 `ReentrantLock` 替代 `synchronized`，杜绝 JDK 21+ 虚拟线程 Carrier Pinning。

---

## 8. 完整 YAML 配置属性清单 (Configuration Properties Reference)

| 配置属性键名 (`ateng.boot.search.*`) | 类型 | 默认值 | 约束与说明 |
| :--- | :--- | :--- | :--- |
| `enabled` | `boolean` | `true` | 是否启用搜索基础设施 Starter |
| `engine` | `SearchEngineType` | `ELASTICSEARCH` | 底层搜索引擎类型（可选 `ELASTICSEARCH` / `OPENSEARCH`） |
| `uris` | `List<String>` | `["http://localhost:9200"]` | 搜索引擎服务节点集群地址列表 |
| `username` | `String` | - | 认证用户名（可选） |
| `password` | `String` | - | 认证密码（可选） |
| `api-key` | `String` | - | 云原生 API Key 凭据（若配置优先于用户名密码） |
| `path-prefix` | `String` | - | 反向代理网关子路径前缀（如 `/es-cluster`） |
| `connect-timeout` | `Duration` | `5s` | 网络连接建立超时时间（强类型 `Duration`） |
| `socket-timeout` | `Duration` | `30s` | 网络套接字读取超时时间（强类型 `Duration`） |
| `max-connections` | `int` | `100` | 连接池最大并发连接数 |
| `max-connections-per-route` | `int` | `50` | 连接池单节点路由最大并发连接数 |
| `keep-alive` | `Duration` | `30s` | 连接保活时长（强类型 `Duration`） |
| `ssl.enabled` | `boolean` | `false` | 是否启用 SSL/TLS 通信加密 |
| `ssl.trust-store-path` | `String` | - | TrustStore 证书文件路径 |
| `ssl.trust-store-password` | `String` | - | TrustStore 证书密码 |
| `ssl.insecure-skip-verify` | `boolean` | `false` | 本地开发沙箱是否跳过自签名证书合法性校验 |
| `multi-tenancy.enabled` | `boolean` | `true` | 是否开启多租户自动隐式过滤 |
| `multi-tenancy.column` | `String` | `tenant_id` | 多租户字段名 |
| `pagination.max-result-window` | `int` | `10000` | 浅分页最大结果窗口阈值，超过将刚性熔断 |
| `bulk.enabled` | `boolean` | `true` | 是否启用受管 BulkProcessor Bean |
| `bulk.batch-size` | `int` | `1000` | 批量提交条数阈值 |
| `bulk.flush-interval` | `Duration` | `1s` | 定时强制刷盘时间间隔（强类型 `Duration`） |
| `bulk.buffer-size` | `DataSize` | `5MB` | 缓冲估算字节容量阈值（强类型 `DataSize`） |
| `bulk.concurrent-requests` | `int` | `1` | 最大并发执行的 Flush 请求数（>0 异步并发，0 同步阻塞） |
| `bulk.max-queue-capacity` | `int` | `10000` | 缓冲队列最大文档积压容量，超出触发微退避背压 |
| `bulk.await-close-timeout` | `Duration` | `10s` | 优雅停机排空最大等待超时时间（强类型 `Duration`） |

---

## 9. 样例工程与自动化回归自验 (Sample Sandbox)

模块严格遵循 1:1 沙箱映射，提供独立场景验证工程：
- **工程路径**：[`../ateng-boot-samples/ateng-boot-sample-search`](../ateng-boot-samples/ateng-boot-sample-search)
- **多 Controller Web 体验**：
  1. `ProductSearchController`：商品多维检索、HEAD 探测、MGet、局部更新、OCC价格更新、高亮、Nested SKU、Collapse折叠、Geo周边、KNN向量、游标分页；
  2. `LogAnalyticsController`：时序分表写入、Count 统计、DateHistogram 趋势聚合、DeleteByQuery 租户隔离清理；
  3. `BulkIngestionController`：严格批量写入、宽容死信隔离、流式非阻塞缓冲写入与手动排空；
  4. `IndexLifecycleController`：索引结构 DDL 创建、时序模板生命周期、服务端重索引、物理段刷新与别名原子热切换。

### 运行全真自洽集成测试

该工程基于纯内存 `MockWebServer` 搭建，**100% 离线自洽，无需启动物理 Elasticsearch 集群或 Docker 容器**：

```bash
mvn test -pl ateng-boot-search,ateng-boot-samples/ateng-boot-sample-search -am -T 1C -nsu -B
```
