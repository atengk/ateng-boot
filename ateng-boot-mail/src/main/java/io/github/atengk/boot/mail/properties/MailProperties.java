package io.github.atengk.boot.mail.properties;

import io.github.atengk.boot.core.properties.AsyncPoolProperties;
import io.github.atengk.boot.mail.model.AuthMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件基础设施全局配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ateng.boot.mail")
public class MailProperties {

    /**
     * 是否启用邮件基础设施，默认启用
     */
    private boolean enabled = true;

    /**
     * 是否允许在未配置任何静态账号时仅以空注册表启动（适用于纯动态账号或纯测试环境，默认 false）
     */
    private boolean allowEmptyAccounts = false;

    /**
     * 默认发信账号名称，默认 "default"
     */
    @NotBlank(message = "defaultAccount 不能为空")
    private String defaultAccount = "default";

    /**
     * SMTP 服务器建立连接超时时间，默认 5 秒
     */
    @NotNull(message = "connectionTimeout 不能为空")
    @ValidDuration(message = "connectionTimeout 必须大于 0")
    private Duration connectionTimeout = Duration.ofSeconds(5);

    /**
     * SMTP 服务器读取响应超时时间，默认 10 秒
     */
    @NotNull(message = "readTimeout 不能为空")
    @ValidDuration(message = "readTimeout 必须大于 0")
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * SMTP 服务器写入数据超时时间，默认 10 秒
     */
    @NotNull(message = "writeTimeout 不能为空")
    @ValidDuration(message = "writeTimeout 必须大于 0")
    private Duration writeTimeout = Duration.ofSeconds(10);

    /**
     * 单个邮件附件最大容量上限，默认 20MB
     */
    @NotNull(message = "maxAttachmentSize 不能为空")
    @ValidDataSize(message = "maxAttachmentSize 必须大于 0")
    private DataSize maxAttachmentSize = DataSize.ofMegabytes(20);

    /**
     * 邮件附件与正文总容量上限，默认 50MB
     */
    @NotNull(message = "maxTotalSize 不能为空")
    @ValidDataSize(message = "maxTotalSize 必须大于 0")
    private DataSize maxTotalSize = DataSize.ofMegabytes(50);

    /**
     * 动态编程式发信账号 LRU 缓存容量上限，默认 100
     */
    @Min(value = 1, message = "maxDynamicAccounts 必须大于或等于 1")
    private int maxDynamicAccounts = 100;

    /**
     * 专职受管异步发信执行器配置（支持平台线程池与虚拟线程双模自适应）
     */
    @ValidAsyncPool
    @NestedConfigurationProperty
    private AsyncPoolProperties async = new AsyncPoolProperties();

    /**
     * 顶级单账号快捷配置：SMTP 服务器主机地址
     */
    private String host;

    /**
     * 顶级单账号快捷配置：SMTP 服务端口
     */
    @Min(value = 1, message = "port 必须在 1 到 65535 之间")
    @Max(value = 65535, message = "port 必须在 1 到 65535 之间")
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
     * 顶级单账号快捷配置：SMTP 认证模式，默认 AUTO
     */
    private AuthMode authMode = AuthMode.AUTO;

    /**
     * 顶级单账号快捷配置：XOAUTH2 访问令牌
     */
    private String accessToken;

    /**
     * 顶级单账号快捷配置：SSL 信任主机名配置（如内网网关域名或 "*"）
     */
    private String sslTrust;

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
     * 顶级单账号快捷配置：主账号遇瞬态可用性故障后的有序备用账号名称列表
     */
    private List<String> backupAccounts = new ArrayList<>();

    /**
     * 多发信账号配置映射字典（键为账号逻辑名称，如 ops, marketing）
     */
    @Valid
    @NestedConfigurationProperty
    private Map<String, @Valid MailAccountProperties> accounts = new LinkedHashMap<>();

    /**
     * 物理发信网络抖动指数退避重试配置
     */
    @Valid
    @NestedConfigurationProperty
    private MailRetryProperties retry = new MailRetryProperties();

    /**
     * 批量邮件合并发信配置
     */
    @Valid
    @NestedConfigurationProperty
    private MailBatchProperties batch = new MailBatchProperties();

    /**
     * 全局合规审计归档密送邮箱（若配置，外发邮件前自动将此地址追加至 Bcc 密送列表，支持逗号或分号分隔多个邮箱）
     */
    private String archiveBcc;

    /**
     * 邮件测试安全沙箱配置
     */
    @Valid
    @NestedConfigurationProperty
    private MailSandboxProperties sandbox = new MailSandboxProperties();

    /**
     * 邮件模板渲染配置
     */
    @Valid
    @NestedConfigurationProperty
    private MailTemplateProperties template = new MailTemplateProperties();

    /**
     * 邮件出站频率限制（账号令牌桶 + 单收件人滑动窗口）配置
     */
    @Valid
    @NestedConfigurationProperty
    private MailRateLimitProperties rateLimit = new MailRateLimitProperties();

    /**
     * 邮件自然日总配额硬熔断配置
     */
    @Valid
    @NestedConfigurationProperty
    private MailQuotaProperties quota = new MailQuotaProperties();

    /**
     * 邮件出站幂等防重守卫配置
     */
    @Valid
    @NestedConfigurationProperty
    private MailIdempotentProperties idempotent = new MailIdempotentProperties();

    /**
     * 邮件主备账号自动顺延容灾配置
     */
    @Valid
    @NestedConfigurationProperty
    private MailFailoverProperties failover = new MailFailoverProperties();

    public MailProperties() {
        this.async.setThreadNamePrefix("mail-async-");
        this.async.setVirtualThreadNamePrefix("mail-vt-async-");
    }
}
