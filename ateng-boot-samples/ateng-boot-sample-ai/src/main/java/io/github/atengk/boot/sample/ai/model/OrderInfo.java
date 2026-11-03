package io.github.atengk.boot.sample.ai.model;

import java.math.BigDecimal;

/**
 * 订单详情领域模型
 *
 * @param orderId  订单号
 * @param buyerId  买家标识
 * @param skuId    商品编号
 * @param quantity 购买数量
 * @param amount   订单实付金额
 * @param status   订单当前状态
 *
 * @author Ateng
 * @since 2026-11-03
 */
public record OrderInfo(
        String orderId,
        String buyerId,
        String skuId,
        Integer quantity,
        BigDecimal amount,
        String status
) {}
