package io.github.atengk.boot.sample.liteflow.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 金融信贷风控审批请求参数
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class RiskApprovalRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 申请人唯一标识
     */
    @NotBlank(message = "申请人标识 (applicantId) 不能为空")
    private String applicantId;

    /**
     * 申请人身份证号
     */
    @NotBlank(message = "身份证号 (idCard) 不能为空")
    private String idCard;

    /**
     * 申请授信额度（元）
     */
    @NotNull(message = "申请额度 (applyAmount) 不能为空")
    @DecimalMin(value = "100.00", message = "申请额度不得低于 100 元")
    private BigDecimal applyAmount;

    /**
     * 外部征信基础分 (300-850)
     */
    @NotNull(message = "征信评分 (creditScore) 不能为空")
    private Integer creditScore;
}
