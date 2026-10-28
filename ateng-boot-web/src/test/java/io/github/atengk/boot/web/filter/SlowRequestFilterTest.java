package io.github.atengk.boot.web.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 慢请求告警过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("SlowRequestFilter 慢请求告警过滤器单元测试")
class SlowRequestFilterTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(SlowRequestFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.clear();
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
    @DisplayName("验证默认构造器与配置优先级契约")
    void shouldVerifyDefaultPropertiesAndOrder() {
        SlowRequestFilter filter = new SlowRequestFilter();
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 3);
        assertThat(filter.getThreshold()).isEqualTo(Duration.ofSeconds(3));
        assertThat(filter.getExcludeUrls()).isEmpty();
        assertThat(filter.shouldNotFilterAsyncDispatch()).isTrue();

        WebProperties.SlowRequestProperties props = new WebProperties.SlowRequestProperties();
        props.setThreshold(Duration.ofMillis(500));
        props.setExcludeUrls(List.of("/health", "/actuator/**"));
        SlowRequestFilter configuredFilter = new SlowRequestFilter(props);
        assertThat(configuredFilter.getThreshold()).isEqualTo(Duration.ofMillis(500));
        assertThat(configuredFilter.getExcludeUrls()).containsExactly("/health", "/actuator/**");
    }

    @Test
    @DisplayName("正常请求执行耗时未达阈值时不输出慢请求告警日志")
    void shouldNotLogWhenRequestDurationBelowThreshold() throws ServletException, IOException {
        SlowRequestFilter filter = new SlowRequestFilter(Duration.ofMillis(200));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/fast");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("请求执行耗时超过配置阈值时输出携带四维上下文与元数据的 WARN 日志")
    void shouldLogWarnWhenRequestDurationExceedsThreshold() throws ServletException, IOException {
        SlowRequestFilter filter = new SlowRequestFilter(Duration.ofMillis(50));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/slow");
        request.setRemoteAddr("192.168.1.100");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put(TraceFilter.MDC_TRACE_ID_KEY, "trace-test-12345");

        FilterChain delayingChain = (req, res) -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        filter.doFilter(request, response, delayingChain);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        String formattedMessage = event.getFormattedMessage();
        assertThat(formattedMessage).contains("[SLOW_REQUEST]");
        assertThat(formattedMessage).contains("method=[POST]");
        assertThat(formattedMessage).contains("uri=[/api/slow]");
        assertThat(formattedMessage).contains("threshold=[50ms]");
        assertThat(formattedMessage).contains("clientIp=[192.168.1.100]");
        assertThat(formattedMessage).contains("traceId=[trace-test-12345]");
    }

    @Test
    @DisplayName("当 MDC 缺失时能够从 X-Trace-Id 请求头或降级占位符提取 TraceId")
    void shouldFallbackTraceIdFromHeaderOrDash() throws ServletException, IOException {
        SlowRequestFilter filter = new SlowRequestFilter(Duration.ofMillis(20));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/header-trace");
        request.addHeader("X-Trace-Id", "header-trace-999");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain delayingChain = (req, res) -> {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        filter.doFilter(request, response, delayingChain);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("traceId=[header-trace-999]");
    }

    @Test
    @DisplayName("智能放行 WebSocket 升级握手请求（请求头或响应头包含 Upgrade）")
    void shouldSkipWebSocketUpgradeRequest() throws ServletException, IOException {
        SlowRequestFilter filter = new SlowRequestFilter(Duration.ofMillis(20));

        // 场景 1：请求头携带 Upgrade: websocket
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/ws/chat");
        req1.addHeader("Upgrade", "websocket");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(req1, res1, (req, res) -> sleep(40));
        assertThat(appender.list).isEmpty();

        // 场景 2：请求头携带 Connection: Upgrade
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/ws/chat2");
        req2.addHeader("Connection", "keep-alive, Upgrade");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, (req, res) -> sleep(40));
        assertThat(appender.list).isEmpty();

        // 场景 3：响应头设置 Upgrade: websocket
        MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/ws/chat3");
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        res3.setHeader("Upgrade", "websocket");
        filter.doFilter(req3, res3, (req, res) -> sleep(40));
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("智能放行 SSE 事件流请求（text/event-stream）")
    void shouldSkipServerSentEventsStream() throws ServletException, IOException {
        SlowRequestFilter filter = new SlowRequestFilter(Duration.ofMillis(20));

        // 场景 1：响应声明 Content-Type: text/event-stream
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/api/sse");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        res1.setContentType("text/event-stream;charset=UTF-8");
        filter.doFilter(req1, res1, (req, res) -> sleep(40));
        assertThat(appender.list).isEmpty();

        // 场景 2：客户端声明 Accept: text/event-stream
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/sse2");
        req2.addHeader("Accept", "text/event-stream");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, (req, res) -> sleep(40));
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("智能放行二进制与文件流下载（application/octet-stream）")
    void shouldSkipOctetStreamDownload() throws ServletException, IOException {
        SlowRequestFilter filter = new SlowRequestFilter(Duration.ofMillis(20));

        // 场景 1：响应声明 Content-Type: application/octet-stream
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/api/download");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        res1.setContentType("application/octet-stream");
        filter.doFilter(req1, res1, (req, res) -> sleep(40));
        assertThat(appender.list).isEmpty();

        // 场景 2：客户端声明 Accept: application/octet-stream
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/download2");
        req2.addHeader("Accept", "application/octet-stream");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, (req, res) -> sleep(40));
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("智能放行异步挂起请求（request.isAsyncStarted() 为 true）")
    void shouldSkipAsyncStartedRequest() throws ServletException, IOException {
        SlowRequestFilter filter = new SlowRequestFilter(Duration.ofMillis(20));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/async-job");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain asyncChain = (req, res) -> {
            request.startAsync();
            sleep(40);
        };

        filter.doFilter(request, response, asyncChain);
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("支持配置 excludeUrls 白名单路径放行")
    void shouldSkipExcludedUrls() throws ServletException, IOException {
        SlowRequestFilter filter = new SlowRequestFilter(Duration.ofMillis(20), List.of("/actuator/**", "/internal/*"));

        // 匹配 /actuator/**
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(req1, res1, (req, res) -> sleep(40));
        assertThat(appender.list).isEmpty();

        // 匹配 /internal/*
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/internal/metrics");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, (req, res) -> sleep(40));
        assertThat(appender.list).isEmpty();

        // 未匹配路径仍需告警
        MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/api/normal");
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        filter.doFilter(req3, res3, (req, res) -> sleep(40));
        assertThat(appender.list).hasSize(1);
    }

    @Test
    @DisplayName("下游过滤器或控制器抛出异常时仍准确记录慢请求日志并无损透传原始异常")
    void shouldLogSlowRequestAndRethrowOriginalException() {
        SlowRequestFilter filter = new SlowRequestFilter(Duration.ofMillis(20));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/error");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain failingChain = (req, res) -> {
            sleep(40);
            throw new IllegalStateException("下游数据库连接超时");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("下游数据库连接超时");

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("[SLOW_REQUEST]");
        assertThat(appender.list.get(0).getFormattedMessage()).contains("uri=[/api/error]");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
