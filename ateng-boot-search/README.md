# ateng-boot-search 基础设施 Starter

> 工业级双引擎全文检索与流批分析基础设施 Starter，基于 Elasticsearch 8.x 官方原生 Java 客户端深度封装，提供直觉强类型操作门面与双轨分页模型，内置乐观并发控制 (OCC)、双模批量写入治理、流式非阻塞 BulkProcessor、声明式高亮富文本自动回填、多租户行级隐式隔离、条件驱动批量清理、短语与前缀联想、Nested SKU 精准嵌套作用域、Collapse 字段折叠去重、DateHistogram 时序直方图分析、服务端 Reindex 平滑数据迁移、Lucene 段物理刷新、原子别名热切换、云原生 Actuator 2 秒超时熔断健康探针与 Micrometer 黄金度量指标体系。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.search.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **文档新增/替换** | 单文档索引保存 | `SearchTemplate` | `ateng.boot.search.enabled` | `index(T doc)` / `index(String, T)` / `save(T doc)` / `save(String, T)`，自动解析 `@SearchIndex` 索引名与 `@SearchId` 文档 ID，自动提取路由键 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **条件更新/OCC** | 乐观并发控制条件更新 | `SearchTemplate`<br>`OccVersion` | - | `index(T, OccVersion)` / `update(Class<?>, String, String, Map<String, Object>, OccVersion)`，基于 `seq_no` 与 `primary_term` 防写覆盖，版本冲突抛出 `SearchVersionConflictException` | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **轻量存在探测** | 主键存在性探测 (HEAD) | `SearchTemplate` | - | `existsById(Class<?>, String)` / `existsById(Class<?>, String, String)` / `existsById(String, String, String)`，映射 HTTP HEAD 协议端点，0 报文体开销快速判定文档存在性 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **精确计数统计** | 条件文档计数 (Count) | `SearchTemplate` | - | `count(SearchQuery, Class<?>)` / `count(String, SearchQuery)`，直达 ES `_count` 专用端点，免除全文打分与文档正文载荷加载 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **精准主键检索** | 主键实体查询 | `SearchTemplate` | - | `getById(Class<T>, String)` / `getById(Class<T>, String, String)` / `getById(String, Class<T>, String, String)`，根据文档 ID 与分片路由键精准定位文档实体，不存在返回 `null`；路由防漏 Fail-Fast 拦截 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **完整命中载荷** | 元数据命中文档模型 | `SearchTemplate`<br>`SearchHit<T>` | - | `getHitById(Class<T>, String)` / `getHitById(Class<T>, String, String)` / `getHitById(String, Class<T>, String, String)`，返回包含打分、版本元数据、高亮与排序凭据的 `SearchHit<T>` | `SearchTemplateMockServerTest` |
| **批量主键检索** | 批量实体拉取 (MGet) | `SearchTemplate` | - | `getByIds(Class<T>, Collection<String>)` / `getByIds(String, Class<T>, Collection<String>)`，直达 `_mget` 批量端点消除 N+1 网络往返，严格过滤 null 元素，保底返回非空列表 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **批量元数据检索** | 批量元数据拉取 (MGet Hits) | `SearchTemplate`<br>`SearchHit<T>` | - | `getHitsByIds(Class<T>, Collection<String>)` / `getHitsByIds(String, Class<T>, Collection<String>)`，单次网络往返批量拉取包含 `_version`、`_seq_no`、`_primary_term` 的完整元数据模型列表 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **局部增量更新** | 字段级 Partial Update | `SearchTemplate` | - | `update(Class<?>, String, Map<String, Object>)` / `update(Class<?>, String, String, Map<String, Object>)`，局部更新指定字段，支持 `docAsUpsert` | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **主键物理删除** | 单文档物理删除 | `SearchTemplate` | - | `deleteById(Class<?>, String)` / `deleteById(Class<?>, String, String)` / `deleteById(String, String, String)`，物理删除单条文档，路由防漏守卫 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **条件批量清理** | 条件物理清理 (Delete-By-Query) | `SearchTemplate` | - | `deleteByQuery(SearchQuery, Class<?>)` / `deleteByQuery(String, SearchQuery)`，直达 `_delete_by_query` 端点，强制隐式织入多租户过滤，默认配置 `conflicts = "proceed"` 并发容错，返回删除文档计数 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **严格批量写入** | 严格快速失败批量操作 | `SearchTemplate`<br>`SearchBulkResult` | - | `indexBatch(Collection<T>)` / `updateBatch(Collection<T>)` / `deleteBatch(Class<?>, Collection<String>)`，任一文档失败立即抛出强类型 `SearchBulkException` 阻断脏数据扩散 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **宽容批量写入** | 容错宽容批量操作 | `SearchTemplate`<br>`SearchBulkResult` | - | `indexBatchLenient(...)` / `updateBatchLenient(...)` / `deleteBatchLenient(...)`，返回包含成功总数与失败明细快照的 `SearchBulkResult`，专供死信隔离与补偿重试 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **流式缓冲写入** | 异步非阻塞批量处理器 | `SearchBulkProcessor` | `ateng.boot.search.bulk.enabled`<br>`ateng.boot.search.bulk.batch-size`<br>`ateng.boot.search.bulk.flush-interval`<br>`ateng.boot.search.bulk.buffer-size` | `add(T doc)` / `addAll(Collection<T> docs)` / `flush()` / `flushAsync()`，基于数量、时间间隔与内存容量三重阈值异步后台刷盘，停机安全排空 | `SearchBulkProcessorTest`<br>`SampleSearchIntegrationTest` |
| **标准浅分页** | 浅分页与刚性熔断 | `SearchTemplate`<br>`SearchPage<T>` | `ateng.boot.search.pagination.max-result-window` | `search(SearchQuery, Class<T>)` / `search(String, SearchQuery, Class<T>)`，返回标准分页载荷 `SearchPage<T>`（支持 `.toPageResult()` 转为 Core 标准模型），超阈值自动抛出 `SearchPaginationExceededException` | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **深度滚动分页** | Search-After 游标滚动 | `SearchTemplate`<br>`SearchScrollResult<T>` | - | `searchAfter(SearchQuery, Object[], int, Class<T>)` / `searchAfter(String, SearchQuery, Object[], int, Class<T>)`，无内存膨胀深翻页，透传末尾排序凭据 `nextSortValues` | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **短语精准匹配** | Match Phrase 短语检索 | `SearchQueryBuilder` | - | `matchPhrase(String field, Object query)`，严格保证词项顺序与间隔邻近度，杜绝分词零散命中 | `SearchTemplateMockServerTest` |
| **前缀即时补全** | Match Phrase Prefix 联想 | `SearchQueryBuilder` | - | `matchPhrasePrefix(String field, Object query)` / `matchPhrasePrefix(String, Object, int)`，构建搜索输入框打字即联想补全提示 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **拼写模糊容错** | Fuzzy 模糊检索 | `SearchQueryBuilder` | - | `fuzzy(String field, String value)` / `fuzzy(String, String, String)`，基于 Levenshtein 编辑距离进行拼写手误容错查询 | `SearchTemplateMockServerTest` |
| **嵌套规格匹配** | Nested 独立作用域检索 | `SearchQueryBuilder` | - | `nested(String path, Consumer<SearchQueryBuilder>)` / `nested(String, ChildScoreMode, Consumer<SearchQueryBuilder>)`，多 SKU 子对象独立作用域精准匹配，杜绝跨对象交叉命中 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **结果折叠去重** | Field Collapsing 字段折叠 | `SearchQueryBuilder` | - | `collapse(String field)`，原生结果折叠去重，防止热门品牌/分类垄断搜索首屏，每个分组展示 Top 1 高分文档 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **声明式高亮** | @HighlightField 自动回填 | `SearchTemplate`<br>`@HighlightField` | - | 检索时指定 `.highlight(...)`，自动将匹配命中切片富文本回填至实体对应字段中，视图层开箱即用 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **多租户隔离** | 行级多租户隐式过滤 | `SearchTemplate`<br>`@SearchIgnoreTenant` | `ateng.boot.search.multi-tenancy.enabled`<br>`ateng.boot.search.multi-tenancy.column` | 自动结合 `TenantContextHolder` 注入 Term 过滤；支持实体级别声明 `@SearchIgnoreTenant` 或上下文跳过 | `SearchTemplateMockServerTest` |
| **地理空间计算** | Geo 距离筛选与排序 | `SearchQueryBuilder` | - | `.geoDistance("location", lat, lon, "5km")`<br>`.orderByGeoDistance("location", lat, lon)`，构建地理范围过滤与距离排序 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **AI 向量检索** | KNN 密集向量近邻召回 | `SearchQueryBuilder` | - | `.knn("embedding", vector, k, numCandidates)`，声明式构建稠密向量检索，赋能大模型 RAG 知识检索 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **度量聚合算子** | 统计与极值度量 | `SearchQueryBuilder` | - | `.termsAggregation(...)` / `.sumAggregation(...)` / `.avgAggregation(...)` / `.minAggregation(...)` / `.maxAggregation(...)`，链式声明基础统计指标 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **时序直方图聚合** | DateHistogram 时间分桶 | `SearchQueryBuilder` | - | `.dateHistogramAggregation("daily_traffic", "timestamp", "1d")`，按日历时间间隔分桶统计流量走势，赋能时序审计大盘 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **复合布尔检索** | Fluent 风格查询构造器 | `SearchQueryBuilder` | - | `create()`、`.term()`、`.terms()`、`.match()`、`.multiMatch()`、`.range()`、`.gte()`、`.lte()`、`.matchAll()`，链式组装复杂 DSL | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **结构面运维** | 索引与映射结构管理 | `SearchIndexOperations` | - | `create(index)` / `create(clazz)` / `exists(index)` / `exists(clazz)` / `delete(index)` / `delete(clazz)` / `putMapping(...)`，专职 DDL 操作 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **索引模板管理** | 时序模板与组件生命周期 | `SearchIndexOperations` | - | `putIndexTemplate(templateName, indexPattern, mappings)` / `deleteIndexTemplate(templateName)`，索引模板对称建立与销毁 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **服务端重索引** | 平滑数据复制 (Reindex) | `SearchIndexOperations` | - | `reindex(sourceIndex, targetIndex)` / `reindex(sourceIndex, targetIndex, SearchQuery filterQuery)`，服务端全量或增量条件数据复制 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **物理段刷新** | Lucene Segment 刷新 (Refresh) | `SearchIndexOperations` | - | `refresh(String index)` / `refresh(Class<?> clazz)`，显式触发 Lucene 物理段合并与刷新，提供写后即读确定性保证 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **原子别名切换** | 生产原子别名热切换 | `SearchIndexOperations` | - | `swapAlias(alias, oldIndex, newIndex)`，单次底层请求原子执行 remove + add，实现零空窗切源与零停机索引重建 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **动态时序分表** | SpEL 表达式时序索引 | `@SearchIndex` | - | `@SearchIndex(name = "access_log_#{T(java.time.LocalDate).now().format('yyyyMMdd')}")`，写入时自动计算目标物理索引 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **流式批处理监听** | 异步刷盘扩展点 (SPI) | `BulkListener` | - | `beforeBulk(long, int)` / `afterBulk(long, int, ...)` / `afterBulkError(long, int, Throwable)`，流式批处理全生命周期监听扩展 | `SearchBulkProcessorTest` |
| **原生底层逃生舱** | 官方原生 Client 执行回调 | `SearchTemplate`<br>`SearchCallback<R>` | - | `searchTemplate.execute(client -> ...)`，遇到官方极限复杂特性时可无损直达原生 API，不留死角 | `SearchTemplateMockServerTest` |
| **Actuator 探针** | 云原生健康状态探测 | `SearchHealthIndicator` | `management.health.search.enabled` | 探查集群健康度（GREEN/YELLOW 返回 UP，RED 返回 DOWN），内置 2 秒硬超时熔断机制防御假死 | `SearchHealthIndicatorTest`<br>`SampleSearchIntegrationTest` |
| **黄金度量指标** | Micrometer 核心度量采集 | `SearchMetrics` | `management.metrics.enable.search` | 采集检索耗时 `ateng.search.query.time` 与批量写入耗时 `ateng.search.bulk.time` Timer 指标 | `SearchMetricsTest`<br>`SampleSearchIntegrationTest` |
| **配置元数据** | 强类型配置属性与元数据 | `AtengSearchProperties` | `ateng.boot.search.*` | 标配 `Duration` 超时与 `DataSize` 缓冲容量强类型规范，提供合理默认值与 IDE 智能补全 | `AtengSearchAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-search` 是面向现代企业级生产环境设计的高内聚、纯原生全文检索与流批分析 Starter：
- **纯原生依赖与底座优先**：基于 JDK 17 与 Spring Boot 3 官方依赖管理纳管的 `co.elastic.clients:elasticsearch-java 8.13.4`，零引入任何臃肿第三方工具包；
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

