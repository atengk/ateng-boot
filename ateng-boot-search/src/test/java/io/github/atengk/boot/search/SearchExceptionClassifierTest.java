package io.github.atengk.boot.search;

import io.github.atengk.boot.search.util.SearchExceptionClassifier;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
}
