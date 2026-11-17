package io.github.atengk.boot.sample.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 图形验证码独立核验请求载荷
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
public class CaptchaVerifyDTO {

    /**
     * 图形验证码唯一键
     */
    @NotBlank(message = "图形验证码键不能为空")
    private String captchaKey;

    /**
     * 用户输入的验证码内容
     */
    @NotBlank(message = "图形验证码不能为空")
    private String captchaCode;
}
