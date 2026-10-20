package io.github.atengk.boot.http;

import io.github.atengk.boot.http.customizer.HttpClient5Customizer;
import io.github.atengk.boot.http.factory.ClientHttpRequestFactoryBuilder;
import io.github.atengk.boot.http.properties.HttpEngineType;
import io.github.atengk.boot.http.properties.HttpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物理连接工厂自适应构建单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class ClientHttpRequestFactoryBuilderTest {

    @Test
    @DisplayName("测试 AUTO 模式下默认自动构建 Apache HttpClient 5 工厂")
    void testAutoEngineBuildsHttpClient5() {
        HttpProperties properties = new HttpProperties();
        properties.setEngine(HttpEngineType.AUTO);

        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.build(properties, Collections.emptyList());

        assertThat(factory).isNotNull();
        assertThat(factory).isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("测试强制指定 JDK 引擎时构建 JdkClientHttpRequestFactory")
    void testJdkEngineBuildsJdkClient() {
        HttpProperties properties = new HttpProperties();
        properties.setEngine(HttpEngineType.JDK);

        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.build(properties, Collections.emptyList());

        assertThat(factory).isNotNull();
        assertThat(factory).isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("测试 SSL Insecure 与代理参数配置生效")
    void testSslAndProxyConfiguration() {
        HttpProperties properties = new HttpProperties();
        properties.getSsl().setInsecure(true);
        properties.getProxy().setEnabled(true);
        properties.getProxy().setHost("127.0.0.1");
        properties.getProxy().setPort(8888);
        properties.getProxy().setUsername("user");
        properties.getProxy().setPassword("pass");

        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.build(properties, Collections.emptyList());

        assertThat(factory).isNotNull();
        assertThat(factory).isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("测试 HttpClient5Customizer 自定义扩展回调触发")
    void testHttpClient5CustomizerInvoked() {
        HttpProperties properties = new HttpProperties();
        AtomicBoolean customizerCalled = new AtomicBoolean(false);

        HttpClient5Customizer customizer = (clientBuilder, connectionManagerBuilder) -> {
            customizerCalled.set(true);
            assertThat(clientBuilder).isNotNull();
            assertThat(connectionManagerBuilder).isNotNull();
        };

        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.build(properties, Collections.singletonList(customizer));

        assertThat(factory).isNotNull();
        assertThat(customizerCalled.get()).isTrue();
    }
}
