package io.github.atengk.boot.web.crypto.cipher;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.model.ApiCipherInput;
import io.github.atengk.boot.web.crypto.model.ApiCipherResult;
import io.github.atengk.boot.web.crypto.model.ApiCryptoContext;
import io.github.atengk.boot.web.crypto.spi.ApiCipher;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * 基于纯 JDK 17 原生 RSA-OAEP-SHA256 与 AES-256-GCM 的混合加解密引擎
 * 零外部重型加密依赖，支持 PEM 与 Base64 格式 RSA X.509/PKCS#8 密钥及可选 AAD 附加认证数据
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class RsaAesGcmApiCipher implements ApiCipher {

    private static final String KEY_ALGORITHM = "RSA";
    private static final String RSA_OAEP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final OAEPParameterSpec OAEP_SHA256_SPEC = new OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
    );
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_TAG_BYTES = CryptoUtil.GCM_TAG_LENGTH / 8;

    @Override
    public boolean supports(CryptoAlgorithm algorithm) {
        return algorithm == CryptoAlgorithm.RSA_AES_GCM;
    }

    @Override
    public ApiCipherResult encrypt(byte[] plaintext, ApiCryptoContext context) {
        if (context == null || !StringUtils.hasText(context.getKey())) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未配置响应体 RSA 加密公钥");
        }
        PublicKey publicKey = parsePublicKey(context.getKey());
        byte[] safePlaintext = plaintext != null ? plaintext : new byte[0];
        byte[] sessionKey = CryptoUtil.generateKey(AES_KEY_BITS);
        byte[] iv = CryptoUtil.generateGcmIv();
        try {
            byte[] cipherBytes = CryptoUtil.encryptGcm(safePlaintext, sessionKey, iv, context.getAad());
            byte[] wrappedKey = rsaEncrypt(sessionKey, publicKey);
            return new ApiCipherResult(
                    Base64.getEncoder().encodeToString(wrappedKey),
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(cipherBytes)
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "RSA+AES-GCM 响应体加密失败: " + e.getMessage(), e);
        } finally {
            Arrays.fill(sessionKey, (byte) 0);
        }
    }

    @Override
    public byte[] decrypt(ApiCipherInput input, ApiCryptoContext context) {
        if (context == null || !StringUtils.hasText(context.getKey())) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未配置请求体 RSA 解密私钥");
        }
        if (input == null
                || !StringUtils.hasText(input.encryptedKey())
                || !StringUtils.hasText(input.iv())
                || !StringUtils.hasText(input.cipherText())) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "缺少加密传输必要元数据或密文载荷");
        }

        PrivateKey privateKey = parsePrivateKey(context.getKey());
        byte[] wrappedKey = decodeBase64Strict(input.encryptedKey(), "加密会话密钥 Base64 格式非法");
        byte[] iv = decodeBase64Strict(input.iv(), "加密 IV Base64 格式非法");
        byte[] cipherBytes = decodeBase64Strict(input.cipherText(), "密文载荷 Base64 格式非法");

        if (iv.length != CryptoUtil.DEFAULT_GCM_IV_SIZE) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "非法的 AES-GCM IV 长度，期望 12 字节");
        }
        if (cipherBytes.length < GCM_TAG_BYTES) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "密文载荷长度非法，缺少完整的 GCM 认证标签");
        }

        byte[] sessionKey = rsaDecrypt(wrappedKey, privateKey);
        try {
            if (sessionKey.length != 16 && sessionKey.length != 24 && sessionKey.length != 32) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, "解封得到的 AES 会话密钥长度非法");
            }
            return CryptoUtil.decryptGcm(cipherBytes, sessionKey, iv, context.getAad());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            Throwable rootCause = (e instanceof IllegalStateException && e.getCause() != null) ? e.getCause() : e;
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求体密文解密或认证校验失败", rootCause);
        } finally {
            Arrays.fill(sessionKey, (byte) 0);
        }
    }

    private static byte[] rsaEncrypt(byte[] data, PublicKey publicKey) {
        try {
            Cipher cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA256_SPEC);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "RSA-OAEP 会话密钥封装失败: " + e.getMessage(), e);
        }
    }

    private static byte[] rsaDecrypt(byte[] wrappedKey, PrivateKey privateKey) {
        try {
            Cipher cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_SHA256_SPEC);
            return cipher.doFinal(wrappedKey);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求会话密钥解封失败", e);
        }
    }

    private static byte[] decodeBase64Strict(String raw, String errorMessage) {
        try {
            return Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, errorMessage, e);
        }
    }

    private static PublicKey parsePublicKey(String keyPem) {
        try {
            String clean = cleanKey(keyPem);
            byte[] keyBytes = Base64.getDecoder().decode(clean);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(spec);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "RSA 加密公钥配置格式非法: " + e.getMessage(), e);
        }
    }

    private static PrivateKey parsePrivateKey(String keyPem) {
        byte[] keyBytes = null;
        try {
            String clean = cleanKey(keyPem);
            keyBytes = Base64.getDecoder().decode(clean);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(spec);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "RSA 解密私钥配置格式非法: " + e.getMessage(), e);
        } finally {
            if (keyBytes != null) {
                Arrays.fill(keyBytes, (byte) 0);
            }
        }
    }

    private static String cleanKey(String key) {
        return key.replace("-----BEGIN RSA PUBLIC KEY-----", "")
                .replace("-----END RSA PUBLIC KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
    }
}
