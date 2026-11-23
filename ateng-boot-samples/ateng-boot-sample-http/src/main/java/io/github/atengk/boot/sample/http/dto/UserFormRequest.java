package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户表单提交请求对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFormRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户登录名
     */
    private String username;

    /**
     * 用户邮箱
     */
    private String email;

    /**
     * 业务角色标识
     */
    private String role;
}
