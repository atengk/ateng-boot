package io.github.atengk.boot.web.properties;

import io.github.atengk.boot.web.sign.enums.SignAlgorithm;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
    private TraceProperties trace = new TraceProperties();

    /**
     * 是否将 Long、BigInteger 序列化为 String（防前端雪花 ID 精度失真）
     */
    private boolean longToString = true;

    /**
     * CORS 跨域自动化配置
     */
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
         * 跨域预检请求缓存有效时间（秒）
         */
        private Long maxAge = 3600L;
    }

    /**
     * Web 接口审计日志切面配置
     */
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
         * 默认签名算法（默认 HMAC_SHA256 对称哈希，可选 RSA 非对称算法）
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
         * 非对称算法公钥（RSA X.509 Base64 格式，用于入站验签）
         */
        private String publicKey;

        /**
         * 非对称算法私钥（RSA PKCS#8 Base64 格式，用于出站加签）
         */
        private String privateKey;

        /**
         * 请求时效防重放时间窗口（秒，默认 60 秒）
         */
        private long expireSeconds = 60L;

        /**
         * 请求体缓存最大字节数（防 OOM，默认 2MB）
         */
        private int maxBodySize = 2 * 1024 * 1024;

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
}
