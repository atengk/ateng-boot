package io.github.atengk.boot.sample.social.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.social.service.SampleSocialScenarioService;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialBindRequest;
import io.github.atengk.boot.social.model.SocialDirectLoginRequest;
import io.github.atengk.boot.social.model.SocialUnbindRequest;
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
 * 社交登录业务场景定制 Controller 样例
 * <p>
 * 演示在企业业务系统层编排 {@link SampleSocialScenarioService} 门面，实现个性化路由、多租户上下文透传与会话接入。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@RestController
@RequestMapping("/sample/social")
@RequiredArgsConstructor
public class SampleSocialController {

    private final SampleSocialScenarioService scenarioService;

    /**
     * 生成第三方 OAuth2 平台授权跳转 URL
     *
     * @param appKey      第三方社交应用唯一标识（如 github_app, wechat_mp_app）
     * @param redirectUrl 登录成功后回跳地址
     * @param tenantId    多租户隔离标识
     * @return 携带高熵防伪 State 的重定向 URL
     */
    @GetMapping("/render/{appKey}")
    public Result<String> renderAuthorizeUrl(@PathVariable("appKey") String appKey,
                                             @RequestParam(value = "redirectUrl", required = false) String redirectUrl,
                                             @RequestParam(value = "tenantId", required = false) String tenantId) {
        String url = scenarioService.generateWebAuthorizeUrl(appKey, redirectUrl, tenantId);
        return Result.success(url);
    }

    /**
     * 处理标准 Web 回调换码认证
     *
     * @param appKey 社交应用唯一标识
     * @param code   第三方平台授权码 code
     * @param state  防伪状态码 state
     * @return 认证结果（已登录签发会话凭据，或未绑定返回 bindTicket 引导两阶段绑定）
     */
    @GetMapping("/callback/{appKey}")
    public Result<SocialAuthResult> completeWebLogin(@PathVariable("appKey") String appKey,
                                                     @RequestParam("code") String code,
                                                     @RequestParam("state") String state) {
        SocialAuthResult result = scenarioService.completeWebLogin(appKey, code, state);
        return Result.success(result);
    }

    /**
     * 微信小程序 (wx.login code2Session) 或 App 原生授权直传凭证登录
     *
     * @param appKey  社交应用唯一标识
     * @param request 直传登录请求对象
     * @return 认证结果
     */
    @PostMapping("/direct-login/{appKey}")
    public Result<SocialAuthResult> directLogin(@PathVariable("appKey") String appKey,
                                                @Valid @RequestBody SocialDirectLoginRequest request) {
        SocialAuthResult result = scenarioService.directLogin(appKey, request.getCode(), request.getDeviceType());
        return Result.success(result);
    }

    /**
     * 消费两阶段绑定票据 BindTicket 完成本地用户与第三方账号绑定
     *
     * @param appKey  社交应用唯一标识
     * @param request 绑定请求对象
     * @return 绑定并登录成功结果
     */
    @PostMapping("/bind/{appKey}")
    public Result<SocialAuthResult> bindUser(@PathVariable("appKey") String appKey,
                                             @Valid @RequestBody SocialBindRequest request) {
        SocialAuthResult result = scenarioService.bindUser(appKey, request.getBindTicket(), request.getUserId(), request.getDeviceType());
        return Result.success(result);
    }

    /**
     * 解除本地用户与第三方平台绑定关系（带孤儿死号前置安全防卫）
     *
     * @param appKey  社交应用唯一标识
     * @param request 解绑请求对象
     * @return 空成功响应
     */
    @PostMapping("/unbind/{appKey}")
    public Result<Void> unbindUser(@PathVariable("appKey") String appKey,
                                   @Valid @RequestBody SocialUnbindRequest request) {
        scenarioService.unbindUser(request.getUserId(), appKey);
        return Result.success();
    }
}
