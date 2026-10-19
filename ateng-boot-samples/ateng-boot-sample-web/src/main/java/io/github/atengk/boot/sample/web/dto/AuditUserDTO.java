package io.github.atengk.boot.sample.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 操作审计场景请求传输载荷
 * 包含密码等敏感字段，验证切面参数排除与脱敏机制
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
public class AuditUserDTO {

    /**
     * 用户登录账号
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 敏感密码凭据（用于验证日志排除）
     */
    private String password;

    /**
     * 电子邮箱
     */
    private String email;

    /**
     * 联系电话
     */
    private String phone;
}
