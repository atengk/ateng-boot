package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户信息局部更新请求载荷 (HTTP PATCH)
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPatchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户名（为空时不修改）
     */
    private String username;

    /**
     * 角色标识（为空时不修改）
     */
    private String role;

    /**
     * 账户状态（为空时不修改）
     */
    private String status;

    /**
     * 邮箱地址（为空时不修改）
     */
    private String email;
}
