package io.github.atengk.boot.ftp.properties;

import io.github.atengk.boot.ftp.enums.FtpFileType;
import io.github.atengk.boot.ftp.enums.FtpMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FTP / FTPS 全局与多节点配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.ftp")
public class FtpProperties {

    /**
     * 是否启用 FTP 自动装配组件
     */
    private boolean enabled = true;

    /**
     * FTP 服务端主机地址或域名
     */
    private String host = "localhost";

    /**
     * FTP 服务端端口（标准 FTP 默认 21，隐式 FTPS 默认 990）
     */
    private int port = 21;

    /**
     * 认证用户名
     */
    private String username = "anonymous";

    /**
     * 认证密码
     */
    private String password = "";

    /**
     * 传输网络模式（默认 PASSIVE 本地被动模式，NAT/容器环境友好）
     */
    private FtpMode mode = FtpMode.PASSIVE;

    /**
     * 传输文件数据类型（默认 BINARY 二进制模式，防止换行符篡改文件哈希）
     */
    private FtpFileType fileType = FtpFileType.BINARY;

    /**
     * 控制通道编码（默认 UTF-8，支持 GBK / ISO-8859-1）
     */
    private String controlEncoding = "UTF-8";

    /**
     * 连接握手超时时间
     */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * 数据通道传输与 Socket 读取超时时间
     */
    private Duration dataTimeout = Duration.ofSeconds(30);

    /**
     * 控制通道空闲保活超时周期（默认 PT30S，传输期间自动发送 NOOP 防止云防火墙截断）
     */
    private Duration controlKeepAliveTimeout = Duration.ofSeconds(30);

    /**
     * 数据传输缓冲区大小（默认 64KB）
     */
    private DataSize bufferSize = DataSize.ofKilobytes(64);

    /**
     * 是否开启原子上传机制（先写入 .tmp 临时文件，成功后原子 rename，杜绝下游读取残缺半截文件）
     */
    private boolean atomicUpload = true;

    /**
     * 原子上传临时文件后缀名
     */
    private String tempFileSuffix = ".tmp";

    /**
     * 上传时是否自动递归创建不存在的父目录（mkdir -p）
     */
    private boolean autoCreateDirectories = true;

    /**
     * 服务端时区标识（用于传统 LIST 命令解析时间戳漂移矫正，默认 Asia/Shanghai）
     */
    private String serverTimeZone = "Asia/Shanghai";

    /**
     * FTPS 安全加密传输配置
     */
    @NestedConfigurationProperty
    private FtpsProperties ftps = new FtpsProperties();

    /**
     * 客户端连接池配置属性
     */
    @NestedConfigurationProperty
    private FtpPoolProperties pool = new FtpPoolProperties();

    /**
     * 命名多服务器节点配置（主配置自动作为 default 节点）
     */
    private Map<String, FtpProperties> servers = new LinkedHashMap<>();
}
