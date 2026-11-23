package io.github.atengk.boot.social.event;

import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialUser;
import io.github.atengk.boot.social.model.StateContext;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * 社交登录认证成功领域事件
 * <p>
 * 当本地用户通过第三方平台成功完成身份认证并签发登录会话时发布
 * （覆盖 Web OAuth2 回调登录、小程序凭据直传登录、两阶段绑定登录及静默自动开户登录），
 * 支撑下游系统进行登录审计、设备统计与积分激励。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Getter
public class SocialAuthenticatedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 本地系统用户唯一标识
     */
    private final String userId;

    /**
     * 社交应用唯一标识
     */
    private final String appKey;

    /**
     * 第三方标准化用户画像
     */
    private final SocialUser socialUser;

    /**
     * 登录控制参数
     */
    private final SocialLoginOptions options;

    /**
     * 防伪业务上下文（若为移动端直传则可能为 null）
     */
    private final StateContext stateContext;

    /**
     * 认证通过时间戳
     */
    private final Instant authenticatedTime;

    public SocialAuthenticatedEvent(Object source,
                                    String userId,
                                    String appKey,
                                    SocialUser socialUser,
                                    SocialLoginOptions options,
                                    StateContext stateContext) {
        this(source, userId, appKey, socialUser, options, stateContext, Instant.now());
    }

    public SocialAuthenticatedEvent(Object source,
                                    String userId,
                                    String appKey,
                                    SocialUser socialUser,
                                    SocialLoginOptions options,
                                    StateContext stateContext,
                                    Instant authenticatedTime) {
        super(source);
        this.userId = userId;
        this.appKey = appKey;
        this.socialUser = socialUser;
        this.options = options;
        this.stateContext = stateContext;
        this.authenticatedTime = authenticatedTime != null ? authenticatedTime : Instant.now();
    }
}
