package io.github.atengk.boot.sample.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.web.audit.MemoryOperateLogStorageProvider;
import io.github.atengk.boot.sample.web.dto.AuditUserDTO;
import io.github.atengk.boot.web.filter.PayloadLoggingFilter;
import io.github.atengk.boot.web.filter.SlowRequestFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全链路追踪、请求体缓存与双向可观测审计端到端集成测试
 * 覆盖 TraceFilter、虚拟线程探测、ContentCachingFilter、PayloadLoggingFilter、@OperateLog、@WebLog 与 SlowRequestFilter
 *
 * @author Ateng
 * @since 2026-11-07
 */
@SpringBootTest(properties = {
        "ateng.boot.web.slow-request.threshold=50ms"
})
@AutoConfigureMockMvc
@DisplayName("全链路追踪、请求体缓存与双向可观测审计端到端测试")
class WebObservabilityAndAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemoryOperateLogStorageProvider storageProvider;

    private Logger payloadLogger;
    private ListAppender<ILoggingEvent> payloadAppender;

    private Logger slowLogger;
    private ListAppender<ILoggingEvent> slowAppender;

    @BeforeEach
    void setUp() {
        storageProvider.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();

        payloadLogger = (Logger) LoggerFactory.getLogger(PayloadLoggingFilter.class);
        payloadAppender = new ListAppender<>();
        payloadAppender.start();
        payloadLogger.addAppender(payloadAppender);

        slowLogger = (Logger) LoggerFactory.getLogger(SlowRequestFilter.class);
        slowAppender = new ListAppender<>();
        slowAppender.start();
        slowLogger.addAppender(slowAppender);
    }

    @AfterEach
    void tearDown() {
        if (payloadLogger != null && payloadAppender != null) {
            payloadLogger.detachAppender(payloadAppender);
            payloadAppender.stop();
        }
        if (slowLogger != null && slowAppender != null) {
            slowLogger.detachAppender(slowAppender);
            slowAppender.stop();
        }
        storageProvider.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. TraceFilter 链路追踪：自动生成 traceId 并回传 X-Trace-Id 响应头与 Result 载荷，出口严格清理现场")
    void shouldGenerateAndCleanTraceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/sample/web/observability/trace"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();

        String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
        String bodyContent = result.getResponse().getContentAsString();
        assertThat(bodyContent).contains(headerTraceId);

        // 验证 TraceFilter 在 finally 严格清理当前线程的 MDC
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("2. TraceFilter 链路追踪：客户端传入自定义 X-Trace-Id 优先透传并贯穿整条调用链")
    void shouldPropagateCustomTraceId() throws Exception {
        String customTrace = "custom-trace-88889999";
        mockMvc.perform(get("/sample/web/observability/trace")
                        .header("X-Trace-Id", customTrace))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", customTrace))
                .andExpect(jsonPath("$.traceId").value(customTrace))
                .andExpect(jsonPath("$.data").value(customTrace));

        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("3. 虚拟线程调度：验证基于 Java 21+ Virtual Thread 调度执行（JDK 17 环境下平滑降级）")
    void shouldRunOnVirtualThread() throws Exception {
        boolean expectedVirtual = Runtime.version().feature() >= 21;
        mockMvc.perform(get("/sample/web/observability/virtual-thread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isVirtual").value(expectedVirtual))
                .andExpect(jsonPath("$.data.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("4. ContentCachingFilter 请求体重读：同一请求内多次消费 InputStream 均可正确读出原始报文")
    void shouldSupportRepeatedStreamReading() throws Exception {
        String testBody = "{\"message\":\"hello repeated stream\",\"amount\":12.5}";

        mockMvc.perform(post("/sample/web/observability/body-cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.firstReadLength").value(testBody.getBytes(StandardCharsets.UTF_8).length))
                .andExpect(jsonPath("$.data.secondReadLength").value(testBody.getBytes(StandardCharsets.UTF_8).length))
                .andExpect(jsonPath("$.data.isCachedWrapper").value(true))
                .andExpect(jsonPath("$.data.contentMatch").value(true));
    }

    @Test
    @DisplayName("5. PayloadLoggingFilter 双向采样与敏感凭据正则打码：单行聚合输出 [HTTP_PAYLOAD]，password/token 自动掩码 ***")
    void shouldLogPayloadWithSensitiveMasking() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "audit-admin");
        body.put("password", "SuperSecretPassword123");
        body.put("token", "secret-token-xyz");
        body.put("amount", 100);
        String json = objectMapper.writeValueAsString(body);

        mockMvc.perform(post("/sample/web/observability/payload-echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("X-Trace-Id", "trace-payload-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("audit-admin"));

        // 验证 PayloadLoggingFilter 捕获到 [HTTP_PAYLOAD] 聚合日志
        ILoggingEvent payloadEvent = payloadAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("[HTTP_PAYLOAD]"))
                .findFirst()
                .orElse(null);

        assertThat(payloadEvent).isNotNull();
        String message = payloadEvent.getFormattedMessage();
        assertThat(message).contains("traceId=[trace-payload-001]");
        assertThat(message).contains("/sample/web/observability/payload-echo");
        assertThat(message).contains("\"username\":\"audit-admin\"");
        // 验证敏感字段被正则掩码为 ***
        assertThat(message).contains("\"password\":\"***\"");
        assertThat(message).contains("\"token\":\"***\"");
        assertThat(message).doesNotContain("SuperSecretPassword123");
        assertThat(message).doesNotContain("secret-token-xyz");
    }

    @Test
    @DisplayName("6. PayloadLoggingFilter 免采样白名单排除：命中 exclude-urls 时跳过采样日志打印")
    void shouldSkipPayloadLoggingWhenExcluded() throws Exception {
        Map<String, Object> body = Map.of("username", "exempt-user", "password", "myPass");
        String json = objectMapper.writeValueAsString(body);

        mockMvc.perform(post("/sample/web/observability/payload-excluded")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        boolean logged = payloadAppender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("/sample/web/observability/payload-excluded"));
        assertThat(logged).isFalse();
    }

    @Test
    @DisplayName("7. @OperateLog 正常新增：完整对齐四维上下文，敏感入参 password 被自动排除为 [EXCLUDED]，出参手机号动态脱敏")
    void shouldCaptureOperateLogOnSuccess() throws Exception {
        UserContextHolder.set("admin-operator");
        TenantContextHolder.setTenantId("tenant-888");

        AuditUserDTO dto = new AuditUserDTO();
        dto.setUsername("audit-user");
        dto.setPassword("SuperSecretPass123");
        dto.setEmail("audit@example.com");
        dto.setPhone("13812345678");
        String json = objectMapper.writeValueAsString(dto);

        mockMvc.perform(post("/sample/web/observability/audit/operator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("X-Trace-Id", "trace-audit-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.phone").value("138****5678")); // 出库动态脱敏

        // 验证存储 SPI 接收到的结构化审计模型
        OperateLogModel lastLog = awaitLastLog();
        assertThat(lastLog).isNotNull();
        assertThat(lastLog.getTraceId()).isEqualTo("trace-audit-001");
        assertThat(lastLog.getUserName()).isEqualTo("admin-operator");
        assertThat(lastLog.getTenantId()).isEqualTo("tenant-888");
        assertThat(lastLog.getModule()).isEqualTo("权限中心");
        assertThat(lastLog.getOperateType()).isEqualTo(OperateTypeEnum.INSERT);
        assertThat(lastLog.getStatus()).isEqualTo(0); // 成功
        assertThat(lastLog.getClientIp()).isNotBlank();
        assertThat(lastLog.getCostTime()).isGreaterThanOrEqualTo(0L);

        // 验证入参中敏感字段 password 被排除替换为 [EXCLUDED]
        assertThat(lastLog.getRequestParams()).contains("\"username\":\"audit-user\"");
        assertThat(lastLog.getRequestParams()).contains("\"password\":\"[EXCLUDED]\"");
        assertThat(lastLog.getRequestParams()).doesNotContain("SuperSecretPass123");

        // 验证审计记录中的出参联动动态脱敏
        assertThat(lastLog.getResponseResult()).contains("138****5678");
    }

    @Test
    @DisplayName("8. @OperateLog 查询操作审计：记录 QUERY 类型审计记录")
    void shouldCaptureOperateLogOnQuery() throws Exception {
        mockMvc.perform(get("/sample/web/observability/audit/operator/10086"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.phone").value("139****5678"));

        OperateLogModel lastLog = awaitLastLog();
        assertThat(lastLog).isNotNull();
        assertThat(lastLog.getModule()).isEqualTo("权限中心");
        assertThat(lastLog.getOperateType()).isEqualTo(OperateTypeEnum.QUERY);
        assertThat(lastLog.getDescription()).isEqualTo("查询操作员详情");
        assertThat(lastLog.getStatus()).isEqualTo(0);
    }

    @Test
    @DisplayName("9. @OperateLog 业务异常审计：切面捕获失败状态 (status=1) 并记录错误信息，异常透明重抛")
    void shouldCaptureOperateLogOnException() throws Exception {
        mockMvc.perform(get("/sample/web/observability/audit/error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("操作员状态已冻结，禁止变更"));

        OperateLogModel lastLog = awaitLastLog();
        assertThat(lastLog).isNotNull();
        assertThat(lastLog.getStatus()).isEqualTo(1); // 失败
        assertThat(lastLog.getErrorMsg()).isEqualTo("操作员状态已冻结，禁止变更");
    }

    @Test
    @DisplayName("10. @WebLog 接口日志切面正常拦截执行（消除测试桩字面量）")
    void shouldAuditWebLogSuccessfully() throws Exception {
        AuditUserDTO dto = new AuditUserDTO();
        dto.setUsername("weblog-tester");
        dto.setPassword("Secret123");
        String json = objectMapper.writeValueAsString(dto);

        mockMvc.perform(post("/sample/web/observability/weblog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.operator").value("weblog-tester"))
                .andExpect(jsonPath("$.data.status").value("AUDITED"))
                .andExpect(jsonPath("$.data.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("11. SlowRequestFilter 慢请求性能主动告警：请求耗时超过阈值 (50ms) 触发 [SLOW_REQUEST] WARN 告警日志")
    void shouldTriggerSlowRequestWarnLog() throws Exception {
        mockMvc.perform(get("/sample/web/observability/slow-query").param("delayMs", "70"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reportName").value("2026年度财务汇总对账单"))
                .andExpect(jsonPath("$.data.records").value(100000));

        ILoggingEvent slowEvent = slowAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("[SLOW_REQUEST]"))
                .findFirst()
                .orElse(null);

        assertThat(slowEvent).isNotNull();
        assertThat(slowEvent.getFormattedMessage()).contains("/sample/web/observability/slow-query");
    }

    @Test
    @DisplayName("12. SlowRequestFilter 慢请求白名单排除：匹配 exclude-urls 时即使超过阈值也不输出告警日志")
    void shouldSkipSlowRequestAlertWhenExcluded() throws Exception {
        mockMvc.perform(get("/sample/web/observability/slow-excluded").param("delayMs", "70"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("excluded-ok"));

        boolean logged = slowAppender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("/sample/web/observability/slow-excluded"));
        assertThat(logged).isFalse();
    }

    private OperateLogModel awaitLastLog() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            OperateLogModel model = storageProvider.getLastLog();
            if (model != null) {
                return model;
            }
            Thread.sleep(20);
        }
        return storageProvider.getLastLog();
    }
}
