package io.github.atengk.boot.sample.service.impl;

import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.entity.BatchItemEntity;
import io.github.atengk.boot.sample.mapper.BatchItemMapper;
import io.github.atengk.boot.sample.service.BatchItemService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

/**
 * 示例批量操作业务实现类
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Service
public class BatchItemServiceImpl extends AtengServiceImpl<BatchItemMapper, BatchItemEntity> implements BatchItemService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void insertBatchWithException(Collection<BatchItemEntity> entityList, int batchSize) {
        insertBatch(entityList, batchSize);
        throw new IllegalStateException("Simulated exception after batch insert to test transaction rollback");
    }
}
