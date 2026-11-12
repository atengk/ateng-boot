package io.github.atengk.boot.sample.smoke.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 冒烟会话登录请求 DTO
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmokeLoginRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID
     */
    @NotBlank(message = "userId 不能为空")
    private String userId;

    /**
     * 登录账号
     */
    @NotBlank(message = "userAccount 不能为空")
    private String userAccount;

    /**
     * 用户姓名
     */
    @NotBlank(message = "userName 不能为空")
    private String userName;

    /**
     * 所属租户 ID
     */
    @NotBlank(message = "tenantId 不能为空")
    private String tenantId;
}
