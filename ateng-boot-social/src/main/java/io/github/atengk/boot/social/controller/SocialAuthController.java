package io.github.atengk.boot.social.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialBindRequest;
import io.github.atengk.boot.social.model.SocialDirectLoginRequest;
import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialUnbindRequest;
import io.github.atengk.boot.social.model.StateContext;
import io.github.atengk.boot.social.template.SocialAuthTemplate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.anduin.model.AuthCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社交登录与 OAuth2 统一 REST 接入端点控制器
 * <p>
 * 默认不开启，可通过配置 {@code ateng.boot.social.controller.enabled=true} 激活自动装配。
 * 遵循 ADR-0002 显式强类型契约设计，所有接口显式返回 {@link Result}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@RestController
@RequestMapping("${ateng.boot.social.controller.prefix:/auth/social}")
@RequiredArgsConstructor
public class SocialAuthController {

    private final SocialAuthTemplate socialAuthTemplate;

    /**
     * 生成第三方 OAuth2 平台授权跳转 URL
     *
     * @param appKey      社交应用唯一标识
     * @param redirectUrl 认证成功后最终重定向业务地址
     * @param tenantId    租户标识
     * @return 携带高熵防伪 State 的第三方平台授权地址
     */
    @GetMapping("/{appKey}/render")
    public Result<String> renderAuthorizeUrl(
            @PathVariable("appKey") String appKey,
            @RequestParam(value = "redirectUrl", required = false) String redirectUrl,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        StateContext context = StateContext.builder()
                .redirectUrl(redirectUrl)
                .tenantId(tenantId)
                .build();
        String authorizeUrl = socialAuthTemplate.getAuthorizeUrl(appKey, context);
        return Result.success(authorizeUrl);
    }

    /**
     * 处理标准 Web 浏览器端 OAuth2 回调换码认证
     *
     * @param appKey   社交应用唯一标识
     * @param callback 接收包含 code、auth_code、state 等平台回调参数
     * @return 认证结果（已绑定签发会话或未绑定引导两阶段绑定）
     */
    @GetMapping("/{appKey}/callback")
    public Result<SocialAuthResult> callback(
            @PathVariable("appKey") String appKey,
            AuthCallback callback) {
        SocialAuthResult result = socialAuthTemplate.login(appKey, callback);
        return Result.success(result);
    }

    /**
     * 小程序 (wx.login code2Session) 或 App 原生授权直传凭证登录
     *
     * @param appKey  社交应用唯一标识
     * @param request 直传登录请求对象
     * @return 认证结果
     */
    @PostMapping("/{appKey}/direct-login")
    public Result<SocialAuthResult> directLogin(
            @PathVariable("appKey") String appKey,
            @Valid @RequestBody SocialDirectLoginRequest request) {
        SocialLoginOptions options = SocialLoginOptions.builder()
                .deviceType(request.getDeviceType())
                .loginType(request.getLoginType())
                .build();
        SocialAuthResult result = socialAuthTemplate.directLogin(appKey, request.getCode(), options);
        return Result.success(result);
    }

    /**
     * 消费两阶段绑定票据 BindTicket 完成本地账号绑定与登录
     *
     * @param appKey  社交应用唯一标识
     * @param request 绑定请求对象
     * @return 认证结果
     */
    @PostMapping("/{appKey}/bind")
    public Result<SocialAuthResult> bind(
            @PathVariable("appKey") String appKey,
            @Valid @RequestBody SocialBindRequest request) {
        SocialLoginOptions options = SocialLoginOptions.builder()
                .deviceType(request.getDeviceType())
                .loginType(request.getLoginType())
                .build();
        SocialAuthResult result = socialAuthTemplate.bind(appKey, request.getBindTicket(), request.getUserId(), options);
        return Result.success(result);
    }

    /**
     * 解除本地用户与第三方账号的绑定关系
     *
     * @param appKey  社交应用唯一标识
     * @param request 解绑请求对象
     * @return 操作成功响应
     */
    @PostMapping("/{appKey}/unbind")
    public Result<Void> unbind(
            @PathVariable("appKey") String appKey,
            @Valid @RequestBody SocialUnbindRequest request) {
        socialAuthTemplate.unbind(request.getUserId(), appKey);
        return Result.success();
    }
}
