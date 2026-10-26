package io.github.atengk.boot.observability.autoconfigure;

import de.codecentric.boot.admin.client.config.InstanceProperties;
import de.codecentric.boot.admin.client.registration.BlockingRegistrationClient;
import io.github.atengk.boot.observability.interceptor.AdminClientSecretHttpRequestInterceptor;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;

/**
 * Spring Boot Admin 客户端透明增强自动装配类
 * 当微服务引入 SBA Client 时，自动将 Actuator Token 注入实例 Metadata，
 * 并向出站注册通信请求附加 X-Admin-Client-Secret 握手密钥
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Slf4j
@AutoConfiguration(afterName = "de.codecentric.boot.admin.client.config.SpringBootAdminClientAutoConfiguration")
@ConditionalOnClass(InstanceProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.observability.admin-client", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class AtengAdminClientAutoConfiguration {

    private final ObservabilityProperties observabilityProperties;
    private final ObjectProvider<InstanceProperties> instancePropertiesProvider;

    public AtengAdminClientAutoConfiguration(ObservabilityProperties observabilityProperties,
                                             ObjectProvider<InstanceProperties> instancePropertiesProvider) {
        this.observabilityProperties = observabilityProperties;
        this.instancePropertiesProvider = instancePropertiesProvider;
    }

    /**
     * 将 Actuator Token 与可选的寻址覆写直接可靠注入 InstanceProperties 内存模型
     */
    @PostConstruct
    public void configureMetadata() {
        InstanceProperties instanceProperties = instancePropertiesProvider.getIfAvailable();
        if (instanceProperties != null) {
            String token = observabilityProperties.getSecurity().getToken();
            if (StringUtils.hasText(token)) {
                if (instanceProperties.getMetadata() == null) {
                    instanceProperties.setMetadata(new LinkedHashMap<>());
                }
                instanceProperties.getMetadata().put("actuator-token", token);
            }
            ObservabilityProperties.AdminClientProperties adminClient = observabilityProperties.getAdminClient();
            if (adminClient != null) {
                if (StringUtils.hasText(adminClient.getServiceUrl())) {
                    instanceProperties.setServiceUrl(adminClient.getServiceUrl());
                }
                if (StringUtils.hasText(adminClient.getManagementUrl())) {
                    instanceProperties.setManagementUrl(adminClient.getManagementUrl());
                }
            }
        }
    }

    /**
     * 注册 RestTemplateCustomizer，自动向 HTTP 出站请求头添加 X-Admin-Client-Secret
     */
    @Bean
    @ConditionalOnClass(RestTemplateCustomizer.class)
    @ConditionalOnMissingBean(name = "adminClientSecretCustomizer")
    public RestTemplateCustomizer adminClientSecretCustomizer() {
        return restTemplate -> {
            String clientSecret = observabilityProperties.getAdminClient().getClientSecret();
            if (StringUtils.hasText(clientSecret)) {
                boolean exists = restTemplate.getInterceptors().stream()
                        .anyMatch(i -> i instanceof AdminClientSecretHttpRequestInterceptor);
                if (!exists) {
                    restTemplate.getInterceptors().add(new AdminClientSecretHttpRequestInterceptor(clientSecret));
                }
            }
        };
    }

    /**
     * 注册 RegistrationClient 后置处理器，防御性确保 SBA 注册客户端内部持有 RestTemplate 也完成拦截器注入
     */
    @Bean
    @ConditionalOnClass(BlockingRegistrationClient.class)
    @ConditionalOnMissingBean(name = "adminRegistrationClientPostProcessor")
    public BeanPostProcessor adminRegistrationClientPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof BlockingRegistrationClient client) {
                    String clientSecret = observabilityProperties.getAdminClient().getClientSecret();
                    if (StringUtils.hasText(clientSecret)) {
                        try {
                            Field field = BlockingRegistrationClient.class.getDeclaredField("restTemplate");
                            field.setAccessible(true);
                            RestTemplate rt = (RestTemplate) field.get(client);
                            if (rt != null) {
                                boolean exists = rt.getInterceptors().stream()
                                        .anyMatch(i -> i instanceof AdminClientSecretHttpRequestInterceptor);
                                if (!exists) {
                                    rt.getInterceptors().add(new AdminClientSecretHttpRequestInterceptor(clientSecret));
                                }
                            }
                        } catch (Exception ex) {
                            log.debug("[SBA Client] 防御性探测 BlockingRegistrationClient restTemplate 失败，依赖 RestTemplateCustomizer 装配: {}", ex.getMessage());
                        }
                    }
                }
                return bean;
            }
        };
    }
}
