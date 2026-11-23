package io.github.atengk.boot.social.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.dromara.anduin.model.AuthCallback;

import java.io.Serializable;

/**
 * 个人中心已登录态直接绑定第三方社交账号请求契约
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialDirectBindRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前已登录的本地系统用户唯一标识
     */
    @NotBlank(message = "本地用户标识 userId 不能为空")
    private String userId;

    /**
     * 第三方平台返回的授权凭据 code（如微信小程序 js_code、OAuth2 回调换码 code 等）
     */
    private String code;

    /**
     * 防伪状态码 State（可选，Web 重定向场景用于防伪校验与业务上下文恢复）
     */
    private String state;

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
     * JSR-303 组合凭证非空校验：code、authCode、oauthToken 至少提供一项
     *
     * @return true 表示至少携带一项有效授权凭据
     */
    @JsonIgnore
    @AssertTrue(message = "授权凭证 (code / authCode / oauthToken) 不能为空")
    public boolean isCredentialPresent() {
        return SocialDirectCallbackSupport.isCredentialPresent(code, authCode, oauthToken);
    }

    /**
     * 是否携带了除普通 code 外的平台扩展回调字段
     *
     * @return true 表示包含扩展回调字段
     */
    @JsonIgnore
    public boolean hasExtendedCallbackFields() {
        return SocialDirectCallbackSupport.hasExtendedCallbackFields(authCode, oauthToken, oauthVerifier, user);
    }

    /**
     * 转换为底层 Anduin 标准 AuthCallback 回调模型
     *
     * @return AuthCallback 实例
     */
    public AuthCallback toAuthCallback() {
        return SocialDirectCallbackSupport.toAuthCallback(code, state, authCode, oauthToken, oauthVerifier, user);
    }
}
