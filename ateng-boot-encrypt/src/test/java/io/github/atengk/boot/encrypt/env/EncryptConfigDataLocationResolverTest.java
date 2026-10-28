package io.github.atengk.boot.encrypt.env;

import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.ConfigDataResource;
import org.springframework.boot.context.config.Profiles;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * EncryptConfigDataLocationResolver 穿透守卫单元测试
 * 模拟 Nacos Client 等配置中心在解析外部 import 时通过 context.getBinder() 早期获取解密密码
 *
 * @author Ateng
 * @since 2026-10-25
 */
class EncryptConfigDataLocationResolverTest {

    private static final String PASSWORD = "nacos-master-secret-2026";
    private final AesGcmConfigDecryptor decryptor = new AesGcmConfigDecryptor();
    private final EncryptConfigDataLocationResolver resolver = new EncryptConfigDataLocationResolver();

    @BeforeEach
    void setUp() {
        System.setProperty(KeyDetector.PROPERTY_PASSWORD, PASSWORD);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(KeyDetector.PROPERTY_PASSWORD);
    }

    @Test
    @DisplayName("测试排序优先级为最高优先级 Ordered.HIGHEST_PRECEDENCE")
    void testOrderIsHighestPrecedence() {
        assertThat(resolver.getOrder()).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    @DisplayName("测试 isResolvable 恒定返回 false 且 resolve 返回空集合")
    void testIsResolvableReturnsFalse() {
        ConfigDataLocationResolverContext mockContext = mock(ConfigDataLocationResolverContext.class);
        ConfigDataLocation location = ConfigDataLocation.of("nacos:my-service.yaml");

        boolean resolvable = resolver.isResolvable(mockContext, location);
        assertThat(resolvable).isFalse();

        List<ConfigDataResource> resolved = resolver.resolve(mockContext, location);
        assertThat(resolved).isEmpty();

        List<ConfigDataResource> profileSpecific = resolver.resolveProfileSpecific(mockContext, location, mock(Profiles.class));
        assertThat(profileSpecific).isEmpty();
    }

    @Test
    @DisplayName("模拟全真 Nacos Client 早阶 bind 场景：穿透守卫触发后 Binder 成功读取解密明文")
    void testNacosClientEarlyBindDecryptedPassword() throws Exception {
        // 1. 构造包含 Nacos 密文连接凭据的本地属性源
        String cipherPassword = decryptor.encrypt("nacos-root-123456", PASSWORD);
        Map<String, Object> localMap = new HashMap<>();
        localMap.put("spring.cloud.nacos.server-addr", "127.0.0.1:8848");
        localMap.put("spring.cloud.nacos.password", "ENC(" + cipherPassword + ")");
        MapPropertySource localPropertySource = new MapPropertySource("applicationConfig: [classpath:/application.yml]", localMap);

        // 2. 模拟包含该属性源的 Contributor 结构
        MockContributor mockContributor = new MockContributor(localPropertySource);
        MockContributors mockContributors = new MockContributors(List.of(mockContributor));
        MockResolverContext mockContext = new MockResolverContext(mockContributors);

        // 3. 模拟 ConfigDataLocationResolvers 执行 isResolvable (触发穿透装饰)
        ConfigDataLocation location = ConfigDataLocation.of("nacos:application.yaml");
        boolean resolvable = resolver.isResolvable(mockContext, location);
        assertThat(resolvable).isFalse();

        // 4. 验证 Contributor 内部的 propertySource 已被替换为 EncryptPropertySourceWrapper
        assertThat(mockContributor.getPropertySource()).isInstanceOf(EncryptPropertySourceWrapper.class);

        // 5. 模拟 NacosConfigDataLocationResolver 调用 context.getBinder().bind(...)
        Binder binder = mockContext.getBinder();
        String boundPassword = binder.bind("spring.cloud.nacos.password", String.class).get();

        // 6. 核心断言：Nacos Client 早期成功拿到真实解密明文密码，彻底消除 403 冲突
        assertThat(boundPassword).isEqualTo("nacos-root-123456");
    }

    @Test
    @DisplayName("测试 Spring Boot 3.3.x 真实静态类字段名 contributors 的穿透探查与解密")
    void testSpringBoot3RealFieldContributorsPenetration() throws Exception {
        String cipherPassword = decryptor.encrypt("nacos-real-field-pass", PASSWORD);
        Map<String, Object> localMap = new HashMap<>();
        localMap.put("spring.cloud.nacos.password", "ENC(" + cipherPassword + ")");
        MapPropertySource localPropertySource = new MapPropertySource("applicationConfig: [classpath:/application.yml]", localMap);

        MockContributor mockContributor = new MockContributor(localPropertySource);
        MockContributors mockContributors = new MockContributors(List.of(mockContributor));
        MockSpringBoot3ResolverContext mockContext = new MockSpringBoot3ResolverContext(mockContributors);

        ConfigDataLocation location = ConfigDataLocation.of("nacos:application.yaml");
        boolean resolvable = resolver.isResolvable(mockContext, location);
        assertThat(resolvable).isFalse();

        assertThat(mockContributor.getPropertySource()).isInstanceOf(EncryptPropertySourceWrapper.class);
        Binder binder = mockContext.getBinder();
        String boundPassword = binder.bind("spring.cloud.nacos.password", String.class).get();
        assertThat(boundPassword).isEqualTo("nacos-real-field-pass");
    }

    // ========== 模拟 Spring Boot 内部 Contributor 树状结构 ==========

    static class MockContributors implements Iterable<MockContributor> {
        private final List<MockContributor> contributors;

        MockContributors(List<MockContributor> contributors) {
            this.contributors = contributors;
        }

        @Override
        public java.util.Iterator<MockContributor> iterator() {
            return contributors.iterator();
        }
    }

    static class MockContributor {
        private PropertySource<?> propertySource;
        private ConfigurationPropertySource configurationPropertySource;

        MockContributor(PropertySource<?> propertySource) {
            this.propertySource = propertySource;
            this.configurationPropertySource = ConfigurationPropertySources.from(propertySource).iterator().next();
        }

        public PropertySource<?> getPropertySource() {
            return propertySource;
        }

        public ConfigurationPropertySource getConfigurationPropertySource() {
            return configurationPropertySource;
        }
    }

    static class MockResolverContext implements ConfigDataLocationResolverContext {
        private final MockContributors this$0; // 模拟 Spring 内部内部类对外部类的隐式引用

        MockResolverContext(MockContributors contributors) {
            this.this$0 = contributors;
        }

        @Override
        public Binder getBinder() {
            // 从当前 contributors 构造 Binder
            List<ConfigurationPropertySource> sources = new java.util.ArrayList<>();
            for (MockContributor c : this$0) {
                if (c.getConfigurationPropertySource() != null) {
                    sources.add(c.getConfigurationPropertySource());
                }
            }
            return new Binder(sources);
        }

        @Override
        public ConfigDataResource getParent() {
            return null;
        }

        @Override
        public ConfigurableBootstrapContext getBootstrapContext() {
            return null;
        }
    }

    static class MockSpringBoot3ResolverContext implements ConfigDataLocationResolverContext {
        // 1:1 对齐 Spring Boot 3.3.x ContributorConfigDataLocationResolverContext 的真实字段名 "contributors"
        private final MockContributors contributors;

        MockSpringBoot3ResolverContext(MockContributors contributors) {
            this.contributors = contributors;
        }

        @Override
        public Binder getBinder() {
            List<ConfigurationPropertySource> sources = new java.util.ArrayList<>();
            for (MockContributor c : contributors) {
                if (c.getConfigurationPropertySource() != null) {
                    sources.add(c.getConfigurationPropertySource());
                }
            }
            return new Binder(sources);
        }

        @Override
        public ConfigDataResource getParent() {
            return null;
        }

        @Override
        public ConfigurableBootstrapContext getBootstrapContext() {
            return null;
        }
    }
}