## 2. 生产基础配置与极简起步体验 (Happy Path)

### 2.1 基础配置范式 (`application.yml`)

```yaml
ateng:
  boot:
    search:
      enabled: true
      engine: elasticsearch
      uris:
        - http://127.0.0.1:9200
      connection-timeout: 2s              # 连接建立超时（强类型 Duration）
      socket-timeout: 5s                  # 网络传输读取超时
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

# 可选集成：Spring Boot Actuator 健康探针与 Micrometer 指标
management:
  health:
    search:
      enabled: true
  endpoint:
    health:
      show-details: always
  endpoints:
    web:
      exposure:
        include: "health,metrics,info"
  metrics:
    enable:
      search: true
```

### 2.2 实体模型声明 (Entity)

依托声明式注解轻松绑定文档主键、物理索引、路由键、多规格 SKU 嵌套结构与高亮回填字段：

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
        private String size;
        private BigDecimal price;
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

### 2.3 业务场景组件起步 (Scenario Component)

直接注入核心执行门面 `SearchTemplate` 开展日常检索业务（示例代码 1:1 逆向提取自 `ProductCatalogSearchScenario`）：

```java
package io.github.atengk.boot.sample.search.scenario;

import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.search.model.SearchHit;
import io.github.atengk.boot.search.model.SearchPage;
import io.github.atengk.boot.search.query.SearchQuery;
import io.github.atengk.boot.search.query.SearchQueryBuilder;
import io.github.atengk.boot.search.template.SearchTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductCatalogSearchScenario {

    private final SearchTemplate searchTemplate;

    // 1. 文档保存与全量覆盖
    public void saveProduct(ProductDoc product) {
        searchTemplate.save(product);
    }

    // 2. 轻量存在探测（HTTP HEAD，0 报文开销）
    public boolean existsProduct(String id, String routing) {
        return searchTemplate.existsById(ProductDoc.class, id, routing);
    }

    // 3. 批量主键查询（_mget 端点，消除 N+1 网络往返）
    public List<ProductDoc> getProductsBatch(Collection<String> ids) {
        return searchTemplate.getByIds(ProductDoc.class, ids);
    }

    // 4. 关键字全文检索与高亮切片自动回填
    public List<SearchHit<ProductDoc>> searchMultiMatchWithHighlight(String keyword) {
        SearchQuery query = SearchQueryBuilder.create()
                .multiMatch(keyword, "title", "description")
                .highlight("title", "description")
                .build();
        SearchPage<ProductDoc> page = searchTemplate.search(query, ProductDoc.class);
        return page.getHits();
    }
}
```

