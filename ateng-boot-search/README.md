# ateng-boot-search 基础设施 Starter

> 工业级双引擎全文检索与流批分析基础设施 Starter，基于 Elasticsearch 8.x 官方原生 Java 客户端深度封装，提供直觉强类型操作门面与双轨分页模型，内置乐观并发控制 (OCC)、双模批量写入治理、流式非阻塞 BulkProcessor、声明式高亮富文本自动回填、多租户行级隐式隔离、Geo 空间与 AI 向量检索、原子别名热切换、云原生 Actuator 2 秒超时熔断健康探针与 Micrometer 黄金度量指标体系。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.search.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **文档新增/替换** | 单文档索引保存 | `SearchTemplate` | `ateng.boot.search.enabled` | `index(T doc)` / `save(T doc)`，自动解析 `@SearchIndex` 索引名与 `@SearchId` 文档 ID，自动提取路由键 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **条件更新/OCC** | 乐观并发控制条件更新 | `SearchTemplate`<br>`OccVersion` | - | `index(T, OccVersion)` / `update(Class<?>, String, String, Map<String, Object>, OccVersion)`，基于 `seq_no` 与 `primary_term` 防竞态，版本冲突抛出 `SearchVersionConflictException` | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **精准主键检索** | 主键文档查询 | `SearchTemplate` | - | `getById(Class<T>, String)` / `getById(Class<T>, String, String)`，根据文档 ID 与分片路由键精准定位文档实体，不存在返回 `null`；路由防漏 Fail-Fast 拦截 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **完整命中载荷** | 元数据命中文档模型 | `SearchTemplate`<br>`SearchHit<T>` | - | `getHitById(Class<T>, String)` / `getHitById(Class<T>, String, String)`，返回包含打分、版本元数据、高亮与排序凭据的 `SearchHit<T>` | `SearchTemplateMockServerTest` |
| **局部增量更新** | 字段级 Partial Update | `SearchTemplate` | - | `update(Class<?>, String, Map<String, Object>)` / `update(Class<?>, String, String, Map<String, Object>)`，局部更新指定字段，支持 `docAsUpsert` | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **主键物理删除** | 单文档物理删除 | `SearchTemplate` | - | `deleteById(Class<?>, String)` / `deleteById(Class<?>, String, String)`，物理删除单条文档，路由防漏守卫 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **严格批量写入** | 严格快速失败批量操作 | `SearchTemplate` | - | `indexBatch(Collection<T>)` / `updateBatch(Collection<T>)` / `deleteBatch(Class<?>, Collection<String>)`，任一文档失败立即抛出强类型 `SearchBulkException` 阻断脏数据扩散 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **宽容批量写入** | 容错宽容批量操作 | `SearchTemplate`<br>`SearchBulkResult` | - | `indexBatchLenient(...)` / `updateBatchLenient(...)` / `deleteBatchLenient(...)`，返回包含成功总数与失败明细快照的 `SearchBulkResult`，专供死信与补偿重试 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **流式缓冲写入** | 异步非阻塞批量处理器 | `SearchBulkProcessor` | `ateng.boot.search.bulk.enabled`<br>`ateng.boot.search.bulk.batch-size`<br>`ateng.boot.search.bulk.flush-interval`<br>`ateng.boot.search.bulk.buffer-size` | `add(T doc)` / `addAll(Collection<T> docs)` / `flush()`，基于数量、时间间隔与内存容量三重阈值异步后台刷盘，停机安全排空 | `SearchBulkProcessorTest`<br>`SampleSearchIntegrationTest` |
| **标准浅分页** | 浅分页与刚性熔断 | `SearchTemplate`<br>`SearchPage<T>` | `ateng.boot.search.pagination.max-result-window` | `search(SearchQuery, Class<T>)`，返回标准分页载荷 `SearchPage<T>`（支持 `.toPageResult()` 转为 Core 标准模型），超万级偏移自动抛出 `SearchPaginationExceededException` | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **深度滚动分页** | Search-After 游标滚动 | `SearchTemplate`<br>`SearchScrollResult<T>` | - | `searchAfter(SearchQuery, Object[], int, Class<T>)`，无内存膨胀深翻页，透传末尾排序凭据 `nextSortValues` | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **声明式高亮** | @HighlightField 自动回填 | `SearchTemplate`<br>`@HighlightField` | - | 检索时指定 `.highlight(...)`，自动将匹配命中切片富文本回填至实体对应字段中，视图层开箱即用 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **多租户隔离** | 行级多租户隐式过滤 | `SearchTemplate`<br>`@SearchIgnoreTenant` | `ateng.boot.search.multi-tenancy.enabled`<br>`ateng.boot.search.multi-tenancy.column` | 自动结合 `TenantContextHolder` 注入 Term 过滤；支持实体级别声明 `@SearchIgnoreTenant` 或上下文跳过 | `SearchTemplateMockServerTest` |
| **结构面运维** | 索引与映射结构管理 | `SearchIndexOperations` | - | `create(index)` / `exists(index)` / `delete(index)` / `putMapping(...)` / `putIndexTemplate(...)`，专职 DDL 操作 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **原子别名切换** | 生产原子别名热切换 | `SearchIndexOperations` | - | `swapAlias(alias, oldIndex, newIndex)`，单次底层请求原子执行 remove + add，实现零空窗切源与零停机索引重建 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **动态时序分表** | SpEL 表达式时序索引 | `@SearchIndex` | - | `@SearchIndex(name = "access_log_#{T(java.time.LocalDate).now().format('yyyyMMdd')}")`，写入时自动计算目标物理索引 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **地理空间计算** | Geo 距离筛选与排序 | `SearchQueryBuilder` | - | `.geoDistance("location", lat, lon, "5km")`<br>`.orderByGeoDistance("location", lat, lon)`，构建地理范围过滤与距离排序 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **AI 向量检索** | KNN 密集向量近邻召回 | `SearchQueryBuilder` | - | `.knn("embedding", vector, k, numCandidates)`，声明式构建稠密向量检索，赋能大模型 RAG 知识检索 | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **复合布尔检索** | Fluent 风格查询构造器 | `SearchQueryBuilder` | - | `create()`、`.term()`、`.match()`、`.multiMatch()`、`.range()`、`.termsAggregation()`、`.avgAggregation()`，链式组装复杂 DSL | `SearchTemplateMockServerTest`<br>`SampleSearchIntegrationTest` |
| **原生底层逃生舱** | 官方原生 Client 执行回调 | `SearchTemplate`<br>`SearchCallback<R>` | - | `searchTemplate.execute(client -> ...)`，遇到官方极限复杂特性时可无损直达原生 API，不留死角 | `SearchTemplateMockServerTest` |
| **Actuator 探针** | 云原生健康状态探测 | `SearchHealthIndicator` | `management.health.search.enabled` | 探查集群健康度（GREEN/YELLOW 返回 UP，RED 返回 DOWN），内置 2 秒硬超时熔断机制防御假死 | `SearchHealthIndicatorTest`<br>`SampleSearchIntegrationTest` |
| **黄金度量指标** | Micrometer 核心度量采集 | `SearchMetrics` | `management.metrics.enable.search` | 采集检索耗时 `ateng.search.query.time` 与批量写入耗时 `ateng.search.bulk.time` Timer 指标 | `SearchMetricsTest`<br>`SampleSearchIntegrationTest` |
| **配置元数据** | 强类型配置属性与元数据 | `AtengSearchProperties` | `ateng.boot.search.*` | 标配 `Duration` 超时与 `DataSize` 缓冲容量强类型规范，提供合理默认值与 IDE 智能补全 | `AtengSearchAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-search` 是面向现代企业级生产环境设计的高内聚、纯原生全文检索与流批分析 Starter：
- **纯原生依赖与底座优先**：基于 JDK 17 与 Spring Boot 3 官方依赖管理纳管的 `co.elastic.clients:elasticsearch-java 8.13.4`，零引入任何臃肿第三方工具包；
- **操作门面对称完备**：彻底终结官方客户端复杂的链式嵌套与 Lambda 逃逸地狱，提供高可用、对称完备的 `SearchTemplate` 读写门面；
- **生产安全刚性守卫**：内置 10,000 条浅分页刚性熔断机制（`max-result-window`），支持 `Search-After` 深度游标分页，从根本上防御深翻查询打垮 ES 集群并引发 JVM OOM；
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

