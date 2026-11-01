package io.github.atengk.boot.encrypt.config;

import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.encrypt.autoconfigure.AtengEncryptAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.crypto.BadPaddingException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.InvalidKeyException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EncryptRetryProperties 单元测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
class EncryptRetryPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengEncryptAutoConfiguration.class));

    @Test
    @DisplayName("测试默认属性值符合生产规范")
    void testDefaultValues() {
        EncryptRetryProperties properties = new EncryptRetryProperties();
        assertEquals(Duration.ofMillis(300), properties.getDelay());
        assertEquals(Duration.ofSeconds(5), properties.getMaxDelay());
        assertEquals(3, properties.getMaxAttempts());
        assertEquals(2.0, properties.getMultiplier());
        assertTrue(properties.isJitter());

        RetryUtil.RetryConfig config = properties.toRetryConfig();
        assertNotNull(config);
        assertEquals(3, config.maxAttempts());
        assertEquals(Duration.ofMillis(300), config.delay());
        assertEquals(Duration.ofSeconds(5), config.maxDelay());
        assertEquals(2.0, config.multiplier());
        assertTrue(config.jitter());
        assertNotNull(config.retryPredicate());
        assertNotNull(config.onRetry());

        // 验证默认谓词行为
        assertTrue(config.retryPredicate().test(new SocketTimeoutException("timeout")));
        assertTrue(config.retryPredicate().test(new IOException("connection reset")));
        assertFalse(config.retryPredicate().test(new BadPaddingException("bad padding")));
        assertFalse(config.retryPredicate().test(new InvalidKeyException("invalid key")));
        assertFalse(config.retryPredicate().test(null));
    }

    @Test
    @DisplayName("测试自定义 predicate 与 onRetry 回调函数")
    void testCustomPredicateAndOnRetry() {
        EncryptRetryProperties properties = new EncryptRetryProperties();
        properties.setMaxAttempts(4);
        properties.setDelay(Duration.ofMillis(50));
        properties.setMultiplier(1.5);
        properties.setMaxDelay(Duration.ofMillis(200));
        properties.setJitter(false);

        AtomicInteger retryCallbacks = new AtomicInteger(0);
        RetryUtil.RetryConfig config = properties.toRetryConfig(
                ex -> ex instanceof IllegalStateException,
                (attempt, error) -> retryCallbacks.incrementAndGet()
        );

        assertEquals(4, config.maxAttempts());
        assertEquals(Duration.ofMillis(50), config.delay());
        assertEquals(1.5, config.multiplier());
        assertEquals(Duration.ofMillis(200), config.maxDelay());
        assertFalse(config.jitter());

        assertTrue(config.retryPredicate().test(new IllegalStateException("custom")));
        assertFalse(config.retryPredicate().test(new SocketTimeoutException("timeout")));

        config.onRetry().onRetry(1, new IllegalStateException("err"));
        assertEquals(1, retryCallbacks.get());
    }

    @Test
    @DisplayName("测试防御性边界与非负时间修正")
    void testDefensiveBoundaries() {
        EncryptRetryProperties properties = new EncryptRetryProperties();
        properties.setMaxAttempts(-5);
        properties.setDelay(null);
        properties.setMaxDelay(Duration.ofMillis(-100));
        properties.setMultiplier(0.5);

        RetryUtil.RetryConfig config = properties.toRetryConfig();
        assertEquals(1, config.maxAttempts(), "maxAttempts 小于 1 时应防御性保底为 1");
        assertEquals(Duration.ofMillis(300), config.delay(), "delay 为 null 时应保底为 300ms");
        assertEquals(Duration.ofSeconds(5), config.maxDelay(), "maxDelay 为负数时应保底为 5s");
        assertEquals(2.0, config.multiplier(), "multiplier 小于 1.0 时应保底为 2.0");
    }

    @Test
    @DisplayName("测试 Spring Boot 配置文件自动强类型绑定（支持 500ms、3s 时间量纲）")
    void testConfigurationPropertiesBinding() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.encrypt.retry.delay=500ms",
                        "ateng.boot.encrypt.retry.max-delay=3s",
                        "ateng.boot.encrypt.retry.max-attempts=5",
                        "ateng.boot.encrypt.retry.multiplier=1.8",
                        "ateng.boot.encrypt.retry.jitter=false"
                )
                .run(context -> {
                    EncryptProperties properties = context.getBean(EncryptProperties.class);
                    assertNotNull(properties);
                    EncryptRetryProperties retry = properties.getRetry();
                    assertNotNull(retry);
                    assertEquals(Duration.ofMillis(500), retry.getDelay());
                    assertEquals(Duration.ofSeconds(3), retry.getMaxDelay());
                    assertEquals(5, retry.getMaxAttempts());
                    assertEquals(1.8, retry.getMultiplier());
                    assertFalse(retry.isJitter());
                });
    }
}
