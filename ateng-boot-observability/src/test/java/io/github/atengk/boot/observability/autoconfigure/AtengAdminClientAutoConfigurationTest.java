package io.github.atengk.boot.observability.autoconfigure;

import de.codecentric.boot.admin.client.config.InstanceProperties;
import de.codecentric.boot.admin.client.registration.BlockingRegistrationClient;
import io.github.atengk.boot.observability.interceptor.AdminClientSecretHttpRequestInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengAdminClientAutoConfiguration 客户端透明增强自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("AtengAdminClientAutoConfiguration 客户端透明增强自动装配单元测试")
class AtengAdminClientAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengObservabilityAutoConfiguration.class,
                    AtengAdminClientAutoConfiguration.class
            ));

    @Test
    @DisplayName("当引入 InstanceProperties 时，自动注入 Metadata 与 RestTemplateCustomizer")
    void testAutoConfigurationWithInstanceProperties() {
        contextRunner
                .withBean(InstanceProperties.class, InstanceProperties::new)
                .withPropertyValues(
                        "ateng.boot.observability.security.token=my-actuator-token-999",
                        "ateng.boot.observability.admin-client.client-secret=my-client-secret-888",
                        "ateng.boot.observability.admin-client.service-url=http://10.0.0.10:8080",
                        "ateng.boot.observability.admin-client.management-url=http://10.0.0.10:8081"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengAdminClientAutoConfiguration.class);
                    assertThat(context).hasSingleBean(RestTemplateCustomizer.class);

                    // 1. 验证 InstanceProperties 元数据与寻址覆写
                    InstanceProperties instanceProperties = context.getBean(InstanceProperties.class);
                    assertThat(instanceProperties.getMetadata()).isNotNull();
                    assertThat(instanceProperties.getMetadata().get("actuator-token"))
                            .isEqualTo("my-actuator-token-999");
                    assertThat(instanceProperties.getServiceUrl()).isEqualTo("http://10.0.0.10:8080");
                    assertThat(instanceProperties.getManagementUrl()).isEqualTo("http://10.0.0.10:8081");

                    // 2. 验证 RestTemplateCustomizer 拦截器生效
                    RestTemplate restTemplate = new RestTemplate();
                    RestTemplateCustomizer customizer = context.getBean(RestTemplateCustomizer.class);
                    customizer.customize(restTemplate);

                    assertThat(restTemplate.getInterceptors())
                            .anyMatch(i -> i instanceof AdminClientSecretHttpRequestInterceptor
                                    && "my-client-secret-888".equals(((AdminClientSecretHttpRequestInterceptor) i).getClientSecret()));
                });
    }

    @Test
    @DisplayName("验证 BlockingRegistrationClient 后置处理器能够防御性注入通信密钥拦截器")
    void testBlockingRegistrationClientPostProcessor() {
        contextRunner
                .withBean(InstanceProperties.class, InstanceProperties::new)
                .withBean(BlockingRegistrationClient.class, () -> new BlockingRegistrationClient(new RestTemplate()))
                .withPropertyValues(
                        "ateng.boot.observability.admin-client.client-secret=deep-postprocessor-secret"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(BlockingRegistrationClient.class);
                    BlockingRegistrationClient client = context.getBean(BlockingRegistrationClient.class);

                    // 反射检查 BlockingRegistrationClient 内部持有的 RestTemplate
                    Field field = BlockingRegistrationClient.class.getDeclaredField("restTemplate");
                    field.setAccessible(true);
                    RestTemplate internalRestTemplate = (RestTemplate) field.get(client);
                    assertThat(internalRestTemplate).isNotNull();
                    assertThat(internalRestTemplate.getInterceptors())
                            .anyMatch(i -> i instanceof AdminClientSecretHttpRequestInterceptor
                                    && "deep-postprocessor-secret".equals(((AdminClientSecretHttpRequestInterceptor) i).getClientSecret()));
                });
    }

    @Test
    @DisplayName("当 admin-client.enabled=false 时，跳过客户端透明增强装配")
    void testAdminClientDisabled() {
        contextRunner
                .withBean(InstanceProperties.class, InstanceProperties::new)
                .withPropertyValues("ateng.boot.observability.admin-client.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengAdminClientAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(RestTemplateCustomizer.class);
                });
    }
}
