package io.github.atengk.boot.mybatisplus.util;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 原生高性能批量操作门面工具类
 * 提供针对 AtengBaseMapper 的集合切片与智能分批批量插入能力，杜绝单次超大 SQL 导致网络包溢出
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class BatchUtil {

    /**
     * 默认分片切片批次大小（1000）
     */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    private BatchUtil() {
    }

    /**
     * 原生分批批量插入（采用默认分批大小 1000）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量插入的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功插入影响的总行数
     */
    public static <T> int insertBatch(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return insertBatch(mapper, entityList, DEFAULT_BATCH_SIZE);
    }

    /**
     * 原生分批批量插入（支持自定义切片批次大小）
     * 将大集合按 batchSize 切片后逐批执行 insertBatchSomeColumn 原生多值 SQL 插入
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量插入的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用默认值 1000）
     * @param <T>        实体类型
     * @return 累计成功插入影响的总行数
     */
    public static <T> int insertBatch(AtengBaseMapper<T> mapper, Collection<T> entityList, int batchSize) {
        if (entityList == null || entityList.isEmpty()) {
            return 0;
        }
        if (mapper == null) {
            throw new IllegalArgumentException("Mapper must not be null");
        }

        int effectiveBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        List<T> list = (entityList instanceof List) ? (List<T>) entityList : new ArrayList<>(entityList);

        int totalSize = list.size();
        int affectedRows = 0;

        for (int fromIndex = 0; fromIndex < totalSize; fromIndex += effectiveBatchSize) {
            int toIndex = Math.min(fromIndex + effectiveBatchSize, totalSize);
            List<T> subList = list.subList(fromIndex, toIndex);
            affectedRows += mapper.insertBatchSomeColumn(subList);
        }

        return affectedRows;
    }

    /**
     * 原生分批批量插入别名方法（采用默认分批大小 1000）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量插入的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功插入影响的总行数
     */
    public static <T> int insertBatchSomeColumn(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return insertBatch(mapper, entityList, DEFAULT_BATCH_SIZE);
    }

    /**
     * 原生分批批量插入别名方法（支持自定义切片批次大小）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量插入的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用默认值 1000）
     * @param <T>        实体类型
     * @return 累计成功插入影响的总行数
     */
    public static <T> int insertBatchSomeColumn(AtengBaseMapper<T> mapper, Collection<T> entityList, int batchSize) {
        return insertBatch(mapper, entityList, batchSize);
    }
}
