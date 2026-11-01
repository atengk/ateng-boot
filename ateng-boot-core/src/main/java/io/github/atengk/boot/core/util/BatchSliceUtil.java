package io.github.atengk.boot.core.util;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 大批量切片流式处理实用工具
 * 提供基于原生 subList 视图的零内存额外分配分批切片（防 OOM 批量流转）
 *
 * @author Ateng
 * @since 2026-11-01
 */
public final class BatchSliceUtil {

    private BatchSliceUtil() {
    }

    /**
     * 将目标列表按照指定切片大小切分为多个子列表视图
     *
     * @param list      原始列表
     * @param sliceSize 切片批次大小（必须大于 0）
     * @param <T>       元素泛型
     * @return 分批子列表集合；若入参列表为空则返回空集合
     */
    public static <T> List<List<T>> slice(List<T> list, int sliceSize) {
        if (CollectionUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        if (sliceSize <= 0) {
            throw new IllegalArgumentException("sliceSize must be greater than 0");
        }
        return CollectionUtil.partition(list, sliceSize);
    }

    /**
     * 分批遍历消费指定列表
     *
     * @param list          原始列表
     * @param sliceSize     切片批次大小（必须大于 0）
     * @param sliceConsumer 每批切片消费器
     * @param <T>           元素泛型
     */
    public static <T> void forEachSlice(List<T> list, int sliceSize, Consumer<List<T>> sliceConsumer) {
        if (CollectionUtil.isEmpty(list)) {
            return;
        }
        Objects.requireNonNull(sliceConsumer, "sliceConsumer must not be null");
        List<List<T>> slices = slice(list, sliceSize);
        for (List<T> batch : slices) {
            sliceConsumer.accept(batch);
        }
    }
}
