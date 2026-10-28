package io.github.atengk.boot.notification.channel;

/**
 * 通知渠道动态路由解析器 SPI
 * 支持应用依据当前多租户上下文（如 TenantContextHolder）或自定义业务标签动态解析并推导当前渠道名称
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface NotificationChannelResolver {

    /**
     * 根据当前运行上下文解析目标渠道名称
     *
     * @return 解析得出的渠道名称，若无法推导或无特殊路由则返回 null（表示回退默认渠道路由策略）
     */
    String resolveChannel();
}