## 2. 生产基础配置与极简三板斧起步

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

依托声明式注解轻松绑定文档主键、物理索引、路由键与高亮回填字段：

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

    private String tenantId;

    private LocalDateTime createTime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoPoint {
        private Double lat;
        private Double lon;
    }
}
```

### 2.3 业务检索组件三板斧起步 (Service)

直接注入核心执行门面 `SearchTemplate` 与 `SearchBulkProcessor`：

```java
package io.github.atengk.boot.sample.search.service;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.search.bulk.SearchBulkProcessor;
import io.github.atengk.boot.search.model.OccVersion;
import io.github.atengk.boot.search.model.SearchBulkResult;
import io.github.atengk.boot.search.model.SearchHit;
import io.github.atengk.boot.search.model.SearchPage;
import io.github.atengk.boot.search.model.SearchScrollResult;
import io.github.atengk.boot.search.query.SearchQuery;
import io.github.atengk.boot.search.query.SearchQueryBuilder;
import io.github.atengk.boot.search.template.SearchTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final SearchTemplate searchTemplate;
    private final SearchBulkProcessor searchBulkProcessor;

    // 1. 文档保存与根据主键检索
    public void saveAndGet(ProductDoc product) {
        searchTemplate.save(product);
        ProductDoc fetched = searchTemplate.getById(ProductDoc.class, product.getId(), product.getBrand());
    }

    // 2. 字段级局部增量更新与乐观锁
    public void updateStockAndPrice(String id, String routing, Integer stock, BigDecimal price, OccVersion version) {
        // 简单局部更新
        searchTemplate.update(ProductDoc.class, id, routing, Map.of("stock", stock));
        // 乐观并发控制 (OCC) 条件更新
        searchTemplate.update(ProductDoc.class, id, routing, Map.of("price", price), version);
    }

    // 3. 关键字全文检索与高亮切片回填
    public List<SearchHit<ProductDoc>> searchProducts(String keyword) {
        SearchQuery query = SearchQueryBuilder.create()
                .multiMatch(keyword, "title", "description")
                .highlight("title", "description")
                .build();
        return searchTemplate.search(query, ProductDoc.class).getHits();
    }
}
```

---

## 3. 生产扩展与高级特性接管 (Customization & Extensibility)

### 3.1 生产原子别名热切换 (Zero-Downtime Reindex)

通过 `SearchIndexOperations` 在底层单次请求中原子执行 `remove` 旧索引 + `add` 新索引绑定，彻底杜绝无索引空窗期：

```java
public void reindexAndSwitch() {
    // 切换别名：零停机由 product_v1 原子切至 product_v2
    searchTemplate.indices().swapAlias("product_alias", "product_v1", "product_v2");
}
```

### 3.2 深度游标滚动分页 (Search-After)

越过万级深翻限制，通过透传排序凭据实现内存恒定的深分页遍历：

```java
public void exportDeepData() {
    SearchQuery query = SearchQueryBuilder.create()
            .matchAll()
            .orderByAsc("id")
            .build();

    Object[] nextCursor = null;
    do {
        SearchScrollResult<ProductDoc> result = searchTemplate.searchAfter(query, nextCursor, 1000, ProductDoc.class);
        List<SearchHit<ProductDoc>> hits = result.getHits();
        // 处理当前批次数据...
        nextCursor = result.getNextSortValuesArray();
    } while (nextCursor != null);
}
```

### 3.3 官方原生 Client 逃生舱 (Execute Escape Hatch)

当需要调用官方最新推出的特有高级语法时，依托 `execute` 回调直接操作官方 `ElasticsearchClient`：

```java
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
2. **双模批量写入契约 (`Strict` vs `Lenient`)**：
   - **严格模式 (`indexBatch`)**：底层若存在任意一条文档写入失败，立即抛出 `SearchBulkException` 并附带失败明细，杜绝上层出现静默数据丢失；
   - **宽容模式 (`indexBatchLenient`)**：捕获全部错误项封装进 `SearchBulkResult`，供 MQ 消费端、Canal 异步同步管道投递至死信队列实施局部按项重试；
