package io.github.atengk.boot.sample.search.scenario;

import co.elastic.clients.elasticsearch._types.query_dsl.ChildScoreMode;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.sample.search.dto.ProductResponse;
import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.search.enums.SearchHybridMode;
import io.github.atengk.boot.search.model.HybridSearchOptions;
import io.github.atengk.boot.search.model.OccVersion;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 电商商品多维检索与高阶搜索业务场景组件
 * <p>
 * 涵盖商品全量索引、轻量存在探测、批量主键拉取、多字段高亮、Nested 嵌套 SKU 检索、搜索联想补全、Collapse 字段折叠去重与深浅分页。
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCatalogSearchScenario {

    private static final int MAX_PIT_TRAVERSAL_PAGES = 20;

    private final SearchTemplate searchTemplate;

    /**
     * 保存或全量覆盖商品文档
     *
     * @param product 商品文档
     */
    public void saveProduct(ProductDoc product) {
        searchTemplate.save(product);
    }

    /**
     * 探测商品是否存在（HEAD 轻量级零文档负载探测）
     *
     * @param id      商品 ID
     * @param routing 分片路由键（若实体有 @SearchRouting 需传入）
     * @return true 表示存在
     */
    public boolean existsProduct(String id, String routing) {
        return searchTemplate.existsById(ProductDoc.class, id, routing);
    }

    /**
     * 探测商品是否存在（针对无 @SearchRouting 实体或使用默认路由）
     *
     * @param id 商品 ID
     * @return true 表示存在
     */
    public boolean existsProduct(String id) {
        return existsProduct(id, null);
    }

    /**
     * 根据主键与路由键获取单个商品
     *
     * @param id      商品 ID
     * @param routing 分片路由键（如品牌名）
     * @return 商品实体，未命中返回 null
     */
    public ProductDoc getProduct(String id, String routing) {
        return searchTemplate.getById(ProductDoc.class, id, routing);
    }

    /**
     * 根据多个商品 ID 批量检索商品列表（底层 _mget 批量端点，适用于未标注 @SearchRouting 或由默认路由分片的场景）
     *
     * @param ids 商品 ID 集合
     * @return 命中的商品列表
     */
    public List<ProductDoc> getProductsBatch(Collection<String> ids) {
        return getProductsBatch(ids, null);
    }

    /**
     * 根据多个商品 ID 与品牌分片路由批量检索商品列表（底层 _mget 批量端点）
     *
     * @param ids     商品 ID 集合
     * @param routing 分片路由键（如品牌名）
     * @return 命中的商品列表
     */
    public List<ProductDoc> getProductsBatch(Collection<String> ids, String routing) {
        return searchTemplate.getByIds(ProductDoc.class, ids, routing);
    }

    /**
     * 删除指定商品
     *
     * @param id      商品 ID
     * @param routing 分片路由键
     */
    public void deleteProduct(String id, String routing) {
        searchTemplate.deleteById(ProductDoc.class, id, routing);
    }

    /**
     * 局部更新商品库存 (Partial Update)
     *
     * @param id       商品 ID
     * @param routing  路由键
     * @param newStock 新库存数
     */
    public void updateStockPartial(String id, String routing, Integer newStock) {
        searchTemplate.update(ProductDoc.class, id, routing, Map.of("stock", newStock));
    }

    /**
     * 基于乐观并发控制 (OCC) 的安全价格更新
     *
     * @param id       商品 ID
     * @param routing  路由键
     * @param newPrice 新价格
     * @param version  期望并发版本凭据
     */
    public void updatePriceWithOcc(String id, String routing, BigDecimal newPrice, OccVersion version) {
        searchTemplate.update(ProductDoc.class, id, routing, Map.of("price", newPrice), version);
    }

    /**
     * 关键字全文多字段检索并自动回填高亮富文本
     *
     * @param keyword 搜索关键字
     * @return 命中结果列表
     */
    public List<SearchHit<ProductDoc>> searchMultiMatchWithHighlight(String keyword) {
        SearchQuery query = SearchQueryBuilder.create()
                .multiMatch(keyword, "title", "description")
                .highlight("title", "description")
                .build();
        SearchPage<ProductDoc> page = searchTemplate.search(query, ProductDoc.class);
        return page.getHits();
    }

    /**
     * 搜索框前缀即时联想补全 (Match Phrase Prefix)
     *
     * @param prefix 输入前缀词
     * @return 联想命中文档列表
     */
    public List<SearchHit<ProductDoc>> searchPrefixSuggestion(String prefix) {
        SearchQuery query = SearchQueryBuilder.create()
                .matchPhrasePrefix("title", prefix)
                .build();
        return searchTemplate.search(query, ProductDoc.class).getHits();
    }

    /**
     * Nested 嵌套 SKU 规格独立匹配检索（颜色与价格组合匹配）
     *
     * @param color    SKU 颜色
     * @param minPrice 最低价格
     * @return 命中文档列表
     */
    public List<SearchHit<ProductDoc>> searchNestedSkus(String color, BigDecimal minPrice) {
        SearchQuery query = SearchQueryBuilder.create()
                .nested("skus", ChildScoreMode.Avg, sub -> sub
                        .term("skus.color", color)
                        .gte("skus.price", minPrice))
                .build();
        return searchTemplate.search(query, ProductDoc.class).getHits();
    }

    /**
     * 检索结果字段折叠去重 (Field Collapsing)
     * <p>
     * 防止单一热门品牌或分类垄断搜索首屏，每个折叠分组仅展示相关度最高的一条。
     *
     * @param brand         指定品牌筛选（可为空）
     * @param collapseField 折叠字段名（如 "brand.keyword"）
     * @return 折叠后的搜索结果分页模型
     */
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

    /**
     * 地理空间距离周边搜索（按距离升序排列）
     *
     * @param lat      中心纬度
     * @param lon      中心经度
     * @param distance 半径范围（如 "10km"）
     * @return 周边商品列表
     */
    public List<SearchHit<ProductDoc>> searchNearbyGeo(double lat, double lon, String distance) {
        SearchQuery query = SearchQueryBuilder.create()
                .geoDistance("location", lat, lon, distance)
                .orderByGeoDistance("location", lat, lon)
                .build();
        return searchTemplate.search(query, ProductDoc.class).getHits();
    }

    /**
     * AI 稠密向量近邻检索 (KNN)
     *
     * @param vector 特征向量
     * @param k      召回数量
     * @return 相似商品列表
     */
    public List<SearchHit<ProductDoc>> searchSimilarKnn(List<Float> vector, int k) {
        SearchQuery query = SearchQueryBuilder.create()
                .knn("embedding", vector, k, 100)
                .build();
        return searchTemplate.search(query, ProductDoc.class).getHits();
    }

    /**
     * 商品价格区间统计与极值度量聚合 (Min / Max / Avg 聚合算子实测)
     *
     * @param category 商品分类筛选（可为空）
     * @return 包含聚合计算结果的搜索分页模型
     */
    public SearchPage<ProductDoc> aggregatePriceMetrics(String category) {
        SearchQueryBuilder builder = SearchQueryBuilder.create();
        if (category != null && !category.isBlank()) {
            builder.term("category", category);
        }
        SearchQuery query = builder
                .size(0)
                .minAggregation("min_price", "price")
                .maxAggregation("max_price", "price")
                .avgAggregation("avg_price", "price")
                .build();
        return searchTemplate.search(query, ProductDoc.class);
    }

    /**
     * 传统浅分页查询（自动转换为主流 PageResult 契约模型）
     *
     * @param page 页码（从 1 开始）
     * @param size 每页记录数
     * @return 强类型分页契约模型
     */
    public PageResult<ProductDoc> searchShallowPaging(int page, int size) {
        SearchQuery query = SearchQueryBuilder.create()
                .matchAll()
                .page(page, size)
                .orderByDesc("createTime")
                .build();
        return searchTemplate.search(query, ProductDoc.class).toPageResult();
    }

    /**
     * 深度游标滚动分页 (Search-After)
     *
     * @param sortValues 游标分页排序凭据值
     * @param size       每页大小
     * @return 游标滚动结果模型
     */
    public SearchScrollResult<ProductDoc> searchCursorPaging(Object[] sortValues, int size) {
        SearchQuery query = SearchQueryBuilder.create()
                .matchAll()
                .orderByAsc("id")
                .build();
        return searchTemplate.searchAfter(query, sortValues, size, ProductDoc.class);
    }

    /**
     * 视图级 @HighlightField 安全投影检索（严守 ProductDoc 内存真明文不被污染）
     *
     * @param keyword 搜索关键字
     * @return 填充了高亮字段的 ProductResponse VO 列表
     */
    public List<ProductResponse> searchMultiMatchVoWithHighlight(String keyword) {
        SearchQuery query = SearchQueryBuilder.create()
                .multiMatch(keyword, "title", "description")
                .highlight("title", "description")
                .build();
        SearchPage<ProductResponse> page = searchTemplate.search(query, ProductDoc.class, ProductResponse.class);
        return page.getHits().stream().map(hit -> {
            ProductResponse vo = hit.getContent();
            if (vo != null) {
                vo.setScore(hit.getScore());
                if (hit.getSortValues() != null) {
                    vo.setSortValues(new ArrayList<>(hit.getSortValues()));
                }
            }
            return vo;
        }).filter(Objects::nonNull).toList();
    }

    /**
     * _source 裁剪检索（仅返回展示所需标量字段并剔除高维 embedding 向量数组）
     *
     * @param keyword 可选搜索关键字
     * @return 裁剪后的商品命中列表
     */
    public List<SearchHit<ProductDoc>> searchWithSourceTrimming(String keyword) {
        SearchQuery query = SearchQueryBuilder.create()
                .when(keyword != null && !keyword.isBlank(), q -> q.multiMatch(keyword, "title", "description"))
                .when(keyword == null || keyword.isBlank(), SearchQueryBuilder::matchAll)
                .includes("id", "title", "description", "category", "brand", "price", "stock", "status")
                .excludes("embedding")
                .build();
        return searchTemplate.search(query, ProductDoc.class).getHits();
    }

    /**
     * 基于 Point-In-Time (PIT) 一致性快照执行全量深翻页遍历并在 finally 确定性释放快照
     *
     * @param pageSize 单页大小
     * @return 遍历收集到的全量商品命中列表
     */
    public List<SearchHit<ProductDoc>> searchWithPitTraversal(int pageSize) {
        int safeSize = Math.max(1, pageSize);
        SearchQuery query = SearchQueryBuilder.create()
                .matchAll()
                .orderByAsc("id")
                .build();
        return searchTemplate.executeWithPointInTime(ProductDoc.class, Duration.ofMinutes(1), pit -> {
            List<SearchHit<ProductDoc>> collected = new ArrayList<>();
            List<String> cursor = null;
            int guardLoops = 0;
            while (guardLoops < MAX_PIT_TRAVERSAL_PAGES) {
                guardLoops++;
                SearchScrollResult<ProductDoc> batch = searchTemplate.searchAfterWithPit(pit, query, cursor, safeSize, ProductDoc.class);
                if (batch.getHits() != null) {
                    collected.addAll(batch.getHits());
                }
                List<String> nextCursor = batch.getNextSortValues();
                if (!batch.hasNext() || nextCursor == null || nextCursor.isEmpty() || nextCursor.equals(cursor)) {
                    break;
                }
                cursor = nextCursor;
            }
            return collected;
        });
    }

    /**
     * _msearch 单次 HTTP 往返批量异构检索（第一路关键词搜索 + 第二路分类计数）
     *
     * @param keyword  全文关键字
     * @param category 指定分类
     * @return 多路分页结果列表
     */
    public List<SearchPage<ProductDoc>> batchMultiSearch(String keyword, String category) {
        SearchQuery keywordQuery = SearchQueryBuilder.create()
                .when(keyword != null && !keyword.isBlank(), q -> q.multiMatch(keyword, "title", "description"))
                .when(keyword == null || keyword.isBlank(), SearchQueryBuilder::matchAll)
                .highlight("title", "description")
                .size(5)
                .build();
        SearchQuery categoryStatQuery = SearchQueryBuilder.create()
                .when(category != null && !category.isBlank(), q -> q.term("category", category))
                .when(category == null || category.isBlank(), SearchQueryBuilder::matchAll)
                .size(0)
                .avgAggregation("avg_price", "price")
                .build();
        return searchTemplate.msearch(List.of(keywordQuery, categoryStatQuery), ProductDoc.class);
    }

    /**
     * AI 混合检索融合 (BM25 词法召回 + kNN 向量召回，默认零许可证 CLIENT_RRF 模式)
     *
     * @param keyword 全文关键字
     * @param vector  特征向量
     * @param k       返回 TopK
     * @param mode    融合模式（可为 null，默认 CLIENT_RRF）
     * @return 融合重排后的分页结果
     */
    public SearchPage<ProductDoc> hybridSearch(String keyword, List<Float> vector, int k, SearchHybridMode mode) {
        int safeK = Math.max(1, k);
        SearchQuery textQuery = SearchQueryBuilder.create()
                .multiMatch(keyword, "title", "description")
                .highlight("title", "description")
                .excludes("embedding")
                .build();
        HybridSearchOptions options = HybridSearchOptions.builder()
                .mode(mode != null ? mode : SearchHybridMode.CLIENT_RRF)
                .topK(safeK)
                .build();
        return searchTemplate.hybridSearch(textQuery, "embedding", vector, safeK, Math.max(20, safeK * 4), options, ProductDoc.class);
    }

    /**
     * 商品价格与类目多维大盘分析（Cardinality + Stats + Percentiles + 嵌套子聚合）
     *
     * @return 携带强类型多维聚合指标的 {@link SearchPage}
     */
    public SearchPage<ProductDoc> analyzePriceDashboard() {
        SearchQuery query = SearchQueryBuilder.create()
                .cardinalityAggregation("unique_brands", "brand")
                .statsAggregation("price_stats", "price")
                .percentilesAggregation("price_pct", "price", 50.0, 95.0, 99.0)
                .termsAggregation("by_category", "category", 10, sub -> sub
                        .statsAggregation("cat_price_stats", "price")
                        .percentilesAggregation("cat_price_pct", "price", 50.0, 95.0))
                .size(0)
                .build();
        return searchTemplate.search(query, ProductDoc.class);
    }

    /**
     * 商品标题检索拼写纠错建议 (Term Suggester + Phrase Suggester)
     *
     * @param text 待建议/纠错文本
     * @param size 建议条数上限
     * @return 携带强类型建议列表的 {@link SearchPage}
     */
    public SearchPage<ProductDoc> suggestProductTitles(String text, int size) {
        int safeSize = Math.max(1, size);
        SearchQuery query = SearchQueryBuilder.create()
                .termSuggest("title_term", "title", text, safeSize)
                .phraseSuggest("title_phrase", "title", text, safeSize)
                .size(0)
                .build();
        return searchTemplate.search(query, ProductDoc.class);
    }
}
