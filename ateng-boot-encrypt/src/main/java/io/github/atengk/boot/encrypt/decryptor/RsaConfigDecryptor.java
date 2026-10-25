package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.parser.MultilineSecretFormatter;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * 纯 JDK 原生 RSA 非对称加解密器
 * 支持 2048 / 4096 位 RSA 算法，实现开发/构建持有公钥加密、生产环境仅注入私钥解密的公私钥职责绝对隔离
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class RsaConfigDecryptor implements ConfigDecryptor {

    public static final String ALGORITHM_RSA = "RSA";
    public static final String TRANSFORMATION_OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    public static final String TRANSFORMATION_PKCS1 = "RSA/ECB/PKCS1Padding";

    public static final int DEFAULT_KEY_SIZE = 2048;

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
            // 优先采用高安全 OAEP 填充模式，若解密失败则平滑降级尝试 PKCS1Padding 模式
            try {
                decryptedBytes = doSegmentedDecrypt(encryptedBytes, privateKey, TRANSFORMATION_OAEP, keyBytes);
            } catch (Exception e) {
                decryptedBytes = doSegmentedDecrypt(encryptedBytes, privateKey, TRANSFORMATION_PKCS1, keyBytes);
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
                                             String transformation, int keyBytes) throws Exception {
        Cipher cipher = Cipher.getInstance(transformation);
        cipher.init(Cipher.DECRYPT_MODE, privateKey);

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
     * 使用公钥对明文进行加密（供开发、CI/CD 构建期与测试使用）
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
                    ? (keyBytes - 66)
                    : (keyBytes - 11);

            byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance(transformation);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int offset = 0; offset < plainBytes.length; offset += maxPlainBlock) {
                int len = Math.min(maxPlainBlock, plainBytes.length - offset);
                byte[] block = cipher.doFinal(plainBytes, offset, len);
                out.write(block);
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("RSA 公钥加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成 RSA 密钥对
     *
     * @param keySize 密钥位长（通常为 2048 或 4096）
     * @return RSA 密钥对
     */
    public static KeyPair generateKeyPair(int keySize) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM_RSA);
            generator.initialize(keySize);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 环境不支持 RSA 算法", e);
        }
    }

    /**
     * 默认生成 2048 位 RSA 密钥对
     *
     * @return RSA 密钥对
     */
    public static KeyPair generateKeyPair() {
        return generateKeyPair(DEFAULT_KEY_SIZE);
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
        String base64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        return formatAsPem("PUBLIC KEY", base64);
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
        byte[] encoded = privateKey.getEncoded();
        try {
            String base64 = Base64.getEncoder().encodeToString(encoded);
            return formatAsPem("PRIVATE KEY", base64);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
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
        String clean = cleanKeyString(publicKeyStr, "PUBLIC KEY");
        byte[] keyBytes = Base64.getDecoder().decode(clean);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory factory = KeyFactory.getInstance(ALGORITHM_RSA);
        return factory.generatePublic(spec);
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
        String clean = cleanKeyString(privateKeyStr, "PRIVATE KEY");
        byte[] keyBytes = Base64.getDecoder().decode(clean);
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM_RSA);
            return factory.generatePrivate(spec);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private static String cleanKeyString(String keyStr, String type) {
        String cleaned = keyStr
                .replace("-----BEGIN RSA " + type + "-----", "")
                .replace("-----END RSA " + type + "-----", "")
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "");
        return MultilineSecretFormatter.cleanCiphertext(cleaned);
    }

    private static String formatAsPem(String type, String base64) {
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append("\n");
        }
        sb.append("-----END ").append(type).append("-----\n");
        return sb.toString();
    }

    private static int getKeyLengthBytes(Object key) {
        if (key instanceof RSAKey rsaKey) {
            return (rsaKey.getModulus().bitLength() + 7) / 8;
        }
        return 256;
    }
}
