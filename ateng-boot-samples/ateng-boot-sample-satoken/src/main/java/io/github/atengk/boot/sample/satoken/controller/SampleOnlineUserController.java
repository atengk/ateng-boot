package io.github.atengk.boot.sample.satoken.controller;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.satoken.model.OnlineUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 在线会话监控与强退治理示例控制器
 * 演示在线用户安全游标分页检索、人数统计及按凭据/设备强退下线
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/auth/online")
public class SampleOnlineUserController {

    /**
     * 获取在线用户会话总数
     *
     * @param loginType 账号体系（可选，默认为主体系）
     * @return 在线人数
     */
    @GetMapping("/count")
    public Result<Long> getOnlineCount(@RequestParam(value = "loginType", required = false) String loginType) {
        if (loginType != null && !loginType.isBlank()) {
            return Result.success(LoginHelper.getOnlineUserCount(loginType));
        }
        return Result.success(LoginHelper.getOnlineUserCount());
    }

    /**
     * 分页查询在线用户列表
     *
     * @param keyword 模糊匹配关键字（账号/用户名/标识/Token）
     * @param page    当前页码
     * @param size    每页记录数
     * @return 在线用户列表
     */
    @GetMapping("/list")
    public Result<List<OnlineUser>> searchOnlineUsers(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return Result.success(LoginHelper.searchOnlineUsers(keyword, page, size));
    }

    /**
     * 分页查询在线用户 PageResult
     *
     * @param keyword 模糊匹配关键字
     * @param page    当前页码
     * @param size    每页记录数
     * @return 强类型 PageResult 对象
     */
    @GetMapping("/page")
    public Result<PageResult<OnlineUser>> searchOnlineUserPage(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return Result.success(LoginHelper.searchOnlineUserPage(keyword, page, size));
    }

    /**
     * 按 Token 凭据将指定会话强退下线
     *
     * @param tokenValue Token 凭据
     * @return 成功响应
     */
    @PostMapping("/kickout-token")
    public Result<Void> kickoutByToken(@NotBlank(message = "Token 凭据不能为空") @RequestParam("tokenValue") String tokenValue) {
        LoginHelper.kickoutByToken(tokenValue);
        return Result.success();
    }

    /**
     * 按账号标识和设备端将指定会话强退下线
     *
     * @param userId 账号标识
     * @param device 设备端类型（如 PC, APP）
     * @return 成功响应
     */
    @PostMapping("/kickout-device")
    public Result<Void> kickoutDevice(
            @NotBlank(message = "账号标识不能为空") @RequestParam("userId") String userId,
            @NotBlank(message = "设备类型不能为空") @RequestParam("device") String device) {
        LoginHelper.kickoutDevice(userId, device);
        return Result.success();
    }
}
