package io.github.atengk.boot.sample.search.controller;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.search.dto.ProductBatchGetRequest;
import io.github.atengk.boot.sample.search.dto.ProductCollapseRequest;
import io.github.atengk.boot.sample.search.dto.ProductDashboardResponse;
import io.github.atengk.boot.sample.search.dto.ProductGeoSearchRequest;
import io.github.atengk.boot.sample.search.dto.ProductHybridSearchRequest;
import io.github.atengk.boot.sample.search.dto.ProductKnnSearchRequest;
import io.github.atengk.boot.sample.search.dto.ProductMultiSearchRequest;
import io.github.atengk.boot.sample.search.dto.ProductMultiSearchResponse;
import io.github.atengk.boot.sample.search.dto.ProductNestedSearchRequest;
import io.github.atengk.boot.sample.search.dto.ProductOccUpdateRequest;
import io.github.atengk.boot.sample.search.dto.ProductPartialUpdateRequest;
import io.github.atengk.boot.sample.search.dto.ProductResponse;
import io.github.atengk.boot.sample.search.dto.ProductSaveRequest;
import io.github.atengk.boot.sample.search.dto.ProductScrollQueryRequest;
import io.github.atengk.boot.sample.search.dto.ProductScrollResponse;
import io.github.atengk.boot.sample.search.dto.ProductSearchRequest;
import io.github.atengk.boot.sample.search.dto.SearchSampleConvertHelper;
import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.sample.search.scenario.ProductCatalogSearchScenario;
import io.github.atengk.boot.search.model.OccVersion;
import io.github.atengk.boot.search.model.SearchHit;
import io.github.atengk.boot.search.model.SearchPage;
import io.github.atengk.boot.search.model.SearchScrollResult;
import io.github.atengk.boot.search.model.SearchSuggestResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 电商商品全文检索与高阶搜索控制器
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/search/product")
@RequiredArgsConstructor
public class ProductSearchController {

    private final ProductCatalogSearchScenario productScenario;

    /**
     * 保存或覆盖商品文档
     */
    @PostMapping("/save")
    public Result<String> saveProduct(@RequestBody @Valid ProductSaveRequest request) {
        ProductDoc doc = SearchSampleConvertHelper.toDoc(request);
        productScenario.saveProduct(doc);
        return Result.success(doc.getId());
    }

    /**
     * 探测商品是否存在（HEAD 轻量级零报文体探测）
     */
    @GetMapping("/exists")
    public Result<Boolean> existsProduct(@RequestParam String id,
                                         @RequestParam(required = false) String brand) {
        boolean exists = productScenario.existsProduct(id, brand);
        return Result.success(exists);
    }

    /**
     * 主键获取单条商品详情
     */
    @GetMapping("/get")
    public Result<ProductResponse> getProduct(@RequestParam String id,
                                              @RequestParam(required = false) String brand) {
        ProductDoc doc = productScenario.getProduct(id, brand);
        return Result.success(SearchSampleConvertHelper.toResponse(doc));
    }

    /**
     * 批量主键获取商品列表 (MGet)
     */
    @PostMapping("/batch-get")
    public Result<List<ProductResponse>> getProductsBatch(@RequestBody @Valid ProductBatchGetRequest request) {
        List<ProductDoc> docs = productScenario.getProductsBatch(request.getIds(), request.getBrand());
        return Result.success(SearchSampleConvertHelper.toResponseList(docs));
    }

    /**
     * 主键物理删除商品
     */
    @DeleteMapping("/delete")
    public Result<Boolean> deleteProduct(@RequestParam String id,
                                         @RequestParam(required = false) String brand) {
        productScenario.deleteProduct(id, brand);
        return Result.success(true);
    }

    /**
     * 局部更新商品库存 (Partial Update)
     */
    @PostMapping("/update-stock")
    public Result<Boolean> updateStock(@RequestBody @Valid ProductPartialUpdateRequest request) {
        productScenario.updateStockPartial(request.getId(), request.getBrand(), request.getStock());
        return Result.success(true);
    }

