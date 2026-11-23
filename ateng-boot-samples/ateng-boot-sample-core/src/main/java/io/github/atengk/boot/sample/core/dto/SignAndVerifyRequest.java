package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 国密 HmacSM3 与标准 HMAC 签名/恒定时间验签请求对象
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignAndVerifyRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待签名业务载荷文本
     */
    @NotBlank(message = "待签名载荷不能为空")
    private String payload;

    /**
     * 签名密钥文本
     */
    @NotBlank(message = "签名密钥不能为空")
    private String secretKey;
}
