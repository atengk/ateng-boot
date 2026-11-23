package io.github.atengk.boot.sample.pay.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 微信分账请求下单传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfitSharingCreateOrderDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    private String subMchid;

    @NotBlank(message = "微信支付订单号不能为空")
    private String transactionId;

    @NotBlank(message = "商户分账单号不能为空")
    private String outOrderNo;

    private Boolean unfreezeUnsplit;

    @Valid
    @NotEmpty(message = "分账接收方明细列表不能为空")
    private List<ReceiverItem> receivers;

    /**
     * 微信分账接收方金额明细项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReceiverItem implements Serializable {

        private static final long serialVersionUID = 1L;

        @NotBlank(message = "接收方类型不能为空")
        private String type;

        @NotBlank(message = "接收方账号不能为空")
        private String account;

        @NotNull(message = "分账金额(分)不能为空")
        @Min(value = 1, message = "分账金额至少为 1 分")
        private Long amount;

        @NotBlank(message = "分账描述不能为空")
        private String description;
    }
}
