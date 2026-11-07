package io.github.atengk.boot.sample.social.controller;

import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.social.service.SampleSocialScenarioService;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialBindRequest;
import io.github.atengk.boot.social.model.SocialBoundAccount;
import io.github.atengk.boot.social.model.SocialDirectBindRequest;
import io.github.atengk.boot.social.model.SocialUnbindRequest;
import io.github.atengk.boot.social.model.SocialUser;
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

import java.util.List;

/**
 * 社交账号绑定、跨号抢占防卫与解绑 Controller
 * <p>
 * 提供未登录两阶段 BindTicket 绑定、个人中心已登录态直接绑定、跨号抢占冲突拦截演示、
 * 孤儿账号防卫解绑以及已绑定账号列表查询等功能。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/social/bind")
@RequiredArgsConstructor
public class SocialAccountBindController {

    private final SampleSocialScenarioService scenarioService;
    private final SocialAuthTemplate socialAuthTemplate;

    /**
     * 消费两阶段绑定票据 BindTicket 完成本地用户与第三方账号绑定
     *
     * @param appKey  社交应用唯一标识
     * @param request 绑定请求对象（包含 bindTicket, userId, deviceType 等）
     * @return 绑定并登录成功结果
     */
    @PostMapping("/two-phase-bind/{appKey}")
    public Result<SocialAuthResult> twoPhaseBind(
            @PathVariable("appKey") String appKey,
            @Valid @RequestBody SocialBindRequest request) {
        SocialAuthResult result = scenarioService.bindUser(
                appKey, request.getBindTicket(), request.getUserId(), request.getDeviceType());
        return Result.success(result);
    }

    /**
     * 个人中心已登录态直接绑定第三方社交账号
     *
     * @param appKey  社交应用唯一标识
     * @param request 直接绑定请求对象（包含当前登录 userId, 第三方 code 与可选 state）
     * @return 绑定的第三方标准化画像
     */
    @PostMapping("/direct-bind/{appKey}")
    public Result<SocialUser> directBind(
            @PathVariable("appKey") String appKey,
            @Valid @RequestBody SocialDirectBindRequest request) {
        SocialUser socialUser;
        if (request.getState() != null && !request.getState().isBlank()) {
            AuthCallback callback = AuthCallback.builder()
                    .code(request.getCode())
                    .state(request.getState())
                    .build();
            socialUser = scenarioService.bindDirect(request.getUserId(), appKey, callback);
        } else {
            socialUser = scenarioService.bindDirect(request.getUserId(), appKey, request.getCode());
        }
        return Result.success(socialUser);
    }

    /**
     * 演示跨号抢占冲突防卫拦截 (Conflict Check)
     * <p>
     * 当尝试绑定的第三方账号已经被其他本地用户绑定时，底层将刚性抛出 {@link io.github.atengk.boot.social.exception.SocialAlreadyBoundException}
     * （错误码 7007），彻底防御跨号越权覆盖风险。
     * </p>
     *
     * @param appKey  社交应用唯一标识
     * @param request 直接绑定请求对象
     * @return 绑定结果
     */
    @PostMapping("/conflict-check/{appKey}")
    public Result<SocialUser> conflictCheck(
            @PathVariable("appKey") String appKey,
            @Valid @RequestBody SocialDirectBindRequest request) {
        // 调用直接绑定，触发冲突检测防线
        SocialUser socialUser = socialAuthTemplate.bindDirect(request.getUserId(), appKey, request.getCode());
        return Result.success(socialUser);
    }

    /**
     * 解除本地用户与第三方账号绑定关系（带孤儿死号前置安全防卫）
     *
     * @param appKey  社交应用唯一标识
     * @param request 解绑请求对象
     * @return 空成功响应
     */
    @PostMapping("/unbind/{appKey}")
    public Result<Void> unbindUser(
            @PathVariable("appKey") String appKey,
            @Valid @RequestBody SocialUnbindRequest request) {
        scenarioService.unbindUser(request.getUserId(), appKey);
        return Result.success();
    }

    /**
     * 查询本地用户已绑定的第三方社交账号列表
     * <p>
     * 优先从当前 Sa-Token 登录态中获取 userId，若未登录且未传 userId 则提示参数缺失。
     * </p>
     *
     * @param userId 本地用户标识（可选，若未指定则自动从当前登录态获取）
     * @return 已绑定的第三方社交账号视图列表
     */
    @GetMapping("/bindings")
    public Result<List<SocialBoundAccount>> listBindings(
            @RequestParam(value = "userId", required = false) String userId) {
        String targetUserId = userId;
        if ((targetUserId == null || targetUserId.isBlank()) && StpUtil.isLogin()) {
            targetUserId = StpUtil.getLoginIdAsString();
        }
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new IllegalArgumentException("本地用户标识 [userId] 不能为空");
        }
        List<SocialBoundAccount> accounts = scenarioService.listBoundAccounts(targetUserId);
        return Result.success(accounts);
    }
}
