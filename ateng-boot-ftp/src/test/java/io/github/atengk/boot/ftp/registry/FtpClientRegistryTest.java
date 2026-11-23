package io.github.atengk.boot.ftp.registry;

import io.github.atengk.boot.ftp.context.FtpContextHolder;
import io.github.atengk.boot.ftp.customizer.FtpClientCustomizer;
import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.model.FtpPoolStats;
import io.github.atengk.boot.ftp.pool.FtpClientPooledObjectFactory;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * FtpClientRegistry 多节点注册表单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("FtpClientRegistry 多节点注册表单元测试")
class FtpClientRegistryTest {

    @AfterEach
    void tearDown() {
        FtpContextHolder.clear();
    }

    @Test
    @DisplayName("测试具名节点注册、寻址与默认兜底回退")
    @SuppressWarnings("unchecked")
    void testRegisterAndRetrieve() {
        FtpClientRegistry registry = new FtpClientRegistry();

        ObjectPool<FTPClient> defaultPool = Mockito.mock(ObjectPool.class);
        FtpProperties defaultProps = new FtpProperties();
        FtpTemplate defaultTemplate = new FtpTemplate(defaultPool, defaultProps, registry, FtpClientRegistry.DEFAULT_SERVER);

        ObjectPool<FTPClient> archivePool = Mockito.mock(ObjectPool.class);
        FtpProperties archiveProps = new FtpProperties();
        FtpTemplate archiveTemplate = new FtpTemplate(archivePool, archiveProps, registry, "archive");

        registry.register(FtpClientRegistry.DEFAULT_SERVER, defaultTemplate, defaultPool);
        registry.register("archive", archiveTemplate, archivePool);

        // 验证精确寻址
        assertThat(registry.getTemplate("archive")).isSameAs(archiveTemplate);
        assertThat(registry.getPool("archive")).isSameAs(archivePool);

        // 验证默认寻址与空回退
        assertThat(registry.getDefaultTemplate()).isSameAs(defaultTemplate);
        assertThat(registry.getTemplate(null)).isSameAs(defaultTemplate);
        assertThat(registry.getTemplate("")).isSameAs(defaultTemplate);
        assertThat(registry.getTemplate("   ")).isSameAs(defaultTemplate);

        // 验证节点集合与包含性
        assertThat(registry.contains("archive")).isTrue();
        assertThat(registry.contains("not_exist")).isFalse();
        assertThat(registry.getServerNames()).containsExactlyInAnyOrder("default", "archive");
    }

    @Test
    @DisplayName("测试获取未注册服务节点抛出 SERVER_NOT_FOUND 异常")
    void testGetNonExistentServer() {
        FtpClientRegistry registry = new FtpClientRegistry();

        assertThatThrownBy(() -> registry.getTemplate("unknown_node"))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());

        assertThatThrownBy(() -> registry.getPool("unknown_node"))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("测试 close 回调确定性关闭释放所有底层连接池")
    @SuppressWarnings("unchecked")
    void testCloseDestroysAllPools() {
        FtpClientRegistry registry = new FtpClientRegistry();

        ObjectPool<FTPClient> pool1 = Mockito.mock(ObjectPool.class);
        ObjectPool<FTPClient> pool2 = Mockito.mock(ObjectPool.class);

        FtpTemplate template1 = new FtpTemplate(pool1, new FtpProperties(), registry, "default");
        FtpTemplate template2 = new FtpTemplate(pool2, new FtpProperties(), registry, "node2");

        registry.register("default", template1, pool1);
        registry.register("node2", template2, pool2);

        registry.close();

        verify(pool1).close();
        verify(pool2).close();
        assertThat(registry.getServerNames()).isEmpty();
    }

    @Test
    @DisplayName("测试空注册表获取默认模板或连接池抛出受管异常")
    void testEmptyRegistryThrowsException() {
        FtpClientRegistry registry = new FtpClientRegistry();

        assertThatThrownBy(registry::getDefaultTemplate)
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());

