package io.github.atengk.boot.sample.satoken.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 安全审计事件记录视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityEventVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事件类型 (LOGIN, LOGOUT, KICKOUT, REPLACED, DISABLE, UNTIE_DISABLE, OPEN_SAFE, CLOSE_SAFE)
     */
    private String eventType;

    /**
     * 账号体系标识
     */
    private String loginType;

    /**
     * 登录主体标识
     */
    private String loginId;

    /**
     * 会话凭据 Token
     */
    private String tokenValue;

    /**
     * 扩展详情描述
     */
    private String details;

    /**
     * 记录时间
     */
    private LocalDateTime timestamp;
}
