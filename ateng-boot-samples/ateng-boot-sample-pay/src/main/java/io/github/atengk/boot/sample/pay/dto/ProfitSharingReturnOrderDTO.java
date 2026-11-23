package io.github.atengk.boot.sample.pay.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信分账回退请求传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfitSharingReturnOrderDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    private String subMchid;

    @NotBlank(message = "原商户分账单号不能为空")
    private String outOrderNo;

    @NotBlank(message = "商户分账回退单号不能为空")
    private String outReturnNo;

    @NotBlank(message = "回退商户号不能为空")
    private String returnMchid;

    @NotNull(message = "回退金额(分)不能为空")
    @Min(value = 1, message = "回退金额至少为 1 分")
    private Long amount;

    @NotBlank(message = "回退原因描述不能为空")
    private String description;
}
