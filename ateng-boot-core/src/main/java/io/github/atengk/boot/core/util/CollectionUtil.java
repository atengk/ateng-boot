package io.github.atengk.boot.core.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        List<List<T>> partitions = new ArrayList<>((totalSize + batchSize - 1) / batchSize);
        for (int i = 0; i < totalSize; i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, totalSize)));
        }
        return partitions;
    }
}
