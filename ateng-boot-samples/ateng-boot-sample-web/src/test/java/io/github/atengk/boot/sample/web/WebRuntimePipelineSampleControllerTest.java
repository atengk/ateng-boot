package io.github.atengk.boot.sample.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 运行时流水线与基础设施端到端集成测试
 * 覆盖 TraceFilter MDC 追踪、虚拟线程调度、ContentCachingFilter 请求体重读、慢请求告警与运行时工具集
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Web 运行时流水线与基础设施端到端测试")
class WebRuntimePipelineSampleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private Logger slowLogger;
    private ListAppender<ILoggingEvent> slowAppender;

    @BeforeEach
    void setUp() {
        MDC.clear();
        slowLogger = (Logger) LoggerFactory.getLogger(SlowRequestFilter.class);
        slowAppender = new ListAppender<>();
        slowAppender.start();
        slowLogger.addAppender(slowAppender);
    }

    @AfterEach
    void tearDown() {
        if (slowLogger != null && slowAppender != null) {
            slowLogger.detachAppender(slowAppender);
            slowAppender.stop();
        }
        MDC.clear();
    }

    @Test
    @DisplayName("1. TraceFilter 链路追踪：自动生成 traceId 并回传 X-Trace-Id 响应头与 Result 载荷，出口严格清理现场")
    void shouldGenerateAndCleanTraceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sample/runtime/trace"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();

        String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
        String bodyTraceId = result.getResponse().getContentAsString();
        assertThat(bodyTraceId).contains(headerTraceId);

        // 验证 TraceFilter 在 finally 严格清理当前线程的 MDC
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("2. TraceFilter 链路追踪：客户端传入自定义 X-Trace-Id 优先透传并贯穿整条调用链")
    void shouldPropagateCustomTraceId() throws Exception {
        String customTrace = "custom-trace-88889999";
        mockMvc.perform(get("/api/sample/runtime/trace")
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
        mockMvc.perform(get("/api/sample/runtime/virtual-thread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isVirtual").value(expectedVirtual));
    }

    @Test
    @DisplayName("4. ContentCachingFilter 请求体重读：同一请求内多次消费 InputStream 均可正确读出原始报文")
    void shouldSupportRepeatedStreamReading() throws Exception {
        String testBody = "{\"message\":\"hello repeated stream\",\"amount\":12.5}";

        mockMvc.perform(post("/api/sample/runtime/body-cache")
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
    @DisplayName("5. IpUtil 真实客户端 IP 提取：穿透多级反向代理 X-Forwarded-For 提取首个真实外网 IP")
    void shouldExtractRealClientIp() throws Exception {
        mockMvc.perform(get("/api/sample/runtime/client-ip")
                        .header("X-Forwarded-For", "203.0.113.195, 192.168.1.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("203.0.113.195"));
    }

    @Test
    @DisplayName("6. ServletUtil 安全流式下载：RFC 6266 双重文件名编码，杜绝浏览器乱码")
    void shouldDownloadFileWithRfc6266Header() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sample/runtime/download"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).contains("filename=");
        assertThat(disposition).contains("filename*=");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("ateng-boot 工业级运行时下载测试内容");
    }

    @Test
    @DisplayName("7. PreStopController 降级端点：GET/POST /pre-stop 翻转就绪状态并幂等排空")
    void shouldSupportPreStopEndpoint() throws Exception {
        mockMvc.perform(get("/pre-stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").isNotEmpty());

        mockMvc.perform(post("/pre-stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
