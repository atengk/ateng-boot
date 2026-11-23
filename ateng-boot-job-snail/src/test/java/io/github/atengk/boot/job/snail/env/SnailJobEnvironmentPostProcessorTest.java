package io.github.atengk.boot.job.snail.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SnailJobEnvironmentPostProcessor} 环境属性桥接单元测试
 *
 * @author Ateng
 * @since 2026-11-20
 */
class SnailJobEnvironmentPostProcessorTest {

    private final SnailJobEnvironmentPostProcessor postProcessor = new SnailJobEnvironmentPostProcessor();

    @Test
    @DisplayName("未配置服务端地址或显式禁用时静默退让，不注入原生配置源")
    void testSkipWhenMissingServerOrDisabled() {
        MockEnvironment missingServerEnv = new MockEnvironment();
        postProcessor.postProcessEnvironment(missingServerEnv, new SpringApplication());
        assertThat(missingServerEnv.getPropertySources().contains(SnailJobEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();

        MockEnvironment disabledEnv = new MockEnvironment()
                .withProperty("ateng.boot.job.snail.enabled", "false")
                .withProperty("ateng.boot.job.snail.server", "127.0.0.1:17888");
        postProcessor.postProcessEnvironment(disabledEnv, new SpringApplication());
        assertThat(disabledEnv.getPropertySources().contains(SnailJobEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();

        postProcessor.postProcessEnvironment(null, new SpringApplication());
    }

    @Test
    @DisplayName("完整桥接服务端、分组、命名空间、令牌、客户端端口与 OpenAPI 配置")
    void testFullPropertyBridgingAndAppNameFallback() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.application.name", "order-service")
                .withProperty("ateng.boot.job.snail.server", "10.0.0.10:37888,10.0.0.11:37888")
                .withProperty("ateng.boot.job.snail.namespace", "ns-prod-01")
                .withProperty("ateng.boot.job.snail.token", "SJ_MOCK_TOKEN")
                .withProperty("ateng.boot.job.snail.port", "27889")
                .withProperty("ateng.boot.job.snail.openapi.host", "openapi.example.internal")
                .withProperty("ateng.boot.job.snail.openapi.port", "38800")
                .withProperty("ateng.boot.job.snail.openapi.prefix", "/snail-job")
                .withProperty("ateng.boot.job.snail.openapi.https", "true");

        postProcessor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getPropertySources().contains(SnailJobEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
        assertThat(env.getProperty("snail-job.enabled", Boolean.class)).isTrue();
        assertThat(env.getProperty("snail-job.server.host")).isEqualTo("10.0.0.10");
        assertThat(env.getProperty("snail-job.server.port", Integer.class)).isEqualTo(37888);
        assertThat(env.getProperty("snail-job.group")).isEqualTo("order-service");
        assertThat(env.getProperty("snail-job.namespace")).isEqualTo("ns-prod-01");
        assertThat(env.getProperty("snail-job.token")).isEqualTo("SJ_MOCK_TOKEN");
        assertThat(env.getProperty("snail-job.port", Integer.class)).isEqualTo(27889);
        assertThat(env.getProperty("snail-job.openapi.host")).isEqualTo("openapi.example.internal");
        assertThat(env.getProperty("snail-job.openapi.port", Integer.class)).isEqualTo(38800);
        assertThat(env.getProperty("snail-job.openapi.prefix")).isEqualTo("/snail-job");
        assertThat(env.getProperty("snail-job.openapi.https", Boolean.class)).isTrue();

        // 验证幂等重入保护：再次执行不重复修改已存在属性源
        PropertySource<?> first = env.getPropertySources().get(SnailJobEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        postProcessor.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getPropertySources().get(SnailJobEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isSameAs(first);
    }

    @Test
    @DisplayName("采用最低优先级注入，不覆盖业务显式声明的 snail-job.* 原生配置")
    void testExplicitNativePropertiesTakePrecedence() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ateng.boot.job.snail.server", "127.0.0.1:17888")
                .withProperty("ateng.boot.job.snail.group", "ateng-group")
                .withProperty("snail-job.enabled", "false")
                .withProperty("snail-job.server.port", "29999")
                .withProperty("snail-job.group", "explicit-native-group");

        postProcessor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("snail-job.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("snail-job.server.host")).isEqualTo("127.0.0.1");
        assertThat(env.getProperty("snail-job.server.port", Integer.class)).isEqualTo(29999);
        assertThat(env.getProperty("snail-job.group")).isEqualTo("explicit-native-group");
        assertThat(env.getProperty("snail-job.openapi.host")).isEqualTo("127.0.0.1");
    }
}
