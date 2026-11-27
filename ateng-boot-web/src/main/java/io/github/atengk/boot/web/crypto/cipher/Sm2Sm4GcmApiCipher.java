package io.github.atengk.boot.web.crypto.cipher;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.model.ApiCipherInput;
import io.github.atengk.boot.web.crypto.model.ApiCipherResult;
import io.github.atengk.boot.web.crypto.model.ApiCryptoContext;
import io.github.atengk.boot.web.crypto.spi.ApiCipher;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;

/**
 * 基于纯 JDK 17 原生国密 SM2 与 SM4-GCM 的信创混合加解密引擎
 * 零 Bouncy Castle 外部依赖，底层 SM4-GCM 复用 Core {@link CryptoUtil#encryptSm4Gcm} / {@link CryptoUtil#decryptSm4Gcm}
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class Sm2Sm4GcmApiCipher implements ApiCipher {

    private static final int GCM_TAG_BYTES = CryptoUtil.GCM_TAG_LENGTH / 8;

    @Override
    public boolean supports(CryptoAlgorithm algorithm) {
        return algorithm == CryptoAlgorithm.SM2_SM4_GCM;
    }

    @Override
    public ApiCipherResult encrypt(byte[] plaintext, ApiCryptoContext context) {
        if (context == null || !StringUtils.hasText(context.getKey())) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未配置响应体 SM2 加密公钥");
        }
        Sm2Signer.ECPoint publicKey;
        try {
            publicKey = Sm2KeyCipher.parsePublicKey(context.getKey());
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "SM2 加密公钥配置格式非法: " + e.getMessage(), e);
        }

        byte[] safePlaintext = plaintext != null ? plaintext : new byte[0];
        byte[] sm4Key = CryptoUtil.generateSm4Key();
        byte[] iv = CryptoUtil.generateGcmIv();
        try {
            byte[] cipherBytes = CryptoUtil.encryptSm4Gcm(safePlaintext, sm4Key, iv, context.getAad());
            byte[] wrappedKey = Sm2KeyCipher.encrypt(sm4Key, publicKey);
            return new ApiCipherResult(
                    Base64.getEncoder().encodeToString(wrappedKey),
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(cipherBytes)
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "SM2+SM4-GCM 响应体加密失败: " + e.getMessage(), e);
        } finally {
            Arrays.fill(sm4Key, (byte) 0);
        }
    }

    @Override
    public byte[] decrypt(ApiCipherInput input, ApiCryptoContext context) {
        if (context == null || !StringUtils.hasText(context.getKey())) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未配置请求体 SM2 解密私钥");
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
        byte[] sm4Key = unwrapSessionKey(input.encryptedKey(), context);
        try {
            return decryptSm4GcmPayload(cipherBytes, sm4Key, iv, context.getAad());
        } finally {
            Arrays.fill(sm4Key, (byte) 0);
        }
    }

    @Override
    public byte[] unwrapSessionKey(String encryptedKey, ApiCryptoContext context) {
        if (context == null || !StringUtils.hasText(context.getKey())) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未配置请求体 SM2 解密私钥");
        }
        if (!StringUtils.hasText(encryptedKey)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "缺少 SM2 封装会话密钥请求头");
        }

        BigInteger privateKey;
        try {
            privateKey = Sm2KeyCipher.parsePrivateKey(context.getKey());
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "SM2 解密私钥配置格式非法: " + e.getMessage(), e);
        }

        byte[] wrappedKey = decodeBase64Strict(encryptedKey, "SM2 封装会话密钥 Base64 格式非法");
        byte[] sm4Key;
        try {
            sm4Key = Sm2KeyCipher.decrypt(wrappedKey, privateKey);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求 SM2 会话密钥解封失败", e);
        }

        if (!isValidSm4KeyLength(sm4Key)) {
            Arrays.fill(sm4Key, (byte) 0);
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "解封得到的 SM4 会话密钥长度非法");
        }
        return sm4Key;
    }

    @Override
    public byte[] decryptWithSessionKey(byte[] sessionKey, String iv, String cipherText, ApiCryptoContext context) {
        if (!isValidSm4KeyLength(sessionKey)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "SM4 会话密钥缺失或长度非法");
        }
        if (!StringUtils.hasText(iv) || !StringUtils.hasText(cipherText)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "缺少加密传输必要元数据或密文载荷");
        }
        byte[] ivBytes = decodeAndValidateIv(iv);
        byte[] cipherBytes = decodeAndValidateCipherBytes(cipherText);
        byte[] aad = context != null ? context.getAad() : null;
        return decryptSm4GcmPayload(cipherBytes, sessionKey, ivBytes, aad);
    }

    @Override
    public ApiCipherResult encryptWithSessionKey(byte[] plaintext, byte[] sessionKey, ApiCryptoContext context) {
        if (!isValidSm4KeyLength(sessionKey)) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "响应体 SM4 会话密钥缺失或长度非法");
        }
        byte[] safePlaintext = plaintext != null ? plaintext : new byte[0];
        byte[] iv = CryptoUtil.generateGcmIv();
        byte[] aad = context != null ? context.getAad() : null;
        try {
            byte[] cipherBytes = CryptoUtil.encryptSm4Gcm(safePlaintext, sessionKey, iv, aad);
            return new ApiCipherResult(
                    null,
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(cipherBytes)
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "SM4-GCM 响应体加密失败: " + e.getMessage(), e);
        }
    }

    private static boolean isValidSm4KeyLength(byte[] sessionKey) {
        return sessionKey != null && sessionKey.length == CryptoUtil.SM4_KEY_SIZE;
    }

    private static byte[] decodeAndValidateIv(String iv) {
        byte[] ivBytes = decodeBase64Strict(iv, "SM4-GCM IV Base64 格式非法");
        if (ivBytes.length != CryptoUtil.DEFAULT_GCM_IV_SIZE) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "非法的 SM4-GCM IV 长度，期望 12 字节");
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

    private static byte[] decryptSm4GcmPayload(byte[] cipherBytes, byte[] sm4Key, byte[] iv, byte[] aad) {
        try {
            return CryptoUtil.decryptSm4Gcm(cipherBytes, sm4Key, iv, aad);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            Throwable rootCause = (e instanceof IllegalStateException && e.getCause() != null) ? e.getCause() : e;
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求体 SM4-GCM 密文解密或认证校验失败", rootCause);
        }
    }

    private static byte[] decodeBase64Strict(String raw, String errorMessage) {
        try {
            return Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, errorMessage, e);
        }
    }
}
