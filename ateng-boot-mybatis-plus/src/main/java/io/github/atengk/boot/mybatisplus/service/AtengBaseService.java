package io.github.atengk.boot.mybatisplus.service;

import com.baomidou.mybatisplus.spring.service.IService;

import java.util.Collection;

/**
 * 增强型通用业务 Service 接口
 * 继承 MyBatis-Plus IService，扩展支持自定义分批切片的原生高性能批量插入能力
 *
 * @param <T> 实体类型
 * @author Ateng
 * @since 2026-10-16
 */
public interface AtengBaseService<T> extends IService<T> {

    /**
     * 原生分批批量插入（采用全局默认批次大小，受事务守护）
     *
     * @param entityList 待批量插入的实体数据集合
     * @return 累计成功插入影响的总行数
     */
    int insertBatchSomeColumn(Collection<T> entityList);

    /**
     * 原生分批批量插入（支持自定义单批次切片大小，受事务守护）
     *
     * @param entityList 待批量插入的实体数据集合
     * @param batchSize  单批次切片大小（小于等于 0 时采用默认配置）
     * @return 累计成功插入影响的总行数
     */
     int insertBatchSomeColumn(Collection<T> entityList, int batchSize);

    /**
     * 原生分批批量插入便捷门面（采用全局默认批次大小，受事务守护）
     *
     * @param entityList 待批量插入的实体数据集合
     * @return 累计成功插入影响的总行数
     */
    default int insertBatch(Collection<T> entityList) {
        return insertBatchSomeColumn(entityList);
    }

    /**
     * 原生分批批量插入便捷门面（支持自定义单批次切片大小，受事务守护）
     *
     * @param entityList 待批量插入的实体数据集合
     * @param batchSize  单批次切片大小（小于等于 0 时采用默认配置）
     * @return 累计成功插入影响的总行数
     */
    default int insertBatch(Collection<T> entityList, int batchSize) {
        return insertBatchSomeColumn(entityList, batchSize);
    }
}
