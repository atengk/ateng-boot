package io.github.atengk.boot.sample.liteflow.context;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 促销决策路由共享业务上下文
 * 承载用户、购物车金额与品类信息，在多条决策路由规则（Route Chain）流转中连续累积命中优惠与审计轨迹
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
public class PromotionContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户唯一标识
     */
    private String userId;

    /**
     * 用户等级（如 VIP, REGULAR）
     */
    private String userLevel;

    /**
     * 购物车原始实付金额
     */
    private BigDecimal cartAmount = BigDecimal.ZERO;

    /**
     * 结算商品分类（如 DIGITAL, CLOTHING, FOOD）
     */
    private String category;

    /**
     * 累积总优惠减免金额
     */
    private BigDecimal totalDiscount = BigDecimal.ZERO;

    /**
     * 最终应付金额
     */
    private BigDecimal finalAmount = BigDecimal.ZERO;

    /**
     * 已成功命中的促销活动名称列表
     */
    private List<String> appliedPromotions = new ArrayList<>();

    /**
     * 决策执行流转审计日志列表
     */
    private List<String> auditLogs = new ArrayList<>();

    /**
     * 累加优惠减免并重新测算最终金额
     *
     * @param promotionName  促销规则名称
     * @param discountAmount 本次优惠减免金额
     * @param reason         优惠生效原由
     */
    public synchronized void applyDiscount(String promotionName, BigDecimal discountAmount, String reason) {
        if (discountAmount == null || discountAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        this.appliedPromotions.add(promotionName);
        this.totalDiscount = this.totalDiscount.add(discountAmount);
        this.finalAmount = this.cartAmount.subtract(this.totalDiscount).max(BigDecimal.ZERO);
        this.auditLogs.add(String.format("命中优惠 [%s]: 减免金额 ¥%s, 原因: %s, 剩余应付: ¥%s",
                promotionName, discountAmount, reason, this.finalAmount));
    }
}
