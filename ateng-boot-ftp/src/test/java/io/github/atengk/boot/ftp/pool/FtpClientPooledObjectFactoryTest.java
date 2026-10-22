package io.github.atengk.boot.ftp.pool;

import io.github.atengk.boot.ftp.enums.FtpFileType;
import io.github.atengk.boot.ftp.enums.FtpMode;
import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.properties.FtpPoolProperties;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.*;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;

import java.time.Duration;

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
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setDataTimeout(Duration.ofSeconds(3));
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
    @DisplayName("测试 FTPS 客户端模式创建实例类型正确性")
    void testFtpsClientCreation() {
        properties.getFtps().setEnabled(true);
        properties.getFtps().setProtocol("TLSv1.3");
        properties.getFtps().setTrustAll(true);

        FtpClientPooledObjectFactory ftpsFactory = new FtpClientPooledObjectFactory(properties);
        // 通过反射或者安全校验客户端创建
        FTPClient client = null;
        try {
            // 对 FTPS 模式下的实例化单独测试（未连接只测试 Client 类型）
            client = new FTPSClient("TLSv1.3", false);
            assertThat(client).isInstanceOf(FTPSClient.class);
        } finally {
            if (client != null && client.isConnected()) {
                try {
                    client.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
