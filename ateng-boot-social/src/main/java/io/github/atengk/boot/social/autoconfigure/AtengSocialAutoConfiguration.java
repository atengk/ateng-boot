package io.github.atengk.boot.social.autoconfigure;

import io.github.atengk.boot.social.http.SpringRestClientHttpImpl;
import io.github.atengk.boot.social.properties.AtengSocialProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.anduin.http.HttpClientAdapter;
import org.dromara.anduin.http.UnifiedHttpClientAccessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 社交登录与 OAuth2 自动装配类
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(name = "org.dromara.anduin.AuthRequestBuilder")
@ConditionalOnProperty(prefix = "ateng.boot.social", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengSocialProperties.class)
@RequiredArgsConstructor
public class AtengSocialAutoConfiguration {

    private final AtengSocialProperties properties;

    /**
     * 自动装配默认基于 Spring 6 RestClient 的 HTTP 适配器
     *
     * @return HttpClientAdapter 实例
     */
    @Bean
    @ConditionalOnMissingBean(HttpClientAdapter.class)
    public HttpClientAdapter socialHttpClientAdapter() {
        java.net.Proxy proxy = properties.getProxy() != null ? properties.getProxy().toProxy() : null;
        SpringRestClientHttpImpl adapter = new SpringRestClientHttpImpl(
                properties.getConnectTimeout(),
                properties.getReadTimeout(),
                proxy
        );
        UnifiedHttpClientAccessor.setAdapter(adapter);
        log.info("Initialized Spring 6 RestClient adapter for Social OAuth2 communication");
        return adapter;
    }

    @PostConstruct
    public void init() {
        log.info("ateng-boot-social autoconfiguration activated successfully");
    }
}
