package io.github.atengk.boot.sample.pay.dto;

import io.github.atengk.boot.pay.enums.PayChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 财务对账单下载与解析查询参数
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayBillQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "支付渠道不能为空")
    private PayChannel channel;

    private String merchantKey;

    @NotBlank(message = "账单日期不能为空 (yyyy-MM-dd)")
    private String billDate;

    private String billType;

    private String tarType;

    /**
     * 微信特约商户号（服务商模式下载特约商户账单时填写）
     */
    private String subMchid;

    /**
     * 支付宝二级商户 smid
     */
    private String smid;
}
