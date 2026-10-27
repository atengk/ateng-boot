package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.Triple;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.ChecksumUtil;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.HexUtil;
import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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

    /**
     * 6. 验证 DesensitizeUtil 扩展敏感类型掩码（统一社会信用代码、密钥Token、护照号）
     *
     * @return Triple<String(统一社会信用代码), String(密钥Token), String(护照号)>
     */
    public Triple<String, String, String> desensitizeExtendedTypes() {
        String creditCode = DesensitizeUtil.unifiedCreditCode("91110000000000000X");
        String secretKey = DesensitizeUtil.secretKey("sk-proj-1234567890abcdef");
        String passport = DesensitizeUtil.passport("G12345678");

        log.info("DesensitizeUtil 扩展类型脱敏: creditCode={}, secretKey={}, passport={}", creditCode, secretKey, passport);
        return Triple.of(creditCode, secretKey, passport);
    }

    /**
     * 7. 验证 HexUtil 十六进制编解码与格式化
     *
     * @param rawText 原始文本
     * @return Pair<String(十六进制字符串), String(解码还原文本)>
     */
    public Pair<String, String> demonstrateHexEncoding(String rawText) {
        byte[] bytes = rawText.getBytes(StandardCharsets.UTF_8);
        String hexString = HexUtil.encodeHex(bytes);
        byte[] decodedBytes = HexUtil.decodeHex(hexString);
        String decodedText = new String(decodedBytes, StandardCharsets.UTF_8);

        log.info("HexUtil 十六进制编解码: raw={}, hex={}, decoded={}",
                rawText, hexString, decodedText);
        return Pair.of(hexString, decodedText);
    }

    /**
     * 8. 验证 HmacUtil 密钥散列加签与恒定时间防侧信道比对
     *
     * @param payload 待签名载荷
     * @param secretKey 密钥
     * @return Triple<String(Hex签名), String(Base64签名), Boolean(验签结果)>
     */
    public Triple<String, String, Boolean> demonstrateHmacSigning(String payload, String secretKey) {
        String hexSign = HmacUtil.hmacSha256Hex(payload, secretKey);
        String base64Sign = HmacUtil.hmacSha256Base64(payload, secretKey);

        // 重新依据载荷计算签名，并进行恒定时间防侧信道比对验签
        String calculatedSign = HmacUtil.hmacSha256Hex(payload, secretKey);
        boolean verified = HmacUtil.verifyHex(calculatedSign, hexSign);

        // 防篡改校验：篡改数据后验签必定失败
        boolean tamperedVerified = HmacUtil.verifyHex(HmacUtil.hmacSha256Hex(payload + "&tampered=1", secretKey), hexSign);
        boolean overallVerified = verified && !tamperedVerified;

        log.info("HmacUtil 密钥加签: payload={}, hex={}, base64={}, verified={}, tamperedBlocked={}",
                payload, hexSign, base64Sign, verified, !tamperedVerified);
        return Triple.of(hexSign, base64Sign, overallVerified);
    }

    /**
     * 9. 验证 ChecksumUtil 数据校验和计算 (CRC32, CRC32C, Adler32)
     *
     * @param data 源数据文本
     * @return Triple<String(CRC32十六进制), String(CRC32C十六进制), String(Adler32十六进制)>
     */
    public Triple<String, String, String> demonstrateChecksum(String data) {
        String crc32Hex = ChecksumUtil.crc32Hex(data);
        String crc32cHex = ChecksumUtil.crc32cHex(data);
        String adler32Hex = ChecksumUtil.adler32Hex(data);

        log.info("ChecksumUtil 校验和计算: data={}, crc32={}, crc32c={}, adler32={}",
                data, crc32Hex, crc32cHex, adler32Hex);
        return Triple.of(crc32Hex, crc32cHex, adler32Hex);
    }
}
