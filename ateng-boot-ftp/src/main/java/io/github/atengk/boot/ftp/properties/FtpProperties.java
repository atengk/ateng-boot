package io.github.atengk.boot.ftp.properties;

import io.github.atengk.boot.ftp.enums.FtpFileType;
import io.github.atengk.boot.ftp.enums.FtpMode;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * FTP / FTPS 全局与多节点配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
@EqualsAndHashCode
@ToString
@ConfigurationProperties(prefix = "ateng.boot.ftp")
public class FtpProperties {

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final Set<String> configuredFields = new HashSet<>();

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
     * 内存字节下载最大允许容量（默认 50MB，超过阈值抛出异常防止 OOM 堆内存溢出）
     */
    private DataSize maxDownloadBytes = DataSize.ofMegabytes(50);

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

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.configuredFields.add("enabled");
    }

    public void setHost(String host) {
        this.host = host;
        this.configuredFields.add("host");
    }

    public void setPort(int port) {
        this.port = port;
        this.configuredFields.add("port");
    }

    public void setUsername(String username) {
        this.username = username;
        this.configuredFields.add("username");
    }

    public void setPassword(String password) {
        this.password = password;
        this.configuredFields.add("password");
    }

    public void setMode(FtpMode mode) {
        this.mode = mode;
        this.configuredFields.add("mode");
    }

    public void setFileType(FtpFileType fileType) {
        this.fileType = fileType;
        this.configuredFields.add("fileType");
    }

    public void setControlEncoding(String controlEncoding) {
        this.controlEncoding = controlEncoding;
        this.configuredFields.add("controlEncoding");
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        this.configuredFields.add("connectTimeout");
    }

    public void setDataTimeout(Duration dataTimeout) {
        this.dataTimeout = dataTimeout;
        this.configuredFields.add("dataTimeout");
    }

    public void setControlKeepAliveTimeout(Duration controlKeepAliveTimeout) {
        this.controlKeepAliveTimeout = controlKeepAliveTimeout;
        this.configuredFields.add("controlKeepAliveTimeout");
    }

    public void setBufferSize(DataSize bufferSize) {
        this.bufferSize = bufferSize;
        this.configuredFields.add("bufferSize");
    }

    public void setMaxDownloadBytes(DataSize maxDownloadBytes) {
        this.maxDownloadBytes = maxDownloadBytes;
        this.configuredFields.add("maxDownloadBytes");
    }

    public void setAtomicUpload(boolean atomicUpload) {
        this.atomicUpload = atomicUpload;
        this.configuredFields.add("atomicUpload");
    }

    public void setTempFileSuffix(String tempFileSuffix) {
        this.tempFileSuffix = tempFileSuffix;
        this.configuredFields.add("tempFileSuffix");
    }

    public void setAutoCreateDirectories(boolean autoCreateDirectories) {
        this.autoCreateDirectories = autoCreateDirectories;
        this.configuredFields.add("autoCreateDirectories");
    }

    public void setServerTimeZone(String serverTimeZone) {
        this.serverTimeZone = serverTimeZone;
        this.configuredFields.add("serverTimeZone");
    }

    public void setFtps(FtpsProperties ftps) {
        this.ftps = ftps;
        this.configuredFields.add("ftps");
    }

    public void setPool(FtpPoolProperties pool) {
        this.pool = pool;
        this.configuredFields.add("pool");
    }

    public void setServers(Map<String, FtpProperties> servers) {
        this.servers = servers;
        this.configuredFields.add("servers");
    }

    /**
     * 创建当前配置的独立深拷贝（隔离可变嵌套子配置，避免原地修改入参）
     *
     * @return 独立拷贝实例
     */
    public FtpProperties copy() {
        FtpProperties copy = new FtpProperties();
        copy.enabled = this.enabled;
        copy.host = this.host;
        copy.port = this.port;
        copy.username = this.username;
        copy.password = this.password;
        copy.mode = this.mode;
        copy.fileType = this.fileType;
        copy.controlEncoding = this.controlEncoding;
        copy.connectTimeout = this.connectTimeout;
        copy.dataTimeout = this.dataTimeout;
        copy.controlKeepAliveTimeout = this.controlKeepAliveTimeout;
        copy.bufferSize = this.bufferSize;
        copy.maxDownloadBytes = this.maxDownloadBytes;
        copy.atomicUpload = this.atomicUpload;
        copy.tempFileSuffix = this.tempFileSuffix;
        copy.autoCreateDirectories = this.autoCreateDirectories;
        copy.serverTimeZone = this.serverTimeZone;
        copy.ftps = this.ftps != null ? this.ftps.copy() : new FtpsProperties();
        copy.pool = this.pool != null ? this.pool.copy() : new FtpPoolProperties();
        copy.configuredFields.addAll(this.configuredFields);
        return copy;
    }

    /**
     * 将缺省的通用配置属性从父级默认配置合并到当前实例中（绝不覆盖子节点显式设置，绝不共享可变引用）
     *
     * @param defaults 父级默认配置
     */
    public void mergeFrom(FtpProperties defaults) {
        if (defaults == null) {
            return;
        }
        this.mode = pick("mode", this.mode, defaults.mode);
        this.fileType = pick("fileType", this.fileType, defaults.fileType);
        this.controlEncoding = pick("controlEncoding", this.controlEncoding, defaults.controlEncoding);
        this.connectTimeout = pick("connectTimeout", this.connectTimeout, defaults.connectTimeout);
        this.dataTimeout = pick("dataTimeout", this.dataTimeout, defaults.dataTimeout);
        this.controlKeepAliveTimeout = pick("controlKeepAliveTimeout", this.controlKeepAliveTimeout, defaults.controlKeepAliveTimeout);
        this.bufferSize = pick("bufferSize", this.bufferSize, defaults.bufferSize);
        this.maxDownloadBytes = pick("maxDownloadBytes", this.maxDownloadBytes, defaults.maxDownloadBytes);
        this.atomicUpload = pick("atomicUpload", this.atomicUpload, defaults.atomicUpload);
        this.tempFileSuffix = pick("tempFileSuffix", this.tempFileSuffix, defaults.tempFileSuffix);
        this.autoCreateDirectories = pick("autoCreateDirectories", this.autoCreateDirectories, defaults.autoCreateDirectories);
        this.serverTimeZone = pick("serverTimeZone", this.serverTimeZone, defaults.serverTimeZone);

        if (this.pool == null) {
            this.pool = defaults.getPool() != null ? defaults.getPool().copy() : new FtpPoolProperties();
        } else {
            this.pool.mergeFrom(defaults.getPool());
        }
        if (this.ftps == null) {
            this.ftps = defaults.getFtps() != null ? defaults.getFtps().copy() : new FtpsProperties();
        } else {
            this.ftps.mergeFrom(defaults.getFtps());
        }
    }

    private <T> T pick(String key, T current, T parent) {
        return configuredFields.contains(key) ? current : parent;
    }
}
