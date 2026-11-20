package io.github.atengk.boot.sample.search;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.search.dto.AccessLogMarkIpRequest;
import io.github.atengk.boot.sample.search.dto.AccessLogPurgeRequest;
import io.github.atengk.boot.sample.search.dto.AccessLogRecordRequest;
import io.github.atengk.boot.sample.search.dto.AliasSwapRequest;
import io.github.atengk.boot.sample.search.dto.BulkDeadLetterStatsResponse;
import io.github.atengk.boot.sample.search.dto.BulkIngestRequest;
import io.github.atengk.boot.sample.search.dto.BulkRawIngestRequest;
import io.github.atengk.boot.sample.search.dto.BulkStreamDeleteRequest;
import io.github.atengk.boot.sample.search.dto.ComponentTemplateRequest;
import io.github.atengk.boot.sample.search.dto.IndexAliasManageRequest;
import io.github.atengk.boot.sample.search.dto.IndexCreateRequest;
import io.github.atengk.boot.sample.search.dto.IndexSettingsUpdateRequest;
import io.github.atengk.boot.sample.search.dto.IndexTemplateRequest;
import io.github.atengk.boot.sample.search.dto.ProductBatchGetRequest;
import io.github.atengk.boot.sample.search.dto.ProductCollapseRequest;
import io.github.atengk.boot.sample.search.dto.ProductGeoSearchRequest;
import io.github.atengk.boot.sample.search.dto.ProductKnnSearchRequest;
import io.github.atengk.boot.sample.search.dto.ProductNestedSearchRequest;
import io.github.atengk.boot.sample.search.dto.ProductOccUpdateRequest;
import io.github.atengk.boot.sample.search.dto.ProductPartialUpdateRequest;
import io.github.atengk.boot.sample.search.dto.ProductSaveRequest;
import io.github.atengk.boot.sample.search.dto.ProductScrollQueryRequest;
import io.github.atengk.boot.sample.search.dto.ProductSearchRequest;
import io.github.atengk.boot.sample.search.dto.ReindexRequest;
import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.sample.search.support.MockSearchServerInitializer;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.model.SearchHit;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.template.SearchTemplate;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全文检索样例工程 4 大领域控制器 Web 端到端自动化回归测试
 * <p>
 * 支持离线自洽 MockSearchServer 与 {@code -Dspring.profiles.active=local} 真实集群双模回归，
 * 全覆盖商品检索、日志分析、批量写入与索引生命周期控制器。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@SpringBootTest(classes = SampleSearchApplication.class)
