package io.github.atengk.boot.social.session;

import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialUser;
import lombok.extern.slf4j.Slf4j;

/**
 * 缺省空会话管理器实现
 * <p>
 * 在未集成 ateng-boot-satoken 且未自定义实现 {@link SocialSessionManager} 时的保底降级实现。
 * 仅完成本地用户映射判定，不主动签发具体框架会话凭据。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public class NoopSocialSessionManager implements SocialSessionManager {

    @Override
    public Object createSession(String userId, SocialLoginOptions options, SocialUser socialUser) {
        log.debug("NoopSocialSessionManager invoked for userId: [{}], skipping session issuance", userId);
        return null;
    }
}
