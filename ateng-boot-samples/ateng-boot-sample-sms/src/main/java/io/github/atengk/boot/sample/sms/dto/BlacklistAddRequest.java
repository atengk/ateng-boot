package io.github.atengk.boot.sample.sms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 黑名单添加请求传输对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlacklistAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标接收手机号
     */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^(\\+?86)?1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 拉黑原因
     */
    private String reason;
}
