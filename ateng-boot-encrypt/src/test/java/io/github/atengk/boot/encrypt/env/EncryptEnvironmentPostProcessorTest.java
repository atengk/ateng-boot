package io.github.atengk.boot.encrypt.env;

import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EncryptEnvironmentPostProcessor 单元测试
 * 覆盖全属性源无差别动态解密、远端配置中心拉取解密、Logback 极早阶段解密协同与自加密风险拦截
 *
 * @author Ateng
 * @since 2026-10-25
 */
class EncryptEnvironmentPostProcessorTest {

    private static final String PASSWORD = "master-key-prod-2026";
    private final AesGcmConfigDecryptor decryptor = new AesGcmConfigDecryptor();
    private final EncryptEnvironmentPostProcessor postProcessor = new EncryptEnvironmentPostProcessor();

    @BeforeEach
    void setUp() {
        System.setProperty(KeyDetector.PROPERTY_PASSWORD, PASSWORD);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(KeyDetector.PROPERTY_PASSWORD);
    }

    @Test
    @DisplayName("测试排序优先级：位于 ConfigData 之后且位于 Logging 之前")
    void testOrderPrecedence() {
        assertThat(postProcessor.getOrder()).isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 5);
        // LoggingApplicationListener.DEFAULT_ORDER 是 HIGHEST_PRECEDENCE + 20
        assertThat(postProcessor.getOrder()).isLessThan(Integer.MIN_VALUE + 20);
    }

    @Test
    @DisplayName("测试全量属性源无差别动态装饰（命令行参数、本地文件源、远端拉取配置源）")
    void testAllPropertySourcesWrappedAndDecrypted() {
        StandardEnvironment environment = new StandardEnvironment();

        // 1. 模拟命令行参数属性源
        String cipherCmd = decryptor.encrypt("cmd-secret-value", PASSWORD);
        SimpleCommandLinePropertySource cmdSource = new SimpleCommandLinePropertySource(
                "--cmd.secret=ENC(" + cipherCmd + ")");
        environment.getPropertySources().addFirst(cmdSource);

        // 2. 模拟本地 YAML 属性源
        String cipherDbPass = decryptor.encrypt("local-db-password-123", PASSWORD);
        Map<String, Object> localMap = new HashMap<>();
        localMap.put("spring.datasource.password", "ENC(" + cipherDbPass + ")");
        MapPropertySource localSource = new MapPropertySource("applicationConfig: [classpath:/application.yml]", localMap);
        environment.getPropertySources().addLast(localSource);

        // 3. 模拟 Nacos 远端拉取回来的远程配置源 (spring.config.import)
        String cipherRemoteRedis = decryptor.encrypt("remote-redis-9999", PASSWORD);
        Map<String, Object> remoteMap = new HashMap<>();
        remoteMap.put("spring.data.redis.password", "ENC(" + cipherRemoteRedis + ")");
        MapPropertySource remoteSource = new MapPropertySource("nacos:my-service.yaml", remoteMap);
        environment.getPropertySources().addLast(remoteSource);

        // 4. 挂载 Spring Boot 内部专用的 configurationProperties 虚拟适配源
        ConfigurationPropertySources.attach(environment);

        // 执行后置处理
        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        // 验证各配置源被动态包装为 EncryptPropertySourceWrapper（排除虚拟适配源）
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (EncryptEnvironmentPostProcessor.CONFIGURATION_PROPERTIES_SOURCE_NAME.equals(ps.getName())) {
                assertThat(ps).isNotInstanceOf(EncryptPropertySourceWrapper.class);
            } else {
                assertThat(ps).isInstanceOf(EncryptPropertySourceWrapper.class);
            }
        }

        // 核心断言：全量属性源均可透明解密
        assertThat(environment.getProperty("cmd.secret")).isEqualTo("cmd-secret-value");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("local-db-password-123");
        assertThat(environment.getProperty("spring.data.redis.password")).isEqualTo("remote-redis-9999");
    }

    @Test
    @DisplayName("测试 Logback <springProperty> 极早协同：在 Logging 初始化前成功提取明文凭据")
    void testLogbackSpringPropertySynergy() {
        StandardEnvironment environment = new StandardEnvironment();

        // 模拟用于远程日志 Appender（如 Loki、Logstash）鉴权的密文密码
        String cipherLogPass = decryptor.encrypt("loki-auth-token-8888", PASSWORD);
        Map<String, Object> logConfig = new HashMap<>();
        logConfig.put("logging.loki.password", "ENC(" + cipherLogPass + ")");
        logConfig.put("logging.loki.username", "loki-admin");
        environment.getPropertySources().addLast(new MapPropertySource("loggingSource", logConfig));

        // 执行后置处理（此时在 LoggingApplicationListener 之前执行）
        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        // 模拟 Logback 的 SpringPropertyAction 从 environment 获取属性
        String logbackExtractedPass = environment.getProperty("logging.loki.password");
        String logbackExtractedUser = environment.getProperty("logging.loki.username");

        // 核心断言：Logback 成功拿到真实明文密码用于远程日志传输鉴权
        assertThat(logbackExtractedPass).isEqualTo("loki-auth-token-8888");
        assertThat(logbackExtractedUser).isEqualTo("loki-admin");
    }

    @Test
    @DisplayName("测试防自欺欺人自加密安全拦截：在本地配置源直接定义密码时刚性阻断")
    void testSelfEncryptionBlockedInLocalConfig() {
        StandardEnvironment environment = new StandardEnvironment();

        Map<String, Object> dangerousMap = new HashMap<>();
        dangerousMap.put(KeyDetector.PROPERTY_PASSWORD, "hardcoded-plaintext-password");
        dangerousMap.put("spring.datasource.password", "ENC(...)");
        environment.getPropertySources().addLast(new MapPropertySource("application.yml", dangerousMap));

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(ConfigSecurityException.class)
                .hasMessageContaining("检测到在本地配置源 'application.yml' 中直接声明了主密钥");
    }

    @Test
    @DisplayName("测试空环境防御性返回")
    void testNullEnvironmentSafeReturn() {
        // 不应抛出任何异常
        postProcessor.postProcessEnvironment(null, new SpringApplication());
    }
}
