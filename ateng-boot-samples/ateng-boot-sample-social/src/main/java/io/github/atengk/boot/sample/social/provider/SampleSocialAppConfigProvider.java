package io.github.atengk.boot.sample.social.provider;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.social.config.SocialAppConfig;
import io.github.atengk.boot.social.spi.SocialAppConfigProvider;
import org.springframework.stereotype.Component;

/**
 * 社交应用动态切源与 SaaS 多租户配置提供者样例
 * <p>
 * 演示在多租户环境下通过 {@link SocialAppConfigProvider} SPI 动态切源，
 * 优先根据租户上下文动态加载第三方应用授权配置，未匹配时平滑降级为 YAML 静态配置。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Component
public class SampleSocialAppConfigProvider implements SocialAppConfigProvider {

    @Override
    public SocialAppConfig getAppConfig(String appKey) {
        String tenantId = TenantContextHolder.getTenantId();
        // 演示场景 1：按特定 appKey 提供 SaaS 动态配置
        if ("tenant_saas_app".equals(appKey)) {
            return SocialAppConfig.builder()
                    .appKey(appKey)
                    .source("GITHUB")
                    .clientId("dynamic_saas_client_id")
                    .clientSecret("dynamic_saas_client_secret")
                    .redirectUri("http://localhost:8080/sample/social/oauth/callback/" + appKey)
                    .build();
        }
        // 演示场景 2：结合当前线程租户上下文动态切源
        if ("tenant_saas_01".equals(tenantId) && "saas_github".equals(appKey)) {
            return SocialAppConfig.builder()
                    .appKey(appKey)
                    .source("GITHUB")
                    .clientId("tenant_01_client_id")
                    .clientSecret("tenant_01_client_secret")
                    .redirectUri("http://localhost:8080/sample/social/oauth/callback/" + appKey)
                    .build();
        }
        return null;
    }
}
