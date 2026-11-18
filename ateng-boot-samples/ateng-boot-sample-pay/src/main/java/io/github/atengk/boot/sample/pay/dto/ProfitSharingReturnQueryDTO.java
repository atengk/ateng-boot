package io.github.atengk.boot.sample.pay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信分账回退结果查询传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfitSharingReturnQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    private String subMchid;

    @NotBlank(message = "商户分账回退单号不能为空")
    private String outReturnNo;

    @NotBlank(message = "原商户分账单号不能为空")
    private String outOrderNo;
}
