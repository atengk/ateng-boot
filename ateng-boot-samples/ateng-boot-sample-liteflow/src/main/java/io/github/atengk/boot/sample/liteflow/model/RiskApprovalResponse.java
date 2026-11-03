package io.github.atengk.boot.sample.liteflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 金融信贷风控审批结果
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskApprovalResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 申请人唯一标识
     */
    private String applicantId;

    /**
     * 是否通过审批
     */
    private boolean approved;

    /**
     * 最终核准授信额度（元）
     */
    private BigDecimal approvedAmount;

    /**
     * 综合风险等级 (LOW, MEDIUM, HIGH)
     */
    private String riskLevel;

    /**
     * 决策路由分支 (LOAN_PASS, MANUAL_REVIEW, LOAN_REJECT)
     */
    private String decision;

    /**
     * 决策原因说明
     */
    private String reason;

    /**
     * 规则执行轨迹摘要
     */
    private String traceSummary;
}
