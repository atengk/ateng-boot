package io.github.atengk.boot.social.event;

import io.github.atengk.boot.social.model.SocialUser;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * 社交账号绑定成功领域事件
 * <p>
 * 当本地用户与第三方社交账号成功建立绑定关联时发布
 * （包含两阶段票据绑定完成与首次登录静默自动注册完成），
 * 支撑下游系统进行账号绑定审计、风险控制及安全通知。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Getter
public class SocialBoundEvent extends ApplicationEvent {

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
     * 绑定完成时间戳
     */
    private final Instant boundTime;

    public SocialBoundEvent(Object source,
                            String userId,
                            String appKey,
                            SocialUser socialUser) {
        this(source, userId, appKey, socialUser, Instant.now());
    }

    public SocialBoundEvent(Object source,
                            String userId,
                            String appKey,
                            SocialUser socialUser,
                            Instant boundTime) {
        super(source);
        this.userId = userId;
        this.appKey = appKey;
        this.socialUser = socialUser;
        this.boundTime = boundTime != null ? boundTime : Instant.now();
    }
}
