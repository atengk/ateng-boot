package io.github.atengk.boot.mail.registry;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.AuthMode;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.provider.MailAccessTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发信账号注册中心测试用例
 *
 * @author Ateng
 * @since 2026-10-22
 */
class MailAccountRegistryTest {

    @Test
    @DisplayName("测试账号注册与按名检索")
    void testRegisterAndGet() {
        MailAccountRegistry registry = new MailAccountRegistry("primary");
        assertEquals("primary", registry.getDefaultAccountName());
        assertEquals(0, registry.size());

        MailAccount account1 = MailAccount.builder().name("primary").host("smtp.primary.com").build();
        MailAccount account2 = MailAccount.builder().name("ops").host("smtp.ops.com").build();

        registry.register(account1);
        registry.register(account2);

        assertEquals(2, registry.size());
        assertTrue(registry.contains("primary"));
        assertTrue(registry.contains("ops"));
        assertFalse(registry.contains("marketing"));

        assertSame(account1, registry.get("primary"));
        assertSame(account2, registry.get("ops"));
        assertSame(account1, registry.getDefault());

        // 验证对称注销
        MailAccount removed = registry.unregister("ops");
        assertSame(account2, removed);
        assertEquals(1, registry.size());
        assertFalse(registry.contains("ops"));
        assertNull(registry.get("ops"));

        // 注销不存在的账号返回 null
        assertNull(registry.unregister("non-existent"));
        assertNull(registry.unregister(null));
    }

    @Test
    @DisplayName("测试空注册表获取默认账号抛出明确异常")
    void testGetDefaultWhenEmpty() {
        MailAccountRegistry registry = new MailAccountRegistry("default");
        assertThrows(MailSendException.class, registry::getDefault);
    }

    @Test
    @DisplayName("测试动态账号 LRU 缓存复用与容量上限淘汰")
    void testDynamicAccountLruCacheAndEviction() {
        // 创建最大容量为 2 的动态账号注册表
        MailAccountRegistry registry = new MailAccountRegistry("default", 2);
        assertEquals(2, registry.getMaxDynamicAccounts());
        assertEquals(0, registry.dynamicSize());

        MailAccount rawAccount1 = MailAccount.builder().name("tenant1").host("smtp.tenant1.com").username("t1").build();
        MailAccount rawAccount2 = MailAccount.builder().name("tenant2").host("smtp.tenant2.com").username("t2").build();
        MailAccount rawAccount3 = MailAccount.builder().name("tenant3").host("smtp.tenant3.com").username("t3").build();

        // 1. 注册 tenant1
        MailAccount managed1 = registry.getOrCreateDynamic(rawAccount1, null);
        assertNotNull(managed1.getSender());
        assertEquals(1, registry.dynamicSize());

        // 再次获取同一指纹账号，应直接复用底层 Sender
        MailAccount managed1Again = registry.getOrCreateDynamic(rawAccount1, null);
        assertSame(managed1.getSender(), managed1Again.getSender(), "相同指纹的动态账号应复用物理 Sender 实例");
        assertEquals(1, registry.dynamicSize());

        // 2. 注册 tenant2，达到容量 2
        MailAccount managed2 = registry.getOrCreateDynamic(rawAccount2, null);
        assertEquals(2, registry.dynamicSize());

        // 3. 访问 tenant1，使其变为最近使用 (LRU 访问顺序更新)
        registry.getOrCreateDynamic(rawAccount1, null);

        // 4. 注册 tenant3，触发 LRU 淘汰最久未访问的 tenant2
        MailAccount managed3 = registry.getOrCreateDynamic(rawAccount3, null);
        assertEquals(2, registry.dynamicSize(), "动态账号条目数不得超过配置的最大上限");

        // 验证 tenant2 已被淘汰，若再次访问会重新创建新 Sender
        MailAccount managed2New = registry.getOrCreateDynamic(rawAccount2, null);
        assertNotSame(managed2.getSender(), managed2New.getSender(), "被淘汰的动态账号重新进入时应重新分配 Sender");

        // 5. 测试清理
        registry.clearDynamic();
        assertEquals(0, registry.dynamicSize());
    }

