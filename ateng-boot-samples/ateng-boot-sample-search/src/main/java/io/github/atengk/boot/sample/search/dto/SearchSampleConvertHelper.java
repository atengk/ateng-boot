package io.github.atengk.boot.sample.search.dto;

import io.github.atengk.boot.sample.search.model.AccessLogDoc;
import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.search.model.SearchBucket;
import io.github.atengk.boot.search.model.SearchHit;
import io.github.atengk.boot.search.model.SearchPage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 样例工程传输模型与索引文档实体转换辅助类 (Zero-Entity-Leakage 守卫)
 *
 * @author Ateng
 * @since 2026-11-11
 */
public final class SearchSampleConvertHelper {

    private SearchSampleConvertHelper() {
    }

    public static ProductDoc toDoc(ProductSaveRequest request) {
        if (request == null) {
            return null;
        }
        String id = request.getId() != null && !request.getId().isBlank()
                ? request.getId()
                : UUID.randomUUID().toString();

        List<ProductDoc.SkuItem> skuDocs = request.getSkus() == null ? Collections.emptyList() :
                request.getSkus().stream()
                        .map(s -> ProductDoc.SkuItem.builder()
                                .skuId(s.getSkuId())
                                .color(s.getColor())
                                .price(s.getPrice())
                                .stock(s.getStock())
                                .build())
                        .collect(Collectors.toList());

        ProductDoc.GeoPoint geoPoint = request.getLocation() == null ? null :
                new ProductDoc.GeoPoint(request.getLocation().getLat(), request.getLocation().getLon());

        return ProductDoc.builder()
                .id(id)
                .title(request.getTitle())
                .description(request.getDescription())
                .category(request.getCategory())
                .brand(request.getBrand())
                .tags(request.getTags())
                .price(request.getPrice())
                .stock(request.getStock())
                .status(request.getStatus() != null ? request.getStatus() : "ON_SALE")
                .location(geoPoint)
                .embedding(request.getEmbedding())
                .tenantId(request.getTenantId())
                .createTime(LocalDateTime.now())
                .skus(skuDocs)
                .build();
    }

    public static ProductResponse toResponse(ProductDoc doc) {
        if (doc == null) {
            return null;
        }
        List<ProductResponse.SkuItemVo> skus = doc.getSkus() == null ? Collections.emptyList() :
                doc.getSkus().stream()
                        .map(s -> ProductResponse.SkuItemVo.builder()
                                .skuId(s.getSkuId())
                                .color(s.getColor())
                                .price(s.getPrice())
                                .stock(s.getStock())
                                .build())
                        .collect(Collectors.toList());

        ProductResponse.GeoPointVo geoVo = doc.getLocation() == null ? null :
                new ProductResponse.GeoPointVo(doc.getLocation().getLat(), doc.getLocation().getLon());

        return ProductResponse.builder()
                .id(doc.getId())
                .title(doc.getTitle())
                .description(doc.getDescription())
                .category(doc.getCategory())
                .brand(doc.getBrand())
                .tags(doc.getTags())
                .price(doc.getPrice())
                .stock(doc.getStock())
                .status(doc.getStatus())
                .location(geoVo)
                .skus(skus)
                .tenantId(doc.getTenantId())
                .createTime(doc.getCreateTime())
                .build();
    }

    public static ProductResponse toResponse(SearchHit<ProductDoc> hit) {
        if (hit == null || hit.getSource() == null) {
            return null;
        }
        return enrichHitMetadata(hit, hit.map(SearchSampleConvertHelper::toResponse).getContent());
    }

