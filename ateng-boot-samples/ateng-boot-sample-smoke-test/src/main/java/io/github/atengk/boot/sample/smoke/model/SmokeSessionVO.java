package io.github.atengk.boot.sample.smoke.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话与上下文协同视图 VO
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmokeSessionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否已登录
     */
    private boolean loggedIn;

    /**
     * 登录主体用户 ID
     */
    private String userId;

    /**
     * 登录账号
     */
    private String userAccount;

    /**
     * 用户名称
     */
    private String userName;

    /**
     * 登录主体租户 ID
     */
    private String tenantId;

    /**
     * Token 请求头名
     */
    private String tokenName;

    /**
     * Token 凭据值
     */
    private String tokenValue;

    /**
     * 通过 Core 上下文门面 (UserContextHolder) 解析到的当前操作人 ID
     */
    private String resolvedContextUserId;

    /**
     * 通过 Core 上下文门面 (TenantContextHolder) 解析到的当前租户 ID
     */
    private String resolvedContextTenantId;
}
