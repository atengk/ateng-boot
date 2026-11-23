package io.github.atengk.boot.http.util;

import io.github.atengk.boot.http.properties.HttpSslProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManager;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HttpSslUtil SSL 与 mTLS 双向认证证书工具类单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class HttpSslUtilTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("loadKeyStore 与 loadKeyManagers: 正常加载 PKCS12 密钥库并初始化密钥管理器")
    void shouldLoadKeyStoreAndKeyManagersSuccessfully() throws Exception {
        Path p12File = tempDir.resolve("test-client-cert.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, "cert_password".toCharArray());
        try (OutputStream out = Files.newOutputStream(p12File)) {
            ks.store(out, "cert_password".toCharArray());
        }

        HttpSslProperties ssl = new HttpSslProperties();
        ssl.setKeyStore(p12File.toUri().toString());
        ssl.setKeyStorePassword("cert_password");
        ssl.setKeyStoreType("PKCS12");

        KeyStore loadedKs = HttpSslUtil.loadKeyStore(ssl);
        assertNotNull(loadedKs);
        assertEquals("PKCS12", loadedKs.getType());

        KeyManager[] keyManagers = HttpSslUtil.loadKeyManagers(ssl);
        assertNotNull(keyManagers);
        assertTrue(keyManagers.length > 0);
    }

    @Test
    @DisplayName("防御性断言: 当证书文件不存在时快速失败抛出 IllegalArgumentException")
    void shouldThrowExceptionWhenCertificateFileNotFound() {
        HttpSslProperties ssl = new HttpSslProperties();
        ssl.setKeyStore("classpath:certs/nonexistent_cert.p12");
        ssl.setKeyStorePassword("password");

        assertThrows(IllegalArgumentException.class, () -> HttpSslUtil.loadKeyStore(ssl));
        assertThrows(IllegalArgumentException.class, () -> HttpSslUtil.loadKeyManagers(ssl));
    }

    @Test
    @DisplayName("防御性断言: 当入参为空时快速失败抛出异常")
    void shouldThrowExceptionWhenInputIsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> HttpSslUtil.loadKeyStore(null));

        HttpSslProperties emptyPathSsl = new HttpSslProperties();
        assertThrows(IllegalArgumentException.class, () -> HttpSslUtil.loadKeyStore(emptyPathSsl));
    }
}
