package io.github.atengk.boot.sample.liteflow.context;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 金融信贷风控审批业务上下文
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class RiskApprovalContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 申请人唯一标识
     */
    private String applicantId;

    /**
     * 申请人身份证号
     */
    private String idCard;

    /**
     * 申请授信额度（元）
     */
    private BigDecimal applyAmount;

    /**
     * 外部征信基础分
     */
    private Integer creditScore;

    /**
     * 是否命中黑名单
     */
    private boolean blacklisted;

    /**
     * 是否通过审批
     */
    private boolean approved;

    /**
     * 反欺诈多维评分 (0-100，越高风险越高)
     */
    private int antiFraudScore;

    /**
     * 综合风险等级 (LOW, MEDIUM, HIGH)
     */
    private String riskLevel;

    /**
     * 决策路由分支 (LOAN_PASS, MANUAL_REVIEW, LOAN_REJECT)
     */
    private String decision;

    /**
     * 最终核准授信额度（元）
     */
    private BigDecimal approvedAmount = BigDecimal.ZERO;

    /**
     * 决策原因说明
     */
    private String decisionReason;
}
