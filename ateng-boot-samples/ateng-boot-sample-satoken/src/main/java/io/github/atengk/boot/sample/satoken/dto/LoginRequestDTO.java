package io.github.atengk.boot.sample.satoken.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;

/**
 * 用户会话登录请求传输对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户唯一标识
     */
    @NotBlank(message = "用户标识不能为空")
    private String userId;

    /**
     * 登录账号
     */
    private String username;

    /**
     * 租户标识
     */
    private String tenantId;

    /**
     * 登录设备端类型（如 PC, APP, MINI_PROGRAM, H5）
     */
    private String device;

    /**
     * 拥有的角色列表
     */
    private Set<String> roles;

    /**
     * 拥有的细粒度权限列表
     */
    private Set<String> permissions;
}
