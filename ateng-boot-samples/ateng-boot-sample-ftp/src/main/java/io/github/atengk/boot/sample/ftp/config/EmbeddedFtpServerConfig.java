package io.github.atengk.boot.sample.ftp.config;

import io.github.atengk.boot.ftp.properties.FtpProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 样例工程非 local 环境嵌入式离线 FTP 服务端配置
 * <p>
 * 标配 {@code @Profile("!local")}，在开发者直接启动样例应用或运行离线测试时自动拉起
 * {@code default} 主节点与 {@code servers.*} 具名节点内存级 {@link FakeFtpServer}；
 * 当激活 {@code local} Profile 连接真实物理 FTP 中间件或端口已被外部服务/测试套件占用时自动退避跳过。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Slf4j
@Configuration
@Profile("!local")
@ConditionalOnProperty(prefix = "ateng.boot.ftp", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class EmbeddedFtpServerConfig {

    private final FtpProperties ftpProperties;
    private final List<FakeFtpServer> startedServers = new CopyOnWriteArrayList<>();

    /**
     * 启动非 local 模式下的本地内存 FTP 服务节点
     */
    @PostConstruct
    public void startEmbeddedServers() {
        startIfLocalAndFree("default", ftpProperties);

        Map<String, FtpProperties> servers = ftpProperties.getServers();
        if (servers != null && !servers.isEmpty()) {
            servers.forEach((name, nodeProps) -> {
                if (nodeProps != null && !"default".equalsIgnoreCase(name)) {
                    startIfLocalAndFree(name, nodeProps);
                }
            });
        }
    }

    /**
     * 容器关闭时确定性停止所有由当前配置启动的内存 FTP 服务
     */
    @PreDestroy
    public void stopEmbeddedServers() {
        for (FakeFtpServer server : startedServers) {
            try {
                server.stop();
            } catch (Exception e) {
                log.warn("ateng-boot-sample-ftp: 停止嵌入式 FakeFtpServer 异常: {}", e.getMessage());
            }
        }
        startedServers.clear();
    }

    private void startIfLocalAndFree(String serverName, FtpProperties props) {
        if (props == null || !isLocalHost(props.getHost()) || props.getPort() <= 0) {
            return;
        }
        int port = props.getPort();
        if (!isPortAvailable(port)) {
            log.info("ateng-boot-sample-ftp: 端口 [{}] 已被监听，跳过嵌入式 FakeFtpServer [node={}] 启动",
                    port, serverName);
            return;
        }

        FakeFtpServer server = new FakeFtpServer();
        server.setServerControlPort(port);

        FileSystem fileSystem = new UnixFakeFileSystem();
        fileSystem.add(new DirectoryEntry("/"));
        server.setFileSystem(fileSystem);

        String username = StringUtils.hasText(props.getUsername()) ? props.getUsername() : "anonymous";
        String password = props.getPassword() != null ? props.getPassword() : "";
        server.addUserAccount(new UserAccount(username, password, "/"));

        server.start();
        startedServers.add(server);
        log.info("ateng-boot-sample-ftp: 已启动嵌入式 FakeFtpServer [node={}, port={}, user={}]",
                serverName, port, username);
    }

    private boolean isLocalHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
