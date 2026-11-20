package io.github.atengk.boot.web.version.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API 多版本控制运行时配置属性
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.web.api-version")
public class ApiVersionProperties {

    /**
     * 是否启用 API 多版本控制分流功能
     */
    private boolean enabled = true;

    /**
     * 缺省默认版本（当请求既未携带 URL 路径版本也未携带请求头版本时回退的默认版本，如 "1"）
     */
    private String defaultVersion = "1";

    /**
     * 用于提取版本标识的 HTTP 请求头名称（如 "X-API-Version"）
     */
    private String header = "X-API-Version";

    /**
     * 是否启用 URL 路径前缀版本匹配（如 /v1/user 或 /v2/user）
     */
    private boolean pathPrefixEnabled = true;
}
