package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯 JDK 原生国密 SM2 非对称加解密器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@DisplayName("纯 JDK 原生国密 SM2 非对称加解密与等保合规单元测试")
class Sm2ConfigDecryptorTest {

    private final Sm2ConfigDecryptor decryptor = new Sm2ConfigDecryptor();

    @Test
    @DisplayName("supports: 仅支持 SM2 算法")
    void testSupports() {
        assertThat(decryptor.supports(EncryptAlgorithm.SM2)).isTrue();
        assertThat(decryptor.supports(EncryptAlgorithm.RSA)).isFalse();
        assertThat(decryptor.supports(EncryptAlgorithm.AES_GCM)).isFalse();
    }

    @Test
    @DisplayName("SM2 加解密全流程与公私钥职责隔离")
    void testSm2KeySeparation() throws Exception {
        Sm2Engine.Sm2KeyPair keyPair = Sm2ConfigDecryptor.generateKeyPair();
        String publicKey = keyPair.getPublicKeyHex();
        String privateKey = keyPair.getPrivateKeyHex();

        assertThat(publicKey).hasSize(130).startsWith("04");
        assertThat(privateKey).hasSize(64);

        String plaintext = "nacos_config_center_prod_password_2026";

        // 开发与构建环境使用公钥加密
        String ciphertext = Sm2ConfigDecryptor.encrypt(plaintext, publicKey);
        assertThat(ciphertext).isNotNull().isNotEmpty();

        // 生产环境使用私钥解密
        String decrypted = decryptor.decrypt(ciphertext, privateKey);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("PEM 格式 SM2 公私钥兼容性测试")
    void testPemKeys() throws Exception {
        Sm2Engine.Sm2KeyPair keyPair = Sm2ConfigDecryptor.generateKeyPair();
        String publicKeyPem = Sm2ConfigDecryptor.encodePublicKey(keyPair.getPublicKey());
        String privateKeyPem = Sm2ConfigDecryptor.encodePrivateKey(keyPair.getPrivateKey());

        assertThat(publicKeyPem).startsWith("-----BEGIN PUBLIC KEY-----");
        assertThat(privateKeyPem).startsWith("-----BEGIN PRIVATE KEY-----");

        String plaintext = "wechat_pay_api_secret_key";
        String ciphertext = Sm2ConfigDecryptor.encrypt(plaintext, publicKeyPem);
        String decrypted = decryptor.decrypt(ciphertext, privateKeyPem);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("超长大文本与微信支付 PEM 私钥解密保真")
    void testMultilinePemHygiene() throws Exception {
        Sm2Engine.Sm2KeyPair keyPair = Sm2ConfigDecryptor.generateKeyPair();

        String pemSecret = "-----BEGIN PRIVATE KEY-----\n"
                + "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC70123456789ab\n"
                + "cdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ01234567890123\n"
                + "456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ012345\n"
                + "-----END PRIVATE KEY-----\n";

        String ciphertext = Sm2ConfigDecryptor.encrypt(pemSecret, keyPair.getPublicKeyHex());
        String decrypted = decryptor.decrypt(ciphertext, keyPair.getPrivateKeyHex());
        assertThat(decrypted).isEqualTo(pemSecret);
    }

    @Test
    @DisplayName("ASN.1 DER 编码密文与标准 C1C3C2 密文兼容解密")
    void testAsn1DerCiphertextCompatibility() throws Exception {
        Sm2Engine.Sm2KeyPair keyPair = Sm2ConfigDecryptor.generateKeyPair();
        String plaintext = "database_password_sm2_der";

        // 默认生成标准 C1C3C2 密文
        String c1c3c2Cipher = Sm2ConfigDecryptor.encrypt(plaintext, keyPair.getPublicKeyHex());
        assertThat(decryptor.decrypt(c1c3c2Cipher, keyPair.getPrivateKeyHex())).isEqualTo(plaintext);

        // 转换为 ASN.1 DER 编码密文
        String derCipher = Sm2ConfigDecryptor.encryptAsn1Der(plaintext, keyPair.getPublicKeyHex());
        assertThat(decryptor.decrypt(derCipher, keyPair.getPrivateKeyHex())).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("安全防御与快速失败: 错误私钥或篡改密文必须触发 MAC 校验失败")
    void testFailFastOnWrongKeyOrTamperedCiphertext() {
        Sm2Engine.Sm2KeyPair keyPair1 = Sm2ConfigDecryptor.generateKeyPair();
        Sm2Engine.Sm2KeyPair keyPair2 = Sm2ConfigDecryptor.generateKeyPair();

        String ciphertext = Sm2ConfigDecryptor.encrypt("secret_value", keyPair1.getPublicKeyHex());

        // 错误私钥解密
        assertThatThrownBy(() -> decryptor.decrypt(ciphertext, keyPair2.getPrivateKeyHex()))
                .isInstanceOf(Exception.class);

        // 篡改密文 (C3 MAC 校验必然失败)
        byte[] raw = java.util.Base64.getDecoder().decode(ciphertext);
        raw[raw.length - 1] ^= 0x01;
        String tamperedCipher = java.util.Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> decryptor.decrypt(tamperedCipher, keyPair1.getPrivateKeyHex()))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("双向格式转译: c1c3c2ToDer 与 derToC1c3c2 无损往返互转")
    void testBidirectionalDerConversion() {
        Sm2Engine.Sm2KeyPair keyPair = Sm2ConfigDecryptor.generateKeyPair();
        String plaintext = "interoperable_sm2_secret_payload_123456";

        // 1. 默认加密生成标准 0x04 || C1 || C3 || C2 密文字节流
        byte[] c1c3c2 = Sm2Engine.encrypt(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8), keyPair.getPublicKey());
        assertThat(c1c3c2[0]).isEqualTo((byte) 0x04);

        // 2. 转译为 ASN.1 DER 编码
        byte[] der = Sm2Engine.c1c3c2ToDer(c1c3c2);
        assertThat(der[0]).isEqualTo((byte) 0x30);

        // 3. 逆向还原为权威国家标准 C1C3C2
        byte[] roundTrip = Sm2Engine.derToC1c3c2(der);
        assertThat(roundTrip).isEqualTo(c1c3c2);

        // 4. 验证 Base64 字符串重载互转无损性
        String c1c3c2Base64 = java.util.Base64.getEncoder().encodeToString(c1c3c2);
        String derBase64 = Sm2ConfigDecryptor.c1c3c2ToDer(c1c3c2Base64);
        assertThat(derBase64).isNotNull().isNotEqualTo(c1c3c2Base64);
        String restoredC1c3c2Base64 = Sm2ConfigDecryptor.derToC1c3c2(derBase64);
        assertThat(restoredC1c3c2Base64).isEqualTo(c1c3c2Base64);

        // 5. 验证还原后密文解密有效性
        byte[] decrypted = Sm2Engine.decrypt(roundTrip, keyPair.getPrivateKey());
        assertThat(new String(decrypted, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("解密无 0x04 前缀的纯 64 字节 C1 原始拼接密文")
    void testDecryptRawC1C3C2Without04Prefix() {
        Sm2Engine.Sm2KeyPair keyPair = Sm2ConfigDecryptor.generateKeyPair();
        String plaintext = "raw_c1_without_04_header";

        byte[] c1c3c2 = Sm2Engine.encrypt(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8), keyPair.getPublicKey());
        assertThat(c1c3c2[0]).isEqualTo((byte) 0x04);

        // 剥离首字节 0x04 (64 字节 C1 + 32 字节 C3 + C2)
        byte[] rawWithout04 = new byte[c1c3c2.length - 1];
        System.arraycopy(c1c3c2, 1, rawWithout04, 0, rawWithout04.length);

        byte[] decrypted = Sm2Engine.decrypt(rawWithout04, keyPair.getPrivateKey());
        assertThat(new String(decrypted, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(plaintext);

        // 多次随机循环测试，验证无论首字节随机值为何（包括恰为 0x30 时）均可稳定解密
        for (int i = 0; i < 50; i++) {
            byte[] enc = Sm2Engine.encrypt(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8), keyPair.getPublicKey());
            byte[] raw = new byte[enc.length - 1];
            System.arraycopy(enc, 1, raw, 0, raw.length);
            byte[] dec = Sm2Engine.decrypt(raw, keyPair.getPrivateKey());
            assertThat(new String(dec, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(plaintext);
        }
    }

    @Test
    @DisplayName("损坏的 ASN.1 DER 结构快速失败并抛出明确的 IllegalArgumentException")
    void testCorruptedAsn1DerFailFast() {
        // 空或过短数组
        assertThatThrownBy(() -> Sm2Engine.derToC1c3c2(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sm2Engine.derToC1c3c2(new byte[]{0x30, 0x01}))
                .isInstanceOf(IllegalArgumentException.class);

        // 非 0x30 起始
        assertThatThrownBy(() -> Sm2Engine.derToC1c3c2(new byte[]{0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0x30");

        // 声明长度超出实际数据流边界
        byte[] seqOverflow = new byte[]{0x30, 0x20, 0x02, 0x01, 0x00, 0x00, 0x00, 0x00};
        assertThatThrownBy(() -> Sm2Engine.derToC1c3c2(seqOverflow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出实际数据流长度");

        // 损坏的 tag (例如 x1 不是 0x02)
        byte[] corruptTag = new byte[]{0x30, 0x06, 0x05, 0x02, 0x01, 0x02, 0x00, 0x00};
        assertThatThrownBy(() -> Sm2Engine.derToC1c3c2(corruptTag))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x1");

        // c1c3c2ToDer 入参校验
        assertThatThrownBy(() -> Sm2Engine.c1c3c2ToDer(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sm2Engine.c1c3c2ToDer(new byte[]{0x04, 0x01, 0x02}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度非法");
        assertThatThrownBy(() -> Sm2Engine.c1c3c2ToDer(new byte[]{0x30, 0x01, 0x02, 0x03}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已为 ASN.1 DER");
    }

    @Test
    @DisplayName("标准输出不变量: 默认加密始终输出 0x04 || C1 || C3 || C2")
    void testStandardOutputInvariant() {
        Sm2Engine.Sm2KeyPair keyPair = Sm2ConfigDecryptor.generateKeyPair();
        byte[] cipherBytes = Sm2Engine.encrypt("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), keyPair.getPublicKey());
        assertThat(cipherBytes[0]).isEqualTo((byte) 0x04);
        assertThat(cipherBytes.length).isEqualTo(65 + 32 + 5);

        String cipherBase64 = Sm2ConfigDecryptor.encrypt("hello", keyPair.getPublicKeyHex());
        byte[] decoded = java.util.Base64.getDecoder().decode(cipherBase64);
        assertThat(decoded[0]).isEqualTo((byte) 0x04);
        assertThat(decoded.length).isEqualTo(65 + 32 + 5);
    }
}
