package io.github.atengk.boot.core.util;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * 纯 JDK 17 原生 RSA 非对称加解密与数字签名原语工具类
 * 统一提供 2048/3072/4096 位密钥生成、X.509/PKCS#8 密钥解析与 PEM 编解码、
 * 单块 {@code RSA-OAEP-SHA256}（显式 {@code MGF1-SHA256}）加解密及 {@code SHA256withRSA} / {@code RSASSA-PSS} 签验
 *
 * @author Ateng
 * @since 2026-11-27
 */
public final class RsaUtil {

    public static final String ALGORITHM_RSA = "RSA";
    public static final String TRANSFORMATION_OAEP_SHA256 = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    public static final String SIGN_ALGORITHM_SHA256 = "SHA256withRSA";
    public static final String SIGN_ALGORITHM_PSS = "RSASSA-PSS";
    public static final int DEFAULT_KEY_SIZE = 2048;
    public static final int OAEP_SHA256_OVERHEAD = 66;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final OAEPParameterSpec OAEP_SHA256_SPEC = new OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
    );
    private static final PSSParameterSpec PSS_SHA256_SPEC = new PSSParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            32,
            1
    );

    private RsaUtil() {
    }

    /**
     * 生成默认 2048 位 RSA 密钥对
     *
     * @return RSA 密钥对
     */
    public static KeyPair generateKeyPair() {
        return generateKeyPair(DEFAULT_KEY_SIZE);
    }

    /**
     * 生成指定位长的 RSA 密钥对
     *
     * @param keySize 密钥位长，仅支持 2048、3072 或 4096
     * @return RSA 密钥对
     * @throws IllegalArgumentException {@code keySize} 不在允许列表时抛出
     */
    public static KeyPair generateKeyPair(int keySize) {
        if (keySize != 2048 && keySize != 3072 && keySize != 4096) {
            throw new IllegalArgumentException("RSA 密钥位长必须为 2048、3072 或 4096，实际入参: " + keySize);
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM_RSA);
            generator.initialize(keySize, SECURE_RANDOM);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 环境不支持 RSA 算法", e);
        }
    }

    /**
     * 将 RSA 公钥编码为 X.509 Base64 字符串
     *
     * @param publicKey RSA 公钥
     * @return Base64 编码公钥；入参为 {@code null} 时返回 {@code null}
     */
    public static String encodePublicKeyBase64(PublicKey publicKey) {
        if (publicKey == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * 将 RSA 私钥编码为 PKCS#8 Base64 字符串（内部瞬态编码字节清零）
     *
     * @param privateKey RSA 私钥
     * @return Base64 编码私钥；入参为 {@code null} 时返回 {@code null}
     */
    public static String encodePrivateKeyBase64(PrivateKey privateKey) {
        if (privateKey == null) {
            return null;
        }
        byte[] encoded = privateKey.getEncoded();
        try {
            return Base64.getEncoder().encodeToString(encoded);
        } finally {
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    /**
     * 将 RSA 公钥编码为标准 X.509 PEM 格式（64 字符换行）
     *
     * @param publicKey RSA 公钥
     * @return PEM 格式公钥；入参为 {@code null} 时返回 {@code null}
     */
    public static String encodePublicKeyToPem(PublicKey publicKey) {
        if (publicKey == null) {
            return null;
        }
        return formatAsPem("PUBLIC KEY", encodePublicKeyBase64(publicKey));
    }

    /**
     * 将 RSA 私钥编码为标准 PKCS#8 PEM 格式（64 字符换行，内部瞬态编码字节清零）
     *
     * @param privateKey RSA 私钥
     * @return PEM 格式私钥；入参为 {@code null} 时返回 {@code null}
     */
    public static String encodePrivateKeyToPem(PrivateKey privateKey) {
        if (privateKey == null) {
            return null;
        }
        byte[] encoded = privateKey.getEncoded();
        try {
            String base64 = Base64.getEncoder().encodeToString(encoded);
            return formatAsPem("PRIVATE KEY", base64);
        } finally {
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    /**
     * 解析 PEM 或 Base64 编码的 X.509 RSA 公钥
     *
     * @param publicKeyStr X.509 PEM 或 Base64 公钥字符串
     * @return RSA {@link PublicKey}
     * @throws IllegalArgumentException 公钥为空或格式非法时抛出
     */
    public static PublicKey parsePublicKey(String publicKeyStr) {
        if (publicKeyStr == null || publicKeyStr.isBlank()) {
            throw new IllegalArgumentException("RSA 公钥不能为空");
        }
        String clean = cleanKeyString(publicKeyStr, "PUBLIC KEY");
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("RSA 公钥 Base64 格式非法", e);
        }
        return parsePublicKey(keyBytes);
    }

    /**
     * 解析 X.509 DER 二进制编码的 RSA 公钥
     *
     * @param x509DerBytes X.509 DER 字节数组
     * @return RSA {@link PublicKey}
     * @throws IllegalArgumentException 字节数组为空或结构非法时抛出
     */
    public static PublicKey parsePublicKey(byte[] x509DerBytes) {
        if (x509DerBytes == null || x509DerBytes.length == 0) {
            throw new IllegalArgumentException("RSA 公钥 DER 字节数组不能为空");
        }
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(x509DerBytes);
            PublicKey key = KeyFactory.getInstance(ALGORITHM_RSA).generatePublic(spec);
            getKeyLengthBytes(key);
            return key;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("RSA X.509 公钥解析失败", e);
        }
    }

    /**
     * 解析 PEM 或 Base64 编码的 PKCS#8 RSA 私钥（暂不支持传统 PKCS#1，检测到将明确报错指引转换）
     *
     * @param privateKeyStr PKCS#8 PEM 或 Base64 私钥字符串
     * @return RSA {@link PrivateKey}
     * @throws IllegalArgumentException 私钥为空、为传统 PKCS#1 或格式非法时抛出
     */
    public static PrivateKey parsePrivateKey(String privateKeyStr) {
        if (privateKeyStr == null || privateKeyStr.isBlank()) {
            throw new IllegalArgumentException("RSA 私钥不能为空");
        }
        boolean hasPkcs1Header = privateKeyStr.contains("BEGIN RSA PRIVATE KEY");
        String clean = cleanKeyString(privateKeyStr, "PRIVATE KEY");
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("RSA 私钥 Base64 格式非法", e);
        }
        try {
            return parsePrivateKey(keyBytes);
        } catch (IllegalArgumentException e) {
            if (hasPkcs1Header) {
                throw new IllegalArgumentException(
                        "检测到传统 OpenSSL PKCS#1 RSA 私钥，请先转换为 PKCS#8 格式（如 openssl pkcs8 -topk8 -nocrypt）",
                        e
                );
            }
            throw e;
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /**
     * 解析 PKCS#8 DER 二进制编码的 RSA 私钥
     *
     * @param pkcs8DerBytes PKCS#8 DER 字节数组
     * @return RSA {@link PrivateKey}
     * @throws IllegalArgumentException 字节数组为空或非合法 PKCS#8 格式时抛出
     */
    public static PrivateKey parsePrivateKey(byte[] pkcs8DerBytes) {
        if (pkcs8DerBytes == null || pkcs8DerBytes.length == 0) {
            throw new IllegalArgumentException("RSA 私钥 DER 字节数组不能为空");
        }
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8DerBytes);
            PrivateKey key = KeyFactory.getInstance(ALGORITHM_RSA).generatePrivate(spec);
            getKeyLengthBytes(key);
            return key;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("RSA PKCS#8 私钥解析失败", e);
        }
    }

    /**
     * 获取 RSA-OAEP-SHA256 单块允许加密的最大明文字节长度（{@code keyBytes - 66}）
     *
     * @param key RSA 公钥或私钥
     * @return 单块最大明文字节长度
     */
    public static int getOaepMaxPlaintextBytes(Key key) {
        int keyBytes = getKeyLengthBytes(key);
        int maxPlain = keyBytes - OAEP_SHA256_OVERHEAD;
        if (maxPlain <= 0) {
            throw new IllegalArgumentException("RSA 密钥长度过短，无法支持 OAEP-SHA256 填充");
        }
        return maxPlain;
    }

    /**
     * 使用 RSA-OAEP-SHA256（显式 {@code MGF1-SHA256}）对单块明文字节加密
     *
     * @param plaintext 明文字节数组（长度不能超过 {@code keyBytes - 66}）
     * @param publicKey RSA 公钥
     * @return 密文字节数组
     * @throws IllegalArgumentException 明文或公钥为 null、或明文超出单块上限时抛出
     * @throws IllegalStateException    加密运算失败时抛出
     */
    public static byte[] encryptOaep(byte[] plaintext, PublicKey publicKey) {
        if (plaintext == null) {
            throw new IllegalArgumentException("RSA 待加密明文不能为 null");
        }
        if (publicKey == null) {
            throw new IllegalArgumentException("RSA 加密公钥不能为 null");
        }
        int maxPlainBytes = getOaepMaxPlaintextBytes(publicKey);
        if (plaintext.length > maxPlainBytes) {
            throw new IllegalArgumentException(
                    "RSA-OAEP-SHA256 单块明文长度超出上限 (" + maxPlainBytes + " 字节)，实际: " + plaintext.length);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_OAEP_SHA256);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA256_SPEC);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA-OAEP 加密执行失败", e);
        }
    }

    /**
     * 使用 RSA-OAEP-SHA256（显式 {@code MGF1-SHA256}）对单块明文字节加密
     *
     * @param plaintext    明文字节数组
     * @param publicKeyStr X.509 PEM 或 Base64 公钥字符串
     * @return 密文字节数组
     */
    public static byte[] encryptOaep(byte[] plaintext, String publicKeyStr) {
        return encryptOaep(plaintext, parsePublicKey(publicKeyStr));
    }

    /**
     * 使用 RSA-OAEP-SHA256 加密 UTF-8 字符串并返回标准 Base64 密文
     *
     * @param plaintext    明文字符串（UTF-8）
     * @param publicKeyStr X.509 PEM 或 Base64 公钥字符串
     * @return 标准 Base64 编码密文
     */
    public static String encryptOaepBase64(String plaintext, String publicKeyStr) {
        if (plaintext == null) {
            throw new IllegalArgumentException("RSA 待加密明文不能为 null");
        }
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] cipherBytes = encryptOaep(plainBytes, parsePublicKey(publicKeyStr));
            return Base64.getEncoder().encodeToString(cipherBytes);
        } finally {
            Arrays.fill(plainBytes, (byte) 0);
        }
    }

    /**
     * 使用 RSA-OAEP-SHA256（显式 {@code MGF1-SHA256}）对单块密文字节解密
     *
     * @param ciphertext 密文字节数组
     * @param privateKey RSA 私钥
     * @return 解密后的明文字节数组
     * @throws IllegalArgumentException 密文或私钥为空时抛出
     * @throws IllegalStateException    解密或填充校验失败时抛出（保留原始 cause）
     */
    public static byte[] decryptOaep(byte[] ciphertext, PrivateKey privateKey) {
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("RSA 待解密密文不能为空");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("RSA 解密私钥不能为 null");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_OAEP_SHA256);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_SHA256_SPEC);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA-OAEP 解密执行失败", e);
        }
    }

    /**
     * 使用 RSA-OAEP-SHA256（显式 {@code MGF1-SHA256}）对单块密文字节解密
     *
     * @param ciphertext    密文字节数组
     * @param privateKeyStr PKCS#8 PEM 或 Base64 私钥字符串
     * @return 解密后的明文字节数组
     */
    public static byte[] decryptOaep(byte[] ciphertext, String privateKeyStr) {
        return decryptOaep(ciphertext, parsePrivateKey(privateKeyStr));
    }

    /**
     * 使用 RSA-OAEP-SHA256 解密 Base64 编码密文并返回 UTF-8 明文字符串
     *
     * @param ciphertextBase64 Base64 编码密文
     * @param privateKeyStr    PKCS#8 PEM 或 Base64 私钥字符串
     * @return UTF-8 明文字符串
     */
    public static String decryptOaepBase64(String ciphertextBase64, String privateKeyStr) {
        if (ciphertextBase64 == null || ciphertextBase64.isBlank()) {
            throw new IllegalArgumentException("RSA Base64 密文不能为空");
        }
        byte[] cipherBytes;
        try {
            cipherBytes = Base64.getDecoder().decode(ciphertextBase64.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("RSA 密文 Base64 格式非法", e);
        }
        byte[] plainBytes = decryptOaep(cipherBytes, parsePrivateKey(privateKeyStr));
        try {
            return new String(plainBytes, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plainBytes, (byte) 0);
        }
    }

    /**
     * 使用 {@code SHA256withRSA} 对字节数组生成数字签名
     *
     * @param data       待签名数据
     * @param privateKey RSA 私钥
     * @return 签名字节数组
     */
    public static byte[] signSha256(byte[] data, PrivateKey privateKey) {
        return signInternal(SIGN_ALGORITHM_SHA256, null, data, privateKey);
    }

    /**
     * 使用 {@code SHA256withRSA} 对字符串生成 Base64 数字签名
     *
     * @param data          待签名文本（UTF-8）
     * @param privateKeyStr PKCS#8 PEM 或 Base64 私钥
     * @return Base64 编码签名
     */
    public static String signSha256Base64(String data, String privateKeyStr) {
        if (data == null) {
            throw new IllegalArgumentException("待签名文本不能为 null");
        }
        return Base64.getEncoder().encodeToString(
                signSha256(data.getBytes(StandardCharsets.UTF_8), parsePrivateKey(privateKeyStr))
        );
    }

    /**
     * 使用 {@code SHA256withRSA} 验签
     *
     * @param data      原文数据
     * @param signature 签名字节数组
     * @param publicKey RSA 公钥
     * @return 验签通过返回 {@code true}；参数为空或校验失败安全返回 {@code false}
     */
    public static boolean verifySha256(byte[] data, byte[] signature, PublicKey publicKey) {
        return verifyInternal(SIGN_ALGORITHM_SHA256, null, data, signature, publicKey);
    }

    /**
     * 使用 {@code SHA256withRSA} 对 Base64 签名执行验签
     *
     * @param data            原文文本（UTF-8）
     * @param signatureBase64 Base64 签名
     * @param publicKeyStr    X.509 PEM 或 Base64 公钥
     * @return 验签通过返回 {@code true}；入参非法或验签失败安全返回 {@code false}
     */
    public static boolean verifySha256Base64(String data, String signatureBase64, String publicKeyStr) {
        return verifyBase64Internal(SIGN_ALGORITHM_SHA256, null, data, signatureBase64, publicKeyStr);
    }

    /**
     * 使用 {@code RSASSA-PSS}（SHA-256 / MGF1-SHA256 / saltLen=32）生成数字签名
     *
     * @param data       待签名数据
     * @param privateKey RSA 私钥
     * @return 签名字节数组
     */
    public static byte[] signPss(byte[] data, PrivateKey privateKey) {
        return signInternal(SIGN_ALGORITHM_PSS, PSS_SHA256_SPEC, data, privateKey);
    }

    /**
     * 使用 {@code RSASSA-PSS}（SHA-256 / MGF1-SHA256 / saltLen=32）生成 Base64 数字签名
     *
     * @param data          待签名文本（UTF-8）
     * @param privateKeyStr PKCS#8 PEM 或 Base64 私钥
     * @return Base64 编码签名
     */
    public static String signPssBase64(String data, String privateKeyStr) {
        if (data == null) {
            throw new IllegalArgumentException("待签名文本不能为 null");
        }
        return Base64.getEncoder().encodeToString(
                signPss(data.getBytes(StandardCharsets.UTF_8), parsePrivateKey(privateKeyStr))
        );
    }

    /**
     * 使用 {@code RSASSA-PSS}（SHA-256 / MGF1-SHA256 / saltLen=32）验签
     *
     * @param data      原文数据
     * @param signature 签名字节数组
     * @param publicKey RSA 公钥
     * @return 验签通过返回 {@code true}；参数为空或校验失败安全返回 {@code false}
     */
    public static boolean verifyPss(byte[] data, byte[] signature, PublicKey publicKey) {
        return verifyInternal(SIGN_ALGORITHM_PSS, PSS_SHA256_SPEC, data, signature, publicKey);
    }

    /**
     * 使用 {@code RSASSA-PSS}（SHA-256 / MGF1-SHA256 / saltLen=32）对 Base64 签名执行验签
     *
     * @param data            原文文本（UTF-8）
     * @param signatureBase64 Base64 签名
     * @param publicKeyStr    X.509 PEM 或 Base64 公钥
     * @return 验签通过返回 {@code true}；入参非法或验签失败安全返回 {@code false}
     */
    public static boolean verifyPssBase64(String data, String signatureBase64, String publicKeyStr) {
        return verifyBase64Internal(SIGN_ALGORITHM_PSS, PSS_SHA256_SPEC, data, signatureBase64, publicKeyStr);
    }

    private static byte[] signInternal(String algorithm, PSSParameterSpec pssSpec, byte[] data, PrivateKey privateKey) {
        if (data == null) {
            throw new IllegalArgumentException("待签名数据不能为 null");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("RSA 签名私钥不能为 null");
        }
        try {
            Signature signer = Signature.getInstance(algorithm);
            if (pssSpec != null) {
                signer.setParameter(pssSpec);
            }
            signer.initSign(privateKey);
            signer.update(data);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA 数字签名计算失败", e);
        }
    }

    private static boolean verifyInternal(String algorithm, PSSParameterSpec pssSpec,
                                          byte[] data, byte[] signature, PublicKey publicKey) {
        if (data == null || signature == null || signature.length == 0 || publicKey == null) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(algorithm);
            if (pssSpec != null) {
                verifier.setParameter(pssSpec);
            }
            verifier.initVerify(publicKey);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean verifyBase64Internal(String algorithm, PSSParameterSpec pssSpec,
                                                String data, String signatureBase64, String publicKeyStr) {
        if (data == null || signatureBase64 == null || signatureBase64.isBlank()
                || publicKeyStr == null || publicKeyStr.isBlank()) {
            return false;
        }
        try {
            PublicKey publicKey = parsePublicKey(publicKeyStr);
            byte[] sigBytes = Base64.getDecoder().decode(signatureBase64.replaceAll("\\s+", ""));
            return verifyInternal(algorithm, pssSpec, data.getBytes(StandardCharsets.UTF_8), sigBytes, publicKey);
        } catch (Exception e) {
            return false;
        }
    }

    private static int getKeyLengthBytes(Key key) {
        if (key instanceof RSAKey rsaKey) {
            return (rsaKey.getModulus().bitLength() + 7) / 8;
        }
        throw new IllegalArgumentException("非 RSA 密钥类型，无法获取密钥模数长度");
    }

    private static String cleanKeyString(String keyStr, String type) {
        return keyStr
                .replace("-----BEGIN RSA " + type + "-----", "")
                .replace("-----END RSA " + type + "-----", "")
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s+", "");
    }

    private static String formatAsPem(String type, String base64) {
        StringBuilder sb = new StringBuilder(base64.length() + 64);
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            int end = Math.min(i + 64, base64.length());
            sb.append(base64, i, end).append('\n');
        }
        sb.append("-----END ").append(type).append("-----");
        return sb.toString();
    }
}
