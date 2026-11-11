package io.github.atengk.boot.sample.search.dto;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import io.github.atengk.boot.sample.search.model.AccessLogDoc;
import io.github.atengk.boot.sample.search.model.ProductDoc;
import io.github.atengk.boot.search.model.SearchHit;

import java.time.LocalDateTime;
import java.util.Arrays;
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
        ProductResponse response = toResponse(hit.getSource());
        response.setScore(hit.getScore());
        if (hit.getSortValues() != null) {
            response.setSortValues(Arrays.asList(hit.getSortValues()));
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

    public static List<TrafficTrendResponse> toTrafficTrends(Map<String, Aggregate> aggregations) {
        if (aggregations == null || !aggregations.containsKey("daily_traffic")) {
            return Collections.emptyList();
        }
        Aggregate agg = aggregations.get("daily_traffic");
        if (agg == null || !agg.isDateHistogram()) {
            return Collections.emptyList();
        }
        return agg.dateHistogram().buckets().array().stream()
                .map(bucket -> TrafficTrendResponse.builder()
                        .bucket(bucket.keyAsString() != null ? bucket.keyAsString() : String.valueOf(bucket.key()))
                        .docCount(bucket.docCount())
                        .build())
                .collect(Collectors.toList());
    }

    public static Map<String, Double> toMetricValues(Map<String, Aggregate> aggregations) {
        if (aggregations == null || aggregations.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        aggregations.forEach((name, agg) -> {
            if (agg == null) {
                return;
            }
            if (agg.isMin()) {
                result.put(name, agg.min().value());
            } else if (agg.isMax()) {
                result.put(name, agg.max().value());
            } else if (agg.isAvg()) {
                result.put(name, agg.avg().value());
            }
        });
        return result;
    }
}
