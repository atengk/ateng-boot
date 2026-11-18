package io.github.atengk.boot.sample.pay.dto;

import io.github.atengk.boot.pay.enums.PayChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 单笔资金转账传输对象
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayTransferDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "支付渠道不能为空")
    private PayChannel channel;

    private String merchantKey;

    @NotBlank(message = "商户转账单号不能为空")
    private String outBizNo;

    @NotNull(message = "转账金额不能为空")
    @DecimalMin(value = "0.01", message = "转账金额必须大于 0 元")
    private BigDecimal amount;

    @NotBlank(message = "转账标题不能为空")
    private String title;

    @NotBlank(message = "收款人账号不能为空")
    private String payeeAccount;

    private String payeeRealName;

    /**
     * 转账备注（可选）
     */
    private String remark;

    /**
     * 收款方身份标识类型（支付宝可选：ALIPAY_LOGON_ID / ALIPAY_USER_ID 等）
     */
    private String payeeIdentityType;

    /**
     * 异步结果通知回调地址（可选）
     */
    private String notifyUrl;

    /**
     * 转账场景 ID（微信新版商家转账必填，如 1000）
     */
    private String transferSceneId;

    /**
     * 转账场景报备信息列表（微信新版商家转账）
     */
    @Valid
    private List<PayTransferSceneReportInfoDTO> transferSceneReportInfos;

    /**
     * 是否退回旧版微信商家批次转账（默认 false 走新版用户确认模式）
     */
    private Boolean legacyBatchMode;
}
