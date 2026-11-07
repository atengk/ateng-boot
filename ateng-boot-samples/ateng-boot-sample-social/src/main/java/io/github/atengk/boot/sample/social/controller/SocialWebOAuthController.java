package io.github.atengk.boot.sample.social.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.social.service.SampleSocialScenarioService;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialToken;
import io.github.atengk.boot.social.model.SocialTokenRefreshRequest;
import io.github.atengk.boot.social.model.SocialTokenRevokeRequest;
import io.github.atengk.boot.social.model.StateContext;
import io.github.atengk.boot.social.template.SocialAuthTemplate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社交登录 Web OAuth2 认证与令牌生命周期 Controller
 * <p>
 * 提供标准 PC 扫码授权 URL 生成、开放重定向白名单校验、Web 回调换码认证、第三方令牌续期及授权吊销等功能。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/social/oauth")
@RequiredArgsConstructor
public class SocialWebOAuthController {

    private final SampleSocialScenarioService scenarioService;
    private final SocialAuthTemplate socialAuthTemplate;

    /**
     * 生成第三方 OAuth2 平台授权跳转 URL
     *
     * @param appKey      第三方社交应用唯一标识（如 github_app）
     * @param redirectUrl 登录成功后回跳目标页面
     * @param tenantId    多租户隔离标识
     * @return 携带高熵防伪 State 的重定向 URL
     */
    @GetMapping("/render/{appKey}")
    public Result<String> renderAuthorizeUrl(
            @PathVariable("appKey") String appKey,
            @RequestParam(value = "redirectUrl", required = false) String redirectUrl,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String url = scenarioService.generateWebAuthorizeUrl(appKey, redirectUrl, tenantId);
        return Result.success(url);
    }

    /**
     * 演示开放重定向白名单安全防卫拦截机制
     * <p>
     * 当传入的 redirectUrl 域名不在 {@code ateng.boot.social.allowed-redirect-domains} 白名单时，
     * 框架将刚性拦截并抛出受控业务异常 {@link io.github.atengk.boot.social.exception.SocialRedirectUriInvalidException}。
     * </p>
     *
     * @param redirectUrl 待校验的回调目标重定向地址
     * @return 校验通过后的授权跳转地址
     */
    @GetMapping("/render-whitelist")
    public Result<String> renderWithWhitelistCheck(
            @RequestParam("redirectUrl") String redirectUrl) {
        StateContext context = StateContext.builder()
                .redirectUrl(redirectUrl)
                .build();
        String url = socialAuthTemplate.getAuthorizeUrl("github_app", context);
        return Result.success(url);
    }

    /**
     * 处理标准 Web 回调换码认证
     *
     * @param appKey 社交应用唯一标识
     * @param code   第三方平台授权码 code
     * @param state  防伪状态码 state
     * @return 认证结果（已绑定用户签发会话凭据，未绑定用户返回 bindTicket）
     */
    @GetMapping("/callback/{appKey}")
    public Result<SocialAuthResult> completeWebLogin(
            @PathVariable("appKey") String appKey,
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        SocialAuthResult result = scenarioService.completeWebLogin(appKey, code, state);
        return Result.success(result);
    }

    /**
     * 刷新第三方平台访问令牌 (refreshToken)
     *
     * @param appKey  社交应用唯一标识
     * @param request 令牌续期请求对象
     * @return 续期后的第三方平台访问令牌
     */
    @PostMapping("/refresh-token/{appKey}")
    public Result<SocialToken> refreshToken(
            @PathVariable("appKey") String appKey,
            @Valid @RequestBody SocialTokenRefreshRequest request) {
        SocialToken token = scenarioService.refreshToken(appKey, request.getRefreshToken());
        return Result.success(token);
    }

    /**
     * 吊销第三方平台授权许可凭据 (revokeAuth)
     *
     * @param appKey  社交应用唯一标识
     * @param request 授权吊销请求对象
     * @return 空成功响应
     */
    @PostMapping("/revoke-token/{appKey}")
    public Result<Void> revokeAuth(
            @PathVariable("appKey") String appKey,
            @Valid @RequestBody SocialTokenRevokeRequest request) {
        scenarioService.revokeAuth(appKey, request.getAccessToken());
        return Result.success();
    }
}
