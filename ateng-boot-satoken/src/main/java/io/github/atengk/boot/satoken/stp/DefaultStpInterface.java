package io.github.atengk.boot.satoken.stp;

import cn.dev33.satoken.stp.StpInterface;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 默认权限数据源加载实现类
 * 开箱即用从当前线程绑定的 LoginUser 会话上下文中提取权限码与角色标识集合
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class DefaultStpInterface implements StpInterface {

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        LoginUser user = LoginHelper.getLoginUser();
        if (user != null && user.getPermissions() != null && !user.getPermissions().isEmpty()) {
            return new ArrayList<>(user.getPermissions());
        }
        return Collections.emptyList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        LoginUser user = LoginHelper.getLoginUser();
        if (user != null && user.getRoles() != null && !user.getRoles().isEmpty()) {
            return new ArrayList<>(user.getRoles());
        }
        return Collections.emptyList();
    }
}
