package io.github.atengk.boot.web.properties;

import io.github.atengk.boot.web.sign.enums.SignAlgorithm;
import io.github.atengk.boot.web.xss.XssMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Web 运行时增强模块配置属性
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.web")
public class WebProperties {

    /**
     * 是否启用 Web 运行时增强模块（默认 true）
     */
    private boolean enabled = true;

    /**
     * 链路追踪过滤器配置
     */
    @NestedConfigurationProperty
    private TraceProperties trace = new TraceProperties();

    /**
     * 是否将 Long、BigInteger 序列化为 String（防前端雪花 ID 精度失真）
     */
    private boolean longToString = true;

    /**
     * CORS 跨域自动化配置
     */
    @NestedConfigurationProperty
    private CorsProperties cors = new CorsProperties();

    /**
     * 链路追踪属性配置
     */
    @Getter
    @Setter
    public static class TraceProperties {

        /**
         * 是否启用链路追踪过滤器
         */
        private boolean enabled = true;

        /**
         * 链路追踪请求头键名
         */
        private String header = "X-Trace-Id";

        public String getHeaderName() {
            return header;
        }

        public void setHeaderName(String headerName) {
            this.header = headerName;
        }
    }

    /**
     * 跨域策略属性配置
     */
    @Getter
    @Setter
    public static class CorsProperties {

        /**
         * 是否启用全局 CORS 自动化装配
         */
        private boolean enabled = false;

