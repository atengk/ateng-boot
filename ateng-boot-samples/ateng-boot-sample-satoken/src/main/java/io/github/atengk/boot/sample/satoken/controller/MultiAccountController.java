package io.github.atengk.boot.sample.satoken.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.satoken.dto.LoginRequestDTO;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 多账号体系隔离认证控制器
 * 演示管理端 (admin) 与移动会员端 (member) 独立登录、独立凭据、基于 loginType 的角色权限隔离校验与独立注销
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/multi-account")
@RequiredArgsConstructor
public class MultiAccountController {

    // ==================== 管理端 (admin) 体系 ====================

    /**
     * 管理端用户登录（下发独立 Token 头 satoken-admin）
     *
     * @param request 登录请求
     * @return 签发的 admin 体系 Token
     */
    @PostMapping("/admin/login")
    public Result<String> adminLogin(@RequestBody LoginRequestDTO request) {
        String userId = (request != null && request.getUserId() != null && !request.getUserId().isBlank())
                ? request.getUserId() : "admin-001";
        String username = (request != null && request.getUsername() != null && !request.getUsername().isBlank())
                ? request.getUsername() : userId;

        LoginUser loginUser = LoginUser.builder()
                .userId(userId)
                .userAccount(username)
                .userName(username)
                .roles(request != null && request.getRoles() != null ? request.getRoles() : Set.of("super_admin"))
                .permissions(request != null && request.getPermissions() != null ? request.getPermissions() : Set.of("system:manage"))
                .build();

        LoginHelper.login("admin", loginUser);
        return Result.success(LoginHelper.getTokenValue("admin"));
    }

    /**
     * 获取当前管理端登录人主体资料
     *
     * @return 登录用户模型
     */
    @GetMapping("/admin/me")
    public Result<LoginUser> getAdminMe() {
        LoginHelper.checkLogin("admin");
        return Result.success(LoginHelper.getLoginUser("admin"));
    }

    /**
     * 校验管理端专属角色权限（必须具备 admin 体系下的 super_admin 角色）
     *
     * @return 业务结果
     */
    @GetMapping("/admin/manage-roles")
    @SaCheckRole(type = "admin", value = "super_admin")
    public Result<String> checkAdminManageRoles() {
        return Result.success("管理员特权接口校验通过");
    }

    /**
     * 管理端用户注销下线
     *
     * @return 成功响应
     */
    @PostMapping("/admin/logout")
    public Result<Void> adminLogout() {
        LoginHelper.logout("admin");
        return Result.success();
    }

    // ==================== 移动会员端 (member) 体系 ====================

    /**
     * 会员端用户登录（下发独立 Token 头 satoken-member）
     *
     * @param request 登录请求
     * @return 签发的 member 体系 Token
     */
    @PostMapping("/member/login")
    public Result<String> memberLogin(@RequestBody LoginRequestDTO request) {
        String userId = (request != null && request.getUserId() != null && !request.getUserId().isBlank())
                ? request.getUserId() : "member-001";
        String username = (request != null && request.getUsername() != null && !request.getUsername().isBlank())
                ? request.getUsername() : userId;

        LoginUser loginUser = LoginUser.builder()
                .userId(userId)
                .userAccount(username)
                .userName(username)
                .roles(request != null && request.getRoles() != null ? request.getRoles() : Set.of("vip_member"))
                .permissions(request != null && request.getPermissions() != null ? request.getPermissions() : Set.of("member:points:view"))
                .build();

        LoginHelper.login("member", loginUser);
        return Result.success(LoginHelper.getTokenValue("member"));
    }

    /**
     * 获取当前会员端登录人主体资料
     *
     * @return 登录用户模型
     */
    @GetMapping("/member/me")
    public Result<LoginUser> getMemberMe() {
        LoginHelper.checkLogin("member");
        return Result.success(LoginHelper.getLoginUser("member"));
    }

    /**
     * 校验会员端专属细粒度权限（必须具备 member 体系下的 member:points:view 权限）
     *
     * @return 业务结果
     */
    @GetMapping("/member/points")
    @SaCheckPermission(type = "member", value = "member:points:view")
    public Result<String> checkMemberPoints() {
        return Result.success("会员积分特权接口校验通过");
    }

    /**
     * 会员端用户注销下线
     *
     * @return 成功响应
     */
    @PostMapping("/member/logout")
    public Result<Void> memberLogout() {
        LoginHelper.logout("member");
        return Result.success();
    }
}
