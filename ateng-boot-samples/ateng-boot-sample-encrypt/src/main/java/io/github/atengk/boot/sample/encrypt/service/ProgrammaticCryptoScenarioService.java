package io.github.atengk.boot.sample.encrypt.service;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.template.EncryptTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 编程式加解密与对称门面场景服务
 * 演示使用 Spring 受管 EncryptTemplate 门面进行多算法编程式加解密、Token 组装、容错逃生舱以及 ConfigEncryptor 底层静态对称门面
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Service
@RequiredArgsConstructor
public class ProgrammaticCryptoScenarioService {

    private final EncryptTemplate encryptTemplate;

    /**
     * 使用环境默认算法与全局主密钥加密明文并包装为 ENC(...) 标记
     *
     * @param plaintext 待加密明文
     * @return 格式化后的密文标记，如 ENC(...)
     */
    public String encryptDefault(String plaintext) {
        return encryptTemplate.encrypt(plaintext);
    }

    /**
     * 自动解密密文字符串（支持标准 ENC(...)、内嵌复合串及纯 Base64 裸密文）
     *
     * @param ciphertext 密文字符串
     * @return 解密还原后的真实明文
     */
    public String decrypt(String ciphertext) {
        return encryptTemplate.decrypt(ciphertext);
    }

    /**
     * 指定密钥别名与加密算法加密明文并包装为 ENC[keyId](algo@...)
     *
     * @param plaintext 明文字符串
     * @param keyId 密钥别名标识
     * @param algorithm 加密算法
     * @return 格式化后的密文标记
     */
    public String encryptWithKeyAndAlgorithm(String plaintext, String keyId, EncryptAlgorithm algorithm) {
        return encryptTemplate.encrypt(plaintext, keyId, algorithm);
    }

    /**
     * 加密明文返回裸 Base64 密文（不带 ENC 前后缀）
     *
     * @param plaintext 明文字符串
     * @return 纯 Base64 密文字符串
     */
    public String encryptRaw(String plaintext) {
        return encryptTemplate.encryptRaw(plaintext);
    }

    /**
     * 宽容容错解密：解密失败或非有效密文时安全保底返回原始输入
     *
     * @param ciphertext 待解密文本
     * @return 解密成功返回真实明文，失败返回原始输入
     */
    public String decryptOrOriginal(String ciphertext) {
        return encryptTemplate.decryptOrOriginal(ciphertext);
    }

    /**
     * 宽容容错解密：解密失败时返回指定的默认值
     *
     * @param ciphertext 待解密文本
     * @param defaultValue 回退默认值
     * @return 解密成功返回真实明文，失败返回 defaultValue
     */
    public String decryptOrDefault(String ciphertext, String defaultValue) {
        return encryptTemplate.decryptOrDefault(ciphertext, defaultValue);
    }

    /**
     * 演示调用底层 ConfigEncryptor 纯原生静态门面执行对称加密与解密
     *
     * @param plaintext 明文字符串
     * @param password 加解密主密钥
     * @param algorithm 加解密算法
     * @return 解密还原后的明文字符串
     */
    public String executeDirectStaticCrypto(String plaintext, String password, EncryptAlgorithm algorithm) {
        String cipher = ConfigEncryptor.encrypt(plaintext, password, algorithm);
        return ConfigEncryptor.decrypt(cipher, password, algorithm);
    }
}
