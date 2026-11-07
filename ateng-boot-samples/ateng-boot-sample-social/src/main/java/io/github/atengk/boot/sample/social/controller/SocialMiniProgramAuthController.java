package io.github.atengk.boot.sample.social.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.social.service.SampleSocialScenarioService;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialDirectLoginRequest;
import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.template.SocialAuthTemplate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社交登录移动端与小程序凭证直传认证 Controller
 * <p>
 * 提供微信小程序 (wx.login code2Session) 或 App 授权凭证直传登录，
 * 以及新用户首次授权登录静默自动注册开户 (autoSignUp) 的业务演示。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/social/miniapp")
@RequiredArgsConstructor
public class SocialMiniProgramAuthController {

    private final SampleSocialScenarioService scenarioService;
    private final SocialAuthTemplate socialAuthTemplate;

    /**
     * 微信小程序或 App 原生授权凭据直传换码登录
     *
     * @param appKey  社交应用唯一标识（如 wechat_mp_app）
     * @param request 直传登录请求对象
     * @return 认证结果（已绑定签发会话凭据，未绑定返回 bindTicket）
     */
    @PostMapping("/direct-login/{appKey}")
    public Result<SocialAuthResult> directLogin(
            @PathVariable("appKey") String appKey,
            @Valid @RequestBody SocialDirectLoginRequest request) {
        SocialAuthResult result = scenarioService.directLogin(appKey, request.getCode(), request.getDeviceType());
        return Result.success(result);
    }

    /**
     * 演示首次登录静默自动开户与会话签发 (Auto-Signup)
     * <p>
     * 当应用开启 {@code autoSignUp: true} 时，若未匹配到已绑定的本地用户，
     * 框架将自动回调 {@link io.github.atengk.boot.social.spi.SocialUserDetailsService#autoRegisterUser} 完成开户落库，
     * 并直接签发受管登录会话，无需引导进入两阶段绑定流程。
     * </p>
     *
     * @param appKey  社交应用唯一标识（如开启了 auto-sign-up 的 wechat_auto_app）
     * @param request 直传登录请求对象
     * @return 自动注册开户并登录后的认证结果
     */
    @PostMapping("/auto-signup-login/{appKey}")
    public Result<SocialAuthResult> autoSignUpLogin(
            @PathVariable("appKey") String appKey,
            @Valid @RequestBody SocialDirectLoginRequest request) {
        SocialLoginOptions options = SocialLoginOptions.builder()
                .deviceType(request.getDeviceType() != null ? request.getDeviceType() : "MINI_PROGRAM")
                .loginType("USER")
                .build();
        SocialAuthResult result = socialAuthTemplate.directLogin(appKey, request.getCode(), options);
        return Result.success(result);
    }
}
