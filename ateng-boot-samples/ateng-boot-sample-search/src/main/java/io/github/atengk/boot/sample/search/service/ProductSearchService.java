package io.github.atengk.boot.sample.search.service;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.sample.search.model.AccessLogDoc;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 电商商品搜索场景业务服务组件
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final SearchTemplate searchTemplate;
    private final SearchBulkProcessor searchBulkProcessor;

    // 1. 文档新增与全量保存
    public void saveProduct(ProductDoc product) {
        searchTemplate.save(product);
    }

    // 2. 主键获取
    public ProductDoc getProduct(String id, String routing) {
        return searchTemplate.getById(ProductDoc.class, id, routing);
    }

    // 3. 文档删除
    public void deleteProduct(String id, String routing) {
        searchTemplate.deleteById(ProductDoc.class, id, routing);
    }

    // 4. 局部更新 (Partial Update)
    public void updateStockPartial(String id, String routing, Integer newStock) {
        searchTemplate.update(ProductDoc.class, id, routing, Map.of("stock", newStock));
    }

    // 5. 乐观并发控制 (OCC)
    public void updatePriceWithOcc(String id, String routing, BigDecimal newPrice, OccVersion version) {
        searchTemplate.update(ProductDoc.class, id, routing, Map.of("price", newPrice), version);
    }

    // 6. 严格批量写入
    public SearchBulkResult bulkSaveStrict(List<ProductDoc> products) {
        return searchTemplate.indexBatch(products);
    }

    // 7. 宽容批量写入
    public SearchBulkResult bulkSaveLenient(List<ProductDoc> products) {
        return searchTemplate.indexBatchLenient(products);
    }

    // 8. 异步流式 BulkProcessor 写入
    public void streamIndexProducts(List<ProductDoc> products) {
        searchBulkProcessor.addAll(products);
        searchBulkProcessor.flush();
    }

    // 9. 关键字全文检索与 @HighlightField 高亮回填
    public List<SearchHit<ProductDoc>> searchByKeyword(String keyword) {
        SearchQuery query = SearchQueryBuilder.create()
                .multiMatch(keyword, "title", "description")
                .highlight("title", "description")
                .build();
        SearchPage<ProductDoc> page = searchTemplate.search(query, ProductDoc.class);
        return page.getHits();
    }

    // 10. 复合布尔筛选、价格区间过滤与品牌聚合
    public SearchPage<ProductDoc> filterByPriceAndBrand(BigDecimal minPrice, BigDecimal maxPrice, String brand) {
        SearchQuery query = SearchQueryBuilder.create()
                .term("brand", brand)
                .range("price", minPrice, maxPrice)
                .termsAggregation("brand_agg", "brand", 10)
                .avgAggregation("avg_price", "price")
                .orderByDesc("price")
                .build();
        return searchTemplate.search(query, ProductDoc.class);
    }

    // 11. Geo 空间计算（周边周边公里商品）
    public List<SearchHit<ProductDoc>> searchNearby(double lat, double lon, String distance) {
        SearchQuery query = SearchQueryBuilder.create()
                .geoDistance("location", lat, lon, distance)
                .orderByGeoDistance("location", lat, lon)
                .build();
        return searchTemplate.search(query, ProductDoc.class).getHits();
    }

    // 12. KNN 向量检索
    public List<SearchHit<ProductDoc>> searchSimilarKnn(List<Float> vector, int k) {
        SearchQuery query = SearchQueryBuilder.create()
                .knn("embedding", vector, k, 100)
                .build();
        return searchTemplate.search(query, ProductDoc.class).getHits();
    }

    // 13. 标准浅分页
    public PageResult<ProductDoc> searchPagingShallow(int page, int size) {
        SearchQuery query = SearchQueryBuilder.create()
                .matchAll()
                .from((page - 1) * size)
                .size(size)
                .orderByDesc("createTime")
                .build();
        return searchTemplate.search(query, ProductDoc.class).toPageResult();
    }

    // 14. 深度游标滚动分页 (Search-After)
    public SearchScrollResult<ProductDoc> searchPagingCursor(Object[] sortValues, int size) {
        SearchQuery query = SearchQueryBuilder.create()
                .matchAll()
                .orderByAsc("id")
                .build();
        return searchTemplate.searchAfter(query, sortValues, size, ProductDoc.class);
    }

    // 15. 时序 SpEL 动态分表
    public void saveAccessLog(AccessLogDoc logDoc) {
        searchTemplate.save(logDoc);
    }

    // 16. 原子别名热切换
    public void swapAlias(String alias, String oldIndex, String newIndex) {
        searchTemplate.indices().swapAlias(alias, oldIndex, newIndex);
    }
}
