package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.exception.ConfigDecryptionException;

/**
 * 配置加解密双向对称门面工具
 * 提供纯原生统一的对称加密与国密解密快捷方法，用于开发调试、脱机脚本与单测
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class ConfigEncryptor {

    private static final AesGcmConfigDecryptor AES_GCM_DECRYPTOR = new AesGcmConfigDecryptor();
    private static final AesCbcConfigDecryptor AES_CBC_DECRYPTOR = new AesCbcConfigDecryptor();
    private static final Sm4ConfigDecryptor SM4_DECRYPTOR = new Sm4ConfigDecryptor();
    private static final RsaConfigDecryptor RSA_DECRYPTOR = new RsaConfigDecryptor();
    private static final Sm2ConfigDecryptor SM2_DECRYPTOR = new Sm2ConfigDecryptor();

    private ConfigEncryptor() {
    }

    /**
     * 按指定算法对明文进行加密
     *
     * @param plaintext 明文字符串
     * @param password  主密钥口令（或公钥）
     * @param algorithm 加密算法
     * @return 包含 IV 的 Base64 密文；入参为 null 时返回 null
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
     * @return 包含 12 字节 IV 的 Base64 密文；入参为 null 时返回 null
     */
    public static String encrypt(String plaintext, String password) {
        return encrypt(plaintext, password, EncryptAlgorithm.AES_GCM);
    }

    /**
     * 按指定算法对密文进行解密还原为明文（对称完备性门面）
     *
     * @param ciphertext 密文字符串（Base64 编码，通常包含前置 IV）
     * @param password   解密主密钥口令（或私钥）
     * @param algorithm  加密算法
     * @return 还原后的真实明文字符串；入参为 null 时返回 null
     * @throws ConfigDecryptionException 解密失败时抛出语义化业务异常并保留原始异常因果链
     */
    public static String decrypt(String ciphertext, String password, EncryptAlgorithm algorithm) {
        if (ciphertext == null) {
            return null;
        }
        EncryptAlgorithm algo = algorithm != null ? algorithm : EncryptAlgorithm.AES_GCM;
        try {
            return switch (algo) {
                case AES_GCM -> AES_GCM_DECRYPTOR.decrypt(ciphertext, password);
                case AES_CBC -> AES_CBC_DECRYPTOR.decrypt(ciphertext, password);
                case SM4_CBC, SM4_ECB -> SM4_DECRYPTOR.decrypt(ciphertext, password);
                case RSA -> RSA_DECRYPTOR.decrypt(ciphertext, password);
                case SM2 -> SM2_DECRYPTOR.decrypt(ciphertext, password);
                default -> throw new UnsupportedOperationException("暂不支持算法的解密: " + algo);
            };
        } catch (ConfigDecryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigDecryptionException("raw", null, algo.getCode(), e);
        }
    }

    /**
     * 默认按 AES-GCM 算法对密文进行解密
     *
     * @param ciphertext 包含 12 字节 IV 的 Base64 密文
     * @param password   主密钥口令
     * @return 还原后的明文字符串；入参为 null 时返回 null
     * @throws ConfigDecryptionException 解密失败时抛出异常
     */
    public static String decrypt(String ciphertext, String password) {
        return decrypt(ciphertext, password, EncryptAlgorithm.AES_GCM);
    }
}
