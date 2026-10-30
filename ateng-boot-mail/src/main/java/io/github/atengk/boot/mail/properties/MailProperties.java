package io.github.atengk.boot.mail.properties;

import io.github.atengk.boot.core.properties.AsyncPoolProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 邮件基础设施全局配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.mail")
public class MailProperties {

    /**
     * 是否启用邮件基础设施，默认启用
     */
    private boolean enabled = true;

    /**
     * 默认发信账号名称，默认 "default"
     */
    private String defaultAccount = "default";

    /**
     * SMTP 服务器建立连接超时时间，默认 5 秒
     */
    private Duration connectionTimeout = Duration.ofSeconds(5);

    /**
     * SMTP 服务器读取响应超时时间，默认 10 秒
     */
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * SMTP 服务器写入数据超时时间，默认 10 秒
     */
    private Duration writeTimeout = Duration.ofSeconds(10);

    /**
     * 单个邮件附件最大容量上限，默认 20MB
     */
    private DataSize maxAttachmentSize = DataSize.ofMegabytes(20);

    /**
     * 邮件附件与正文总容量上限，默认 50MB
     */
    private DataSize maxTotalSize = DataSize.ofMegabytes(50);

    /**
     * 动态编程式发信账号 LRU 缓存容量上限，默认 100
     */
    private int maxDynamicAccounts = 100;

    /**
     * 专职受管异步发信执行器配置（支持平台线程池与虚拟线程双模自适应）
     */
    @NestedConfigurationProperty
    private AsyncPoolProperties async = new AsyncPoolProperties();

    /**
     * 顶级单账号快捷配置：SMTP 服务器主机地址
     */
    private String host;

    /**
     * 顶级单账号快捷配置：SMTP 服务端口
     */
    private Integer port = 465;

    /**
     * 顶级单账号快捷配置：认证用户名
     */
    private String username;

    /**
     * 顶级单账号快捷配置：认证密码或授权码
     */
    private String password;

    /**
     * 顶级单账号快捷配置：传输协议
     */
    private String protocol = "smtp";

    /**
     * 顶级单账号快捷配置：是否开启 SSL 安全连接
     */
    private boolean ssl = true;

    /**
     * 顶级单账号快捷配置：是否开启 STARTTLS
     */
    private boolean starttls = false;

    /**
     * 顶级单账号快捷配置：发件人邮箱地址（别名字段，对齐 mail.from 与 defaultFrom）
     */
    private String from;

    /**
     * 顶级单账号快捷配置：发件人中文昵称（别名字段，对齐 mail.from-name 与 defaultFromName）
     */
    private String fromName;

    /**
     * 顶级单账号快捷配置：默认发件人邮箱
     */
    private String defaultFrom;

    /**
     * 顶级单账号快捷配置：默认发件人中文昵称
     */
    private String defaultFromName;

    public String getFrom() {
        return from != null && !from.isBlank() ? from : defaultFrom;
    }

    public void setFrom(String from) {
        this.from = from;
        this.defaultFrom = from;
    }

    public String getDefaultFrom() {
        return defaultFrom != null && !defaultFrom.isBlank() ? defaultFrom : from;
    }

    public void setDefaultFrom(String defaultFrom) {
        this.defaultFrom = defaultFrom;
        this.from = defaultFrom;
    }

    public String getFromName() {
        return fromName != null && !fromName.isBlank() ? fromName : defaultFromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
        this.defaultFromName = fromName;
    }

    public String getDefaultFromName() {
        return defaultFromName != null && !defaultFromName.isBlank() ? defaultFromName : fromName;
    }

    public void setDefaultFromName(String defaultFromName) {
        this.defaultFromName = defaultFromName;
        this.fromName = defaultFromName;
    }

    /**
     * 顶级单账号快捷配置：额外 JavaMail 属性
     */
    private Map<String, String> properties = new LinkedHashMap<>();

    /**
     * 多发信账号配置映射字典（键为账号逻辑名称，如 ops, marketing）
     */
    @NestedConfigurationProperty
    private Map<String, MailAccountProperties> accounts = new LinkedHashMap<>();

    /**
     * 物理发信网络抖动指数退避重试配置
     */
    @NestedConfigurationProperty
    private MailRetryProperties retry = new MailRetryProperties();

    /**
     * 批量邮件合并发信配置
     */
    @NestedConfigurationProperty
    private MailBatchProperties batch = new MailBatchProperties();

    /**
     * 邮件测试安全沙箱配置
     */
    @NestedConfigurationProperty
    private MailSandboxProperties sandbox = new MailSandboxProperties();

    public MailProperties() {
        this.async.setThreadNamePrefix("mail-async-");
        this.async.setVirtualThreadNamePrefix("mail-vt-async-");
    }
}
