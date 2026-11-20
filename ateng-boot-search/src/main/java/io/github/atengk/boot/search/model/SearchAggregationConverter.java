package io.github.atengk.boot.search.model;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.ArrayPercentilesItem;
import co.elastic.clients.elasticsearch._types.aggregations.DateHistogramBucket;
import co.elastic.clients.elasticsearch._types.aggregations.DoubleTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.FilterAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.Percentiles;
import co.elastic.clients.elasticsearch._types.aggregations.StatsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 原生聚合响应到强类型 {@link SearchBucket} / {@link SearchNumericStats} /
 * {@link SearchPercentilesResult} 模型的内部统一转换器
 *
 * @author Ateng
 * @since 2026-11-20
 */
public final class SearchAggregationConverter {

    private static final int MAX_SUB_AGG_DEPTH = 16;

    private SearchAggregationConverter() {
    }

    /**
     * 读取指标类聚合值（min / max / avg / sum / cardinality / value_count 等）
     *
     * @param aggregations 聚合结果映射
     * @param name         聚合名称
     * @return 指标数值（不存在或非有限值时返回 null）
     */
    public static Double getMetricValue(Map<String, Aggregate> aggregations, String name) {
        if (aggregations == null || name == null) {
            return null;
        }
        return extractMetricValue(aggregations.get(name));
    }

    /**
     * 读取词项分桶聚合结果列表
     *
     * @param aggregations 聚合结果映射
     * @param name         聚合名称
     * @return 强类型分桶列表（保底返回空集合）
     */
    public static List<SearchBucket> getTermsBuckets(Map<String, Aggregate> aggregations, String name) {
        if (aggregations == null || name == null) {
            return Collections.emptyList();
        }
        return extractTermsBuckets(aggregations.get(name), 0);
    }

    /**
     * 读取日期直方图分桶聚合结果列表
     *
     * @param aggregations 聚合结果映射
     * @param name         聚合名称
     * @return 强类型分桶列表（保底返回空集合）
     */
    public static List<SearchBucket> getDateHistogramBuckets(Map<String, Aggregate> aggregations, String name) {
        if (aggregations == null || name == null) {
            return Collections.emptyList();
        }
        return extractDateHistogramBuckets(aggregations.get(name), 0);
    }

    /**
     * 读取过滤单桶聚合结果
     *
     * @param aggregations 聚合结果映射
     * @param name         聚合名称
     * @return 过滤单桶结果（不存在时返回 null）
     */
    public static SearchBucket getFilterBucket(Map<String, Aggregate> aggregations, String name) {
        if (aggregations == null || name == null) {
            return null;
        }
        return extractFilterBucket(aggregations.get(name), 0);
    }

    /**
     * 读取数值统计聚合结果
     *
     * @param aggregations 聚合结果映射
     * @param name         聚合名称
     * @return 数值统计模型（不存在时返回 null）
     */
    public static SearchNumericStats getStats(Map<String, Aggregate> aggregations, String name) {
        if (aggregations == null || name == null) {
            return null;
        }
        return extractStats(aggregations.get(name));
    }

    /**
     * 读取分位数聚合结果
     *
     * @param aggregations 聚合结果映射
     * @param name         聚合名称
     * @return 分位数模型（不存在时返回 null）
     */
    public static SearchPercentilesResult getPercentiles(Map<String, Aggregate> aggregations, String name) {
        if (aggregations == null || name == null) {
            return null;
        }
        return extractPercentiles(aggregations.get(name));
    }

