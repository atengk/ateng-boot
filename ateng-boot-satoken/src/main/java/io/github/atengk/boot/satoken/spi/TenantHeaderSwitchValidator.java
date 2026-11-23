package io.github.atengk.boot.satoken.spi;

/**
 * 请求头租户解析与平台超管切换租户校验 SPI
 * <p>
 * 当开启 {@code ateng.boot.satoken.tenant.allow-anonymous-header=true} 或
 * {@code ateng.boot.satoken.tenant.allow-super-admin-switch=true} 时，
 * {@link io.github.atengk.boot.satoken.interceptor.AtengSaTokenInterceptor} 在通过前置格式合法性校验后，
 * 调用此 SPI 校验目标租户是否允许被匿名访问或平台超管临时切换（例如校验租户是否存在、是否处于启用状态）。
 * 返回 {@code false} 时拦截器将快速失败抛出 {@link io.github.atengk.boot.core.exception.BusinessException}（403）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@FunctionalInterface
public interface TenantHeaderSwitchValidator {

    /**
     * 校验请求头指定的目标租户是否允许生效
     *
     * @param targetTenantId  请求头中提取并清洗后的目标租户标识
     * @param currentTenantId 当前会话所属租户标识（匿名未登录请求为 null）
     * @param loginType       当前请求关联的账号体系标识（匿名且未命中路由时可能为 null）
     * @param anonymous       true 表示匿名未登录请求；false 表示已登录平台超管切换请求
     * @return true 表示校验通过允许生效；false 表示拒绝请求
     */
    boolean validate(String targetTenantId, String currentTenantId, String loginType, boolean anonymous);
}
