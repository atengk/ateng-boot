package io.github.atengk.boot.web.properties;

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
}
