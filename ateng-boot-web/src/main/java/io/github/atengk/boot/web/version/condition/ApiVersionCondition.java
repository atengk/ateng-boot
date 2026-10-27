package io.github.atengk.boot.web.version.condition;

import io.github.atengk.boot.web.version.properties.ApiVersionProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.condition.RequestCondition;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 版本请求匹配条件
 * 实现 Spring MVC 原生 {@link RequestCondition} 契约，支持 URL 路径前缀（/v{version}/）与请求头（X-API-Version）双向提取与缺省版本回退
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Getter
public class ApiVersionCondition implements RequestCondition<ApiVersionCondition> {

    /**
     * URL 路径中提取 API 版本的正则表达式（严格以 /v{version} 前缀锚定开头，匹配如 /v1/ 或结尾 /v1）
     */
    private static final Pattern VERSION_PATH_PATTERN = Pattern.compile("^/v([0-9]+(?:\\.[0-9]+)*)(?:/|$)");

    /**
     * 当前接口声明的 API 版本号（如 "1", "2", "1.0"）
     */
    private final String version;

    /**
     * API 版本控制配置属性
     */
    private final ApiVersionProperties properties;

    public ApiVersionCondition(String version, ApiVersionProperties properties) {
        this.version = StringUtils.hasText(version) ? version.trim() : "1";
        this.properties = properties != null ? properties : new ApiVersionProperties();
    }

    /**
     * 合并类级别与方法级别的条件
     * 遵循 Spring MVC 规范，方法级注解优先于（覆盖）类级别注解
     *
     * @param other 方法级别的条件对象
     * @return 最终生效的条件
     */
    @Override
    public ApiVersionCondition combine(ApiVersionCondition other) {
        return other != null ? other : this;
    }

    /**
     * 根据当前 HTTP 请求进行版本匹配探测
     *
     * @param request 当前 HTTP 请求
     * @return 若匹配成功返回当前条件实例，否则返回 null
     */
    @Override
    public ApiVersionCondition getMatchingCondition(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return this;
        }

        String requestVersion = extractVersion(request);
        if (isVersionMatch(requestVersion, this.version)) {
            return this;
        }

        return null;
    }

    /**
     * 比较两个匹配条件的优先级（版本号更高的优先胜出）
     *
     * @param other   另一个待比较的条件
     * @param request 当前 HTTP 请求
     * @return 负数表示当前条件更优先，正数表示 other 更优先，0 表示相等
     */
    @Override
    public int compareTo(ApiVersionCondition other, HttpServletRequest request) {
        if (other == null) {
            return -1;
        }
        return compareVersion(other.version, this.version);
    }

    /**
     * 从 HTTP 请求中提取版本标识
     * 优先级：URL 路径前缀 > 请求头 > 缺省默认版本
     */
    private String extractVersion(HttpServletRequest request) {
        if (request == null) {
            return properties.getDefaultVersion();
        }

        // 1. 尝试从 URL 路径前缀中提取（如 /v1/user 或 /app/v1/user）
        if (properties.isPathPrefixEnabled()) {
            String uri = request.getRequestURI();
            if (StringUtils.hasText(uri)) {
                String contextPath = request.getContextPath();
                String path = (StringUtils.hasText(contextPath) && uri.startsWith(contextPath))
                        ? uri.substring(contextPath.length())
                        : uri;
                Matcher matcher = VERSION_PATH_PATTERN.matcher(path);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }

        // 2. 尝试从请求头中提取（如 X-API-Version: 2）
        if (StringUtils.hasText(properties.getHeader())) {
            String headerVersion = request.getHeader(properties.getHeader());
            if (StringUtils.hasText(headerVersion)) {
                return cleanVersion(headerVersion);
            }
        }

        // 3. 回退为配置的缺省默认版本
        return properties.getDefaultVersion();
    }

    /**
     * 清理版本号前缀（去除 'v' 或 'V'）并规范化
     */
    public static String cleanVersion(String version) {
        if (!StringUtils.hasText(version)) {
            return "";
        }
        String trimmed = version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            return trimmed.substring(1).trim();
        }
        return trimmed;
    }

    /**
     * 判定请求版本与声明版本是否匹配（基于语义化点分整数段比对）
     */
    public static boolean isVersionMatch(String requestVersion, String targetVersion) {
        String cleanReq = cleanVersion(requestVersion);
        String cleanTarget = cleanVersion(targetVersion);

        if (!StringUtils.hasText(cleanReq) || !StringUtils.hasText(cleanTarget)) {
            return false;
        }

        if (cleanReq.equalsIgnoreCase(cleanTarget)) {
            return true;
        }

        // 统一采用点分整数段比对（兼顾 1 与 1.0 的等价性，并防御 2.10 与 2.1 的浮点混淆）
        try {
            return compareVersion(cleanReq, cleanTarget) == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 语义化版本数值比对（v1 大于 v2 返回正数，v1 小于 v2 返回负数，相等返回 0）
     *
     * @param v1 版本1
     * @param v2 版本2
     * @return 比较结果数值
     */
    public static int compareVersion(String v1, String v2) {
        String s1 = cleanVersion(v1);
        String s2 = cleanVersion(v2);

        String[] parts1 = s1.split("\\.");
        String[] parts2 = s2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLen; i++) {
            String p1 = i < parts1.length ? parts1[i] : "0";
            String p2 = i < parts2.length ? parts2[i] : "0";

            try {
                int num1 = Integer.parseInt(p1);
                int num2 = Integer.parseInt(p2);
                int cmp = Integer.compare(num1, num2);
                if (cmp != 0) {
                    return cmp;
                }
            } catch (NumberFormatException e) {
                int cmp = p1.compareToIgnoreCase(p2);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiVersionCondition that = (ApiVersionCondition) o;
        return Objects.equals(cleanVersion(version), cleanVersion(that.version));
    }

    @Override
    public int hashCode() {
        return Objects.hash(cleanVersion(version));
    }

    @Override
    public String toString() {
        return "[ApiVersion: " + version + "]";
    }
}
