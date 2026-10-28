package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;

/**
 * 配置加密门面工具
 * 提供统一的对称加密与国密加密快捷方法，用于开发调试、单测与工具链
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class ConfigEncryptor {

    private ConfigEncryptor() {
    }

    /**
     * 按指定算法对明文进行加密
     *
     * @param plaintext 明文字符串
     * @param password  主密钥口令
     * @param algorithm 加密算法
     * @return 包含 IV 的 Base64 密文
     */
    public static String encrypt(String plaintext, String password, EncryptAlgorithm algorithm) {
        if (plaintext == null) {
            return null;
        }
        EncryptAlgorithm algo = algorithm != null ? algorithm : EncryptAlgorithm.AES_GCM;
        return switch (algo) {
            case AES_GCM -> AesGcmConfigDecryptor.encrypt(plaintext, password);
            case AES_CBC -> AesCbcConfigDecryptor.encrypt(plaintext, password);
            case SM4_CBC -> Sm4ConfigDecryptor.encryptCbc(plaintext, password);
            case SM4_ECB -> Sm4ConfigDecryptor.encryptEcb(plaintext, password);
            case RSA -> RsaConfigDecryptor.encrypt(plaintext, password);
            case SM2 -> Sm2ConfigDecryptor.encrypt(plaintext, password);
            default -> throw new UnsupportedOperationException("暂不支持算法的加密: " + algo);
        };
    }

    /**
     * 默认按 AES-GCM 算法加密
     *
     * @param plaintext 明文字符串
     * @param password  主密钥口令
     * @return 包含 12 字节 IV 的 Base64 密文
     */
    public static String encrypt(String plaintext, String password) {
        return encrypt(plaintext, password, EncryptAlgorithm.AES_GCM);
    }
}