    /**
     * 将已经过视图投影的命中集合补充命中元信息（相关性得分与排序游标）
     *
     * @param hits 视图投影命中集合
     * @return 展示响应列表
     */
    public static List<ProductResponse> toVoResponseList(List<SearchHit<ProductResponse>> hits) {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        return hits.stream()
                .map(hit -> hit == null ? null : enrichHitMetadata(hit, hit.getContent()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    private static ProductResponse enrichHitMetadata(SearchHit<?> hit, ProductResponse response) {
        if (response != null && hit != null) {
            response.setScore(hit.getScore());
            if (hit.getSortValues() != null) {
                response.setSortValues(new ArrayList<>(hit.getSortValues()));
            }
        }
        return response;
    }

    public static List<ProductResponse> toResponseList(List<ProductDoc> docs) {
        if (docs == null) {
            return Collections.emptyList();
        }
        return docs.stream()
                .map(SearchSampleConvertHelper::toResponse)
                .collect(Collectors.toList());
    }

    public static List<ProductResponse> toHitResponseList(List<SearchHit<ProductDoc>> hits) {
        if (hits == null) {
            return Collections.emptyList();
        }
        return hits.stream()
                .map(SearchSampleConvertHelper::toResponse)
                .collect(Collectors.toList());
    }

    public static AccessLogDoc toDoc(AccessLogRecordRequest request) {
        if (request == null) {
            return null;
        }
        String id = request.getId() != null && !request.getId().isBlank()
                ? request.getId()
                : UUID.randomUUID().toString();
        LocalDateTime time = request.getTimestamp() != null ? request.getTimestamp() : LocalDateTime.now();

        return AccessLogDoc.builder()
                .id(id)
                .path(request.getPath())
                .clientIp(request.getClientIp())
                .timestamp(time)
                .build();
    }

    public static List<TrafficTrendResponse> toTrafficTrends(SearchPage<?> page) {
        if (page == null) {
            return Collections.emptyList();
        }
        List<SearchBucket> buckets = page.getDateHistogramBuckets("daily_traffic");
        if (buckets.isEmpty()) {
            return Collections.emptyList();
        }
        return buckets.stream()
                .map(bucket -> TrafficTrendResponse.builder()
                        .bucket(bucket.getKeyAsString() != null ? bucket.getKeyAsString() : bucket.getKey())
                        .docCount(bucket.getDocCount())
                        .build())
                .collect(Collectors.toList());
    }

    public static Map<String, Double> toMetricValues(SearchPage<?> page) {
        if (page == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (String name : new String[]{"min_price", "max_price", "avg_price"}) {
            Double value = page.getMetricValue(name);
            if (value != null) {
                result.put(name, value);
            }
        }
        return result;
    }

    public static AccessLogDashboardResponse toAccessLogDashboard(SearchPage<?> page) {
        if (page == null) {
            return AccessLogDashboardResponse.builder().build();
        }
        Double uniqueIps = page.getMetricValue("unique_ips");
        List<AccessLogDashboardResponse.DailyBucketItem> items = page.getDateHistogramBuckets("daily_traffic").stream()
                .map(bucket -> {
                    Double dailyUnique = bucket.getMetricValue("daily_unique_ips");
                    return AccessLogDashboardResponse.DailyBucketItem.builder()
                            .bucket(bucket.getKeyAsString() != null ? bucket.getKeyAsString() : bucket.getKey())
                            .docCount(bucket.getDocCount())
                            .uniqueIpCount(dailyUnique != null ? dailyUnique.longValue() : 0L)
                            .build();
                })
                .collect(Collectors.toList());
        return AccessLogDashboardResponse.builder()
                .uniqueIpCount(uniqueIps != null ? uniqueIps.longValue() : 0L)
                .dailyBuckets(items)
                .build();
    }

    public static ProductDashboardResponse toProductDashboard(SearchPage<?> page) {
        if (page == null) {
            return ProductDashboardResponse.builder().build();
        }
        Double uniqueBrands = page.getMetricValue("unique_brands");
        List<ProductDashboardResponse.CategoryStatsItem> categories = page.getTermsBuckets("by_category").stream()
                .map(bucket -> ProductDashboardResponse.CategoryStatsItem.builder()
                        .category(bucket.getKey())
                        .docCount(bucket.getDocCount())
                        .priceStats(bucket.getStats("cat_price_stats"))
                        .pricePercentiles(bucket.getPercentiles("cat_price_pct"))
                        .build())
                .collect(Collectors.toList());
        return ProductDashboardResponse.builder()
                .uniqueBrandCount(uniqueBrands != null ? uniqueBrands.longValue() : 0L)
                .priceStats(page.getStats("price_stats"))
                .pricePercentiles(page.getPercentiles("price_pct"))
                .categoryStats(categories)
                .build();
    }
}
