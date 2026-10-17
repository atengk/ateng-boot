package io.github.atengk.boot.satoken.cache;

import java.util.Collections;
import java.util.List;

/**
 * 业务数据源角色与权限加载器契约
 * 供业务系统自定义实现从底层持久层加载角色与权限映射，未自定义时默认从当前会话载荷读取
 *
 * @author Ateng
 * @since 2026-10-17
 */
public interface UserRolePermissionLoader {

    /**
     * 根据用户 ID 加载其拥有的角色编码列表
     *
     * @param userId 用户唯一标识
     * @return 角色列表
     */
    List<String> loadRolesByUserId(String userId);

    /**
     * 根据角色编码加载该角色关联的权限码列表
     *
     * @param roleCode 角色编码
     * @return 权限码列表
     */
    List<String> loadPermissionsByRole(String roleCode);

    /**
     * 根据用户 ID 加载用户个人特有的额外直属权限码列表（可选）
     *
     * @param userId 用户唯一标识
     * @return 直属权限码列表
     */
    default List<String> loadDirectPermissionsByUserId(String userId) {
        return Collections.emptyList();
    }
}
