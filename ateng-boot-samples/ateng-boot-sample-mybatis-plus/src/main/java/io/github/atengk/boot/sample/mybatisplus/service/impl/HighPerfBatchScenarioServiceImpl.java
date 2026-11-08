package io.github.atengk.boot.sample.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.sample.mybatisplus.entity.ProductSkuEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.ProductSkuMapper;
import io.github.atengk.boot.sample.mybatisplus.model.BatchSkuDTO;
import io.github.atengk.boot.sample.mybatisplus.model.ProductSkuVO;
import io.github.atengk.boot.sample.mybatisplus.service.HighPerfBatchScenarioService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原生高性能批处理、流式游标、并发锁与全表防卫业务场景服务实现类
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Service
public class HighPerfBatchScenarioServiceImpl extends AtengServiceImpl<ProductSkuMapper, ProductSkuEntity> implements HighPerfBatchScenarioService {

    @Transactional(rollbackFor = Exception.class)
    @Override
    public List<ProductSkuVO> insertBatchSkus(List<BatchSkuDTO> dtoList) {
        if (CollectionUtil.isEmpty(dtoList)) {
            return Collections.emptyList();
        }
        List<ProductSkuEntity> entityList = new ArrayList<>(dtoList.size());
        for (BatchSkuDTO dto : dtoList) {
            ProductSkuEntity entity = new ProductSkuEntity();
            entity.setId(dto.getId());
            entity.setSkuCode(dto.getSkuCode());
            entity.setSkuName(dto.getSkuName());
            entity.setCategory(dto.getCategory() != null ? dto.getCategory() : "默认类目");
            entity.setPrice(dto.getPrice());
            entityList.add(entity);
        }

        // 调用 AtengBaseService 原生单条多值批量插入（内部由 BatchUtil 自动自愈 ID、审计字段并切片执行）
        insertBatch(entityList);

        return entityList.stream()
                .map(ProductSkuVO::fromEntity)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int upsertBatchSkus(List<BatchSkuDTO> dtoList) {
        if (CollectionUtil.isEmpty(dtoList)) {
            return 0;
        }
        List<ProductSkuEntity> entityList = new ArrayList<>(dtoList.size());
        for (BatchSkuDTO dto : dtoList) {
            ProductSkuEntity entity = new ProductSkuEntity();
            entity.setId(dto.getId());
            entity.setSkuCode(dto.getSkuCode());
            entity.setSkuName(dto.getSkuName());
            entity.setCategory(dto.getCategory() != null ? dto.getCategory() : "默认类目");
            entity.setPrice(dto.getPrice());
            entityList.add(entity);
        }

        // 调用 AtengBaseService 双方言批量 Upsert（自适应 MySQL ON DUPLICATE KEY UPDATE / PostgreSQL ON CONFLICT）
        return upsertBatch(entityList);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public List<ProductSkuVO> updateBatchSkus(List<BatchSkuDTO> dtoList) {
        if (CollectionUtil.isEmpty(dtoList)) {
            return Collections.emptyList();
        }
        List<ProductSkuEntity> entityList = new ArrayList<>(dtoList.size());
        List<Long> ids = new ArrayList<>(dtoList.size());
        for (BatchSkuDTO dto : dtoList) {
            ProductSkuEntity entity = new ProductSkuEntity();
            entity.setId(dto.getId());
            entity.setSkuCode(dto.getSkuCode());
            entity.setSkuName(dto.getSkuName());
            entity.setCategory(dto.getCategory());
            entity.setPrice(dto.getPrice());
            entityList.add(entity);
            if (dto.getId() != null) {
                ids.add(dto.getId());
            }
        }

        // 调用 AtengBaseService updateBatch 批量更新（内部由 AntiDeadlockSqlSource 强制主键升序排列与 CASE WHEN 空值保护）
        updateBatch(entityList);

        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<ProductSkuEntity> updated = listByIds(ids);
        return updated.stream()
                .map(ProductSkuVO::fromEntity)
                .toList();
    }

    @Override
    public Map<String, Object> streamExportSkus(int batchSize) {
        ensureSampleSkusIfEmpty();

        int effectiveBatchSize = batchSize > 0 ? batchSize : 2;
        AtomicInteger totalCount = new AtomicInteger(0);
        AtomicInteger batchCount = new AtomicInteger(0);
        List<List<String>> batchSummaries = new ArrayList<>();

        // 使用受管流式游标分批导出，自动开启只读事务并使用 try-with-resources 闭合释放 JDBC 物理连接
        getStreamingQueryTemplate().forEachBatch(
                () -> getBaseMapper().selectCursor(Wrappers.emptyWrapper()),
                effectiveBatchSize,
                batch -> {
                    batchCount.incrementAndGet();
                    totalCount.addAndGet(batch.size());
                    List<String> skuCodes = batch.stream().map(ProductSkuEntity::getSkuCode).toList();
                    batchSummaries.add(skuCodes);
                }
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchSize", effectiveBatchSize);
        result.put("totalExported", totalCount.get());
        result.put("batchCount", batchCount.get());
        result.put("batches", batchSummaries);
        return result;
    }

    @Override
    public List<ProductSkuVO> streamWhileSkus(int limit) {
        ensureSampleSkusIfEmpty();

        int effectiveLimit = limit > 0 ? limit : 3;
        List<ProductSkuVO> collected = new ArrayList<>();

        LambdaQueryWrapper<ProductSkuEntity> query = Wrappers.lambdaQuery(ProductSkuEntity.class)
                .orderByAsc(ProductSkuEntity::getId);

        // 使用 forEachWhile 执行带 Predicate 提前短路退出的受管游标检索
        getStreamingQueryTemplate().forEachWhile(
                () -> getBaseMapper().selectCursor(query),
                sku -> {
                    collected.add(ProductSkuVO.fromEntity(sku));
                    return collected.size() < effectiveLimit; // 返回 false 时提前终止游标遍历
                }
        );

        return collected;
    }

    @Override
    public ProductSkuVO deductSkuPriceWithOptimisticRetry(Long skuId, BigDecimal deductAmount) {
        Objects.requireNonNull(skuId, "skuId 不能为空");
        Objects.requireNonNull(deductAmount, "deductAmount 不能为空");

        // 使用 updateWithOptimisticRetry 执行带 CAS 乐观锁自旋重试的实体更新
        ProductSkuEntity updated = updateWithOptimisticRetry(skuId, sku -> {
            BigDecimal currentPrice = sku.getPrice() != null ? sku.getPrice() : BigDecimal.ZERO;
            sku.setPrice(currentPrice.subtract(deductAmount));
        });

        return ProductSkuVO.fromEntity(updated);
    }

    @Override
    public Map<String, Object> triggerBlockAttackFullTableUpdate() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 尝试发起无 WHERE 条件的全表更新
            UpdateWrapper<ProductSkuEntity> emptyUpdate = new UpdateWrapper<>();
            emptyUpdate.set("price", new BigDecimal("9999.00"));
            update(null, emptyUpdate);

            result.put("blocked", false);
            result.put("message", "未被拦截（可能未启用 BlockAttackInnerInterceptor）");
        } catch (Exception ex) {
            log.info("成功触发 BlockAttackInnerInterceptor 防全表更新拦截: {}", ex.getMessage());
            result.put("blocked", true);
            result.put("exceptionType", ex.getClass().getSimpleName());
            result.put("message", ex.getMessage() != null ? ex.getMessage() : "Prohibition of table update operation");
            result.put("protectionEngine", "BlockAttackInnerInterceptor");
        }
        return result;
    }

    private void ensureSampleSkusIfEmpty() {
        if (count() == 0) {
            List<ProductSkuEntity> seedList = List.of(
                    new ProductSkuEntity("SKU_SAMPLE_101", "机械键盘 Pro"),
                    new ProductSkuEntity("SKU_SAMPLE_102", "无线鼠标 Master"),
                    new ProductSkuEntity("SKU_SAMPLE_103", "4K 显示器 Ultra"),
                    new ProductSkuEntity("SKU_SAMPLE_104", "降噪耳机 Quiet"),
                    new ProductSkuEntity("SKU_SAMPLE_105", "人体工学椅 Ergo")
            );
            insertBatch(seedList);
        }
    }
}