---

## 3. 生产扩展与四大业务场景接管 (Customization & Extensibility)

根据企业真实生产场景，`ateng-boot-sample-search` 遵循 **1:1 独立业务场景组件** 解耦设计，提供以下四大端到端自洽实测场景：

### 3.1 场景一：电商商品多维检索与高阶搜索 (`ProductCatalogSearchScenario`)

覆盖商品多维筛选、SKU 嵌套对象作用域独立匹配、输入框前缀联想、字段折叠去重以及深浅分页：

```java
// 1. 搜索框前缀即时联想补全 (Match Phrase Prefix)
public List<SearchHit<ProductDoc>> searchPrefixSuggestion(String prefix) {
    SearchQuery query = SearchQueryBuilder.create()
            .matchPhrasePrefix("title", prefix)
            .build();
    return searchTemplate.search(query, ProductDoc.class).getHits();
}

// 2. Nested 嵌套 SKU 规格独立匹配（杜绝跨 SKU 颜色与价格交叉误判）
public List<SearchHit<ProductDoc>> searchNestedSkus(String color, BigDecimal minPrice) {
    SearchQuery query = SearchQueryBuilder.create()
            .nested("skus", ChildScoreMode.Avg, sub -> sub
                    .term("skus.color", color)
                    .gte("skus.price", minPrice))
            .build();
    return searchTemplate.search(query, ProductDoc.class).getHits();
}

// 3. 检索结果字段折叠去重 (Field Collapsing，防单一热门品牌垄断首屏)
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

// 4. 传统浅分页契约转换
public PageResult<ProductDoc> searchShallowPaging(int page, int size) {
    SearchQuery query = SearchQueryBuilder.create()
            .matchAll()
            .page(page, size)
            .orderByDesc("createTime")
            .build();
    return searchTemplate.search(query, ProductDoc.class).toPageResult();
}
```

