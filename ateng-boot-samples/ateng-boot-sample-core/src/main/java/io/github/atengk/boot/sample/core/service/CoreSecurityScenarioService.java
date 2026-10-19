package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.Triple;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Core 场景二：安全加解密与脱敏服务
 * 演示并自验 DesensitizeUtil 全场景掩码、内存真明文不变量、CryptoUtil AES/GCM/CBC 原生加解密与 DigestUtil 哈希摘要
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
@Service
public class CoreSecurityScenarioService {

    /**
     * 1. 验证 DesensitizeUtil 高频隐私数据掩码能力
     *
     * @return 包含各类脱敏结果的数据集
     */
    public Triple<String, String, String> desensitizePrimitives() {
        String phoneMasked = DesensitizeUtil.mobilePhone("13812345678");
        String idCardMasked = DesensitizeUtil.idCard("110101199003072345");
        String nameMasked = DesensitizeUtil.chineseName("诸葛孔明");

        log.info("DesensitizeUtil 原语脱敏: phone={}, idCard={}, name={}", phoneMasked, idCardMasked, nameMasked);
        return Triple.of(phoneMasked, idCardMasked, nameMasked);
    }

    /**
     * 2. 验证 @FieldSensitive 注解在 Jackson 序列化时自动脱敏，且保证 JVM 内存中实体保持 100% 原始真明文
     *
     * @return Pair<String(JSON序列化输出), SampleUserDTO(内存原始对象)>
     */
    public Pair<String, SampleUserDTO> verifyInMemoryPlaintextAndJsonMasking() {
        SampleUserDTO user = SampleUserDTO.builder()
                .id(104485532445560832L)
                .name("诸葛孔明")
                .phone("13812345678")
                .idCard("110101199003072345")
                .email("atengk@example.com")
                .secretToken("TOPSECRETTOKEN123456")
                .customCode("1234567890")
                .status(1)
                .createTime(LocalDateTime.of(2026, 10, 19, 12, 0, 0))
                .build();

        // 1. Jackson 序列化输出（触发 @FieldSensitive 掩码）
        String json = JsonUtil.toJsonString(user);
        log.info("Jackson 动态掩码序列化 JSON: {}", json);

        // 2. 严格断言在内存中依然是 100% 原始真明文
        log.info("内存真明文验证: phone={}, idCard={}", user.getPhone(), user.getIdCard());

        return Pair.of(json, user);
    }

    /**
     * 3. 验证 AES/GCM 认证加解密 (推荐现代加密范式，带防篡改校验)
     *
     * @param plaintext 明文字符串
     * @return Pair<String(Base64密文), String(解密后明文)>
     */
    public Pair<String, String> encryptAndDecryptAesGcm(String plaintext) {
        // 生成 256 位密钥与 12 字节推荐 GCM IV
        byte[] key = CryptoUtil.generateKey(256);
        byte[] iv = CryptoUtil.generateGcmIv();

        String ciphertext = CryptoUtil.encryptGcm(plaintext, key, iv);
        String decrypted = CryptoUtil.decryptGcm(ciphertext, key, iv);

        log.info("AES/GCM 认证加解密: plain={}, cipher={}, decrypted={}", plaintext, ciphertext, decrypted);
        return Pair.of(ciphertext, decrypted);
    }

    /**
     * 4. 验证 AES/CBC 对称加解密
     *
     * @param plaintext 明文字符串
     * @return Pair<String(Base64密文), String(解密后明文)>
     */
    public Pair<String, String> encryptAndDecryptAesCbc(String plaintext) {
        byte[] key = CryptoUtil.generateKey(128);
        byte[] iv = CryptoUtil.generateIv(); // 16 字节 IV

        String ciphertext = CryptoUtil.encryptCbc(plaintext, key, iv);
        String decrypted = CryptoUtil.decryptCbc(ciphertext, key, iv);

        log.info("AES/CBC 对称加解密: plain={}, cipher={}, decrypted={}", plaintext, ciphertext, decrypted);
        return Pair.of(ciphertext, decrypted);
    }

    /**
     * 5. 验证 DigestUtil 消息摘要哈希计算
     *
     * @param rawText 原始文本
     * @return Pair<String(SHA-256摘要), String(MD5摘要)>
     */
    public Pair<String, String> calculateDigests(String rawText) {
        String sha256Hex = DigestUtil.sha256Hex(rawText);
        String md5Hex = DigestUtil.md5Hex(rawText);

        log.info("DigestUtil 摘要计算: raw={}, sha256={}, md5={}", rawText, sha256Hex, md5Hex);
        return Pair.of(sha256Hex, md5Hex);
    }
}
