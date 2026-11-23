package io.github.atengk.boot.admin.autoconfigure;

import de.codecentric.boot.admin.server.config.AdminServerMarkerConfiguration;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.eventstore.InstanceEventStore;
import de.codecentric.boot.admin.server.services.InstanceFilter;
import de.codecentric.boot.admin.server.web.client.BasicAuthHttpHeaderProvider;
import io.github.atengk.boot.admin.admission.AdminAdmissionInstanceFilter;
import io.github.atengk.boot.admin.admission.AdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.DefaultAdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.eventstore.AtengInMemoryEventStore;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.observability.AdminMetricsBinder;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.security.AdminOutboundSsrfFilterFunction;
import io.github.atengk.boot.admin.silence.AdminSilenceManager;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
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
    @ConditionalOnClass(MeterBinder.class)
    @ConditionalOnMissingBean
    public AdminMetricsBinder adminMetricsBinder(ObjectProvider<InstanceRepository> repositoryProvider,
                                                 ObjectProvider<AtengAdminNotificationNotifier> notifierProvider,
                                                 ObjectProvider<AdminSilenceManager> silenceManagerProvider) {
        return new AdminMetricsBinder(repositoryProvider, notifierProvider, silenceManagerProvider);
    }

    @Bean
    @ConditionalOnMissingBean(InstanceFilter.class)
    public InstanceFilter instanceFilter(AdminInstanceAdmissionPolicy admissionPolicy,
                                         ObjectProvider<AdminMetricsBinder> metricsBinderProvider) {
        return new AdminAdmissionInstanceFilter(admissionPolicy, metricsBinderProvider.getIfAvailable());
    }

    @Bean
    @Order(AdminOutboundSsrfFilterFunction.DEFAULT_ORDER)
    @ConditionalOnMissingBean(AdminOutboundSsrfFilterFunction.class)
    @ConditionalOnProperty(prefix = "ateng.boot.admin.server", name = "outbound-ssrf-check-enabled", havingValue = "true", matchIfMissing = true)
    public AdminOutboundSsrfFilterFunction adminOutboundSsrfFilterFunction(AdminInstanceAdmissionPolicy admissionPolicy,
                                                                           AtengAdminServerProperties properties) {
        return new AdminOutboundSsrfFilterFunction(admissionPolicy, properties);
    }

    @Bean
    @ConditionalOnMissingBean(InstanceEventStore.class)
    public AtengInMemoryEventStore eventStore(AtengAdminServerProperties properties) {
        AtengAdminServerProperties.EventStoreProperties storeProps = properties != null ? properties.getEventStore() : null;
        int maxLogSize = storeProps != null
                ? storeProps.getMaxLogSizePerAggregate()
                : AtengInMemoryEventStore.DEFAULT_MAX_LOG_SIZE_PER_AGGREGATE;
        int maxRetainedDeregistered = storeProps != null
                ? storeProps.getMaxRetainedDeregistered()
                : AtengInMemoryEventStore.DEFAULT_MAX_RETAINED_DEREGISTERED;
        return new AtengInMemoryEventStore(maxLogSize, maxRetainedDeregistered);
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