### 3.2 场景二：日志审计与时序分析大盘 (`LogAnalyticsAggScenario`)

覆盖 SpEL 动态分表写入、日历直方图时间分桶聚合、count 文档计数与 deleteByQuery 历史冷数据批量清理：

```java
// 1. 写入时序审计日志（SpEL 动态路由至当天物理索引 access_log_YYYYMMDD）
public void recordAccessLog(AccessLogDoc logDoc) {
    searchTemplate.save(logDoc);
}

// 2. 按日历间隔（如 "1d", "1h"）分桶统计流量走势 (DateHistogram)
public SearchPage<AccessLogDoc> aggregateTrafficTrend(String index, String calendarInterval) {
    SearchQuery query = SearchQueryBuilder.create()
            .dateHistogramAggregation("daily_traffic", "timestamp", calendarInterval)
            .size(0)
            .build();
    return searchTemplate.search(index, query, AccessLogDoc.class);
}

// 3. 精确统计指定路径访问总量 (_count 专用端点)
public long countLogsByPath(String path) {
    SearchQuery query = SearchQueryBuilder.create()
            .term("path", path)
            .build();
    return searchTemplate.count(query, AccessLogDoc.class);
}

// 4. 条件驱动批量物理清理指定截止时间之前的过期历史日志 (deleteByQuery)
public long purgeHistoricalLogsBefore(LocalDateTime cutoffTime) {
    SearchQuery query = SearchQueryBuilder.create()
            .lt("timestamp", cutoffTime)
            .build();
    return searchTemplate.deleteByQuery(query, AccessLogDoc.class);
}
```

