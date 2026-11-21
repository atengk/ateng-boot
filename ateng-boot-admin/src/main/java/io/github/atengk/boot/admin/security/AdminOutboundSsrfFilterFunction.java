package io.github.atengk.boot.admin.security;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.web.client.InstanceExchangeFilterFunction;
import io.github.atengk.boot.admin.admission.AdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Objects;

/**
 * Spring Boot Admin 出站 HTTP 代理与健康探测前 SSRF 二次重检过滤器
 * <p>
 * 顺序锚定在 SBA 原生 {@code rewriteEndpointUrlInstanceExchangeFilter}（{@code @Order(10)}）之后、
 * 重试/底层连接之前（默认 {@code @Order(15)}），确保拿到已解析为完整绝对 HTTP(S) URL 的目标地址并实时重检
 * DNS 解析与 CIDR 策略，纵深防御实例注册后才生效的 DNS Rebinding 攻击。
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class AdminOutboundSsrfFilterFunction implements InstanceExchangeFilterFunction, Ordered {

    /**
     * 默认排序优先级：位于 SBA URL 重写过滤器（10）之后、高阶连接/重试过滤器之前
     */
    public static final int DEFAULT_ORDER = 15;

    private final AdminInstanceAdmissionPolicy admissionPolicy;
    private final AtengAdminServerProperties properties;
    private final int order;

    /**
     * 基于准入策略与配置属性构造出站 SSRF 重检过滤器（默认顺序 {@link #DEFAULT_ORDER}）
     *
     * @param admissionPolicy 实例准入策略（不可为 null）
     * @param properties      Admin Server 配置属性
     */
    public AdminOutboundSsrfFilterFunction(AdminInstanceAdmissionPolicy admissionPolicy,
                                           AtengAdminServerProperties properties) {
        this(admissionPolicy, properties, DEFAULT_ORDER);
    }

    /**
     * 基于准入策略、配置属性与自定义过滤器顺序构造出站 SSRF 重检过滤器
     *
     * @param admissionPolicy 实例准入策略（不可为 null）
     * @param properties      Admin Server 配置属性
     * @param order           过滤器执行顺序
     */
    public AdminOutboundSsrfFilterFunction(AdminInstanceAdmissionPolicy admissionPolicy,
                                           AtengAdminServerProperties properties,
                                           int order) {
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy must not be null");
        this.properties = properties;
        this.order = order;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public Mono<ClientResponse> filter(Instance instance, ClientRequest request, ExchangeFunction next) {
        if (properties != null && !properties.isOutboundSsrfCheckEnabled()) {
            return next.exchange(request);
        }
        URI targetUri = request != null ? request.url() : null;
        String targetUrl = targetUri != null ? targetUri.toString() : null;
        if (targetUri == null
                || !targetUri.isAbsolute()
                || !StringUtils.hasText(targetUrl)
                || !admissionPolicy.isOutboundUrlAllowed(targetUrl)) {
            String instanceId = instance != null && instance.getId() != null
                    ? instance.getId().getValue()
                    : "unknown";
            log.warn("[Outbound SSRF Guard] 阻断高危出站请求（疑为 DNS 重绑定、空/相对 URI 或违规目标）: instanceId=[{}], url=[{}]",
                    instanceId, targetUrl);
            return Mono.error(new BusinessException(
                    CommonResultCode.FORBIDDEN,
                    "Outbound SSRF guard blocked request to unsafe URL: " + targetUrl
            ));
        }
        return next.exchange(request);
    }
}
