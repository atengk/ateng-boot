package io.github.atengk.boot.web.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PayloadLoggingFilter 与 MockMvc 端到端全真集成测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("PayloadLoggingFilter MockMvc 端到端全真集成测试")
class PayloadLoggingMockMvcTest {

    private MockMvc mockMvc;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @RestController
    @RequestMapping("/mock/payload")
    static class MockPayloadController {

        @PostMapping("/echo")
        public Result<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
            return Result.success(body);
        }

        @PostMapping(value = "/form", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        public Result<String> form(@RequestParam("username") String username,
                                   @RequestParam("password") String password) {
            return Result.success(username + ":ok");
        }

        @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        public Result<String> upload(@RequestParam("file") MultipartFile file) {
            return Result.success("uploaded:" + file.getOriginalFilename());
        }

        @GetMapping(value = "/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public byte[] download() {
            return new byte[]{10, 20, 30, 40};
        }

        @PostMapping("/fail")
        public Result<Void> fail() {
            return Result.failure(400, "参数校验不通过");
        }

        @GetMapping("/excluded")
        public Result<String> excluded() {
            return Result.success("excluded-ok");
        }
    }

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(PayloadLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.clear();

        WebProperties webProperties = new WebProperties();
        webProperties.getPayloadLog().setEnabled(true);
        webProperties.getPayloadLog().setMaxPayloadSize(DataSize.ofBytes(80));
        webProperties.getPayloadLog().setExcludeUrls(List.of("/mock/payload/excluded", "/actuator/**"));

        TraceFilter traceFilter = new TraceFilter(webProperties);
        ContentCachingFilter contentCachingFilter = new ContentCachingFilter();
        PayloadLoggingFilter payloadLoggingFilter = new PayloadLoggingFilter(webProperties.getPayloadLog());

        mockMvc = MockMvcBuilders.standaloneSetup(new MockPayloadController())
                .addFilters(traceFilter, contentCachingFilter, payloadLoggingFilter)
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
    @DisplayName("MockMvc 请求中正确采样 JSON 请求与响应体且敏感凭据被掩码")
    void shouldSampleJsonAndMaskCredentials() throws Exception {
        String json = "{\"username\":\"ateng\",\"password\":\"secretPass123\",\"token\":\"jwt-token\"}";

        mockMvc.perform(post("/mock/payload/echo")
                        .header("X-Trace-Id", "trace-payload-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("ateng"))
                .andExpect(jsonPath("$.data.password").value("secretPass123"));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        String logMsg = event.getFormattedMessage();
        assertThat(logMsg).contains("[HTTP_PAYLOAD]");
        assertThat(logMsg).contains("traceId=[trace-payload-001]");
        assertThat(logMsg).contains("status=[200]");
        assertThat(logMsg).contains("\"password\":\"***\"");
        assertThat(logMsg).contains("\"token\":\"***\"");
        assertThat(logMsg).doesNotContain("secretPass123");
    }

    @Test
    @DisplayName("MockMvc 表单提交中正确识别并掩码表单凭据参数")
    void shouldSampleFormAndMaskCredentials() throws Exception {
        mockMvc.perform(post("/mock/payload/form")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "ateng")
                        .param("password", "formPassword999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(appender.list).hasSize(1);
        String logMsg = appender.list.get(0).getFormattedMessage();
        assertThat(logMsg).contains("password=***");
        assertThat(logMsg).doesNotContain("formPassword999");
    }

    @Test
    @DisplayName("MockMvc 文件上传请求中请求体自动置换为 [BINARY_STREAM] 防爆防 OOM")
    void shouldPlaceholderMultipartUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "hello world".getBytes());

        mockMvc.perform(multipart("/mock/payload/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("uploaded:test.txt"));

        assertThat(appender.list).hasSize(1);
        String logMsg = appender.list.get(0).getFormattedMessage();
        assertThat(logMsg).contains("reqBody=[[BINARY_STREAM]]");
    }

    @Test
    @DisplayName("MockMvc 文件下载响应中响应体自动置换为 [BINARY_STREAM] 且客户端正常接收字节流")
    void shouldPlaceholderDownloadStream() throws Exception {
        mockMvc.perform(get("/mock/payload/download"))
                .andExpect(status().isOk());

        assertThat(appender.list).hasSize(1);
        String logMsg = appender.list.get(0).getFormattedMessage();
        assertThat(logMsg).contains("respBody=[[BINARY_STREAM]]");
    }

    @Test
    @DisplayName("MockMvc 响应状态码 >= 400 时日志级别自动提升为 WARN")
    void shouldElevateToWarnFor4xxStatus() throws Exception {
        mockMvc.perform(post("/mock/payload/fail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // Controller 显式业务失败但 HTTP 200 时根据 HTTP 状态码为 INFO
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    @DisplayName("MockMvc 载荷超过 maxPayloadSize 时自动截断并追加 ... [TRUNCATED]")
    void shouldTruncateWhenPayloadExceedsLimit() throws Exception {
        String longText = "a".repeat(120);
        String json = "{\"data\":\"" + longText + "\"}";

        mockMvc.perform(post("/mock/payload/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        assertThat(appender.list).hasSize(1);
        String logMsg = appender.list.get(0).getFormattedMessage();
        assertThat(logMsg).contains("... [TRUNCATED]");
    }

    @Test
    @DisplayName("MockMvc 命中 excludeUrls 时直接跳过日志输出且响应正常到达")
    void shouldSkipWhenUrlIsExcluded() throws Exception {
        mockMvc.perform(get("/mock/payload/excluded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("excluded-ok"));

        assertThat(appender.list).isEmpty();
    }
}
