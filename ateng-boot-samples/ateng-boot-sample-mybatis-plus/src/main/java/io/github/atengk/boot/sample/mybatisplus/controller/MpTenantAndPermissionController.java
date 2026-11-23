package io.github.atengk.boot.sample.mybatisplus.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.mybatisplus.model.DataPermissionCompareVO;
import io.github.atengk.boot.sample.mybatisplus.model.TenantIsolationCompareVO;
import io.github.atengk.boot.sample.mybatisplus.model.TenantOrderVO;
import io.github.atengk.boot.sample.mybatisplus.model.UserVO;
import io.github.atengk.boot.sample.mybatisplus.service.DynamicDatasourceGuardScenarioService;
import io.github.atengk.boot.sample.mybatisplus.service.TenantOrderService;
import io.github.atengk.boot.sample.mybatisplus.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SaaS 行级多租户隔离、AST 细粒度数据权限与多源守卫体验控制器
 * 对应模块文档第 5 章，演示 AtengTenantLineInnerInterceptor 自动隔离、@IgnoreTenant 与 runWithoutTenant 跨租户统计、
 * @DataScope 部门与本人数据权限过滤、runAsSuperAdmin / runWithoutPermission 秒级短路放行及 DynamicDatasourceGuard 上下文守卫。
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/mybatis-plus/governance")
@RequiredArgsConstructor
public class MpTenantAndPermissionController {

    private final TenantOrderService tenantOrderService;
    private final UserService userService;
    private final DynamicDatasourceGuardScenarioService guardScenarioService;

    /**
     * 在指定租户上下文下查询订单列表（底层由 AtengTenantLineInnerInterceptor 自动注入 WHERE tenant_id = ?）
     *
     * @param tenantId 租户标识（默认 tenant_A）
     * @return 当前租户可见的订单视图列表
     */
    @GetMapping("/tenant/orders")
    public Result<List<TenantOrderVO>> listTenantOrders(
            @RequestParam(defaultValue = "tenant_A") String tenantId) {
        return Result.success(tenantOrderService.listTenantOrders(tenantId));
    }

    /**
     * 对比验证行级多租户自动隔离与 @IgnoreTenant 注解 / TenantContextHolder.runWithoutTenant 跨租户平台汇总统计
     *
     * @param currentTenantId 当前绑定的租户标识（默认 tenant_A）
     * @return 多租户隔离与跨租户平台汇总对比结果
     */
    @GetMapping("/tenant/platform-summary")
    public Result<TenantIsolationCompareVO> getTenantPlatformSummary(
            @RequestParam(defaultValue = "tenant_A") String currentTenantId) {
        return Result.success(tenantOrderService.compareTenantIsolationAndBypass(currentTenantId));
    }

    /**
     * 基于 @DataScope AST 细粒度数据权限（部门列表 + 本人操作人）查询可见用户列表
     *
     * @param deptIds      授权可访问的部门 ID 列表（默认 10,20）
     * @param selfOperator 本人操作人标识（可选，传入时叠加 OR create_by = ? 本人数据权限）
     * @return 过滤后的可见用户视图列表
     */
    @GetMapping("/permission/users")
    public Result<List<UserVO>> listAccessibleUsers(
            @RequestParam(defaultValue = "10,20") List<Long> deptIds,
            @RequestParam(required = false) String selfOperator) {
        return Result.success(userService.listAccessibleUsersByDataScope(deptIds, selfOperator));
    }

    /**
     * 对比验证 @DataScope 部门权限过滤与 DataPermissionContextHolder.runAsSuperAdmin / runWithoutPermission 短路放行
     *
     * @param deptIds      授权可访问的部门 ID 列表（默认 10）
     * @param selfOperator 本人操作人标识（可选）
     * @return 数据权限过滤与短路放行对比视图
     */
    @GetMapping("/permission/bypass-compare")
    public Result<DataPermissionCompareVO> compareDataPermissionBypass(
            @RequestParam(defaultValue = "10") List<Long> deptIds,
            @RequestParam(required = false) String selfOperator) {
        return Result.success(userService.compareDataPermissionAndBypass(deptIds, selfOperator));
    }

    /**
     * 执行多数据源上下文隔离守卫自检与活跃事务假切源阻断演练
     *
     * @param mode             演练模式（clean / active-tx / super-admin / ignore-permission / tenant-check）
     * @param currentTenantId  当前租户 ID（tenant-check 模式下可选）
     * @param expectedTenantId 期望租户 ID（tenant-check 模式下可选）
     * @return 校验诊断结果
     */
    @PostMapping("/datasource-guard/check")
    public Result<Map<String, Object>> checkDatasourceGuard(
            @RequestParam(defaultValue = "clean") String mode,
            @RequestParam(required = false) String currentTenantId,
            @RequestParam(required = false) String expectedTenantId) {
        return Result.success(guardScenarioService.verifyGuardByMode(mode, currentTenantId, expectedTenantId));
    }
}
