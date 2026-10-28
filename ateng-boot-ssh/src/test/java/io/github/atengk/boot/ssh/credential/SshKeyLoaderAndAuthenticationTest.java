package io.github.atengk.boot.ssh.credential;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSH 多元凭据解析与纯内存私钥端到端认证单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SSH 多元凭据解析与纯内存私钥端到端认证单元测试")
class SshKeyLoaderAndAuthenticationTest {

    private SshServer sshServer;
    private SshClient sshClient;
    private int serverPort;
    private KeyPair authorizedKeyPair;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException, GeneralSecurityException {
        authorizedKeyPair = generateRsaKeyPair();

        // 1. 启动嵌入式内存 SSH 服务端，支持口令与公钥双重认证
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));

        sshServer.setPasswordAuthenticator((username, password, session) ->
                "passuser".equals(username) && "pass123".equals(password));

        sshServer.setPublickeyAuthenticator((username, key, session) ->
                "keyuser".equals(username) && KeyUtils.compareKeys(authorizedKeyPair.getPublic(), key));

        sshServer.start();
        serverPort = sshServer.getPort();

        // 2. 启动客户端
        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (sshClient != null) {
            sshClient.stop();
        }
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    @Test
    @DisplayName("测试从纯内存 PEM 字符串解析密钥对（零磁盘文件）")
    void testLoadKeyPairsFromMemory() {
        String pem = toPkcs8Pem(authorizedKeyPair);

        List<KeyPair> keyPairs = SshKeyLoader.loadKeyPairsFromMemory(pem, null);
        assertThat(keyPairs).hasSize(1);
        assertThat(KeyUtils.compareKeys(keyPairs.get(0).getPublic(), authorizedKeyPair.getPublic())).isTrue();

        KeyPair singleKey = SshKeyLoader.loadKeyPairFromMemory(pem, null);
        assertThat(KeyUtils.compareKeys(singleKey.getPublic(), authorizedKeyPair.getPublic())).isTrue();

        // 空内容保底返回空列表
        assertThat(SshKeyLoader.loadKeyPairsFromMemory(null, null)).isEmpty();
        assertThat(SshKeyLoader.loadKeyPairsFromMemory("   ", null)).isEmpty();
    }

    @Test
    @DisplayName("测试非法或损坏的纯内存私钥内容抛出 AUTH_FAILED 异常")
    void testCorruptedPrivateKeyThrowsException() {
        String invalidPem = "-----BEGIN PRIVATE KEY-----\nINVALID_CORRUPTED_BASE64\n-----END PRIVATE KEY-----";

        assertThatThrownBy(() -> SshKeyLoader.loadKeyPairsFromMemory(invalidPem, null))
                .isInstanceOf(SshOperationException.class)
                .satisfies(ex -> {
                    SshOperationException sshEx = (SshOperationException) ex;
                    assertThat(sshEx.getResultCode()).isEqualTo(SshResultCode.AUTH_FAILED);
                });
    }

    @Test
    @DisplayName("测试从磁盘路径加载私钥及非法路径防卫")
    void testLoadKeyPairsFromPath(@TempDir Path tempDir) throws IOException {
        Path keyFile = tempDir.resolve("id_rsa");
        Files.writeString(keyFile, toPkcs8Pem(authorizedKeyPair));

        List<KeyPair> keyPairs = SshKeyLoader.loadKeyPairsFromPath(keyFile, null);
        assertThat(keyPairs).hasSize(1);
        assertThat(KeyUtils.compareKeys(keyPairs.get(0).getPublic(), authorizedKeyPair.getPublic())).isTrue();

        // 不存在的文件抛出异常
        Path nonExistent = tempDir.resolve("missing_key");
        assertThatThrownBy(() -> SshKeyLoader.loadKeyPairsFromPath(nonExistent, null))
                .isInstanceOf(SshOperationException.class)
                .satisfies(ex -> {
                    SshOperationException sshEx = (SshOperationException) ex;
                    assertThat(sshEx.getResultCode()).isEqualTo(SshResultCode.AUTH_FAILED);
                });
    }

    @Test
    @DisplayName("测试端到端认证：使用纯内存 PEM 私钥字符串成功完成 SSH 鉴权登录")
    void testEndToEndAuthenticationWithInMemoryPrivateKey() throws IOException {
        String pem = toPkcs8Pem(authorizedKeyPair);

        SshServerInfo serverInfo = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("keyuser")
                .privateKey(pem)
                .build();

        try (ClientSession session = sshClient.connect(serverInfo.getUsername(), serverInfo.getHost(), serverInfo.getPort())
                .verify(Duration.ofSeconds(5))
                .getSession()) {

            SshKeyLoader.applyCredentials(session, serverInfo);
            session.auth().verify(Duration.ofSeconds(5));

            assertThat(session.isAuthenticated()).isTrue();
            assertThat(session.isOpen()).isTrue();
        }
    }

    @Test
    @DisplayName("测试端到端认证：使用预解析 KeyPair 成功完成 SSH 鉴权登录")
    void testEndToEndAuthenticationWithPreParsedKeyPair() throws IOException {
        SshServerInfo serverInfo = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("keyuser")
                .keyPair(authorizedKeyPair)
                .build();

        try (ClientSession session = sshClient.connect(serverInfo.getUsername(), serverInfo.getHost(), serverInfo.getPort())
                .verify(Duration.ofSeconds(5))
                .getSession()) {

            SshKeyLoader.applyCredentials(session, serverInfo);
            session.auth().verify(Duration.ofSeconds(5));

            assertThat(session.isAuthenticated()).isTrue();
            assertThat(session.isOpen()).isTrue();
        }
    }

    @Test
    @DisplayName("测试端到端认证：使用错误私钥时认证失败阻断")
    void testEndToEndAuthenticationWithWrongPrivateKeyFails() throws Exception {
        KeyPair unauthorizedKeyPair = generateRsaKeyPair();
        String wrongPem = toPkcs8Pem(unauthorizedKeyPair);

        SshServerInfo serverInfo = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("keyuser")
                .privateKey(wrongPem)
                .build();

        try (ClientSession session = sshClient.connect(serverInfo.getUsername(), serverInfo.getHost(), serverInfo.getPort())
                .verify(Duration.ofSeconds(5))
                .getSession()) {

            SshKeyLoader.applyCredentials(session, serverInfo);
            assertThatThrownBy(() -> session.auth().verify(Duration.ofSeconds(5)))
                    .isInstanceOf(IOException.class);

            assertThat(session.isAuthenticated()).isFalse();
        }
    }

    private static KeyPair generateRsaKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String toPkcs8Pem(KeyPair keyPair) {
        byte[] encoded = keyPair.getPrivate().getEncoded();
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }
}
