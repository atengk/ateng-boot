package io.github.atengk.boot.social.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.dromara.anduin.model.AuthCallback;

import java.io.Serializable;

/**
 * 社交凭证直传登录请求对象
 * <p>
 * 适用于微信小程序 (wx.login code2Session)、QQ 小程序或 App 原生 SDK 授权码（含支付宝 auth_code、
 * Twitter OAuth 1.0a oauth_token/oauth_verifier、Apple user 载荷）直传后端换码登录。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialDirectLoginRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 授权凭证 code（小程序 js_code 或标准 OAuth2 App 授权凭据）
     */
    private String code;

    /**
     * 支付宝等平台专用授权码 (auth_code，可选)
     */
    private String authCode;

    /**
     * OAuth 1.0a 平台（如 Twitter）回调令牌 (oauth_token，可选)
     */
    private String oauthToken;

    /**
     * OAuth 1.0a 平台（如 Twitter）回调校验码 (oauth_verifier，可选)
     */
    private String oauthVerifier;

    /**
     * Apple 登录首次授权回调附带的用户信息 JSON 字符串 (user，可选)
     */
    private String user;

    /**
     * 客户端设备类型（如 "PC", "MOBILE", "MINI_PROGRAM"）
     */
    private String deviceType;

    /**
     * 业务登录类型/账号标识（多账号体系隔离）
     */
    private String loginType;

    /**
     * JSR-303 组合凭证非空校验：code、authCode、oauthToken 至少提供一项
     *
     * @return true 表示至少携带一项有效授权凭据
     */
    @JsonIgnore
    @AssertTrue(message = "授权凭证 (code / authCode / oauthToken) 不能为空")
    public boolean isCredentialPresent() {
        return hasText(code) || hasText(authCode) || hasText(oauthToken);
    }

    /**
     * 是否携带了除普通 code 外的平台扩展回调字段
     *
     * @return true 表示包含扩展回调字段
     */
    @JsonIgnore
    public boolean hasExtendedCallbackFields() {
        return hasText(authCode) || hasText(oauthToken) || hasText(oauthVerifier) || hasText(user);
    }

    /**
     * 转换为底层 Anduin 标准 AuthCallback 回调模型
     *
     * @return AuthCallback 实例
     */
    public AuthCallback toAuthCallback() {
        return AuthCallback.builder()
                .code(code)
                .auth_code(authCode)
                .oauth_token(oauthToken)
                .oauth_verifier(oauthVerifier)
                .user(user)
                .build();
    }

    private static boolean hasText(String str) {
        return str != null && !str.isBlank();
    }
}