        assertThatThrownBy(registry::getDefaultPool)
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("测试通过 FtpClientRegistry 与 FtpTemplate 获取强类型 FtpPoolStats 运行指标快照")
    void testGetPoolStatsAndGetAllPoolStats() {
        FtpClientRegistry registry = new FtpClientRegistry();

        FtpProperties defaultProps = new FtpProperties();
        GenericObjectPoolConfig<FTPClient> defaultConfig = new GenericObjectPoolConfig<>();
        defaultConfig.setMaxTotal(12);
        defaultConfig.setMaxIdle(6);
        defaultConfig.setMinIdle(2);
        GenericObjectPool<FTPClient> defaultPool = new GenericObjectPool<>(
                new FtpClientPooledObjectFactory(defaultProps), defaultConfig);
        FtpTemplate defaultTemplate = new FtpTemplate(defaultPool, defaultProps, registry, FtpClientRegistry.DEFAULT_SERVER);

        FtpProperties backupProps = new FtpProperties();
        GenericObjectPoolConfig<FTPClient> backupConfig = new GenericObjectPoolConfig<>();
        backupConfig.setMaxTotal(5);
        backupConfig.setMaxIdle(3);
        backupConfig.setMinIdle(1);
        GenericObjectPool<FTPClient> backupPool = new GenericObjectPool<>(
                new FtpClientPooledObjectFactory(backupProps), backupConfig);
        FtpTemplate backupTemplate = new FtpTemplate(backupPool, backupProps, registry, "backup");

        registry.register("default", defaultTemplate, defaultPool);
        registry.register("backup", backupTemplate, backupPool);

        try {
            FtpPoolStats defaultStats = registry.getPoolStats();
            assertThat(defaultStats.getServerName()).isEqualTo("default");
            assertThat(defaultStats.getMaxTotal()).isEqualTo(12);
            assertThat(defaultStats.getMaxIdle()).isEqualTo(6);
            assertThat(defaultStats.getMinIdle()).isEqualTo(2);
            assertThat(defaultStats.getActiveConnections()).isZero();
            assertThat(defaultStats.getIdleConnections()).isZero();
            assertThat(defaultStats.getWaitingBorrowers()).isZero();
            assertThat(registry.getPoolStats(null)).isEqualTo(defaultStats);

            FtpPoolStats viaTemplate = defaultTemplate.getPoolStats("backup");
            assertThat(viaTemplate.getServerName()).isEqualTo("backup");
            assertThat(viaTemplate.getMaxTotal()).isEqualTo(5);

            assertThat(defaultTemplate.getPoolStats().getServerName()).isEqualTo("default");
            assertThat(defaultTemplate.getAllPoolStats()).containsOnlyKeys("default", "backup");

            Map<String, FtpPoolStats> allStats = registry.getAllPoolStats();
            assertThat(allStats).containsOnlyKeys("default", "backup");
            assertThat(allStats.get("backup").getMaxIdle()).isEqualTo(3);

            assertThatThrownBy(() -> registry.getPoolStats("missing"))
                    .isInstanceOf(FtpException.class)
                    .extracting(e -> ((FtpException) e).getCode())
                    .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());

            assertThatThrownBy(() -> FtpClientRegistry.buildPoolStats(" ", defaultPool))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> FtpClientRegistry.buildPoolStats("default", null))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("测试运行期动态注册、同名平滑热替换、对称注销及 default 主节点防注销守卫")
    void testDynamicRegisterHotReplaceAndUnregisterLifecycle() {
        FakeFtpServer serverA = startFakeServer("userA", "passA");
        FakeFtpServer serverB = startFakeServer("userB", "passB");
        List<String> customizedNodes = new CopyOnWriteArrayList<>();
        FtpClientCustomizer customizer = (name, client, props) -> customizedNodes.add(name);

        FtpProperties defaultProps = new FtpProperties();
        defaultProps.setConnectTimeout(Duration.ofSeconds(5));
        defaultProps.setDataTimeout(Duration.ofSeconds(5));

        FtpClientRegistry registry = new FtpClientRegistry(defaultProps, List.of(customizer));
        try {
            FtpTemplate defaultTemplate = registry.register(FtpClientRegistry.DEFAULT_SERVER, defaultProps);

            // 1. 动态热注册 tenantA -> serverA
            FtpProperties propsA = new FtpProperties();
            propsA.setHost("127.0.0.1");
            propsA.setPort(serverA.getServerControlPort());
            propsA.setUsername("userA");
            propsA.setPassword("passA");
            FtpTemplate tenantTemplate = registry.register("tenantA", propsA);
            // 入参对象不应被 register 原地修改
            assertThat(propsA.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));

            assertThat(registry.contains("tenantA")).isTrue();
            FtpContextHolder.runWith("tenantA", () ->
                    defaultTemplate.writeString("/node_check.txt", "payload-A"));
            assertThat(customizedNodes).contains("tenantA");
            assertThat(serverA.getFileSystem().exists("/node_check.txt")).isTrue();

            GenericObjectPool<?> oldPool = (GenericObjectPool<?>) registry.getPool("tenantA");

            // 2. 同名热替换 tenantA -> serverB，校验旧池已关闭且后续读写路由至 serverB
            FtpProperties propsB = new FtpProperties();
            propsB.setHost("127.0.0.1");
            propsB.setPort(serverB.getServerControlPort());
            propsB.setUsername("userB");
            propsB.setPassword("passB");
            tenantTemplate = registry.register("tenantA", propsB);

            assertThat(oldPool.isClosed()).isTrue();
            assertThat(registry.getPool("tenantA")).isNotSameAs(oldPool);
            FtpContextHolder.runWith("tenantA", () -> {
                defaultTemplate.writeString("/node_check_b.txt", "payload-B");
                assertThat(defaultTemplate.readString("/node_check_b.txt")).isEqualTo("payload-B");
            });
            assertThat(serverB.getFileSystem().exists("/node_check_b.txt")).isTrue();

            GenericObjectPool<?> activePoolB = (GenericObjectPool<?>) registry.getPool("tenantA");

            // 3. 对称注销 tenantA，校验连接池关闭与后续寻址及旧引用指标查询均抛出 SERVER_NOT_FOUND
            assertThat(registry.unregister("tenantA")).isTrue();
            assertThat(activePoolB.isClosed()).isTrue();
            assertThat(registry.contains("tenantA")).isFalse();
            assertThat(registry.unregister("tenantA")).isFalse();
            assertThatThrownBy(() -> registry.getTemplate("tenantA"))
                    .isInstanceOf(FtpException.class)
                    .extracting(e -> ((FtpException) e).getCode())
                    .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());
            FtpTemplate staleTemplate = tenantTemplate;
            assertThatThrownBy(staleTemplate::getPoolStats)
                    .isInstanceOf(FtpException.class)
                    .extracting(e -> ((FtpException) e).getCode())
                    .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());

            // 4. 大小写归一化与严禁注销 default 默认主节点
            registry.register("DEFAULT", defaultProps);
            assertThat(registry.getServerNames()).containsOnly("default");
            assertThatThrownBy(() -> registry.unregister("default"))
                    .isInstanceOf(FtpException.class)
                    .extracting(e -> ((FtpException) e).getCode())
                    .isEqualTo(FtpResultCode.OPERATION_FAILED.getCode());
            assertThatThrownBy(() -> registry.unregister("DEFAULT"))
                    .isInstanceOf(FtpException.class)
                    .extracting(e -> ((FtpException) e).getCode())
                    .isEqualTo(FtpResultCode.OPERATION_FAILED.getCode());
            assertThat(registry.contains("default")).isTrue();
        } finally {
            registry.close();
            serverA.stop();
            serverB.stop();
        }
    }

    private FakeFtpServer startFakeServer(String username, String password) {
        FakeFtpServer server = new FakeFtpServer();
        server.setServerControlPort(0);
        server.addUserAccount(new UserAccount(username, password, "/"));
        FileSystem fs = new UnixFakeFileSystem();
        fs.add(new DirectoryEntry("/"));
        server.setFileSystem(fs);
        server.start();
        return server;
    }
}
