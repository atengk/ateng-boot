package io.github.atengk.boot.social.spi;

import io.github.atengk.boot.social.config.SocialAppConfig;

/**
 * 社交应用配置动态提取与切源 SPI 扩展点
 * <p>
 * 支持多租户、SaaS 平台或基于数据库动态维护第三方应用配置的场景。
 * 框架在获取应用配置时，优先调用本 SPI 进行动态解析；若返回 null，则回退查找本地 YAML 静态配置。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public interface SocialAppConfigProvider {

    /**
     * 根据应用唯一标识（及当前线程租户上下文）动态获取社交应用配置
     *
     * @param appKey 应用唯一标识
     * @return 动态应用配置；若不存在或未接管该 appKey 则返回 null 以回退本地静态配置
     */
    SocialAppConfig getAppConfig(String appKey);
}
