package io.github.atengk.boot.core.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collector;

/**
 * 扩展 Stream 收集器标准件
 * 彻底解决原生 Collectors.toMap 当 Value 为 null 时抛出 NPE 以及未处理重复 Key 抛出 IllegalStateException 的痛点
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class MoreCollectors {

    private MoreCollectors() {
    }

    /**
     * 将 Stream 流安全收集为有序 Map
     * 1. 允许 Value 为 null（原生 Collectors.toMap 会无情抛出 NullPointerException）
     * 2. 遇到重复 Key 时默认后置覆盖（保留最新值）
     * 3. 采用 LinkedHashMap 保留流的原始相对次序
     *
     * @param keyMapper   键映射函数
     * @param valueMapper 值映射函数
     * @param <T>         流输入元素类型
     * @param <K>         Map 键类型
     * @param <U>         Map 值类型
     * @return 安全的 Collector 收集器
     */
    public static <T, K, U> Collector<T, ?, Map<K, U>> toSafeMap(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper
    ) {
        return toSafeMap(keyMapper, valueMapper, (existing, replacement) -> replacement);
    }

    /**
     * 将 Stream 流安全收集为有序 Map（支持自定义重复键合并策略）
     *
     * @param keyMapper     键映射函数
     * @param valueMapper   值映射函数
     * @param mergeFunction 重复键合并策略
     * @param <T>           流输入元素类型
     * @param <K>           Map 键类型
     * @param <U>           Map 值类型
     * @return 安全的 Collector 收集器
     */
    public static <T, K, U> Collector<T, ?, Map<K, U>> toSafeMap(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper,
            BinaryOperator<U> mergeFunction
    ) {
        Objects.requireNonNull(keyMapper, "keyMapper 不能为空");
        Objects.requireNonNull(valueMapper, "valueMapper 不能为空");
        Objects.requireNonNull(mergeFunction, "mergeFunction 不能为空");

        return Collector.of(
                LinkedHashMap::new,
                (map, element) -> {
                    K key = keyMapper.apply(element);
                    U value = valueMapper.apply(element);
                    if (map.containsKey(key)) {
                        map.put(key, mergeFunction.apply(map.get(key), value));
                    } else {
                        map.put(key, value);
                    }
                },
                (left, right) -> {
                    for (Map.Entry<K, U> entry : right.entrySet()) {
                        K key = entry.getKey();
                        U value = entry.getValue();
                        if (left.containsKey(key)) {
                            left.put(key, mergeFunction.apply(left.get(key), value));
                        } else {
                            left.put(key, value);
                        }
                    }
                    return left;
                }
        );
    }

    /**
     * 根据指定的键提取函数生成用于 Stream.filter 的去重谓词断言
     *
     * @param keyExtractor 键提取函数
     * @param <T>          元素泛型
     * @return 去重断言 Predicate
     */
    public static <T> java.util.function.Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Objects.requireNonNull(keyExtractor, "keyExtractor 不能为空");
        java.util.Set<Object> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }

    /**
     * 根据指定的键提取函数返回去重收集器（将流收集为按指定 Key 去重后的有序 List）
     * 满足 Collector 标准契约，保留元素遭遇顺序（Encounter Order）
     *
     * @param keyExtractor 键提取函数
     * @param <T>          元素泛型
     * @return 按 Key 去重的 List 收集器
     */
    public static <T> Collector<T, ?, List<T>> toDistinctList(Function<? super T, ?> keyExtractor) {
        Objects.requireNonNull(keyExtractor, "keyExtractor 不能为空");
        class DistinctAccumulator {
            final Set<Object> seen = new HashSet<>();
            final List<T> list = new ArrayList<>();

            void add(T item) {
                if (seen.add(keyExtractor.apply(item))) {
                    list.add(item);
                }
            }

            DistinctAccumulator combine(DistinctAccumulator other) {
                for (T item : other.list) {
                    add(item);
                }
                return this;
            }
        }
        return Collector.of(
                DistinctAccumulator::new,
                DistinctAccumulator::add,
                DistinctAccumulator::combine,
                acc -> acc.list
        );
    }

    /**
     * 根据指定的键提取函数返回去重收集器（契约别名方法，等同于 {@link #toDistinctList(Function)}）
     *
     * @param keyExtractor 键提取函数
     * @param <T>          元素泛型
     * @return 按 Key 去重的 List 收集器
     */
    public static <T> Collector<T, ?, List<T>> distinctByKeyCollector(Function<? super T, ?> keyExtractor) {
        return toDistinctList(keyExtractor);
    }
}
