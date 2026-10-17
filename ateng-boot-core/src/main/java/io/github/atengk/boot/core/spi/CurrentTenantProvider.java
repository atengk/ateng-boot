package io.github.atengk.boot.core.spi;

/**
 * 当前租户标识提取函数式 SPI 接口
 * 供外部安全鉴权体系（如 Sa-Token、网关请求头、Cookie 等）无缝接入提供当前租户信息
 *
 * @author Ateng
 * @since 2026-10-17
 */
@FunctionalInterface
public interface CurrentTenantProvider {

    /**
     * 获取当前租户唯一标识
     *
     * @return 当前租户标识，未识别或未登录时返回 null
     */
    String getCurrentTenantId();
}
