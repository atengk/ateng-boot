package io.github.atengk.boot.admin.security;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.web.client.InstanceExchangeFilterFunction;
import de.codecentric.boot.admin.server.web.client.InstanceExchangeFilterFunctions;
import de.codecentric.boot.admin.server.web.client.InstanceWebClient;
import io.github.atengk.boot.admin.admission.DefaultAdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.HostAddressResolver;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdminOutboundSsrfFilterFunction 出站 SSRF 重检过滤器单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminOutboundSsrfFilterFunction 出站 SSRF 重检单元测试")
class AdminOutboundSsrfFilterFunctionTest {

    @Test
    @DisplayName("当域名在出站阶段发生 DNS Rebinding 指向高危内网/元数据 IP 时，阻断请求且绝不触发底层网络调用")
    void shouldBlockDnsReboundTargetBeforeExchangeExecution() {
        AtomicReference<String> dynamicResolvedIp = new AtomicReference<>("10.20.1.5");
        HostAddressResolver dynamicResolver = host ->
                new InetAddress[]{InetAddress.getByName(dynamicResolvedIp.get())};

        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties, dynamicResolver);
        AdminOutboundSsrfFilterFunction filterFunction = new AdminOutboundSsrfFilterFunction(policy, properties);

        Instance instance = Instance.create(InstanceId.of("inst-rebind"))
                .register(Registration.create("rebind-svc", "http://dynamic-svc.internal:8080/actuator/health")
                        .build());
        ClientRequest request = ClientRequest.create(HttpMethod.GET,
                URI.create("http://dynamic-svc.internal:8080/actuator/health")).build();

        AtomicBoolean exchangeCalled = new AtomicBoolean(false);
        ExchangeFunction next = req -> {
            exchangeCalled.set(true);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };

        // 1. 首次合法解析 IP，允许发起出站探测
        ClientResponse okResp = filterFunction.filter(instance, request, next).block();
        assertThat(okResp).isNotNull();
        assertThat(okResp.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchangeCalled.get()).isTrue();

        // 2. 模拟 DNS Rebinding 切换为云元数据 169.254.169.254
        exchangeCalled.set(false);
        dynamicResolvedIp.set("169.254.169.254");

        assertThatThrownBy(() -> filterFunction.filter(instance, request, next).block())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Outbound SSRF");
        assertThat(exchangeCalled.get()).isFalse();
    }

    @Test
    @DisplayName("outboundSsrfCheckEnabled=false 时跳过出站重检开关控制生效")
    void shouldBypassOutboundCheckWhenDisabled() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setOutboundSsrfCheckEnabled(false);
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);
        AdminOutboundSsrfFilterFunction filterFunction = new AdminOutboundSsrfFilterFunction(policy, properties);

        Instance instance = Instance.create(InstanceId.of("inst-bypass"))
                .register(Registration.create("bypass-svc", "http://169.254.169.254/latest/meta-data/").build());
        ClientRequest request = ClientRequest.create(HttpMethod.GET,
                URI.create("http://169.254.169.254/latest/meta-data/")).build();

        AtomicBoolean exchangeCalled = new AtomicBoolean(false);
        ClientResponse resp = filterFunction.filter(instance, request, req -> {
            exchangeCalled.set(true);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }).block();

        assertThat(exchangeCalled.get()).isTrue();
        assertThat(resp).isNotNull();
    }

    @Test
    @DisplayName("默认顺序位于 SBA rewriteEndpointUrl(@Order(10)) 之后，经完整 InstanceWebClient 解析相对路径 'health' 时安全地址放行、重绑定阻断，且拦截空/相对 URI")
    void shouldExecuteAfterRewriteEndpointUrlAndRejectBlankOrRelativeUris() {
        AtomicReference<String> dynamicResolvedIp = new AtomicReference<>("10.20.1.5");
        HostAddressResolver resolver = host -> new InetAddress[]{InetAddress.getByName(dynamicResolvedIp.get())};

        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties, resolver);
        AdminOutboundSsrfFilterFunction filterFunction = new AdminOutboundSsrfFilterFunction(policy, properties);

        // 1. 验证顺序契约（15 > SBA rewriteEndpointUrl 默认 10）与直接空/相对 URI 拒绝
        assertThat(filterFunction.getOrder()).isGreaterThan(10);
        Instance instance = Instance.create(InstanceId.of("inst-chain"))
                .register(Registration.create("chain-svc", "http://dynamic-svc.internal:8080/actuator/health")
                        .managementUrl("http://dynamic-svc.internal:8080/actuator")
                        .build());
        ExchangeFunction failIfReached = req -> Mono.error(new AssertionError("Should not execute exchange for relative/empty URI"));
        ClientRequest emptyRequest = ClientRequest.create(HttpMethod.GET, URI.create("")).build();
        ClientRequest relativeRequest = ClientRequest.create(HttpMethod.GET, URI.create("health")).build();
        assertThatThrownBy(() -> filterFunction.filter(instance, emptyRequest, failIfReached).block())
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> filterFunction.filter(instance, relativeRequest, failIfReached).block())
                .isInstanceOf(BusinessException.class);

        // 2. 模拟 SBA 过滤器排序栈：@Order(10) rewriteEndpointUrl + AdminOutboundSsrfFilterFunction(@Order(15))
        record OrderedFilter(InstanceExchangeFilterFunction delegate, int order)
                implements InstanceExchangeFilterFunction, Ordered {
            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public Mono<ClientResponse> filter(Instance i, ClientRequest r, ExchangeFunction n) {
                return delegate.filter(i, r, n);
            }
        }
        List<InstanceExchangeFilterFunction> filters = new ArrayList<>();
        filters.add(filterFunction);
        filters.add(new OrderedFilter(InstanceExchangeFilterFunctions.rewriteEndpointUrl(), 10));
        AnnotationAwareOrderComparator.sort(filters);

        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        WebClient.Builder webClientBuilder = WebClient.builder().exchangeFunction(req -> {
            downstreamCalled.set(true);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        });
        InstanceWebClient client = InstanceWebClient.builder(webClientBuilder)
                .filters(f -> f.addAll(filters))
                .build();

        // 安全 IP 下，传入 SBA 相对路径 "health" 先经 Order(10) 改写为绝对 URI，再通过 Order(15) 出站重检并成功调用下游
        ClientResponse okResp = client.instance(instance).get().uri("health").exchangeToMono(Mono::just).block();
        assertThat(okResp).isNotNull();
        assertThat(okResp.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downstreamCalled.get()).isTrue();

        // DNS Rebinding 后同样传入相对路径 "health"，在 rewrite 之后被 Order(15) 精准拦截且不触发下游调用
        downstreamCalled.set(false);
        dynamicResolvedIp.set("169.254.169.254");
        assertThatThrownBy(() -> client.instance(instance).get().uri("health").exchangeToMono(Mono::just).block())
                .isInstanceOf(BusinessException.class);
        assertThat(downstreamCalled.get()).isFalse();
    }
}
