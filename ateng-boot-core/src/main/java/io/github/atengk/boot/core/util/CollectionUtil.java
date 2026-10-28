package io.github.atengk.boot.core.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 集合增强实用工具
 * 提供集合判空与基于 subList 视图的零内存拷贝安全分批切片（防 OOM 批量流转）
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class CollectionUtil {

    private CollectionUtil() {
    }

    /**
     * 判断集合是否为空（null 或元素为空）
     *
     * @param collection 目标集合
     * @return true 表示为空
     */
    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /**
     * 判断集合是否非空
     *
     * @param collection 目标集合
     * @return true 表示非空
     */
    public static boolean isNotEmpty(Collection<?> collection) {
        return !isEmpty(collection);
    }

    /**
     * 判断 Map 是否为空（null 或无键值对）
     *
     * @param map 目标 Map
     * @return true 表示为空
     */
    public static boolean isEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * 判断 Map 是否非空
     *
     * @param map 目标 Map
     * @return true 表示非空
     */
    public static boolean isNotEmpty(Map<?, ?> map) {
        return !isEmpty(map);
    }

    /**
     * 将大列表按照指定批次大小安全切分为多个子列表
     * 基于 list.subList 原生视图实现，零内存额外分配，防御空入参与非法分批大小
     *
     * @param list      原始列表
     * @param batchSize 批次容量大小（必须大于 0）
     * @param <T>       元素泛型
     * @return 切分后的分批列表集合；入参为空时返回空集合
     */
    public static <T> List<List<T>> partition(List<T> list, int batchSize) {
        if (isEmpty(list) || batchSize <= 0) {
            return Collections.emptyList();
        }
        int totalSize = list.size();
        int capacity = (int) ((((long) totalSize) + batchSize - 1) / batchSize);
        List<List<T>> partitions = new ArrayList<>(capacity);
        for (int i = 0; i < totalSize; i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, totalSize)));
        }
        return partitions;
    }

    /**
     * 空安全集合保底返回：若传入集合为 null 则返回不可变空集合，否则返回原集合
     *
     * @param collection 目标集合
     * @param <T>        元素泛型
     * @return 非 null 的集合
     */
    public static <T> Collection<T> emptyIfNull(Collection<T> collection) {
        return collection != null ? collection : Collections.emptyList();
    }

    /**
     * 空安全列表保底返回：若传入列表为 null 则返回不可变空列表，否则返回原列表
     *
     * @param list 目标列表
     * @param <T>  元素泛型
     * @return 非 null 的列表
     */
    public static <T> List<T> emptyIfNull(List<T> list) {
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 空安全 Set 保底返回：若传入 Set 为 null 则返回不可变空 Set，否则返回原 Set
     *
     * @param set 目标 Set
     * @param <T> 元素泛型
     * @return 非 null 的 Set
     */
    public static <T> java.util.Set<T> emptyIfNull(java.util.Set<T> set) {
        return set != null ? set : Collections.emptySet();
    }

    /**
     * 空安全 Map 保底返回：若传入 Map 为 null 则返回不可变空 Map，否则返回原 Map
     *
     * @param map 目标 Map
     * @param <K> 键泛型
     * @param <V> 值泛型
     * @return 非 null 的 Map
     */
    public static <K, V> Map<K, V> emptyIfNull(Map<K, V> map) {
        return map != null ? map : Collections.emptyMap();
    }

    /**
     * 安全获取集合的第一个元素
     *
     * @param collection 目标集合
     * @param <T>        元素泛型
     * @return 第一个元素；若集合为空或 null 则返回 null
     */
    public static <T> T first(Collection<T> collection) {
        if (isEmpty(collection)) {
            return null;
        }
        if (collection instanceof List<T> list) {
            return list.get(0);
        }
        return collection.iterator().next();
    }

    /**
     * 安全获取列表的最后一个元素
     *
     * @param list 目标列表
     * @param <T>  元素泛型
     * @return 最后一个元素；若列表为空或 null 则返回 null
     */
    public static <T> T last(List<T> list) {
        if (isEmpty(list)) {
            return null;
        }
        return list.get(list.size() - 1);
    }

    /**
     * 根据指定的键提取函数生成用于 Stream.filter 的去重谓词断言
     *
     * @param keyExtractor 键提取函数
     * @param <T>          元素泛型
     * @return 去重断言 Predicate
     */
    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        return MoreCollectors.distinctByKey(keyExtractor);
    }
}
