package io.github.atengk.boot.sample.sms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 验证码获取请求传输对象
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendVerifyCodeRequest {

    /**
     * 目标接收手机号
     */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^(\\+?86)?1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 业务场景编码 (如 login, register, reset_pwd, bind)
     */
    @NotBlank(message = "业务类型不能为空")
    private String bizType;
}
