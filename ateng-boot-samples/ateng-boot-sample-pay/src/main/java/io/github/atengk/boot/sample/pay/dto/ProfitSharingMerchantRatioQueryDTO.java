package io.github.atengk.boot.sample.pay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信特约子商户最大分账比例查询传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfitSharingMerchantRatioQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    @NotBlank(message = "特约子商户号不能为空")
    private String subMchid;
}