    @Test
    @DisplayName("测试动态账号指纹计算纳管 properties 扩展配置，防御 Sender 碰撞误复用")
    void testDynamicAccountFingerprintIncludesProperties() {
        MailAccountRegistry registry = new MailAccountRegistry("default", 10);

        java.util.Map<String, String> propsA = java.util.Map.of("mail.smtp.timeout", "3000");
        java.util.Map<String, String> propsB = java.util.Map.of("mail.smtp.timeout", "8000");

        MailAccount accountA = MailAccount.builder()
                .host("smtp.shared.com")
                .port(465)
                .username("shared-user")
                .password("pwd")
                .properties(propsA)
                .build();

        MailAccount accountB = MailAccount.builder()
                .host("smtp.shared.com")
                .port(465)
                .username("shared-user")
                .password("pwd")
                .properties(propsB)
                .build();

        assertNotEquals(accountA.toFingerprint(), accountB.toFingerprint(), "不同 properties 的账号指纹必须互异");

        MailAccount managedA = registry.getOrCreateDynamic(accountA, null);
        MailAccount managedB = registry.getOrCreateDynamic(accountB, null);

        assertNotSame(managedA.getSender(), managedB.getSender(), "不同 properties 的动态账号严禁复用相同的物理 Sender");
        assertEquals(2, registry.dynamicSize());
    }

    @Test
    @DisplayName("测试动态账号按逻辑名称存取、检索与精准驱逐")
    void testDynamicAccountByNameAndEviction() {
        MailAccountRegistry registry = new MailAccountRegistry("default", 10);

        MailAccount rawAccount = MailAccount.builder()
                .host("smtp.dynamic.com")
                .port(465)
                .username("dynamic-user")
                .password("pwd")
                .build();

        // 初始不存在
        assertNull(registry.getDynamic("tenant-dynamic"));

        // 注册到动态缓存
        MailAccount managed = registry.getOrCreateDynamic("tenant-dynamic", rawAccount, null);
        assertNotNull(managed);
        assertNotNull(managed.getSender());
        assertEquals("tenant-dynamic", managed.getName());

        // 按名称检索
        MailAccount fetched = registry.getDynamic("tenant-dynamic");
        assertSame(managed, fetched);

        // 精准驱逐
        MailAccount evicted = registry.evictDynamic("tenant-dynamic");
        assertSame(managed, evicted);
        assertNull(registry.getDynamic("tenant-dynamic"));
    }

    @Test
    @DisplayName("测试 getDynamic 命中时同步刷新 LRU 访问序，避免热点账号被提前淘汰")
    void testGetDynamicRefreshesLruOrder() {
        MailAccountRegistry registry = new MailAccountRegistry("default", 2);

        MailAccount raw1 = MailAccount.builder().name("hot-alias").host("smtp.hot.com").username("hot").build();
        MailAccount raw2 = MailAccount.builder().name("cold-alias").host("smtp.cold.com").username("cold").build();
        MailAccount raw3 = MailAccount.builder().name("new-alias").host("smtp.new.com").username("new").build();

        MailAccount managed1 = registry.getOrCreateDynamic("hot-alias", raw1, null);
        MailAccount managed2 = registry.getOrCreateDynamic("cold-alias", raw2, null);

        // 仅通过 getDynamic 访问 hot-alias，应将其标记为最近使用
        MailAccount fetchedHot = registry.getDynamic("hot-alias");
        assertNotNull(fetchedHot);
        assertSame(managed1.getSender(), fetchedHot.getSender());

        // 注册第 3 个账号触发淘汰：应淘汰冷门的 cold-alias，而非 hot-alias
        registry.getOrCreateDynamic("new-alias", raw3, null);

        assertNotNull(registry.getDynamic("hot-alias"), "被 getDynamic 激活的热点账号应留在缓存中");
        assertNull(registry.getDynamic("cold-alias"), "冷门账号应被优先淘汰");
        MailAccount hotAgain = registry.getOrCreateDynamic("hot-alias", raw1, null);
        assertSame(managed1.getSender(), hotAgain.getSender(), "热点账号对应的物理 Sender 仍应被复用");
        MailAccount coldAgain = registry.getOrCreateDynamic("cold-alias", raw2, null);
        assertNotSame(managed2.getSender(), coldAgain.getSender());
    }

