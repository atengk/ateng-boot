package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.BadPaddingException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 弹性重试配置解密器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class RetryingConfigDecryptorTest {

    @Test
    @DisplayName("测试模拟网络抖动前2次失败第3次成功触发指数退避重试")
    void testRetrySuccessAfterTransientNetworkFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        ConfigDecryptor mockKmsDecryptor = new ConfigDecryptor() {
            @Override
            public boolean supports(EncryptAlgorithm algorithm) {
                return algorithm == EncryptAlgorithm.KMS;
            }

            @Override
            public String decrypt(String ciphertext, String password) throws Exception {
                int count = attempts.incrementAndGet();
                if (count < 3) {
                    throw new SocketTimeoutException("KMS 远程连接超时抖动 (attempt=" + count + ")");
                }
                return "decrypted-by-kms-root";
            }
        };

        // 使用快速退避配置加快测试执行
        RetryUtil.RetryConfig fastRetryConfig = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(10))
                .multiplier(1.5)
                .maxDelay(Duration.ofMillis(50))
                .retryPredicate(RetryingConfigDecryptor.DEFAULT_RETRYABLE_PREDICATE)
                .build();

        RetryingConfigDecryptor retryingDecryptor = new RetryingConfigDecryptor(mockKmsDecryptor, fastRetryConfig);

        String result = retryingDecryptor.decrypt("ciphertext-sample", "kms-key-id");
        assertEquals("decrypted-by-kms-root", result);
        assertEquals(3, attempts.get(), "应该经历了 3 次尝试（首次 + 2 次重试）");
    }

    @Test
    @DisplayName("测试持续网络故障超过重试上限抛出异常并保留因果链")
    void testRetryExhaustedThrowsOriginalException() {
        AtomicInteger attempts = new AtomicInteger(0);
        ConfigDecryptor failingKms = new ConfigDecryptor() {
            @Override
            public boolean supports(EncryptAlgorithm algorithm) {
                return algorithm == EncryptAlgorithm.KMS;
            }

            @Override
            public String decrypt(String ciphertext, String password) throws Exception {
                attempts.incrementAndGet();
                throw new IOException("KMS 服务端完全不可达 503");
            }
        };

        RetryUtil.RetryConfig fastRetryConfig = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(5))
                .multiplier(1.0)
                .maxDelay(Duration.ofMillis(10))
                .build();

        RetryingConfigDecryptor retryingDecryptor = new RetryingConfigDecryptor(failingKms, fastRetryConfig);

        Exception ex = assertThrows(Exception.class, () ->
                retryingDecryptor.decrypt("sample", "key"));

        assertEquals(3, attempts.get());
        assertTrue(ex.getMessage().contains("KMS 服务端完全不可达 503") || (ex.getCause() != null && ex.getCause().getMessage().contains("KMS")));
    }

    @Test
    @DisplayName("测试确定性密码学校验异常快速失败不触发盲目重试")
    void testNonRetryableCryptoExceptionFailsImmediately() {
        AtomicInteger attempts = new AtomicInteger(0);
        ConfigDecryptor cryptoFailing = new ConfigDecryptor() {
            @Override
            public boolean supports(EncryptAlgorithm algorithm) {
                return algorithm == EncryptAlgorithm.AES_GCM;
            }

            @Override
            public String decrypt(String ciphertext, String password) throws Exception {
                attempts.incrementAndGet();
                throw new BadPaddingException("Given final block not properly padded");
            }
        };

        RetryingConfigDecryptor retryingDecryptor = new RetryingConfigDecryptor(cryptoFailing);

        assertThrows(BadPaddingException.class, () ->
                retryingDecryptor.decrypt("bad-cipher", "wrong-key"));

        assertEquals(1, attempts.get(), "确定性密码学填充损坏应单次快速失败，严禁盲目重试");
    }

    @Test
    @DisplayName("测试委托方法正确透传")
    void testDelegationMethods() {
        ConfigDecryptor delegate = new ConfigDecryptor() {
            @Override
            public boolean supports(EncryptAlgorithm algorithm) {
                return algorithm == EncryptAlgorithm.AES_GCM;
            }

            @Override
            public String decrypt(String ciphertext, String password) {
                return "plain";
            }

            @Override
            public String getAlgorithmCode() {
                return "CUSTOM-AES";
            }
        };

        RetryingConfigDecryptor retrying = new RetryingConfigDecryptor(delegate);
        assertTrue(retrying.supports(EncryptAlgorithm.AES_GCM));
        assertFalse(retrying.supports(EncryptAlgorithm.SM4_CBC));
        assertEquals("CUSTOM-AES", retrying.getAlgorithmCode());
        assertSame(delegate, retrying.getDelegate());
        assertNotNull(retrying.getRetryConfig());
    }
}
