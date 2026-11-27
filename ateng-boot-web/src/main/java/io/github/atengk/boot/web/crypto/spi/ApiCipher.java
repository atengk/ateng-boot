package io.github.atengk.boot.web.crypto.spi;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.model.ApiCipherInput;
import io.github.atengk.boot.web.crypto.model.ApiCipherResult;
import io.github.atengk.boot.web.crypto.model.ApiCryptoContext;

/**
 * API 传输层混合加密引擎 SPI 契约
 * 定义随机对称会话密钥非对称封装与 AEAD 认证载荷加解密标准能力
 *
 * @author Ateng
 * @since 2026-11-27
 */
public interface ApiCipher {

    /**
     * 判断当前引擎是否支持指定混合加密算法套件
     *
     * @param algorithm 加密算法套件
     * @return true 表示支持
     */
    boolean supports(CryptoAlgorithm algorithm);

    /**
     * 加密明文载荷并返回封装密钥、随机 IV 与 Base64 密文
     *
     * @param plaintext 明文字节数组
     * @param context   加密上下文（含客户端公钥与可选 AAD）
     * @return 加密输出结果
     */
    ApiCipherResult encrypt(byte[] plaintext, ApiCryptoContext context);

    /**
     * 解封会话密钥并对密文载荷执行 AEAD 认证解密
     *
     * @param input   解密输入载体（封装密钥、IV、密文）
     * @param context 解密上下文（含服务端私钥与可选 AAD）
     * @return 还原后的明文字节数组
     */
    byte[] decrypt(ApiCipherInput input, ApiCryptoContext context);

    /**
     * 仅使用服务端私钥解封传输层一次性对称会话密钥
     * 调用方必须在使用完毕后对返回数组执行内存清零
     *
     * @param encryptedKey Base64 编码的非对称封装会话密钥
     * @param context      解密上下文（含服务端私钥）
     * @return 解封得到的原始对称会话密钥字节数组
     */
    default byte[] unwrapSessionKey(String encryptedKey, ApiCryptoContext context) {
        throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                "当前 ApiCipher 实现不支持请求会话密钥回包模式: " + getClass().getSimpleName());
    }

    /**
     * 使用已解封的对称会话密钥对密文载荷执行 AEAD 认证解密
     *
     * @param sessionKey 已解封的对称会话密钥字节数组
     * @param iv         Base64 编码的 12 字节随机 GCM IV
     * @param cipherText Base64 编码的对称认证密文
     * @param context    解密上下文（含可选 AAD）
     * @return 还原后的明文字节数组
     */
    default byte[] decryptWithSessionKey(byte[] sessionKey, String iv, String cipherText, ApiCryptoContext context) {
        throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                "当前 ApiCipher 实现不支持请求会话密钥回包模式: " + getClass().getSimpleName());
    }

    /**
     * 使用已有对称会话密钥配合全新随机 12 字节 IV 加密明文载荷
     * 返回载体中 {@link ApiCipherResult#encryptedKey()} 应为 {@code null}
     *
     * @param plaintext  明文字节数组
     * @param sessionKey 对称会话密钥字节数组
     * @param context    加密上下文（含可选 AAD）
     * @return 加密输出结果（不含封装密钥）
     */
    default ApiCipherResult encryptWithSessionKey(byte[] plaintext, byte[] sessionKey, ApiCryptoContext context) {
        throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                "当前 ApiCipher 实现不支持请求会话密钥回包模式: " + getClass().getSimpleName());
    }
}
