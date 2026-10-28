package io.github.atengk.boot.satoken.provider;

import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.util.LoginHelper;

/**
 * Sa-Token 当前登录操作人与双维身份模拟 SPI 适配提供者
 * 对接 ateng-boot-core 的 CurrentUserProvider SPI 契约，自动驱动持久层审计自动填充、操作日志双维身份记录与数据权限引擎；
 * 支持多账号体系动态路由与 primary-login-type 优先探测
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class SaTokenCurrentUserProvider implements CurrentUserProvider {

    private final SaTokenProperties properties;

    public SaTokenCurrentUserProvider() {
        this(null);
    }

    public SaTokenCurrentUserProvider(SaTokenProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getCurrentUser() {
        if (LoginHelper.isRunAs()) {
            return LoginHelper.getUserId();
        }
        return LoginHelper.getUserId(resolveEffectiveLoginType());
    }

    @Override
    public String getOriginalUser() {
        if (LoginHelper.isRunAs()) {
            return LoginHelper.getOriginalUserId();
        }
        return LoginHelper.getOriginalUserId(resolveEffectiveLoginType());
    }

    @Override
    public boolean isRunAs() {
        if (LoginHelper.isRunAs()) {
            return true;
        }
        return LoginHelper.isRunAs(resolveEffectiveLoginType());
    }

    /**
     * 解析当前生效的目标账号体系类型（优先活跃账号，未命中回退主配置）
     */
    private String resolveEffectiveLoginType() {
        String preferredType = properties != null ? properties.getPrimaryLoginType() : null;
        String activeType = LoginHelper.getActiveLoginType(preferredType);
        if (activeType != null) {
            return activeType;
        }
        return preferredType != null ? preferredType : LoginHelper.getPrimaryLoginType();
    }
}
