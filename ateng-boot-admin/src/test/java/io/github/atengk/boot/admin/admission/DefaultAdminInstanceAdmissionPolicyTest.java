package io.github.atengk.boot.admin.admission;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.properties.DnsResolutionMode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DefaultAdminInstanceAdmissionPolicy 单元测试
 * 覆盖密钥核验、169.254 云元数据阻断、本地环回控制与正则白名单过滤
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("DefaultAdminInstanceAdmissionPolicy 默认准入策略单元测试")
class DefaultAdminInstanceAdmissionPolicyTest {

    private static final String VALID_SECRET = "CLUSTER_SECRET_2026";

    private AtengAdminServerProperties createProperties() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setClientSecret(VALID_SECRET);
        properties.setAllowLocalInstances(true);
        return properties;
    }

    @Test
    @DisplayName("合法实例携带匹配密钥与安全URL时评估通过")
    void shouldAdmitValidRegistration() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        InstanceRegistration registration = InstanceRegistration.builder()
                .name("order-service")
                .serviceUrl("http://order-service:8080/")
                .managementUrl("http://order-service:8081/actuator")
                .healthUrl("http://order-service:8081/actuator/health")
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        AdminAdmissionResult result = policy.evaluate(registration, request);

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.isRejected()).isFalse();
        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(result.getReason()).isNull();
    }

    @Test
    @DisplayName("客户端未提供通信密钥或密钥不匹配时直接 403 拒绝")
    void shouldRejectWhenSecretMissingOrMismatched() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        InstanceRegistration registration = InstanceRegistration.of(
                "order-service",
                "http://order-service:8080/",
                "http://order-service:8081/actuator",
                "http://order-service:8081/actuator/health"
        );

        // 1. 无标头
        MockHttpServletRequest requestWithoutSecret = new MockHttpServletRequest("POST", "/instances");
        AdminAdmissionResult res1 = policy.evaluate(registration, requestWithoutSecret);
        assertThat(res1.isRejected()).isTrue();
        assertThat(res1.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res1.getReason()).contains("Invalid X-Admin-Client-Secret");

        // 2. 错误标头
        MockHttpServletRequest requestWithWrongSecret = new MockHttpServletRequest("POST", "/instances");
        requestWithWrongSecret.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, "WRONG");
        AdminAdmissionResult res2 = policy.evaluate(registration, requestWithWrongSecret);
        assertThat(res2.isRejected()).isTrue();
        assertThat(res2.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res2.getReason()).contains("Invalid X-Admin-Client-Secret");
    }

    @Test
    @DisplayName("硬阻断 169.254.169.254 云元数据与 169.254.x.x 链路本地地址")
    void shouldHardBlockCloudMetadataAddress() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        InstanceRegistration registration = InstanceRegistration.builder()
                .name("evil-app")
                .serviceUrl("http://169.254.169.254/latest/meta-data/")
                .managementUrl("http://169.254.169.254/latest/meta-data/")
                .healthUrl("http://169.254.169.254/latest/meta-data/")
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        AdminAdmissionResult result = policy.evaluate(registration, request);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.getReason()).contains("URL forbidden by SSRF Guard Policy");
    }

    @Test
    @DisplayName("当 allowLocalInstances=false 时拦截本地环回地址；为 true 时放行")
    void shouldEnforceLoopbackPolicy() {
        AtengAdminServerProperties properties = createProperties();
        properties.setAllowLocalInstances(false);
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        InstanceRegistration registration = InstanceRegistration.of(
                "local-app",
                "http://127.0.0.1:8080/",
                "http://127.0.0.1:8080/actuator",
                "http://127.0.0.1:8080/actuator/health"
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        AdminAdmissionResult resultBlocked = policy.evaluate(registration, request);
        assertThat(resultBlocked.isRejected()).isTrue();

        // 重新开启环回允许
        properties.setAllowLocalInstances(true);
        AdminAdmissionResult resultAllowed = policy.evaluate(registration, request);
        assertThat(resultAllowed.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("配置 allowed-url-patterns 正则白名单时精确匹配过滤")
    void shouldEnforceAllowedUrlPatterns() {
        AtengAdminServerProperties properties = createProperties();
        properties.setAllowedUrlPatterns(List.of("^https?://.*\\.corp\\.internal(:\\d+)?/.*$"));
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        // 1. 命中白名单正则
        InstanceRegistration validReg = InstanceRegistration.of(
                "corp-service",
                "http://order.corp.internal:8080/",
                "http://order.corp.internal:8080/actuator",
                "http://order.corp.internal:8080/actuator/health"
        );
        assertThat(policy.evaluate(validReg, request).isAllowed()).isTrue();

        // 2. 未命中白名单正则
        InstanceRegistration invalidReg = InstanceRegistration.of(
                "external-service",
                "http://unknown-host.com:8080/",
                "http://unknown-host.com:8080/actuator",
                "http://unknown-host.com:8080/actuator/health"
        );
        assertThat(policy.evaluate(invalidReg, request).isRejected()).isTrue();
    }

    @Test
    @DisplayName("拒绝 null registration 载荷防止空注册绕过")
    void shouldRejectNullRegistration() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        AdminAdmissionResult result = policy.evaluate(null, request);
        assertThat(result.isRejected()).isTrue();
        assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("畸形或非法格式 URL 触发解析异常并安全阻断")
    void shouldRejectMalformedUrlSafely() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        InstanceRegistration malformedReg = InstanceRegistration.of(
                "malformed-svc",
                "http://[::invalid-ipv6]:8080/",
                "http://127.0.0.1:8080/actuator",
                "http://127.0.0.1:8080/actuator/health"
        );
        AdminAdmissionResult result = policy.evaluate(malformedReg, request);
        assertThat(result.isRejected()).isTrue();
        assertThat(result.getReason()).contains("URL forbidden by SSRF Guard Policy");
    }

    @Test
    @DisplayName("InstanceRegistration.from 适配器空安全与全字段精确映射")
    void shouldAdaptFromSbaRegistration() {
        assertThat(InstanceRegistration.from(null)).isNull();

        de.codecentric.boot.admin.server.domain.values.Registration sbaReg =
                de.codecentric.boot.admin.server.domain.values.Registration.builder()
                        .name("sba-adapted-svc")
                        .serviceUrl("http://192.168.1.10:8080/")
                        .managementUrl("http://192.168.1.10:8080/actuator")
                        .healthUrl("http://192.168.1.10:8080/actuator/health")
                        .source("http-api")
                        .metadata(java.util.Map.of("region", "cn-hangzhou"))
                        .build();

        InstanceRegistration adapted = InstanceRegistration.from(sbaReg);
        assertThat(adapted).isNotNull();
        assertThat(adapted.getName()).isEqualTo("sba-adapted-svc");
        assertThat(adapted.getServiceUrl()).isEqualTo("http://192.168.1.10:8080/");
        assertThat(adapted.getManagementUrl()).isEqualTo("http://192.168.1.10:8080/actuator");
        assertThat(adapted.getHealthUrl()).isEqualTo("http://192.168.1.10:8080/actuator/health");
        assertThat(adapted.getSource()).isEqualTo("http-api");
        assertThat(adapted.getMetadata()).containsEntry("region", "cn-hangzhou");
    }

    @Test
    @DisplayName("强制拦截非 http/https Scheme 与无 Scheme 注册 URL")
    void shouldRejectNonHttpAndHttpsSchemes() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        for (String invalidUrl : List.of(
                "ftp://order-service:8080/health",
                "file://order-service/etc/passwd",
                "gopher://order-service:6379/_INFO",
                "//order-service:8080/health"
        )) {
            InstanceRegistration reg = InstanceRegistration.of(
                    "order-service",
                    invalidUrl,
                    "http://order-service:8081/actuator",
                    "http://order-service:8081/actuator/health"
            );
            AdminAdmissionResult result = policy.evaluate(reg, request);
            assertThat(result.isRejected()).as("Expected rejection for URL: %s", invalidUrl).isTrue();
            assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        InstanceRegistration upperCaseHttpsReg = InstanceRegistration.of(
                "order-service",
                "HTTPS://order-service:8443/",
                "HTTP://order-service:8443/actuator",
                "https://order-service:8443/actuator/health"
        );
        assertThat(policy.evaluate(upperCaseHttpsReg, request).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("当 allowLocalInstances=false 时拦截标准与带方括号的 IPv6 环回地址；为 true 时放行")
    void shouldEnforceIpv6LoopbackPolicy() {
        AtengAdminServerProperties properties = createProperties();
        properties.setAllowLocalInstances(false);
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        for (String loopbackUrl : List.of(
                "http://[::1]:8080/",
                "http://[0:0:0:0:0:0:0:1]:8080/",
                "http://[::]:8080/",
                "http://[0:0:0:0:0:0:0:0]:8080/"
        )) {
            InstanceRegistration reg = InstanceRegistration.of(
                    "ipv6-local-app",
                    loopbackUrl,
                    "http://order-service:8081/actuator",
                    "http://order-service:8081/actuator/health"
            );
            AdminAdmissionResult blocked = policy.evaluate(reg, request);
            assertThat(blocked.isRejected()).as("Expected loopback block for URL: %s", loopbackUrl).isTrue();
            assertThat(blocked.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        properties.setAllowLocalInstances(true);
        InstanceRegistration allowedIpv6Reg = InstanceRegistration.of(
                "ipv6-local-app",
                "http://[::1]:8080/",
                "http://[0:0:0:0:0:0:0:1]:8080/actuator",
                "http://[::1]:8080/actuator/health"
        );
        assertThat(policy.evaluate(allowedIpv6Reg, request).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("当 allowLocalInstances=false 时拦截 IPv4/IPv6 环回及等效编码变体；allowLocalInstances=true 时仍硬阻断链路本地变体")
    void shouldBlockLoopbackAndLinkLocalEncodedVariants() {
        AtengAdminServerProperties properties = createProperties();
        properties.setAllowLocalInstances(false);
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        for (String loopbackVariant : List.of(
                "http://127.0.0.2:8080/",
                "http://127.1:8080/",
                "http://2130706433:8080/",
                "http://0177.0.0.1:8080/",
                "http://0x7f.0.0.1:8080/",
                "http://localhost.:8080/",
                "http://svc.localhost:8080/",
                "http://[0:0::1]:8080/",
                "http://[0000:0000:0000:0000:0000:0000:0000:0001]:8080/",
                "http://[::1%25lo0]:8080/",
                "http://[::ffff:127.0.0.1]:8080/",
                "http://[::127.0.0.1]:8080/"
        )) {
            InstanceRegistration reg = InstanceRegistration.of(
                    "variant-app",
                    loopbackVariant,
                    "http://order-service:8081/actuator",
                    "http://order-service:8081/actuator/health"
            );
            AdminAdmissionResult result = policy.evaluate(reg, request);
            assertThat(result.isRejected()).as("Expected loopback block for URL: %s", loopbackVariant).isTrue();
            assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        properties.setAllowLocalInstances(true);
        for (String linkLocalVariant : List.of(
                "http://169.254.169.254./latest/meta-data/",
                "http://2852039166/latest/meta-data/",
                "http://0xa9fea9fe/latest/meta-data/",
                "http://[::ffff:169.254.169.254]/latest/meta-data/",
                "http://[fe80::1]:8080/",
                "http://[fe80::1%25eth0]:8080/"
        )) {
            InstanceRegistration reg = InstanceRegistration.of(
                    "link-local-app",
                    linkLocalVariant,
                    "http://order-service:8081/actuator",
                    "http://order-service:8081/actuator/health"
            );
            AdminAdmissionResult result = policy.evaluate(reg, request);
            assertThat(result.isRejected()).as("Expected hard link-local block for URL: %s", linkLocalVariant).isTrue();
            assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("allowed-url-patterns 正则白名单对前后空白修剪后的 URL 保持一致校验")
    void shouldTrimUrlBeforeMatchingAllowedUrlPatterns() {
        AtengAdminServerProperties properties = createProperties();
        properties.setAllowedUrlPatterns(List.of("^https?://.*\\.corp\\.internal(:\\d+)?/.*$"));
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        InstanceRegistration paddedUrlReg = InstanceRegistration.of(
                "corp-service",
                "  http://order.corp.internal:8080/  ",
                "http://order.corp.internal:8080/actuator",
                "http://order.corp.internal:8080/actuator/health"
        );
        assertThat(policy.evaluate(paddedUrlReg, request).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("拒绝含 userInfo 凭据混淆 URL 与阿里云 CGNAT、AWS IPv6 ULA、组播等高危段")
    void shouldRejectUserInfoCloudMetadataAndMulticastAddresses() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        for (String dangerousUrl : List.of(
                "http://trusted.internal@169.254.169.254/latest/meta-data/",
                "http://user:pass@10.0.0.1:8080/actuator",
                "http://100.100.100.200/latest/meta-data/",
                "http://[fd00:ec2::254]/latest/meta-data/",
                "http://224.0.0.1:8080/",
                "http://[ff02::1]:8080/"
        )) {
            InstanceRegistration reg = InstanceRegistration.of("evil-svc", dangerousUrl, null, null);
            AdminAdmissionResult result = policy.evaluate(reg, request);
            assertThat(result.isRejected()).as("Expected block for dangerous URL: %s", dangerousUrl).isTrue();
            assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("block-private-networks 与 allowed-cidrs / blocked-cidrs 联动策略符合优先级规范")
    void shouldEnforceCidrAllowBlockAndPrivateNetworkPolicies() {
        AtengAdminServerProperties properties = createProperties();
        properties.setBlockPrivateNetworks(true);
        properties.setAllowedCidrs(List.of("10.20.0.0/16"));
        properties.setBlockedCidrs(List.of("10.20.99.0/24"));
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        // 1. 处于 allowedCidrs 且未命中 blockedCidrs 的私网节点 -> 放行
        assertThat(policy.evaluate(
                InstanceRegistration.of("svc-ok", "http://10.20.1.8:8080/", null, null), request).isAllowed()).isTrue();

        // 2. 命中 blockedCidrs 的子网（即便在 allowedCidrs 内） -> 拒绝
        assertThat(policy.evaluate(
                InstanceRegistration.of("svc-blocked", "http://10.20.99.5:8080/", null, null), request).isRejected()).isTrue();

        // 3. 不在 allowedCidrs 内的其他私网与公网 IP -> 拒绝
        assertThat(policy.evaluate(
                InstanceRegistration.of("svc-other-private", "http://192.168.1.10:8080/", null, null), request).isRejected()).isTrue();
        assertThat(policy.evaluate(
                InstanceRegistration.of("svc-public", "http://8.8.8.8:8080/", null, null), request).isRejected()).isTrue();

        // 4. 硬阻断段 169.254.169.254 即便配置在 allowedCidrs 也绝不允许旁路
        AtengAdminServerProperties bypassProps = createProperties();
        bypassProps.setAllowedCidrs(List.of("169.254.0.0/16"));
        DefaultAdminInstanceAdmissionPolicy hardBlockPolicy = new DefaultAdminInstanceAdmissionPolicy(bypassProps);
        assertThat(hardBlockPolicy.evaluate(
                InstanceRegistration.of("imds", "http://169.254.169.254/", null, null), request).isRejected()).isTrue();
    }

    @Test
    @DisplayName("非法的 allowed-url-patterns 正则在构造策略时 Fail-Fast 抛出 BusinessException")
    void shouldFailFastOnInvalidAllowedUrlRegexPattern() {
        AtengAdminServerProperties properties = createProperties();
        properties.setAllowedUrlPatterns(List.of("^https://valid\\.internal$", "[unclosed-regex("));

        assertThatThrownBy(() -> new DefaultAdminInstanceAdmissionPolicy(properties))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(java.util.regex.PatternSyntaxException.class);
    }

    @Test
    @DisplayName("DnsResolutionMode 控制域名 DNS 解析防重绑定/通配域名与失败兜底行为")
    void shouldRespectDnsResolutionModeUsingResolverSeam() {
        HostAddressResolver mockResolver = host -> {
            if ("rebind.nip.io".equalsIgnoreCase(host)) {
                return new InetAddress[]{InetAddress.getByName("169.254.169.254")};
            }
            if ("cgw.localtest.me".equalsIgnoreCase(host)) {
                return new InetAddress[]{InetAddress.getByName("100.100.100.200")};
            }
            if ("safe.corp.internal".equalsIgnoreCase(host)) {
                return new InetAddress[]{InetAddress.getByName("10.20.30.40")};
            }
            throw new UnknownHostException("No DNS record for " + host);
        };

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        // 1. FAIL_OPEN（默认）：可解析命中高危 IP（nip.io/100.100.100.200）必须阻断，未知离线主机名宽松放行
        AtengAdminServerProperties failOpenProps = createProperties();
        failOpenProps.setDnsResolutionMode(DnsResolutionMode.FAIL_OPEN);
        DefaultAdminInstanceAdmissionPolicy failOpenPolicy = new DefaultAdminInstanceAdmissionPolicy(failOpenProps, mockResolver);
        assertThat(failOpenPolicy.evaluate(InstanceRegistration.of("r1", "http://rebind.nip.io:8080/", null, null), request).isRejected()).isTrue();
        assertThat(failOpenPolicy.evaluate(InstanceRegistration.of("r2", "http://cgw.localtest.me/", null, null), request).isRejected()).isTrue();
        assertThat(failOpenPolicy.evaluate(InstanceRegistration.of("ok", "http://safe.corp.internal:8080/", null, null), request).isAllowed()).isTrue();
        assertThat(failOpenPolicy.evaluate(InstanceRegistration.of("offline", "http://unknown-svc:8080/", null, null), request).isAllowed()).isTrue();

        // 2. FAIL_CLOSED：未知不可解析域名严格拒绝
        AtengAdminServerProperties failClosedProps = createProperties();
        failClosedProps.setDnsResolutionMode(DnsResolutionMode.FAIL_CLOSED);
        DefaultAdminInstanceAdmissionPolicy failClosedPolicy = new DefaultAdminInstanceAdmissionPolicy(failClosedProps, mockResolver);
        assertThat(failClosedPolicy.evaluate(InstanceRegistration.of("offline", "http://unknown-svc:8080/", null, null), request).isRejected()).isTrue();
        assertThat(failClosedPolicy.evaluate(InstanceRegistration.of("ok", "http://safe.corp.internal:8080/", null, null), request).isAllowed()).isTrue();

        // 3. DISABLED：跳过 DNS 解析（域名即便在解析器中映射高危 IP 也不做解析检查）
        AtengAdminServerProperties disabledProps = createProperties();
        disabledProps.setDnsResolutionMode(DnsResolutionMode.DISABLED);
        DefaultAdminInstanceAdmissionPolicy disabledPolicy = new DefaultAdminInstanceAdmissionPolicy(disabledProps, mockResolver);
        assertThat(disabledPolicy.evaluate(InstanceRegistration.of("r1", "http://rebind.nip.io:8080/", null, null), request).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("内部服务发现 source=discovery 免检握手密钥但仍执行 SSRF 清洗，HTTP 伪造 source=discovery 严禁绕过密钥")
    void shouldSkipSecretForInternalDiscoveryOnlyWhileBlockingHttpSpoof() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        InstanceRegistration validDiscovery = InstanceRegistration.builder()
                .name("discovery-svc")
                .serviceUrl("http://10.20.1.5:8080/")
                .source("discovery")
                .build();
        // 内部无 HttpServletRequest 的 discovery 实例允许跳过 X-Admin-Client-Secret
        assertThat(policy.evaluate(validDiscovery, null).isAllowed()).isTrue();

        // 内部 discovery 实例 URL 命中黑名单仍必须阻断
        InstanceRegistration dangerousDiscovery = InstanceRegistration.builder()
                .name("discovery-evil")
                .serviceUrl("http://100.100.100.200/latest/meta-data/")
                .source("discovery")
                .build();
        assertThat(policy.evaluate(dangerousDiscovery, null).isRejected()).isTrue();

        // HTTP 注册请求伪造 source=discovery 但无 X-Admin-Client-Secret -> 必须 403 拒绝，防绕过
        MockHttpServletRequest spoofedHttpRequest = new MockHttpServletRequest("POST", "/instances");
        AdminAdmissionResult spoofResult = policy.evaluate(validDiscovery, spoofedHttpRequest);
        assertThat(spoofResult.isRejected()).isTrue();
        assertThat(spoofResult.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("拒绝空分隔符 userInfo (http://@host)、阻断命中 blockedCidrs 的 localhost 及拒绝空出站 URL")
    void shouldRejectEmptyUserInfoBlockedLocalhostAndBlankOutboundUrls() {
        AtengAdminServerProperties properties = createProperties();
        properties.setAllowLocalInstances(true);
        properties.setBlockedCidrs(List.of("127.0.0.0/8"));
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        // 1. 空 userInfo 分隔符 http://@169.254.169.254/ 必须拦截
        assertThat(policy.evaluate(
                InstanceRegistration.of("empty-userinfo", "http://@169.254.169.254/", null, null),
                request).isRejected()).isTrue();

        // 2. blockedCidrs 包含 127.0.0.0/8 时，即使 allowLocalInstances=true 也必须拦截 localhost
        assertThat(policy.evaluate(
                InstanceRegistration.of("blocked-localhost", "http://localhost:8080/actuator/health", null, null),
                request).isRejected()).isTrue();

        // 3. 出站重检入口面对空/空白 URL 严格返回 false
        assertThat(policy.isOutboundUrlAllowed(null)).isFalse();
        assertThat(policy.isOutboundUrlAllowed("")).isFalse();
        assertThat(policy.isOutboundUrlAllowed("   ")).isFalse();
    }
}
