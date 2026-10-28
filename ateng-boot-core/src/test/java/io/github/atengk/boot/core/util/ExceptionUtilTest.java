package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异常处理工具 ExceptionUtil 单测
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("异常处理工具 ExceptionUtil 测试")
class ExceptionUtilTest {

    @Test
    @DisplayName("getRootCause 提取深层根因与自引用循环防御验证")
    void shouldExtractRootCauseSafely() {
        assertThat(ExceptionUtil.getRootCause(null)).isNull();

        // 单层异常
        IllegalArgumentException single = new IllegalArgumentException("single error");
        assertThat(ExceptionUtil.getRootCause(single)).isSameAs(single);

        // 三层嵌套异常
        NullPointerException root = new NullPointerException("deepest root cause");
        IllegalStateException middle = new IllegalStateException("middle layer", root);
        RuntimeException top = new RuntimeException("top level", middle);

        assertThat(ExceptionUtil.getRootCause(top)).isSameAs(root);
    }

    @Test
    @DisplayName("getMessage 空安全与类名保底验证")
    void shouldGetMessageSafely() {
        assertThat(ExceptionUtil.getMessage(null)).isEqualTo("");
        assertThat(ExceptionUtil.getMessage(new RuntimeException("explicit message"))).isEqualTo("explicit message");

        // 无 message 时回退类名
        assertThat(ExceptionUtil.getMessage(new RuntimeException())).isEqualTo(RuntimeException.class.getName());
    }

    @Test
    @DisplayName("stackTraceToString 序列化与有界长度截断验证")
    void shouldSerializeStackTraceWithBounds() {
        assertThat(ExceptionUtil.stackTraceToString(null)).isEqualTo("");
        assertThat(ExceptionUtil.stackTraceToString(null, 100)).isEqualTo("");

        Exception ex = new Exception("test stacktrace");
        String fullTrace = ExceptionUtil.stackTraceToString(ex);
        assertThat(fullTrace)
                .contains("java.lang.Exception: test stacktrace")
                .contains("ExceptionUtilTest");

        // 有界截断
        String truncated = ExceptionUtil.stackTraceToString(ex, 80);
        assertThat(truncated.length()).isLessThanOrEqualTo(80);
        assertThat(truncated).endsWith("\n... [truncated]");

        // maxLen 小于等于 0 保底
        assertThat(ExceptionUtil.stackTraceToString(ex, 0)).isEqualTo("");
        assertThat(ExceptionUtil.stackTraceToString(ex, -5)).isEqualTo("");
    }
}
