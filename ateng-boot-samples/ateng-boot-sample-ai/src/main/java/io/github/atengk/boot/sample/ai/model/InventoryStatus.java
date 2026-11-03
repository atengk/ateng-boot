package io.github.atengk.boot.sample.ai.model;

/**
 * 库存状态领域模型
 *
 * @param skuId          商品编号
 * @param inStock        是否有库存
 * @param availableStock 可用库存数
 * @param warehouse      归属智能仓储节点
 *
 * @author Ateng
 * @since 2026-11-03
 */
public record InventoryStatus(
        String skuId,
        boolean inStock,
        Integer availableStock,
        String warehouse
) {}
