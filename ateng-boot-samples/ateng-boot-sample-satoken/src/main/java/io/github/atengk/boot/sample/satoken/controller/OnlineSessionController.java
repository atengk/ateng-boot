package io.github.atengk.boot.sample.satoken.controller;

import cn.dev33.satoken.annotation.SaCheckDisable;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.satoken.vo.DisableStatusVO;
import io.github.atengk.boot.satoken.model.OnlineUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 在线会话监控与安全治理控制器
 * 提供在线会话增量游标统计分页、按 Token/设备/用户强退踢人、同端顶替下线、账号阶梯封禁解封及 @SaCheckDisable 受限业务校验
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/online")
@RequiredArgsConstructor
public class OnlineSessionController {

    /**
     * 获取当前系统在线会话总数
     *
     * @param loginType 账号体系类型标识（可选，默认主体系）
     * @return 在线会话数
     */
    @GetMapping("/count")
    public Result<Long> getOnlineCount(@RequestParam(value = "loginType", required = false) String loginType) {
        if (loginType != null && !loginType.isBlank()) {
            return Result.success(LoginHelper.getOnlineUserCount(loginType));
        }
        return Result.success(LoginHelper.getOnlineUserCount());
    }

    /**
     * 分页检索在线用户列表（增量游标 SCAN 检索与分页切片，支持按 loginType 过滤）
     *
     * @param loginType 账号体系类型标识（可选，默认主体系）
     * @param keyword   模糊匹配关键字（账号、用户名、用户 ID、Token）
     * @param page      当前页码
     * @param size      每页记录数
     * @return 分页结果
     */
    @GetMapping("/page")
    public Result<PageResult<OnlineUser>> searchOnlineUserPage(
            @RequestParam(value = "loginType", required = false) String loginType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        if (loginType != null && !loginType.isBlank()) {
            return Result.success(LoginHelper.searchOnlineUserPage(loginType, keyword, page, size));
        }
        return Result.success(LoginHelper.searchOnlineUserPage(keyword, page, size));
    }

    /**
     * 根据 Token 强制指定会话下线
     *
     * @param tokenValue 目标会话凭据
     * @return 成功响应
     */
    @PostMapping("/kickout-token")
    public Result<Void> kickoutByToken(@NotBlank(message = "Token 凭据不能为空") @RequestParam("tokenValue") String tokenValue) {
        LoginHelper.kickoutByToken(tokenValue);
        return Result.success();
    }

    /**
     * 根据用户标识与设备类型精准强退指定端会话
     *
     * @param userId 目标用户标识
     * @param device 设备端类型（如 PC, APP）
     * @return 成功响应
     */
    @PostMapping("/kickout-device")
    public Result<Void> kickoutDevice(
            @NotBlank(message = "用户标识不能为空") @RequestParam("userId") String userId,
            @NotBlank(message = "设备端类型不能为空") @RequestParam("device") String device) {
        LoginHelper.kickoutDevice(userId, device);
        return Result.success();
    }

    /**
     * 根据用户标识强制将用户全端踢下线
     *
     * @param userId 目标用户标识
     * @return 成功响应
     */
    @PostMapping("/kickout-user")
    public Result<Void> kickoutUser(@NotBlank(message = "用户标识不能为空") @RequestParam("userId") String userId) {
        LoginHelper.kickout(userId);
        return Result.success();
    }

    /**
     * 模拟用户在指定设备端被新会话顶替下线 (replaced)
     *
     * @param userId 目标用户标识
     * @param device 设备端类型（默认 PC）
     * @return 成功响应
     */
    @PostMapping("/replaced")
    public Result<Void> replaced(
            @NotBlank(message = "用户标识不能为空") @RequestParam("userId") String userId,
            @RequestParam(value = "device", defaultValue = "PC") String device) {
        LoginHelper.replaced(userId, device);
        return Result.success();
    }

    /**
     * 对违规账号执行指定时长阶梯封禁
     *
     * @param userId          目标用户标识
     * @param durationSeconds 封禁秒数（默认 300 秒）
     * @return 业务结果
     */
    @PostMapping("/disable")
    public Result<String> disableUser(
            @NotBlank(message = "用户标识不能为空") @RequestParam("userId") String userId,
            @RequestParam(value = "durationSeconds", defaultValue = "300") long durationSeconds) {
        LoginHelper.disable(userId, Duration.ofSeconds(durationSeconds));
        return Result.success("账号已封禁，时长: " + durationSeconds + " 秒");
    }

    /**
     * 解除目标账号的封禁状态
     *
     * @param userId 目标用户标识
     * @return 业务结果
     */
    @PostMapping("/untie-disable")
    public Result<String> untieDisableUser(@NotBlank(message = "用户标识不能为空") @RequestParam("userId") String userId) {
        LoginHelper.untieDisable(userId);
        return Result.success("账号已解封: " + userId);
    }

    /**
     * 查询账号的封禁状态与剩余锁定时间
     *
     * @param userId 目标用户标识
     * @return 封禁状态视图对象
     */
    @GetMapping("/disable-status")
    public Result<DisableStatusVO> getDisableStatus(@NotBlank(message = "用户标识不能为空") @RequestParam("userId") String userId) {
        DisableStatusVO vo = DisableStatusVO.builder()
                .userId(userId)
                .disabled(LoginHelper.isDisable(userId))
                .disableTime(LoginHelper.getDisableTime(userId))
                .build();
        return Result.success(vo);
    }

    /**
     * 声明式 @SaCheckDisable 受限业务接口
     * 处于封禁状态的账号调用将被 AOP 切面拦截返回 403 契约
     *
     * @return 业务结果
     */
    @GetMapping("/restricted-action")
    @SaCheckDisable
    public Result<String> checkRestrictedAction() {
        return Result.success("受限操作调用成功");
    }
}
