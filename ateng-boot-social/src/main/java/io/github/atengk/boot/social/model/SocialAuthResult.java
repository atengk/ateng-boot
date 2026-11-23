package io.github.atengk.boot.social.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 社交登录统一认证结果领域模型
 * <p>
 * 聚合第三方授权用户画像、已绑定的本地用户标识与会话凭据，或未绑定时签发的两阶段绑定票据与深链上下文。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialAuthResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 认证流转结果状态（LOGGED_IN: 已签发登录会话；REQUIRE_BIND: 需引导两阶段绑定）
     */
    private SocialAuthState state;

    /**
     * 绑定的本地用户唯一标识（已登录状态下非空）
     */
    private String userId;

    /**
     * 签发的登录会话凭据（如 SaTokenInfo、JWT 或 Token 字符串，已登录状态下存在）
     */
    private Object tokenInfo;

    /**
     * 两阶段安全绑定票据（待绑定状态下非空，具备高熵与阅后即焚防重放特性）
     */
    private String bindTicket;

    /**
     * 第三方标准化用户画像
     */
    private SocialUser socialUser;

    /**
     * 还原的深链与多租户业务上下文
     */
    private StateContext stateContext;

    /**
     * 构建已成功登录的认证结果
     *
     * @param userId       本地用户唯一标识
     * @param tokenInfo    会话凭据对象
     * @param socialUser   第三方用户画像
     * @param stateContext 深链与业务上下文
     * @return 认证结果对象
     */
    public static SocialAuthResult loggedIn(String userId, Object tokenInfo, SocialUser socialUser, StateContext stateContext) {
        return SocialAuthResult.builder()
                .state(SocialAuthState.LOGGED_IN)
                .userId(userId)
                .tokenInfo(tokenInfo)
                .socialUser(socialUser)
                .stateContext(stateContext)
                .build();
    }

    /**
     * 构建待两阶段绑定的认证结果
     *
     * @param bindTicket   两阶段绑定票据
     * @param socialUser   第三方用户画像
     * @param stateContext 深链与业务上下文
     * @return 认证结果对象
     */
    public static SocialAuthResult requireBind(String bindTicket, SocialUser socialUser, StateContext stateContext) {
        return SocialAuthResult.builder()
                .state(SocialAuthState.REQUIRE_BIND)
                .bindTicket(bindTicket)
                .socialUser(socialUser)
                .stateContext(stateContext)
                .build();
    }

    /**
     * 是否已成功匹配本地用户并签发会话
     *
     * @return true 表示已登录
     */
    public boolean isLoggedIn() {
        return SocialAuthState.LOGGED_IN.equals(this.state);
    }

    /**
     * 是否需要进行两阶段身份绑定或注册
     *
     * @return true 表示需要绑定
     */
    public boolean isRequireBind() {
        return SocialAuthState.REQUIRE_BIND.equals(this.state);
    }
}
