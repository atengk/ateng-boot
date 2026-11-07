package io.github.atengk.boot.social.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.dromara.anduin.model.AuthToken;

import java.io.Serializable;
import java.time.Duration;

/**
 * 标准化第三方社交平台授权令牌领域模型
 * <p>
 * 封装 OAuth2 访问令牌（accessToken）、刷新令牌（refreshToken）、有效时长（expireIn）
 * 及跨端标识（openId、unionId），并提供原始底层 Token 凭据快照与双向转换工厂。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialToken implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 访问令牌 (Access Token)
     */
    private String accessToken;

    /**
     * 刷新令牌 (Refresh Token)
     */
    private String refreshToken;

    /**
     * 访问令牌有效时长（单位：秒，遵循 OAuth2 规范与 Anduin 原始契约；应用侧建议优先使用 {@link #getExpireDuration()} 获取强类型对象）
     */
    private int expireIn;

    /**
     * 第三方应用级用户唯一标识（如微信 openid、GitHub user id 等）
     */
    private String openId;

    /**
     * 第三方开发者平台级用户唯一标识（如微信 unionid）
     */
    private String unionId;

    /**
     * 授权作用域
     */
    private String scope;

    /**
     * 原始第三方授权 Token 凭据快照（如 Anduin AuthToken 原生对象）
     */
    private Object rawToken;

    /**
     * 从 Dromara Anduin 原生 AuthToken 转换为标准化 SocialToken 领域模型
     *
     * @param authToken Anduin 授权令牌对象
     * @return 标准化 SocialToken；若 authToken 为 null 则返回 null
     */
    public static SocialToken fromAuthToken(AuthToken authToken) {
        if (authToken == null) {
            return null;
        }
        return SocialToken.builder()
                .accessToken(authToken.getAccessToken())
                .refreshToken(authToken.getRefreshToken())
                .expireIn(authToken.getExpireIn())
                .openId(authToken.getOpenId())
                .unionId(authToken.getUnionId())
                .scope(authToken.getScope())
                .rawToken(authToken)
                .build();
    }

    /**
     * 转换为 Dromara Anduin 原生 AuthToken 对象
     *
     * @return 原生 AuthToken 对象
     */
    public AuthToken toAuthToken() {
        return AuthToken.builder()
                .accessToken(this.accessToken)
                .refreshToken(this.refreshToken)
                .expireIn(this.expireIn)
                .openId(this.openId)
                .unionId(this.unionId)
                .scope(this.scope)
                .build();
    }

    /**
     * 获取访问令牌有效时长 Duration 抽象
     *
     * @return 访问令牌有效时长 Duration
     */
    public Duration getExpireDuration() {
        return Duration.ofSeconds(this.expireIn);
    }
}
