package io.github.atengk.boot.social.session;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialUser;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 ateng-boot-satoken 基础设施的社交登录会话管理实现
 * <p>
 * 联动 {@link LoginHelper} 完成 Sa-Token 登录会话与 Token-Session 强类型封包，
 * 原生支持 deviceType 多端治理、loginType 独立账号体系隔离与自定义会话超时控制。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public class SaTokenSocialSessionManager implements SocialSessionManager {

    @Override
    public Object createSession(String userId, SocialLoginOptions options, SocialUser socialUser) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("无法签发 Sa-Token 登录会话：本地用户标识 (userId) 不能为空");
        }

        SaLoginParameter parameter = new SaLoginParameter();
        String loginType = null;

        if (options != null) {
            if (options.getDeviceType() != null && !options.getDeviceType().isBlank()) {
                parameter.setDeviceType(options.getDeviceType());
            }
            if (options.getTimeout() != null) {
                parameter.setTimeout(options.getTimeout().getSeconds());
            }
            if (options.getIsLastingCookie() != null) {
                parameter.setIsLastingCookie(options.getIsLastingCookie());
            }
            if (options.getExtraData() != null && !options.getExtraData().isEmpty()) {
                options.getExtraData().forEach(parameter::setExtra);
            }
            loginType = options.getLoginType();
        }

        LoginUser loginUser = LoginUser.builder()
                .userId(userId)
                .userName(socialUser != null ? (socialUser.getNickname() != null ? socialUser.getNickname() : socialUser.getUsername()) : null)
                .build();

        if (loginType != null && !loginType.isBlank()) {
            LoginHelper.login(loginType, loginUser, parameter);
            StpLogic stpLogic = LoginHelper.getStpLogic(loginType);
            log.info("Social login session issued for userId: [{}], loginType: [{}], deviceType: [{}]",
                    userId, loginType, parameter.getDeviceType());
            return stpLogic.getTokenInfo();
        } else {
            LoginHelper.login(loginUser, parameter);
            log.info("Social login session issued for userId: [{}], default loginType, deviceType: [{}]",
                    userId, parameter.getDeviceType());
            return StpUtil.getTokenInfo();
        }
    }
}
