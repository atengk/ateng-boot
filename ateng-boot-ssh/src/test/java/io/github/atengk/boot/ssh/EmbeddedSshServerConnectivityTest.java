package io.github.atengk.boot.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基于嵌入式 SshServer 的离线自洽连通与认证单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("嵌入式 SSH 离线连通与认证单元测试")
class EmbeddedSshServerConnectivityTest {

    private SshServer sshServer;
    private SshClient sshClient;
    private int serverPort;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        // 1. 启动嵌入式内存 SSH 服务端
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        sshServer.setPasswordAuthenticator((username, password, session) ->
                "testuser".equals(username) && "testpassword".equals(password));
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
    @DisplayName("测试使用正确账号密码能够成功完成 SSH 握手并认证通过")
    void testSuccessfulAuthentication() throws IOException {
        try (ClientSession session = sshClient.connect("testuser", "127.0.0.1", serverPort)
                .verify(Duration.ofSeconds(5))
                .getSession()) {
            session.addPasswordIdentity("testpassword");
            session.auth().verify(Duration.ofSeconds(5));

            assertThat(session.isAuthenticated()).isTrue();
            assertThat(session.isOpen()).isTrue();
        }
    }

    @Test
    @DisplayName("测试使用错误密码时认证失败抛出异常")
    void testFailedAuthentication() throws IOException {
        try (ClientSession session = sshClient.connect("testuser", "127.0.0.1", serverPort)
                .verify(Duration.ofSeconds(5))
                .getSession()) {
            session.addPasswordIdentity("wrongpassword");

            assertThatThrownBy(() -> session.auth().verify(Duration.ofSeconds(5)))
                    .isInstanceOf(IOException.class);
            assertThat(session.isAuthenticated()).isFalse();
        }
    }
}