### 3.3 场景三：生产零停机平滑热迁移与 DDL 生命周期 (`ZeroDowntimeReindexScenario`)

闭环覆盖模板管理、Mapping 定义、服务端数据全量/增量复制、物理段刷新与单次底层原子别名热切换：

```java
// 1. 服务端全量平滑重索引 (Reindex)
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

// 3. 显式刷新索引（使物理段写入即时对搜索可见）
public boolean refreshIndex(String indexName) {
    return searchTemplate.indices().refresh(indexName);
}

// 4. 生产原子别名热切换 (swapAlias，单次底层请求原子 remove + add，零查询空窗期)
public boolean switchTrafficAlias(String aliasName, String oldIndex, String newIndex) {
    return searchTemplate.indices().swapAlias(aliasName, oldIndex, newIndex);
}
```

### 3.4 场景四：海量流水数据批量写入与异步流式缓冲 (`BulkIngestionScenario`)

严格事务性批量写入、宽容局部失败隔离以及基于受管 `SearchBulkProcessor` 的高吞吐非阻塞缓冲入库：

```java
// 1. 严格批量写入（遇任何单条失败立即抛出异常快速失败）
public SearchBulkResult ingestBatchStrict(List<ProductDoc> products) {
    return searchTemplate.indexBatch(products);
}

// 2. 宽容批量写入（自动容错并返回失败项快照，专供重试与死信隔离）
public SearchBulkResult ingestBatchLenient(List<ProductDoc> products) {
    return searchTemplate.indexBatchLenient(products);
}

// 3. 异步流式批量缓冲写入（单条非阻塞投递）
public void streamIngest(ProductDoc product) {
    searchBulkProcessor.add(product);
}

// 4. 显式强制排空当前流式批量缓冲区
public void flushStream() {
    searchBulkProcessor.flush();
}
```

