package io.github.atengk.boot.web.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP Payload 采样安全日志过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("PayloadLoggingFilter 采样过滤器单元测试")
class PayloadLoggingFilterTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(PayloadLoggingFilter.class);
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
    @DisplayName("验证默认构造器、优先级及配置属性契约")
    void shouldVerifyDefaultPropertiesAndOrder() {
        PayloadLoggingFilter filter = new PayloadLoggingFilter();
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
        assertThat(filter.getMaxPayloadSize()).isEqualTo(DataSize.ofKilobytes(4));
        assertThat(filter.getExcludeUrls()).isEmpty();
        assertThat(filter.shouldNotFilterAsyncDispatch()).isTrue();

        WebProperties.PayloadLogProperties props = new WebProperties.PayloadLogProperties();
        props.setMaxPayloadSize(DataSize.ofKilobytes(8));
        props.setExcludeUrls(List.of("/health", "/actuator/**"));
        PayloadLoggingFilter configuredFilter = new PayloadLoggingFilter(props);
        assertThat(configuredFilter.getMaxPayloadSize()).isEqualTo(DataSize.ofKilobytes(8));
        assertThat(configuredFilter.getExcludeUrls()).containsExactly("/health", "/actuator/**");
    }

    @Test
    @DisplayName("敏感凭据掩码：JSON、Form 与 XML 格式凭据均被安全置换为 ***")
    void shouldMaskSensitiveCredentialsInVariousFormats() {
        // 1. JSON 格式凭据掩码
        String json = "{\"username\":\"ateng\",\"password\":\"my_secret_pwd\",\"token\":\"jwt.xxx.yyy\",\"secret\":\"ak_123\",\"authorization\":\"Bearer token\"}";
        String maskedJson = PayloadLoggingFilter.maskSensitiveData(json);
        assertThat(maskedJson).contains("\"password\":\"***\"");
        assertThat(maskedJson).contains("\"token\":\"***\"");
        assertThat(maskedJson).contains("\"secret\":\"***\"");
        assertThat(maskedJson).contains("\"authorization\":\"***\"");
        assertThat(maskedJson).contains("\"username\":\"ateng\"");

        // 2. 表单与 URL 参数凭据掩码
        String form = "username=ateng&password=plainPassword&token=abc123456&grant_type=code";
        String maskedForm = PayloadLoggingFilter.maskSensitiveData(form);
        assertThat(maskedForm).contains("password=***");
        assertThat(maskedForm).contains("token=***");
        assertThat(maskedForm).contains("username=ateng");
        assertThat(maskedForm).contains("grant_type=code");

        // 3. XML 标签凭据掩码
        String xml = "<root><username>ateng</username><password>myPassword</password><token>tok</token></root>";
        String maskedXml = PayloadLoggingFilter.maskSensitiveData(xml);
        assertThat(maskedXml).contains("<password>***</password>");
        assertThat(maskedXml).contains("<token>***</token>");
        assertThat(maskedXml).contains("<username>ateng</username>");
    }

    @Test
    @DisplayName("MIME 类型判别防卫：正确识别文本/JSON 与二进制/流式媒体")
    void shouldDifferentiateTextAndBinaryMimeTypes() {
        assertThat(PayloadLoggingFilter.isTextOrJson("application/json")).isTrue();
        assertThat(PayloadLoggingFilter.isTextOrJson("application/json;charset=UTF-8")).isTrue();
        assertThat(PayloadLoggingFilter.isTextOrJson("application/problem+json")).isTrue();
        assertThat(PayloadLoggingFilter.isTextOrJson("application/xml")).isTrue();
        assertThat(PayloadLoggingFilter.isTextOrJson("text/plain")).isTrue();
        assertThat(PayloadLoggingFilter.isTextOrJson("text/html")).isTrue();
        assertThat(PayloadLoggingFilter.isTextOrJson("application/x-www-form-urlencoded")).isTrue();

        assertThat(PayloadLoggingFilter.isBinaryOrMultipart("multipart/form-data")).isTrue();
        assertThat(PayloadLoggingFilter.isBinaryOrMultipart("application/octet-stream")).isTrue();
        assertThat(PayloadLoggingFilter.isBinaryOrMultipart("image/png")).isTrue();
        assertThat(PayloadLoggingFilter.isBinaryOrMultipart("image/jpeg")).isTrue();
        assertThat(PayloadLoggingFilter.isBinaryOrMultipart("application/pdf")).isTrue();
        assertThat(PayloadLoggingFilter.isBinaryOrMultipart("text/event-stream")).isTrue();
    }

    @Test
    @DisplayName("正常请求执行时输出单行 INFO 级别日志且正确写入并释放响应体")
    void shouldLogInfoAndCopyBodyToResponseForSuccessfulRequest() throws ServletException, IOException {
        PayloadLoggingFilter filter = new PayloadLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user");
        request.setContentType("application/json");
        String reqBody = "{\"name\":\"ateng\",\"password\":\"secret\"}";
        request.setContent(reqBody.getBytes(StandardCharsets.UTF_8));
        MDC.put(TraceFilter.MDC_TRACE_ID_KEY, "trace-abc-123");

        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> {
            res.setContentType("application/json;charset=UTF-8");
            res.getOutputStream().write("{\"code\":200,\"msg\":\"success\"}".getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilter(request, response, filterChain);

        // 验证日志输出
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        String msg = event.getFormattedMessage();
        assertThat(msg).contains("[HTTP_PAYLOAD]");
        assertThat(msg).contains("traceId=[trace-abc-123]");
        assertThat(msg).contains("method=[POST]");
        assertThat(msg).contains("uri=[/api/user]");
        assertThat(msg).contains("status=[200]");
        assertThat(msg).contains("reqBody=[{\"name\":\"ateng\",\"password\":\"***\"}]");
        assertThat(msg).contains("respBody=[{\"code\":200,\"msg\":\"success\"}]");

        // 验证客户端响应体通过 copyBodyToResponse 正常到达
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":200,\"msg\":\"success\"}");
    }

    @Test
    @DisplayName("当 HTTP 响应状态码 >= 400 时日志级别自动提升为 WARN")
    void shouldElevateLogToWarnWhenStatusIs400OrHigher() throws ServletException, IOException {
        PayloadLoggingFilter filter = new PayloadLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/error");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain failingChain = (req, res) -> {
            if (res instanceof jakarta.servlet.http.HttpServletResponse httpRes) {
                httpRes.setStatus(400);
            }
            res.setContentType("application/json");
            res.getOutputStream().write("{\"code\":400,\"msg\":\"bad request\"}".getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilter(request, response, failingChain);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("status=[400]");
    }

    @Test
    @DisplayName("大文件与二进制流响应直接以 [BINARY_STREAM] 占位防 OOM 且不破坏原始流")
    void shouldPlaceholderBinaryAndMultipartStreams() throws ServletException, IOException {
        PayloadLoggingFilter filter = new PayloadLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/upload");
        request.setContentType("multipart/form-data; boundary=---12345");
        request.setContent("dummy-file-binary-bytes".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain binaryChain = (req, res) -> {
            res.setContentType("application/octet-stream");
            res.getOutputStream().write(new byte[]{0, 1, 2, 3});
        };

        filter.doFilter(request, response, binaryChain);

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("reqBody=[[BINARY_STREAM]]");
        assertThat(msg).contains("respBody=[[BINARY_STREAM]]");
        assertThat(response.getContentAsByteArray()).isEqualTo(new byte[]{0, 1, 2, 3});
    }

    @Test
    @DisplayName("超长载荷超出 maxPayloadSize 时截断并追加 ... [TRUNCATED]")
    void shouldTruncatePayloadWhenExceedingLimit() throws ServletException, IOException {
        PayloadLoggingFilter filter = new PayloadLoggingFilter(DataSize.ofBytes(20));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/long");
        request.setContentType("application/json");
        request.setContent("{\"message\":\"this is a very long json string that exceeds twenty bytes\"}".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain echoChain = (req, res) -> {
            res.setContentType("application/json");
            res.getOutputStream().write("{\"result\":\"this is also a long response string exceeding limit\"}".getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilter(request, response, echoChain);

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("... [TRUNCATED]");
    }

    @Test
    @DisplayName("命中 excludeUrls 白名单路径时直接跳过日志输出且正常响应")
    void shouldSkipWhenUrlIsExcluded() throws ServletException, IOException {
        PayloadLoggingFilter filter = new PayloadLoggingFilter(DataSize.ofKilobytes(4), List.of("/actuator/**", "/health"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            res.setContentType("application/json");
            res.getOutputStream().write("{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilter(request, response, chain);

        assertThat(appender.list).isEmpty();
        assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    @DisplayName("下游过滤器或控制器抛出异常时自动提升为 WARN 记录且 100% 重抛原始异常")
    void shouldLogWarnAndRethrowWhenExceptionThrown() {
        PayloadLoggingFilter filter = new PayloadLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/crash");
        request.setContentType("application/json");
        request.setContent("{\"id\":1}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain throwingChain = (req, res) -> {
            throw new IllegalArgumentException("测试异常：业务参数校验失败");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("测试异常：业务参数校验失败");

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("status=[500]");
        assertThat(event.getFormattedMessage()).contains("uri=[/api/crash]");
    }
}
