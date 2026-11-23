package io.github.atengk.boot.sample.rocketmq.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 触发样本毒丸消息投递请求 DTO
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Data
public class DeadLetterPoisonPillRequest {

    /**
     * 毒丸触发模式：FATAL（致命业务异常）、VALIDATION（JSR-303 校验失败）、
     * MALFORMED_JSON（非法 JSON 文本）、CORRUPTED_GZIP（损坏 GZIP 二进制字节）
     */
    @Pattern(regexp = "FATAL|VALIDATION|MALFORMED_JSON|CORRUPTED_GZIP",
            message = "毒丸模式仅支持 FATAL / VALIDATION / MALFORMED_JSON / CORRUPTED_GZIP")
    private String mode = "FATAL";

    /**
     * 可选订单编号，默认按模式生成语义化毒丸标识
     */
    private String orderId;

    /**
     * 可选金额
     */
    @DecimalMin(value = "0.00", message = "订单金额不能为负数")
    private BigDecimal amount;

    /**
     * 可选自定义原始载荷（用于 MALFORMED_JSON 等场景覆盖默认畸形内容）
     */
    private String rawPayload;
}
