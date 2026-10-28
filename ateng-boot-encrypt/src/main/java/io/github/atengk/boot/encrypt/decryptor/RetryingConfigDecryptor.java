package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.BadPaddingException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 弹性重试配置解密器装饰器
 * 基于 ateng-boot-core 的 RetryUtil 原生纯函数式退避机制，
 * 为外部 KMS（AWS KMS / 阿里云 KMS）及硬件加密机 (HSM) 提供生产就绪的网络抖动指数退避弹性容灾
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class RetryingConfigDecryptor implements ConfigDecryptor {

    private static final Logger log = LoggerFactory.getLogger(RetryingConfigDecryptor.class);

    private final ConfigDecryptor delegate;
    private final RetryUtil.RetryConfig retryConfig;

    /**
     * 默认重试判断谓词：排除确定性密码学口令/填充校验失败，网络及瞬态服务抖动均执行重试
     */
    public static final Predicate<Throwable> DEFAULT_RETRYABLE_PREDICATE = ex -> {
        if (ex == null) {
            return false;
        }
        Throwable current = ex;
        while (current != null) {
            // 若底层已明确为填充损坏、密钥长度非法或认证 Tag 校验失败，属于确定性配置错误，无需盲目重试
            if (current instanceof BadPaddingException
                    || "javax.crypto.AEADBadTagException".equals(current.getClass().getName())
                    || "javax.crypto.IllegalBlockSizeException".equals(current.getClass().getName())
                    || current instanceof java.security.InvalidKeyException) {
                return false;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return true;
    };

    /**
     * 默认重试配置：最多尝试 3 次，初始等待 300ms，2.0 指数退避，上限 5000ms，启用 Equal Jitter 打散洪峰
     */
    public static final RetryUtil.RetryConfig DEFAULT_RETRY_CONFIG = RetryUtil.RetryConfig.builder()
            .maxAttempts(3)
            .delay(Duration.ofMillis(300))
            .multiplier(2.0)
            .maxDelay(Duration.ofMillis(5000))
            .jitter(true)
            .retryPredicate(DEFAULT_RETRYABLE_PREDICATE)
            .onRetry((attempt, error) ->
                    log.warn("[ateng-boot-encrypt] 外部解密器执行抖动异常，正在进行第 {} 次指数退避重试: {}", attempt, error.getMessage()))
            .build();

    public RetryingConfigDecryptor(ConfigDecryptor delegate) {
        this(delegate, DEFAULT_RETRY_CONFIG);
    }

    public RetryingConfigDecryptor(ConfigDecryptor delegate, RetryUtil.RetryConfig retryConfig) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 解密器不能为空");
        this.retryConfig = Objects.requireNonNull(retryConfig, "retryConfig 不能为空");
    }

    public RetryingConfigDecryptor(ConfigDecryptor delegate, int maxAttempts, Duration delay) {
        this(delegate, RetryUtil.RetryConfig.builder()
                .maxAttempts(maxAttempts)
                .delay(delay)
                .multiplier(2.0)
                .maxDelay(Duration.ofSeconds(5))
                .jitter(true)
                .retryPredicate(DEFAULT_RETRYABLE_PREDICATE)
                .onRetry((attempt, error) ->
                        log.warn("[ateng-boot-encrypt] 外部解密器执行抖动异常，正在进行第 {} 次重试: {}", attempt, error.getMessage()))
                .build());
    }

    @Override
    public boolean supports(EncryptAlgorithm algorithm) {
        return delegate.supports(algorithm);
    }

    @Override
    public String getAlgorithmCode() {
        return delegate.getAlgorithmCode();
    }

    @Override
    public String decrypt(String ciphertext, String password) throws Exception {
        return RetryUtil.execute(() -> {
            try {
                return delegate.decrypt(ciphertext, password);
            } catch (Exception e) {
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new RetryingDecryptionException(e.getMessage(), e);
            }
        }, retryConfig, (Throwable lastError) -> {
            if (lastError instanceof RetryingDecryptionException rde && rde.getCause() instanceof Exception ex) {
                throw sneakyThrow(ex);
            }
            if (lastError instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("解密重试耗尽: " + lastError.getMessage(), lastError);
        });
    }

    public ConfigDecryptor getDelegate() {
        return delegate;
    }

    public RetryUtil.RetryConfig getRetryConfig() {
        return retryConfig;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    private static class RetryingDecryptionException extends RuntimeException {
        public RetryingDecryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
