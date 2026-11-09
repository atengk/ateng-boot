package io.github.atengk.boot.sample.realtime.controller;

import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.model.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实时通信握手鉴权与 Sa-Token 会话联动切断控制器
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/realtime/auth")
@RequiredArgsConstructor
public class RealtimeAuthController {

    /**
     * 模拟用户登录并签发 Sa-Token 凭据（可作为握手 Query / Header / Cookie 参数）
     */
    @PostMapping("/login")
    public Result<String> login(@RequestParam String userId) {
        log.info("[Sample] 模拟用户登录签发 Token: userId={}", userId);
        StpUtil.login(userId);
        return Result.success(StpUtil.getTokenValue());
    }

    /**
     * 触发 Sa-Token 账号主动注销
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestParam String userId) {
        log.info("[Sample] 触发用户注销: userId={}", userId);
        StpUtil.logout(userId);
        return Result.success();
    }

    /**
     * 触发 Sa-Token 强制踢人（自动联动切断全集群长连接并下发 4002 状态码）
     */
    @PostMapping("/kickout")
    public Result<Void> kickout(@RequestParam String userId) {
        log.info("[Sample] 触发 Sa-Token 强制踢退用户: userId={}", userId);
        StpUtil.kickout(userId);
        return Result.success();
    }

    /**
     * 触发 Sa-Token 账号封禁（自动联动切断全集群长连接并下发 4002 状态码）
     */
    @PostMapping("/disable")
    public Result<Void> disable(@RequestParam String userId,
                                @RequestParam(defaultValue = "86400") long disableTime) {
        log.info("[Sample] 触发 Sa-Token 封禁账号: userId={}, disableTime={}s", userId, disableTime);
        StpUtil.disable(userId, disableTime);
        return Result.success();
    }
}
