package io.github.atengk.boot.http.error;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HttpErrorHandler 单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class HttpErrorHandlerTest {

    private HttpErrorHandler errorHandler;
    private final URI testUri = URI.create("https://api.example.com/test");

    @BeforeEach
    void setUp() {
        errorHandler = new HttpErrorHandler();
    }

    @Test
    @DisplayName("测试 hasError 对成功状态码与错误状态码的判定")
    void testHasError() throws IOException {
        MockClientHttpResponse okResponse = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        MockClientHttpResponse createdResponse = new MockClientHttpResponse(new byte[0], HttpStatus.CREATED);
        MockClientHttpResponse badRequestResponse = new MockClientHttpResponse(new byte[0], HttpStatus.BAD_REQUEST);
        MockClientHttpResponse serverErrorResponse = new MockClientHttpResponse(new byte[0], HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(errorHandler.hasError(okResponse)).isFalse();
        assertThat(errorHandler.hasError(createdResponse)).isFalse();
        assertThat(errorHandler.hasError(badRequestResponse)).isTrue();
        assertThat(errorHandler.hasError(serverErrorResponse)).isTrue();
    }

    @Test
    @DisplayName("测试普通 JSON 错误响应体的正常提取与异常转译")
    void testHandleNormalError() {
        String errorJson = "{\"code\":40001,\"message\":\"invalid payload\"}";
        MockClientHttpResponse response = new MockClientHttpResponse(errorJson.getBytes(StandardCharsets.UTF_8), HttpStatus.BAD_REQUEST);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        assertThatThrownBy(() -> errorHandler.handleError(testUri, HttpMethod.POST, response))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getHttpStatusCode()).isEqualTo(400);
                    assertThat(remoteEx.getRequestMethod()).isEqualTo(HttpMethod.POST);
                    assertThat(remoteEx.getRequestUrl()).isEqualTo(testUri);
                    assertThat(remoteEx.getResponseBody()).isEqualTo(errorJson);
                });
    }

    @Test
    @DisplayName("测试超过 2048 字符的超长响应体安全截断与标记追加")
    void testTruncateLongResponseBody() {
        // 构建 3000 字符的超长文本
        String longBody = "A".repeat(3000);
        MockClientHttpResponse response = new MockClientHttpResponse(longBody.getBytes(StandardCharsets.UTF_8), HttpStatus.INTERNAL_SERVER_ERROR);
        response.getHeaders().setContentType(MediaType.TEXT_PLAIN);

        assertThatThrownBy(() -> errorHandler.handleError(testUri, HttpMethod.GET, response))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getHttpStatusCode()).isEqualTo(500);
                    String body = remoteEx.getResponseBody();
                    assertThat(body).isNotNull();
                    assertThat(body).startsWith("A".repeat(2048));
                    assertThat(body).endsWith("... [truncated]");
                    assertThat(body).hasSize(2048 + "... [truncated]".length());
                });
    }

    @Test
    @DisplayName("测试二进制响应流跳过读取并替换为占位文本")
    void testBinaryContentOmitted() {
        byte[] fakeBinary = new byte[]{0x1F, (byte) 0x8B, 0x08, 0x00}; // gzip magic bytes
        MockClientHttpResponse octetResponse = new MockClientHttpResponse(fakeBinary, HttpStatus.INTERNAL_SERVER_ERROR);
        octetResponse.getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);

        assertThatThrownBy(() -> errorHandler.handleError(testUri, HttpMethod.GET, octetResponse))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getResponseBody()).isEqualTo("[binary content omitted]");
                });

        MockClientHttpResponse imageResponse = new MockClientHttpResponse(fakeBinary, HttpStatus.INTERNAL_SERVER_ERROR);
        imageResponse.getHeaders().setContentType(MediaType.IMAGE_PNG);

        assertThatThrownBy(() -> errorHandler.handleError(testUri, HttpMethod.GET, imageResponse))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getResponseBody()).isEqualTo("[binary content omitted]");
                });

        MockClientHttpResponse pdfResponse = new MockClientHttpResponse(fakeBinary, HttpStatus.INTERNAL_SERVER_ERROR);
        pdfResponse.getHeaders().setContentType(MediaType.APPLICATION_PDF);

        assertThatThrownBy(() -> errorHandler.handleError(testUri, HttpMethod.GET, pdfResponse))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getResponseBody()).isEqualTo("[binary content omitted]");
                });
    }

    @Test
    @DisplayName("测试空响应体能够安全转译且不产生 NPE")
    void testEmptyResponseBody() {
        MockClientHttpResponse emptyResponse = new MockClientHttpResponse(new byte[0], HttpStatus.NOT_FOUND);

        assertThatThrownBy(() -> errorHandler.handleError(testUri, HttpMethod.GET, emptyResponse))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getHttpStatusCode()).isEqualTo(404);
                    assertThat(remoteEx.getResponseBody()).isNull();
                });
    }
}
