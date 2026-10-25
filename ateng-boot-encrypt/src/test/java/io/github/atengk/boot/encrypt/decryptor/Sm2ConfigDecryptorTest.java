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
}
