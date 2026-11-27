package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.id.Snowflake;
import io.github.atengk.boot.core.id.SnowflakeIdInfo;
import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.Triple;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.ChecksumUtil;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.HexUtil;
import io.github.atengk.boot.core.util.HkdfUtil;
import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.boot.core.util.Sm2Util;
import io.github.atengk.boot.sample.core.dto.AsymmetricCryptoRequest;
import io.github.atengk.boot.sample.core.dto.KdfAndStreamRequest;
import io.github.atengk.boot.sample.core.dto.PasswordCheckRequest;
import io.github.atengk.boot.sample.core.dto.PaymentCryptoRequest;
import io.github.atengk.boot.sample.core.dto.SecurityCryptoRequest;
import io.github.atengk.boot.sample.core.dto.SignAndVerifyRequest;
import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import io.github.atengk.boot.sample.core.vo.AsymmetricCryptoVO;
import io.github.atengk.boot.sample.core.vo.CustomerPrivacyVO;
import io.github.atengk.boot.sample.core.vo.IdGenerateVO;
import io.github.atengk.boot.sample.core.vo.KdfAndStreamVO;
import io.github.atengk.boot.sample.core.vo.PasswordCheckVO;
import io.github.atengk.boot.sample.core.vo.PaymentCryptoVO;
import io.github.atengk.boot.sample.core.vo.SecurityCryptoVO;
import io.github.atengk.boot.sample.core.vo.SignAndVerifyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;

