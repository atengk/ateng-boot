package io.github.atengk.boot.satoken.provider;

import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.satoken.util.LoginHelper;

/**
 * Sa-Token 当前登录操作人 SPI 适配提供者
 * 对接 ateng-boot-core 的 CurrentUserProvider SPI 契约，自动驱动持久层审计自动填充与数据权限引擎
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class SaTokenCurrentUserProvider implements CurrentUserProvider {

    @Override
    public String getCurrentUser() {
        if (!LoginHelper.isLogin()) {
            return null;
        }
        return LoginHelper.getUserId();
    }
}
