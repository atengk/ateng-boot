package io.github.atengk.boot.sample.observability;

import io.github.atengk.boot.observability.event.ActuatorAccessDeniedEvent;
import io.github.atengk.boot.observability.event.HealthProbeDegradedEvent;
import io.github.atengk.boot.observability.event.HealthProbeRecoveredEvent;
import io.github.atengk.boot.observability.event.ThreadPoolRejectedEvent;
import io.github.atengk.boot.observability.health.AbstractSafeHealthIndicator;
import io.github.atengk.boot.observability.security.ActuatorEndpointSecurityFilter;
import io.github.atengk.boot.sample.observability.probe.PaymentGatewayHealthIndicator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 云原生可观测性端到端集成场景自验测试
 * 覆盖全部核心生产场景：
 * 1. Prometheus 性能度量与受管线程池动态指标抓取
 * 2. 编程式与声明式 (@ObservedMetric) 业务指标打点与 Prometheus 抓取验证
 * 3. Kubernetes 黄金探针（Health / Liveness / Readiness）聚合就绪检查与 Liveness 外部依赖绝对硬隔离
 * 4. 业务正常 API 访问不被 Actuator 安全过滤器干扰
 * 5. 白名单端点（health / info / prometheus）免密无 Token 畅行无阻
 * 6. CIDR IP 白名单免密穿透与敏感高危端点（/actuator/env）放行
 * 7. HTTP Basic Auth 认证鉴权与敏感端点防卫
 * 8. X-Actuator-Token 凭据防卫与 403 阻断
 * 9. 观测领域事件总线（ActuatorAccessDeniedEvent、ThreadPoolRejectedEvent、HealthProbeDegradedEvent）监听捕获
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ObservabilityScenarioTest.TestEventCaptureConfiguration.class)
@DisplayName("云原生可观测性场景样例端到端自验测试")
class ObservabilityScenarioTest {

    private static final String VALID_TOKEN = "obs-admin-secret-token";
    private static final String BASIC_AUTH_HEADER = "Basic " + Base64.getEncoder()
            .encodeToString("admin:obs-admin-pass-123".getBytes(StandardCharsets.UTF_8));
    private static final String INVALID_BASIC_AUTH_HEADER = "Basic " + Base64.getEncoder()
            .encodeToString("admin:wrong-password".getBytes(StandardCharsets.UTF_8));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TestEventCaptureConfiguration eventCapture;

    @Autowired
    private PaymentGatewayHealthIndicator paymentGatewayHealthIndicator;

    @BeforeEach
    void setUp() {
        eventCapture.clear();
        paymentGatewayHealthIndicator.setSimulatedDown(false, null);
        paymentGatewayHealthIndicator.resetProbeState();
    }

    @AfterEach
    void tearDown() {
        paymentGatewayHealthIndicator.setSimulatedDown(false, null);
        paymentGatewayHealthIndicator.resetProbeState();
    }

    @Test
    @DisplayName("场景一：/actuator/prometheus 端点抓取验证受管线程池动态打标指标")
    void scenario1_prometheusThreadPoolMetricsScrape() throws Exception {
        // 先触发物流履约异步推单，产生受管线程池负载
        mockMvc.perform(post("/sample/observability/thread-pool/dispatch-logistics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackId\":\"TRK-10001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("运单 TRK-10001 已提交至受管线程池 sampleTaskExecutor"));

        // 抓取 Prometheus 文本指标报文
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ateng_threadpool_core_size{pool=\"sampleTaskExecutor\"")))
                .andExpect(content().string(containsString("ateng_threadpool_max_size{pool=\"sampleTaskExecutor\"")))
                .andExpect(content().string(containsString("ateng_threadpool_active_count{pool=\"sampleTaskExecutor\"")))
                .andExpect(content().string(containsString("ateng_threadpool_queue_size{pool=\"sampleTaskExecutor\"")))
                .andExpect(content().string(containsString("ateng_threadpool_rejected_tasks_total{pool=\"sampleTaskExecutor\"")));
    }

