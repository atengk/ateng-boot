package io.github.atengk.boot.sample.sms.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 验证码核验请求传输对象
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyCodeCheckRequest {

    /**
     * 目标接收手机号
     */
    @NotBlank(message = "手机号不能为空")
    private String phone;

    /**
     * 业务场景编码
     */
    @NotBlank(message = "业务类型不能为空")
    private String bizType;

    /**
     * 用户输入的待核验验证码
     */
    @NotBlank(message = "验证码不能为空")
    private String code;
}
