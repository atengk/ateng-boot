package io.github.atengk.boot.satoken.cache;

import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 默认角色与权限加载器实现
 * 开箱即用从当前活跃会话 LoginUser 强类型数据模型或对应 SaSession 中提取角色编码与权限码
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public class DefaultUserRolePermissionLoader implements UserRolePermissionLoader {

    @Override
    public List<String> loadRolesByUserId(String userId) {
        return loadRolesByUserId(userId, null, null);
    }

    @Override
    public List<String> loadRolesByUserId(String userId, String loginType) {
        return loadRolesByUserId(userId, loginType, null);
    }

    @Override
    public List<String> loadRolesByUserId(String userId, String loginType, String tenantId) {
        try {
            LoginUser targetUser = LoginHelper.resolveTargetLoginUser(userId, loginType, tenantId);
            if (targetUser != null && targetUser.getRoles() != null && !targetUser.getRoles().isEmpty()) {
                return new ArrayList<>(targetUser.getRoles());
            }
        } catch (RuntimeException ex) {
            // 当不在活跃 Web 请求上下文或无当前会话时，安全回退返回空集合
            log.debug("默认加载器解析用户角色失败（已降级为空集合）: userId={}, loginType={}, tenantId={}, error={}",
                    userId, loginType, tenantId, ex.toString());
        }
        return Collections.emptyList();
    }

    @Override
    public List<String> loadPermissionsByRole(String roleCode) {
        // 默认模式下，角色对应的权限已打包在 LoginUser 的全局 permissions 集合中，由直属权限统一承接
        return Collections.emptyList();
    }

    @Override
    public List<String> loadDirectPermissionsByUserId(String userId) {
        return loadDirectPermissionsByUserId(userId, null, null);
    }

    @Override
    public List<String> loadDirectPermissionsByUserId(String userId, String loginType) {
        return loadDirectPermissionsByUserId(userId, loginType, null);
    }

    @Override
    public List<String> loadDirectPermissionsByUserId(String userId, String loginType, String tenantId) {
        try {
            LoginUser targetUser = LoginHelper.resolveTargetLoginUser(userId, loginType, tenantId);
            if (targetUser != null && targetUser.getPermissions() != null && !targetUser.getPermissions().isEmpty()) {
                return new ArrayList<>(targetUser.getPermissions());
            }
        } catch (RuntimeException ex) {
            // 当不在活跃 Web 请求上下文或无当前会话时，安全回退返回空集合
            log.debug("默认加载器解析用户直属权限失败（已降级为空集合）: userId={}, loginType={}, tenantId={}, error={}",
                    userId, loginType, tenantId, ex.toString());
        }
        return Collections.emptyList();
    }
}
