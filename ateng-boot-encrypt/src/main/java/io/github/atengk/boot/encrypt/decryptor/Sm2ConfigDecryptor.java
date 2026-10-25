package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.parser.MultilineSecretFormatter;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * 纯 JDK 原生国密 SM2 非对称解密器 (GB/T 32918-2016)
 * 100% 依托原生 BigInteger 与纯 Java 数学算法，零 Bouncy Castle 外部依赖
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class Sm2ConfigDecryptor implements ConfigDecryptor {

    @Override
    public boolean supports(EncryptAlgorithm algorithm) {
        return algorithm == EncryptAlgorithm.SM2;
    }

    @Override
    public String decrypt(String ciphertext, String privateKeyStr) throws Exception {
        if (ciphertext == null || ciphertext.trim().isEmpty()) {
            return ciphertext;
        }
        String cleanCipher = MultilineSecretFormatter.cleanCiphertext(ciphertext);
        byte[] cipherBytes = Base64.getDecoder().decode(cleanCipher);

        BigInteger privateKey = Sm2Engine.parsePrivateKey(privateKeyStr);
        byte[] decryptedBytes = null;
        try {
            decryptedBytes = Sm2Engine.decrypt(cipherBytes, privateKey);
            String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);
            if (MultilineSecretFormatter.isPem(decryptedText)) {
                return MultilineSecretFormatter.formatPem(decryptedText);
            }
            return decryptedText;
        } finally {
            if (decryptedBytes != null) {
                Arrays.fill(decryptedBytes, (byte) 0);
            }
            Arrays.fill(cipherBytes, (byte) 0);
        }
    }

    /**
     * SM2 密钥对别名载体
     */
    public static class Sm2KeyPair extends Sm2Engine.Sm2KeyPair {
        public Sm2KeyPair(BigInteger privateKey, Sm2Engine.Point publicKey) {
            super(privateKey, publicKey);
        }

        public Sm2KeyPair(Sm2Engine.Sm2KeyPair pair) {
            super(pair.getPrivateKey(), pair.getPublicKey());
        }
    }

    /**
     * 生成 SM2 椭圆曲线非对称密钥对
     *
     * @return SM2 密钥对
     */
    public static Sm2KeyPair generateKeyPair() {
        return new Sm2KeyPair(Sm2Engine.generateKeyPair());
    }

    /**
     * 执行 SM2 公钥加密并返回 Base64 编码密文字符串 (标准 C1C3C2)
     *
     * @param plaintext    明文字符串
     * @param publicKeyStr 公钥（支持 Hex、Base64 或 X.509 PEM）
     * @return Base64 密文字符串
     */
    public static String encrypt(String plaintext, String publicKeyStr) {
        if (plaintext == null) {
            return null;
        }
        Sm2Engine.Point pubPoint = Sm2Engine.parsePublicKey(publicKeyStr);
        byte[] cipherBytes = Sm2Engine.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), pubPoint);
        return Base64.getEncoder().encodeToString(cipherBytes);
    }

    /**
     * 执行 SM2 公钥加密并返回标准 ASN.1 DER 格式的 Base64 密文字符串
     *
     * @param plaintext    明文字符串
     * @param publicKeyStr 公钥（支持 Hex、Base64 或 X.509 PEM）
     * @return ASN.1 DER 编码的 Base64 密文字符串
     */
    public static String encryptAsn1Der(String plaintext, String publicKeyStr) {
        if (plaintext == null) {
            return null;
        }
        Sm2Engine.Point pubPoint = Sm2Engine.parsePublicKey(publicKeyStr);
        byte[] c1c3c2 = Sm2Engine.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), pubPoint);
        byte[] der = Sm2Engine.toAsn1Der(c1c3c2);
        return Base64.getEncoder().encodeToString(der);
    }

    /**
     * 将 SM2 公钥格式化为 PEM 格式
     *
     * @param pubPoint 公钥点
     * @return PEM 格式公钥字符串
     */
    public static String encodePublicKey(Sm2Engine.Point pubPoint) {
        if (pubPoint == null) {
            return null;
        }
        String base64 = Base64.getEncoder().encodeToString(pubPoint.toUncompressedBytes());
        return formatAsPem("PUBLIC KEY", base64);
    }

    /**
     * 将 SM2 私钥格式化为 PEM 格式
     *
     * @param privateKey 私钥标量
     * @return PEM 格式私钥字符串
     */
    public static String encodePrivateKey(BigInteger privateKey) {
        if (privateKey == null) {
            return null;
        }
        byte[] dBytes = Sm2Engine.toFixedBytes(privateKey, 32);
        try {
            String base64 = Base64.getEncoder().encodeToString(dBytes);
            return formatAsPem("PRIVATE KEY", base64);
        } finally {
            Arrays.fill(dBytes, (byte) 0);
        }
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
}
