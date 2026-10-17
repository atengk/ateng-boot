package io.github.atengk.boot.sample.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaCheckSafe;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mybatisplus.context.TenantContextHolder;
import io.github.atengk.boot.sample.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.mapper.TenantOrderMapper;
import io.github.atengk.boot.sample.service.SaTokenSampleService;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    private final TenantOrderMapper orderMapper;

    /**
     * 用户登录接口
     *
     * @param loginUser 登录实体
     * @return 签发的访问凭据 Token
     */
    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginUser loginUser) {
        LoginHelper.login(loginUser);
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
     * @param req 请求载荷
     * @return 订单 ID
     */
    @PostMapping("/order")
    public Result<Long> createOrder(@RequestBody Map<String, Object> req) {
        LoginHelper.checkLogin();
        TenantOrderEntity order = new TenantOrderEntity();
        order.setOrderNo((String) req.get("orderNo"));
        if (req.get("amount") != null) {
            order.setAmount(new BigDecimal(req.get("amount").toString()));
        }
        orderMapper.insert(order);
        return Result.success(order.getId());
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
}
