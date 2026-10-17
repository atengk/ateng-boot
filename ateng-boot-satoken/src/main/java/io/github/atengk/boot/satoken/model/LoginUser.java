package io.github.atengk.boot.satoken.model;

import cn.dev33.satoken.json.SaJsonType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 登录主体强类型业务实体模型
 * 承载用户基础身份、权限码集合、角色集合、部门与多租户元数据，实现全链路操作人上下文穿透
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class LoginUser implements SaJsonType, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户唯一标识（主键 ID）
     */
    private String userId;

    /**
     * 用户登录账号
     */
    private String userAccount;

    /**
     * 用户姓名或昵称
     */
    private String userName;

    /**
     * 用户身份类别标识（如 default, admin, member 等）
     */
    @Builder.Default
    private String userType = "default";

    /**
     * 用户所属部门唯一标识
     */
    private String deptId;

    /**
     * 用户归属的多租户唯一标识
     */
    private String tenantId;

    /**
     * 用户拥有的角色编码集合
     */
    private Set<String> roles;

    /**
     * 用户拥有的操作权限码集合
     */
    private Set<String> permissions;

    /**
     * 业务自定义动态扩展元数据字典
     */
    private Map<String, Object> extraData;

    /**
     * 获取不可变的角色编码集合（空安全防御）
     *
     * @return 角色集合，无角色时返回空集合
     */
    public Set<String> getRoles() {
        return roles != null ? Collections.unmodifiableSet(roles) : Collections.emptySet();
    }

    /**
     * 获取不可变的权限码集合（空安全防御）
     *
     * @return 权限集合，无权限时返回空集合
     */
    public Set<String> getPermissions() {
        return permissions != null ? Collections.unmodifiableSet(permissions) : Collections.emptySet();
    }

    /**
     * 判断当前用户是否具备指定角色
     *
     * @param role 待校验角色码
     * @return true 表示具备
     */
    public boolean hasRole(String role) {
        return role != null && roles != null && roles.contains(role);
    }

    /**
     * 判断当前用户是否具备指定权限码
     *
     * @param permission 待校验权限码
     * @return true 表示具备
     */
    public boolean hasPermission(String permission) {
        return permission != null && permissions != null && permissions.contains(permission);
    }

    /**
     * 存入扩展业务键值对
     *
     * @param key   键
     * @param value 值
     * @return 自身引用
     */
    public LoginUser setExtra(String key, Object value) {
        if (this.extraData == null) {
            this.extraData = new HashMap<>();
        }
        this.extraData.put(key, value);
        return this;
    }

    /**
     * 获取指定扩展业务键对应的值
     *
     * @param key 键
     * @return 对应值，不存在时返回 null
     */
    public Object getExtra(String key) {
        return this.extraData != null ? this.extraData.get(key) : null;
    }
}
