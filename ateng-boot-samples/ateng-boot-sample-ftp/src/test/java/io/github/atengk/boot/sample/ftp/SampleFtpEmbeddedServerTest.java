package io.github.atengk.boot.sample.ftp;

import io.github.atengk.boot.ftp.autoconfigure.AtengFtpAutoConfiguration;
import io.github.atengk.boot.ftp.context.FtpContextHolder;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import io.github.atengk.boot.sample.ftp.config.EmbeddedFtpServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 样例工程 @Profile("!local") 嵌入式 FakeFtpServer 自动配置自洽验证
 *
 * @author Ateng
 * @since 2026-11-17
 */
@DisplayName("Sample @Profile(\"!local\") 嵌入式 FakeFtpServer 自洽测试")
class SampleFtpEmbeddedServerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengFtpAutoConfiguration.class))
            .withUserConfiguration(EmbeddedFtpServerConfig.class);

    @AfterEach
    void tearDown() {
        FtpContextHolder.clear();
    }

    @Test
    @DisplayName("非 local 环境下自动启动 default 与 backup 嵌入式 FakeFtpServer 并可正常读写")
    void testEmbeddedServersAutoStartInNonLocalProfile() throws Exception {
        int defaultPort = findFreePort();
        int backupPort = findFreePort();

        contextRunner.withPropertyValues(
                        "spring.profiles.active=default",
                        "ateng.boot.ftp.enabled=true",
                        "ateng.boot.ftp.host=127.0.0.1",
                        "ateng.boot.ftp.port=" + defaultPort,
                        "ateng.boot.ftp.username=admin",
                        "ateng.boot.ftp.password=password123",
                        "ateng.boot.ftp.servers.backup.host=127.0.0.1",
                        "ateng.boot.ftp.servers.backup.port=" + backupPort,
                        "ateng.boot.ftp.servers.backup.username=backup_user",
                        "ateng.boot.ftp.servers.backup.password=backup_password"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(EmbeddedFtpServerConfig.class);
                    FtpTemplate ftpTemplate = context.getBean(FtpTemplate.class);
                    assertThat(ftpTemplate.testConnection()).isTrue();
                    assertThat(ftpTemplate.testConnection("backup")).isTrue();

                    ftpTemplate.writeString("/docs/hello.txt", "Embedded FTP Ready");
                    assertThat(ftpTemplate.readString("/docs/hello.txt")).isEqualTo("Embedded FTP Ready");

                    FtpContextHolder.runWith("backup", () -> {
                        ftpTemplate.writeString("/routing/backup.txt", "Backup Ready");
                        assertThat(ftpTemplate.readString("/routing/backup.txt")).isEqualTo("Backup Ready");
                    });
                });
    }

    @Test
    @DisplayName("激活 local Profile 时自动禁用嵌入式 FakeFtpServer")
    void testEmbeddedServersDisabledUnderLocalProfile() {
        contextRunner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).doesNotHaveBean(EmbeddedFtpServerConfig.class));
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
