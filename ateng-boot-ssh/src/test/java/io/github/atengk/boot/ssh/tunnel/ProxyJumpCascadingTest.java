package io.github.atengk.boot.ssh.tunnel;

import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshPoolProperties;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import io.github.atengk.boot.ssh.template.SshTemplate;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProxyJump 跳板机与级联多跳穿透端到端单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("ProxyJump 跳板机与多跳级联穿透单元测试")
class ProxyJumpCascadingTest {

    private SshServer bastion1;
    private SshServer bastion2;
    private SshServer targetServer;

    private int bastion1Port;
    private int bastion2Port;
    private int targetPort;

    private SshClient sshClient;
    private SshSessionPool sessionPool;
    private SshTemplate template;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        // 1. 启动第一级跳板机 (Bastion 1)
        bastion1 = SshServer.setUpDefaultServer();
        bastion1.setHost("127.0.0.1");
        bastion1.setPort(0);
        bastion1.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("bastion1_key.ser")));
        bastion1.setPasswordAuthenticator((username, password, session) ->
                "jump1_user".equals(username) && "jump1_pass".equals(password));
        bastion1.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        bastion1.start();
        bastion1Port = bastion1.getPort();

        // 2. 启动第二级跳板机 (Bastion 2)
        bastion2 = SshServer.setUpDefaultServer();
        bastion2.setHost("127.0.0.1");
        bastion2.setPort(0);
        bastion2.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("bastion2_key.ser")));
        bastion2.setPasswordAuthenticator((username, password, session) ->
                "jump2_user".equals(username) && "jump2_pass".equals(password));
        bastion2.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        bastion2.start();
        bastion2Port = bastion2.getPort();

        // 3. 启动内网隔离的最终目标服务器 (Target Server)
        targetServer = SshServer.setUpDefaultServer();
        targetServer.setHost("127.0.0.1");
        targetServer.setPort(0);
        targetServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("target_key.ser")));
        targetServer.setPasswordAuthenticator((username, password, session) ->
                "target_user".equals(username) && "target_pass".equals(password));
        targetServer.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        targetServer.setCommandFactory((channel, command) -> new Command() {
            private OutputStream out;
            private ExitCallback callback;

            @Override
            public void setOutputStream(OutputStream out) {
                this.out = out;
            }

            @Override
            public void setInputStream(InputStream in) {
            }

            @Override
            public void setErrorStream(OutputStream err) {
            }

            @Override
            public void setExitCallback(ExitCallback callback) {
                this.callback = callback;
            }

            @Override
            public void start(ChannelSession channel, Environment env) throws IOException {
                if (out != null) {
                    if (command.startsWith("echo ")) {
                        String payload = command.substring(5).trim();
                        out.write((payload + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else {
                        out.write(("EXECUTED: " + command + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
                if (callback != null) {
                    callback.onExit(0);
                }
            }

            @Override
            public void destroy(ChannelSession channel) {
            }
        });
        targetServer.start();
        targetPort = targetServer.getPort();

        // 4. 初始化客户端与门面
        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();

        SshProperties properties = new SshProperties();
        SshClientRegistry registry = new SshClientRegistry();
        sessionPool = new SshSessionPool(sshClient, new SshPoolProperties());
        template = new SshTemplate(sessionPool, registry, properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (sessionPool != null) {
            sessionPool.close();
        }
        if (sshClient != null) {
            sshClient.stop();
        }
        if (targetServer != null) {
            targetServer.stop(true);
        }
        if (bastion2 != null) {
            bastion2.stop(true);
        }
        if (bastion1 != null) {
            bastion1.stop(true);
        }
    }

    @Test
    @DisplayName("测试单跳 ProxyJump：通过跳板机1穿透访问目标服务器并执行远程命令")
    void testSingleHopProxyJump() {
        SshServerInfo jump1 = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(bastion1Port)
                .username("jump1_user")
                .password("jump1_pass")
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        SshServerInfo target = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(targetPort)
                .username("target_user")
                .password("target_pass")
                .jumpHost(jump1)
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        SshExecResult result = template.exec(target, "echo SINGLE_JUMP_SUCCESS");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout()).isEqualTo("SINGLE_JUMP_SUCCESS\n");
    }

    @Test
    @DisplayName("测试两级 ProxyJump 级联穿透：Client -> Jump1 -> Jump2 -> Target")
    void testTwoHopCascadingProxyJump() {
        SshServerInfo jump1 = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(bastion1Port)
                .username("jump1_user")
                .password("jump1_pass")
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        SshServerInfo jump2 = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(bastion2Port)
                .username("jump2_user")
                .password("jump2_pass")
                .jumpHost(jump1)
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        SshServerInfo target = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(targetPort)
                .username("target_user")
                .password("target_pass")
                .jumpHost(jump2)
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        SshExecResult result = template.exec(target, "echo CASCADE_TWO_HOP_SUCCESS");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout()).isEqualTo("CASCADE_TWO_HOP_SUCCESS\n");
    }

    @Test
    @DisplayName("测试跳板机故障对抗用例：跳板机凭据错误阻断并保留因果链")
    void testBastionAuthFailureAdversarial() {
        SshServerInfo badJump = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(bastion1Port)
                .username("jump1_user")
                .password("WRONG_PASSWORD")
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        SshServerInfo target = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(targetPort)
                .username("target_user")
                .password("target_pass")
                .jumpHost(badJump)
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        assertThatThrownBy(() -> template.exec(target, "echo SHOULD_FAIL"))
                .isInstanceOf(SshOperationException.class)
                .hasMessageContaining("SSH 身份凭据认证失败");
    }

    @Test
    @DisplayName("测试瞬态单次任务在跳板机拓扑下的物理清理")
    void testExecOnceWithProxyJump() {
        SshServerInfo jump1 = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(bastion1Port)
                .username("jump1_user")
                .password("jump1_pass")
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        SshServerInfo target = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(targetPort)
                .username("target_user")
                .password("target_pass")
                .jumpHost(jump1)
                .hostKeyPolicy(HostKeyPolicy.IGNORE)
                .build();

        SshExecResult result = template.execOnce(target, "echo ONCE_WITH_JUMP");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout()).isEqualTo("ONCE_WITH_JUMP\n");
    }
}
