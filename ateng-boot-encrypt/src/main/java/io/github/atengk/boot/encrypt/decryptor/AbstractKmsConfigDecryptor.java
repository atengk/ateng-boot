package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;

import java.util.Objects;

/**
 * 外部云 KMS / 硬件加密机 (HSM) 解密器抽象基类
 * 为对接 AWS KMS、阿里云 KMS、腾讯云 KMS 以及公司内网 PCI-E 硬件加密机提供标准 SPI 逃生舱契约。
 * 默认集成基于 ateng-boot-core 的 RetryUtil 原生指数退避重试能力，防范外部云端网络抖动
 *
 * @author Ateng
 * @since 2026-10-25
 */
public abstract class AbstractKmsConfigDecryptor implements ConfigDecryptor {

    private final RetryUtil.RetryConfig retryConfig;

    protected AbstractKmsConfigDecryptor() {
        this(RetryingConfigDecryptor.DEFAULT_RETRY_CONFIG);
    }

    protected AbstractKmsConfigDecryptor(RetryUtil.RetryConfig retryConfig) {
        this.retryConfig = Objects.requireNonNull(retryConfig, "retryConfig 不能为空");
    }

    @Override
    public boolean supports(EncryptAlgorithm algorithm) {
        return algorithm == EncryptAlgorithm.KMS;
    }

    @Override
    public String getAlgorithmCode() {
        return "KMS";
    }

    @Override
    public String decrypt(String ciphertext, String password) throws Exception {
        if (ciphertext == null || ciphertext.trim().isEmpty()) {
            return ciphertext;
        }
        String cleanCipher = ciphertext.trim();
        return RetryUtil.execute(() -> {
            try {
                return doDecrypt(cleanCipher, password);
            } catch (Exception e) {
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new KmsDecryptionException(e.getMessage(), e);
            }
        }, retryConfig, (Throwable lastError) -> {
            if (lastError instanceof KmsDecryptionException kde && kde.getCause() instanceof Exception ex) {
                throw sneakyThrow(ex);
            }
            if (lastError instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("KMS 外部解密重试耗尽: " + lastError.getMessage(), lastError);
        });
    }

    public RetryUtil.RetryConfig getRetryConfig() {
        return retryConfig;
    }

    /**
     * 执行底层 KMS / HSM 实际解密逻辑
     *
     * @param ciphertext      密文字符串
     * @param keyIdOrPassword 密钥标识或访问凭据（由 MultiKeyRegistry 注入）
     * @return 解密后的真实明文
     * @throws Exception 解密调用异常（网络抖动或鉴权失败）
     */
    protected abstract String doDecrypt(String ciphertext, String keyIdOrPassword) throws Exception;

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    private static class KmsDecryptionException extends RuntimeException {
        public KmsDecryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
