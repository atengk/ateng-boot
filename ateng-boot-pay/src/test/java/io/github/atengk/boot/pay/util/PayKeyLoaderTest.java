package io.github.atengk.boot.pay.util;

import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.spi.PayCertificateProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付密钥四模自适应解析器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class PayKeyLoaderTest {

    private static final String CLASSPATH_KEY = "classpath:certs/test_private_key.pem";
    private static final String CLASSPATH_CERT = "classpath:certs/test_certificate.pem";
    private static final String CLASSPATH_PUBKEY = "classpath:certs/test_public_key.pem";

    @AfterEach
    void tearDown() {
        PayKeyLoader.clearProviders();
        PayKeyLoader.clearPublicKeyCache();
    }

    @Test
    @DisplayName("Mode 2: 类路径资源 (classpath:) 加载与公私钥验签闭环")
    void testLoadFromClasspath() throws Exception {
        PrivateKey privateKey = PayKeyLoader.loadPrivateKey(CLASSPATH_KEY);
        assertNotNull(privateKey, "从 classpath: 解析出的私钥不应为空");
        assertEquals("RSA", privateKey.getAlgorithm());

        X509Certificate certificate = PayKeyLoader.loadCertificate(CLASSPATH_CERT);
        assertNotNull(certificate, "从 classpath: 解析出的证书不应为空");

        String serialNumber = PayKeyLoader.getCertificateSerialNumber(certificate);
        assertNotNull(serialNumber);
        assertFalse(serialNumber.isBlank());

        // 使用加载的私钥签名，并用证书公钥验签
        byte[] data = "Hello Ateng Boot Pay".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(data);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(data);
        assertTrue(verifier.verify(signature), "使用解析出的私钥签名应能被对应证书公钥验签通过");
    }

    @Test
    @DisplayName("Mode 1: 文件系统路径 (file:) 与真实绝对路径自适应加载")
    void testLoadFromFileSystem(@TempDir Path tempDir) throws IOException {
        String keyContent = PayKeyLoader.loadString(CLASSPATH_KEY);
        Path keyFile = tempDir.resolve("temp_key.pem");
        Files.writeString(keyFile, keyContent);

        // 1. 使用 file: 前缀
        PrivateKey privateKey1 = PayKeyLoader.loadPrivateKey("file:" + keyFile.toAbsolutePath());
        assertNotNull(privateKey1);

        // 2. 直接使用真实文件绝对路径（不带 file: 前缀）
        PrivateKey privateKey2 = PayKeyLoader.loadPrivateKey(keyFile.toAbsolutePath().toString());
        assertNotNull(privateKey2);
        assertArrayEquals(privateKey1.getEncoded(), privateKey2.getEncoded());
    }

    @Test
    @DisplayName("Mode 3: 行内 PEM 文本内容与纯 Base64 清洗解析")
    void testLoadFromInlinePem() {
        String keyContent = PayKeyLoader.loadString(CLASSPATH_KEY);
        PrivateKey privateKey = PayKeyLoader.loadPrivateKey(keyContent);
        assertNotNull(privateKey);

        String cleanedPem = PayKeyLoader.loadPrivateKeyPem(CLASSPATH_KEY);
        assertFalse(cleanedPem.contains("-----BEGIN"));
        assertFalse(cleanedPem.contains("-----END"));
        assertFalse(cleanedPem.contains("\n"));
        assertFalse(cleanedPem.contains("\r"));

        // 直接使用清洗后的纯 Base64 字符串再次解析
        PrivateKey privateKeyFromBase64 = PayKeyLoader.loadPrivateKey(cleanedPem);
        assertNotNull(privateKeyFromBase64);
        assertArrayEquals(privateKey.getEncoded(), privateKeyFromBase64.getEncoded());

        // 公钥与证书 PEM 清洗验证
        String rawPublicKeyPem = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA\n-----END PUBLIC KEY-----";
        String cleanedPublicKey = PayKeyLoader.loadPublicKeyPem(rawPublicKeyPem);
        assertEquals("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA", cleanedPublicKey);
        assertFalse(cleanedPublicKey.contains("-----BEGIN"));
        assertFalse(cleanedPublicKey.contains("-----END"));
    }

    @Test
    @DisplayName("Mode 4: 外部 SPI (PayCertificateProvider) 动态加载支持")
    void testLoadFromSpi() {
        String keyContent = PayKeyLoader.loadString(CLASSPATH_KEY);
        PayCertificateProvider mockSpi = new PayCertificateProvider() {
            @Override
            public boolean supports(String locationOrIdentifier) {
                return "kms:wechat-mch-1001:key".equals(locationOrIdentifier);
            }

            @Override
            public byte[] loadBytes(String locationOrIdentifier) {
                return keyContent.getBytes(StandardCharsets.UTF_8);
            }
        };

        // 1. 显式指定 SPI 提供者
        PrivateKey keyViaExplicitSpi = PayKeyLoader.loadPrivateKey("kms:wechat-mch-1001:key", mockSpi);
        assertNotNull(keyViaExplicitSpi);

        // 2. 注册为全局 SPI 提供者后自动发现
        PayKeyLoader.registerProvider(mockSpi);
        PrivateKey keyViaGlobalSpi = PayKeyLoader.loadPrivateKey("kms:wechat-mch-1001:key");
        assertNotNull(keyViaGlobalSpi);
    }

    @Test
    @DisplayName("公钥加载：类路径资源 (classpath:) 加载与公私钥验签闭环")
    void testLoadPublicKeyFromClasspath() throws Exception {
        PublicKey publicKey = PayKeyLoader.loadPublicKey(CLASSPATH_PUBKEY);
        assertNotNull(publicKey, "从 classpath: 解析出的公钥不应为空");
        assertEquals("RSA", publicKey.getAlgorithm());

        PrivateKey privateKey = PayKeyLoader.loadPrivateKey(CLASSPATH_KEY);

        // 使用私钥签名，并用加载的公钥验签
        byte[] data = "Hello WeChat Pay Public Key".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(data);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        assertTrue(verifier.verify(signature), "使用解析出的公钥应能正确验证对应私钥签名");
    }

    @Test
    @DisplayName("公钥加载：文件系统路径 (file:) 与真实路径自适应加载")
    void testLoadPublicKeyFromFileSystem(@TempDir Path tempDir) throws IOException {
        String pubKeyContent = PayKeyLoader.loadString(CLASSPATH_PUBKEY);
        Path pubKeyFile = tempDir.resolve("temp_pubkey.pem");
        Files.writeString(pubKeyFile, pubKeyContent);

        PublicKey pubKey1 = PayKeyLoader.loadPublicKey("file:" + pubKeyFile.toAbsolutePath());
        assertNotNull(pubKey1);

        PublicKey pubKey2 = PayKeyLoader.loadPublicKey(pubKeyFile.toAbsolutePath().toString());
        assertNotNull(pubKey2);
        assertArrayEquals(pubKey1.getEncoded(), pubKey2.getEncoded());
    }

    @Test
    @DisplayName("公钥加载：行内 PEM、纯 Base64 清洗与内存缓存命中测试")
    void testLoadPublicKeyFromInlinePemAndCache() {
        String pubKeyContent = PayKeyLoader.loadString(CLASSPATH_PUBKEY);
        PublicKey pubKey1 = PayKeyLoader.loadPublicKey(pubKeyContent);
        assertNotNull(pubKey1);

        // 验证内存缓存命中 (引用完全相同)
        PublicKey pubKeyCached = PayKeyLoader.loadPublicKey(pubKeyContent);
        assertSame(pubKey1, pubKeyCached, "二次加载相同内容必须直接命中内存缓存");

        // 清理缓存后重新加载，对象不同但密钥字节一致
        PayKeyLoader.clearPublicKeyCache();
        PublicKey pubKeyReloaded = PayKeyLoader.loadPublicKey(pubKeyContent);
        assertNotSame(pubKey1, pubKeyReloaded);
        assertArrayEquals(pubKey1.getEncoded(), pubKeyReloaded.getEncoded());

        // 清洗后纯 Base64 加载
        String cleanedPem = PayKeyLoader.loadPublicKeyPem(CLASSPATH_PUBKEY);
        assertFalse(cleanedPem.contains("-----BEGIN"));
        assertFalse(cleanedPem.contains("-----END"));
        PublicKey pubKeyFromBase64 = PayKeyLoader.loadPublicKey(cleanedPem);
        assertNotNull(pubKeyFromBase64);
        assertArrayEquals(pubKey1.getEncoded(), pubKeyFromBase64.getEncoded());
    }

    @Test
    @DisplayName("异常防御：空定位符、不存在的文件与损坏的内容快速失败")
    void testExceptionDefenses() {
        // 空定位符
        assertThrows(PayException.class, () -> PayKeyLoader.loadPrivateKey(null));
        assertThrows(PayException.class, () -> PayKeyLoader.loadPrivateKey("   "));
        assertThrows(PayException.class, () -> PayKeyLoader.loadPublicKey(null));
        assertThrows(PayException.class, () -> PayKeyLoader.loadPublicKey("   "));

        // 不存在的类路径资源
        assertThrows(PayException.class, () -> PayKeyLoader.loadPrivateKey("classpath:non_existent.pem"));
        assertThrows(PayException.class, () -> PayKeyLoader.loadPublicKey("classpath:non_existent.pem"));

        // 不存在的文件系统路径
        assertThrows(PayException.class, () -> PayKeyLoader.loadPrivateKey("file:/non/existent/key.pem"));
        assertThrows(PayException.class, () -> PayKeyLoader.loadPublicKey("file:/non/existent/pubkey.pem"));

        // 格式损坏的私钥
        assertThrows(PaySignException.class, () -> PayKeyLoader.loadPrivateKey("invalid-corrupted-private-key"));

        // 格式损坏的公钥
        assertThrows(PaySignException.class, () -> PayKeyLoader.loadPublicKey("invalid-corrupted-public-key"));

        // 格式损坏的证书
        assertThrows(PaySignException.class, () -> PayKeyLoader.loadCertificate("invalid-corrupted-cert"));
    }
}
