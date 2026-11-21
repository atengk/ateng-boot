package io.github.atengk.boot.admin.autoconfigure;

import de.codecentric.boot.admin.server.config.AdminServerMarkerConfiguration;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.services.InstanceFilter;
import de.codecentric.boot.admin.server.web.client.BasicAuthHttpHeaderProvider;
import io.github.atengk.boot.admin.admission.AdminAdmissionInstanceFilter;
import io.github.atengk.boot.admin.admission.AdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.DefaultAdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.security.AdminOutboundSsrfFilterFunction;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;

/**
 * Spring Boot Admin 防线前置自动化装配（先于 SBA 原生 {@code AdminServerAutoConfiguration} 生效）
 * <p>
 * 负责在 SBA 初始化 {@code InstanceFilter} 与 {@code InstanceWebClient} 前注入准入策略、
 * 服务发现准入过滤器及出站 SSRF 二次重检过滤器。
 *
 * @author Ateng
 * @since 2026-11-21
 */
@AutoConfiguration(beforeName = {
        "de.codecentric.boot.admin.server.config.AdminServerAutoConfiguration",
        "de.codecentric.boot.admin.server.config.AdminServerInstanceWebClientConfiguration"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(AdminServerMarkerConfiguration.Marker.class)
@ConditionalOnBean(AdminServerMarkerConfiguration.Marker.class)
@ConditionalOnProperty(prefix = "ateng.boot.admin.server", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengAdminServerProperties.class)
public class AtengAdminServerDefenseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AdminInstanceAdmissionPolicy.class)
    public AdminInstanceAdmissionPolicy adminInstanceAdmissionPolicy(AtengAdminServerProperties properties) {
        return new DefaultAdminInstanceAdmissionPolicy(properties);
    }

    @Bean
    @ConditionalOnMissingBean(InstanceFilter.class)
    public InstanceFilter instanceFilter(AdminInstanceAdmissionPolicy admissionPolicy) {
        return new AdminAdmissionInstanceFilter(admissionPolicy);
    }

    @Bean
    @Order(AdminOutboundSsrfFilterFunction.DEFAULT_ORDER)
    @ConditionalOnMissingBean(AdminOutboundSsrfFilterFunction.class)
    @ConditionalOnProperty(prefix = "ateng.boot.admin.server", name = "outbound-ssrf-check-enabled", havingValue = "true", matchIfMissing = true)
    public AdminOutboundSsrfFilterFunction adminOutboundSsrfFilterFunction(AdminInstanceAdmissionPolicy admissionPolicy,
                                                                           AtengAdminServerProperties properties) {
        return new AdminOutboundSsrfFilterFunction(admissionPolicy, properties);
    }

    /**
     * 抑制 SBA 原生 BasicAuthHttpHeaderProvider 默认重复注入，确保仅由权威 {@code AtengActuatorHttpHeadersProvider} 统一生成认证请求头
     */
    @Bean
    @ConditionalOnMissingBean(BasicAuthHttpHeaderProvider.class)
    public BasicAuthHttpHeaderProvider basicAuthHttpHeadersProvider() {
        return new BasicAuthHttpHeaderProvider() {
            @Override
            public HttpHeaders getHeaders(Instance instance) {
                return HttpHeaders.EMPTY;
            }
        };
    }
}
