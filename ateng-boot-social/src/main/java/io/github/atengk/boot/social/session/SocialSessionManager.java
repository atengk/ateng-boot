package io.github.atengk.boot.social.session;

import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialUser;

/**
 * 社交登录会话凭据管理契约
 * <p>
 * 负责在第三方社交认证通过并成功关联本地用户后，完成受管登录会话的签发与凭据封装。
 * 默认与 Sa-Token 深度打通，亦支持宿主系统定制接入任意专有 Token 机制。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public interface SocialSessionManager {

    /**
     * 为已绑定的本地用户签发登录会话凭据
     *
     * @param userId     本地用户唯一标识
     * @param options    登录控制参数（设备端类型、超时等）
     * @param socialUser 第三方标准化用户画像
     * @return 签发的会话凭据对象（如 SaTokenInfo、JWT 或 Token 字符串）
     */
    Object createSession(String userId, SocialLoginOptions options, SocialUser socialUser);
}
