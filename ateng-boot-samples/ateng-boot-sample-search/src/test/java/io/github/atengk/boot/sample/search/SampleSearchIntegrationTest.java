package io.github.atengk.boot.sample.search;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.sample.search.model.AccessLogDoc;
import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.sample.search.service.ProductSearchService;
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
import io.github.atengk.boot.search.query.SearchQuery;
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
    private ProductSearchService productService;

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
                              "embedding": { "type": "knn_vector", "dimension": 4 }
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
                              "embedding": { "type": "dense_vector", "dims": 4 }
                            }
                          }
                        }
                        """;
            }
            if (!searchTemplate.indices().exists("product_index")) {
                searchTemplate.indices().create("product_index", mappingJson);
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
                    .build();
            productService.saveProduct(doc);

            try {
                searchTemplate.execute(client -> client.indices().refresh(b -> b.index("product_index")));
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

    @Test
    @DisplayName("01. 单文档保存、主键获取、局部更新与删除闭环")
    void testSingleDocumentCrud() {
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
                .build();

        // 保存
        productService.saveProduct(doc);

        // 获取
        ProductDoc fetched = productService.getProduct(testId, "Apple");
        assertThat(fetched).isNotNull();
        if (isLocalProfileActive()) {
            assertThat(fetched.getId()).isEqualTo(testId);
        }
        assertThat(fetched.getTitle()).contains("iPhone");

        // 局部更新库存
        productService.updateStockPartial(testId, "Apple", 88);

        // 删除
        productService.deleteProduct(testId, "Apple");
    }

    @Test
    @DisplayName("02. 乐观并发控制 (OCC) 冲突拦截验证")
    void testOptimisticConcurrencyControl() {
        String testId = isLocalProfileActive() ? "P_OCC_001" : "P001";
        // 先保存以初始化文档并获取有效 OCC 版本
        ProductDoc occDoc = ProductDoc.builder()
                .id(testId)
                .title("iPhone 16 Pro Max")
                .brand("Apple")
                .price(new BigDecimal("9999.00"))
                .build();
        productService.saveProduct(occDoc);
        SearchHit<ProductDoc> hit = searchTemplate.getHitById(ProductDoc.class, testId, "Apple");
        OccVersion validVersion = (hit != null && hit.getOccVersion() != null && hit.getOccVersion().isSpecified())
                ? hit.getOccVersion()
                : new OccVersion(2L, 1L);

        // 正确的版本号正常更新
        productService.updatePriceWithOcc(testId, "Apple", new BigDecimal("9899.00"), validVersion);

        // 错误的版本号 (seq_no=999) 触发 409 拦截并抛出 SearchVersionConflictException
        OccVersion conflictVersion = new OccVersion(999L, 1L);
        assertThatThrownBy(() -> productService.updatePriceWithOcc(testId, "Apple", new BigDecimal("8888.00"), conflictVersion))
                .isInstanceOf(SearchVersionConflictException.class);
    }

    @Test
    @DisplayName("03. 严格批量写入与宽容批量写入（含失败项明细校验）")
    void testBulkOperations() {
        ProductDoc doc1 = ProductDoc.builder().id("P101").title("iPad Pro").brand("Apple").build();
        ProductDoc docFail = ProductDoc.builder().id("FAIL_TRIGGER").title("Error Item").brand("Apple").build();

        if (!isLocalProfileActive()) {
            // 宽容模式：返回明细，不抛异常
            SearchBulkResult lenientResult = productService.bulkSaveLenient(List.of(doc1, docFail));
            assertThat(lenientResult.hasFailures()).isTrue();
            assertThat(lenientResult.getFailureCount()).isEqualTo(1);
            assertThat(lenientResult.getFailures().get(0).getId()).isEqualTo("FAIL_TRIGGER");

            // 严格模式：发现失败立即抛出 SearchBulkException
            assertThatThrownBy(() -> productService.bulkSaveStrict(List.of(doc1, docFail)))
                    .isInstanceOf(SearchBulkException.class);
        } else {
            ProductDoc doc2 = ProductDoc.builder().id("P102").title("MacBook Pro").brand("Apple").build();
            SearchBulkResult lenientResult = productService.bulkSaveLenient(List.of(doc1, doc2));
            assertThat(lenientResult.hasFailures()).isFalse();
            assertThat(lenientResult.getSuccessCount()).isEqualTo(2);

            SearchBulkResult strictResult = productService.bulkSaveStrict(List.of(doc1, doc2));
            assertThat(strictResult.hasFailures()).isFalse();
        }
    }

    @Test
    @DisplayName("04. SearchBulkProcessor 异步流式写入与手动安全排空")
    void testBulkProcessorStreaming() {
        String id1 = isLocalProfileActive() ? "P_STREAM_001" : "P001";
        String id2 = isLocalProfileActive() ? "P_STREAM_002" : "P002";
        ProductDoc doc1 = ProductDoc.builder().id(id1).title("AirPods Pro").brand("Apple").build();
        ProductDoc doc2 = ProductDoc.builder().id(id2).title("MacBook Pro").brand("Apple").build();

        productService.streamIndexProducts(List.of(doc1, doc2));
    }

    @Test
    @DisplayName("05. 关键字全文检索与 @HighlightField 高亮富文本自动回填")
    void testKeywordSearchWithHighlight() {
        List<SearchHit<ProductDoc>> hits = productService.searchByKeyword("手机");
        assertThat(hits).isNotEmpty();

        ProductDoc product = hits.get(0).getData();
        // 验证高亮切片已回填到数据实体字段中
        if (!isLocalProfileActive()) {
            assertThat(product.getTitle()).contains("<em>iPhone</em>");
            assertThat(product.getDescription()).contains("<em>旗舰</em>");
        } else {
            assertThat(product.getTitle() + (product.getDescription() != null ? product.getDescription() : "")).contains("<em>");
        }
    }

    @Test
    @DisplayName("06. 复合布尔筛选、价格区间过滤与品牌聚合度量")
    void testFilterAndAggregation() {
        SearchPage<ProductDoc> page = productService.filterByPriceAndBrand(
                new BigDecimal("5000"), new BigDecimal("12000"), "Apple");

        assertThat(page.getHits()).isNotEmpty();
        assertThat(page.getTotalHits()).isGreaterThanOrEqualTo(1L);
        assertThat(page.getAggregations()).isNotEmpty();
    }

    @Test
    @DisplayName("07. Geo 空间距离筛选与周边商品排序")
    void testGeoDistanceSearch() {
        List<SearchHit<ProductDoc>> hits = productService.searchNearby(31.2304, 121.4737, "5km");
        assertThat(hits).isNotEmpty();
    }

    @Test
    @DisplayName("08. KNN AI 向量近似度检索")
    void testKnnVectorSearch() {
        List<SearchHit<ProductDoc>> hits = productService.searchSimilarKnn(List.of(0.12f, 0.34f, 0.56f, 0.78f), 5);
        assertThat(hits).isNotEmpty();
    }

    @Test
    @DisplayName("09. 浅分页与超过 10,000 条安全刚性熔断")
    void testShallowPagination() {
        PageResult<ProductDoc> result = productService.searchPagingShallow(1, 10);
        assertThat(result.getRecords()).isNotEmpty();
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(1L);

        // 越过 10,000 条深分页安全熔断
        assertThatThrownBy(() -> productService.searchPagingShallow(1001, 10))
                .isInstanceOf(SearchPaginationExceededException.class);
    }

    @Test
    @DisplayName("10. searchAfter 深度游标滚动分页")
    void testSearchAfterCursor() {
        SearchScrollResult<ProductDoc> result = productService.searchPagingCursor(null, 1);
        assertThat(result.getHits()).isNotEmpty();
        assertThat(result.hasNext()).isTrue();
        assertThat(result.getNextSortValues()).isNotEmpty();
    }

    @Test
    @DisplayName("11. SpEL 动态时序分表与别名原子热切换")
    void testTimeSeriesAndAliasOperations() {
        AccessLogDoc log = AccessLogDoc.builder()
                .id("LOG-001")
                .path("/api/products/search")
                .clientIp("127.0.0.1")
                .timestamp(LocalDateTime.now())
                .build();

        productService.saveAccessLog(log);

        if (isLocalProfileActive()) {
            if (!searchTemplate.indices().exists("product_v1")) {
                searchTemplate.indices().create("product_v1");
            }
            if (!searchTemplate.indices().exists("product_v2")) {
                searchTemplate.indices().create("product_v2");
            }
            if (!searchTemplate.indices().existsAlias("product_alias")) {
                searchTemplate.indices().swapAlias("product_alias", null, "product_v1");
            }
        }

        productService.swapAlias("product_alias", "product_v1", "product_v2");
    }

    @Test
    @DisplayName("12. Actuator 云原生健康探针与 Micrometer 性能度量指标采集验证")
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
        // 执行一次带度量的检索确保度量计数器自增
        searchTemplate.search(SearchQueryBuilder.create().matchAll().build(), ProductDoc.class);
        assertThat(searchMetrics.getQueryTimer().count()).isGreaterThanOrEqualTo(1L);
    }
}