### 3.5 深度游标滚动分页 (Search-After) 与官方原生 Client 逃生舱 (execute)

```java
// 1. Search-After 深度滚动分页（跨越万级熔断门槛，内存恒定）
public SearchScrollResult<ProductDoc> searchCursorPaging(Object[] sortValues, int size) {
    SearchQuery query = SearchQueryBuilder.create()
            .matchAll()
            .orderByAsc("id")
            .build();
    return searchTemplate.searchAfter(query, sortValues, size, ProductDoc.class);
}

// 2. 官方原生 Client 逃生舱（直达原生 ElasticsearchClient）
public Long getExactDocCount(String indexName) {
    return searchTemplate.execute(client -> {
        return client.count(c -> c.index(indexName)).count();
    });
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

1. **浅分页刚性安全熔断 (`SearchPaginationExceededException`)**：
   当查询请求 `from + size` 超过 `max-result-window`（生产默认 10,000）时，框架在前置卫语句中立即触发快速失败，彻底消除深翻引发的集群数据节点内存打爆与 OOM 风险；
2. **条件批量清理的多租户行级安全网与并发冲突容错 (`deleteByQuery`)**：
   - **多租户安全网**：当开启 `ateng.boot.search.multi-tenancy.enabled=true` 时，框架在构造 ES 请求前自动解析当前上下文 `TenantContextHolder.getTenantId()`，强制向 Query 的 filter 过滤器织入租户隔离 Term 条件。若实体标注了 `@SearchIgnoreTenant` 或上下文处于系统管理员租户豁免态，则安全放行；
   - **并发版本容错**：默认配置 `conflicts = "proceed"`，若在清理过程中遇到并发写入导致的版本冲突，批处理不会整体熔断中止，而是继续执行并返回真实删除的文档总数；
3. **零停机蓝绿迁移与 Lucene Segment 刷新一致性 (`reindex` + `refresh` + `swapAlias`)**：
   - 索引重建时，首先在 ES 服务端通过 `reindex` 高性能复制数据；
   - 在别名切换前，调用 `refresh` 触发 Lucene 物理段刷盘，确保所有复制文档对外立即可见；
   - 最后通过 `swapAlias` 单次底层请求原子解除旧索引并挂载新索引，实现业务零停机、零查询空窗期与零数据丢失；
4. **嵌套对象 `nested` 与普通 `object` 数组的交叉命中陷阱与精准作用域隔离**：
   Elasticsearch 默认将对象数组打平存储为单值多属性数组，会导致“红色的 iPhone”与“512GB 的 iPhone”跨 SKU 错误命中同一商品。采用 `nested` 算子后，底层将每个 SKU 作为独立隐藏文档索引，并在指定的子作用域闭包内计算匹配，保证多属性在同一 SKU 上同时满足时才命中；
5. **结果折叠去重与首屏霸屏防御 (`collapse`)**：
   在热门分类搜索中，同一品牌或店铺可能占据前几页全部结果。通过 `collapse("brand.keyword")`，ES 在节点级别即对分组实施折叠，每组仅返回打分最高的一条文档，大幅提升搜索结果的多样性与用户体验；
6. **双模批量写入契约 (`Strict` vs `Lenient`) 与流式缓冲优雅停机排空 (`SearchBulkProcessor`)**：
   - **严格模式 (`indexBatch`)**：底层若存在任意一条文档写入失败，立即抛出 `SearchBulkException` 并附带失败明细，杜绝上层出现静默数据丢失；
   - **宽容模式 (`indexBatchLenient`)**：捕获全部错误项封装进 `SearchBulkResult`，供 MQ 消费端、Canal 异步同步管道投递至死信队列实施局部按项重试；
   - **优雅停机排空**：`SearchBulkProcessor` 实现 Spring `SmartLifecycle` 接口，在 JVM 接收停机信号时触发优雅排空（Drain），最长等待排空完成后方释放底层 HTTP 连接，保证内存缓冲不丢件；
7. **内存真明文与高亮富文本隔离不变量**：
   高亮标签仅回填在视图层模型或标记了 `@HighlightField` 的字段中；持久化存储与实体内存中始终保持 100% 原始真实明文，严禁 HTML 高亮标签回写至持久层；
8. **Actuator 探针 2 秒硬超时熔断与自愈机制**：
   探针独立运行于异步线程池并受 2 秒硬超时保护；当探测到集群状态为 `RED` 或连接假死时，探针自愈翻转为 `DOWN` 状态，触发云原生 K8s 流量隔离摘除。

---

## 5. 样例工程与实测验证 (Sample Sandbox)

模块提供了 1:1 独立业务场景沙箱工程：
- **工程路径**：`ateng-boot-samples/ateng-boot-sample-search`
- **场景架构**：彻底废除单体大类，解耦为 4 大业务场景组件：
  1. [`ProductCatalogSearchScenario.java`](../ateng-boot-samples/ateng-boot-sample-search/src/main/java/io/github/atengk/boot/sample/search/scenario/ProductCatalogSearchScenario.java)：商品多维检索、HEAD 探测、MGet 批量拉取、高亮回填、SKU Nested 查询、搜索联想补全、Collapse 字段折叠、Geo周边、KNN向量与深浅分页；
  2. [`LogAnalyticsAggScenario.java`](../ateng-boot-samples/ateng-boot-sample-search/src/main/java/io/github/atengk/boot/sample/search/scenario/LogAnalyticsAggScenario.java)：SpEL 动态时序按天分表、DateHistogram 直方图时间分桶走势统计、count 计数与 deleteByQuery 批量清理；
  3. [`ZeroDowntimeReindexScenario.java`](../ateng-boot-samples/ateng-boot-sample-search/src/main/java/io/github/atengk/boot/sample/search/scenario/ZeroDowntimeReindexScenario.java)：索引模板建立与销毁、Mapping 结构更新、服务端全量/增量 Reindex、物理段显式 Refresh 与 swapAlias 零空窗别名热切；
  4. [`BulkIngestionScenario.java`](../ateng-boot-samples/ateng-boot-sample-search/src/main/java/io/github/atengk/boot/sample/search/scenario/BulkIngestionScenario.java)：严格模式写入、宽容模式死信隔离、流式非阻塞缓冲批量写入与停机排空安全。

### 运行自洽集成测试

该工程基于纯内存 `MockWebServer` 搭建，**100% 离线自洽，无需启动真实 Elasticsearch 集群或 Docker 容器**：

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-search -am -T 1C -nsu -B
```

测试全景覆盖 16 项端到端业务测试用例并 100% 全绿准出。
