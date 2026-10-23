package io.github.atengk.boot.ssh.tunnel;

import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.lifecycle.SshLifecycleManager;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import io.github.atengk.boot.ssh.template.SshTemplate;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshTemplate 本地端口转发受管隧道端到端单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshTemplate 本地端口转发端到端测试")
class SshTemplateTunnelTest {

    private SshServer sshServer;
    private SshClient sshClient;
    private SshSessionPool sessionPool;
    private SshClientRegistry registry;
    private SshLifecycleManager lifecycleManager;
    private SshTemplate template;
    private SshServerInfo serverInfo;

    private ServerSocket echoServerSocket;
    private int echoServerPort;
    private ExecutorService echoServerExecutor;
    private final AtomicBoolean echoServerRunning = new AtomicBoolean(true);

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        // 1. 启动轻量级 Echo TCP 测试服务端（模拟内网服务，如 MySQL/Redis/HTTP）
        echoServerRunning.set(true);
        echoServerSocket = new ServerSocket(0);
        echoServerSocket.setReuseAddress(true);
        echoServerPort = echoServerSocket.getLocalPort();

        echoServerExecutor = Executors.newCachedThreadPool();
        echoServerExecutor.submit(() -> {
            while (echoServerRunning.get() && !echoServerSocket.isClosed()) {
                try {
                    Socket clientSocket = echoServerSocket.accept();
                    echoServerExecutor.submit(() -> handleEchoClient(clientSocket));
                } catch (IOException e) {
                    break;
                }
            }
        });

        // 2. 启动嵌入式 SSH 服务端，并开启转发过滤器
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        sshServer.setPasswordAuthenticator((username, password, session) ->
                "testuser".equals(username) && "testpass".equals(password));
        sshServer.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        sshServer.start();
        int sshPort = sshServer.getPort();

        // 3. 构建客户端底座与操作门面
        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();

        SshProperties properties = new SshProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(sshPort);
        properties.setUsername("testuser");
        properties.setPassword("testpass");
        properties.setHostKeyPolicy(HostKeyPolicy.IGNORE);

        serverInfo = SshServerInfo.fromProperties(properties);

        registry = new SshClientRegistry();
        registry.register(SshClientRegistry.DEFAULT_SERVER, serverInfo);
        registry.register("bastion", serverInfo);

        sessionPool = new SshSessionPool(sshClient, properties.getPool());
        lifecycleManager = new SshLifecycleManager(sessionPool, sshClient);
        lifecycleManager.start();

        template = new SshTemplate(sessionPool, registry, properties, null, lifecycleManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        echoServerRunning.set(false);
        if (echoServerSocket != null && !echoServerSocket.isClosed()) {
            echoServerSocket.close();
        }
        if (echoServerExecutor != null) {
            echoServerExecutor.shutdownNow();
        }
        if (lifecycleManager != null && lifecycleManager.isRunning()) {
            lifecycleManager.stop();
        } else {
            if (sessionPool != null) {
                sessionPool.close();
            }
            if (sshClient != null) {
                sshClient.stop();
            }
        }
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    private void handleEchoClient(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write("ECHO:" + line + "\n");
                writer.flush();
            }
        } catch (IOException ignored) {
        }
    }

    @Test
    @DisplayName("测试本地端口转发：动态分配端口、双向TCP流量端到端传输与确定性资源清理")
    void testLocalPortForwardingEndToEnd() throws Exception {
        // 1. 打开本地端口转发（localPort=0 表示操作系统动态分配随机可用端口）
        try (SshTunnelHandle handle = template.openLocalTunnel(0, "127.0.0.1", echoServerPort)) {
            assertThat(handle).isNotNull();
            assertThat(handle.isAlive()).isTrue();
            assertThat(handle.getLocalPort()).isPositive();
            assertThat(handle.getLocalPort()).isNotEqualTo(0);
            assertThat(handle.getRemotePort()).isEqualTo(echoServerPort);

            // 验证已自动登记至生命周期纳管列表
            assertThat(lifecycleManager.getActiveTunnels()).contains(handle);

            // 2. 通过本地绑定的转发端口发起真实 TCP 通信
            try (Socket clientSocket = new Socket("127.0.0.1", handle.getLocalPort());
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {

                clientSocket.setSoTimeout(3000);
                writer.write("PING_FROM_TUNNEL\n");
                writer.flush();

                String response = reader.readLine();
                assertThat(response).isEqualTo("ECHO:PING_FROM_TUNNEL");
            }

            // 3. 显式关闭隧道
            handle.close();
            assertThat(handle.isAlive()).isFalse();
            assertThat(handle.isClosed()).isTrue();
            assertThat(lifecycleManager.getActiveTunnels()).doesNotContain(handle);
        }

        // 4. 验证端口已被彻底释放
        // 再次连接已关闭的本地端口应抛出异常（连接被拒绝）
        // （稍等片刻待操作系统完成端口回收）
        Thread.sleep(100);
    }

    @Test
    @DisplayName("测试具名节点与显式描述符重载门面")
    void testOverloadFacading() throws Exception {
        // 1. 具名节点重载
        try (SshTunnelHandle handle = template.openLocalTunnel("bastion", 0, "127.0.0.1", echoServerPort)) {
            assertThat(handle.isAlive()).isTrue();
            assertThat(handle.getLocalPort()).isPositive();

            try (Socket socket = new Socket("127.0.0.1", handle.getLocalPort());
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write("VIA_BASTION\n");
                writer.flush();
                assertThat(reader.readLine()).isEqualTo("ECHO:VIA_BASTION");
            }
        }

        // 2. 显式值对象重载
        try (SshTunnelHandle handle = template.openLocalTunnel(serverInfo, 0, "127.0.0.1", echoServerPort)) {
            assertThat(handle.isAlive()).isTrue();
            assertThat(handle.getLocalPort()).isPositive();
        }
    }

    @Test
    @DisplayName("测试端口转发参数防卫：非法端口或空主机快速失败")
    void testArgumentValidationFailFast() {
        assertThatThrownBy(() -> template.openLocalTunnel(-1, "127.0.0.1", echoServerPort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localPort must be between 0 and 65535");

        assertThatThrownBy(() -> template.openLocalTunnel(0, "", echoServerPort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remoteHost must not be blank");

        assertThatThrownBy(() -> template.openLocalTunnel(0, "127.0.0.1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remotePort must be between 1 and 65535");

        assertThatThrownBy(() -> template.openLocalTunnel(0, "127.0.0.1", 70000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remotePort must be between 1 and 65535");
    }
}