    @Test
    @DisplayName("测试多别名共享物理配置时驱逐单个别名不会误删共享 Sender 指纹缓存")
    void testEvictSharedFingerprintAliasPreservesSiblingSender() {
        MailAccountRegistry registry = new MailAccountRegistry("default", 10);

        MailAccount aliasA = MailAccount.builder()
                .name("brand-a")
                .host("smtp.shared-corp.com")
                .port(465)
                .username("relay@corp.com")
                .password("pwd")
                .defaultFrom("a@corp.com")
                .archiveBcc("audit-a@corp.com")
                .build();
        MailAccount aliasB = MailAccount.builder()
                .name("brand-b")
                .host("smtp.shared-corp.com")
                .port(465)
                .username("relay@corp.com")
                .password("pwd")
                .defaultFrom("b@corp.com")
                .archiveBcc("audit-b@corp.com")
                .build();

        MailAccount managedA = registry.getOrCreateDynamic("brand-a", aliasA, null);
        MailAccount managedB = registry.getOrCreateDynamic("brand-b", aliasB, null);
        assertSame(managedA.getSender(), managedB.getSender());

        // 驱逐 brand-a，brand-b 仍存活，共享物理 Sender 应保留可复用
        registry.evictDynamic("brand-a");
        assertNull(registry.getDynamic("brand-a"));
        assertNotNull(registry.getDynamic("brand-b"));

        MailAccount aliasC = MailAccount.builder()
                .name("brand-c")
                .host("smtp.shared-corp.com")
                .port(465)
                .username("relay@corp.com")
                .password("pwd")
                .defaultFrom("c@corp.com")
                .build();
        MailAccount managedC = registry.getOrCreateDynamic("brand-c", aliasC, null);
        assertSame(managedB.getSender(), managedC.getSender(), "兄弟别名仍存活时不应清除共享指纹对应的 Sender");

        // 全部别名驱逐完毕后，应释放指纹缓存，再次创建将分配新 Sender
        registry.evictDynamic("brand-b");
        registry.evictDynamic("brand-c");
        MailAccount managedAAfterFullEvict = registry.getOrCreateDynamic("brand-a", aliasA, null);
        assertNotSame(managedA.getSender(), managedAAfterFullEvict.getSender());
    }

