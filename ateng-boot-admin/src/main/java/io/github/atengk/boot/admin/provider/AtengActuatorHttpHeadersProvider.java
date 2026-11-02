package io.github.atengk.boot.admin.provider;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.web.client.HttpHeadersProvider;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * SBA 服务端向客户端抓取 Actuator 端点时的多模态 HTTP 标头提供者
 * 按四阶明确优先级自适应支持多种业界认证协议：
 * 1. 优先识别实例元数据中的 user.name 与 user.password 并挂载标准 Authorization: Basic <base64> 请求头；
 * 2. 其次识别 bearer-token 或 jwt-token 并挂载 Authorization: Bearer <token> 请求头；
 * 3. 再次识别 actuator-token 挂载自定义 X-Actuator-Token 请求头；
 * 4. 最后兜底读取服务端配置的全局 default-actuator-token。
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class AtengActuatorHttpHeadersProvider implements HttpHeadersProvider {

    /**
     * Actuator 安全校验 HTTP 请求头键名
     */
    public static final String ACTUATOR_TOKEN_HEADER = "X-Actuator-Token";

    /**
     * 实例元数据中存储 Actuator Token 的键名
     */
    public static final String METADATA_ACTUATOR_TOKEN = "actuator-token";

    /**
     * 实例元数据中存储 Basic 认证用户名的键名
     */
    public static final String METADATA_USER_NAME = "user.name";

    /**
     * 实例元数据中存储 Basic 认证密码的键名
     */
    public static final String METADATA_USER_PASSWORD = "user.password";

    /**
     * 实例元数据中存储 Spring Security Basic 认证用户名的扩展键名
     */
    public static final String METADATA_SPRING_SECURITY_USER_NAME = "spring.security.user.name";

    /**
     * 实例元数据中存储 Spring Security Basic 认证密码的扩展键名
     */
    public static final String METADATA_SPRING_SECURITY_USER_PASSWORD = "spring.security.user.password";

    /**
     * 实例元数据中存储 Bearer 认证 Token 的键名
     */
    public static final String METADATA_BEARER_TOKEN = "bearer-token";

    /**
     * 实例元数据中存储 JWT Bearer 认证 Token 的扩展键名
     */
    public static final String METADATA_JWT_TOKEN = "jwt-token";

    private final AtengAdminServerProperties properties;

    public AtengActuatorHttpHeadersProvider(AtengAdminServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public HttpHeaders getHeaders(Instance instance) {
        HttpHeaders headers = new HttpHeaders();
        if (instance == null) {
            return headers;
        }

        Map<String, String> metadata = null;
        if (instance.isRegistered()) {
            Registration registration = instance.getRegistration();
            if (registration != null) {
                metadata = registration.getMetadata();
            }
        }

        // 1. 第一优先级：Basic Authentication (user.name / user.password)
        if (metadata != null) {
            String username = metadata.get(METADATA_USER_NAME);
            if (!StringUtils.hasText(username)) {
                username = metadata.get(METADATA_SPRING_SECURITY_USER_NAME);
            }
            String password = metadata.get(METADATA_USER_PASSWORD);
            if (password == null) {
                password = metadata.get(METADATA_SPRING_SECURITY_USER_PASSWORD);
            }
            if (StringUtils.hasText(username) && password != null) {
                String credentials = username.trim() + ":" + password;
                String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                headers.set(HttpHeaders.AUTHORIZATION, basicAuth);
                return headers;
            }

            // 2. 第二优先级：Bearer / JWT Authentication (bearer-token / jwt-token)
            String bearerToken = metadata.get(METADATA_BEARER_TOKEN);
            if (!StringUtils.hasText(bearerToken)) {
                bearerToken = metadata.get(METADATA_JWT_TOKEN);
            }
            if (StringUtils.hasText(bearerToken)) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken.trim());
                return headers;
            }

            // 3. 第三优先级：实例自定义 Actuator Token (actuator-token)
            String actuatorToken = metadata.get(METADATA_ACTUATOR_TOKEN);
            if (StringUtils.hasText(actuatorToken)) {
                headers.set(ACTUATOR_TOKEN_HEADER, actuatorToken.trim());
                return headers;
            }
        }

        // 4. 第四优先级：服务端全局兜底 default-actuator-token
        if (properties != null && StringUtils.hasText(properties.getDefaultActuatorToken())) {
            headers.set(ACTUATOR_TOKEN_HEADER, properties.getDefaultActuatorToken().trim());
            return headers;
        }

        return headers;
    }
}
