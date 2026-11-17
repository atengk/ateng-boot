package io.github.atengk.boot.ftp.pool;

import io.github.atengk.boot.ftp.customizer.FtpClientCustomizer;
import io.github.atengk.boot.ftp.enums.FtpFileType;
import io.github.atengk.boot.ftp.enums.FtpMode;
import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.properties.FtpPoolProperties;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.util.TrustManagerUtils;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FTP 连接池工厂与生命周期治理单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("FTP 连接池工厂与生命周期治理单元测试")
class FtpClientPooledObjectFactoryTest {

    private static final String USERNAME = "testUser";
    private static final String PASSWORD = "testPassword";

    private FakeFtpServer fakeFtpServer;
    private FtpProperties properties;
    private FtpClientPooledObjectFactory factory;
    private GenericObjectPool<FTPClient> pool;

    @BeforeEach
    void setUp() {
        fakeFtpServer = new FakeFtpServer();
        fakeFtpServer.setServerControlPort(0); // 随机分配空闲本地端口
        fakeFtpServer.addUserAccount(new UserAccount(USERNAME, PASSWORD, "/"));

        FileSystem fileSystem = new UnixFakeFileSystem();
        fileSystem.add(new DirectoryEntry("/"));
        fileSystem.add(new DirectoryEntry("/data"));
        fileSystem.add(new DirectoryEntry("/data/tenantA"));
        fakeFtpServer.setFileSystem(fileSystem);

        fakeFtpServer.start();

        properties = new FtpProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(fakeFtpServer.getServerControlPort());
        properties.setUsername(USERNAME);
        properties.setPassword(PASSWORD);
        properties.setMode(FtpMode.PASSIVE);
        properties.setFileType(FtpFileType.BINARY);
        properties.setConnectTimeout(Duration.ofSeconds(10));
        properties.setDataTimeout(Duration.ofSeconds(10));
        properties.setControlKeepAliveTimeout(Duration.ofSeconds(10));

        FtpPoolProperties poolProps = properties.getPool();
        poolProps.setMaxTotal(4);
        poolProps.setMaxIdle(2);
        poolProps.setMinIdle(1);
        poolProps.setTestOnBorrow(true);

        factory = new FtpClientPooledObjectFactory(properties);

        GenericObjectPoolConfig<FTPClient> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(poolProps.getMaxTotal());
        config.setMaxIdle(poolProps.getMaxIdle());
        config.setMinIdle(poolProps.getMinIdle());
        config.setTestOnBorrow(poolProps.isTestOnBorrow());

        pool = new GenericObjectPool<>(factory, config);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
        if (fakeFtpServer != null) {
            fakeFtpServer.stop();
        }
    }

    @Test
    @DisplayName("测试连接池正常借出与归还生命周期")
    void testBorrowAndReturn() throws Exception {
        FTPClient client = pool.borrowObject();
        try {
            assertThat(client).isNotNull();
            assertThat(client.isConnected()).isTrue();
            assertThat(client.isAvailable()).isTrue();
            assertThat(client.printWorkingDirectory()).isEqualTo("/");
        } finally {
            pool.returnObject(client);
        }
        assertThat(pool.getNumIdle()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("测试连接池 passivateObject 强制重置工作目录到根路径防串目录")
    void testPassivateObjectResetsWorkingDirectory() throws Exception {
        // 1. 线程 A 借出连接并进入子目录 /data/tenantA
        FTPClient clientA = pool.borrowObject();
        try {
            boolean cwdSuccess = clientA.changeWorkingDirectory("/data/tenantA");
            assertThat(cwdSuccess).isTrue();
            assertThat(clientA.printWorkingDirectory()).isEqualTo("/data/tenantA");
        } finally {
            // 归还连接池，触发 passivateObject 强制重置 CWD 为 "/"
            pool.returnObject(clientA);
        }

        // 2. 线程 B 借出复用连接，断言工作目录必须严格重置为 "/"，严禁残留 "/data/tenantA"
        FTPClient clientB = pool.borrowObject();
        try {
            assertThat(clientB.printWorkingDirectory()).isEqualTo("/");
        } finally {
            pool.returnObject(clientB);
        }
    }

    @Test
    @DisplayName("测试连接池借出探针 validateObject 存活校验")
    void testValidateObject() throws Exception {
        FTPClient client = pool.borrowObject();
        try {
            DefaultPooledObject<FTPClient> pooledObject = new DefaultPooledObject<>(client);
            assertThat(factory.validateObject(pooledObject)).isTrue();

            // 主动断开连接，校验探针应识别为失败
            client.disconnect();
            assertThat(factory.validateObject(pooledObject)).isFalse();
        } finally {
            pool.invalidateObject(client);
        }
    }

    @Test
    @DisplayName("测试服务端鉴权失败抛出受管业务异常 FtpException")
    void testAuthFailedThrowsFtpException() {
        properties.setPassword("wrong_password");
        FtpClientPooledObjectFactory wrongFactory = new FtpClientPooledObjectFactory(properties);

        assertThatThrownBy(wrongFactory::create)
                .isInstanceOf(FtpException.class)
                .hasMessageContaining("FTP 鉴权登录失败")
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.AUTH_FAILED.getCode());
    }

    @Test
    @DisplayName("测试服务端不可达抛出受管业务异常 FtpException")
    void testConnectFailedThrowsFtpException() {
        properties.setPort(65530); // 不可达端口
        properties.setConnectTimeout(Duration.ofMillis(500));
        FtpClientPooledObjectFactory unreachableFactory = new FtpClientPooledObjectFactory(properties);

        assertThatThrownBy(unreachableFactory::create)
                .isInstanceOf(FtpException.class)
                .hasMessageContaining("创建 FTP 客户端连接失败")
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.CONNECT_FAILED.getCode());
    }

