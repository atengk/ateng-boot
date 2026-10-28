package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯 JDK 原生 RSA 非对称加解密器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@DisplayName("RSA 非对称加解密与公私钥职责隔离单元测试")
class RsaConfigDecryptorTest {

    private final RsaConfigDecryptor decryptor = new RsaConfigDecryptor();

    @Test
    @DisplayName("supports: 仅支持 RSA 算法")
    void testSupports() {
        assertThat(decryptor.supports(EncryptAlgorithm.RSA)).isTrue();
        assertThat(decryptor.supports(EncryptAlgorithm.AES_GCM)).isFalse();
        assertThat(decryptor.supports(EncryptAlgorithm.SM4_CBC)).isFalse();
    }

    @Test
    @DisplayName("2048 位 RSA: 公钥加密、私钥解密完整流程与公私钥职责隔离")
    void testRsa2048KeySeparation() throws Exception {
        KeyPair keyPair = RsaConfigDecryptor.generateKeyPair(2048);
        String publicKeyPem = RsaConfigDecryptor.encodePublicKey(keyPair.getPublic());
        String privateKeyPem = RsaConfigDecryptor.encodePrivateKey(keyPair.getPrivate());

        assertThat(publicKeyPem).startsWith("-----BEGIN PUBLIC KEY-----");
        assertThat(privateKeyPem).startsWith("-----BEGIN PRIVATE KEY-----");

        String plaintext = "prod_db_secret_password_2026";
        // 开发/构建环境仅使用公钥加密
        String ciphertext = RsaConfigDecryptor.encrypt(plaintext, publicKeyPem);
        assertThat(ciphertext).isNotNull().isNotEmpty();

        // 生产环境仅使用私钥解密
        String decrypted = decryptor.decrypt(ciphertext, privateKeyPem);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("4096 位 RSA: 支持超高安全等级 4096 位密钥加解密")
    void testRsa4096() throws Exception {
        KeyPair keyPair = RsaConfigDecryptor.generateKeyPair(4096);
        String publicKey = RsaConfigDecryptor.encodePublicKey(keyPair.getPublic());
        String privateKey = RsaConfigDecryptor.encodePrivateKey(keyPair.getPrivate());

        String plaintext = "root_super_admin_pass_4096";
        String ciphertext = RsaConfigDecryptor.encrypt(plaintext, publicKey);
        String decrypted = decryptor.decrypt(ciphertext, privateKey);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("超长大文本分段加解密: 完美容纳多行 PEM 证书与超长报文")
    void testSegmentedLongText() throws Exception {
        KeyPair keyPair = RsaConfigDecryptor.generateKeyPair(2048);
        String publicKey = RsaConfigDecryptor.encodePublicKey(keyPair.getPublic());
        String privateKey = RsaConfigDecryptor.encodePrivateKey(keyPair.getPrivate());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("This is line ").append(i).append(" of confidential RSA secret credentials.\n");
        }
        String longText = sb.toString();

        String ciphertext = RsaConfigDecryptor.encrypt(longText, publicKey);
        String decrypted = decryptor.decrypt(ciphertext, privateKey);
        assertThat(decrypted).isEqualTo(longText);
    }

    @Test
    @DisplayName("PEM 机密保真: 针对解密结果为 PEM 证书/私钥时保真还原换行与标头标尾")
    void testPemPayloadHygiene() throws Exception {
        KeyPair keyPair = RsaConfigDecryptor.generateKeyPair(2048);
        String publicKey = RsaConfigDecryptor.encodePublicKey(keyPair.getPublic());
        String privateKey = RsaConfigDecryptor.encodePrivateKey(keyPair.getPrivate());

        String pemSecret = "-----BEGIN CERTIFICATE-----\n"
                + "MIIDdzCCAl+gAwIBAgIEAgAAuTANBgkqhkiG9w0BAQsFADBaMQswCQYDVQQGEwJD\n"
                + "TjESMBAGA1UECAwJQmVpamluZzESMBAGA1UEBwwJQmVpamluZzETMBEGA1UECgwK\n"
                + "QXRlbmctQm9vdDETMBEGA1UECwwKQXRlbmctQm9vdDAeFw0yNjEwMjUwOTAwMDBa\n"
                + "-----END CERTIFICATE-----\n";

        String ciphertext = RsaConfigDecryptor.encrypt(pemSecret, publicKey);
        String decrypted = decryptor.decrypt(ciphertext, privateKey);
        assertThat(decrypted).isEqualTo(pemSecret);
    }

    @Test
    @DisplayName("安全防御与快速失败: 错误私钥或篡改密文必须抛出异常")
    void testFailFastOnWrongKeyOrCorruptedCiphertext() throws Exception {
        KeyPair keyPair1 = RsaConfigDecryptor.generateKeyPair(2048);
        KeyPair keyPair2 = RsaConfigDecryptor.generateKeyPair(2048);

        String publicKey1 = RsaConfigDecryptor.encodePublicKey(keyPair1.getPublic());
        String privateKey2 = RsaConfigDecryptor.encodePrivateKey(keyPair2.getPrivate());

        String ciphertext = RsaConfigDecryptor.encrypt("secret", publicKey1);

        // 使用不同私钥解密必须失败
        assertThatThrownBy(() -> decryptor.decrypt(ciphertext, privateKey2))
                .isInstanceOf(Exception.class);

        // 篡改密文解密必须失败
        String tamperedCiphertext = "AAAA" + ciphertext.substring(4);
        assertThatThrownBy(() -> decryptor.decrypt(tamperedCiphertext, RsaConfigDecryptor.encodePrivateKey(keyPair1.getPrivate())))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("因果异常体系: OAEP 与 PKCS1 双重解密失败时必须保留 OAEP 原始被抑制异常")
    void testSuppressedExceptionOnDoubleFailure() throws Exception {
        KeyPair keyPair1 = RsaConfigDecryptor.generateKeyPair(2048);
        KeyPair keyPair2 = RsaConfigDecryptor.generateKeyPair(2048);

        String ciphertext = RsaConfigDecryptor.encrypt("secret_causal_chain", RsaConfigDecryptor.encodePublicKey(keyPair1.getPublic()));
        String wrongPrivateKey = RsaConfigDecryptor.encodePrivateKey(keyPair2.getPrivate());

        assertThatThrownBy(() -> decryptor.decrypt(ciphertext, wrongPrivateKey))
                .satisfies(ex -> {
                    assertThat(ex.getSuppressed()).isNotEmpty();
                });
    }
}