    /**
     * 基于 OCC 乐观并发控制安全更新价格
     */
    @PostMapping("/update-price-occ")
    public Result<Boolean> updatePriceWithOcc(@RequestBody @Valid ProductOccUpdateRequest request) {
        OccVersion version = new OccVersion(request.getSeqNo(), request.getPrimaryTerm());
        productScenario.updatePriceWithOcc(request.getId(), request.getBrand(), request.getPrice(), version);
        return Result.success(true);
    }

    /**
     * 关键字全文多字段高亮搜索
     */
    @PostMapping("/search")
    public Result<List<ProductResponse>> searchMultiMatch(@RequestBody @Valid ProductSearchRequest request) {
        List<SearchHit<ProductDoc>> hits = productScenario.searchMultiMatchWithHighlight(request.getKeyword());
        return Result.success(SearchSampleConvertHelper.toHitResponseList(hits));
    }

    /**
     * 搜索输入框打字前缀联想补全
     */
    @GetMapping("/suggest")
    public Result<List<ProductResponse>> searchPrefixSuggest(@RequestParam String prefix) {
        List<SearchHit<ProductDoc>> hits = productScenario.searchPrefixSuggestion(prefix);
        return Result.success(SearchSampleConvertHelper.toHitResponseList(hits));
    }

    /**
     * Nested 嵌套 SKU 规格独立作用域匹配检索
     */
    @PostMapping("/nested-sku")
    public Result<List<ProductResponse>> searchNestedSkus(@RequestBody @Valid ProductNestedSearchRequest request) {
        List<SearchHit<ProductDoc>> hits = productScenario.searchNestedSkus(request.getColor(), request.getMinPrice());
        return Result.success(SearchSampleConvertHelper.toHitResponseList(hits));
    }

    /**
     * 检索结果字段折叠去重 (Field Collapsing)
     */
    @PostMapping("/collapse")
    public Result<PageResult<ProductResponse>> searchWithCollapse(@RequestBody(required = false) ProductCollapseRequest request) {
        String brand = request != null ? request.getBrand() : null;
        String collapseField = request != null && request.getCollapseField() != null ? request.getCollapseField() : "brand.keyword";
        SearchPage<ProductDoc> page = productScenario.searchWithCollapse(brand, collapseField);
        return Result.success(page.toPageResult().map(SearchSampleConvertHelper::toResponse));
    }

    /**
     * 地理空间距离周边搜索
     */
    @PostMapping("/geo")
    public Result<List<ProductResponse>> searchNearbyGeo(@RequestBody @Valid ProductGeoSearchRequest request) {
        List<SearchHit<ProductDoc>> hits = productScenario.searchNearbyGeo(request.getLat(), request.getLon(), request.getDistance());
        return Result.success(SearchSampleConvertHelper.toHitResponseList(hits));
    }

    /**
     * AI 稠密向量近邻检索 (KNN)
     */
    @PostMapping("/knn")
    public Result<List<ProductResponse>> searchSimilarKnn(@RequestBody @Valid ProductKnnSearchRequest request) {
        int k = request.getK() != null ? request.getK() : 5;
        List<SearchHit<ProductDoc>> hits = productScenario.searchSimilarKnn(request.getVector(), k);
        return Result.success(SearchSampleConvertHelper.toHitResponseList(hits));
    }

    /**
     * 商品价格极值与平均度量聚合统计
     */
    @GetMapping("/metrics")
    public Result<Map<String, Double>> aggregatePriceMetrics(@RequestParam(required = false) String category) {
        SearchPage<ProductDoc> page = productScenario.aggregatePriceMetrics(category);
        return Result.success(SearchSampleConvertHelper.toMetricValues(page));
    }

    /**
     * 商品价格统计、分位数 (P50/P95/P99) 与类目嵌套子聚合多维大盘
     */
    @GetMapping("/dashboard")
    public Result<ProductDashboardResponse> analyzePriceDashboard() {
        SearchPage<ProductDoc> page = productScenario.analyzePriceDashboard();
        return Result.success(SearchSampleConvertHelper.toProductDashboard(page));
    }

    /**
     * 商品标题拼写纠错建议与短语推荐 (Term + Phrase Suggester)
     */
    @GetMapping("/spell-suggest")
    public Result<Map<String, List<SearchSuggestResult>>> suggestProductTitles(
            @RequestParam @NotBlank String text,
            @RequestParam(defaultValue = "5") @Min(1) int size) {
        SearchPage<ProductDoc> page = productScenario.suggestProductTitles(text, size);
        return Result.success(page.getSuggestions());
    }