        /**
         * 允许跨域请求的源域名模式列表
         */
        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));

        /**
         * 允许跨域请求的 HTTP 方法列表
         */
        private List<String> allowedMethods = new ArrayList<>(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        /**
         * 允许跨域请求携带的请求头列表
         */
        private List<String> allowedHeaders = new ArrayList<>(List.of("*"));

        /**
         * 是否允许跨域请求携带认证 Cookie 凭证
         */
        private Boolean allowCredentials = false;

        /**
         * 是否自动放行本地 localhost 与 127.0.0.1 跨域调试
         */
        private boolean allowLocalhost = true;

        /**
         * 允许向浏览器端暴露的自定义响应头列表
         */
        private List<String> exposedHeaders = new ArrayList<>(Arrays.asList("X-Trace-Id", "Retry-After", "X-Signature"));

        /**
         * 跨域预检请求缓存有效时间（强类型 Duration，默认 3600 秒）
         */
        private Duration maxAge = Duration.ofSeconds(3600);

        /**
         * 获取跨域预检请求缓存有效时间（秒，向后兼容）
         *
         * @return 缓存秒数
         */
        public Long getMaxAgeSeconds() {
            return maxAge != null ? maxAge.toSeconds() : 3600L;
        }

        /**
         * 设置跨域预检请求缓存有效时间（秒，向后兼容）
         *
         * @param maxAgeSeconds 缓存秒数
         */
        public void setMaxAgeSeconds(Long maxAgeSeconds) {
            this.maxAge = maxAgeSeconds != null ? Duration.ofSeconds(maxAgeSeconds) : Duration.ofSeconds(3600);
        }
    }

    /**
     * Web 接口审计日志切面配置
     */
    @NestedConfigurationProperty
    private LogProperties log = new LogProperties();

    /**
     * Web 日志切面属性配置
     */
    @Getter
    @Setter
    public static class LogProperties {

        /**
         * 是否启用 Web 接口审计日志切面
         */
        private boolean enabled = false;
    }

    /**
     * API 数据加签验签与防重放配置
     */
    @NestedConfigurationProperty
    private SignProperties sign = new SignProperties();

    /**
     * API 加签验签属性配置
     */
    @Getter
    @Setter
    public static class SignProperties {

        /**
         * 是否启用 API 双向加签验签切面与自动装配
         */
        private boolean enabled = false;

        /**
         * 默认签名算法（默认 HMAC_SHA256 对称哈希，可选 RSA 非对称算法、SM2 国密非对称算法）
         */
        private SignAlgorithm algorithm = SignAlgorithm.HMAC_SHA256;

        /**
         * 默认应用 ID（单应用缺省配置）
         */
        private String appId = "default";

        /**
         * 对称算法密钥（HMAC-SHA256 SecretKey）
         */
        private String secretKey;

        /**
         * 非对称算法公钥（RSA X.509 Base64 或 SM2 Hex/Base64 格式，用于入站验签）
         */
        private String publicKey;

        /**
         * 非对称算法私钥（RSA PKCS#8 Base64 或 SM2 Hex/Base64 格式，用于出站加签）
         */
        private String privateKey;

        /**
         * 请求时效防重放时间窗口（强类型 Duration，默认 60 秒）
         */
        private Duration expire = Duration.ofSeconds(60);

        /**
         * 请求体缓存最大字节数载荷上限（强类型 DataSize 防 OOM，默认 2MB）
         */
        private DataSize maxBodySize = DataSize.ofMegabytes(2);

        /**
         * 获取防重放窗口时间（秒，向后兼容）
         *
         * @return 超时秒数
         */
        public long getExpireSeconds() {
            return expire != null ? expire.toSeconds() : 60L;
        }

        /**
         * 设置防重放窗口时间（秒，向后兼容）
         *
         * @param expireSeconds 超时秒数
         */
        public void setExpireSeconds(long expireSeconds) {
            this.expire = Duration.ofSeconds(expireSeconds);
        }

        /**
         * 获取请求体最大缓存字节数（向后兼容）
         *
         * @return 字节数
         */
        public int getMaxBodySizeBytes() {
            return maxBodySize != null ? (int) maxBodySize.toBytes() : 2 * 1024 * 1024;
        }

        /**
         * 设置请求体最大缓存字节数（向后兼容）
         *
         * @param maxBodySizeBytes 字节数
         */
        public void setMaxBodySizeBytes(int maxBodySizeBytes) {
            this.maxBodySize = DataSize.ofBytes(maxBodySizeBytes);
        }

        /**
         * 应用标识请求头名称（默认 X-App-Id）
         */
        private String headerAppId = "X-App-Id";

        /**
         * 时间戳请求头/响应头名称（默认 X-Timestamp）
         */
        private String headerTimestamp = "X-Timestamp";

        /**
         * 随机防重放串请求头/响应头名称（默认 X-Nonce）
         */
        private String headerNonce = "X-Nonce";

        /**
         * 签名串请求头/响应头名称（默认 X-Signature）
         */
        private String headerSignature = "X-Signature";
    }

    /**
     * 请求体通用可重读缓存配置
     */
    @NestedConfigurationProperty
    private BodyCacheProperties bodyCache = new BodyCacheProperties();

    /**
     * HTTP Payload 采样安全日志配置
     */
    @NestedConfigurationProperty
    private PayloadLogProperties payloadLog = new PayloadLogProperties();

    /**
     * 请求体通用缓存属性配置
     */
    @Getter
    @Setter
    public static class BodyCacheProperties {

        /**
         * 请求体缓存最大字节数载荷上限（强类型 DataSize 防 OOM，默认 2MB）
         */
        private DataSize maxBodySize = DataSize.ofMegabytes(2);

        /**
         * 获取请求体最大缓存字节数（向后兼容）
         *
         * @return 字节数
         */
        public int getMaxBodySizeBytes() {
            return maxBodySize != null ? (int) maxBodySize.toBytes() : 2 * 1024 * 1024;
        }

        /**
         * 设置请求体最大缓存字节数（向后兼容）
         *
         * @param maxBodySizeBytes 字节数
         */
        public void setMaxBodySizeBytes(int maxBodySizeBytes) {
            this.maxBodySize = DataSize.ofBytes(maxBodySizeBytes);
        }
    }

    /**
     * Payload 采样日志属性配置
     */
    @Getter
    @Setter
    public static class PayloadLogProperties {

        /**
         * 是否启用 HTTP Payload 双向采样安全日志过滤器
         */
        private boolean enabled = false;

        /**
         * 单次最大采样截断字节数上限（强类型 DataSize，默认 4KB）
         */
        private DataSize maxPayloadSize = DataSize.ofKilobytes(4);

        /**
         * 采样日志排除路径白名单（Ant 风格匹配，如 /actuator/**）
         */
        private List<String> excludeUrls = new ArrayList<>();

        /**
         * 获取单次最大采样截断字节数（向后兼容）
         *
         * @return 字节数
         */
        public int getMaxPayloadSizeBytes() {
            return maxPayloadSize != null ? (int) maxPayloadSize.toBytes() : 4096;
        }

        /**
         * 设置单次最大采样截断字节数（向后兼容）
         *
         * @param maxPayloadSizeBytes 字节数
         */
        public void setMaxPayloadSizeBytes(int maxPayloadSizeBytes) {
            this.maxPayloadSize = DataSize.ofBytes(maxPayloadSizeBytes);
        }
    }

    /**
     * 结构化操作审计日志切面配置
     */
    @NestedConfigurationProperty
    private OperateLogProperties operateLog = new OperateLogProperties();

    /**
     * 结构化操作审计属性配置
     */
    @Getter
    @Setter
    public static class OperateLogProperties {

        /**
         * 是否启用结构化操作审计切面（默认 true）
         */
        private boolean enabled = true;
    }

    /**
     * K8s 云原生就绪探针排空与容器生命周期治理配置
     */
    @NestedConfigurationProperty
    private GracefulShutdownProperties shutdown = new GracefulShutdownProperties();

    /**
     * 容器优雅下线与流量排空配置属性
     */
    @Getter
    @Setter
    public static class GracefulShutdownProperties {

        /**
         * 是否启用云原生流量排空与生命周期治理（默认 true）
         */
        private boolean enabled = true;

        /**
         * 就绪探针置为拒绝后静默排空等待时间（强类型 Duration，默认 10 秒）
         */
        private Duration drainTimeout = Duration.ofSeconds(10);

        /**
         * 是否启用 SmartLifecycle 兜底保护（未配 preStop 收到 SIGTERM 时自动排空，默认 true）
         */
        private boolean smartLifecycleEnabled = true;

        /**
         * 是否启用 Actuator preStop 端点（默认 true，当类路径存在 Actuator 时生效）
         */
        private boolean actuatorEndpointEnabled = true;

        /**
         * 是否启用 Spring MVC preStop 控制器（为 null 时自动判定：无 Actuator 时自动降级启用）
         */
        private Boolean mvcEndpointEnabled;

        /**
         * Spring MVC 降级端点路径（默认 /pre-stop）
         */
        private String mvcEndpointPath = "/pre-stop";
    }

    /**
     * 长耗时慢请求性能告警配置
     */
    @NestedConfigurationProperty
    private SlowRequestProperties slowRequest = new SlowRequestProperties();

    /**
     * 慢请求监控属性配置
     */
    @Getter
    @Setter
    public static class SlowRequestProperties {

        /**
         * 是否启用慢请求性能告警过滤器（默认 true）
         */
        private boolean enabled = true;

        /**
         * 慢请求判定阈值（强类型 Duration，默认 3 秒）
         */
        private Duration threshold = Duration.ofSeconds(3);

        /**
         * 慢请求监控排除路径白名单（Ant 风格匹配，如 /actuator/**）
         */
        private List<String> excludeUrls = new ArrayList<>();

        /**
         * 获取判定阈值毫秒数（向后兼容）
         *
         * @return 毫秒数
         */
        public long getThresholdMillis() {
            return threshold != null ? threshold.toMillis() : 3000L;
        }

        /**
         * 设置判定阈值毫秒数（向后兼容）
         *
         * @param thresholdMillis 毫秒数
         */
        public void setThresholdMillis(long thresholdMillis) {
            this.threshold = Duration.ofMillis(thresholdMillis);
        }
    }

    /**
     * HTTP 安全响应头防御配置（OWASP / 等保三级基线）
     */
    @NestedConfigurationProperty
    private SecurityHeadersProperties securityHeaders = new SecurityHeadersProperties();

    /**
     * ETag 条件协商缓存配置
     */
    @NestedConfigurationProperty
    private EtagProperties etag = new EtagProperties();

    /**
     * XSS 跨站脚本安全清洗配置
     */
    @NestedConfigurationProperty
    private XssProperties xss = new XssProperties();

    /**
     * HTTP 安全响应头属性配置
     */
    @Getter
    @Setter
    public static class SecurityHeadersProperties {

        /**
         * 是否启用 HTTP 安全响应头过滤器（默认 true，开箱即用注入 OWASP 推荐标头）
         */
        private boolean enabled = true;

        /**
         * 是否注入 X-Content-Type-Options: nosniff（默认 true，防御 MIME 类型混淆嗅探攻击）
         */
        private boolean contentTypeOptions = true;

        /**
         * X-Frame-Options 响应头（默认 SAMEORIGIN，防御点击劫持；为 null 或空时不注入）
         */
        private String frameOptions = "SAMEORIGIN";

        /**
         * X-XSS-Protection 响应头（默认 1; mode=block，防御旧版浏览器反射型 XSS；为 null 或空时不注入）
         */
        private String xssProtection = "1; mode=block";

        /**
         * Referrer-Policy 响应头（默认 strict-origin-when-cross-origin，防御敏感 URL 泄露；为 null 或空时不注入）
         */
        private String referrerPolicy = "strict-origin-when-cross-origin";

        /**
         * Content-Security-Policy (CSP) 策略头（默认 null 不注入；显式配置时生效）
         */
        private String contentSecurityPolicy;

        /**
         * Permissions-Policy 权限策略头（默认 null 不注入；显式配置时生效）
         */
        private String permissionsPolicy;

        /**
         * 是否擦除或隐藏 Server 响应头版本指纹（默认 true）
         */
        private boolean removeServerHeader = true;
    }

    /**
     * ETag 条件协商缓存属性配置
     */
    @Getter
    @Setter
    public static class EtagProperties {

        /**
         * 是否启用 ShallowEtagHeaderFilter 协商缓存（默认 false，按需开启）
         */
        private boolean enabled = false;

        /**
         * 是否生成弱 ETag (W/"...")，默认 false 生成强 ETag
         */
        private boolean writeWeakETag = false;
    }

    /**
     * XSS 跨站脚本安全清洗属性配置
     */
    @Getter
    @Setter
    public static class XssProperties {

        /**
         * 是否启用 XSS 跨站脚本安全清洗（默认 false，按需开启）
         */
        private boolean enabled = false;

        /**
         * XSS 安全清洗模式（默认 ESCAPE 转义 HTML 实体；可选 STRIP 剔除标签）
         */
        private XssMode mode = XssMode.ESCAPE;

        /**
         * XSS 清洗排除路径白名单列表（Ant 风格匹配，如 /api/admin/announcement/**）
         */
        private List<String> excludeUrls = new ArrayList<>();
    }
}


