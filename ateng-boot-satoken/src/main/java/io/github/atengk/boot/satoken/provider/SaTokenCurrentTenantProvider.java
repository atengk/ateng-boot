package io.github.atengk.boot.satoken.provider;

import io.github.atengk.boot.core.spi.CurrentTenantProvider;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.util.LoginHelper;

/**
 * Sa-Token 当前登录租户 SPI 适配提供者
 * 对接 ateng-boot-core 的 CurrentTenantProvider SPI 契约，自动驱动多租户行级与列级数据隔离；
 * 支持多账号体系动态路由与 primary-login-type 优先探测
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class SaTokenCurrentTenantProvider implements CurrentTenantProvider {

    private final SaTokenProperties properties;

    public SaTokenCurrentTenantProvider() {
        this(null);
    }

    public SaTokenCurrentTenantProvider(SaTokenProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getCurrentTenantId() {
        String preferredType = properties != null ? properties.getPrimaryLoginType() : null;
        String activeType = LoginHelper.getActiveLoginType(preferredType);
        if (activeType != null) {
            return LoginHelper.getTenantId(activeType);
        }
        if (LoginHelper.isLogin()) {
            return LoginHelper.getTenantId();
        }
        return null;
    }
}
