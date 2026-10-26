package io.github.atengk.boot.sample.search;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.sample.search.model.AccessLogDoc;
import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.sample.search.service.ProductSearchService;
import io.github.atengk.boot.sample.search.support.MockSearchServerInitializer;
import io.github.atengk.boot.search.actuator.SearchHealthIndicator;
import io.github.atengk.boot.search.exception.SearchBulkException;
import io.github.atengk.boot.search.exception.SearchPaginationExceededException;
import io.github.atengk.boot.search.exception.SearchVersionConflictException;
import io.github.atengk.boot.search.metrics.SearchMetrics;
import io.github.atengk.boot.search.model.OccVersion;
import io.github.atengk.boot.search.model.SearchBulkResult;
import io.github.atengk.boot.search.model.SearchHit;
import io.github.atengk.boot.search.model.SearchPage;
import io.github.atengk.boot.search.model.SearchScrollResult;
import io.github.atengk.boot.search.query.SearchQuery;
import io.github.atengk.boot.search.query.SearchQueryBuilder;
import io.github.atengk.boot.search.template.SearchTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 全文检索样例工程端到端集成测试（100% 内存自洽离线运行）
 *
 * @author Ateng
 * @since 2026-10-26
 */
@SpringBootTest(classes = SampleSearchApplication.class)
@ContextConfiguration(initializers = MockSearchServerInitializer.class)
class SampleSearchIntegrationTest {

    @Autowired
    private ProductSearchService productService;

    @Autowired
    private SearchTemplate searchTemplate;

    @Autowired(required = false)
    private SearchHealthIndicator healthIndicator;

    @Autowired(required = false)
    private SearchMetrics searchMetrics;

    @Test
    @DisplayName("1. 单文档保存、主键获取、局部更新与删除闭环")
    void testSingleDocumentCrud() {
        ProductDoc doc = ProductDoc.builder()
                .id("P001")
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
        ProductDoc fetched = productService.getProduct("P001", "Apple");
        assertThat(fetched).isNotNull();
        assertThat(fetched.getId()).isEqualTo("P001");
        assertThat(fetched.getTitle()).contains("iPhone");

        // 局部更新库存
        productService.updateStockPartial("P001", "Apple", 88);

        // 删除
        productService.deleteProduct("P001", "Apple");
    }

    @Test
    @DisplayName("2. 乐观并发控制 (OCC) 冲突拦截验证")
    void testOptimisticConcurrencyControl() {
        // 正确的版本号 (seq_no=2, primary_term=1) 正常更新
        OccVersion validVersion = new OccVersion(2L, 1L);
        productService.updatePriceWithOcc("P001", "Apple", new BigDecimal("9899.00"), validVersion);

        // 错误的版本号 (seq_no=999) 触发 409 拦截并抛出 SearchVersionConflictException
        OccVersion conflictVersion = new OccVersion(999L, 1L);
        assertThatThrownBy(() -> productService.updatePriceWithOcc("P001", "Apple", new BigDecimal("8888.00"), conflictVersion))
                .isInstanceOf(SearchVersionConflictException.class);
    }

    @Test
    @DisplayName("3. 严格批量写入与宽容批量写入（含失败项明细校验）")
    void testBulkOperations() {
        ProductDoc doc1 = ProductDoc.builder().id("P101").title("iPad Pro").brand("Apple").build();
        ProductDoc docFail = ProductDoc.builder().id("FAIL_TRIGGER").title("Error Item").brand("Apple").build();

        // 宽容模式：返回明细，不抛异常
        SearchBulkResult lenientResult = productService.bulkSaveLenient(List.of(doc1, docFail));
        assertThat(lenientResult.hasFailures()).isTrue();
        assertThat(lenientResult.getFailureCount()).isEqualTo(1);
        assertThat(lenientResult.getFailures().get(0).getId()).isEqualTo("FAIL_TRIGGER");

        // 严格模式：发现失败立即抛出 SearchBulkException
        assertThatThrownBy(() -> productService.bulkSaveStrict(List.of(doc1, docFail)))
                .isInstanceOf(SearchBulkException.class);
    }

    @Test
    @DisplayName("4. SearchBulkProcessor 异步流式写入与手动安全排空")
    void testBulkProcessorStreaming() {
        ProductDoc doc1 = ProductDoc.builder().id("P001").title("AirPods Pro").brand("Apple").build();
        ProductDoc doc2 = ProductDoc.builder().id("P002").title("MacBook Pro").brand("Apple").build();

        productService.streamIndexProducts(List.of(doc1, doc2));
    }

    @Test
    @DisplayName("5. 关键字全文检索与 @HighlightField 高亮富文本自动回填")
    void testKeywordSearchWithHighlight() {
        List<SearchHit<ProductDoc>> hits = productService.searchByKeyword("手机");
        assertThat(hits).isNotEmpty();

        ProductDoc product = hits.get(0).getData();
        // 验证高亮切片已回填到数据实体字段中
        assertThat(product.getTitle()).contains("<em>iPhone</em>");
        assertThat(product.getDescription()).contains("<em>旗舰</em>");
    }

    @Test
    @DisplayName("6. 复合布尔筛选、价格区间过滤与品牌聚合度量")
    void testFilterAndAggregation() {
        SearchPage<ProductDoc> page = productService.filterByPriceAndBrand(
                new BigDecimal("5000"), new BigDecimal("12000"), "Apple");

        assertThat(page.getHits()).isNotEmpty();
        assertThat(page.getTotalHits()).isEqualTo(1L);
        assertThat(page.getAggregations()).isNotEmpty();
    }

    @Test
    @DisplayName("7. Geo 空间距离筛选与周边商品排序")
    void testGeoDistanceSearch() {
        List<SearchHit<ProductDoc>> hits = productService.searchNearby(31.2304, 121.4737, "5km");
        assertThat(hits).isNotEmpty();
    }

    @Test
    @DisplayName("8. KNN AI 向量近似度检索")
    void testKnnVectorSearch() {
        List<SearchHit<ProductDoc>> hits = productService.searchSimilarKnn(List.of(0.12f, 0.34f, 0.56f, 0.78f), 5);
        assertThat(hits).isNotEmpty();
    }

    @Test
    @DisplayName("9. 浅分页与超过 10,000 条安全刚性熔断")
    void testShallowPagination() {
        PageResult<ProductDoc> result = productService.searchPagingShallow(1, 10);
        assertThat(result.getRecords()).isNotEmpty();
        assertThat(result.getTotal()).isEqualTo(1L);

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
        productService.swapAlias("product_alias", "product_v1", "product_v2");
    }

    @Test
    @DisplayName("12. Actuator 云原生健康探针与 Micrometer 性能度量指标采集验证")
    void testActuatorAndMetrics() {
        assertThat(healthIndicator).isNotNull();
        Health health = healthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "green");
        assertThat(health.getDetails()).containsEntry("numberOfNodes", 3);

        assertThat(searchMetrics).isNotNull();
        assertThat(searchMetrics.getQueryTimer()).isNotNull();
        assertThat(searchMetrics.getBulkTimer()).isNotNull();
        // 执行一次带度量的检索确保度量计数器自增
        searchTemplate.search(SearchQueryBuilder.create().matchAll().build(), ProductDoc.class);
        assertThat(searchMetrics.getQueryTimer().count()).isGreaterThanOrEqualTo(1L);
    }
}
