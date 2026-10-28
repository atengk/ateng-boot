package io.github.atengk.boot.sample.pay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 资金分账解冻传输对象
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfitSharingUnfreezeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    @NotBlank(message = "微信支付订单号不能为空")
    private String transactionId;

    @NotBlank(message = "商户解冻分账单号不能为空")
    private String outOrderNo;

    @NotBlank(message = "解冻原因描述不能为空")
    private String description;
}
