package io.github.atengk.boot.sample.satoken.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 防爆破登录请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefenseLoginRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 登录账号
     */
    @NotBlank(message = "账号不能为空")
    private String account;

    /**
     * 登录密码
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 客户端 IP（可选，留空时自动嗅探上下文）
     */
    private String clientIp;

    /**
     * 所属租户标识（可选，非空时启用多租户作用域隔离防爆破）
     */
    private String tenantId;

    /**
     * 账号体系标识（可选，如 admin、member；为空时默认 login）
     */
    private String loginType;

    /**
     * 兼容保留原有三参构造器
     */
    public DefenseLoginRequestDTO(String account, String password, String clientIp) {
        this.account = account;
        this.password = password;
        this.clientIp = clientIp;
    }
}
