package io.github.atengk.boot.ssh.pool;

import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.properties.SshPoolProperties;
import io.github.atengk.boot.ssh.verifier.InMemoryServerKeyVerifier;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshSessionPool 受管会话缓存池单元与并发高压测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshSessionPool 会话缓存池单元与高并发测试")
class SshSessionPoolTest {

    private SshServer sshServer;
    private SshClient sshClient;
    private int serverPort;
    private final AtomicInteger serverAuthCount = new AtomicInteger(0);

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        serverAuthCount.set(0);

        // 启动嵌入式测试 SSH 服务端
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0);
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser")));
        sshServer.setPasswordAuthenticator((username, password, session) -> {
            serverAuthCount.incrementAndGet();
            return "pooluser".equals(username) && "poolpass".equals(password);
        });
        sshServer.start();
        serverPort = sshServer.getPort();

        // 启动客户端
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
    @DisplayName("测试握手复用：二次获取相同主机会话时握手开销归零且复用同一活会话")
    void testHandshakeReuseAndLatencyReduction() {
        SshPoolProperties poolProperties = new SshPoolProperties();
        try (SshSessionPool pool = new SshSessionPool(sshClient, poolProperties)) {
            SshServerInfo serverInfo = SshServerInfo.builder()
                    .host("127.0.0.1")
                    .port(serverPort)
                    .username("pooluser")
                    .password("poolpass")
                    .hostKeyPolicy(HostKeyPolicy.IGNORE)
                    .build();

            // 1. 首次建连：触发完整 TCP 握手与认证
            long start1 = System.nanoTime();
            ClientSession session1 = pool.acquire(serverInfo);
            long elapsed1 = System.nanoTime() - start1;

            assertThat(session1).isNotNull();
            assertThat(session1.isOpen()).isTrue();
            assertThat(session1.isAuthenticated()).isTrue();
            assertThat(serverAuthCount.get()).isEqualTo(1);
            assertThat(pool.getActiveSessionCount()).isEqualTo(1);

            // 2. 第二次获取：极速命中缓存，耗时骤降，服务端认证次数保持为 1
            long start2 = System.nanoTime();
            ClientSession session2 = pool.acquire(serverInfo);
            long elapsed2 = System.nanoTime() - start2;

            assertThat(session2).isSameAs(session1);
            assertThat(serverAuthCount.get()).isEqualTo(1); // 未产生二次鉴权握手
            assertThat(elapsed2).isLessThan(elapsed1);
        }
    }

    @Test
    @DisplayName("测试 execute 闭包安全执行与返回值获取")
    void testExecuteClosure() {
        SshPoolProperties poolProperties = new SshPoolProperties();
        try (SshSessionPool pool = new SshSessionPool(sshClient, poolProperties)) {
            SshServerInfo serverInfo = SshServerInfo.of("127.0.0.1", serverPort, "pooluser", "poolpass");

            String result = pool.execute(serverInfo, session -> {
                assertThat(session.isOpen()).isTrue();
                return "execute-ok";
            });

            assertThat(result).isEqualTo("execute-ok");
            assertThat(pool.getActiveSessionCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("测试 executeOnce 瞬态闭包：执行完毕会话立即物理断开，零池化残留")
    void testExecuteOnceClosureZeroRetention() {
        SshPoolProperties poolProperties = new SshPoolProperties();
        try (SshSessionPool pool = new SshSessionPool(sshClient, poolProperties)) {
            SshServerInfo serverInfo = SshServerInfo.of("127.0.0.1", serverPort, "pooluser", "poolpass");

            ClientSession[] capturedSession = new ClientSession[1];
            String result = pool.executeOnce(serverInfo, session -> {
                capturedSession[0] = session;
                assertThat(session.isOpen()).isTrue();
                return "transient-result";
            });

            assertThat(result).isEqualTo("transient-result");
            // 验证会话在方法退出后已被物理关闭断开
            assertThat(capturedSession[0]).isNotNull();
            assertThat(capturedSession[0].isOpen()).isFalse();
            // 验证池中没有任何残留
            assertThat(pool.getPoolSize()).isEqualTo(0);
            assertThat(pool.getActiveSessionCount()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("测试 LRU 容量淘汰机制：超出 maxSessions 时淘汰最久未活跃会话")
    void testLruEviction() {
        SshPoolProperties poolProperties = new SshPoolProperties();
        poolProperties.setMaxSessions(2); // 上限 2 个

        try (SshSessionPool pool = new SshSessionPool(sshClient, poolProperties)) {
            SshServerInfo server1 = SshServerInfo.of("127.0.0.1", serverPort, "pooluser", "poolpass");
            // 构造稍微不同的 Key 作为不同会话
            SshServerInfo server2 = SshServerInfo.builder()
                    .host("127.0.0.1")
                    .port(serverPort)
                    .username("pooluser")
                    .password("poolpass")
                    .passphrase("diff1")
                    .build();
            SshServerInfo server3 = SshServerInfo.builder()
                    .host("127.0.0.1")
                    .port(serverPort)
                    .username("pooluser")
                    .password("poolpass")
                    .passphrase("diff2")
                    .build();

            ClientSession session1 = pool.acquire(server1);
            ClientSession session2 = pool.acquire(server2);
            assertThat(pool.getPoolSize()).isEqualTo(2);

            // 再次访问 server1，使其最近被访问，成为 MRU；server2 变为 LRU
            pool.acquire(server1);

            // 插入 server3，触发 LRU 淘汰，server2 应被驱逐关闭
            ClientSession session3 = pool.acquire(server3);
            assertThat(pool.getPoolSize()).isLessThanOrEqualTo(2);
            assertThat(session2.isOpen()).isFalse(); // server2 被物理断开
            assertThat(session1.isOpen()).isTrue();
            assertThat(session3.isOpen()).isTrue();
        }
    }

    @Test
    @DisplayName("测试空闲超时淘汰：超过 idleTimeout 的会话自动过期并重建")
    void testIdleExpiration() throws Exception {
        SshPoolProperties poolProperties = new SshPoolProperties();
        poolProperties.setIdleTimeout(Duration.ofMillis(200)); // 200ms 空闲超时

        try (SshSessionPool pool = new SshSessionPool(sshClient, poolProperties)) {
            SshServerInfo serverInfo = SshServerInfo.of("127.0.0.1", serverPort, "pooluser", "poolpass");

            ClientSession session1 = pool.acquire(serverInfo);
            assertThat(session1.isOpen()).isTrue();
            assertThat(serverAuthCount.get()).isEqualTo(1);

            // 等待超过 200ms 空闲超时
            Thread.sleep(300);

            // 再次获取应检测到过期并建立全新会话
            ClientSession session2 = pool.acquire(serverInfo);
            assertThat(session2).isNotSameAs(session1);
            assertThat(serverAuthCount.get()).isEqualTo(2); // 产生二次认证
            assertThat(session1.isOpen()).isFalse(); // 旧会话被安全关闭
            assertThat(session2.isOpen()).isTrue();
        }
    }

    @Test
    @DisplayName("测试多线程并发建连防惊群高压测试：10 个线程并发获取相同主机会话安全无争用")
    void testConcurrentHighConcurrencyAcquire() throws Exception {
        SshPoolProperties poolProperties = new SshPoolProperties();
        int concurrency = 10;

        try (SshSessionPool pool = new SshSessionPool(sshClient, poolProperties)) {
            SshServerInfo serverInfo = SshServerInfo.of("127.0.0.1", serverPort, "pooluser", "poolpass");
            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            CountDownLatch readyLatch = new CountDownLatch(concurrency);
            CountDownLatch startLatch = new CountDownLatch(1);

            List<Future<ClientSession>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();
                    return pool.acquire(serverInfo);
                }));
            }

            readyLatch.await();
            startLatch.countDown(); // 10 个线程同时瞬间发起并发请求

            List<ClientSession> sessions = new ArrayList<>();
            for (Future<ClientSession> future : futures) {
                sessions.add(future.get());
            }

            executor.shutdown();

            // 验证所有并发线程获取到的全部是同一个复用的会话实例
            ClientSession baseSession = sessions.get(0);
            assertThat(baseSession.isOpen()).isTrue();
            for (ClientSession session : sessions) {
                assertThat(session).isSameAs(baseSession);
            }

            // 验证服务端只产生了 1 次认证建连（完美防惊群）
            assertThat(serverAuthCount.get()).isEqualTo(1);
            assertThat(pool.getActiveSessionCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("测试失效死会话并发探测与自动剔除重建：会话断开后多线程并发获取能够感知失效并平滑重建")
    void testDeadSessionEvictionAndRecreationOnAcquire() throws Exception {
        SshPoolProperties poolProperties = new SshPoolProperties();
        try (SshSessionPool pool = new SshSessionPool(sshClient, poolProperties)) {
            SshServerInfo serverInfo = SshServerInfo.of("127.0.0.1", serverPort, "pooluser", "poolpass");

            // 1. 首次获取健康会话
            ClientSession originalSession = pool.acquire(serverInfo);
            assertThat(originalSession.isOpen()).isTrue();
            assertThat(serverAuthCount.get()).isEqualTo(1);

            // 2. 模拟服务端断开或网络闪断导致的死会话 (物理关闭底层会话)
            originalSession.close(true);
            assertThat(originalSession.isOpen()).isFalse();

            // 3. 多线程并发再次获取
            int concurrency = 8;
            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            CountDownLatch latch = new CountDownLatch(concurrency);
            List<Future<ClientSession>> futures = new ArrayList<>();

            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        return pool.acquire(serverInfo);
                    } finally {
                        latch.countDown();
                    }
                }));
            }

            latch.await();
            executor.shutdown();

            List<ClientSession> newSessions = new ArrayList<>();
            for (Future<ClientSession> f : futures) {
                newSessions.add(f.get());
            }

            // 4. 验证旧死会话已被自动剔除，并发线程获取到了全新且一致的健康会话
            ClientSession renewedSession = newSessions.get(0);
            assertThat(renewedSession.isOpen()).isTrue();
            assertThat(renewedSession).isNotSameAs(originalSession);

            for (ClientSession session : newSessions) {
                assertThat(session).isSameAs(renewedSession);
            }

            // 认证计数递增为 2（只产生了一次平滑重建）
            assertThat(serverAuthCount.get()).isEqualTo(2);
            assertThat(pool.getActiveSessionCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("测试会话级 HostKeyPolicy 端到端校验生效：STRICT 严格模式未注册阻断 vs IGNORE 模式放行")
    void testHostKeyPolicyEnforcement() {
        // 创建配置了全局 InMemoryServerKeyVerifier 的客户端
        SshClient customClient = SshClient.setUpDefaultClient();
        InMemoryServerKeyVerifier verifier = new InMemoryServerKeyVerifier(HostKeyPolicy.LENIENT);
        customClient.setServerKeyVerifier(verifier);
        customClient.start();

        try (SshSessionPool pool = new SshSessionPool(customClient, new SshPoolProperties())) {
            // 1. 配置 STRICT 严格策略的主机描述符（未预先注册公钥，应阻断抛出异常）
            SshServerInfo strictServer = SshServerInfo.builder()
                    .host("127.0.0.1")
                    .port(serverPort)
                    .username("pooluser")
                    .password("poolpass")
                    .hostKeyPolicy(HostKeyPolicy.STRICT)
                    .build();

            assertThatThrownBy(() -> pool.acquire(strictServer))
                    .isInstanceOf(SshOperationException.class);

            // 2. 配置 IGNORE 策略的主机描述符（跳过指纹校验，宽容放行）
            SshServerInfo ignoreServer = SshServerInfo.builder()
                    .host("127.0.0.1")
                    .port(serverPort)
                    .username("pooluser")
                    .password("poolpass")
                    .hostKeyPolicy(HostKeyPolicy.IGNORE)
                    .build();

            ClientSession ignoreSession = pool.acquire(ignoreServer);
            assertThat(ignoreSession).isNotNull();
            assertThat(ignoreSession.isOpen()).isTrue();
        } finally {
            customClient.stop();
        }
    }

    @Test
    @DisplayName("测试 KeyPair 凭据哈希一致性：不同对象实例相同公私钥编码能够命中同一会话池缓存")
    void testKeyPairCredentialHashConsistency() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair kp = generator.generateKeyPair();

        // 构造两个相同密钥对内容但不同对象引用的 ServerInfo
        SshServerInfo server1 = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("pooluser")
                .keyPair(kp)
                .build();

        SshServerInfo server2 = SshServerInfo.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("pooluser")
                .keyPair(new KeyPair(kp.getPublic(), kp.getPrivate()))
                .build();

        // 验证两者计算出的 toPoolKey 100% 相同
        assertThat(server1.toPoolKey()).isEqualTo(server2.toPoolKey());
    }
}
