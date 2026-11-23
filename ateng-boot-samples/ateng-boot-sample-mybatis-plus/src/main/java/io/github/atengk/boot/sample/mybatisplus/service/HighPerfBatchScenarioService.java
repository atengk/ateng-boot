package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.mybatisplus.entity.ProductSkuEntity;
import io.github.atengk.boot.sample.mybatisplus.model.BatchSkuDTO;
import io.github.atengk.boot.sample.mybatisplus.model.ProductSkuVO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 原生高性能批处理、受管流式游标、并发锁与全表防卫业务场景服务契约
 * 演示：
 * 1. BatchUtil 原生多值批量插入（insertBatch / insertBatchSomeColumn）
 * 2. 双方言原生批量 Upsert 增强（upsertBatch / @ConflictKeys 原地更新）
 * 3. 乱序主键升序重排防死锁批量更新（updateBatch 配合 CASE WHEN 空值保护）
 * 4. StreamingQueryTemplate 受管游标流式分批与提前短路消费
 * 5. OptimisticLockTemplate / updateWithOptimisticRetry 乐观锁并发自旋扣减
 * 6. BlockAttackInnerInterceptor 防全表更新删除拦截
 *
 * @author Ateng
 * @since 2026-11-08
 */
public interface HighPerfBatchScenarioService extends AtengBaseService<ProductSkuEntity> {

    /**
     * 原生单条多值批量插入商品 SKU
     *
     * @param dtoList SKU 数据传输对象列表
     * @return 成功插入的商品 SKU 视图对象列表（零实体泄露）
     */
    List<ProductSkuVO> insertBatchSkus(List<BatchSkuDTO> dtoList);

    /**
     * 双方言原生批量 Upsert 商品 SKU（基于 @ConflictKeys 复合唯一键冲突原地更新）
     *
     * @param dtoList SKU 数据传输对象列表
     * @return 累计受影响行数
     */
    int upsertBatchSkus(List<BatchSkuDTO> dtoList);

    /**
     * 乱序主键升序重排防死锁批量更新（基于单条 CASE WHEN 动态选择性更新与空值保护）
     *
     * @param dtoList SKU 数据传输对象列表（允许乱序主键与部分为 null 字段）
     * @return 更新后的商品 SKU 视图列表
     */
    List<ProductSkuVO> updateBatchSkus(List<BatchSkuDTO> dtoList);

    /**
     * 受管游标流式分批导出商品 SKU（防 JVM 内存 OOM 与数据库连接悬挂）
     *
     * @param batchSize 单批切片大小
     * @return 流式分批导出执行摘要（总数、批次等）
     */
    Map<String, Object> streamExportSkus(int batchSize);

    /**
     * 受管游标流式条件消费（支持 Predicate 提前短路退出）
     *
     * @param limit 最大消费条数限制
     * @return 提前短路拉取到的商品 SKU 视图列表
     */
    List<ProductSkuVO> streamWhileSkus(int limit);

    /**
     * 乐观锁并发自旋重试降价/扣减
     *
     * @param skuId        商品 SKU 主键 ID
     * @param deductAmount 扣减金额
     * @return 扣减更新后的商品 SKU 视图对象（包含递增后的 version 版本号）
     */
    ProductSkuVO deductSkuPriceWithOptimisticRetry(Long skuId, BigDecimal deductAmount);

    /**
     * 触发全表无条件更新以演示 BlockAttackInnerInterceptor 防全表更新拦截
     *
     * @return 拦截诊断结果元数据
     */
    Map<String, Object> triggerBlockAttackFullTableUpdate();
}
