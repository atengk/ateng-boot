package io.github.atengk.boot.satoken.cache;

import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 默认角色与权限加载器实现
 * 开箱即用从当前活跃会话 LoginUser 强类型数据模型中提取角色编码与权限码
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class DefaultUserRolePermissionLoader implements UserRolePermissionLoader {

    @Override
    public List<String> loadRolesByUserId(String userId) {
        try {
            LoginUser loginUser = LoginHelper.getLoginUser();
            if (loginUser != null && loginUser.getRoles() != null && !loginUser.getRoles().isEmpty()) {
                return new ArrayList<>(loginUser.getRoles());
            }
        } catch (Exception ignored) {
            // 当不在活跃 Web 请求上下文或无当前会话时，安全回退返回空集合
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
        try {
            LoginUser loginUser = LoginHelper.getLoginUser();
            if (loginUser != null && loginUser.getPermissions() != null && !loginUser.getPermissions().isEmpty()) {
                return new ArrayList<>(loginUser.getPermissions());
            }
        } catch (Exception ignored) {
            // 当不在活跃 Web 请求上下文或无当前会话时，安全回退返回空集合
        }
        return Collections.emptyList();
    }
}
