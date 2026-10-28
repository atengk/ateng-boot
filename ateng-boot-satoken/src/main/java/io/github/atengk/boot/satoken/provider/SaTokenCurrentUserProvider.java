package io.github.atengk.boot.satoken.provider;

import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.satoken.util.LoginHelper;

/**
 * Sa-Token 当前登录操作人与双维身份模拟 SPI 适配提供者
 * 对接 ateng-boot-core 的 CurrentUserProvider SPI 契约，自动驱动持久层审计自动填充、操作日志双维身份记录与数据权限引擎
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class SaTokenCurrentUserProvider implements CurrentUserProvider {

    @Override
    public String getCurrentUser() {
        return LoginHelper.getUserId();
    }

    @Override
    public String getOriginalUser() {
        return LoginHelper.getOriginalUserId();
    }

    @Override
    public boolean isRunAs() {
        return LoginHelper.isRunAs();
    }
}
