package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;

/**
 * 配置解密器核心 SPI 契约接口
 *
 * @author Ateng
 * @since 2026-10-25
 */
public interface ConfigDecryptor {

    /**
     * 判断当前解密器是否支持指定的加密算法
     *
     * @param algorithm 加密算法
     * @return 若支持则返回 true
     */
    boolean supports(EncryptAlgorithm algorithm);

    /**
     * 执行密文解密还原为明文
     *
     * @param ciphertext 密文字符串（通常为 Base64 编码，且已包含前置 IV）
     * @param password   解密主密钥口令
     * @return 解密后的真实明文字符串
     * @throws Exception 解密失败时抛出底层密码学异常
     */
    String decrypt(String ciphertext, String password) throws Exception;

    /**
     * 获取支持的核心算法编码标识
     *
     * @return 算法名称（如 AES-GCM, AES-CBC, SM4-CBC）
     */
    default String getAlgorithmCode() {
        return getClass().getSimpleName();
    }
}
