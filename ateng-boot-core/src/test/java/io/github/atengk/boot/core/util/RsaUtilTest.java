package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RsaUtil} 纯 JDK 原生 RSA 非对称加解密与数字签名单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("RsaUtil 非对称加解密与数字签名原语测试")
class RsaUtilTest {

    @Test
    @DisplayName("支持 2048/3072/4096 位密钥对生成并拒绝弱密钥位长")
    void shouldGenerateValidKeySizesAndRejectWeakSizes() {
        for (int size : new int[]{2048, 3072, 4096}) {
            KeyPair kp = RsaUtil.generateKeyPair(size);
            assertNotNull(kp);
            assertEquals(size, ((RSAKey) kp.getPublic()).getModulus().bitLength());
            assertEquals(size, ((RSAKey) kp.getPrivate()).getModulus().bitLength());
        }

        assertThrows(IllegalArgumentException.class, () -> RsaUtil.generateKeyPair(1024));
        assertThrows(IllegalArgumentException.class, () -> RsaUtil.generateKeyPair(512));
        assertThrows(IllegalArgumentException.class, () -> RsaUtil.generateKeyPair(0));
    }

    @Test
    @DisplayName("支持 PEM / Base64 / DER 格式 X.509 公钥与 PKCS#8 私钥互转解析，并明确拒绝 PKCS#1")
    void shouldParseAndEncodePemBase64DerKeys() {
        KeyPair kp = RsaUtil.generateKeyPair();

        String pubPem = RsaUtil.encodePublicKeyToPem(kp.getPublic());
        String priPem = RsaUtil.encodePrivateKeyToPem(kp.getPrivate());
        String pubBase64 = RsaUtil.encodePublicKeyBase64(kp.getPublic());
        String priBase64 = RsaUtil.encodePrivateKeyBase64(kp.getPrivate());

        assertTrue(pubPem.startsWith("-----BEGIN PUBLIC KEY-----"));
        assertTrue(pubPem.endsWith("-----END PUBLIC KEY-----"));
        assertTrue(priPem.startsWith("-----BEGIN PRIVATE KEY-----"));
        assertTrue(priPem.endsWith("-----END PRIVATE KEY-----"));

        PublicKey fromPemPub = RsaUtil.parsePublicKey(pubPem);
        PublicKey fromB64Pub = RsaUtil.parsePublicKey(pubBase64);
        PublicKey fromDerPub = RsaUtil.parsePublicKey(kp.getPublic().getEncoded());
        assertArrayEquals(kp.getPublic().getEncoded(), fromPemPub.getEncoded());
        assertArrayEquals(kp.getPublic().getEncoded(), fromB64Pub.getEncoded());
        assertArrayEquals(kp.getPublic().getEncoded(), fromDerPub.getEncoded());

        PrivateKey fromPemPri = RsaUtil.parsePrivateKey(priPem);
        PrivateKey fromB64Pri = RsaUtil.parsePrivateKey(priBase64);
        PrivateKey fromDerPri = RsaUtil.parsePrivateKey(kp.getPrivate().getEncoded());
        assertArrayEquals(kp.getPrivate().getEncoded(), fromPemPri.getEncoded());
        assertArrayEquals(kp.getPrivate().getEncoded(), fromB64Pri.getEncoded());
        assertArrayEquals(kp.getPrivate().getEncoded(), fromDerPri.getEncoded());

        assertNull(RsaUtil.encodePublicKeyToPem(null));
        assertNull(RsaUtil.encodePrivateKeyToPem(null));
        assertNull(RsaUtil.encodePublicKeyBase64(null));
        assertNull(RsaUtil.encodePrivateKeyBase64(null));

        // 模拟传统 OpenSSL PKCS#1 私钥头结构（合法 Base64 DER 载荷），必须抛出包含明确转换提示的异常
        String fakePkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBALeFakePkcs1Payload=\n-----END RSA PRIVATE KEY-----";
        IllegalArgumentException pkcs1Ex = assertThrows(IllegalArgumentException.class,
                () -> RsaUtil.parsePrivateKey(fakePkcs1));
        assertTrue(pkcs1Ex.getMessage().contains("PKCS#1"));
        assertTrue(pkcs1Ex.getMessage().contains("PKCS#8"));
    }

