package io.github.atengk.boot.sample.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 加密业务转账/结算请求传输对象
 * 仅在 Controller 内部以解密后明文形式出现，杜绝 ORM 实体泄露
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Data
public class CryptoTransferDTO {

    /**
     * 业务流水号/订单号（支持作为 @Idempotent SpEL 幂等键）
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderNo;

    /**
     * 收款账户标识（敏感业务字段）
     */
    @NotBlank(message = "目标账号不能为空")
    private String accountNo;

    /**
     * 交易金额（必须大于 0）
     */
    @NotNull(message = "交易金额不能为空")
    @Positive(message = "交易金额必须大于 0")
    private BigDecimal amount;

    /**
     * 交易备注信息（受 XSS 反序列化安全清洗）
     */
    private String remark;
}
