package io.github.atengk.boot.sample.search;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.search.dto.AccessLogPurgeRequest;
import io.github.atengk.boot.sample.search.dto.AccessLogRecordRequest;
import io.github.atengk.boot.sample.search.dto.AliasSwapRequest;
import io.github.atengk.boot.sample.search.dto.BulkIngestRequest;
import io.github.atengk.boot.sample.search.dto.IndexCreateRequest;
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
import io.github.atengk.boot.sample.search.support.MockSearchServerInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全文检索样例工程 4 大领域控制器 Web 端到端自动化回归测试
 * <p>
 * 基于 Spring MockMvc 配合 100% 内存自洽 MockSearchServer，全覆盖商品检索、日志分析、批量写入与索引生命周期控制器。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@SpringBootTest(classes = SampleSearchApplication.class)
@AutoConfigureMockMvc
@ContextConfiguration(initializers = MockSearchServerInitializer.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
class SampleSearchWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
        ProductOccUpdateRequest request = ProductOccUpdateRequest.builder()
                .id("P001")
                .brand("Apple")
                .price(new BigDecimal("8888.00"))
                .seqNo(1L)
                .primaryTerm(1L)
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
                .andExpect(jsonPath("$.data", notNullValue()));
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
                .collapseField("brand.keyword")
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
    }

    @Test
    @DisplayName("2.2 路径访问量轻量 Count 统计")
    void test21_countLogsByPath() throws Exception {
        mockMvc.perform(get("/sample/search/log/count")
                        .param("path", "/api/v1/goods/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo("42")));
    }

    @Test
    @DisplayName("2.3 DateHistogram 时序直方图聚合走势")
    void test22_aggregateTrafficTrend() throws Exception {
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
                .andExpect(jsonPath("$.data", equalTo("10")));
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
        mockMvc.perform(post("/sample/search/index/refresh")
                        .param("indexName", "product_v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
    }

    @Test
    @DisplayName("4.6 生产原子别名热切换 (swapAlias)")
    void test45_swapAlias() throws Exception {
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
}