    /**
     * 标准浅分页检索
     */
    @GetMapping("/page")
    public Result<PageResult<ProductResponse>> searchShallowPaging(@RequestParam(defaultValue = "1") int page,
                                                                   @RequestParam(defaultValue = "10") int size) {
        PageResult<ProductDoc> pageResult = productScenario.searchShallowPaging(page, size);
        return Result.success(pageResult.map(SearchSampleConvertHelper::toResponse));
    }

    /**
     * Search-After 深度游标分页
     */
    @PostMapping("/search-after")
    public Result<ProductScrollResponse> searchCursorPaging(@RequestBody @Valid ProductScrollQueryRequest request) {
        Object[] sortValues = request.getSortValues() != null ? request.getSortValues().toArray() : null;
        int size = request.getSize() != null ? request.getSize() : 10;
        SearchScrollResult<ProductDoc> scrollResult = productScenario.searchCursorPaging(sortValues, size);
        List<ProductResponse> items = SearchSampleConvertHelper.toHitResponseList(scrollResult.getHits());
        List<Object> nextSortValues = scrollResult.getNextSortValues() != null
                ? Arrays.asList(scrollResult.getNextSortValues())
                : Collections.emptyList();

        ProductScrollResponse response = ProductScrollResponse.builder()
                .items(items)
                .nextSortValues(nextSortValues)
                .hasMore(scrollResult.isHasMore())
                .build();
        return Result.success(response);
    }

    /**
     * 视图级 VO 高亮投影搜索（不污染持久实体内存明文）
     */
    @PostMapping("/search-vo")
    public Result<List<ProductResponse>> searchVoWithHighlight(@RequestBody @Valid ProductSearchRequest request) {
        return Result.success(productScenario.searchMultiMatchVoWithHighlight(request.getKeyword()));
    }

    /**
     * _source 裁剪检索（剔除 embedding 向量载荷以节约带宽）
     */
    @GetMapping("/source-trimmed")
    public Result<List<ProductResponse>> searchSourceTrimmed(@RequestParam(required = false) String keyword) {
        List<SearchHit<ProductDoc>> hits = productScenario.searchWithSourceTrimming(keyword);
        return Result.success(SearchSampleConvertHelper.toHitResponseList(hits));
    }

    /**
     * Point-In-Time (PIT) 一致性快照深度分页全量遍历
     */
    @GetMapping("/pit-pages")
    public Result<List<ProductResponse>> searchWithPitTraversal(@RequestParam(defaultValue = "2") @Min(1) int size) {
        List<SearchHit<ProductDoc>> hits = productScenario.searchWithPitTraversal(size);
        return Result.success(SearchSampleConvertHelper.toHitResponseList(hits));
    }

    /**
     * _msearch 批量异构检索（单次往返返回关键词命中列表与分类汇总指标）
     */
    @PostMapping("/msearch")
    public Result<ProductMultiSearchResponse> batchMultiSearch(@RequestBody(required = false) ProductMultiSearchRequest request) {
        String keyword = request != null ? request.getKeyword() : null;
        String category = request != null ? request.getCategory() : null;
        List<SearchPage<ProductDoc>> pages = productScenario.batchMultiSearch(keyword, category);
        List<ProductResponse> items = !pages.isEmpty()
                ? SearchSampleConvertHelper.toHitResponseList(pages.get(0).getHits())
                : Collections.emptyList();
        long statTotal = pages.size() > 1 ? pages.get(1).getTotal() : 0L;
        return Result.success(ProductMultiSearchResponse.builder()
                .items(items)
                .statTotal(statTotal)
                .build());
    }

    /**
     * AI 混合检索融合 (BM25 + kNN，默认 CLIENT_RRF 零许可证模式)
     */
    @PostMapping("/hybrid")
    public Result<List<ProductResponse>> hybridSearch(@RequestBody @Valid ProductHybridSearchRequest request) {
        int k = request.getK() != null ? request.getK() : 5;
        SearchPage<ProductDoc> page = productScenario.hybridSearch(
                request.getKeyword(), request.getVector(), k, request.getMode());
        return Result.success(SearchSampleConvertHelper.toHitResponseList(page.getHits()));
    }
}
