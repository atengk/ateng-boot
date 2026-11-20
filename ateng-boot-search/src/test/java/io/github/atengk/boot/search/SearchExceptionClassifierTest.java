package io.github.atengk.boot.search;

import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.util.SearchExceptionClassifier;
import org.apache.http.StatusLine;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SearchExceptionClassifier 单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class SearchExceptionClassifierTest {

    @Test
    @DisplayName("测试 null 异常返回 -1 与 false")
    void testNullHandling() {
        assertThat(SearchExceptionClassifier.extractStatusCode(null)).isEqualTo(-1);
        assertThat(SearchExceptionClassifier.isVersionConflict(null)).isFalse();
        assertThat(SearchExceptionClassifier.isNotFound(null)).isFalse();
    }

    @Test
    @DisplayName("测试普通 RuntimeException 状态识别")
    void testGenericException() {
        RuntimeException ex = new RuntimeException("generic error");
        assertThat(SearchExceptionClassifier.extractStatusCode(ex)).isEqualTo(-1);
        assertThat(SearchExceptionClassifier.isVersionConflict(ex)).isFalse();
        assertThat(SearchExceptionClassifier.isNotFound(ex)).isFalse();
    }

    @Test
    @DisplayName("测试基于异常信息文本识别 409 冲突")
    void testMessageBasedConflict() {
        RuntimeException ex = new RuntimeException("version_conflict_engine_exception: [1] version conflict");
        assertThat(SearchExceptionClassifier.isVersionConflict(ex)).isTrue();
    }

    @Test
    @DisplayName("测试嵌套因果链中的 409 冲突识别")
    void testNestedCauseConflict() {
        RuntimeException cause = new RuntimeException("409 Conflict");
        RuntimeException wrapper = new RuntimeException("Wrapper exception", cause);
        assertThat(SearchExceptionClassifier.isVersionConflict(wrapper)).isTrue();
    }

    @Test
    @DisplayName("测试瞬态错误识别与全谱系状态码因果映射")
    void testTransientAndResultCodeClassification() {
        assertThat(SearchExceptionClassifier.isTransient(new SocketTimeoutException("read timed out"))).isTrue();
        assertThat(SearchExceptionClassifier.isTransient(new ConnectException("Connection refused"))).isTrue();
        assertThat(SearchExceptionClassifier.isTransient(mockResponseException(429))).isTrue();
        assertThat(SearchExceptionClassifier.isTransient(mockResponseException(408))).isTrue();
        assertThat(SearchExceptionClassifier.isTransient(mockResponseException(502))).isTrue();
        assertThat(SearchExceptionClassifier.isTransient(mockResponseException(503))).isTrue();
        assertThat(SearchExceptionClassifier.isTransient(mockResponseException(504))).isTrue();
        assertThat(SearchExceptionClassifier.isTransient(new java.util.concurrent.TimeoutException("future timed out"))).isTrue();
        assertThat(SearchExceptionClassifier.isTransient(new java.net.UnknownHostException("search-node"))).isTrue();
        assertThat(SearchExceptionClassifier.isTransient(mockResponseException(400))).isFalse();
        assertThat(SearchExceptionClassifier.isTransient(mockResponseException(409))).isFalse();

        assertThat(SearchExceptionClassifier.classifyResultCode(mockResponseException(400), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_BAD_REQUEST);
        assertThat(SearchExceptionClassifier.classifyResultCode(mockResponseException(401), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_AUTH_ERROR);
        assertThat(SearchExceptionClassifier.classifyResultCode(mockResponseException(403), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_AUTH_ERROR);
        assertThat(SearchExceptionClassifier.classifyResultCode(mockResponseException(429), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_RATE_LIMITED);
        assertThat(SearchExceptionClassifier.classifyResultCode(mockResponseException(408), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_TIMEOUT_ERROR);
        assertThat(SearchExceptionClassifier.classifyResultCode(mockResponseException(504), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_TIMEOUT_ERROR);
        assertThat(SearchExceptionClassifier.classifyResultCode(new SocketTimeoutException("timeout"), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_TIMEOUT_ERROR);
        assertThat(SearchExceptionClassifier.classifyResultCode(new java.util.concurrent.TimeoutException("timeout"), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_TIMEOUT_ERROR);
        assertThat(SearchExceptionClassifier.classifyResultCode(mockResponseException(502), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_ENGINE_CONNECT_ERROR);
        assertThat(SearchExceptionClassifier.classifyResultCode(mockResponseException(503), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_ENGINE_CONNECT_ERROR);
        assertThat(SearchExceptionClassifier.classifyResultCode(new ConnectException("refused"), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_ENGINE_CONNECT_ERROR);
        assertThat(SearchExceptionClassifier.classifyResultCode(new java.net.UnknownHostException("unknown"), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_ENGINE_CONNECT_ERROR);
        assertThat(SearchExceptionClassifier.classifyResultCode(mockResponseException(404), SearchResultCode.SEARCH_OPERATION_ERROR))
                .isEqualTo(SearchResultCode.SEARCH_INDEX_NOT_FOUND);
    }

    private ResponseException mockResponseException(int status) {
        Response response = Mockito.mock(Response.class);
        StatusLine statusLine = Mockito.mock(StatusLine.class);
        Mockito.when(statusLine.getStatusCode()).thenReturn(status);
        Mockito.when(statusLine.getReasonPhrase()).thenReturn("Status " + status);
        Mockito.when(response.getStatusLine()).thenReturn(statusLine);
        Mockito.when(response.getHost()).thenReturn(new org.apache.http.HttpHost("localhost", 9200));
        Mockito.when(response.getRequestLine()).thenReturn(
                new org.apache.http.message.BasicRequestLine("GET", "/", org.apache.http.HttpVersion.HTTP_1_1));
        try {
            return new ResponseException(response);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
