package io.github.atengk.boot.sample.ftp.config;

import io.github.atengk.boot.ftp.customizer.FtpClientCustomizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 样例工程 FTP 客户端定制器扩展点配置
 * <p>
 * 演示如何通过注入 {@link FtpClientCustomizer} SPI Bean，按节点名称定制底层原生
 * {@code FTPClient} 参数（如多网卡备份节点关闭源端 IP 校验），而无需覆盖整体连接池工厂。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Slf4j
@Configuration
public class SampleFtpCustomizerConfig {

    /**
     * 为备用节点 {@code backup} 注册连接定制器，关闭远端数据连接 IP 校验以兼容跨网段 NAT 部署
     *
     * @return 客户端定制器 Bean
     */
    @Bean
    public FtpClientCustomizer backupRemoteVerificationCustomizer() {
        return (serverName, client, properties) -> {
            if ("backup".equals(serverName)) {
                client.setRemoteVerificationEnabled(false);
                log.debug("ateng-boot-sample-ftp: 已为节点 [{}] 应用 FtpClientCustomizer 定制策略", serverName);
            }
        };
    }
}
