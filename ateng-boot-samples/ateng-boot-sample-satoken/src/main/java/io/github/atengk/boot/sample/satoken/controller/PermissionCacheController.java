package io.github.atengk.boot.sample.satoken.controller;

import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.util.LoginHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 权限与角色多级缓存治理控制器
 * 提供缓存角色与权限查询、按角色/用户精准刷新及全量清空端点
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/permission-cache")
@RequiredArgsConstructor
public class PermissionCacheController {

    private final PermissionCacheService permissionCacheService;

    /**
     * 读取当前登录主体经由两级缓存切片解析的角色列表
     *
     * @return 角色编码集合
     */
    @GetMapping("/roles")
    public Result<Set<String>> getCurrentRoles() {
        LoginHelper.checkLogin();
        java.util.List<String> roleList = StpUtil.getRoleList();
        return Result.success(roleList != null ? Set.copyOf(roleList) : java.util.Collections.emptySet());
    }

    /**
     * 读取当前登录主体经由两级缓存切片解析的细粒度权限码集合
     *
     * @return 权限码集合
     */
    @GetMapping("/permissions")
    public Result<Set<String>> getCurrentPermissions() {
        LoginHelper.checkLogin();
        java.util.List<String> permissionList = StpUtil.getPermissionList();
        return Result.success(permissionList != null ? Set.copyOf(permissionList) : java.util.Collections.emptySet());
    }

    /**
     * 精准刷新指定角色关联的权限缓存（秒级淘汰两级缓存并广播异地节点）
     *
     * @param roleCode 目标角色编码
     * @return 业务结果
     */
    @PostMapping("/refresh-role")
    public Result<String> refreshRole(@RequestParam("roleCode") String roleCode) {
        permissionCacheService.refreshRolePermissions(roleCode);
        return Result.success("角色权限缓存已刷新: " + roleCode);
    }

    /**
     * 精准刷新指定用户的角色与直属权限缓存
     *
     * @param userId 目标用户标识
     * @return 业务结果
     */
    @PostMapping("/refresh-user")
    public Result<String> refreshUser(@RequestParam("userId") String userId) {
        permissionCacheService.refreshUserPermissions(userId);
        return Result.success("用户权限缓存已刷新: " + userId);
    }

    /**
     * 清空全量两级权限缓存
     *
     * @return 成功响应
     */
    @PostMapping("/clear-all")
    public Result<Void> clearAll() {
        permissionCacheService.clearAll();
        return Result.success();
    }
}
