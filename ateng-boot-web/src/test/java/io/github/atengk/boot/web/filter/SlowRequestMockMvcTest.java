package io.github.atengk.boot.web.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SlowRequestFilter 与 MockMvc 端到端全真集成测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("SlowRequestFilter MockMvc 端到端集成测试")
class SlowRequestMockMvcTest {

    private MockMvc mockMvc;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @RestController
    @RequestMapping("/mock")
    static class MockSlowController {

        @GetMapping("/fast")
        public Result<String> fast() {
            return Result.success("fast-ok");
        }

        @PostMapping("/slow")
        public Result<String> slow() throws InterruptedException {
            Thread.sleep(80);
            return Result.success("slow-ok");
        }

        @GetMapping("/slow-excluded")
        public Result<String> slowExcluded() throws InterruptedException {
            Thread.sleep(80);
            return Result.success("excluded-ok");
        }

        @GetMapping(value = "/sse", produces = "text/event-stream")
        public String sse(HttpServletResponse response) throws InterruptedException {
            response.setContentType("text/event-stream;charset=UTF-8");
            Thread.sleep(80);
            return "data: hello\n\n";
        }

        @GetMapping(value = "/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public byte[] download(HttpServletResponse response) throws InterruptedException {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            Thread.sleep(80);
            return new byte[]{1, 2, 3, 4};
        }
    }

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(SlowRequestFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.clear();

        WebProperties webProperties = new WebProperties();
        webProperties.getSlowRequest().setThreshold(Duration.ofMillis(50));
        webProperties.getSlowRequest().setExcludeUrls(List.of("/mock/slow-excluded", "/actuator/**"));

        TraceFilter traceFilter = new TraceFilter(webProperties);
        SlowRequestFilter slowRequestFilter = new SlowRequestFilter(webProperties.getSlowRequest());

        mockMvc = MockMvcBuilders.standaloneSetup(new MockSlowController())
                .addFilters(traceFilter, slowRequestFilter)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
            appender.stop();
        }
        MDC.clear();
    }

    @Test
    @DisplayName("MockMvc 请求未超阈值时正常响应且不输出慢请求日志")
    void shouldNotLogWhenRequestIsFast() throws Exception {
        mockMvc.perform(get("/mock/fast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("fast-ok"));

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("MockMvc 请求耗时超阈值时输出携带 TraceId 与全链路信息的 WARN 日志")
    void shouldLogWarnWhenRequestExceedsThreshold() throws Exception {
        mockMvc.perform(post("/mock/slow")
                        .header("X-Trace-Id", "trace-mock-888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("slow-ok"));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        String msg = event.getFormattedMessage();
        assertThat(msg).contains("[SLOW_REQUEST]");
        assertThat(msg).contains("method=[POST]");
        assertThat(msg).contains("uri=[/mock/slow]");
        assertThat(msg).contains("threshold=[50ms]");
        assertThat(msg).contains("traceId=[trace-mock-888]");
    }

    @Test
    @DisplayName("MockMvc 命中 excludeUrls 路径白名单时即使耗时超阈值亦不输出告警日志")
    void shouldSkipWhenUrlIsExcluded() throws Exception {
        mockMvc.perform(get("/mock/slow-excluded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("excluded-ok"));

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("MockMvc 响应 text/event-stream 时自动放行长连接不输出告警日志")
    void shouldSkipSseStream() throws Exception {
        mockMvc.perform(get("/mock/sse"))
                .andExpect(status().isOk());

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("MockMvc 响应 application/octet-stream 时自动放行文件流不输出告警日志")
    void shouldSkipDownloadStream() throws Exception {
        mockMvc.perform(get("/mock/download"))
                .andExpect(status().isOk());

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("MockMvc 请求头携带 Upgrade 握手时自动放行 WebSocket 不输出告警日志")
    void shouldSkipWebSocketUpgradeHeader() throws Exception {
        mockMvc.perform(post("/mock/slow")
                        .header(HttpHeaders.UPGRADE, "websocket"))
                .andExpect(status().isOk());

        assertThat(appender.list).isEmpty();
    }
}