    @Test
    @DisplayName("测试高压并发动态账号存取、驱逐与容量有界不变量（基于 ReentrantLock 无锁退化）")
    void testConcurrentDynamicOperations() throws Exception {
        MailAccountRegistry registry = new MailAccountRegistry("default", 8);
        int threads = 16;
        int opsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Callable<Void>> tasks = new ArrayList<>();

        try {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                tasks.add(() -> {
                    startLatch.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < opsPerThread; i++) {
                        int keyIdx = (threadId + i) % 16;
                        String name = "tenant-" + keyIdx;
                        MailAccount account = MailAccount.builder()
                                .name(name)
                                .host("smtp.tenant" + keyIdx + ".example.com")
                                .port(465)
                                .username("user" + keyIdx)
                                .password("pwd" + keyIdx)
                                .archiveBcc("audit" + keyIdx + "@example.com")
                                .build();
                        MailAccount managed = registry.getOrCreateDynamic(name, account, null);
                        assertNotNull(managed.getSender());
                        assertEquals("audit" + keyIdx + "@example.com", managed.getArchiveBcc());
                        registry.getDynamic(name);
                        if (i % 5 == 0) {
                            registry.evictDynamic(name);
                        }
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            startLatch.countDown();
            for (Future<Void> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
            assertTrue(registry.dynamicSize() <= 8, "并发高压场景下动态缓存尺寸不得突破上限 8");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("测试 testConnectionOrThrow(MailAccount) 构建临时发信器继承全局网络超时防止假死")
    void testConnectionOrThrowInheritsGlobalTimeouts() throws Exception {
        MailProperties globalProps = new MailProperties();
        globalProps.setConnectionTimeout(Duration.ofMillis(300));
        globalProps.setReadTimeout(Duration.ofMillis(200));
        globalProps.setWriteTimeout(Duration.ofMillis(300));
        MailAccountRegistry registry = new MailAccountRegistry("default", 10, globalProps);

        try (ServerSocket silentSmtp = new ServerSocket(0)) {
            int port = silentSmtp.getLocalPort();
            ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
            try {
                acceptExecutor.submit(() -> {
                    try (Socket accepted = silentSmtp.accept()) {
                        Thread.sleep(2000);
                    } catch (Exception ignored) {
                    }
                });

                MailAccount blackholeAccount = MailAccount.builder()
                        .name("blackhole")
                        .host("127.0.0.1")
                        .port(port)
                        .username("probe@example.com")
                        .password("pwd")
                        .ssl(false)
                        .starttls(false)
                        .build();

                long startNanos = System.nanoTime();
                MailSendException ex = assertThrows(MailSendException.class,
                        () -> registry.testConnectionOrThrow(blackholeAccount));
                long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

                assertEquals(MailResultCode.MAIL_CONNECT_TIMEOUT.getCode(), ex.getCode());
                assertTrue(elapsedMs < 1500, "探活应继承 200ms 全局读超时并快速失败，实际耗时: " + elapsedMs + "ms");
            } finally {
                acceptExecutor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("测试 AuthMode 认证模式切换、SSL 主机名校验与 sslTrust 覆写及指纹/withSender 继承")
    void testAuthModeAndSslIdentityVerification() {
        // 1. AuthMode.AUTO 无凭据自动关闭 SMTP 认证
        MailAccount relayAuto = MailAccount.builder()
                .name("relay-auto")
                .host("127.0.0.1")
                .port(25)
                .ssl(false)
                .build();
        JavaMailSenderImpl relaySender = MailAccountRegistry.createSender(relayAuto, null);
        assertEquals("false", relaySender.getJavaMailProperties().getProperty("mail.smtp.auth"));

        // 2. AuthMode.AUTO 有凭据开启 SMTP 认证
        MailAccount pwdAuto = MailAccount.builder()
                .name("pwd-auto")
                .host("smtp.example.com")
                .username("u@example.com")
                .password("p")
                .build();
        JavaMailSenderImpl pwdSender = MailAccountRegistry.createSender(pwdAuto, null);
        assertEquals("true", pwdSender.getJavaMailProperties().getProperty("mail.smtp.auth"));
        assertEquals("true", pwdSender.getJavaMailProperties().getProperty("mail.smtp.ssl.checkserveridentity"));

        // 3. AuthMode.NONE 即使配置了凭据也强制跳过认证
        MailAccount forcedNone = MailAccount.builder()
                .name("forced-none")
                .host("127.0.0.1")
                .port(25)
                .username("u@example.com")
                .password("p")
                .authMode(AuthMode.NONE)
                .ssl(false)
                .build();
        JavaMailSenderImpl noneSender = MailAccountRegistry.createSender(forcedNone, null);
        assertEquals("false", noneSender.getJavaMailProperties().getProperty("mail.smtp.auth"));
        assertNull(noneSender.getUsername());
        assertNull(noneSender.getPassword());

        // 4. ssl=true + sslTrust 覆写（指定域名保留主机名校验，通配符 "*" 适配自签名放行，properties 可显式覆盖）
        MailAccount trustedHost = MailAccount.builder()
                .name("trusted-host")
                .host("10.0.0.5")
                .ssl(true)
                .sslTrust("smtp.corp.local")
                .build();
        JavaMailSenderImpl trustedSender = MailAccountRegistry.createSender(trustedHost, null);
        assertEquals("smtp.corp.local", trustedSender.getJavaMailProperties().getProperty("mail.smtp.ssl.trust"));
        assertEquals("true", trustedSender.getJavaMailProperties().getProperty("mail.smtp.ssl.checkserveridentity"));

        MailAccount wildcardTrust = MailAccount.builder()
                .name("wildcard-trust")
                .host("10.0.0.6")
                .ssl(true)
                .sslTrust("*")
                .build();
        JavaMailSenderImpl wildcardSender = MailAccountRegistry.createSender(wildcardTrust, null);
        assertEquals("*", wildcardSender.getJavaMailProperties().getProperty("mail.smtp.ssl.trust"));
        assertEquals("false", wildcardSender.getJavaMailProperties().getProperty("mail.smtp.ssl.checkserveridentity"));

        // 5. withSender 与 toFingerprint 必须完整包含 authMode、accessToken 与 sslTrust
        MailAccount oauthAcc = MailAccount.builder()
                .name("oauth")
                .host("smtp.office365.com")
                .port(587)
                .username("u@corp.com")
                .authMode(AuthMode.XOAUTH2)
                .accessToken("tok-1")
                .ssl(false)
                .starttls(true)
                .sslTrust("smtp.office365.com")
                .build();
        MailAccount copied = oauthAcc.withSender(new JavaMailSenderImpl());
        assertEquals(AuthMode.XOAUTH2, copied.getAuthMode());
        assertEquals("tok-1", copied.getAccessToken());
        assertEquals("smtp.office365.com", copied.getSslTrust());
        assertEquals(oauthAcc.toFingerprint(), copied.toFingerprint());

        MailAccount diffToken = MailAccount.builder()
                .name("oauth")
                .host("smtp.office365.com")
                .port(587)
                .username("u@corp.com")
                .authMode(AuthMode.XOAUTH2)
                .accessToken("tok-2")
                .ssl(false)
                .starttls(true)
                .sslTrust("smtp.office365.com")
                .build();
        assertNotEquals(oauthAcc.toFingerprint(), diffToken.toFingerprint());
    }

    @Test
    @DisplayName("测试 XOAUTH2 探活自动注入 AccessToken 并在初次认证失败时刷新令牌重试一次")
    void testConnectionOAuth2TokenRefreshAndSingleRetry() {
        java.util.concurrent.atomic.AtomicInteger refreshCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger connectCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.List<String> observedTokens = new java.util.ArrayList<>();

        MailAccessTokenProvider tokenProvider = new MailAccessTokenProvider() {
            @Override
            public String getAccessToken(MailAccount account) {
                return "token-v1";
            }

            @Override
            public String refreshAccessToken(MailAccount account) {
                refreshCount.incrementAndGet();
                return "token-v2";
            }
        };

        MailAccountRegistry registry = new MailAccountRegistry("default", 10, new MailProperties(), tokenProvider);

        JavaMailSenderImpl mockSender = new JavaMailSenderImpl() {
            @Override
            public void testConnection() throws jakarta.mail.MessagingException {
                int call = connectCalls.incrementAndGet();
                observedTokens.add(getPassword());
                if (call == 1) {
                    throw new jakarta.mail.AuthenticationFailedException("expired token");
                }
            }
        };
        MailAccount oauthAccount = MailAccount.builder()
                .name("oauth-probe")
                .host("smtp.office365.com")
                .port(587)
                .username("bot@corp.com")
                .authMode(AuthMode.XOAUTH2)
                .sender(mockSender)
                .build();
        registry.register(oauthAccount);

        assertDoesNotThrow(() -> registry.testConnectionOrThrow("oauth-probe"));
        assertEquals(1, refreshCount.get());
        assertEquals(2, connectCalls.get());
        assertEquals(List.of("token-v1", "token-v2"), observedTokens);

        // 二次认证失败应收敛抛出 MAIL_AUTHENTICATION_ERROR 而不无限循环
        JavaMailSenderImpl alwaysFailSender = new JavaMailSenderImpl() {
            @Override
            public void testConnection() throws jakarta.mail.MessagingException {
                connectCalls.incrementAndGet();
                throw new jakarta.mail.AuthenticationFailedException("invalid token");
            }
        };
        MailAccount badOauth = MailAccount.builder()
                .name("oauth-bad")
                .host("smtp.office365.com")
                .port(587)
                .username("bot@corp.com")
                .authMode(AuthMode.XOAUTH2)
                .sender(alwaysFailSender)
                .build();
        registry.register(badOauth);
        connectCalls.set(0);
        refreshCount.set(0);

        MailSendException ex = assertThrows(MailSendException.class,
                () -> registry.testConnectionOrThrow("oauth-bad"));
        assertEquals(MailResultCode.MAIL_AUTHENTICATION_ERROR, ex.getResultCode());
        assertEquals(1, refreshCount.get());
        assertEquals(2, connectCalls.get());
    }

    @Test
    @DisplayName("测试探针严格隔离：testConnection 与 testConnectionOrThrow 仅探测目标账号，绝不触发 Failover")
    void testConnectionProbeStrictlyIsolatedWithoutFailover() {
        MailAccountRegistry registry = new MailAccountRegistry("primary");
        java.util.concurrent.atomic.AtomicInteger primaryCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger backupCalls = new java.util.concurrent.atomic.AtomicInteger(0);

        JavaMailSenderImpl failingPrimarySender = new JavaMailSenderImpl() {
            @Override
            public void testConnection() throws jakarta.mail.MessagingException {
                primaryCalls.incrementAndGet();
                throw new jakarta.mail.MessagingException("Connection timeout",
                        new java.net.SocketTimeoutException("Read timed out"));
            }
        };
        JavaMailSenderImpl healthyBackupSender = new JavaMailSenderImpl() {
            @Override
            public void testConnection() {
                backupCalls.incrementAndGet();
            }
        };

        MailAccount primary = MailAccount.builder()
                .name("primary")
                .host("smtp.primary.example.com")
                .port(465)
                .backupAccounts(List.of("healthy-backup"))
                .sender(failingPrimarySender)
                .build();
        MailAccount backup = MailAccount.builder()
                .name("healthy-backup")
                .host("smtp.backup.example.com")
                .port(465)
                .sender(healthyBackupSender)
                .build();

        assertEquals(List.of("healthy-backup"), primary.withSender(failingPrimarySender).getBackupAccounts());
        registry.register(primary);
        registry.register(backup);

        assertFalse(registry.testConnection("primary"));
        MailSendException probeEx = assertThrows(MailSendException.class,
                () -> registry.testConnectionOrThrow("primary"));
        assertEquals(MailResultCode.MAIL_CONNECT_TIMEOUT, probeEx.getResultCode());
        assertEquals(2, primaryCalls.get());
        assertEquals(0, backupCalls.get(), "主动连通性探针绝不允许顺延探测备用账号");
        assertTrue(registry.testConnection("healthy-backup"));
        assertEquals(1, backupCalls.get());
    }
}
