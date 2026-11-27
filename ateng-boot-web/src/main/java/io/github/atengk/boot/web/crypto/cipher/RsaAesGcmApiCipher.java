package io.github.atengk.boot.web.crypto.cipher;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.model.ApiCipherInput;
import io.github.atengk.boot.web.crypto.model.ApiCipherResult;
import io.github.atengk.boot.web.crypto.model.ApiCryptoContext;
import io.github.atengk.boot.web.crypto.spi.ApiCipher;
import org.springframework.util.StringUtils;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;

/**
 * 基于纯 JDK 17 原生 RSA-OAEP-SHA256 与 AES-256-GCM 的混合加解密引擎
 * 零外部重型加密依赖，RSA 密钥解析与 OAEP-SHA256 加解密委托至 {@link RsaUtil}，AES-GCM 委托至 {@link CryptoUtil}
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class RsaAesGcmApiCipher implements ApiCipher {

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
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "RSA+AES-GCM 响应体加密失败", e);
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

        // 先校验密文/IV 元数据格式，避免畸形载荷消耗非对称私钥解密算力
        byte[] iv = decodeAndValidateIv(input.iv());
        byte[] cipherBytes = decodeAndValidateCipherBytes(input.cipherText());
        byte[] sessionKey = unwrapSessionKey(input.encryptedKey(), context);
        try {
            return decryptGcmPayload(cipherBytes, sessionKey, iv, context.getAad());
        } finally {
            Arrays.fill(sessionKey, (byte) 0);
        }
    }

    @Override
    public byte[] unwrapSessionKey(String encryptedKey, ApiCryptoContext context) {
        if (context == null || !StringUtils.hasText(context.getKey())) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未配置请求体 RSA 解密私钥");
        }
        if (!StringUtils.hasText(encryptedKey)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "缺少加密会话密钥请求头");
        }
        PrivateKey privateKey = parsePrivateKey(context.getKey());
        byte[] wrappedKey = decodeBase64Strict(encryptedKey, "加密会话密钥 Base64 格式非法");
        byte[] sessionKey = rsaDecrypt(wrappedKey, privateKey);
        if (!isValidAesKeyLength(sessionKey)) {
            Arrays.fill(sessionKey, (byte) 0);
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "解封得到的 AES 会话密钥长度非法");
        }
        return sessionKey;
    }

    @Override
    public byte[] decryptWithSessionKey(byte[] sessionKey, String iv, String cipherText, ApiCryptoContext context) {
        if (!isValidAesKeyLength(sessionKey)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "AES 会话密钥缺失或长度非法");
        }
        if (!StringUtils.hasText(iv) || !StringUtils.hasText(cipherText)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "缺少加密传输必要元数据或密文载荷");
        }
        byte[] ivBytes = decodeAndValidateIv(iv);
        byte[] cipherBytes = decodeAndValidateCipherBytes(cipherText);
        byte[] aad = context != null ? context.getAad() : null;
        return decryptGcmPayload(cipherBytes, sessionKey, ivBytes, aad);
    }

    @Override
    public ApiCipherResult encryptWithSessionKey(byte[] plaintext, byte[] sessionKey, ApiCryptoContext context) {
        if (!isValidAesKeyLength(sessionKey)) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "响应体 AES 会话密钥缺失或长度非法");
        }
        byte[] safePlaintext = plaintext != null ? plaintext : new byte[0];
        byte[] iv = CryptoUtil.generateGcmIv();
        byte[] aad = context != null ? context.getAad() : null;
        try {
            byte[] cipherBytes = CryptoUtil.encryptGcm(safePlaintext, sessionKey, iv, aad);
            return new ApiCipherResult(
                    null,
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(cipherBytes)
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "AES-GCM 响应体加密失败", e);
        }
    }

    private static boolean isValidAesKeyLength(byte[] sessionKey) {
        return sessionKey != null && (sessionKey.length == 16 || sessionKey.length == 24 || sessionKey.length == 32);
    }

    private static byte[] decodeAndValidateIv(String iv) {
        byte[] ivBytes = decodeBase64Strict(iv, "加密 IV Base64 格式非法");
        if (ivBytes.length != CryptoUtil.DEFAULT_GCM_IV_SIZE) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "非法的 AES-GCM IV 长度，期望 12 字节");
        }
        return ivBytes;
    }

    private static byte[] decodeAndValidateCipherBytes(String cipherText) {
        byte[] cipherBytes = decodeBase64Strict(cipherText, "密文载荷 Base64 格式非法");
        if (cipherBytes.length < GCM_TAG_BYTES) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "密文载荷长度非法，缺少完整的 GCM 认证标签");
        }
        return cipherBytes;
    }

    private static byte[] decryptGcmPayload(byte[] cipherBytes, byte[] sessionKey, byte[] iv, byte[] aad) {
        try {
            return CryptoUtil.decryptGcm(cipherBytes, sessionKey, iv, aad);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            Throwable rootCause = (e instanceof IllegalStateException && e.getCause() != null) ? e.getCause() : e;
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求体密文解密或认证校验失败", rootCause);
        }
    }

    private static byte[] rsaEncrypt(byte[] data, PublicKey publicKey) {
        try {
            return RsaUtil.encryptOaep(data, publicKey);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "RSA-OAEP 会话密钥封装失败", e);
        }
    }

    private static byte[] rsaDecrypt(byte[] wrappedKey, PrivateKey privateKey) {
        try {
            return RsaUtil.decryptOaep(wrappedKey, privateKey);
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
            return RsaUtil.parsePublicKey(keyPem);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "RSA 加密公钥配置格式非法", e);
        }
    }

    private static PrivateKey parsePrivateKey(String keyPem) {
        try {
            return RsaUtil.parsePrivateKey(keyPem);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "RSA 解密私钥配置格式非法", e);
        }
    }
}
