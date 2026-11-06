package io.github.atengk.boot.sample.liteflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 促销决策路由匹配响应结果
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionMatchResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 结算用户标识
     */
    private String userId;

    /**
     * 用户会员等级
     */
    private String userLevel;

    /**
     * 购物车原始金额
     */
    private BigDecimal originalAmount;

    /**
     * 累加优惠减免总金额
     */
    private BigDecimal totalDiscount;

    /**
     * 最终应付金额
     */
    private BigDecimal finalAmount;

    /**
     * 匹配命中的促销规则名称列表
     */
    private List<String> appliedPromotions;

    /**
     * 匹配命中的流程链标识列表
     */
    private List<String> matchedChainIds;

    /**
     * 命中规则总数量
     */
    private int matchedCount;

    /**
     * 促销决策过程审计记录列表
     */
    private List<String> auditLogs;

    /**
     * 决策路由整体执行耗时（毫秒）
     */
    private long totalDurationMs;
}