    @Test
    @DisplayName("场景二：编程式与声明式 (@ObservedMetric) 业务指标打点并在 /actuator/prometheus 抓取断言")
    void scenario2_businessMetricsProgrammaticAndDeclarativeScrape() throws Exception {
        // 1. 触发编程式指标打点（订单创建）
        mockMvc.perform(post("/sample/observability/metrics/order-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-20261110-001\",\"amount\":99.5,\"channel\":\"app\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("订单 ORD-20261110-001 业务指标打点成功"));

        // 2. 触发声明式 @ObservedMetric 切面打点（库存扣减）
        mockMvc.perform(post("/sample/observability/metrics/stock-deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"PROD-8888\",\"count\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("商品 PROD-8888 成功扣减库存 2 件"));

        // 3. 触发商户在线 Gauge 采集
        mockMvc.perform(get("/sample/observability/metrics/merchant-active-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(128.0));

        // 4. 抓取 Prometheus 端点，断言业务指标已被发布
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("sample_orders_total{channel=\"app\"} 1.0")))
                .andExpect(content().string(containsString("sample_orders_amount_count{channel=\"app\"} 1")))
                .andExpect(content().string(containsString("sample_orders_amount_sum{channel=\"app\"} 99.5")))
                .andExpect(content().string(containsString("sample_orders_process_time_seconds_count{channel=\"app\"} 1")))
                .andExpect(content().string(containsString("sample_orders_process_time_seconds_sum{channel=\"app\"} 0.025")))
                .andExpect(content().string(containsString("sample_demo_observed_seconds_count{")))
                .andExpect(content().string(containsString("exception=\"none\"")))
                .andExpect(content().string(containsString("status=\"success\"")))
                .andExpect(content().string(containsString("tier=\"api\"")))
                .andExpect(content().string(containsString("quantile=\"0.95\"")))
                .andExpect(content().string(containsString("quantile=\"0.99\"")))
                .andExpect(content().string(containsString("sample_demo_observed_calls_total{")));
    }

    @Test
    @DisplayName("场景三：/actuator/health 聚合探针与 K8s 存活/就绪探针黄金硬隔离断言")
    void scenario3_healthAndKubernetesProbesHardDecoupling() throws Exception {
        // 1. 全局健康检查
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.dynamicDatasource.status").value("UP"))
                .andExpect(jsonPath("$.components.storage.status").value("UP"))
                .andExpect(jsonPath("$.components.paymentGateway.status").value("UP"));

        // 2. 自定义安全健康探针 Controller 体验接口返回 200 UP
        mockMvc.perform(get("/sample/observability/probe/payment-gateway"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.details.channel").value("MockPayGateway"));

        // 3. K8s 存活探针（Liveness）—— 黄金隔离铁律：仅允许 internal 状态（livenessState, ping），坚决不能包含外部依赖
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.livenessState.status").value("UP"))
                .andExpect(jsonPath("$.components.ping.status").value("UP"))
                .andExpect(jsonPath("$.components.dynamicDatasource").doesNotExist())
                .andExpect(jsonPath("$.components.storage").doesNotExist())
                .andExpect(jsonPath("$.components.paymentGateway").doesNotExist());

        // 4. K8s 就绪探针（Readiness）—— 必须聚合全部外部关键业务组件
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
                .andExpect(jsonPath("$.components.dynamicDatasource.status").value("UP"))
                .andExpect(jsonPath("$.components.storage.status").value("UP"))
                .andExpect(jsonPath("$.components.paymentGateway.status").value("UP"));
    }

    @Test
    @DisplayName("场景四：业务正常 API 访问不被 Actuator 安全过滤器干扰")
    void scenario4_businessApiPassThrough() throws Exception {
        mockMvc.perform(get("/sample/observability/security/public-ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("可观测性服务正常在线，白名单业务端点畅通"));
    }

    @Test
    @DisplayName("场景五：白名单端点（health / info / prometheus）免密无 Token 畅行无阻")
    void scenario5_whitelistedEndpointsNoTokenRequired() throws Exception {
        // /actuator/health 免密 200
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // /actuator/info 免密 200
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());

        // /actuator/prometheus 免密 200
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("场景六：CIDR IP 白名单免密穿透与敏感高危端点（/actuator/env）直接放行")
    void scenario6_cidrIpWhitelistBypassSecurity() throws Exception {
        // 1. 来源 IP 在 10.0.0.0/8 网段内（如 10.20.30.40），免密直接 200 放行
        mockMvc.perform(get("/actuator/env")
                        .with(request -> {
                            request.setRemoteAddr("10.20.30.40");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfiles").exists());

        // 2. 来源 IP 单点在 192.168.1.100 白名单内，免密直接 200 放行
        mockMvc.perform(get("/actuator/beans")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.100");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").exists());
    }

    @Test
    @DisplayName("场景七：非白名单 IP 下 HTTP Basic Auth 认证访问与凭据防卫")
    void scenario7_basicAuthAuthenticationAndGuard() throws Exception {
        // 1. 非白名单 IP (172.16.0.1) 无凭证 -> 403 Forbidden 并触发 ActuatorAccessDeniedEvent
        mockMvc.perform(get("/actuator/env")
                        .with(request -> {
                            request.setRemoteAddr("172.16.0.1");
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value(containsString("Actuator 端点访问拒绝")));

        assertThat(eventCapture.deniedEvents).isNotEmpty();
        ActuatorAccessDeniedEvent deniedEvent = eventCapture.deniedEvents.get(0);
        assertThat(deniedEvent.getUri()).isEqualTo("/actuator/env");
        assertThat(deniedEvent.getClientIp()).isEqualTo("172.16.0.1");

        eventCapture.clear();

        // 2. 携带错误 Basic Auth 凭据 -> 403 Forbidden
        mockMvc.perform(get("/actuator/env")
                        .with(request -> {
                            request.setRemoteAddr("172.16.0.1");
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, INVALID_BASIC_AUTH_HEADER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // 3. 携带正确 Basic Auth 凭据 -> 200 OK 正常访问
        mockMvc.perform(get("/actuator/env")
                        .with(request -> {
                            request.setRemoteAddr("172.16.0.1");
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfiles").exists());
    }

    @Test
    @DisplayName("场景八：高危敏感端点通过 X-Actuator-Token 凭据防卫与鉴权放行")
    void scenario8_sensitiveEndpointsTokenGuard() throws Exception {
        // 1. /actuator/env 带错误 Token -> 403 Forbidden
        mockMvc.perform(get("/actuator/env")
                        .with(request -> {
                            request.setRemoteAddr("172.16.0.1");
                            return request;
                        })
                        .header(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, "wrong-token-abc"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // 2. /actuator/env 带正确 Token -> 200 OK 正常访问且返回 activeProfiles 属性
        mockMvc.perform(get("/actuator/env")
                        .with(request -> {
                            request.setRemoteAddr("172.16.0.1");
                            return request;
                        })
                        .header(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfiles").exists());

        // 3. /actuator/beans 带正确 Token -> 200 OK 正常访问且返回 contexts 属性
        mockMvc.perform(get("/actuator/beans")
                        .with(request -> {
                            request.setRemoteAddr("172.16.0.1");
                            return request;
                        })
                        .header(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").exists());
    }

    @Test
    @DisplayName("场景九：线程池饱和拒绝发布 ThreadPoolRejectedEvent 领域事件断言")
    void scenario9_threadPoolRejectedEventBroadcast() throws Exception {
        // 触发线程池任务过载并打满拒绝
        mockMvc.perform(post("/sample/observability/thread-pool/trigger-burst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskCount\":70}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("已触发线程池并发高压与任务拒绝追踪"));

        // 断言事件总线捕获到 ThreadPoolRejectedEvent
        assertThat(eventCapture.rejectedEvents).isNotEmpty();
        ThreadPoolRejectedEvent rejectedEvent = eventCapture.rejectedEvents.get(0);
        assertThat(rejectedEvent.getPoolName()).isEqualTo("sampleTaskExecutor");
        assertThat(rejectedEvent.getCumulativeRejectedCount()).isGreaterThanOrEqualTo(1);
        assertThat(rejectedEvent.getActiveCount()).isGreaterThanOrEqualTo(0);
        assertThat(rejectedEvent.getQueueSize()).isGreaterThanOrEqualTo(0);
        assertThat(rejectedEvent.getCorePoolSize()).isEqualTo(4);
        assertThat(rejectedEvent.getMaxPoolSize()).isEqualTo(8);

        // 断言最近领域事件查询端点返回事件记录
        mockMvc.perform(get("/sample/observability/security/recent-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("场景十：安全探针防假死超时降级与发布 HealthProbeDegradedEvent 领域事件断言")
    void scenario10_healthProbeDegradedEventBroadcast() throws Exception {
        // 1. 调用模拟超时慢探针 Controller 体验接口
        mockMvc.perform(get("/sample/observability/probe/simulate-timeout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.details.status").value("TIMEOUT"));

        // 2. 创建一个探测超时阈值为 80ms 的 Mock 探针
        TimeoutMockSafeHealthIndicator indicator = new TimeoutMockSafeHealthIndicator();
        indicator.setApplicationEventPublisher(eventPublisher);

        // 执行健康检测，触发超时降级
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("status", "TIMEOUT");

        // 断言事件总线捕获到 HealthProbeDegradedEvent
        assertThat(eventCapture.degradedEvents).isNotEmpty();
        HealthProbeDegradedEvent degradedEvent = eventCapture.degradedEvents.get(0);
        assertThat(degradedEvent.getComponentName()).isEqualTo("simulatedSlowProbe");
        assertThat(degradedEvent.getStatus()).isEqualTo("TIMEOUT");
        assertThat(degradedEvent.getCostMs()).isGreaterThanOrEqualTo(80);
    }

    @Test
    @DisplayName("场景十一：Redis 等安全健康探针弱装配与离线自洽探测断言")
    void scenario11_redisSafeHealthIndicatorOfflineSelfContained() {
        // 在无外部物理 Redis 连接工厂环境下，验证弱探测保护返回 UNKNOWN 并附带友好诊断详情
        io.github.atengk.boot.observability.health.RedisSafeHealthIndicator redisIndicator =
                new io.github.atengk.boot.observability.health.RedisSafeHealthIndicator(null, null);
        Health health = redisIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("message", "未检测到已装配的 Redis 连接工厂或 Redisson 客户端");
    }

    @Test
    @DisplayName("场景十二：默认关闭代理头信任防 X-Forwarded-For 伪造绕过与 SBA 客户端握手自检")
    void scenario12_proxyHeaderSpoofingBlockedAndAdminHandshakeCheck() throws Exception {
        // 1. 外网 IP 携带伪造 X-Forwarded-For: 10.0.0.1 与 X-Real-IP: 192.168.1.100 企图绕过白名单 -> 必须被拦截 403 且审计真实直连 IP
        mockMvc.perform(get("/actuator/env")
                        .with(request -> {
                            request.setRemoteAddr("172.16.0.1");
                            return request;
                        })
                        .header("X-Forwarded-For", "10.0.0.1")
                        .header("X-Real-IP", "192.168.1.100"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value(containsString("Actuator 端点访问拒绝")));

        assertThat(eventCapture.deniedEvents).isNotEmpty();
        ActuatorAccessDeniedEvent deniedEvent = eventCapture.deniedEvents.get(0);
        assertThat(deniedEvent.getUri()).isEqualTo("/actuator/env");
        assertThat(deniedEvent.getClientIp()).isEqualTo("172.16.0.1");

        // 2. SBA 客户端握手密钥拦截器自检：传入测试握手密钥时应自动注入 X-Admin-Client-Secret 请求头
        mockMvc.perform(get("/sample/observability/security/admin-handshake-check")
                        .param("clientSecret", "test-sba-handshake-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.headerName").value("X-Admin-Client-Secret"))
                .andExpect(jsonPath("$.data.headerInjected").value(true))
                .andExpect(jsonPath("$.data.maskedHeaderValue").value("test*****************cret"))
                .andExpect(jsonPath("$.data.adminClientEnabled").value(true));

        // 3. 未配置或传入空白密钥时，拦截器应保持幂等跳过而不注入请求头
        mockMvc.perform(get("/sample/observability/security/admin-handshake-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.headerName").value("X-Admin-Client-Secret"))
                .andExpect(jsonPath("$.data.headerInjected").value(false))
                .andExpect(jsonPath("$.data.maskedHeaderValue").value(""));
    }

    @Test
    @DisplayName("场景十三：PaymentGatewayHealthIndicator 受管线程池绑定与 UP -> DOWN -> UP 对称自愈闭环验证")
    void scenario13_healthProbeDegradeAndRecoveryLifecycle() throws Exception {
        // 1. 验证 BeanFactoryAware 已绑定专属受管线程池，未回退 ForkJoinPool.commonPool()
        assertThat(paymentGatewayHealthIndicator.getExecutor()).isNotSameAs(ForkJoinPool.commonPool());

        // 2. 注入支付网关故障并触发即时探测 -> 返回 DOWN，同时发布 HealthProbeDegradedEvent
        mockMvc.perform(post("/sample/observability/probe/payment-gateway/degrade")
                        .param("reason", "MockGatewayDown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("DOWN"))
                .andExpect(jsonPath("$.data.details.reason").value("MockGatewayDown"));

        // 3. 验证 Readiness 联动降级为 503，Liveness 仍保持 200 UP 硬隔离
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.paymentGateway.status").value("DOWN"));

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        assertThat(eventCapture.degradedEvents)
                .anySatisfy(event -> {
                    assertThat(event.getComponentName()).isEqualTo("paymentGateway");
                    assertThat(event.getStatus()).isEqualTo("DOWN");
                });

        // 4. 恢复支付网关并触发自愈探测 -> 返回 UP 并广播对称 HealthProbeRecoveredEvent
        mockMvc.perform(post("/sample/observability/probe/payment-gateway/recover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.paymentGateway.status").value("UP"));

        assertThat(eventCapture.recoveredEvents)
                .anySatisfy(event -> {
                    assertThat(event.getComponentName()).isEqualTo("paymentGateway");
                    assertThat(event.getPreviousStatus()).isEqualTo("DOWN");
                    assertThat(event.getConsecutiveFailures()).isGreaterThanOrEqualTo(1L);
                    assertThat(event.getDowntimeMs()).isGreaterThanOrEqualTo(0L);
                });

        // 5. 查询最近领域事件端点，验证 ObservabilityAlertEventListener 已同时捕获降级与恢复事件
        mockMvc.perform(get("/sample/observability/security/recent-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(content().string(containsString("HealthProbeDegradedEvent")))
                .andExpect(content().string(containsString("HealthProbeRecoveredEvent")));
    }

    /**
     * 测试专用领域事件监听收集器配置
     */
    @TestConfiguration
    static class TestEventCaptureConfiguration {
        final List<ActuatorAccessDeniedEvent> deniedEvents = new CopyOnWriteArrayList<>();
        final List<ThreadPoolRejectedEvent> rejectedEvents = new CopyOnWriteArrayList<>();
        final List<HealthProbeDegradedEvent> degradedEvents = new CopyOnWriteArrayList<>();
        final List<HealthProbeRecoveredEvent> recoveredEvents = new CopyOnWriteArrayList<>();

        @EventListener
        void onAccessDenied(ActuatorAccessDeniedEvent event) {
            deniedEvents.add(event);
        }

        @EventListener
        void onThreadPoolRejected(ThreadPoolRejectedEvent event) {
            rejectedEvents.add(event);
        }

        @EventListener
        void onHealthProbeDegraded(HealthProbeDegradedEvent event) {
            degradedEvents.add(event);
        }

        @EventListener
        void onHealthProbeRecovered(HealthProbeRecoveredEvent event) {
            recoveredEvents.add(event);
        }

        void clear() {
            deniedEvents.clear();
            rejectedEvents.clear();
            degradedEvents.clear();
            recoveredEvents.clear();
        }
    }

    /**
     * 测试专用超时模拟防假死探针
     */
    static class TimeoutMockSafeHealthIndicator extends AbstractSafeHealthIndicator {

        TimeoutMockSafeHealthIndicator() {
            super(Duration.ofMillis(80));
        }

        @Override
        protected String getComponentName() {
            return "mockTimeout";
        }

        @Override
        protected void doCheck(Health.Builder builder) throws Exception {
            Thread.sleep(250);
            builder.up();
        }
    }
}
