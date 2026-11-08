package io.github.atengk.boot.sample.satoken.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.satoken.dto.LoginRequestDTO;
import io.github.atengk.boot.sample.satoken.dto.OrderCreateDTO;
import io.github.atengk.boot.sample.satoken.service.SaTokenSampleService;
import io.github.atengk.boot.sample.satoken.vo.TenantOrderVO;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户会话与租户上下文控制器
 * 提供多端登录、会话读取、租户上下文提取、设备注销与租户订单读写端点
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/auth")
@RequiredArgsConstructor
public class AuthSessionController {

    private final SaTokenSampleService sampleService;

    /**
     * 用户登录接口
     * 支持多端设备与多租户复合身份签发
     *
     * @param request 登录请求传输对象
     * @return 签发的访问凭据 Token
     */
    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginRequestDTO request) {
        String userId = (request != null && request.getUserId() != null && !request.getUserId().isBlank())
                ? request.getUserId()
                : (request != null && request.getUsername() != null ? "user-" + request.getUsername() : "user-1001");
        String username = (request != null && request.getUsername() != null && !request.getUsername().isBlank())
                ? request.getUsername()
                : userId;

        LoginUser loginUser = LoginUser.builder()
                .userId(userId)
                .userAccount(username)
                .userName(username)
                .tenantId(request != null ? request.getTenantId() : null)
                .roles(request != null ? request.getRoles() : null)
                .permissions(request != null ? request.getPermissions() : null)
                .build();

        if (request != null && request.getDevice() != null && !request.getDevice().isBlank()) {
            LoginHelper.login(loginUser, request.getDevice());
        } else {
            LoginHelper.login(loginUser);
        }
        return Result.success(LoginHelper.getTokenValue());
    }

    /**
     * 校验登录状态并获取当前登录用户信息
     *
     * @return 当前登录主体
     */
    @GetMapping("/me")
    public Result<LoginUser> getLoginUser() {
        LoginHelper.checkLogin();
        return Result.success(LoginHelper.getLoginUser());
    }

    /**
     * 获取当前操作人用户 ID（透明剥离多租户前缀）
     *
     * @return 原始用户唯一标识
     */
    @GetMapping("/user-id")
    public Result<String> getUserId() {
        LoginHelper.checkLogin();
        return Result.success(LoginHelper.getUserId());
    }

    /**
     * 获取当前租户上下文标识
     *
     * @return 当前租户标识
     */
    @GetMapping("/current-tenant")
    public Result<String> getCurrentTenant() {
        LoginHelper.checkLogin();
        return Result.success(TenantContextHolder.getTenantId());
    }

    /**
     * 编程式校验登录状态
     *
     * @return 状态信息
     */
    @GetMapping("/check-login")
    public Result<String> checkLogin() {
        LoginHelper.checkLogin();
        return Result.success("已登录");
    }

    /**
     * 当前会话注销下线
     *
     * @return 成功响应
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        LoginHelper.logout();
        return Result.success();
    }

    /**
     * 指定设备端注销下线
     *
     * @param device 设备端类型标识（如 PC, APP, MINI_PROGRAM, H5）
     * @return 成功响应
     */
    @PostMapping("/logout-device")
    public Result<Void> logoutDevice(@RequestParam("device") String device) {
        LoginHelper.logoutByDevice(device);
        return Result.success();
    }

    /**
     * 创建租户采购订单（验证当前操作人与租户上下文自动审计填充）
     *
     * @param orderDto 订单创建传输对象
     * @return 订单持久化 ID
     */
    @PostMapping("/orders")
    public Result<Long> createOrder(@Valid @RequestBody OrderCreateDTO orderDto) {
        Long orderId = sampleService.createOrder(orderDto.getOrderNo(), orderDto.getAmount());
        return Result.success(orderId);
    }

    /**
     * 查询租户订单详情（验证行级多租户隔离与零实体泄露原则）
     *
     * @param orderId 订单标识
     * @return 订单展示视图对象
     */
    @GetMapping("/orders/{orderId}")
    public Result<TenantOrderVO> getOrder(@PathVariable("orderId") Long orderId) {
        TenantOrderVO order = sampleService.getOrderById(orderId);
        if (order == null) {
            return Result.fail(CommonResultCode.NOT_FOUND.getCode(), "订单不存在");
        }
        return Result.success(order);
    }
}