@AutoConfigureMockMvc
@ContextConfiguration(initializers = MockSearchServerInitializer.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
class SampleSearchWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SearchTemplate searchTemplate;

    @Autowired(required = false)
    private AtengSearchProperties properties;

    @Autowired
    private Environment environment;

    private static final String OPENSEARCH_PRODUCT_MAPPING = """
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

    private static final String ELASTICSEARCH_PRODUCT_MAPPING = """
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

    private static final String ACCESS_LOG_MAPPING = """
            {
              "mappings": {
                "properties": {
                  "id": { "type": "keyword" },
                  "path": { "type": "keyword" },
                  "clientIp": { "type": "keyword" },
                  "tenantId": { "type": "keyword" },
                  "timestamp": { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||strict_date_optional_time||epoch_millis" }
                }
              }
            }
            """;

    private boolean isLocalProfileActive() {
        return environment != null && Arrays.asList(environment.getActiveProfiles()).contains("local");
    }

    @BeforeEach
    void setUp() {
        if (isLocalProfileActive()) {
            ensureLocalIndicesReady();
        }
    }

    private void ensureLocalIndicesReady() {
        try {
            boolean isOpenSearch = properties != null && properties.getEngine() == SearchEngineType.OPENSEARCH;
            String mappingJson = isOpenSearch ? OPENSEARCH_PRODUCT_MAPPING : ELASTICSEARCH_PRODUCT_MAPPING;
            if (!searchTemplate.indices().exists("product_index")) {
                searchTemplate.indices().create("product_index", mappingJson);
            }
            String todayLogIndex = "access_log_" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            if (!searchTemplate.indices().exists(todayLogIndex)) {
                searchTemplate.indices().create(todayLogIndex, ACCESS_LOG_MAPPING);
            }
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
            searchTemplate.index(doc);
            searchTemplate.indices().refresh("product_index");
        } catch (Exception ex) {
            log.warn("ensureLocalIndicesReady failed: {}", ex.getMessage());
        }
    }

    @AfterAll
    static void tearDownAll(@Autowired(required = false) SearchTemplate searchTemplate,
                            @Autowired(required = false) Environment environment) {
        if (searchTemplate != null && environment != null
                && Arrays.asList(environment.getActiveProfiles()).contains("local")) {
            try {
                String todayLogIndex = "access_log_" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
                String[] testIndices = {"product_index", "product_v1", "product_v2", "order_idx", todayLogIndex};
                for (String idx : testIndices) {
                    if (searchTemplate.indices().exists(idx)) {
                        searchTemplate.indices().delete(idx);
                    }
                }
                if (searchTemplate.indices().existsIndexTemplate("sample_log_template")) {
                    searchTemplate.indices().deleteIndexTemplate("sample_log_template");
                }
                if (searchTemplate.indices().existsComponentTemplate("sample_comp_tpl")) {
                    searchTemplate.indices().deleteComponentTemplate("sample_comp_tpl");
                }
            } catch (Exception ex) {
                log.warn("Failed to clean up local test indices in tearDownAll: {}", ex.getMessage());
            }
        }
    }

    // =========================================================================
    // 1. ProductSearchController 测试
    // =========================================================================

    @Test
    @DisplayName("1.1 保存商品文档 - 成功")
    void test01_saveProduct_success() throws Exception {
        ProductSaveRequest request = ProductSaveRequest.builder()
                .id("P001")
                .title("iPhone 16 Pro Max")
                .description("苹果年度旗舰")
                .brand("Apple")
                .category("手机")
                .price(new BigDecimal("9999.00"))
                .stock(100)
                .status("ON_SALE")
                .tags(Arrays.asList("数码", "旗舰"))
                .skus(List.of(
                        ProductSaveRequest.SkuItemDto.builder()
                                .skuId("SKU-001")
                                .color("曜石黑")
                                .price(new BigDecimal("9999.00"))
                                .stock(50)
                                .build()
                ))
                .build();

        mockMvc.perform(post("/sample/search/product/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo("P001")));
    }

    @Test
    @DisplayName("1.2 保存商品文档 - 参数校验阻断")
    void test02_saveProduct_validationFailure() throws Exception {
        ProductSaveRequest invalidRequest = ProductSaveRequest.builder()
                .title("") // 标题为空触发 @NotBlank
                .brand("Apple")
                .price(new BigDecimal("99.00"))
                .build();

        mockMvc.perform(post("/sample/search/product/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(invalidRequest)))
                .andExpect(jsonPath("$.code", notNullValue()));
    }

    @Test
    @DisplayName("1.3 探测商品是否存在 (HEAD)")
    void test03_existsProduct() throws Exception {
        mockMvc.perform(get("/sample/search/product/exists")
                        .param("id", "P001")
                        .param("brand", "Apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("1.4 主键获取单条商品详情")
    void test04_getProduct() throws Exception {
        mockMvc.perform(get("/sample/search/product/get")
                        .param("id", "P001")
                        .param("brand", "Apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.id", equalTo("P001")))
                .andExpect(jsonPath("$.data.brand", equalTo("Apple")));
    }

    @Test
    @DisplayName("1.5 批量主键获取商品列表 (MGet)")
    void test05_getProductsBatch() throws Exception {
        ProductBatchGetRequest request = ProductBatchGetRequest.builder()
                .ids(Arrays.asList("P001", "P002"))
                .brand("Apple")
                .build();

        mockMvc.perform(post("/sample/search/product/batch-get")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()));
    }

    @Test
    @DisplayName("1.6 局部更新商品库存")
    void test06_updateStock() throws Exception {
        ProductPartialUpdateRequest request = ProductPartialUpdateRequest.builder()
                .id("P001")
                .brand("Apple")
                .stock(88)
                .build();

        mockMvc.perform(post("/sample/search/product/update-stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("1.7 基于 OCC 乐观并发控制安全更新价格")
    void test07_updatePriceOcc() throws Exception {
        long seqNo = 1L;
        long primaryTerm = 1L;
        if (isLocalProfileActive()) {
            SearchHit<ProductDoc> hit = searchTemplate.getHitById(ProductDoc.class, "P001", "Apple");
            if (hit != null && hit.getSeqNo() != null && hit.getPrimaryTerm() != null) {
                seqNo = hit.getSeqNo();
                primaryTerm = hit.getPrimaryTerm();
            }
        }
        ProductOccUpdateRequest request = ProductOccUpdateRequest.builder()
                .id("P001")
                .brand("Apple")
                .price(new BigDecimal("8888.00"))
                .seqNo(seqNo)
                .primaryTerm(primaryTerm)
                .build();

        mockMvc.perform(post("/sample/search/product/update-price-occ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("1.8 关键字全文多字段高亮搜索")
    void test08_searchMultiMatch() throws Exception {
        ProductSearchRequest request = ProductSearchRequest.builder()
                .keyword("手机")
                .build();

        mockMvc.perform(post("/sample/search/product/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()))
                .andExpect(jsonPath("$.data[0].title", not(containsString("<em>"))));
    }

    @Test
    @DisplayName("1.9 搜索输入框前缀联想补全")
    void test09_searchPrefixSuggest() throws Exception {
        mockMvc.perform(get("/sample/search/product/suggest")
                        .param("prefix", "iPh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()));
    }

    @Test
    @DisplayName("1.10 Nested SKU 规格独立作用域匹配检索")
    void test10_searchNestedSkus() throws Exception {
        ProductNestedSearchRequest request = ProductNestedSearchRequest.builder()
                .color("曜石黑")
                .minPrice(new BigDecimal("5000.00"))
                .build();

        mockMvc.perform(post("/sample/search/product/nested-sku")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()));
    }

    @Test
    @DisplayName("1.11 检索结果字段折叠去重 (Collapse)")
    void test11_searchWithCollapse() throws Exception {
        ProductCollapseRequest request = ProductCollapseRequest.builder()
                .brand("Apple")
                .collapseField(isLocalProfileActive() ? "brand" : "brand.keyword")
                .build();

        mockMvc.perform(post("/sample/search/product/collapse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.records", notNullValue()));
    }

    @Test
    @DisplayName("1.12 地理空间距离周边搜索")
    void test12_searchNearbyGeo() throws Exception {
        ProductGeoSearchRequest request = ProductGeoSearchRequest.builder()
                .lat(31.2304)
                .lon(121.4737)
                .distance("10km")
                .build();

        mockMvc.perform(post("/sample/search/product/geo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()));
    }

    @Test
    @DisplayName("1.13 AI 稠密向量近邻检索 (KNN)")
    void test13_searchSimilarKnn() throws Exception {
        ProductKnnSearchRequest request = ProductKnnSearchRequest.builder()
                .vector(Arrays.asList(0.12f, 0.34f, 0.56f, 0.78f))
                .k(5)
                .build();

        mockMvc.perform(post("/sample/search/product/knn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()));
    }

    @Test
    @DisplayName("1.14 商品价格度量聚合统计")
    void test14_aggregatePriceMetrics() throws Exception {
        mockMvc.perform(get("/sample/search/product/metrics")
                        .param("category", "手机"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()));
    }

    @Test
    @DisplayName("1.15 标准浅分页检索")
    void test15_searchShallowPaging() throws Exception {
        mockMvc.perform(get("/sample/search/product/page")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.records", notNullValue()));
    }

    @Test
    @DisplayName("1.16 Search-After 深度游标分页")
    void test16_searchCursorPaging() throws Exception {
        ProductScrollQueryRequest request = ProductScrollQueryRequest.builder()
                .sortValues(Arrays.asList("P001"))
                .size(10)
                .build();

        mockMvc.perform(post("/sample/search/product/search-after")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.items", notNullValue()));
    }

    @Test
    @DisplayName("1.16.1 视图级 VO 高亮投影搜索（不污染实体内存真明文）")
    void test16_1_searchVoWithHighlight() throws Exception {
        ProductSearchRequest request = ProductSearchRequest.builder()
                .keyword("手机")
                .build();

        mockMvc.perform(post("/sample/search/product/search-vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data[0].title", not(containsString("<em>"))))
                .andExpect(jsonPath("$.data[0].highlightedTitle", containsString("<em>")));
    }

    @Test
    @DisplayName("1.16.2 _source 裁剪检索（剔除向量载荷）")
    void test16_2_searchSourceTrimmed() throws Exception {
        mockMvc.perform(get("/sample/search/product/source-trimmed")
                        .param("keyword", "手机"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data[0].id", notNullValue()));
    }

    @Test
    @DisplayName("1.16.3 Point-In-Time (PIT) 快照深度分页遍历")
    void test16_3_searchWithPitTraversal() throws Exception {
        mockMvc.perform(get("/sample/search/product/pit-pages")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data[0].id", notNullValue()));
    }

    @Test
    @DisplayName("1.16.3b Point-In-Time (PIT) 快照分页 size 非法入参 Fail-Fast 校验")
    void test16_3b_searchWithPitTraversalValidation() throws Exception {
        mockMvc.perform(get("/sample/search/product/pit-pages")
                        .param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(200)));
    }

    @Test
    @DisplayName("1.16.4 _msearch 批量异构检索")
    void test16_4_batchMultiSearch() throws Exception {
        io.github.atengk.boot.sample.search.dto.ProductMultiSearchRequest request =
                io.github.atengk.boot.sample.search.dto.ProductMultiSearchRequest.builder()
                        .keyword("手机")
                        .category("手机")
                        .build();

        mockMvc.perform(post("/sample/search/product/msearch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.items", notNullValue()))
                .andExpect(jsonPath("$.data.statTotal", notNullValue()));
    }

    @Test
    @DisplayName("1.16.5 HybridSearch 零许可证 CLIENT_RRF 混合检索")
    void test16_5_hybridSearchRrf() throws Exception {
        io.github.atengk.boot.sample.search.dto.ProductHybridSearchRequest request =
                io.github.atengk.boot.sample.search.dto.ProductHybridSearchRequest.builder()
                        .keyword("手机")
                        .vector(Arrays.asList(0.12f, 0.34f, 0.56f, 0.78f))
                        .k(5)
                        .mode(io.github.atengk.boot.search.enums.SearchHybridMode.CLIENT_RRF)
                        .build();

        mockMvc.perform(post("/sample/search/product/hybrid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data[0].id", notNullValue()));
    }

    @Test
    @DisplayName("1.16.6 商品价格 Stats/Percentiles 与类目嵌套子聚合大盘")
    void test16_6_productDashboard() throws Exception {
        mockMvc.perform(get("/sample/search/product/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.uniqueBrandCount", notNullValue()))
                .andExpect(jsonPath("$.data.priceStats.count", notNullValue()))
                .andExpect(jsonPath("$.data.pricePercentiles.values", notNullValue()))
                .andExpect(jsonPath("$.data.categoryStats[0].category", notNullValue()));
    }

    @Test
    @DisplayName("1.16.7 商品标题 Term/Phrase 拼写纠错建议 (Suggester)")
    void test16_7_productSuggest() throws Exception {
        mockMvc.perform(get("/sample/search/product/spell-suggest")
                        .param("text", "iphon")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.title_term", notNullValue()));
    }

    @Test
    @DisplayName("1.17 主键物理删除商品")
    void test17_deleteProduct() throws Exception {
        mockMvc.perform(delete("/sample/search/product/delete")
                        .param("id", "P001")
                        .param("brand", "Apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    // =========================================================================
    // 2. LogAnalyticsController 测试
    // =========================================================================

    @Test
    @DisplayName("2.1 写入审计访问日志 (SpEL 时序分表)")
    void test20_recordAccessLog() throws Exception {
        AccessLogRecordRequest request = AccessLogRecordRequest.builder()
                .id("LOG001")
                .path("/api/v1/goods/detail")
                .clientIp("192.168.1.100")
                .timestamp(LocalDateTime.now())
                .build();

        mockMvc.perform(post("/sample/search/log/record")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo("LOG001")));

        if (isLocalProfileActive()) {
            searchTemplate.indices().refresh("access_log_*");
        }
    }

    @Test
    @DisplayName("2.2 路径访问量轻量 Count 统计")
    void test21_countLogsByPath() throws Exception {
        if (isLocalProfileActive()) {
            searchTemplate.indices().refresh("access_log_*");
        }
        mockMvc.perform(get("/sample/search/log/count")
                        .param("path", "/api/v1/goods/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(isLocalProfileActive() ? jsonPath("$.data", notNullValue()) : jsonPath("$.data", equalTo("42")));
    }

    @Test
    @DisplayName("2.3 DateHistogram 时序直方图聚合走势")
    void test22_aggregateTrafficTrend() throws Exception {
        if (isLocalProfileActive()) {
            searchTemplate.indices().refresh("access_log_*");
        }
        mockMvc.perform(get("/sample/search/log/trend")
                        .param("index", "access_log_*")
                        .param("interval", "1d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()))
                .andExpect(jsonPath("$.data[0].bucket", notNullValue()));
    }

    @Test
    @DisplayName("2.4 DeleteByQuery 历史日志批量清理 (带租户标头)")
    void test23_purgeHistoricalLogs() throws Exception {
        AccessLogPurgeRequest request = AccessLogPurgeRequest.builder()
                .cutoffTime(LocalDateTime.now().minusDays(30))
                .build();

        mockMvc.perform(post("/sample/search/log/purge")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(isLocalProfileActive() ? jsonPath("$.data", notNullValue()) : jsonPath("$.data", equalTo("10")));
    }

    @Test
    @DisplayName("2.5 参数化 UpdateByQuery (WAIT_UNTIL) 批量订正日志客户端 IP")
    void test24_markLogsClientIp() throws Exception {
        AccessLogMarkIpRequest request = AccessLogMarkIpRequest.builder()
                .path("/api/v1/goods/detail")
                .clientIp("10.20.30.40")
                .build();

        mockMvc.perform(post("/sample/search/log/mark-ip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()));
    }

    @Test
    @DisplayName("2.6 日志多维分析大盘 (Cardinality + 按日独立 IP 子聚合)")
    void test25_analyzeAccessLogDashboard() throws Exception {
        mockMvc.perform(get("/sample/search/log/dashboard")
                        .param("index", "access_log_*"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.uniqueIpCount", notNullValue()))
                .andExpect(jsonPath("$.data.dailyBuckets", notNullValue()));
    }

    // =========================================================================
    // 3. BulkIngestionController 测试
    // =========================================================================

    @Test
    @DisplayName("3.1 严格模式批量写入 (Fail-Fast)")
    void test30_ingestStrict() throws Exception {
        BulkIngestRequest request = BulkIngestRequest.builder()
                .items(List.of(
                        ProductSaveRequest.builder().id("P101").title("商品1").brand("Apple").price(new BigDecimal("100")).build(),
                        ProductSaveRequest.builder().id("P102").title("商品2").brand("Apple").price(new BigDecimal("200")).build()
                ))
                .build();

        mockMvc.perform(post("/sample/search/bulk/strict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.successfulCount", equalTo("2")))
                .andExpect(jsonPath("$.data.hasFailures", equalTo(false)));
    }

    @Test
    @DisplayName("3.2 宽容模式批量写入")
    void test31_ingestLenient() throws Exception {
        BulkIngestRequest request = BulkIngestRequest.builder()
                .items(List.of(
                        ProductSaveRequest.builder().id("P101").title("商品1").brand("Apple").price(new BigDecimal("100")).build(),
                        ProductSaveRequest.builder().id("P102").title("商品2").brand("Apple").price(new BigDecimal("200")).build()
                ))
                .build();

        mockMvc.perform(post("/sample/search/bulk/lenient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.successfulCount", equalTo("2")));
    }

    @Test
    @DisplayName("3.3 异步流式批量缓冲投递")
    void test32_streamAdd() throws Exception {
        ProductSaveRequest request = ProductSaveRequest.builder()
                .id("P103")
                .title("流式商品")
                .brand("Apple")
                .price(new BigDecimal("300"))
                .build();

        mockMvc.perform(post("/sample/search/bulk/stream-add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("3.4 显式排空流式缓冲区")
    void test33_streamFlush() throws Exception {
        mockMvc.perform(post("/sample/search/bulk/stream-flush"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    // =========================================================================
    // 4. IndexLifecycleController 测试
    // =========================================================================

    @Test
    @DisplayName("4.1 索引结构 DDL 创建")
    void test40_createIndex() throws Exception {
        if (isLocalProfileActive() && searchTemplate.indices().exists("order_idx")) {
            searchTemplate.indices().delete("order_idx");
        }
        IndexCreateRequest request = IndexCreateRequest.builder()
                .indexName("order_idx")
                .mappingJson("{\"mappings\":{\"properties\":{\"orderNo\":{\"type\":\"keyword\"}}}}")
                .build();

        mockMvc.perform(post("/sample/search/index/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("4.2 创建或应用时序索引模板")
    void test41_applyIndexTemplate() throws Exception {
        IndexTemplateRequest request = IndexTemplateRequest.builder()
                .templateName("sample_log_template")
                .templateJson("{\"index_patterns\":[\"access_log_*\"],\"template\":{\"mappings\":{\"properties\":{\"path\":{\"type\":\"keyword\"}}}}}")
                .build();

        mockMvc.perform(post("/sample/search/index/template/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("4.3 删除索引模板")
    void test42_deleteIndexTemplate() throws Exception {
        mockMvc.perform(delete("/sample/search/index/template/delete")
                        .param("templateName", "sample_log_template"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("4.4 服务端数据重索引 Reindex")
    void test43_reindex() throws Exception {
        if (isLocalProfileActive()) {
            if (!searchTemplate.indices().exists("product_v1")) {
                searchTemplate.indices().create("product_v1");
            }
            if (!searchTemplate.indices().exists("product_v2")) {
                searchTemplate.indices().create("product_v2");
            }
        }
        ReindexRequest request = ReindexRequest.builder()
                .sourceIndex("product_v1")
                .targetIndex("product_v2")
                .build();

        mockMvc.perform(post("/sample/search/index/reindex")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("4.5 显式物理段刷新 (Refresh)")
    void test44_refreshIndex() throws Exception {
        if (isLocalProfileActive() && !searchTemplate.indices().exists("product_v2")) {
            searchTemplate.indices().create("product_v2");
        }
        mockMvc.perform(post("/sample/search/index/refresh")
                        .param("indexName", "product_v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("4.6 生产原子别名热切换 (swapAlias)")
    void test45_swapAlias() throws Exception {
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
        AliasSwapRequest request = AliasSwapRequest.builder()
                .aliasName("product_alias")
                .oldIndex("product_v1")
                .newIndex("product_v2")
                .build();

        mockMvc.perform(post("/sample/search/index/swap-alias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("4.7 组件模板 ComponentTemplate 创建、存在性检查与对称删除")
    void test46_componentTemplateLifecycle() throws Exception {
        ComponentTemplateRequest request = ComponentTemplateRequest.builder()
                .templateName("sample_comp_tpl")
                .templateJson("{\"template\":{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}}}")
                .build();

        mockMvc.perform(post("/sample/search/index/component-template/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));

        mockMvc.perform(get("/sample/search/index/component-template/exists")
                        .param("templateName", "sample_comp_tpl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));

        mockMvc.perform(delete("/sample/search/index/component-template/delete")
                        .param("templateName", "sample_comp_tpl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("4.8 运行期 Settings 获取/动态调优与 Alias 增查删精细治理")
    void test47_settingsAndAliasLifecycle() throws Exception {
        IndexSettingsUpdateRequest settingsReq = IndexSettingsUpdateRequest.builder()
                .indexName("product_v2")
                .settingsJson("{\"index\":{\"refresh_interval\":\"5s\"}}")
                .build();

        mockMvc.perform(put("/sample/search/index/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(settingsReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));

        mockMvc.perform(get("/sample/search/index/settings")
                        .param("indexName", "product_v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.refreshInterval", equalTo("5s")));

        IndexAliasManageRequest addAliasReq = IndexAliasManageRequest.builder()
                .indexName("product_v2")
                .aliasName("product_alias")
                .writeIndex(true)
                .build();

        mockMvc.perform(post("/sample/search/index/alias/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(addAliasReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));

        mockMvc.perform(get("/sample/search/index/alias/list")
                        .param("indexName", "product_v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()));

        mockMvc.perform(delete("/sample/search/index/alias/remove")
                        .param("indexName", "product_v2")
                        .param("aliasName", "product_alias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("4.9 异步 Reindex 提交与 Task 状态追踪/取消治理")
    void test48_asyncReindexAndTaskGovernance() throws Exception {
        ReindexRequest request = ReindexRequest.builder()
                .sourceIndex("product_v1")
                .targetIndex("product_v2")
                .build();

        String responseBody = mockMvc.perform(post("/sample/search/index/reindex-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String taskId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(responseBody).path("data").asText();

        mockMvc.perform(get("/sample/search/index/task")
                        .param("taskId", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.taskId", equalTo(taskId)));

        mockMvc.perform(post("/sample/search/index/task/cancel")
                        .param("taskId", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(isLocalProfileActive() ? jsonPath("$.data", notNullValue()) : jsonPath("$.data", equalTo(true)));
    }

    @org.junit.jupiter.api.AfterEach
    void cleanupThreadTenantContext() {
        io.github.atengk.boot.core.context.TenantContextHolder.clear();
    }

    @Test
    @DisplayName("5.1 X-Tenant-Id 请求头透传、读写多租户隔离、防跨租户 IDOR 阻断与线程零残留")
    void test50_multiTenantHeaderIsolationAndAntiIdor() throws Exception {
        String tenantProductId = "P_TENANT_WEB_A";
        String tenantLogId = "LOG_TENANT_WEB_A";
        // 注意 MockSearchServer 按 P_TENANT_/LOG_TENANT_ 前缀识别，上述 ID 已匹配前缀

        // 1. 租户 A 通过 HTTP 头写入商品（DTO 不传 tenantId）并校验自动注入与读回
        ProductSaveRequest saveReq = ProductSaveRequest.builder()
                .id(tenantProductId)
                .title("租户A专享手机")
                .brand("Apple")
                .category("手机")
                .price(new BigDecimal("6999.00"))
                .stock(20)
                .status("ON_SALE")
                .build();

        mockMvc.perform(post("/sample/search/product/save")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(saveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(tenantProductId)));
        org.assertj.core.api.Assertions.assertThat(io.github.atengk.boot.core.context.TenantContextHolder.getTenantId()).isNull();

        if (isLocalProfileActive()) {
            searchTemplate.indices().refresh("product_index");
        }

        // 2. 租户 A 写入审计日志（AccessLogDoc tenantId 自动注入）
        AccessLogRecordRequest logReq = AccessLogRecordRequest.builder()
                .id(tenantLogId)
                .path("/api/v1/tenant-a/orders")
                .clientIp("10.10.1.8")
                .timestamp(LocalDateTime.now())
                .build();
        mockMvc.perform(post("/sample/search/log/record")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(logReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(tenantLogId)));
        org.assertj.core.api.Assertions.assertThat(io.github.atengk.boot.core.context.TenantContextHolder.getTenantId()).isNull();

        if (isLocalProfileActive()) {
            searchTemplate.indices().refresh("access_log_*");
        }

        // 3. 租户 A 同租户点查命中且携带注入的 tenantId
        mockMvc.perform(get("/sample/search/product/get")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .param("id", tenantProductId)
                        .param("brand", "Apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.id", equalTo(tenantProductId)))
                .andExpect(jsonPath("$.data.tenantId", equalTo("tenant_corp_a")));
        org.assertj.core.api.Assertions.assertThat(io.github.atengk.boot.core.context.TenantContextHolder.getTenantId()).isNull();

        // 4. 租户 B 跨租户点查返回 null，exists 返回 false
        mockMvc.perform(get("/sample/search/product/get")
                        .header("X-Tenant-Id", "tenant_corp_b")
                        .param("id", tenantProductId)
                        .param("brand", "Apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(get("/sample/search/product/exists")
                        .header("X-Tenant-Id", "tenant_corp_b")
                        .param("id", tenantProductId)
                        .param("brand", "Apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(false)));

        // 5. 租户 B 尝试局部更新或物理删除租户 A 商品 -> 触发 60017 SEARCH_TENANT_ACCESS_DENIED 拦截
        ProductPartialUpdateRequest tamperStockReq = ProductPartialUpdateRequest.builder()
                .id(tenantProductId)
                .brand("Apple")
                .stock(1)
                .build();
        mockMvc.perform(post("/sample/search/product/update-stock")
                        .header("X-Tenant-Id", "tenant_corp_b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(tamperStockReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(60017)));
        org.assertj.core.api.Assertions.assertThat(io.github.atengk.boot.core.context.TenantContextHolder.getTenantId()).isNull();

        mockMvc.perform(delete("/sample/search/product/delete")
                        .header("X-Tenant-Id", "tenant_corp_b")
                        .param("id", tenantProductId)
                        .param("brand", "Apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(60017)));
        org.assertj.core.api.Assertions.assertThat(io.github.atengk.boot.core.context.TenantContextHolder.getTenantId()).isNull();

        // 6. AccessLogDoc 多租户统计与 deleteByQuery 条件清理闭环（租户 B 无法计数或误删，租户 A 正常清理）
        mockMvc.perform(get("/sample/search/log/count")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .param("path", "/api/v1/tenant-a/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", not(equalTo("0"))));

        mockMvc.perform(get("/sample/search/log/count")
                        .header("X-Tenant-Id", "tenant_corp_b")
                        .param("path", "/api/v1/tenant-a/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo("0")));

        AccessLogPurgeRequest purgeReq = AccessLogPurgeRequest.builder()
                .cutoffTime(LocalDateTime.now().plusMinutes(5))
                .build();
        mockMvc.perform(post("/sample/search/log/purge")
                        .header("X-Tenant-Id", "tenant_corp_b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(purgeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo("0")));

        mockMvc.perform(post("/sample/search/log/purge")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(purgeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", not(equalTo("0"))));

        if (isLocalProfileActive()) {
            searchTemplate.indices().refresh("access_log_*");
        }

        mockMvc.perform(get("/sample/search/log/count")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .param("path", "/api/v1/tenant-a/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo("0")));

        // 7. 租户 A 清理自身商品测试数据，保证双模沙箱洁净
        mockMvc.perform(delete("/sample/search/product/delete")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .param("id", tenantProductId)
                        .param("brand", "Apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
        org.assertj.core.api.Assertions.assertThat(io.github.atengk.boot.core.context.TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("5.2 流式带路由批量删除、混合租户隔离及自定义 BulkDeadLetterHandler 死信统计端点闭环")
    void test51_streamDeleteRoutingAndDeadLetterStats() throws Exception {
        String bulkProductId = "P_BULK_TENANT_WEB_A";

        // 1. 重置死信统计
        mockMvc.perform(delete("/sample/search/bulk/dead-letter/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));

        // 2. 租户 A 通过 stream-add + stream-flush 写入带路由商品并校验可读回
        ProductSaveRequest streamReq = ProductSaveRequest.builder()
                .id(bulkProductId)
                .title("流式多租户专享商品")
                .brand("Apple")
                .category("手机")
                .price(new BigDecimal("4999.00"))
                .stock(30)
                .status("ON_SALE")
                .build();
        mockMvc.perform(post("/sample/search/bulk/stream-add")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(streamReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));

        mockMvc.perform(post("/sample/search/bulk/stream-flush"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)));

        if (isLocalProfileActive()) {
            searchTemplate.indices().refresh("product_index");
        }

        mockMvc.perform(get("/sample/search/product/get")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .param("id", bulkProductId)
                        .param("brand", "Apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", equalTo(bulkProductId)))
                .andExpect(jsonPath("$.data.tenantId", equalTo("tenant_corp_a")));

        // 3. 租户 B 尝试通过带路由 stream-delete 越权删租户 A 文档 -> flush 触发 STRICT 拦截并记入整批死信统计
        BulkStreamDeleteRequest deleteReq = BulkStreamDeleteRequest.builder()
                .id(bulkProductId)
                .routing("Apple")
                .build();
        mockMvc.perform(post("/sample/search/bulk/stream-delete")
                        .header("X-Tenant-Id", "tenant_corp_b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(deleteReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", equalTo(true)));

        mockMvc.perform(post("/sample/search/bulk/stream-flush"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/sample/search/product/get")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .param("id", bulkProductId)
                        .param("brand", "Apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", equalTo(bulkProductId)));

        mockMvc.perform(get("/sample/search/bulk/dead-letter/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.batchFailureCount", not(equalTo("0"))));

        // 4. 租户 A 使用对称 stream-delete 正常清理，随后验证已彻底删除
        mockMvc.perform(post("/sample/search/bulk/stream-delete")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(deleteReq)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/sample/search/bulk/stream-flush"))
                .andExpect(status().isOk());

        if (isLocalProfileActive()) {
            searchTemplate.indices().refresh("product_index");
        }

        mockMvc.perform(get("/sample/search/product/get")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .param("id", bulkProductId)
                        .param("brand", "Apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        // 5. 触发局部映射异常写入并校验自定义 SPI 记录 itemFailureCount 与失败摘要
        BulkRawIngestRequest badDocReq = BulkRawIngestRequest.builder()
                .id("DLQ_BAD_DOC")
                .routing("Apple")
                .fields(java.util.Map.of("id", "DLQ_BAD_DOC", "brand", "Apple", "price", "invalid-number"))
                .build();
        mockMvc.perform(post("/sample/search/bulk/stream-raw")
                        .header("X-Tenant-Id", "tenant_corp_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(badDocReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", equalTo(true)));

        mockMvc.perform(post("/sample/search/bulk/stream-flush"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/sample/search/bulk/dead-letter/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.itemFailureCount", not(equalTo("0"))))
                .andExpect(jsonPath("$.data.recentMessages[0]", containsString("DLQ_BAD_DOC")));

        // 6. 重置统计与线程上下文清理
        mockMvc.perform(delete("/sample/search/bulk/dead-letter/stats"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(io.github.atengk.boot.core.context.TenantContextHolder.getTenantId()).isNull();
    }
}
