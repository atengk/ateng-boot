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
 * 外部云 KMS / 硬件加密机抽象基类单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class AbstractKmsConfigDecryptorTest {

    @Test
    @DisplayName("测试 KMS 解密器在网络抖动下自动触发退避重试并在第3次成功")
    void testKmsRetrySuccessAfterTransientFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        RetryUtil.RetryConfig fastRetryConfig = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(10))
                .multiplier(1.5)
                .maxDelay(Duration.ofMillis(50))
                .retryPredicate(RetryingConfigDecryptor.DEFAULT_RETRYABLE_PREDICATE)
                .build();

        AbstractKmsConfigDecryptor kmsDecryptor = new AbstractKmsConfigDecryptor(fastRetryConfig) {
            @Override
            protected String doDecrypt(String ciphertext, String keyIdOrPassword) throws Exception {
                int count = attempts.incrementAndGet();
                if (count < 3) {
                    throw new SocketTimeoutException("AWS KMS 连接超时抖动 (attempt=" + count + ")");
                }
                return "kms-decrypted-plaintext";
            }
        };

        String result = kmsDecryptor.decrypt("encrypted-payload", "alias/my-kms-key");
        assertEquals("kms-decrypted-plaintext", result);
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("测试 KMS 解密器在遇到确定性密码学校验异常时快速失败不重试")
    void testKmsCryptoErrorFailsFast() {
        AtomicInteger attempts = new AtomicInteger(0);

        AbstractKmsConfigDecryptor kmsDecryptor = new AbstractKmsConfigDecryptor() {
            @Override
            protected String doDecrypt(String ciphertext, String keyIdOrPassword) throws Exception {
                attempts.incrementAndGet();
                throw new BadPaddingException("Tag 认证错误或密文篡改");
            }
        };

        assertThrows(BadPaddingException.class, () ->
                kmsDecryptor.decrypt("tampered-cipher", "key"));

        assertEquals(1, attempts.get(), "确定性密码学异常必须单次快速失败，严禁盲目重试");
    }

    @Test
    @DisplayName("测试空值保底与算法元数据契约")
    void testEmptyValueAndMetadata() throws Exception {
        AbstractKmsConfigDecryptor kmsDecryptor = new AbstractKmsConfigDecryptor() {
            @Override
            protected String doDecrypt(String ciphertext, String keyIdOrPassword) {
                return "decrypted";
            }
        };

        assertNull(kmsDecryptor.decrypt(null, "key"));
        assertEquals("", kmsDecryptor.decrypt("", "key"));
        assertEquals("   ", kmsDecryptor.decrypt("   ", "key"));

        assertTrue(kmsDecryptor.supports(EncryptAlgorithm.KMS));
        assertFalse(kmsDecryptor.supports(EncryptAlgorithm.AES_GCM));
        assertEquals("KMS", kmsDecryptor.getAlgorithmCode());
        assertNotNull(kmsDecryptor.getRetryConfig());
    }

    @Test
    @DisplayName("测试 KMS 持续网络故障耗尽重试后抛出原始因果异常")
    void testKmsRetryExhausted() {
        AtomicInteger attempts = new AtomicInteger(0);

        RetryUtil.RetryConfig fastRetryConfig = RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(5))
                .multiplier(1.0)
                .maxDelay(Duration.ofMillis(10))
                .build();

        AbstractKmsConfigDecryptor kmsDecryptor = new AbstractKmsConfigDecryptor(fastRetryConfig) {
            @Override
            protected String doDecrypt(String ciphertext, String keyIdOrPassword) throws Exception {
                attempts.incrementAndGet();
                throw new IOException("KMS Gateway 502 Bad Gateway");
            }
        };

        Exception ex = assertThrows(Exception.class, () ->
                kmsDecryptor.decrypt("sample", "key"));

        assertEquals(3, attempts.get());
        assertTrue(ex.getMessage().contains("502 Bad Gateway")
                || (ex.getCause() != null && ex.getCause().getMessage().contains("502 Bad Gateway")));
    }
}
