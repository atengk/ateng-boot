package io.github.atengk.boot.satoken.provider;

import io.github.atengk.boot.core.spi.CurrentTenantProvider;
import io.github.atengk.boot.satoken.util.LoginHelper;

/**
 * Sa-Token 当前登录租户 SPI 适配提供者
 * 对接 ateng-boot-core 的 CurrentTenantProvider SPI 契约，自动驱动多租户行级与列级数据隔离
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class SaTokenCurrentTenantProvider implements CurrentTenantProvider {

    @Override
    public String getCurrentTenantId() {
        if (!LoginHelper.isLogin()) {
            return null;
        }
        return LoginHelper.getTenantId();
    }
}
