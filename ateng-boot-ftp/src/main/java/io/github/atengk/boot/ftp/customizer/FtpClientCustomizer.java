package io.github.atengk.boot.ftp.customizer;

import io.github.atengk.boot.ftp.properties.FtpProperties;
import org.apache.commons.net.ftp.FTPClient;

/**
 * FTP / FTPS 原生客户端自定义扩展点 SPI
 * <p>
 * 在受管连接池工厂创建并完成基础参数、超时及协议握手初始化后触发回调，
 * 允许业务层按节点名称定制底层 {@link FTPClient}（如挂载代理、设置被动端口范围、注册自定义目录解析器等）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
@FunctionalInterface
public interface FtpClientCustomizer {

    /**
     * 对新建并完成登录初始化的 FTP 客户端实例执行自定义配置
     *
     * @param serverName 当前所属的服务节点名称（如 default、backup）
     * @param client     底层 FTPClient 或 FTPSClient 实例
     * @param properties 当前节点的有效配置属性
     */
    void customize(String serverName, FTPClient client, FtpProperties properties);
}
