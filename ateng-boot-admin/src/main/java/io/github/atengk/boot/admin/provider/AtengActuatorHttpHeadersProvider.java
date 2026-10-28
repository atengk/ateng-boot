package io.github.atengk.boot.admin.provider;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.web.client.HttpHeadersProvider;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * SBA 服务端向客户端抓取 Actuator 端点时的 HTTP 标头提供者
 * 动态从实例注册元数据中提取 actuator-token 并挂载至 X-Actuator-Token 请求头；
 * 若 Metadata 中无配置，则保底读取服务端的全局默认 Token
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

        String token = null;
        if (instance.isRegistered()) {
            Registration registration = instance.getRegistration();
            if (registration != null) {
                Map<String, String> metadata = registration.getMetadata();
                if (metadata != null) {
                    token = metadata.get(METADATA_ACTUATOR_TOKEN);
                }
            }
        }

        // 若 Metadata 中未提供，保底读取服务端配置的全局兜底 Token
        if (!StringUtils.hasText(token) && properties != null) {
            token = properties.getDefaultActuatorToken();
        }

        if (StringUtils.hasText(token)) {
            headers.add(ACTUATOR_TOKEN_HEADER, token.trim());
        }

        return headers;
    }
}
