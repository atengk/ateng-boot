package io.github.atengk.boot.sample.pay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信分账接收方删除请求传输对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfitSharingDeleteReceiverDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String merchantKey;

    @NotBlank(message = "分账接收方类型不能为空 (MERCHANT_ID / PERSONAL_OPENID)")
    private String type;

    @NotBlank(message = "分账接收方账号不能为空")
    private String account;
}
