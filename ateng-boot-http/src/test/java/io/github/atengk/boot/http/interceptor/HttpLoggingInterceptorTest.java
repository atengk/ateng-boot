package io.github.atengk.boot.http.interceptor;

import io.github.atengk.boot.http.constant.HttpConstants;
import io.github.atengk.boot.http.properties.HttpLoggingLevel;
import io.github.atengk.boot.http.properties.HttpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 出站 HTTP 结构化日志与敏感脱敏拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class HttpLoggingInterceptorTest {

    private HttpProperties properties;
    private HttpLoggingInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new HttpProperties();
        properties.getLogging().setLevel(HttpLoggingLevel.FULL);
        interceptor = new HttpLoggingInterceptor(properties);
    }

    @Test
    @DisplayName("cURL 命令生成与敏感 Header 动态脱敏：Bearer Token 与敏感凭据打码")
    void shouldGenerateCurlWithMaskedHeaders() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://api.example.com/orders"));
        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        request.getHeaders().add("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secretToken");
        request.getHeaders().add("Cookie", "SESSION=my-session-id-12345");
        request.getHeaders().add("X-Api-Key", "sk-1234567890abcdef");
        request.getHeaders().add("Accept", "application/json");

        byte[] requestBody = "{\"productId\":\"P100\",\"amount\":2}".getBytes(StandardCharsets.UTF_8);

        String curl = interceptor.generateCurlCommand(request, requestBody, properties.getLogging());

        assertNotNull(curl);
        assertTrue(curl.startsWith("curl -X POST 'https://api.example.com/orders'"));
        // 验证敏感头脱敏（DesensitizeUtil 保持 1:1 掩码字符长度，保留前后各 2 位）
        assertTrue(curl.contains("-H 'Authorization: Bearer ey"));
        assertTrue(curl.contains("en'"));
        assertTrue(curl.contains("-H 'Cookie: SE"));
        assertTrue(curl.contains("45'"));
        assertTrue(curl.contains("-H 'X-Api-Key: sk"));
        assertTrue(curl.contains("ef'"));
        // 验证普通头保留真实明文
        assertTrue(curl.contains("-H 'Content-Type: application/json'"));
        assertTrue(curl.contains("-H 'Accept: application/json'"));
        // 验证请求体包含
        assertTrue(curl.contains("--data-raw '{\"productId\":\"P100\",\"amount\":2}'"));
        // 严格杜绝原始明文 Token 泄漏至 cURL 文本中
        assertFalse(curl.contains("secretToken"));
        assertFalse(curl.contains("my-session-id-12345"));
        assertFalse(curl.contains("1234567890abcdef"));
    }

    @Test
    @DisplayName("FULL 级别下响应体缓冲：验证响应流在日志消费后依然可被多次读取并反序列化")
    void shouldSupportMultipleBodyReadsUnderFullLogging() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.example.com/profile"));
        byte[] expectedPayload = "{\"userId\":1001,\"username\":\"ateng\"}".getBytes(StandardCharsets.UTF_8);

        ClientHttpRequestExecution execution = (req, body) -> {
            MockClientHttpResponse resp = new MockClientHttpResponse(expectedPayload, HttpStatus.OK);
            resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return resp;
        };

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

        // 验证返回的是 BufferingClientHttpResponseWrapper
        assertInstanceOf(BufferingClientHttpResponseWrapper.class, response);

        // 第一次读取
        String firstRead = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        assertEquals("{\"userId\":1001,\"username\":\"ateng\"}", firstRead);

        // 第二次重复读取（验证未发生 Stream closed 或 EOF）
        String secondRead = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        assertEquals("{\"userId\":1001,\"username\":\"ateng\"}", secondRead);
    }

    @Test
    @DisplayName("大流下载通道显式旁路：标记流式传输时禁止缓冲 Body，守护零内存缓冲不变量")
    void shouldBypassBodyBufferingWhenStreamingDownload() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.example.com/files/large.zip"));
        // 模拟 HttpTemplate.download 注入流式标记
        request.getHeaders().add(HttpConstants.HEADER_STREAM_TRANSFER, "true");

        byte[] fakeLargeStream = "STREAMING-CHUNK-DATA-HERE".getBytes(StandardCharsets.UTF_8);

        ClientHttpRequestExecution execution = (req, body) -> {
            // 验证内部流式传输控制头在网络物理层前已被安全剥离
            assertNull(req.getHeaders().getFirst(HttpConstants.HEADER_STREAM_TRANSFER), "内部流式标记头必须已被剥离");
            MockClientHttpResponse resp = new MockClientHttpResponse(fakeLargeStream, HttpStatus.OK);
            resp.getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return resp;
        };

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

        // 验证大流下载场景下绝不包装为 BufferingClientHttpResponseWrapper
        assertFalse(response instanceof BufferingClientHttpResponseWrapper, "大流下载绝不允许全量缓冲入内存");

        // 验证原始流依然完好直通可读
        String readContent = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        assertEquals("STREAMING-CHUNK-DATA-HERE", readContent);
    }

    @Test
    @DisplayName("二进制多媒体流自动过滤：不打印乱码二进制内容")
    void shouldFilterBinaryContentInLogging() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://api.example.com/upload"));
        request.getHeaders().setContentType(MediaType.IMAGE_PNG);
        byte[] binaryData = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

        String curl = interceptor.generateCurlCommand(request, binaryData, properties.getLogging());
        assertTrue(curl.contains("--data-binary '[binary content omitted]'"));
        assertFalse(curl.contains("PNG"));
    }

    @Test
    @DisplayName("长文本请求体安全截断：超过 maxBodyLength 追加省略提示")
    void shouldTruncateBodyWhenExceedingMaxLength() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://api.example.com/echo"));
        request.getHeaders().setContentType(MediaType.TEXT_PLAIN);
        properties.getLogging().setMaxBodyLength(10);

        byte[] longText = "1234567890EXTRA-CONTENT".getBytes(StandardCharsets.UTF_8);
        String curl = interceptor.generateCurlCommand(request, longText, properties.getLogging());

        assertTrue(curl.contains("--data-raw '1234567890... [truncated]'"));
        assertFalse(curl.contains("EXTRA-CONTENT"));
    }

    @Test
    @DisplayName("网络异常透传与链路耗时：发生 IOException 时保留原始因果链并向外抛出")
    void shouldPropagateIOExceptionWithoutSwallowing() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://unreachable.host.com/api"));

        ClientHttpRequestExecution execution = (req, body) -> {
            throw new IOException("Connection refused: connect");
        };

        IOException ex = assertThrows(IOException.class, () -> interceptor.intercept(request, new byte[0], execution));
        assertEquals("Connection refused: connect", ex.getMessage());
    }

    @Test
    @DisplayName("NONE 日志级别下：拦截器完全静默直通，但内部流式标记头依然必须安全剥离")
    void shouldBypassDirectlyWhenLevelIsNone() throws IOException {
        properties.getLogging().setLevel(HttpLoggingLevel.NONE);
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.example.com/silent"));
        request.getHeaders().add(HttpConstants.HEADER_STREAM_TRANSFER, "true");

        byte[] payload = "plain-data".getBytes(StandardCharsets.UTF_8);
        ClientHttpRequestExecution execution = (req, body) -> {
            assertNull(req.getHeaders().getFirst(HttpConstants.HEADER_STREAM_TRANSFER), "关闭日志时内部流式头仍须被严格剥离");
            return new MockClientHttpResponse(payload, HttpStatus.OK);
        };

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);
        assertFalse(response instanceof BufferingClientHttpResponseWrapper);
        assertEquals("plain-data", StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("分块传输超限无损保护：超过 64KB 缓冲容量时安全退化为拼接流，确保下游能完整无损读取全部数据")
    void shouldSupportChunkedStreamOverflowWithoutDataLoss() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.example.com/chunked-large"));
        // 构造 70KB 数据（超过默认 64KB 缓冲上限）
        int totalSize = 70 * 1024;
        byte[] largeData = new byte[totalSize];
        for (int i = 0; i < totalSize; i++) {
            largeData[i] = (byte) (i % 127);
        }

        ClientHttpRequestExecution execution = (req, body) -> {
            // 不设置 Content-Length 模拟 chunked 分块传输
            MockClientHttpResponse resp = new MockClientHttpResponse(new ByteArrayInputStream(largeData), HttpStatus.OK);
            resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return resp;
        };

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

        // 验证被安全包装为溢出拼接包装器
        assertInstanceOf(BufferingClientHttpResponseWrapper.class, response);
        BufferingClientHttpResponseWrapper wrapper = (BufferingClientHttpResponseWrapper) response;
        assertTrue(wrapper.isOverflow(), "分块超限流必须标记为 isOverflow 拼接流");

        // 验证下游通过 getBody 读取出的总字节数 100% 完整，零丢失
        byte[] readBackBytes = StreamUtils.copyToByteArray(response.getBody());
        assertEquals(totalSize, readBackBytes.length, "下游读取到的总字节数必须完全等于原始流大小");
        assertArrayEquals(largeData, readBackBytes, "下游读取到的流数据内容必须与原始流完全一致");
    }
}
