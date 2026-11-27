package io.github.atengk.boot.web.crypto.cipher;

import io.github.atengk.boot.core.util.Sm2Util;
import org.springframework.util.StringUtils;

import java.math.BigInteger;

/**
 * 纯 JDK 17 原生国密 SM2 非对称加解密内部门面
 * 彻底解除对签名子包的跨包耦合，曲线运算、密钥解析与 C1C3C2/DER 编解码统一委托至 {@link Sm2Util}，零 Bouncy Castle 依赖
 *
 * @author Ateng
 * @since 2026-11-27
 */
final class Sm2KeyCipher {

    private Sm2KeyCipher() {
    }

    static Sm2Util.Sm2Point parsePublicKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("SM2 公钥不能为空");
        }
        return Sm2Util.parsePublicKey(key);
    }

    static BigInteger parsePrivateKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("SM2 私钥不能为空");
        }
        return Sm2Util.parsePrivateKey(key);
    }

    static byte[] encrypt(byte[] plaintext, Sm2Util.Sm2Point pubKey) {
        return Sm2Util.encrypt(plaintext, pubKey);
    }

    static byte[] decrypt(byte[] ciphertext, BigInteger privKey) {
        return Sm2Util.decrypt(ciphertext, privKey);
    }
}
