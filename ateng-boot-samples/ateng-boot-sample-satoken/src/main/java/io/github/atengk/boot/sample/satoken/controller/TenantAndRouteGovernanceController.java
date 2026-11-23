package io.github.atengk.boot.sample.satoken.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.sample.satoken.vo.GovernanceContextVO;
import io.github.atengk.boot.satoken.annotation.Anonymous;
import io.github.atengk.boot.satoken.annotation.CheckDataOwner;
import io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 路由注解自动识别、数据权限超管桥接、租户头解析与共享路径权限治理控制器
 *
 * @author Ateng
 * @since 2026-11-19
 */
@RestController
@RequestMapping("/sample/satoken/governance")
public class TenantAndRouteGovernanceController {

    /**
     * 匿名登录前租户配置读取接口（演示白名单匿名 X-Tenant-Id 解析与已登录防绕过）
     *
     * @return 当前线程上下文观测视图
     */
    @GetMapping("/public/tenant-config")
    @Anonymous
    public Result<GovernanceContextVO> getPublicTenantConfig() {
        return Result.success(snapshot("public-tenant-config"));
    }

    /**
     * 管理端数据导出接口（未显式声明注解 type，演示按 login-type-routes 自动路由至 admin 体系、
     * DataPermissionContextHolder 超管桥接与平台超管 X-Tenant-Id 受控切租户）
     *
     * @return 当前线程上下文观测视图
     */
    @GetMapping("/admin/users/export")
    @SaCheckPermission("system:user:export")
    public Result<GovernanceContextVO> exportAdminUsers() {
        return Result.success(snapshot("admin-export"));
    }

    /**
     * 会员端订单导出接口（未显式声明注解 type，演示同服务多端自动路由至 member 体系）
     *
     * @return 当前线程上下文观测视图
     */
    @GetMapping("/member/orders/export")
    @SaCheckPermission("member:order:export")
    public Result<GovernanceContextVO> exportMemberOrders() {
        return Result.success(snapshot("member-export"));
    }

    /**
     * 会员端个人主页接口（未显式声明 @CheckDataOwner type，演示按路由上下文自动对齐 member 体系）
     *
     * @param userId 目标会员标识
     * @return 当前线程上下文观测视图
     */
    @GetMapping("/member/profile/{userId}")
    @CheckDataOwner(expr = "#userId")
    public Result<GovernanceContextVO> getMemberProfile(@PathVariable("userId") String userId) {
        return Result.success(snapshot("member-profile:" + userId));
    }

    /**
     * 跨端共享枚举字典查询接口（无方法级注解，由 YAML path-permissions 与 shared-login-type-paths 统一治理）
     *
     * @param dictCode 字典编码
     * @return 当前线程上下文观测视图
     */
    @GetMapping("/shared/enums/{dictCode}")
    public Result<GovernanceContextVO> getSharedEnums(@PathVariable("dictCode") String dictCode) {
        return Result.success(snapshot("shared-enum:" + dictCode));
    }

    private GovernanceContextVO snapshot(String scene) {
        return GovernanceContextVO.builder()
                .userId(UserContextHolder.get())
                .tenantId(TenantContextHolder.getTenantId())
                .routeLoginType(SaTokenRouteContextHolder.get())
                .dataPermissionSuperAdmin(DataPermissionContextHolder.isSuperAdmin())
                .scene(scene)
                .build();
    }
}
