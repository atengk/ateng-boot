package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户口令安全哈希、校验与平滑升级演示请求对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordCheckRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务账号名
     */
    @NotBlank(message = "账号名不能为空")
    private String username;

    /**
     * 原始明文口令
     */
    @NotBlank(message = "原始口令不能为空")
    private String rawPassword;

    /**
     * 模拟存量低迭代基线次数（用于演示登录后平滑升级判定，默认 100000）
     */
    @Min(value = 10000, message = "存量迭代次数不能低于 10000")
    @Max(value = 5000000, message = "存量迭代次数不能高于 5000000")
    private Integer legacyIterations;
}
