package io.github.atengk.boot.web.properties;

import io.github.atengk.boot.web.audit.OperateLogUrlStrategy;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 审计日志与请求参数默认敏感字段脱敏基线
     */
    private static final List<String> DEFAULT_SENSITIVE_EXCLUDE_PARAMS = List.of(
            "password", "passwd", "pwd", "oldPassword", "newPassword", "confirmPassword",
            "secret", "token", "accessToken", "refreshToken", "authorization"
    );

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
     * Jackson 序列化增强配置
     */
    @NestedConfigurationProperty
    private JacksonProperties jackson = new JacksonProperties();

    /**
     * 全局授信反向代理 IP / CIDR 网段列表（默认包含本地环回 127.0.0.1 与 ::1）
     * 启动期自动与 ateng.boot.web.ip-access-control.trusted-proxies 合并并同步至 IpUtil 默认防伪造基线
     */
    private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1"));

    /**
     * CORS 跨域自动化配置
     */
    @NestedConfigurationProperty
    private CorsProperties cors = new CorsProperties();

    /**
     * Jackson 序列化属性配置
     */
    @Getter
    @Setter
    public static class JacksonProperties {

        /**
         * 是否将 BigDecimal 按平原十进制输出，禁用科学计数法（默认 true）
         */
        private boolean writeBigDecimalAsPlain = true;
    }

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

        /**
         * 入站链路追踪标识最大允许长度（默认 64；超出或含非法字符时丢弃并重生成安全 UUID）
         */
        private int maxLength = 64;

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
        private List<String> exposedHeaders = new ArrayList<>(Arrays.asList(
                "X-Trace-Id",
                "Retry-After",
                "X-Signature",
                "X-Timestamp",
                "X-Nonce",
                "X-Encrypt-Key",
                "X-Encrypt-Iv",
                "X-Key-Id",
                "X-Encrypt-Alg"
        ));

        /**
         * 跨域预检请求缓存有效时间（强类型 Duration，默认 3600 秒）
         */
        private Duration maxAge = Duration.ofSeconds(3600);
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

        /**
         * 全局默认敏感排除参数列表（与 @WebLog#excludeParams 合并并忽略大小写匹配）
         */
        private List<String> defaultExcludeParams = new ArrayList<>(DEFAULT_SENSITIVE_EXCLUDE_PARAMS);
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
         * RSA 算法专属公钥（多算法并存场景优先使用，未配置时回退读取 publicKey）
         */
        private String rsaPublicKey;

        /**
         * RSA 算法专属私钥（多算法并存场景优先使用，未配置时回退读取 privateKey）
         */
        private String rsaPrivateKey;

        /**
         * 国密 SM2 算法专属公钥（多算法并存场景优先使用，未配置时回退读取 publicKey）
         */
        private String sm2PublicKey;

        /**
         * 国密 SM2 算法专属私钥（多算法并存场景优先使用，未配置时回退读取 privateKey）
         */
        private String sm2PrivateKey;

        /**
         * 请求时效防重放时间窗口（强类型 Duration，默认 60 秒）
         */
        private Duration expire = Duration.ofSeconds(60);

        /**
         * 请求体缓存最大字节数载荷上限（强类型 DataSize 防 OOM，默认 2MB）
         */
        private DataSize maxBodySize = DataSize.ofMegabytes(2);

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
         * Nonce 防重放缓存 Key 默认前缀
         */
        public static final String DEFAULT_NONCE_PREFIX = "ateng:sign:nonce:";

        /**
         * 签名串请求头/响应头名称（默认 X-Signature）
         */
        private String headerSignature = "X-Signature";

        /**
         * Nonce 防重放缓存 Key 前缀（默认 ateng:sign:nonce:）
         */
        private String noncePrefix = DEFAULT_NONCE_PREFIX;

        /**
         * 分布式 Nonce 防重放存储异常时的降级策略（默认 false 强安全阻断；设为 true 时降级至本地内存锁）
         */
        private boolean failOpen = false;
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
         * 默认保存的 User-Agent 最大字符长度
         */
        public static final int DEFAULT_USER_AGENT_MAX_LENGTH = 512;

        /**
         * 是否启用结构化操作审计切面（默认 true）
         */
        private boolean enabled = true;

        /**
         * 全局默认敏感排除参数列表（与 @OperateLog#excludeParams 合并并忽略大小写匹配）
         */
        private List<String> defaultExcludeParams = new ArrayList<>(DEFAULT_SENSITIVE_EXCLUDE_PARAMS);

        /**
         * 是否在进入目标方法前同步采集 HTTP User-Agent 请求头（默认 true）
         */
        private boolean collectUserAgent = true;

        /**
         * 是否使用轻量原生 UserAgentUtil 自动解析浏览器与操作系统名称（默认 true；仅在 collectUserAgent 开启时生效）
         */
        private boolean parseUserAgent = true;

        /**
         * 采集保存的 User-Agent 最大长度限制（默认 512，防超长恶意头污染存储；<= 0 时回退默认上限 512）
         */
        private int userAgentMaxLength = DEFAULT_USER_AGENT_MAX_LENGTH;

        /**
         * 全局默认请求 URL 采集策略（默认 RAW_URI 保持 100% 向后兼容；设为 BEST_MATCHING_PATTERN 时全局采集 Spring MVC 路由模板）
         */
        private OperateLogUrlStrategy urlStrategy = OperateLogUrlStrategy.RAW_URI;

        /**
         * 自动启用路由模板采集（BEST_MATCHING_PATTERN）的 Ant 风格路径模式列表（默认空列表；
         * 当方法或类级 @OperateLog 未显式覆写策略时，命中列表的请求自动切换为路由模板模式，兼顾零侵入脱敏与常规接口兼容）
         */
        private List<String> templateUrls = new ArrayList<>();
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
         * 就绪探针置为拒绝后静默排空等待总上限（强类型 Duration，默认 10 秒）
         */
        private Duration drainTimeout = Duration.ofSeconds(10);

        /**
         * K8s 路由摘除传播静默等待期（第一阶段；未配置时默认与 drainTimeout 一致以保持向后兼容）
         */
        private Duration quietPeriod;

        /**
         * 在途请求自适应排空轮询步长（第二阶段，默认 100ms；最小毫秒级粒度，小于 1ms 时自动回退为 100ms 防止忙等）
         */
        private Duration pollInterval = Duration.ofMillis(100);

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
     * 启动一体化就绪总览卡片配置
     */
    @NestedConfigurationProperty
    private ReadyCardProperties readyCard = new ReadyCardProperties();

    /**
     * 生产环境安全防呆自检配置
     */
    @NestedConfigurationProperty
    private SanityCheckProperties sanityCheck = new SanityCheckProperties();

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

        /**
         * 获取是否生成弱 ETag
         *
         * @return true 生成弱 ETag
         */
        public boolean isWriteWeakETag() {
            return writeWeakETag;
        }

        /**
         * 设置是否生成弱 ETag
         *
         * @param writeWeakETag true 生成弱 ETag
         */
        public void setWriteWeakETag(boolean writeWeakETag) {
            this.writeWeakETag = writeWeakETag;
        }

        /**
         * 获取是否生成弱 ETag（兼容标准驼峰 write-weak-etag 属性绑定）
         *
         * @return true 生成弱 ETag
         */
        public boolean isWriteWeakEtag() {
            return writeWeakETag;
        }

        /**
         * 设置是否生成弱 ETag（兼容标准驼峰 write-weak-etag 属性绑定）
         *
         * @param writeWeakEtag true 生成弱 ETag
         */
        public void setWriteWeakEtag(boolean writeWeakEtag) {
            this.writeWeakETag = writeWeakEtag;
        }
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

        /**
         * 是否对 HTTP 请求头执行 XSS 清洗（默认 false，按需开启）
         */
        private boolean cleanHeaders = false;

        /**
         * 请求头 XSS 清洗排除白名单列表（大小写不敏感匹配，默认排除常见鉴权、媒体类型、签名及传输层加密标头）
         */
        private List<String> excludeHeaders = new ArrayList<>(List.of(
                "Authorization", "Cookie", "X-Signature", "Content-Type", "Accept",
                "X-Encrypt-Key", "X-Encrypt-Iv", "X-Key-Id", "X-Encrypt-Alg"
        ));
    }

    /**
     * 启动一体化就绪总览卡片属性配置
     */
    @Getter
    @Setter
    public static class ReadyCardProperties {

        /**
         * 是否启用服务完全启动就绪 ASCII 卡片输出（默认 true）
         */
        private boolean enabled = true;
    }

    /**
     * API 传输层混合加解密配置
     */
    @NestedConfigurationProperty
    private CryptoProperties crypto = new CryptoProperties();

    /**
     * API 传输层混合加解密属性配置
     */
    @Getter
    @Setter
    public static class CryptoProperties {

        /**
         * 是否启用 API 传输层请求/响应体混合加解密引擎（默认 false，按需 Opt-in 开启）
         */
        private boolean enabled = false;

        /**
         * 全局默认混合加密套件（默认 RSA_AES_GCM；可选国密 SM2_SM4_GCM）
         */
        private CryptoAlgorithm algorithm = CryptoAlgorithm.RSA_AES_GCM;

        /**
         * 默认应用标识（单应用缺省配置，默认 default）
         */
        private String appId = "default";

        /**
         * 当前出站加密默认使用的活跃密钥版本标识（默认 v1，支持多版本平滑轮换）
         */
        private String activeKeyId = "v1";

        /**
         * 加密请求/响应体最大允许载荷字节上限（强类型 DataSize 防爆，默认 2MB；入站超限返回 413）
         */
        private DataSize maxBodySize = DataSize.ofMegabytes(2);

        /**
         * 是否开启 AEAD 附加认证数据 AAD 绑定（默认 false；开启后绑定 appId|keyId|algorithm 元数据防头部替换）
         */
        private boolean bindAad = false;

        /**
         * 超限或解密校验失败时是否同步输出物理 HTTP 状态码（默认 false 遵守 ADR-0004 返回 HTTP 200 + 业务状态码；设为 true 时返回 HTTP 413/400/500）
         */
        private boolean useHttpStatus = false;

        /**
         * 通用服务端解密私钥（RSA PKCS#8 或 SM2 Hex/Base64/PEM）
         */
        private String serverPrivateKey;

        /**
         * 通用服务端加密公钥（供服务端自检或示例加密辅助使用，非运行时强制）
         */
        private String serverPublicKey;

        /**
         * 通用客户端加密公钥（RSA X.509 或 SM2 Hex/Base64/PEM，用于服务端出站响应加密封装会话密钥）
         */
        private String clientPublicKey;

        /**
         * RSA 算法专属服务端私钥（多算法共存优先使用，未配时回退 serverPrivateKey）
         */
        private String rsaServerPrivateKey;

        /**
         * RSA 算法专属服务端公钥（供示例/客户端辅助封装使用，未配时回退 serverPublicKey）
         */
        private String rsaServerPublicKey;

        /**
         * RSA 算法专属客户端公钥（出站响应加密优先使用，未配时回退 clientPublicKey）
         */
        private String rsaClientPublicKey;

        /**
         * 国密 SM2 算法专属服务端私钥（多算法共存优先使用，未配时回退 serverPrivateKey）
         */
        private String sm2ServerPrivateKey;

        /**
         * 国密 SM2 算法专属服务端公钥（供示例/客户端辅助封装使用，未配时回退 serverPublicKey）
         */
        private String sm2ServerPublicKey;

        /**
         * 国密 SM2 算法专属客户端公钥（出站响应加密优先使用，未配时回退 clientPublicKey）
         */
        private String sm2ClientPublicKey;

        /**
         * 多版本密钥轮换配置表（keyId -> 对应版本密钥配置，支持历史版本并行解密与下线停用）
         */
        private Map<String, CryptoKeyVersionProperties> keys = new LinkedHashMap<>();

        /**
         * 应用标识请求头名称（默认 X-App-Id）
         */
        private String headerAppId = "X-App-Id";

        /**
         * 封装会话密钥请求/响应头名称（默认 X-Encrypt-Key）
         */
        private String headerEncryptKey = "X-Encrypt-Key";

        /**
         * 随机 GCM IV 请求/响应头名称（默认 X-Encrypt-Iv）
         */
        private String headerEncryptIv = "X-Encrypt-Iv";

        /**
         * 密钥轮换版本标识请求/响应头名称（默认 X-Key-Id）
         */
        private String headerKeyId = "X-Key-Id";

        /**
         * 加密算法套件标识请求/响应头名称（默认 X-Encrypt-Alg）
         */
        private String headerEncryptAlg = "X-Encrypt-Alg";
    }

    /**
     * 多版本加解密密钥条目配置
     */
    @Getter
    @Setter
    public static class CryptoKeyVersionProperties {

        /**
         * 当前密钥版本是否启用（设为 false 表示已过期/吊销，入站携带该 keyId 将被拒绝）
         */
        private boolean enabled = true;

        /**
         * 通用服务端解密私钥
         */
        private String serverPrivateKey;

        /**
         * 通用服务端加密公钥
         */
        private String serverPublicKey;

        /**
         * 通用客户端加密公钥
         */
        private String clientPublicKey;

        /**
         * RSA 专属服务端解密私钥
         */
        private String rsaServerPrivateKey;

        /**
         * RSA 专属服务端加密公钥
         */
        private String rsaServerPublicKey;

        /**
         * RSA 专属客户端加密公钥
         */
        private String rsaClientPublicKey;

        /**
         * SM2 专属服务端解密私钥
         */
        private String sm2ServerPrivateKey;

        /**
         * SM2 专属服务端加密公钥
         */
        private String sm2ServerPublicKey;

        /**
         * SM2 专属客户端加密公钥
         */
        private String sm2ClientPublicKey;
    }

    /**
     * 生产环境安全防呆自检属性配置
     */
    @Getter
    @Setter
    public static class SanityCheckProperties {

        /**
         * 是否启用生产环境高危配置安全防呆自检（默认 true）
         */
        private boolean enabled = true;
    }
}


