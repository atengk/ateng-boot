package io.github.atengk.boot.ftp.autoconfigure;

import io.github.atengk.boot.ftp.context.FtpContextHolder;
import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import io.github.atengk.boot.ftp.registry.FtpClientRegistry;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FTP 动态切源路由与 Spring Boot 3 自动装配条件单测
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("FTP 动态切源路由与自动装配条件单测")
class FtpDynamicRoutingAndAutoConfigurationTest {

    private static final String USERNAME = "ftpUser";
    private static final String PASSWORD = "ftpPassword";

    private FakeFtpServer server1;
    private FakeFtpServer server2;
    private FtpClientRegistry registry;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengFtpAutoConfiguration.class));

    @BeforeEach
    void setUp() {
        FtpContextHolder.clear();

        // 1. 初始化双端口 FakeFtpServer
        server1 = new FakeFtpServer();
        server1.setServerControlPort(0);
        server1.addUserAccount(new UserAccount(USERNAME, PASSWORD, "/"));
        FileSystem fs1 = new UnixFakeFileSystem();
        fs1.add(new DirectoryEntry("/"));
        fs1.add(new DirectoryEntry("/server1"));
        server1.setFileSystem(fs1);
        server1.start();

        server2 = new FakeFtpServer();
        server2.setServerControlPort(0);
        server2.addUserAccount(new UserAccount(USERNAME, PASSWORD, "/"));
        FileSystem fs2 = new UnixFakeFileSystem();
        fs2.add(new DirectoryEntry("/"));
        fs2.add(new DirectoryEntry("/server2"));
        server2.setFileSystem(fs2);
        server2.start();
    }

    @AfterEach
    void tearDown() {
        FtpContextHolder.clear();
        if (registry != null) {
            registry.close();
        }
        if (server1 != null) {
            server1.stop();
        }
        if (server2 != null) {
            server2.stop();
        }
    }

    @Test
    @DisplayName("测试双节点配置下 FtpContextHolder 动态切源路由与数据隔离")
    void testDynamicRoutingAcrossDualServers() {
        // 构建主节点与 archive 具名多节点配置
        FtpProperties properties = new FtpProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(server1.getServerControlPort());
        properties.setUsername(USERNAME);
        properties.setPassword(PASSWORD);
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setDataTimeout(Duration.ofSeconds(3));

        FtpProperties archiveProps = new FtpProperties();
        archiveProps.setHost("127.0.0.1");
        archiveProps.setPort(server2.getServerControlPort());
        archiveProps.setUsername(USERNAME);
        archiveProps.setPassword(PASSWORD);
        archiveProps.setConnectTimeout(Duration.ofSeconds(3));
        archiveProps.setDataTimeout(Duration.ofSeconds(3));

        properties.getServers().put("archive", archiveProps);

        // 使用自动装配逻辑初始化注册表与主门面
        AtengFtpAutoConfiguration autoConfig = new AtengFtpAutoConfiguration();
        registry = autoConfig.ftpClientRegistry(properties);
        FtpTemplate ftpTemplate = autoConfig.ftpTemplate(registry);

        assertThat(registry.getServerNames()).containsExactlyInAnyOrder("default", "archive");

        // 1. 默认上下文写入主节点 (Server 1)
        byte[] payload1 = "server1 payload".getBytes(StandardCharsets.UTF_8);
        boolean uploadedDefault = ftpTemplate.upload("/server1/bill.csv", payload1);
        assertThat(uploadedDefault).isTrue();
        assertThat(ftpTemplate.exists("/server1/bill.csv")).isTrue();

        // 2. 动态切源至 archive 节点写入 Server 2
        byte[] payload2 = "server2 payload".getBytes(StandardCharsets.UTF_8);
        FtpContextHolder.runWith("archive", () -> {
            boolean uploadedArchive = ftpTemplate.upload("/server2/archive_bill.csv", payload2);
            assertThat(uploadedArchive).isTrue();
            assertThat(ftpTemplate.exists("/server2/archive_bill.csv")).isTrue();
        });

        // 3. 验证切源结束自动复位回 default 主节点
        assertThat(FtpContextHolder.getServer()).isNull();
        byte[] defaultFileContent = ftpTemplate.downloadToBytes("/server1/bill.csv");
        assertThat(defaultFileContent).isEqualTo(payload1);

        // 4. 切源到不存在的节点抛出 SERVER_NOT_FOUND
        assertThatThrownBy(() -> FtpContextHolder.runWith("non_existent_node", () -> {
            ftpTemplate.exists("/any.txt");
        })).isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("测试自动装配在 enabled=false 时平滑关闭不注入 Bean")
    void testAutoConfigurationDisabled() {
        contextRunner.withPropertyValues("ateng.boot.ftp.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FtpClientRegistry.class);
                    assertThat(context).doesNotHaveBean(FtpTemplate.class);
                });
    }

    @Test
    @DisplayName("测试自动装配在 @ConditionalOnMissingBean 守卫下允许外部 Bean 接管")
    void testAutoConfigurationCustomBeanOverride() {
        FtpTemplate mockTemplate = Mockito.mock(FtpTemplate.class);
        FtpClientRegistry mockRegistry = Mockito.mock(FtpClientRegistry.class);

        contextRunner.withBean("ftpTemplate", FtpTemplate.class, () -> mockTemplate)
                .withBean("ftpClientRegistry", FtpClientRegistry.class, () -> mockRegistry)
                .run(context -> {
                    assertThat(context).hasSingleBean(FtpTemplate.class);
                    assertThat(context).hasSingleBean(FtpClientRegistry.class);
                    assertThat(context.getBean(FtpTemplate.class)).isSameAs(mockTemplate);
                    assertThat(context.getBean(FtpClientRegistry.class)).isSameAs(mockRegistry);
                });
    }
}