    static Double extractMetricValue(Aggregate agg) {
        if (agg == null) {
            return null;
        }
        if (agg.isMin()) {
            return finiteOrNull(agg.min().value());
        }
        if (agg.isMax()) {
            return finiteOrNull(agg.max().value());
        }
        if (agg.isAvg()) {
            return finiteOrNull(agg.avg().value());
        }
        if (agg.isSum()) {
            return finiteOrNull(agg.sum().value());
        }
        if (agg.isCardinality()) {
            return (double) agg.cardinality().value();
        }
        if (agg.isValueCount()) {
            return (double) agg.valueCount().value();
        }
        if (agg.isSimpleValue()) {
            return finiteOrNull(agg.simpleValue().value());
        }
        if (agg.isSimpleLongValue()) {
            return (double) agg.simpleLongValue().value();
        }
        if (agg.isBucketMetricValue()) {
            return finiteOrNull(agg.bucketMetricValue().value());
        }
        if (agg.isWeightedAvg()) {
            return finiteOrNull(agg.weightedAvg().value());
        }
        return null;
    }

    private static List<SearchBucket> extractTermsBuckets(Aggregate agg, int depth) {
        if (agg == null) {
            return Collections.emptyList();
        }
        if (agg.isSterms() && agg.sterms().buckets() != null) {
            List<StringTermsBucket> array = agg.sterms().buckets().array();
            if (array == null || array.isEmpty()) {
                return Collections.emptyList();
            }
            return array.stream()
                    .map(b -> buildBucket(extractFieldKey(b.key()), null, b.docCount(), b.aggregations(), depth))
                    .toList();
        }
        if (agg.isLterms() && agg.lterms().buckets() != null) {
            List<LongTermsBucket> array = agg.lterms().buckets().array();
            if (array == null || array.isEmpty()) {
                return Collections.emptyList();
            }
            return array.stream()
                    .map(b -> buildBucket(
                            b.keyAsString() != null ? b.keyAsString() : String.valueOf(b.key()),
                            b.keyAsString(),
                            b.docCount(),
                            b.aggregations(),
                            depth))
                    .toList();
        }
        if (agg.isDterms() && agg.dterms().buckets() != null) {
            List<DoubleTermsBucket> array = agg.dterms().buckets().array();
            if (array == null || array.isEmpty()) {
                return Collections.emptyList();
            }
            return array.stream()
                    .map(b -> buildBucket(
                            b.keyAsString() != null ? b.keyAsString() : String.valueOf(b.key()),
                            b.keyAsString(),
                            b.docCount(),
                            b.aggregations(),
                            depth))
                    .toList();
        }
        return Collections.emptyList();
    }

    private static List<SearchBucket> extractDateHistogramBuckets(Aggregate agg, int depth) {
        if (agg == null || !agg.isDateHistogram() || agg.dateHistogram().buckets() == null) {
            return Collections.emptyList();
        }
        List<DateHistogramBucket> array = agg.dateHistogram().buckets().array();
        if (array == null || array.isEmpty()) {
            return Collections.emptyList();
        }
        return array.stream()
                .map(b -> buildBucket(
                        b.keyAsString() != null ? b.keyAsString() : String.valueOf(b.key()),
                        b.keyAsString(),
                        b.docCount(),
                        b.aggregations(),
                        depth))
                .toList();
    }

    private static SearchBucket extractFilterBucket(Aggregate agg, int depth) {
        if (agg == null || !agg.isFilter()) {
            return null;
        }
        FilterAggregate filterAgg = agg.filter();
        return buildBucket(null, null, filterAgg.docCount(), filterAgg.aggregations(), depth);
    }

    private static SearchNumericStats extractStats(Aggregate agg) {
        if (agg == null || !agg.isStats()) {
            return null;
        }
        StatsAggregate stats = agg.stats();
        return SearchNumericStats.builder()
                .count(stats.count())
                .min(finiteOrNull(stats.min()))
                .max(finiteOrNull(stats.max()))
                .avg(finiteOrNull(stats.avg()))
                .sum(finiteOrNull(stats.sum()))
                .build();
    }

