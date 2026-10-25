package io.github.atengk.boot.encrypt.env;

import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EncryptConfigDataEnvironmentUpdateListener 单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class EncryptConfigDataEnvironmentUpdateListenerTest {

    private static final String PASSWORD = "test-master-password-2026";
    private final AesGcmConfigDecryptor decryptor = new AesGcmConfigDecryptor();

    @BeforeEach
    void setUp() {
        System.setProperty(KeyDetector.PROPERTY_PASSWORD, PASSWORD);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(KeyDetector.PROPERTY_PASSWORD);
    }

    @Test
    @DisplayName("测试 onPropertySourceAdded 回调时动态置换环境中的属性源")
    void testOnPropertySourceAddedReplacesInEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        KeyDetector keyDetector = new KeyDetector(PASSWORD, null);
        EncryptConfigDataEnvironmentUpdateListener listener =
                new EncryptConfigDataEnvironmentUpdateListener(environment, keyDetector);

        String ciphertext = decryptor.encrypt("plain-db-pass", PASSWORD);
        Map<String, Object> map = new HashMap<>();
        map.put("spring.datasource.password", "ENC(" + ciphertext + ")");
        MapPropertySource rawSource = new MapPropertySource("applicationConfig: [classpath:/application.yml]", map);

        environment.getPropertySources().addLast(rawSource);

        // 触发监听器回调
        listener.onPropertySourceAdded(rawSource, null, null);

        // 验证已置换为 EncryptPropertySourceWrapper
        PropertySource<?> current = environment.getPropertySources().get(rawSource.getName());
        assertThat(current).isInstanceOf(EncryptPropertySourceWrapper.class);

        // 验证读取时透明解密
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("plain-db-pass");
        assertThat(listener.getProcessedPropertySources()).contains(rawSource.getName());
    }

    @Test
    @DisplayName("测试 wrapPropertySource 编程式包装")
    void testWrapPropertySource() {
        KeyDetector keyDetector = new KeyDetector(PASSWORD, null);
        EncryptConfigDataEnvironmentUpdateListener listener =
                new EncryptConfigDataEnvironmentUpdateListener(null, keyDetector);

        String ciphertext = decryptor.encrypt("secret-token", PASSWORD);
        Map<String, Object> map = Map.of("service.token", "ENC(" + ciphertext + ")");
        MapPropertySource source = new MapPropertySource("customSource", map);

        PropertySource<?> wrapped = listener.wrapPropertySource(source);
        assertThat(wrapped).isInstanceOf(EncryptPropertySourceWrapper.class);
        assertThat(wrapped.getProperty("service.token")).isEqualTo("secret-token");

        // 重复包装应返回自身
        PropertySource<?> wrappedAgain = listener.wrapPropertySource(wrapped);
        assertThat(wrappedAgain).isSameAs(wrapped);
    }
}
