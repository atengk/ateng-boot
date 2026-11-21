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

    /**
     * 演示 QQ 小程序 (qq.login code2Session) 凭证直传换码登录
     * <p>
     * 对应 {@code qq_mini_app}（{@code source: QQ_MINI_PROGRAM}），验证 {@code SocialAppFactory}
     * 对上游 {@code AuthDefaultSource.QQ_MINI_PROGRAM} 的自动映射自愈与小程序无状态换码。
     * </p>
     *
     * @param request 直传登录请求对象
     * @return 认证结果
     */
    @PostMapping("/qq-direct-login")
    public Result<SocialAuthResult> qqMiniProgramDirectLogin(
            @Valid @RequestBody SocialDirectLoginRequest request) {
        SocialAuthResult result = scenarioService.directLogin("qq_mini_app", request.getCode(), request.getDeviceType());
        return Result.success(result);
    }

    /**
     * 演示微信快照页虚拟账号安全防卫拦截 (Snapshot User Guard)
     * <p>
     * 对应 {@code wechat_snapshot_guard_app}（开启 {@code reject-snapshot-user: true}）。
     * 当用户未同意隐私协议处于微信快照浏览模式（{@code is_snapshotuser=1}）时，
     * 框架在入库与签发会话前刚性抛出 {@link io.github.atengk.boot.social.exception.SocialSnapshotUserException}（错误码 7009），
     * 彻底杜绝虚拟假 OpenID 污染生产用户表。
     * </p>
     *
     * @param request 直传登录请求对象
     * @return 认证结果（若为快照虚拟号将被 7009 异常阻断）
     */
    @PostMapping("/snapshot-guard-login")
    public Result<SocialAuthResult> snapshotGuardLogin(
            @Valid @RequestBody SocialDirectLoginRequest request) {
        SocialAuthResult result = scenarioService.directLogin("wechat_snapshot_guard_app", request.getCode(), request.getDeviceType());
        return Result.success(result);
    }
}
