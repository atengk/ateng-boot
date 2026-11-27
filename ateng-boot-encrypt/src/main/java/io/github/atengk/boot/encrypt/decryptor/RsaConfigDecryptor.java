package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.parser.MultilineSecretFormatter;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * 纯 JDK 原生 RSA 非对称加解密器
 * 密钥生成、X.509/PKCS#8 解析与 PEM 编解码委托至 Core {@link RsaUtil}，
 * 保留面向长文本配置的分段加解密能力及 {@code OAEP(MGF1-SHA256) -> OAEP(MGF1-SHA1) -> PKCS1Padding} 三级平滑兼容解密链
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class RsaConfigDecryptor implements ConfigDecryptor {

    public static final String ALGORITHM_RSA = RsaUtil.ALGORITHM_RSA;
    public static final String TRANSFORMATION_OAEP = RsaUtil.TRANSFORMATION_OAEP_SHA256;
    public static final String TRANSFORMATION_PKCS1 = "RSA/ECB/PKCS1Padding";

    public static final int DEFAULT_KEY_SIZE = RsaUtil.DEFAULT_KEY_SIZE;

    private static final OAEPParameterSpec OAEP_SHA256_SPEC = new OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
    );

    @Override
    public boolean supports(EncryptAlgorithm algorithm) {
        return algorithm == EncryptAlgorithm.RSA;
    }

    @Override
    public String decrypt(String ciphertext, String privateKeyStr) throws Exception {
        if (ciphertext == null || ciphertext.trim().isEmpty()) {
            return ciphertext;
        }
        String cleanCipher = MultilineSecretFormatter.cleanCiphertext(ciphertext);
        byte[] encryptedBytes = Base64.getDecoder().decode(cleanCipher);

        PrivateKey privateKey = parsePrivateKey(privateKeyStr);
        int keyBytes = getKeyLengthBytes(privateKey);

        byte[] decryptedBytes = null;
        try {
            // 三级兼容探测链：现代 OAEP(MGF1-SHA256) -> 存量 JDK OAEP(MGF1-SHA1) -> PKCS1Padding
            try {
                decryptedBytes = doSegmentedDecrypt(encryptedBytes, privateKey, TRANSFORMATION_OAEP, OAEP_SHA256_SPEC, keyBytes);
            } catch (Exception oaepSha256Ex) {
                try {
                    decryptedBytes = doSegmentedDecrypt(encryptedBytes, privateKey, TRANSFORMATION_OAEP, null, keyBytes);
                } catch (Exception oaepLegacyEx) {
                    try {
                        decryptedBytes = doSegmentedDecrypt(encryptedBytes, privateKey, TRANSFORMATION_PKCS1, null, keyBytes);
                    } catch (Exception pkcs1Ex) {
                        pkcs1Ex.addSuppressed(oaepSha256Ex);
                        pkcs1Ex.addSuppressed(oaepLegacyEx);
                        throw pkcs1Ex;
                    }
                }
            }
            String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);
            if (MultilineSecretFormatter.isPem(decryptedText)) {
                return MultilineSecretFormatter.formatPem(decryptedText);
            }
            return decryptedText;
        } finally {
            if (decryptedBytes != null) {
                Arrays.fill(decryptedBytes, (byte) 0);
            }
            Arrays.fill(encryptedBytes, (byte) 0);
        }
    }

    private static byte[] doSegmentedDecrypt(byte[] encryptedBytes, PrivateKey privateKey,
                                             String transformation, OAEPParameterSpec oaepSpec,
                                             int keyBytes) throws Exception {
        Cipher cipher = Cipher.getInstance(transformation);
        if (oaepSpec != null) {
            cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec);
        } else {
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] block = null;
        try {
            for (int offset = 0; offset < encryptedBytes.length; offset += keyBytes) {
                int len = Math.min(keyBytes, encryptedBytes.length - offset);
                block = cipher.doFinal(encryptedBytes, offset, len);
                out.write(block);
                Arrays.fill(block, (byte) 0);
            }
            return out.toByteArray();
        } finally {
            if (block != null) {
                Arrays.fill(block, (byte) 0);
            }
        }
    }

    /**
     * 使用公钥对明文进行加密（保持 JDK 默认 OAEP-MGF1-SHA1 行为，保障老版本节点灰度滚发兼容）
     *
     * @param plaintext    明文字符串
     * @param publicKeyStr 公钥（X.509 PEM 或 Base64 编码字符串）
     * @return Base64 密文字符串
     */
    public static String encrypt(String plaintext, String publicKeyStr) {
        return encrypt(plaintext, publicKeyStr, TRANSFORMATION_OAEP);
    }

    /**
     * 按指定转换模式加密
     *
     * @param plaintext      明文字符串
     * @param publicKeyStr   公钥字符串
     * @param transformation 密码学转换模式（如 OAEP 或 PKCS1Padding）
     * @return Base64 密文字符串
     */
    public static String encrypt(String plaintext, String publicKeyStr, String transformation) {
        if (plaintext == null) {
            return null;
        }
        try {
            PublicKey publicKey = parsePublicKey(publicKeyStr);
            int keyBytes = getKeyLengthBytes(publicKey);
            int maxPlainBlock = TRANSFORMATION_OAEP.equalsIgnoreCase(transformation)
                    ? (keyBytes - RsaUtil.OAEP_SHA256_OVERHEAD)
                    : (keyBytes - 11);

            byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            try {
                Cipher cipher = Cipher.getInstance(transformation);
                cipher.init(Cipher.ENCRYPT_MODE, publicKey);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] block = null;
                try {
                    for (int offset = 0; offset < plainBytes.length; offset += maxPlainBlock) {
                        int len = Math.min(maxPlainBlock, plainBytes.length - offset);
                        block = cipher.doFinal(plainBytes, offset, len);
                        out.write(block);
                        Arrays.fill(block, (byte) 0);
                    }
                    return Base64.getEncoder().encodeToString(out.toByteArray());
                } finally {
                    if (block != null) {
                        Arrays.fill(block, (byte) 0);
                    }
                }
            } finally {
                Arrays.fill(plainBytes, (byte) 0);
            }
        } catch (Exception e) {
            throw new IllegalStateException("RSA 公钥加密失败", e);
        }
    }

    /**
     * 生成 RSA 密钥对
     *
     * @param keySize 密钥位长（支持 2048、3072 或 4096）
     * @return RSA 密钥对
     */
    public static KeyPair generateKeyPair(int keySize) {
        return RsaUtil.generateKeyPair(keySize);
    }

    /**
     * 默认生成 2048 位 RSA 密钥对
     *
     * @return RSA 密钥对
     */
    public static KeyPair generateKeyPair() {
        return RsaUtil.generateKeyPair();
    }

    /**
     * 将公钥编码为标准 X.509 PEM 格式
     *
     * @param publicKey RSA 公钥
     * @return PEM 格式公钥字符串
     */
    public static String encodePublicKey(PublicKey publicKey) {
        if (publicKey == null) {
            return null;
        }
        return MultilineSecretFormatter.formatAsPem("PUBLIC KEY", RsaUtil.encodePublicKeyBase64(publicKey));
    }

    /**
     * 将公钥编码为标准 X.509 PEM 格式（语义别名）
     */
    public static String encodePublicKeyToPem(PublicKey publicKey) {
        return encodePublicKey(publicKey);
    }

    /**
     * 将私钥编码为标准 PKCS#8 PEM 格式
     *
     * @param privateKey RSA 私钥
     * @return PEM 格式私钥字符串
     */
    public static String encodePrivateKey(PrivateKey privateKey) {
        if (privateKey == null) {
            return null;
        }
        return MultilineSecretFormatter.formatAsPem("PRIVATE KEY", RsaUtil.encodePrivateKeyBase64(privateKey));
    }

    /**
     * 将私钥编码为标准 PKCS#8 PEM 格式（语义别名）
     */
    public static String encodePrivateKeyToPem(PrivateKey privateKey) {
        return encodePrivateKey(privateKey);
    }

    /**
     * 解析 X.509 公钥（支持 PEM 与 Base64）
     *
     * @param publicKeyStr 公钥字符串
     * @return PublicKey 实例
     */
    public static PublicKey parsePublicKey(String publicKeyStr) throws Exception {
        if (publicKeyStr == null || publicKeyStr.trim().isEmpty()) {
            throw new IllegalArgumentException("RSA 公钥不能为空");
        }
        String normalized = MultilineSecretFormatter.isPem(publicKeyStr)
                ? publicKeyStr
                : MultilineSecretFormatter.cleanCiphertext(publicKeyStr);
        return RsaUtil.parsePublicKey(normalized);
    }

    /**
     * 解析 PKCS#8 私钥（支持 PEM 与 Base64），完成瞬态内存抹零
     *
     * @param privateKeyStr 私钥字符串
     * @return PrivateKey 实例
     */
    public static PrivateKey parsePrivateKey(String privateKeyStr) throws Exception {
        if (privateKeyStr == null || privateKeyStr.trim().isEmpty()) {
            throw new IllegalArgumentException("RSA 私钥不能为空");
        }
        String normalized = MultilineSecretFormatter.isPem(privateKeyStr)
                ? privateKeyStr
                : MultilineSecretFormatter.cleanCiphertext(privateKeyStr);
        return RsaUtil.parsePrivateKey(normalized);
    }

    private static int getKeyLengthBytes(Key key) {
        if (key instanceof RSAKey rsaKey) {
            return (rsaKey.getModulus().bitLength() + 7) / 8;
        }
        throw new IllegalArgumentException("非 RSA 密钥类型，无法计算密钥字节长度: " + (key != null ? key.getClass().getName() : "null"));
    }
}