    @Test
    @DisplayName("RSA-OAEP-SHA256 双向加解密、明文长度边界校验与篡改拦截")
    void shouldEncryptDecryptOaepWithBoundsAndTamperDetection() {
        KeyPair kp = RsaUtil.generateKeyPair(2048);
        String pubPem = RsaUtil.encodePublicKeyToPem(kp.getPublic());
        String priPem = RsaUtil.encodePrivateKeyToPem(kp.getPrivate());

        String plain = "{\"sessionKey\":\"sm4-aes-demo-2026\",\"scope\":\"payment\"}";
        String cipherB64 = RsaUtil.encryptOaepBase64(plain, pubPem);
        String decrypted = RsaUtil.decryptOaepBase64(cipherB64, priPem);
        assertEquals(plain, decrypted);

        // 最大允许明文长度 boundary = 256 - 66 = 190 字节
        int maxPlainBytes = RsaUtil.getOaepMaxPlaintextBytes(kp.getPublic());
        assertEquals(190, maxPlainBytes);

        byte[] maxBytes = new byte[maxPlainBytes];
        for (int i = 0; i < maxBytes.length; i++) {
            maxBytes[i] = (byte) (i & 0x7f);
        }
        byte[] maxCipher = RsaUtil.encryptOaep(maxBytes, kp.getPublic());
        assertArrayEquals(maxBytes, RsaUtil.decryptOaep(maxCipher, kp.getPrivate()));

        // 空明文 (0 字节) 合法单块
        byte[] emptyCipher = RsaUtil.encryptOaep(new byte[0], pubPem);
        assertArrayEquals(new byte[0], RsaUtil.decryptOaep(emptyCipher, priPem));

        // 超出上限 1 字节快速失败
        byte[] overBytes = new byte[maxPlainBytes + 1];
        IllegalArgumentException lenEx = assertThrows(IllegalArgumentException.class,
                () -> RsaUtil.encryptOaep(overBytes, kp.getPublic()));
        assertTrue(lenEx.getMessage().contains("190"));

        // 篡改密文或错私钥必须抛出脱敏 IllegalStateException 且保留 cause
        byte[] tamperedCipher = maxCipher.clone();
        tamperedCipher[0] ^= 0x01;
        IllegalStateException tamperEx = assertThrows(IllegalStateException.class,
                () -> RsaUtil.decryptOaep(tamperedCipher, kp.getPrivate()));
        assertNotNull(tamperEx.getCause());
        assertEquals("RSA-OAEP 解密执行失败", tamperEx.getMessage());

        KeyPair otherKp = RsaUtil.generateKeyPair(2048);
        assertThrows(IllegalStateException.class, () -> RsaUtil.decryptOaep(maxCipher, otherKp.getPrivate()));

        // 空参防御
        assertThrows(IllegalArgumentException.class, () -> RsaUtil.encryptOaep((byte[]) null, kp.getPublic()));
        assertThrows(IllegalArgumentException.class, () -> RsaUtil.decryptOaep((byte[]) null, kp.getPrivate()));
        assertThrows(IllegalArgumentException.class, () -> RsaUtil.decryptOaep(new byte[0], kp.getPrivate()));
    }

    @Test
    @DisplayName("SHA256withRSA 与 RSASSA-PSS 数字签名、验签及防篡改验证")
    void shouldSignAndVerifySha256AndPss() {
        KeyPair kp = RsaUtil.generateKeyPair(2048);
        KeyPair otherKp = RsaUtil.generateKeyPair(2048);
        String pubPem = RsaUtil.encodePublicKeyToPem(kp.getPublic());
        String priPem = RsaUtil.encodePrivateKeyToPem(kp.getPrivate());
        String payload = "POST\n/api/v2/settlement\n1794052800000\nnonce-rsa-01\n{\"amount\":888.88}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        // 1. SHA256withRSA
        String sha256Sig = RsaUtil.signSha256Base64(payload, priPem);
        assertTrue(RsaUtil.verifySha256Base64(payload, sha256Sig, pubPem));
        assertFalse(RsaUtil.verifySha256Base64(payload + "!", sha256Sig, pubPem));
        assertFalse(RsaUtil.verifySha256Base64(payload, sha256Sig, RsaUtil.encodePublicKeyBase64(otherKp.getPublic())));

        // 2. RSASSA-PSS (SHA-256)
        byte[] pssSig = RsaUtil.signPss(payloadBytes, kp.getPrivate());
        assertTrue(RsaUtil.verifyPss(payloadBytes, pssSig, kp.getPublic()));
        assertFalse(RsaUtil.verifyPss((payload + "!").getBytes(StandardCharsets.UTF_8), pssSig, kp.getPublic()));

        String pssSigB64 = RsaUtil.signPssBase64(payload, priPem);
        assertTrue(RsaUtil.verifyPssBase64(payload, pssSigB64, pubPem));
        assertFalse(RsaUtil.verifyPssBase64(payload, sha256Sig, pubPem)); // PSS 与 PKCS1v1.5 签名不可混用

        byte[] tamperedPss = pssSig.clone();
        tamperedPss[tamperedPss.length - 1] ^= 0x01;
        assertFalse(RsaUtil.verifyPss(payloadBytes, tamperedPss, kp.getPublic()));

        // 3. 空值与异常参数防御
        assertFalse(RsaUtil.verifySha256Base64(null, sha256Sig, pubPem));
        assertFalse(RsaUtil.verifySha256Base64(payload, null, pubPem));
        assertFalse(RsaUtil.verifySha256Base64(payload, "not-base64!!!", pubPem));
        assertFalse(RsaUtil.verifyPssBase64(payload, pssSigB64, "invalid-pub-key"));
        assertThrows(IllegalArgumentException.class, () -> RsaUtil.signPss(null, kp.getPrivate()));
        assertThrows(IllegalArgumentException.class, () -> RsaUtil.signSha256(payloadBytes, null));
    }
}
