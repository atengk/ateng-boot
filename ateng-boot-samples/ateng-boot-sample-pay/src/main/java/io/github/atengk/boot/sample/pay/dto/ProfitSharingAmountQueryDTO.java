package io.github.atengk.boot.sample.pay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信订单剩余待分金额查询传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfitSharingAmountQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    @NotBlank(message = "微信支付订单号不能为空")
    private String transactionId;
}
