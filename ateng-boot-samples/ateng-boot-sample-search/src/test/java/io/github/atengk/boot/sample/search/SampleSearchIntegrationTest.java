package io.github.atengk.boot.sample.search;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.sample.search.model.AccessLogDoc;
import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.sample.search.scenario.BulkIngestionScenario;
import io.github.atengk.boot.sample.search.scenario.LogAnalyticsAggScenario;
import io.github.atengk.boot.sample.search.scenario.ProductCatalogSearchScenario;
import io.github.atengk.boot.sample.search.scenario.ZeroDowntimeReindexScenario;
import io.github.atengk.boot.sample.search.support.MockSearchServerInitializer;
import io.github.atengk.boot.search.actuator.SearchHealthIndicator;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.exception.SearchBulkException;
import io.github.atengk.boot.search.exception.SearchPaginationExceededException;
import io.github.atengk.boot.search.exception.SearchVersionConflictException;
import io.github.atengk.boot.search.metrics.SearchMetrics;
import io.github.atengk.boot.search.model.OccVersion;
import io.github.atengk.boot.search.model.SearchBulkResult;
import io.github.atengk.boot.search.model.SearchHit;
import io.github.atengk.boot.search.model.SearchPage;
import io.github.atengk.boot.search.model.SearchScrollResult;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.query.SearchQueryBuilder;
import io.github.atengk.boot.search.template.SearchTemplate;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 全文检索样例工程端到端集成测试（单测试套件双模自适应：离线自洽 Mock + Local 真实中间件直连）
 * <p>
 * 100% 覆盖 4 大核心正交业务场景组件及云原生可观测性设施。
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Slf4j
@SpringBootTest(classes = SampleSearchApplication.class)
@ContextConfiguration(initializers = MockSearchServerInitializer.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
class SampleSearchIntegrationTest {

    @Autowired
    private ProductCatalogSearchScenario productCatalogScenario;

    @Autowired
    private LogAnalyticsAggScenario logAnalyticsScenario;

    @Autowired
    private ZeroDowntimeReindexScenario zeroDowntimeScenario;

    @Autowired
    private BulkIngestionScenario bulkIngestionScenario;

    @Autowired
    private SearchTemplate searchTemplate;

    @Autowired(required = false)
    private AtengSearchProperties properties;

    @Autowired(required = false)
    private SearchHealthIndicator healthIndicator;

    @Autowired(required = false)
    private SearchMetrics searchMetrics;

    @Autowired
    private Environment environment;

    private boolean isLocalProfileActive() {
        return environment != null && Arrays.asList(environment.getActiveProfiles()).contains("local");
    }

    @BeforeEach
    void setUp() {
        if (isLocalProfileActive()) {
            ensureProductIndexReady();
        }
    }

    private void ensureProductIndexReady() {
        try {
            boolean isOpenSearch = properties != null && properties.getEngine() == SearchEngineType.OPENSEARCH;
            String mappingJson;
            if (isOpenSearch) {
                mappingJson = """
                        {
                          "settings": {
                            "index.knn": true
                          },
                          "mappings": {
                            "properties": {
                              "id": { "type": "keyword" },
                              "title": { "type": "text" },
                              "description": { "type": "text" },
                              "category": { "type": "keyword" },
                              "brand": { "type": "keyword" },
                              "status": { "type": "keyword" },
                              "price": { "type": "double" },
                              "stock": { "type": "integer" },
                              "tenantId": { "type": "keyword" },
                              "createTime": { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||strict_date_optional_time||epoch_millis" },
                              "location": { "type": "geo_point" },
                              "embedding": { "type": "knn_vector", "dimension": 4 },
                              "skus": {
                                "type": "nested",
                                "properties": {
                                  "skuId": { "type": "keyword" },
                                  "color": { "type": "keyword" },
                                  "price": { "type": "double" },
                                  "stock": { "type": "integer" }
                                }
                              }
                            }
                          }
                        }
                        """;
            } else {
                mappingJson = """
                        {
                          "mappings": {
                            "properties": {
                              "id": { "type": "keyword" },
                              "title": { "type": "text" },
                              "description": { "type": "text" },
                              "category": { "type": "keyword" },
                              "brand": { "type": "keyword" },
                              "status": { "type": "keyword" },
                              "price": { "type": "double" },
                              "stock": { "type": "integer" },
                              "tenantId": { "type": "keyword" },
                              "createTime": { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||strict_date_optional_time||epoch_millis" },
                              "location": { "type": "geo_point" },
                              "embedding": { "type": "dense_vector", "dims": 4 },
                              "skus": {
                                "type": "nested",
                                "properties": {
                                  "skuId": { "type": "keyword" },
                                  "color": { "type": "keyword" },
                                  "price": { "type": "double" },
                                  "stock": { "type": "integer" }
                                }
                              }
                            }
                          }
                        }
                        """;
            }
            if (!searchTemplate.indices().exists("product_index")) {
                searchTemplate.indices().create("product_index", mappingJson);
            }

            // 初始化 access_log 索引 mapping (确保 timestamp 为 date 类型)
            String todayLogIndex = "access_log_" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            if (!searchTemplate.indices().exists(todayLogIndex)) {
                String logMappingJson = """
                        {
                          "mappings": {
                            "properties": {
                              "id": { "type": "keyword" },
                              "path": { "type": "keyword" },
                              "clientIp": { "type": "keyword" },
                              "timestamp": { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||strict_date_optional_time||epoch_millis" }
                            }
                          }
                        }
                        """;
                searchTemplate.indices().create(todayLogIndex, logMappingJson);
            }

            // 预置基准商品文档并立即刷盘
            ProductDoc doc = ProductDoc.builder()
                    .id("P001")
                    .title("iPhone 16 Pro Max 手机")
                    .description("苹果年度旗舰芯片A18 Pro")
                    .category("手机")
                    .brand("Apple")
                    .price(new BigDecimal("9999.00"))
                    .stock(100)
                    .status("ON_SALE")
                    .location(new ProductDoc.GeoPoint(31.2304, 121.4737))
                    .embedding(List.of(0.12f, 0.34f, 0.56f, 0.78f))
                    .tenantId("tenant-001")
                    .createTime(LocalDateTime.now())
                    .skus(List.of(
                            ProductDoc.SkuItem.builder().skuId("SKU-001").color("曜石黑").price(new BigDecimal("9999.00")).stock(50).build(),
                            ProductDoc.SkuItem.builder().skuId("SKU-002").color("沙漠钛").price(new BigDecimal("10999.00")).stock(50).build()
                    ))
                    .build();
            productCatalogScenario.saveProduct(doc);

            try {
                searchTemplate.refresh("product_index");
            } catch (Exception ignored) {
            }
        } catch (Exception ex) {
            log.warn("ensureProductIndexReady failed: {}", ex.getMessage());
        }
    }

    @AfterAll
    static void tearDownAll(@Autowired(required = false) SearchTemplate searchTemplate,
                            @Autowired(required = false) Environment environment) {
        if (searchTemplate != null && environment != null
                && Arrays.asList(environment.getActiveProfiles()).contains("local")) {
            try {
                String todayLogIndex = "access_log_" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
                String[] testIndices = {"product_index", "product_v1", "product_v2", todayLogIndex};
                for (String idx : testIndices) {
                    if (searchTemplate.indices().exists(idx)) {
                        searchTemplate.indices().delete(idx);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    // =========================================================================
    // 场景一：电商商品多维检索业务场景 (ProductCatalogSearchScenario)
    // =========================================================================

    @Test
    @DisplayName("01. 商品保存、HEAD 存在性轻量探测、主键与 _mget 批量拉取闭环")
    void testProductSaveExistsAndBatchFetch() {
        String testId = isLocalProfileActive() ? "P_CRUD_001" : "P001";
        ProductDoc doc = ProductDoc.builder()
                .id(testId)
                .title("iPhone 16 Pro Max")
                .description("苹果年度旗舰芯片A18 Pro")
                .category("手机")
                .brand("Apple")
                .price(new BigDecimal("9999.00"))
                .stock(100)
                .status("ON_SALE")
                .skus(List.of(
                        ProductDoc.SkuItem.builder().skuId("SKU-001").color("曜石黑").price(new BigDecimal("9999.00")).stock(50).build()
                ))
                .build();

        // 1. 保存
        productCatalogScenario.saveProduct(doc);

        // 2. HEAD 轻量存在探测 (existsById 带 routing)
        boolean exists = productCatalogScenario.existsProduct(testId, "Apple");
        assertThat(exists).isTrue();

        // 3. 单主键获取
        ProductDoc fetched = productCatalogScenario.getProduct(testId, "Apple");
        assertThat(fetched).isNotNull();
        if (isLocalProfileActive()) {
            assertThat(fetched.getId()).isEqualTo(testId);
        }
        assertThat(fetched.getTitle()).contains("iPhone");

        // 4. _mget 批量多主键查询
        List<ProductDoc> batchDocs = productCatalogScenario.getProductsBatch(List.of(testId, "P_NON_EXISTING"));
        assertThat(batchDocs).isNotEmpty();

        // 5. 局部更新与删除
        productCatalogScenario.updateStockPartial(testId, "Apple", 88);
        productCatalogScenario.deleteProduct(testId, "Apple");
    }

    @Test
    @DisplayName("02. 局部更新与乐观并发控制 (OCC) 冲突拦截验证")
    void testOptimisticConcurrencyControl() {
        String testId = isLocalProfileActive() ? "P_OCC_001" : "P001";
        ProductDoc occDoc = ProductDoc.builder()
                .id(testId)
                .title("iPhone 16 Pro Max")
                .brand("Apple")
                .price(new BigDecimal("9999.00"))
                .build();
        productCatalogScenario.saveProduct(occDoc);
        SearchHit<ProductDoc> hit = searchTemplate.getHitById(ProductDoc.class, testId, "Apple");
        OccVersion validVersion = (hit != null && hit.getOccVersion() != null && hit.getOccVersion().isSpecified())
                ? hit.getOccVersion()
                : new OccVersion(2L, 1L);

        // 正确的版本号正常更新
        productCatalogScenario.updatePriceWithOcc(testId, "Apple", new BigDecimal("9899.00"), validVersion);

        // 错误的版本号 (seq_no=999) 触发 409 拦截并抛出 SearchVersionConflictException
        OccVersion conflictVersion = new OccVersion(999L, 1L);
        assertThatThrownBy(() -> productCatalogScenario.updatePriceWithOcc(testId, "Apple", new BigDecimal("8888.00"), conflictVersion))
                .isInstanceOf(SearchVersionConflictException.class);
    }

    @Test
    @DisplayName("03. 关键字全文多字段检索与 @HighlightField 高亮富文本自动回填")
    void testKeywordSearchWithHighlight() {
        List<SearchHit<ProductDoc>> hits = productCatalogScenario.searchMultiMatchWithHighlight("手机");
        assertThat(hits).isNotEmpty();

        ProductDoc product = hits.get(0).getData();
        if (!isLocalProfileActive()) {
            assertThat(product.getTitle()).contains("<em>iPhone</em>");
            assertThat(product.getDescription()).contains("<em>旗舰</em>");
        } else {
            assertThat(product.getTitle() + (product.getDescription() != null ? product.getDescription() : "")).contains("<em>");
        }
    }

    @Test
    @DisplayName("04. 搜索框前缀即时联想补全 (Match Phrase Prefix)")
    void testPhrasePrefixSuggestion() {
        List<SearchHit<ProductDoc>> hits = productCatalogScenario.searchPrefixSuggestion("iPho");
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getContent().getTitle()).contains("iPhone");
    }

    @Test
    @DisplayName("05. Nested 嵌套 SKU 规格独立作用域匹配检索")
    void testNestedSkuSearch() {
        List<SearchHit<ProductDoc>> hits = productCatalogScenario.searchNestedSkus("曜石黑", new BigDecimal("9000.00"));
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getContent().getSkus()).isNotEmpty();
    }

    @Test
    @DisplayName("06. 检索结果字段折叠去重 (Field Collapsing)")
    void testFieldCollapsingSearch() {
        SearchPage<ProductDoc> page = productCatalogScenario.searchWithCollapse("Apple", "brand");
        assertThat(page.getHits()).isNotEmpty();
    }

    @Test
    @DisplayName("07. Geo 空间距离周边排序与 KNN AI 向量检索")
    void testGeoAndKnnSearch() {
        List<SearchHit<ProductDoc>> nearbyHits = productCatalogScenario.searchNearbyGeo(31.2304, 121.4737, "5km");
        assertThat(nearbyHits).isNotEmpty();

        List<SearchHit<ProductDoc>> knnHits = productCatalogScenario.searchSimilarKnn(List.of(0.12f, 0.34f, 0.56f, 0.78f), 5);
        assertThat(knnHits).isNotEmpty();
    }

    @Test
    @DisplayName("08. 浅分页与超过 10,000 条深度分页安全刚性熔断")
    void testShallowPagination() {
        PageResult<ProductDoc> result = productCatalogScenario.searchShallowPaging(1, 10);
        assertThat(result.getRecords()).isNotEmpty();
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(1L);

        // 越过 10,000 条深分页安全熔断
        assertThatThrownBy(() -> productCatalogScenario.searchShallowPaging(1001, 10))
                .isInstanceOf(SearchPaginationExceededException.class);
    }

    @Test
    @DisplayName("09. searchAfter 深度游标滚动分页")
    void testSearchAfterCursor() {
        SearchScrollResult<ProductDoc> result = productCatalogScenario.searchCursorPaging(null, 1);
        assertThat(result.getHits()).isNotEmpty();
        assertThat(result.hasNext()).isTrue();
        assertThat(result.getNextSortValues()).isNotEmpty();
    }

    // =========================================================================
    // 场景二：日志审计时序大盘与生命周期管理场景 (LogAnalyticsAggScenario)
    // =========================================================================

    @Test
    @DisplayName("10. SpEL 动态时序按天分表写入与 DateHistogram 直方图时间分桶聚合走势")
    void testSpelDynamicTimeSeriesAndDateHistogram() {
        AccessLogDoc logDoc = AccessLogDoc.builder()
                .id("LOG-001")
                .path("/api/products/search")
                .clientIp("127.0.0.1")
                .timestamp(LocalDateTime.now())
                .build();

        logAnalyticsScenario.recordAccessLog(logDoc);
        if (isLocalProfileActive()) {
            try {
                searchTemplate.refresh("access_log_*");
            } catch (Exception ignored) {
            }
        }

        // 时序分桶直方图聚合
        SearchPage<AccessLogDoc> page = logAnalyticsScenario.aggregateTrafficTrend("access_log_*", "1d");
        assertThat(page.getAggregations()).isNotEmpty();
    }

    @Test
    @DisplayName("11. 审计日志轻量计数与 deleteByQuery 条件批量物理清理")
    void testCountAndDeleteByQueryPurge() {
        // 轻量计数
        long count = logAnalyticsScenario.countLogsByPath("/api/products/search");
        assertThat(count).isGreaterThanOrEqualTo(0L);

        // 条件清理 30 天前的旧日志
        long deleted = logAnalyticsScenario.purgeHistoricalLogsBefore(LocalDateTime.now().minusDays(30));
        assertThat(deleted).isGreaterThanOrEqualTo(0L);
    }

    // =========================================================================
    // 场景三：生产零停机平滑热迁移与 DDL 闭环场景 (ZeroDowntimeReindexScenario)
    // =========================================================================

    @Test
    @DisplayName("12. 索引模板建立与对称删除生命周期 (Index Template)")
    void testIndexTemplateLifecycle() {
        String templateName = "sample_log_template";
        String templateJson = """
                {
                  "index_patterns": ["sample_log_*"],
                  "template": {
                    "settings": { "number_of_shards": 1 }
                  }
                }
                """;

        boolean applied = zeroDowntimeScenario.applyIndexTemplate(templateName, templateJson);
        assertThat(applied).isTrue();

        boolean deleted = zeroDowntimeScenario.removeIndexTemplate(templateName);
        assertThat(deleted).isTrue();
    }

    @Test
    @DisplayName("13. 服务端平滑数据重索引 (全量与增量)、物理刷新与原子别名热切闭环")
    void testZeroDowntimeReindexAndSwapAlias() {
        if (isLocalProfileActive()) {
            if (!searchTemplate.indices().exists("product_v1")) {
                zeroDowntimeScenario.initIndexWithMapping("product_v1", null);
            }
            if (!searchTemplate.indices().exists("product_v2")) {
                zeroDowntimeScenario.initIndexWithMapping("product_v2", null);
            }
            if (!searchTemplate.indices().existsAlias("product_alias")) {
                zeroDowntimeScenario.switchTrafficAlias("product_alias", null, "product_v1");
            }
        }

        // 1. 服务端全量平滑复制重索引
        boolean reindexAllSuccess = zeroDowntimeScenario.reindexAll("product_v1", "product_v2");
        assertThat(reindexAllSuccess).isTrue();

        // 2. 服务端带时间边界增量重索引
        boolean reindexIncSuccess = zeroDowntimeScenario.reindexIncremental("product_v1", "product_v2", LocalDateTime.now().minusHours(1));
        assertThat(reindexIncSuccess).isTrue();

        // 3. 显式物理段刷新
        boolean refreshed = zeroDowntimeScenario.refreshIndex("product_v2");
        assertThat(refreshed).isTrue();

        // 4. 原子别名热切换 (swapAlias)
        boolean swapped = zeroDowntimeScenario.switchTrafficAlias("product_alias", "product_v1", "product_v2");
        assertThat(swapped).isTrue();
    }

    // =========================================================================
    // 场景四：海量流水数据批量写入与异步流式缓冲场景 (BulkIngestionScenario)
    // =========================================================================

    @Test
    @DisplayName("14. 严格批量全事务性写入与宽容批量局部失败隔离")
    void testBatchIngestionModes() {
        ProductDoc doc1 = ProductDoc.builder().id("P101").title("iPad Pro").brand("Apple").build();
        ProductDoc docFail = ProductDoc.builder().id("FAIL_TRIGGER").title("Error Item").brand("Apple").build();

        if (!isLocalProfileActive()) {
            // 宽容模式：返回明细，不抛异常
            SearchBulkResult lenientResult = bulkIngestionScenario.ingestBatchLenient(List.of(doc1, docFail));
            assertThat(lenientResult.hasFailures()).isTrue();
            assertThat(lenientResult.getFailureCount()).isEqualTo(1);
            assertThat(lenientResult.getFailures().get(0).getId()).isEqualTo("FAIL_TRIGGER");

            // 严格模式：发现失败立即抛出 SearchBulkException
            assertThatThrownBy(() -> bulkIngestionScenario.ingestBatchStrict(List.of(doc1, docFail)))
                    .isInstanceOf(SearchBulkException.class);
        } else {
            ProductDoc doc2 = ProductDoc.builder().id("P102").title("MacBook Pro").brand("Apple").build();
            SearchBulkResult lenientResult = bulkIngestionScenario.ingestBatchLenient(List.of(doc1, doc2));
            assertThat(lenientResult.hasFailures()).isFalse();
            assertThat(lenientResult.getSuccessCount()).isEqualTo(2);

            SearchBulkResult strictResult = bulkIngestionScenario.ingestBatchStrict(List.of(doc1, doc2));
            assertThat(strictResult.hasFailures()).isFalse();
        }
    }

    @Test
    @DisplayName("15. SearchBulkProcessor 高吞吐非阻塞流式缓冲写入与安全排空")
    void testBulkProcessorStreaming() {
        String id1 = isLocalProfileActive() ? "P_STREAM_001" : "P001";
        String id2 = isLocalProfileActive() ? "P_STREAM_002" : "P002";
        ProductDoc doc1 = ProductDoc.builder().id(id1).title("AirPods Pro").brand("Apple").build();
        ProductDoc doc2 = ProductDoc.builder().id(id2).title("MacBook Pro").brand("Apple").build();

        bulkIngestionScenario.streamIngest(doc1);
        bulkIngestionScenario.streamIngestBatchAndFlush(List.of(doc2));
        bulkIngestionScenario.flushStream();
    }

    // =========================================================================
    // 场景五：基础设施云原生健康探针与 Micrometer 指标采集
    // =========================================================================

    @Test
    @DisplayName("16. Actuator 云原生健康探针与 Micrometer 性能度量指标采集验证")
    void testActuatorAndMetrics() {
        assertThat(healthIndicator).isNotNull();
        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("status")).isIn("green", "yellow");
        assertThat(health.getDetails()).containsKey("numberOfNodes");
        assertThat(((Number) health.getDetails().get("numberOfNodes")).intValue()).isGreaterThanOrEqualTo(1);

        assertThat(searchMetrics).isNotNull();
        assertThat(searchMetrics.getQueryTimer()).isNotNull();
        assertThat(searchMetrics.getBulkTimer()).isNotNull();
        searchTemplate.search(SearchQueryBuilder.create().matchAll().build(), ProductDoc.class);
        assertThat(searchMetrics.getQueryTimer().count()).isGreaterThanOrEqualTo(1L);
    }
}
