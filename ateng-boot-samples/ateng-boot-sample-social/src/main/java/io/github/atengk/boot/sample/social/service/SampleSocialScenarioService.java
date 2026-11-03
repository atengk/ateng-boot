package io.github.atengk.boot.sample.social.service;

import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.StateContext;
import io.github.atengk.boot.social.template.SocialAuthTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.anduin.model.AuthCallback;
import org.springframework.stereotype.Service;

/**
 * 社交登录业务场景门面服务样例
 * <p>
 * 演示在业务层直接注入 {@link SocialAuthTemplate}，编排 Web 授权、小程序直传登录、两阶段绑定与孤儿死号解绑防御。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SampleSocialScenarioService {

    private final SocialAuthTemplate socialAuthTemplate;

    /**
     * 生成第三方 OAuth2 平台授权跳转 URL
     *
     * @param appKey      社交应用唯一标识
     * @param redirectUrl 登录成功后回跳目标页面
     * @param tenantId    多租户隔离标识
     * @return 携带高熵防伪 State 的重定向 URL
     */
    public String generateWebAuthorizeUrl(String appKey, String redirectUrl, String tenantId) {
        log.info("业务场景编排：发起生成第三方授权跳转地址, appKey={}, tenantId={}, redirectUrl={}", appKey, tenantId, redirectUrl);
        StateContext context = StateContext.builder()
                .redirectUrl(redirectUrl)
                .tenantId(tenantId)
                .build();
        return socialAuthTemplate.getAuthorizeUrl(appKey, context);
    }

    /**
     * 处理标准 Web 回调换码认证
     *
     * @param appKey 社交应用唯一标识
     * @param code   第三方授权码
     * @param state  防伪状态码
     * @return 认证结果（已登录签发会话，或未绑定引导两阶段绑定）
     */
    public SocialAuthResult completeWebLogin(String appKey, String code, String state) {
        log.info("业务场景编排：执行 Web OAuth2 回调换码登录, appKey={}", appKey);
        AuthCallback callback = AuthCallback.builder()
                .code(code)
                .state(state)
                .build();
        SocialLoginOptions options = SocialLoginOptions.builder()
                .deviceType("PC")
                .loginType("USER")
                .build();
        return socialAuthTemplate.login(appKey, callback, options);
    }

    /**
     * 微信小程序 (wx.login code2Session) 或 App 原生授权直传凭证登录
     *
     * @param appKey     社交应用唯一标识
     * @param code       授权凭证 code
     * @param deviceType 客户端设备类型
     * @return 认证结果
     */
    public SocialAuthResult directLogin(String appKey, String code, String deviceType) {
        log.info("业务场景编排：执行移动端直传凭证登录, appKey={}, deviceType={}", appKey, deviceType);
        SocialLoginOptions options = SocialLoginOptions.builder()
                .deviceType(deviceType != null ? deviceType : "MINI_PROGRAM")
                .loginType("USER")
                .build();
        return socialAuthTemplate.directLogin(appKey, code, options);
    }

    /**
     * 消费两阶段绑定票据 BindTicket 完成绑定
     *
     * @param appKey     社交应用唯一标识
     * @param bindTicket 阅后即焚绑定凭证
     * @param userId     本地系统用户 ID
     * @param deviceType 客户端设备类型
     * @return 绑定并登录成功结果
     */
    public SocialAuthResult bindUser(String appKey, String bindTicket, String userId, String deviceType) {
        log.info("业务场景编排：执行社交账号两阶段绑定, appKey={}, userId={}, deviceType={}", appKey, userId, deviceType);
        SocialLoginOptions options = SocialLoginOptions.builder()
                .deviceType(deviceType != null ? deviceType : "PC")
                .loginType("USER")
                .build();
        return socialAuthTemplate.bind(appKey, bindTicket, userId, options);
    }

    /**
     * 解除本地用户与第三方账号绑定关系
     *
     * @param userId 本地用户 ID
     * @param appKey 社交应用唯一标识
     */
    public void unbindUser(String userId, String appKey) {
        log.info("业务场景编排：执行社交账号解绑与孤儿防卫前置探测, userId={}, appKey={}", userId, appKey);
        socialAuthTemplate.unbind(userId, appKey);
    }
}
