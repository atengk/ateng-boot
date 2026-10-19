package io.github.atengk.boot.satoken.spi;

import java.util.Set;

/**
 * Sa-Token 动态白名单扩展提供者 SPI
 * <p>
 * 允许外部业务模块、微服务网关或配置中心动态注册免鉴权放行路径，
 * 系统在启动期将自动收集所有 Provider 实例并汇入 Sa-Token 路由白名单。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
@FunctionalInterface
public interface SaTokenWhitelistProvider {

    /**
     * 获取动态白名单路径集合
     *
     * @return 白名单路径集合（支持 AntPath 通配符，如 "/public/**"）
     */
    Set<String> getWhitelist();
}