3. **流式异步刷盘与停机排空安全 (`SearchBulkProcessor`)**：
   实现 Spring `SmartLifecycle` 接口，在 JVM 接收停机信号时触发优雅排空（Drain），最长等待排空完成后方释放底层 HTTP 连接，保证内存缓冲不丢件；
4. **内存真明文与高亮富文本隔离**：
   高亮标签仅回填在视图层模型或标记了 `@HighlightField` 的字段中；持久化存储与实体内存中始终保持 100% 原始真实明文，严禁 HTML 高亮标签回写至持久层；
5. **Actuator 探针 2 秒硬超时熔断与自愈机制**：
   探针独立运行于异步线程池并受 2 秒硬超时保护；当探测到集群状态为 `RED` 或连接假死时，探针自愈翻转为 `DOWN` 状态，触发云原生 K8s 流量隔离摘除。

---

## 5. 样例工程与实测验证 (Sample Sandbox)

模块提供了 1:1 对应的场景样例工程：
- 工程路径：`ateng-boot-samples/ateng-boot-sample-search`
- 特性覆盖：单文档 CRUD、OCC 乐观并发控制、双模 Bulk 批量操作、BulkProcessor 流式缓冲、高亮回填、布尔复合筛选与聚合度量、Geo 空间周边检索、KNN AI 向量检索、浅分页熔断、Search-After 深度游标滚动、SpEL 时序索引、原子别名热切换、Actuator 健康探针与 Micrometer 指标采集。

### 运行自洽集成测试

该工程基于纯内存 `MockWebServer` 搭建，**100% 离线自洽，无需启动真实 Elasticsearch 集群或 Docker 容器**：

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-search -am -T 1C -nsu -B
```
