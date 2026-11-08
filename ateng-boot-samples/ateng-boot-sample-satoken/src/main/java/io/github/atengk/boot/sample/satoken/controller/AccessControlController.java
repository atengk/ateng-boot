package io.github.atengk.boot.sample.satoken.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaCheckSafe;
import cn.dev33.satoken.annotation.SaMode;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.satoken.dto.SettlementRequestDTO;
import io.github.atengk.boot.sample.satoken.dto.UserProfileDTO;
import io.github.atengk.boot.sample.satoken.provider.SampleOrderDataOwnerProvider;
import io.github.atengk.boot.sample.satoken.service.SaTokenSampleService;
import io.github.atengk.boot.satoken.annotation.CheckDataOwner;
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
 * 细粒度访问控制控制器
 * 演示 RBAC 角色/权限校验、二级安全认证与基于 @CheckDataOwner 的水平越权防御
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/access")
@RequiredArgsConstructor
public class AccessControlController {

    private final SaTokenSampleService sampleService;

    /**
     * 多角色 OR 逻辑校验（需具备 audit_manager 或 finance_director 角色）
     *
     * @return 业务结果
     */
    @GetMapping("/role-audit")
    @SaCheckRole(value = {"audit_manager", "finance_director"}, mode = SaMode.OR)
    public Result<String> checkRoleAudit() {
        return Result.success("审计角色鉴权通过");
    }

    /**
     * 单角色严格校验（必须具备 finance_director 角色）
     *
     * @return 业务结果
     */
    @GetMapping("/role-finance-only")
    @SaCheckRole("finance_director")
    public Result<String> checkRoleFinanceOnly() {
        return Result.success("财务总监专属鉴权通过");
    }

    /**
     * 细粒度权限码校验（需要 order:query 权限）
     *
     * @return 业务结果
     */
    @GetMapping("/perm-order-query")
    @SaCheckPermission("order:query")
    public Result<String> checkPermOrderQuery() {
        return Result.success("订单查询权限鉴权通过");
    }

    /**
     * 细粒度高危权限码校验（需要 order:delete 权限）
     *
     * @return 业务结果
     */
    @GetMapping("/perm-order-delete")
    @SaCheckPermission("order:delete")
    public Result<String> checkPermOrderDelete() {
        return Result.success("订单删除高危权限鉴权通过");
    }

    /**
     * 穿透至 Service 层执行声明式角色注解校验
     *
     * @return 业务结果
     */
    @GetMapping("/service-role")
    public Result<String> checkServiceRole() {
        return Result.success(sampleService.checkServiceRole());
    }

    /**
     * 穿透至 Service 层执行声明式权限注解校验
     *
     * @return 业务结果
     */
    @GetMapping("/service-permission")
    public Result<String> checkServicePermission() {
        return Result.success(sampleService.checkServicePermission());
    }

    /**
     * 开启二级安全认证（在指定超时时间内无需重复校验密码）
     *
     * @param timeout 有效期秒数，默认 120 秒
     * @return 生效有效期
     */
    @PostMapping("/open-safe")
    public Result<Integer> openSafe(@RequestParam(value = "timeout", defaultValue = "120") int timeout) {
        LoginHelper.openSafe(timeout);
        return Result.success(timeout);
    }

    /**
     * 提前关闭二级安全认证时间窗
     *
     * @return 成功响应
     */
    @PostMapping("/close-safe")
    public Result<Void> closeSafe() {
        LoginHelper.closeSafe();
        return Result.success();
    }

    /**
     * 编程式二级认证大额资金结算
     *
     * @param dto 结算请求传输对象
     * @return 结算成功响应
     */
    @PostMapping("/safe-settle")
    public Result<String> safeSettleProgrammatic(@Valid @RequestBody SettlementRequestDTO dto) {
        LoginHelper.checkSafe();
        return Result.success("大额资金结算已完成: " + dto.getAmount());
    }

    /**
     * 声明式 @SaCheckSafe 二级认证大额资金结算
     *
     * @param dto 结算请求传输对象
     * @return 结算成功响应
     */
    @PostMapping("/safe-settle-anno")
    @SaCheckSafe
    public Result<String> safeSettleDeclarative(@Valid @RequestBody SettlementRequestDTO dto) {
        return Result.success("大额资金结算已完成 (声明式): " + dto.getAmount());
    }

    /**
     * 基于 @CheckDataOwner 的用户个人资料防水平越权修改
     *
     * @param dto 资料更新传输对象
     * @return 业务结果
     */
    @PostMapping("/profile/update")
    @CheckDataOwner(expr = "#dto.userId")
    public Result<String> updateProfile(@Valid @RequestBody UserProfileDTO dto) {
        return Result.success("个人资料更新成功: " + dto.getNickname());
    }

    /**
     * 基于 @CheckDataOwner 与 DataOwnerProvider 反查 SPI 的订单防水平越权取消
     *
     * @param orderId 订单标识
     * @return 业务结果
     */
    @PostMapping("/orders/{orderId}/cancel")
    @CheckDataOwner(expr = "#orderId", provider = SampleOrderDataOwnerProvider.class)
    public Result<String> cancelOrder(@PathVariable("orderId") Long orderId) {
        return Result.success("订单已成功取消: " + orderId);
    }

    /**
     * 编程式断言当前操作人本人归属
     *
     * @param userId 目标用户标识
     * @return 业务结果
     */
    @PostMapping("/assert-self")
    public Result<String> assertSelf(@RequestParam("userId") String userId) {
        LoginHelper.checkSelf(userId);
        return Result.success("本人所有权断言校验通过: " + userId);
    }

    /**
     * 编程式断言目标租户与当前操作人所在租户一致
     *
     * @param tenantId 目标租户标识
     * @return 业务结果
     */
    @PostMapping("/assert-tenant")
    public Result<String> assertTenant(@RequestParam("tenantId") String tenantId) {
        LoginHelper.checkBelongsToTenant(tenantId);
        return Result.success("当前租户归属断言校验通过: " + tenantId);
    }
}