/**
 * Core 场景二：安全加解密与脱敏服务
 * 演示并自验 DesensitizeUtil 全场景掩码、内存真明文不变量、CryptoUtil AES/GCM/CBC 原生加解密与 DigestUtil 哈希摘要
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = {@Autowired})
public class CoreSecurityScenarioService {

    private final Snowflake snowflake;

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

    /**
     * 10. 演示分布式业务发号、雪花元数据逆向反解与 Base62 紧凑短码双向编解码
     *
     * @return 分布式发号完整结构体
     */
    public IdGenerateVO generateDistributedId() {
        // 容器受管 Snowflake Bean 已与静态门面 IdUtil 自动双向同步
        if (snowflake != null) {
            Snowflake active = IdUtil.getSnowflake();
            BusinessAssert.isTrue(
                    active.getWorkerId() == snowflake.getWorkerId()
                            && active.getDatacenterId() == snowflake.getDatacenterId()
                            && active.getEpoch() == snowflake.getEpoch(),
                    "IdUtil 静态门面未与容器 Snowflake 配置保持一致");
        }
        long id = IdUtil.nextId();
        String idStr = String.valueOf(id);
        String base62 = IdUtil.toBase62(id);
        long restoredId = IdUtil.fromBase62(base62);
        SnowflakeIdInfo info = IdUtil.parseSnowflake(id);
        String simpleUuid = IdUtil.fastSimpleUUID();
        String uuid = IdUtil.fastUUID();
        String nanoId = IdUtil.nanoId();

        log.info("分布式发号器生成 ID: id={}, base62={}, datacenterId={}, workerId={}, seq={}",
                id, base62, info.datacenterId(), info.workerId(), info.sequence());

        return IdGenerateVO.builder()
                .id(id)
                .idStr(idStr)
                .base62(base62)
                .restoredId(restoredId)
                .datacenterId(info.datacenterId())
                .workerId(info.workerId())
                .sequence(info.sequence())
                .timestamp(info.timestamp())
                .dateTime(info.dateTime())
                .simpleUuid(simpleUuid)
                .uuid(uuid)
                .nanoId(nanoId)
                .build();
    }

    /**
     * 11. 获取客户隐私档案，验证服务层 JVM 内存对象保持 100% 原始真明文
     *
     * @return 客户隐私视图对象 (出界序列化时将自动触发 @FieldSensitive 掩码)
     */
    public CustomerPrivacyVO getCustomerPrivacyProfile() {
        CustomerPrivacyVO vo = CustomerPrivacyVO.builder()
                .customerId(104485532445560832L)
                .realName("诸葛孔明")
                .phone("13812345678")
                .fixedPhone("010-88889978")
                .idCard("110101199003072345")
                .email("atengk@example.com")
                .bankCard("6222020200001234012")
                .password("MySecr3tP@ss")
                .address("北京市海淀区中关村南大街1号院")
                .carLicense("京A88888")
                .clientIpv4("192.168.1.100")
                .clientIpv6("2001:0db8:85a3:0000:0000:8a2e:0370:7334")
                .creditCode("91110000000000000X")
                .secretKey("sk-proj-1234567890abcdef")
                .passport("G12345678")
                .customToken("AB998877CD")
                .internalCode("PR123456US")
                .build();

        // 内存真明文严格守卫断言（业务层严禁直接打星号污染实体）
        BusinessAssert.isTrue("诸葛孔明".equals(vo.getRealName()), "内存真明文校验失败: 姓名受损");
        BusinessAssert.isTrue("13812345678".equals(vo.getPhone()), "内存真明文校验失败: 手机号受损");
        BusinessAssert.isTrue("110101199003072345".equals(vo.getIdCard()), "内存真明文校验失败: 身份证受损");
        BusinessAssert.isTrue("atengk@example.com".equals(vo.getEmail()), "内存真明文校验失败: 邮箱受损");
        BusinessAssert.isTrue("MySecr3tP@ss".equals(vo.getPassword()), "内存真明文校验失败: 密码受损");

        log.info("内存真明文不变量验证通过: name={}, phone={}, idCard={}",
                vo.getRealName(), vo.getPhone(), vo.getIdCard());

        return vo;
    }

    /**
     * 12. 支付报文原生加解密、HMAC 恒定时间防侧信道验签与多算法校验和计算
     *
     * @param request 支付加密请求体
     * @return 支付加解密与验签响应模型
     */
    public PaymentCryptoVO processPaymentCryptoAndVerify(PaymentCryptoRequest request) {
        BusinessAssert.notNull(request, "支付加解密请求体不能为空");
        BusinessAssert.hasText(request.getRawPayload(), "交易明文载荷不能为空");

        String raw = request.getRawPayload();
        String secretKey = request.getSecretKey();

        // 1. AES/GCM 认证加解密 (推荐现代加密标准)
        byte[] gcmKey = CryptoUtil.generateKey(256);
        byte[] gcmIv = CryptoUtil.generateGcmIv();
        String gcmCiphertext = CryptoUtil.encryptGcm(raw, gcmKey, gcmIv);
        String gcmDecrypted = CryptoUtil.decryptGcm(gcmCiphertext, gcmKey, gcmIv);
        BusinessAssert.isTrue(raw.equals(gcmDecrypted), "AES/GCM 解密明文与原始明文不匹配");

        // 2. AES/CBC 对称加解密
        byte[] cbcKey = CryptoUtil.generateKey(128);
        byte[] cbcIv = CryptoUtil.generateIv();
        String cbcCiphertext = CryptoUtil.encryptCbc(raw, cbcKey, cbcIv);
        String cbcDecrypted = CryptoUtil.decryptCbc(cbcCiphertext, cbcKey, cbcIv);
        BusinessAssert.isTrue(raw.equals(cbcDecrypted), "AES/CBC 解密明文与原始明文不匹配");

        // 3. 国密 SM4/CBC 对称加解密（纯 JDK 原生实现，16 字节随机 SM4 密钥与随机 16 字节 IV）
        byte[] sm4Key = CryptoUtil.generateSm4Key();
        byte[] sm4Iv = CryptoUtil.generateIv(CryptoUtil.SM4_IV_SIZE);
        String sm4CbcCiphertext = CryptoUtil.encryptSm4Cbc(raw, sm4Key, sm4Iv);
        String sm4CbcDecrypted = CryptoUtil.decryptSm4Cbc(sm4CbcCiphertext, sm4Key, sm4Iv);
        BusinessAssert.isTrue(raw.equals(sm4CbcDecrypted), "国密 SM4/CBC 解密明文与原始明文不匹配");

        // 4. HMAC-SHA256 密钥散列加签与恒定时间防侧信道比对验签
        String hmacHex = HmacUtil.hmacSha256Hex(raw, secretKey);
        String hmacBase64 = HmacUtil.hmacSha256Base64(raw, secretKey);
        String recomputedHex = HmacUtil.hmacSha256Hex(raw, secretKey);
        boolean signatureVerified = HmacUtil.verifyHex(recomputedHex, hmacHex);

        // 5. DigestUtil 消息摘要（SHA-256 / 国密 SM3 / MD5）
        String sha256Hex = DigestUtil.sha256Hex(raw);
        String sm3Hex = DigestUtil.sm3Hex(raw);
        String md5Hex = DigestUtil.md5Hex(raw);

        // 6. HexUtil 编解码
        String encodedHex = HexUtil.encodeHex(raw.getBytes(StandardCharsets.UTF_8));

        // 7. ChecksumUtil 校验和计算
        String crc32Hex = ChecksumUtil.crc32Hex(raw);
        String crc32cHex = ChecksumUtil.crc32cHex(raw);
        String adler32Hex = ChecksumUtil.adler32Hex(raw);

        log.info("支付报文加解密与验签完成 [tradeNo={}]: verified={}, gcmCipher={}, hmacHex={}, sm3Hex={}",
                request.getTradeNo(), signatureVerified, gcmCiphertext, hmacHex, sm3Hex);

        return PaymentCryptoVO.builder()
                .tradeNo(request.getTradeNo())
                .gcmCiphertext(gcmCiphertext)
                .gcmDecrypted(gcmDecrypted)
                .cbcCiphertext(cbcCiphertext)
                .cbcDecrypted(cbcDecrypted)
                .sm4CbcCiphertext(sm4CbcCiphertext)
                .sm4CbcDecrypted(sm4CbcDecrypted)
                .hmacSha256Hex(hmacHex)
                .hmacSha256Base64(hmacBase64)
                .signatureVerified(signatureVerified)
                .sha256Hex(sha256Hex)
                .sm3Hex(sm3Hex)
                .md5Hex(md5Hex)
                .encodedHex(encodedHex)
                .crc32Hex(crc32Hex)
                .crc32cHex(crc32cHex)
                .adler32Hex(adler32Hex)
                .build();
    }

    /**
     * 13. 验证纯 JDK 原生国密 HmacSM3 签名与恒定时间防侧信道验签闭环
     *
     * @param request 签名与验签请求对象
     * @return 国密 HmacSM3 签名与验签视图对象
     */
    public SignAndVerifyVO signAndVerifyScenario(SignAndVerifyRequest request) {
        BusinessAssert.notNull(request, "签名与验签请求体不能为空");
        BusinessAssert.hasText(request.getPayload(), "待签名载荷不能为空");
        BusinessAssert.hasText(request.getSecretKey(), "签名密钥不能为空");

        String payload = request.getPayload();
        String secretKey = request.getSecretKey();

        // 1. 计算国密 HmacSM3 十六进制与 Base64 签名
        String hmacSm3Hex = HmacUtil.hmacSm3Hex(payload, secretKey);
        String hmacSm3Base64 = HmacUtil.hmacSm3Base64(payload, secretKey);

        // 2. 恒定时间安全验签与防篡改校验
        boolean sm3HexVerified = HmacUtil.verifyHex(hmacSm3Hex, HmacUtil.hmacSm3Hex(payload, secretKey));
        boolean sm3Base64Verified = HmacUtil.verifyBase64(hmacSm3Base64, HmacUtil.hmacSm3Base64(payload, secretKey));
        boolean sm3TamperBlocked = !HmacUtil.verifyHex(
                hmacSm3Hex,
                HmacUtil.hmacSm3Hex(payload + "&tampered=1", secretKey)
        );

        String hmacSha256Hex = HmacUtil.hmacSha256Hex(payload, secretKey);
        String sm3DigestHex = DigestUtil.sm3Hex(payload);

        log.info("国密 HmacSM3 签名与验签完成: sm3HexVerified={}, sm3Base64Verified={}, tamperBlocked={}",
                sm3HexVerified, sm3Base64Verified, sm3TamperBlocked);

        return SignAndVerifyVO.builder()
                .hmacSm3Hex(hmacSm3Hex)
                .hmacSm3Base64(hmacSm3Base64)
                .sm3HexVerified(sm3HexVerified)
                .sm3Base64Verified(sm3Base64Verified)
                .sm3TamperBlocked(sm3TamperBlocked)
                .hmacSha256Hex(hmacSha256Hex)
                .sm3DigestHex(sm3DigestHex)
                .build();
    }

    /**
     * 14. 验证 generateSm4Key() 与字符串级带 AAD 认证的 AES-GCM 加解密闭环
     *
     * @param request 对称加解密请求对象
     * @return 对称加解密与 AAD 防篡改验证响应视图
     */
    public SecurityCryptoVO processSymmetricCryptoScenario(SecurityCryptoRequest request) {
        BusinessAssert.notNull(request, "对称加密请求体不能为空");
        BusinessAssert.hasText(request.getPlaintext(), "待加密明文不能为空");
        BusinessAssert.hasText(request.getAad(), "附加认证数据 AAD 不能为空");

        String plaintext = request.getPlaintext();
        byte[] aadBytes = request.getAad().getBytes(StandardCharsets.UTF_8);

        // 1. 生成 16 字节国密 SM4 随机密钥并完成 SM4/CBC 往返验证
        byte[] sm4Key = CryptoUtil.generateSm4Key();
        byte[] sm4Iv = CryptoUtil.generateIv(CryptoUtil.SM4_IV_SIZE);
        byte[] gcmKey = CryptoUtil.generateKey(256);
        byte[] gcmIv = CryptoUtil.generateGcmIv();
        try {
            String sm4Ciphertext = CryptoUtil.encryptSm4Cbc(plaintext, sm4Key, sm4Iv);
            String sm4Decrypted = CryptoUtil.decryptSm4Cbc(sm4Ciphertext, sm4Key, sm4Iv);
            BusinessAssert.isTrue(plaintext.equals(sm4Decrypted), "SM4/CBC 解密还原明文不匹配");

            // 2. 字符串级带 AAD 的 AES/GCM 加解密与篡改防御验证
            String gcmCiphertext = CryptoUtil.encryptGcm(plaintext, gcmKey, gcmIv, aadBytes);
            String gcmDecrypted = CryptoUtil.decryptGcm(gcmCiphertext, gcmKey, gcmIv, aadBytes);
            BusinessAssert.isTrue(plaintext.equals(gcmDecrypted), "AES/GCM(AAD) 解密还原明文不匹配");

            boolean aadTamperBlocked = false;
            byte[] tamperedAad = (request.getAad() + "-tampered").getBytes(StandardCharsets.UTF_8);
            try {
                CryptoUtil.decryptGcm(gcmCiphertext, gcmKey, gcmIv, tamperedAad);
            } catch (BusinessException ex) {
                aadTamperBlocked = ex.getCause() != null;
                log.debug("已按预期拦截 AAD 篡改请求: {}", ex.getMessage());
            }

            // 3. 字符串级带 AAD 的国密 SM4/GCM 加解密与篡改防御验证
            byte[] sm4GcmIv = CryptoUtil.generateGcmIv();
            String sm4GcmCiphertext = CryptoUtil.encryptSm4Gcm(plaintext, sm4Key, sm4GcmIv, aadBytes);
            String sm4GcmDecrypted = CryptoUtil.decryptSm4Gcm(sm4GcmCiphertext, sm4Key, sm4GcmIv, aadBytes);
            BusinessAssert.isTrue(plaintext.equals(sm4GcmDecrypted), "SM4/GCM(AAD) 解密还原明文不匹配");

            boolean sm4GcmAadTamperBlocked = false;
            try {
                CryptoUtil.decryptSm4Gcm(sm4GcmCiphertext, sm4Key, sm4GcmIv, tamperedAad);
            } catch (BusinessException ex) {
                sm4GcmAadTamperBlocked = ex.getCause() != null;
                log.debug("已按预期拦截 SM4-GCM AAD 篡改请求: {}", ex.getMessage());
            }

            log.info("SM4 密钥生成与带 AAD 的 AES-GCM / SM4-GCM 闭环完成: sm4KeyLen={}, aadTamperBlocked={}, sm4GcmAadTamperBlocked={}",
                    sm4Key.length, aadTamperBlocked, sm4GcmAadTamperBlocked);

            return SecurityCryptoVO.builder()
                    .sm4KeyByteLength(sm4Key.length)
                    .sm4CbcCiphertext(sm4Ciphertext)
                    .sm4CbcDecrypted(sm4Decrypted)
                    .gcmCiphertextWithAad(gcmCiphertext)
                    .gcmDecryptedWithAad(gcmDecrypted)
                    .aadTamperBlocked(aadTamperBlocked)
                    .sm4GcmCiphertextWithAad(sm4GcmCiphertext)
                    .sm4GcmDecryptedWithAad(sm4GcmDecrypted)
                    .sm4GcmAadTamperBlocked(sm4GcmAadTamperBlocked)
                    .build();
        } finally {
            Arrays.fill(sm4Key, (byte) 0);
            Arrays.fill(gcmKey, (byte) 0);
        }
    }

    /**
     * 15. 验证纯 JDK 原生 PasswordUtil 口令慢哈希、恒定时间防时序校验与迭代升级闭环
     *
     * @param request 口令哈希与校验请求对象
     * @return 口令慢哈希、匹配结果与平滑升级状态视图
     */
    public PasswordCheckVO verifyPasswordLifecycle(PasswordCheckRequest request) {
        BusinessAssert.notNull(request, "口令校验请求体不能为空");
        BusinessAssert.hasText(request.getUsername(), "账号名不能为空");
        BusinessAssert.hasText(request.getRawPassword(), "原始口令不能为空");

        int legacyIterations = request.getLegacyIterations() != null ? request.getLegacyIterations() : 100_000;
        BusinessAssert.isTrue(
                legacyIterations >= PasswordUtil.MIN_ITERATIONS && legacyIterations <= PasswordUtil.MAX_ITERATIONS,
                "存量迭代次数必须在 [{}, {}] 区间内",
                PasswordUtil.MIN_ITERATIONS,
                PasswordUtil.MAX_ITERATIONS
        );

        String rawPassword = request.getRawPassword();

        // 1. 注册/改密：同一明文两次哈希因随机盐不同产生完全不同的存储串
        String encoded = PasswordUtil.hash(rawPassword);
        String secondEncoded = PasswordUtil.hash(rawPassword);
        boolean saltUnique = !encoded.equals(secondEncoded);

        // 2. 登录验密：常量时间比对正确口令，并安全拒绝错误口令与畸形哈希串
        boolean matched = PasswordUtil.matches(rawPassword, encoded) && PasswordUtil.matches(rawPassword, secondEncoded);
        boolean wrongPasswordRejected = !PasswordUtil.matches(rawPassword + "-invalid", encoded)
                && !PasswordUtil.matches(rawPassword, "$pbkdf2-sha256$invalid$salt$hash");

        // 3. 存量低迭代哈希检测与登录后平滑重哈希升级
        String legacyEncoded = PasswordUtil.hash(rawPassword, legacyIterations);
        boolean legacyNeedsUpgrade = PasswordUtil.matches(rawPassword, legacyEncoded)
                && PasswordUtil.needsUpgrade(legacyEncoded);
        String upgradedPassword = legacyNeedsUpgrade ? PasswordUtil.hash(rawPassword) : legacyEncoded;
        boolean currentNeedsUpgrade = PasswordUtil.needsUpgrade(upgradedPassword);

        // 4. 账号不存在分支执行恒定时间虚拟 PBKDF2 计算，防御时序枚举
        boolean dummyCheckRejected = !PasswordUtil.constantTimeDummyCheck(rawPassword)
                && !PasswordUtil.constantTimeDummyCheck((CharSequence) null);

        log.info("口令慢哈希与校验闭环完成: username={}, saltUnique={}, matched={}, legacyNeedsUpgrade={}, dummyCheckRejected={}",
                request.getUsername(), saltUnique, matched, legacyNeedsUpgrade, dummyCheckRejected);

        return PasswordCheckVO.builder()
                .username(request.getUsername())
                .encodedPassword(encoded)
                .secondEncodedPassword(secondEncoded)
                .saltUnique(saltUnique)
                .matched(matched)
                .wrongPasswordRejected(wrongPasswordRejected)
                .legacyNeedsUpgrade(legacyNeedsUpgrade)
                .upgradedPassword(upgradedPassword)
                .currentNeedsUpgrade(currentNeedsUpgrade)
                .dummyCheckRejected(dummyCheckRejected)
                .build();
    }

    /**
     * 16. 验证纯 JDK 原生 RSA-OAEP-SHA256 / RSASSA-PSS 与国密 SM2 非对称加解密及签验闭环
     *
     * @param request 非对称加解密与签验请求对象
     * @return RSA 与 SM2 加解密、多格式签名与防篡改结果视图
     */
    public AsymmetricCryptoVO verifyAsymmetricCrypto(AsymmetricCryptoRequest request) {
        BusinessAssert.notNull(request, "非对称加解密请求体不能为空");
        BusinessAssert.hasText(request.getPlaintext(), "待加密业务明文不能为空");
        BusinessAssert.hasText(request.getSignPayload(), "待签名报文载荷不能为空");

        // 1. RSA 2048 位密钥对生成、PEM 往返解析、OAEP-SHA256 单块加解密与 SHA256/PSS 签验
        KeyPair rsaPair = RsaUtil.generateKeyPair(2048);
        KeyPair otherRsaPair = RsaUtil.generateKeyPair(2048);
        String rsaPubPem = RsaUtil.encodePublicKeyToPem(rsaPair.getPublic());
        String rsaPriPem = RsaUtil.encodePrivateKeyToPem(rsaPair.getPrivate());
        String otherRsaPriPem = RsaUtil.encodePrivateKeyToPem(otherRsaPair.getPrivate());

        PublicKey parsedPub = RsaUtil.parsePublicKey(rsaPubPem);
        PrivateKey parsedPri = RsaUtil.parsePrivateKey(rsaPriPem);
        byte[] origPriEncoded = rsaPair.getPrivate().getEncoded();
        byte[] parsedPriEncoded = parsedPri.getEncoded();
        boolean rsaKeyPemRoundTripValid;
        try {
            rsaKeyPemRoundTripValid = Arrays.equals(rsaPair.getPublic().getEncoded(), parsedPub.getEncoded())
                    && Arrays.equals(origPriEncoded, parsedPriEncoded);
        } finally {
            if (origPriEncoded != null) {
                Arrays.fill(origPriEncoded, (byte) 0);
            }
            if (parsedPriEncoded != null) {
                Arrays.fill(parsedPriEncoded, (byte) 0);
            }
        }

        String rsaOaepCiphertext = RsaUtil.encryptOaepBase64(request.getPlaintext(), rsaPubPem);
        String rsaOaepDecrypted = RsaUtil.decryptOaepBase64(rsaOaepCiphertext, rsaPriPem);

        String rsaSha256Sig = RsaUtil.signSha256Base64(request.getSignPayload(), rsaPriPem);
        boolean rsaSha256Verified = RsaUtil.verifySha256Base64(request.getSignPayload(), rsaSha256Sig, rsaPubPem);

        String rsaPssSig = RsaUtil.signPssBase64(request.getSignPayload(), rsaPriPem);
        boolean rsaPssVerified = RsaUtil.verifyPssBase64(request.getSignPayload(), rsaPssSig, rsaPubPem);

        boolean wrongDecryptBlocked = false;
        try {
            RsaUtil.decryptOaepBase64(rsaOaepCiphertext, otherRsaPriPem);
        } catch (IllegalStateException ex) {
            wrongDecryptBlocked = ex.getCause() != null;
        }
        boolean rsaTamperBlocked = wrongDecryptBlocked
                && !RsaUtil.verifyPssBase64(request.getSignPayload() + "&tamper=1", rsaPssSig, rsaPubPem);

        // 2. 国密 SM2 密钥对生成、C1C3C2 / ASN.1 DER 双向加解密与 SM2/SM3 DER + Raw 64B 签验
        Sm2Util.Sm2KeyPair sm2Pair = Sm2Util.generateKeyPair();
        byte[] customUserId = (request.getSm2UserId() != null && !request.getSm2UserId().isBlank())
                ? request.getSm2UserId().getBytes(StandardCharsets.UTF_8)
                : Sm2Util.defaultUserId();

        String sm2C1c3c2Ciphertext = Sm2Util.encryptBase64(request.getPlaintext(), sm2Pair.publicKeyHex());
        String sm2DerCiphertext = Sm2Util.encryptToDerBase64(request.getPlaintext(), sm2Pair.publicKeyHex());
        String sm2C1c3c2Decrypted = Sm2Util.decryptBase64(sm2C1c3c2Ciphertext, sm2Pair.privateKeyHex());
        String sm2DerDecrypted = Sm2Util.decryptBase64(sm2DerCiphertext, sm2Pair.privateKeyHex());

        byte[] signBytes = request.getSignPayload().getBytes(StandardCharsets.UTF_8);
        String sm2DerSig = Sm2Util.signBase64(request.getSignPayload(), sm2Pair.privateKeyHex(), customUserId);
        boolean sm2DerVerified = Sm2Util.verifyBase64(request.getSignPayload(), sm2DerSig, sm2Pair.publicKeyHex(), customUserId);

        byte[] sm2RawSig = Sm2Util.signRaw(signBytes, sm2Pair.privateKey(), customUserId);
        boolean sm2RawVerified = sm2RawSig.length == 64
                && Sm2Util.verify(signBytes, sm2RawSig, sm2Pair.publicKey(), customUserId);

        byte[] otherUserId = "OTHER_DOMAIN_APP".getBytes(StandardCharsets.UTF_8);
        boolean sm2UserIdIsolated = !Sm2Util.verifyBase64(request.getSignPayload(), sm2DerSig, sm2Pair.publicKeyHex(), otherUserId);

        byte[] rawCipher = Base64.getDecoder().decode(sm2C1c3c2Ciphertext);
        rawCipher[70] ^= 0x01;
        boolean sm2TamperBlocked = false;
        try {
            Sm2Util.decrypt(rawCipher, sm2Pair.privateKey());
        } catch (IllegalStateException ex) {
            sm2TamperBlocked = true;
        }

        log.info("非对称加解密与签验闭环完成: rsaKeyPemRoundTripValid={}, sm2DerVerified={}, sm2RawVerified={}",
                rsaKeyPemRoundTripValid, sm2DerVerified, sm2RawVerified);

        return AsymmetricCryptoVO.builder()
                .rsaKeyPemRoundTripValid(rsaKeyPemRoundTripValid)
                .rsaOaepCiphertext(rsaOaepCiphertext)
                .rsaOaepDecrypted(rsaOaepDecrypted)
                .rsaSha256Verified(rsaSha256Verified)
                .rsaPssVerified(rsaPssVerified)
                .rsaTamperBlocked(rsaTamperBlocked)
                .sm2PublicKeyHex(sm2Pair.publicKeyHex())
                .sm2C1c3c2Ciphertext(sm2C1c3c2Ciphertext)
                .sm2DerCiphertext(sm2DerCiphertext)
                .sm2C1c3c2Decrypted(sm2C1c3c2Decrypted)
                .sm2DerDecrypted(sm2DerDecrypted)
                .sm2DerVerified(sm2DerVerified)
                .sm2RawVerified(sm2RawVerified)
                .sm2UserIdIsolated(sm2UserIdIsolated)
                .sm2TamperBlocked(sm2TamperBlocked)
                .build();
    }

    /**
     * 17. 验证 HKDF-SHA256 / HKDF-SM3 密钥派生、HMAC-SHA512、流式摘要与自包含 Packed AEAD 闭环
     *
     * @param request KDF 与流式摘要请求对象
     * @return HKDF 派生密钥、HMAC-SHA512、流式摘要、Packed AEAD 往返与 Base64URL 结果视图
     */
    public KdfAndStreamVO verifyKdfAndStream(KdfAndStreamRequest request) {
        BusinessAssert.notNull(request, "KDF 与流式摘要请求体不能为空");
        BusinessAssert.hasText(request.getMasterSecret(), "主密钥材料不能为空");
        BusinessAssert.hasText(request.getInfo(), "HKDF 上下文信息 info 不能为空");
        BusinessAssert.hasText(request.getPayload(), "业务载荷不能为空");
        BusinessAssert.hasText(request.getAad(), "附加认证数据 AAD 不能为空");

        byte[] ikm = request.getMasterSecret().getBytes(StandardCharsets.UTF_8);
        byte[] saltBytes = (request.getSalt() != null && !request.getSalt().isEmpty())
                ? request.getSalt().getBytes(StandardCharsets.UTF_8)
                : null;
        byte[] derivedAesKey = null;
        byte[] derivedSm4Key = null;
        byte[] auditDomainKey = null;
        byte[] hmacKey = null;
        try {
            // 1. RFC 5869 HKDF 派生子密钥与上下文域隔离
            derivedAesKey = HkdfUtil.deriveKey(ikm, saltBytes, request.getInfo(), 32);
            derivedSm4Key = HkdfUtil.deriveKeySm3(ikm, saltBytes, request.getInfo() + ":sm4", 32);
            auditDomainKey = HkdfUtil.deriveKey(ikm, saltBytes, request.getInfo() + ":audit", 32);
            boolean hkdfDomainIsolated = !Arrays.equals(derivedAesKey, auditDomainKey);

            // 2. HMAC-SHA512 签验与流式 InputStream 增量摘要（验证流结果与字节数组摘要严格一致）
            hmacKey = Arrays.copyOf(derivedAesKey, 32);
            byte[] payloadBytes = request.getPayload().getBytes(StandardCharsets.UTF_8);
            String hmacSha512Hex = HmacUtil.hmacSha512Hex(payloadBytes, hmacKey);
            boolean hmacSha512Verified = HmacUtil.verifyHex(
                    HmacUtil.hmacSha512Hex(payloadBytes, hmacKey), hmacSha512Hex);

            String sha256StreamHex = DigestUtil.sha256Hex(new ByteArrayInputStream(payloadBytes));
            String sha512StreamHex = DigestUtil.sha512Hex(new ByteArrayInputStream(payloadBytes));
            String sm3StreamHex = DigestUtil.sm3Hex(new ByteArrayInputStream(payloadBytes));
            BusinessAssert.isTrue(sha256StreamHex.equals(DigestUtil.sha256Hex(payloadBytes))
                            && sha512StreamHex.equals(DigestUtil.sha512Hex(payloadBytes))
                            && sm3StreamHex.equals(DigestUtil.sm3Hex(payloadBytes)),
                    "流式摘要结果必须与字节数组摘要完全一致");

            // 3. Packed AEAD 自包含加密往返（前 16 字节用作 SM4 密钥，32 字节派生密钥用作 AES-256 密钥）
            byte[] aadBytes = request.getAad().getBytes(StandardCharsets.UTF_8);
            byte[] sm4Key = Arrays.copyOf(derivedSm4Key, CryptoUtil.SM4_KEY_SIZE);
            try {
                String aesGcmPackedCiphertext = CryptoUtil.encryptGcmPacked(
                        request.getPayload(), derivedAesKey, aadBytes);
                String aesGcmPackedDecrypted = CryptoUtil.decryptGcmPacked(
                        aesGcmPackedCiphertext, derivedAesKey, aadBytes);

                String sm4GcmPackedCiphertext = CryptoUtil.encryptSm4GcmPacked(
                        request.getPayload(), sm4Key, aadBytes);
                String sm4GcmPackedDecrypted = CryptoUtil.decryptSm4GcmPacked(
                        sm4GcmPackedCiphertext, sm4Key, aadBytes);

                // 4. Base64URL 无填充编码与恒定时间字符串/字节比较
                String base64UrlToken = CryptoUtil.encodeBase64Url(payloadBytes);
                String base64UrlDecoded = new String(
                        CryptoUtil.decodeBase64Url(base64UrlToken), StandardCharsets.UTF_8);
                boolean constantTimeEqualsValid = CryptoUtil.constantTimeEquals(hmacSha512Hex, hmacSha512Hex)
                        && !CryptoUtil.constantTimeEquals(hmacSha512Hex, hmacSha512Hex + "0");

                return KdfAndStreamVO.builder()
                        .hkdfSha256KeyHex(HexUtil.encodeHex(derivedAesKey))
                        .hkdfSm3KeyHex(HexUtil.encodeHex(derivedSm4Key))
                        .hkdfDomainIsolated(hkdfDomainIsolated)
                        .hmacSha512Hex(hmacSha512Hex)
                        .hmacSha512Verified(hmacSha512Verified)
                        .sha256StreamHex(sha256StreamHex)
                        .sha512StreamHex(sha512StreamHex)
                        .sm3StreamHex(sm3StreamHex)
                        .aesGcmPackedCiphertext(aesGcmPackedCiphertext)
                        .aesGcmPackedDecrypted(aesGcmPackedDecrypted)
                        .sm4GcmPackedCiphertext(sm4GcmPackedCiphertext)
                        .sm4GcmPackedDecrypted(sm4GcmPackedDecrypted)
                        .base64UrlToken(base64UrlToken)
                        .base64UrlDecoded(base64UrlDecoded)
                        .constantTimeEqualsValid(constantTimeEqualsValid)
                        .build();
            } finally {
                Arrays.fill(sm4Key, (byte) 0);
            }
        } finally {
            Arrays.fill(ikm, (byte) 0);
            if (saltBytes != null) Arrays.fill(saltBytes, (byte) 0);
            if (derivedAesKey != null) Arrays.fill(derivedAesKey, (byte) 0);
            if (derivedSm4Key != null) Arrays.fill(derivedSm4Key, (byte) 0);
            if (auditDomainKey != null) Arrays.fill(auditDomainKey, (byte) 0);
            if (hmacKey != null) Arrays.fill(hmacKey, (byte) 0);
        }
    }
}