    private static SearchPercentilesResult extractPercentiles(Aggregate agg) {
        if (agg == null) {
            return null;
        }
        Percentiles values = null;
        if (agg.isTdigestPercentiles()) {
            values = agg.tdigestPercentiles().values();
        } else if (agg.isHdrPercentiles()) {
            values = agg.hdrPercentiles().values();
        }
        if (values == null) {
            return null;
        }
        Map<String, Double> parsed = new LinkedHashMap<>();
        if (values.isKeyed() && values.keyed() != null) {
            values.keyed().forEach((k, v) -> parsed.put(k, parsePercentileDouble(v)));
        } else if (values.isArray() && values.array() != null) {
            for (ArrayPercentilesItem item : values.array()) {
                parsed.put(item.key(), finiteOrNull(item.value()));
            }
        }
        return SearchPercentilesResult.builder().values(parsed).build();
    }

    private static SearchBucket buildBucket(
            String key,
            String keyAsString,
            long docCount,
            Map<String, Aggregate> subAggregations,
            int depth) {
        SearchBucket.SearchBucketBuilder builder = SearchBucket.builder()
                .key(key)
                .keyAsString(keyAsString)
                .docCount(docCount);

        if (subAggregations == null || subAggregations.isEmpty() || depth >= MAX_SUB_AGG_DEPTH) {
            return builder.build();
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        Map<String, List<SearchBucket>> bucketLists = new LinkedHashMap<>();
        Map<String, SearchBucket> filterBuckets = new LinkedHashMap<>();
        Map<String, SearchNumericStats> statsMap = new LinkedHashMap<>();
        Map<String, SearchPercentilesResult> percentilesMap = new LinkedHashMap<>();

        for (Map.Entry<String, Aggregate> entry : subAggregations.entrySet()) {
            String subName = entry.getKey();
            Aggregate subAgg = entry.getValue();
            if (subAgg == null) {
                continue;
            }
            Double metric = extractMetricValue(subAgg);
            if (metric != null) {
                metrics.put(subName, metric);
                continue;
            }
            if (subAgg.isStats()) {
                SearchNumericStats s = extractStats(subAgg);
                if (s != null) {
                    statsMap.put(subName, s);
                }
                continue;
            }
            if (subAgg.isTdigestPercentiles() || subAgg.isHdrPercentiles()) {
                SearchPercentilesResult p = extractPercentiles(subAgg);
                if (p != null) {
                    percentilesMap.put(subName, p);
                }
                continue;
            }
            if (subAgg.isFilter()) {
                SearchBucket fb = extractFilterBucket(subAgg, depth + 1);
                if (fb != null) {
                    filterBuckets.put(subName, fb);
                }
                continue;
            }
            if (subAgg.isSterms() || subAgg.isLterms() || subAgg.isDterms()) {
                bucketLists.put(subName, extractTermsBuckets(subAgg, depth + 1));
                continue;
            }
            if (subAgg.isDateHistogram()) {
                bucketLists.put(subName, extractDateHistogramBuckets(subAgg, depth + 1));
            }
        }

        if (!metrics.isEmpty()) {
            builder.metrics(metrics);
        }
        if (!bucketLists.isEmpty()) {
            builder.bucketLists(bucketLists);
        }
        if (!filterBuckets.isEmpty()) {
            builder.filterBuckets(filterBuckets);
        }
        if (!statsMap.isEmpty()) {
            builder.stats(statsMap);
        }
        if (!percentilesMap.isEmpty()) {
            builder.percentiles(percentilesMap);
        }
        return builder.build();
    }

    private static String extractFieldKey(FieldValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.stringValue();
        }
        if (value.isLong()) {
            return String.valueOf(value.longValue());
        }
        if (value.isDouble()) {
            return String.valueOf(value.doubleValue());
        }
        if (value.isBoolean()) {
            return String.valueOf(value.booleanValue());
        }
        Object raw = value._get();
        return raw != null ? String.valueOf(raw) : value._toJsonString();
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static Double parsePercentileDouble(String value) {
        if (value == null || value.isBlank() || "NaN".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
