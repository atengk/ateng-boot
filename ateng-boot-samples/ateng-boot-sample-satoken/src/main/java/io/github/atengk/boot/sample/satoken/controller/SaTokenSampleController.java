package io.github.atengk.boot.sample.satoken.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaCheckSafe;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.satoken.dto.UserProfileDTO;
import io.github.atengk.boot.sample.satoken.provider.SampleOrderDataOwnerProvider;
import io.github.atengk.boot.sample.satoken.service.SaTokenSampleService;
import io.github.atengk.boot.satoken.annotation.CheckDataOwner;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Sa-Token 鉴权与会话治理示例控制器
 * 提供用户登录、注销、会话状态查询、二级安全认证与租户主键穿透端到端验证接口
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class SaTokenSampleController {

    private final SaTokenSampleService sampleService;
    private final PermissionCacheService permissionCacheService;

    /**
     * 用户登录接口
     *
     * @param loginUser 登录实体
     * @return 签发的访问凭据 Token
     */
    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginUser loginUser,
                                @org.springframework.web.bind.annotation.RequestParam(value = "device", required = false) String device) {
        if (device != null && !device.isBlank()) {
            LoginHelper.login(loginUser, device);
        } else {
            LoginHelper.login(loginUser);
        }
        return Result.success(LoginHelper.getTokenValue());
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
     * 登录状态心跳检测
     *
     * @return true 表示已登录
     */
    @GetMapping("/check-login")
    public Result<Boolean> checkLogin() {
        LoginHelper.checkLogin();
        return Result.success(true);
    }

    /**
     * 获取当前线程注入的租户 ID 上下文
     *
     * @return 当前租户 ID
     */
    @GetMapping("/current-tenant")
    public Result<String> getCurrentTenant() {
        LoginHelper.checkLogin();
        return Result.success(TenantContextHolder.getTenantId());
    }

    /**
     * 创建订单测试接口（验证审计字段 createBy 与租户 tenantId 自动填充）
     *
     * @param dto 订单创建数据载荷
     * @return 订单 ID
     */
    @PostMapping("/order")
    public Result<Long> createOrder(@RequestBody @org.springframework.validation.annotation.Validated io.github.atengk.boot.sample.satoken.dto.OrderCreateDTO dto) {
        LoginHelper.checkLogin();
        Long orderId = sampleService.createOrder(dto.getOrderNo(), dto.getAmount());
        return Result.success(orderId);
    }

    /**
     * 开启指定业务的二级安全认证
     *
     * @return 成功响应
     */
    @PostMapping("/open-safe")
    public Result<Void> openSafe() {
        LoginHelper.checkLogin();
        LoginHelper.openSafe("payment", 60);
        return Result.success();
    }

    /**
     * 编程式二级安全受保护接口
     *
     * @return 业务响应
     */
    @GetMapping("/safe-action")
    public Result<String> safeAction() {
        LoginHelper.checkLogin();
        LoginHelper.checkSafe("payment");
        return Result.success("支付操作通过");
    }

    /**
     * AOP 注解式二级安全受保护接口
     *
     * @return 业务响应
     */
    @GetMapping("/check-safe-annotation")
    @SaCheckSafe("payment")
    public Result<String> checkSafeAnnotation() {
        return Result.success("注解式安全支付通过");
    }

    /**
     * 关闭指定业务的二级安全认证
     *
     * @return 成功响应
     */
    @PostMapping("/close-safe")
    public Result<Void> closeSafe() {
        LoginHelper.checkLogin();
        LoginHelper.closeSafe("payment");
        return Result.success();
    }

    /**
     * AOP 注解角色校验接口（成功用例）
     *
     * @return 业务响应
     */
    @GetMapping("/check-role")
    @SaCheckRole("super_admin")
    public Result<String> checkRole() {
        return Result.success("角色校验通过");
    }

    /**
     * AOP 注解角色校验接口（权限不足用例）
     *
     * @return 业务响应
     */
    @GetMapping("/check-role-forbidden")
    @SaCheckRole("financial_boss")
    public Result<String> checkRoleForbidden() {
        return Result.success("财务主管角色通过");
    }

    /**
     * AOP 注解权限码校验接口（成功用例）
     *
     * @return 业务响应
     */
    @GetMapping("/check-permission")
    @SaCheckPermission("user:query")
    public Result<String> checkPermission() {
        return Result.success("权限码校验通过");
    }

    /**
     * AOP 注解权限码校验接口（权限不足用例）
     *
     * @return 业务响应
     */
    @GetMapping("/check-permission-forbidden")
    @SaCheckPermission("user:delete")
    public Result<String> checkPermissionForbidden() {
        return Result.success("用户删除权限通过");
    }

    /**
     * 调用 Service 业务层 AOP 角色校验
     *
     * @return 业务响应
     */
    @GetMapping("/service-role")
    public Result<String> callServiceRole() {
        return Result.success(sampleService.checkServiceRole());
    }

    /**
     * 调用 Service 业务层 AOP 权限码校验
     *
     * @return 业务响应
     */
    @GetMapping("/service-permission")
    public Result<String> callServicePermission() {
        return Result.success(sampleService.checkServicePermission());
    }

    /**
     * 白名单公共测试接口
     *
     * @return 响应结果
     */
    @GetMapping("/public/ping")
    public Result<String> publicPing() {
        return Result.success("pong");
    }

    /**
     * 即时刷新指定角色关联的权限缓存
     *
     * @param body 请求体（含 roleCode）
     * @return 成功响应
     */
    @PostMapping("/cache/refresh-role")
    public Result<Void> refreshRoleCache(@RequestBody Map<String, String> body) {
        LoginHelper.checkLogin();
        String roleCode = body.get("roleCode");
        permissionCacheService.refreshRolePermissions(roleCode);
        return Result.success();
    }

    /**
     * 即时刷新指定用户的角色与权限缓存
     *
     * @param body 请求体（含 userId）
     * @return 成功响应
     */
    @PostMapping("/cache/refresh-user")
    public Result<Void> refreshUserCache(@RequestBody Map<String, String> body) {
        LoginHelper.checkLogin();
        String userId = body.get("userId");
        permissionCacheService.refreshUserPermissions(userId);
        return Result.success();
    }

    /**
     * 清空全域两级权限缓存
     *
     * @return 成功响应
     */
    @PostMapping("/cache/clear-all")
    public Result<Void> clearAllCache() {
        LoginHelper.checkLogin();
        permissionCacheService.clearAll();
        return Result.success();
    }

    /**
     * 获取当前登录用户的角色编码列表
     *
     * @return 角色列表
     */
    @GetMapping("/current-roles")
    public Result<java.util.List<String>> getCurrentRoles() {
        LoginHelper.checkLogin();
        return Result.success(cn.dev33.satoken.stp.StpUtil.getRoleList());
    }

    /**
     * 获取当前登录用户的权限码列表
     *
     * @return 权限码列表
     */
    @GetMapping("/current-permissions")
    public Result<java.util.List<String>> getCurrentPermissions() {
        LoginHelper.checkLogin();
        return Result.success(cn.dev33.satoken.stp.StpUtil.getPermissionList());
    }

    /**
     * 主动将指定用户踢下线
     *
     * @param body 请求体（含 userId）
     * @return 成功响应
     */
    @PostMapping("/kickout")
    public Result<Void> kickout(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String device = body.get("device");
        if (device != null && !device.isBlank()) {
            LoginHelper.kickout(userId, device);
        } else {
            LoginHelper.kickout(userId);
        }
        return Result.success();
    }

    /**
     * 标注 @CheckSameToken 的微服务内部机密数据接口
     *
     * @return 业务数据
     */
    @GetMapping("/internal/data")
    @io.github.atengk.boot.satoken.annotation.CheckSameToken
    public Result<String> getInternalData() {
        return Result.success("内部机密数据访问成功");
    }

    /**
     * 修改个人资料接口（直接通过 SpEL 校验 userId 数据所有权）
     *
     * @param dto 个人资料
     * @return 响应结果
     */
    @PostMapping("/user/profile")
    @CheckDataOwner(expr = "#dto.userId")
    public Result<String> updateProfile(@RequestBody UserProfileDTO dto) {
        return Result.success("个人资料更新成功: " + dto.getUserId());
    }

    /**
     * 订单取消接口（间接通过 SPI 反查 orderId 数据所有权）
     *
     * @param orderId 订单标识
     * @return 响应结果
     */
    @PostMapping("/order/{orderId}/cancel")
    @CheckDataOwner(expr = "#orderId", provider = SampleOrderDataOwnerProvider.class, message = "无权取消该订单")
    public Result<String> cancelOrder(@PathVariable("orderId") Long orderId) {
        return Result.success("订单取消成功: " + orderId);
    }

    /**
     * 编程式本人校验断言
     *
     * @param targetUserId 目标用户标识
     * @return 响应结果
     */
    @PostMapping("/assert/self")
    public Result<String> assertSelf(@RequestParam("targetUserId") String targetUserId) {
        LoginHelper.checkSelf(targetUserId);
        return Result.success("本人校验通过: " + targetUserId);
    }

    /**
     * 编程式租户归属校验断言
     *
     * @param targetTenantId 目标租户标识
     * @return 响应结果
     */
    @PostMapping("/assert/tenant")
    public Result<String> assertTenant(@RequestParam("targetTenantId") String targetTenantId) {
        LoginHelper.checkBelongsToTenant(targetTenantId);
        return Result.success("租户归属校验通过: " + targetTenantId);
    }
}

