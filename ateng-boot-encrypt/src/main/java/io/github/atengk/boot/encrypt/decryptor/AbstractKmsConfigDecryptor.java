package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;

/**
 * 外部云 KMS / 硬件加密机 (HSM) 解密器抽象基类
 * 为对接 AWS KMS、阿里云 KMS、腾讯云 KMS 以及公司内网 PCI-E 硬件加密机提供标准 SPI 逃生舱契约
 *
 * @author Ateng
 * @since 2026-10-25
 */
public abstract class AbstractKmsConfigDecryptor implements ConfigDecryptor {

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
        return doDecrypt(ciphertext.trim(), password);
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
}
