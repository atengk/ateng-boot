package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 控制台管理员认证演练响应 VO（脱敏账号）
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
public class AuthVerifyVO {

    /**
     * 是否通过控制台身份认证
     */
    private boolean authenticated;

    /**
     * 脱敏处理后的用户名
     */
    private String maskedUsername;

    /**
     * 实际执行的认证提供者实现类名称
     */
    private String providerClass;

    /**
     * 认证结果说明文案
     */
    private String message;
}