    @Test
    @DisplayName("测试连接对端断开后通过 testOnBorrow 自动剔除死连接并重建自愈")
    void testConnectionSelfHealingOnDisconnect() throws Exception {
        // 1. 借出连接并归还，确保连接留在池中作为空闲连接
        FTPClient client1 = pool.borrowObject();
        pool.returnObject(client1);
        assertThat(pool.getNumIdle()).isGreaterThanOrEqualTo(1);

        // 2. 模拟网络闪断或对端强行关闭底层连接（直接物理关闭底层 socket）
        client1.disconnect();
        assertThat(client1.isConnected()).isFalse();

        // 3. 再次借出连接：由于配置了 testOnBorrow=true，连接池探针将检测到 client1 已失效并销毁它，
        //    随后自动触发工厂 create() 重新创建全新健康连接
        FTPClient client2 = pool.borrowObject();
        try {
            assertThat(client2).isNotNull();
            assertThat(client2).isNotSameAs(client1);
            assertThat(client2.isConnected()).isTrue();
            assertThat(client2.isAvailable()).isTrue();
            assertThat(client2.sendNoOp()).isTrue();
        } finally {
            pool.returnObject(client2);
        }
    }

    @Test
    @DisplayName("测试 passivateObject 当重置根目录失败时抛出受管异常防卫受污连接")
    void testPassivateObjectFailureThrowsException() throws Exception {
        FTPClient mockClient = org.mockito.Mockito.mock(FTPClient.class);
        org.mockito.Mockito.when(mockClient.isConnected()).thenReturn(true);
        org.mockito.Mockito.when(mockClient.changeWorkingDirectory("/")).thenReturn(false);
        org.mockito.Mockito.when(mockClient.getReplyCode()).thenReturn(550);

        DefaultPooledObject<FTPClient> pooled = new DefaultPooledObject<>(mockClient);
        assertThatThrownBy(() -> factory.passivateObject(pooled))
                .isInstanceOf(FtpException.class)
                .hasMessageContaining("重置根目录失败")
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.OPERATION_FAILED.getCode());
    }

    @Test
    @DisplayName("测试 activateObject 在账号初始目录非根目录时借出前置强制复位为 /")
    void testActivateObjectResetsNonRootHomeDirectory() throws Exception {
        FakeFtpServer tenantServer = new FakeFtpServer();
        tenantServer.setServerControlPort(0);
        tenantServer.addUserAccount(new UserAccount("tenantUser", "tenantPass", "/data/tenantA"));
        FileSystem fs = new UnixFakeFileSystem();
        fs.add(new DirectoryEntry("/"));
        fs.add(new DirectoryEntry("/data"));
        fs.add(new DirectoryEntry("/data/tenantA"));
        tenantServer.setFileSystem(fs);
        tenantServer.start();

        FtpProperties tenantProps = new FtpProperties();
        tenantProps.setHost("127.0.0.1");
        tenantProps.setPort(tenantServer.getServerControlPort());
        tenantProps.setUsername("tenantUser");
        tenantProps.setPassword("tenantPass");
        tenantProps.setConnectTimeout(Duration.ofSeconds(3));
        tenantProps.setDataTimeout(Duration.ofSeconds(3));

        GenericObjectPoolConfig<FTPClient> tenantPoolConfig = new GenericObjectPoolConfig<>();
        tenantPoolConfig.setMaxTotal(2);
        GenericObjectPool<FTPClient> tenantPool = new GenericObjectPool<>(
                new FtpClientPooledObjectFactory(tenantProps), tenantPoolConfig);
        try {
            FTPClient client = tenantPool.borrowObject();
            try {
                assertThat(client.printWorkingDirectory()).isEqualTo("/");
            } finally {
                tenantPool.returnObject(client);
            }
        } finally {
            tenantPool.close();
            tenantServer.stop();
        }
    }

    @Test
    @DisplayName("测试 activateObject 当重置根目录失败时抛出 OPERATION_FAILED 受管异常")
    void testActivateObjectFailureThrowsException() throws Exception {
        FTPClient mockClient = org.mockito.Mockito.mock(FTPClient.class);
        org.mockito.Mockito.when(mockClient.isConnected()).thenReturn(true);
        org.mockito.Mockito.when(mockClient.changeWorkingDirectory("/")).thenReturn(false);
        org.mockito.Mockito.when(mockClient.getReplyCode()).thenReturn(550);

        DefaultPooledObject<FTPClient> pooled = new DefaultPooledObject<>(mockClient);
        assertThatThrownBy(() -> factory.activateObject(pooled))
                .isInstanceOf(FtpException.class)
                .hasMessageContaining("重置根目录失败")
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.OPERATION_FAILED.getCode());

        // 校验当设置传输数据类型返回 false 或 IOException 时同样抛出 OPERATION_FAILED
        org.mockito.Mockito.when(mockClient.changeWorkingDirectory("/")).thenReturn(true);
        org.mockito.Mockito.when(mockClient.setFileType(org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);
        assertThatThrownBy(() -> factory.activateObject(pooled))
                .isInstanceOf(FtpException.class)
                .hasMessageContaining("设置传输数据类型失败")
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.OPERATION_FAILED.getCode());

        org.mockito.Mockito.when(mockClient.changeWorkingDirectory("/")).thenThrow(new java.io.IOException("CWD I/O error"));
        assertThatThrownBy(() -> factory.passivateObject(pooled))
                .isInstanceOf(FtpException.class)
                .satisfies(e -> {
                    assertThat(((FtpException) e).getCode()).isEqualTo(FtpResultCode.OPERATION_FAILED.getCode());
                    assertThat(e.getCause()).isInstanceOf(java.io.IOException.class);
                });
    }

    @Test
    @DisplayName("测试 FTPS 客户端模式创建实例类型与安全策略正确性")
    void testFtpsClientCreation() {
        properties.getFtps().setEnabled(true);
        properties.getFtps().setProtocol("TLSv1.3");
        properties.getFtps().setImplicit(false);
        properties.getFtps().setTrustAll(true);

        FtpClientPooledObjectFactory ftpsFactory = new FtpClientPooledObjectFactory(properties);
        FTPClient client = ftpsFactory.createClientInstance();
        try {
            assertThat(client).isNotNull();
            assertThat(client).isInstanceOf(FTPSClient.class);

            // 验证未开启 FTPS 时为普通 FTPClient
            properties.getFtps().setEnabled(false);
            FtpClientPooledObjectFactory plainFactory = new FtpClientPooledObjectFactory(properties);
            FTPClient plainClient = plainFactory.createClientInstance();
            assertThat(plainClient).isNotInstanceOf(FTPSClient.class);
        } finally {
            if (client != null && client.isConnected()) {
                try {
                    client.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Test
    @DisplayName("测试 FTPS 加载 PKCS12/JKS 双向证书库与不存在路径快速失败")
    void testFtpsKeyStoreAndTrustStoreLoading(@TempDir Path tempDir) throws Exception {
        Path p12Path = tempDir.resolve("client.p12");
        Path jksPath = tempDir.resolve("client.jks");
        String storePass = "changeit123";

        generateSelfSignedKeyStore(p12Path, "PKCS12", storePass);
        generateSelfSignedKeyStore(jksPath, "JKS", storePass);

        // 1. PKCS12 KeyStore + TrustStore 真实加载
        properties.getFtps().setEnabled(true);
        properties.getFtps().setTrustAll(false);
        properties.getFtps().setTrustStorePath(p12Path.toAbsolutePath().toString());
        properties.getFtps().setTrustStorePassword(storePass);
        properties.getFtps().setKeyStorePath(p12Path.toAbsolutePath().toString());
        properties.getFtps().setKeyStorePassword(storePass);

        FtpClientPooledObjectFactory p12Factory = new FtpClientPooledObjectFactory(properties);
        FTPClient p12Client = p12Factory.createClientInstance();
        assertThat(p12Client).isInstanceOf(FTPSClient.class);
        FTPSClient p12Ftps = (FTPSClient) p12Client;
        assertThat(p12Ftps.getTrustManager()).isNotNull();
        assertThat(p12Ftps.getTrustManager()).isNotSameAs(TrustManagerUtils.getAcceptAllTrustManager());

        // 2. JKS 格式自动识别加载
        properties.getFtps().setTrustStorePath(jksPath.toUri().toString());
        properties.getFtps().setKeyStorePath(jksPath.toUri().toString());
        FtpClientPooledObjectFactory jksFactory = new FtpClientPooledObjectFactory(properties);
        FTPClient jksClient = jksFactory.createClientInstance();
        assertThat(jksClient).isInstanceOf(FTPSClient.class);
        assertThat(((FTPSClient) jksClient).getTrustManager()).isNotNull();

        // 3. 证书文件不存在快速失败抛出 INVALID_PATH
        properties.getFtps().setTrustStorePath(tempDir.resolve("missing.p12").toAbsolutePath().toString());
        FtpClientPooledObjectFactory missingFactory = new FtpClientPooledObjectFactory(properties);
        assertThatThrownBy(missingFactory::createClientInstance)
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.INVALID_PATH.getCode());

        // 4. 密码错误加载失败抛出 CONNECT_FAILED 并保留原始 cause
        properties.getFtps().setTrustStorePath(p12Path.toAbsolutePath().toString());
        properties.getFtps().setTrustStorePassword("wrong_pass");
        FtpClientPooledObjectFactory badPwdFactory = new FtpClientPooledObjectFactory(properties);
        assertThatThrownBy(badPwdFactory::createClientInstance)
                .isInstanceOf(FtpException.class)
                .satisfies(e -> {
                    assertThat(((FtpException) e).getCode()).isEqualTo(FtpResultCode.CONNECT_FAILED.getCode());
                    assertThat(e.getCause()).isNotNull();
                });
    }

    @Test
    @DisplayName("测试 serverTimeZone 绑定与多个 FtpClientCustomizer 按序回调")
    void testServerTimeZoneAndCustomizerInvocation() throws Exception {
        properties.setServerTimeZone("Asia/Shanghai");
        AtomicInteger invokedCount = new AtomicInteger(0);
        AtomicReference<String> capturedServerName = new AtomicReference<>();
        AtomicReference<FTPClient> capturedClient = new AtomicReference<>();
        List<String> invocationOrder = new ArrayList<>();

        FtpClientCustomizer firstCustomizer = (serverName, client, props) -> {
            invocationOrder.add("first");
            invokedCount.incrementAndGet();
            capturedServerName.set(serverName);
            capturedClient.set(client);
            client.setListHiddenFiles(true);
        };
        FtpClientCustomizer secondCustomizer = (serverName, client, props) -> {
            invocationOrder.add("second");
            invokedCount.incrementAndGet();
            client.setRemoteVerificationEnabled(false);
        };

        FtpClientPooledObjectFactory customFactory = new FtpClientPooledObjectFactory(
                "archive", properties, List.of(firstCustomizer, secondCustomizer));
        FTPClient client = customFactory.create();
        try {
            assertThat(invokedCount.get()).isEqualTo(2);
            assertThat(invocationOrder).containsExactly("first", "second");
            assertThat(capturedServerName.get()).isEqualTo("archive");
            assertThat(capturedClient.get()).isSameAs(client);
            assertThat(client.getListHiddenFiles()).isTrue();
            assertThat(client.isRemoteVerificationEnabled()).isFalse();
            assertThat(client.listFiles("/")).isNotNull();
        } finally {
            customFactory.destroyObject(new DefaultPooledObject<>(client));
        }
    }

    private void generateSelfSignedKeyStore(Path targetPath, String storeType, String password) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(
                keytool, "-genkeypair",
                "-alias", "ateng-ftp-test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=localhost,OU=Ateng,O=AtengBoot,C=CN",
                "-storetype", storeType,
                "-keystore", targetPath.toAbsolutePath().toString(),
                "-storepass", password,
                "-keypass", password
        ).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        assertThat(exitCode).withFailMessage("keytool failed: %s", output).isZero();
    }
}
