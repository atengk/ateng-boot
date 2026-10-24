package io.github.atengk.boot.sample.pay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 资金分账接收方传输对象
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfitSharingReceiverDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    @NotBlank(message = "分账接收方类型不能为空 (MERCHANT_ID / PERSONAL_OPENID)")
    private String type;

    @NotBlank(message = "分账接收方账号不能为空")
    private String account;

    private String name;

    @NotBlank(message = "与分账方关系类型不能为空")
    private String relationType;
}
