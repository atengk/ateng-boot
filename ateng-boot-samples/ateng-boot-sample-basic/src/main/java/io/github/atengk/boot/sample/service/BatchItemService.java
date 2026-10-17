package io.github.atengk.boot.sample.service;

import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.entity.BatchItemEntity;

import java.util.Collection;

/**
 * 示例批量操作业务接口
 * 继承 AtengBaseService 获得原生高性能分批批量插入能力
 *
 * @author Ateng
 * @since 2026-10-16
 */
public interface BatchItemService extends AtengBaseService<BatchItemEntity> {

    /**
     * 模拟在插入后抛出异常以验证事务原子回滚
     *
     * @param entityList 实体列表
     * @param batchSize 批次大小
     */
    void insertBatchWithException(Collection<BatchItemEntity> entityList, int batchSize);
}
