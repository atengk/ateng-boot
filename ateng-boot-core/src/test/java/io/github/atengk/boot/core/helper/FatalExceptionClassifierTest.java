package io.github.atengk.boot.core.helper;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 毒丸与致命不可自愈异常判定器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("FatalExceptionClassifier 单元测试")
class FatalExceptionClassifierTest {

    @Test
    @DisplayName("直接致命异常判定")
    void testDirectFatalExceptions() {
        assertTrue(FatalExceptionClassifier.isFatal(new ValidationException("校验失败")));
        assertTrue(FatalExceptionClassifier.isFatal(new ConstraintViolationException("违规", Collections.emptySet())));
        assertTrue(FatalExceptionClassifier.isFatal(new IllegalArgumentException("非法参数")));
        assertTrue(FatalExceptionClassifier.isFatal(new IllegalStateException("非法状态")));
        assertTrue(FatalExceptionClassifier.isFatal(new NullPointerException("空指针")));
        assertTrue(FatalExceptionClassifier.isFatal(new ClassCastException("类型转换异常")));
        assertTrue(FatalExceptionClassifier.isFatal(new ClassNotFoundException("类未找到")));
    }

    @Test
    @DisplayName("多层嵌套与包装的毒丸异常判定")
    void testNestedFatalExceptions() {
        // RuntimeException 包装 ValidationException
        RuntimeException wrapped = new RuntimeException("外层业务异常", new ValidationException("底层校验失败"));
        assertTrue(FatalExceptionClassifier.isFatal(wrapped));

        // 三层嵌套
        Exception deep = new Exception("Level1", new IllegalStateException("Level2", new IllegalArgumentException("Level3")));
        assertTrue(FatalExceptionClassifier.isFatal(deep));
    }

    @Test
    @DisplayName("常规可自愈网络与超时异常判定为非 Fatal")
    void testNonFatalExceptions() {
        assertFalse(FatalExceptionClassifier.isFatal(null));
        assertFalse(FatalExceptionClassifier.isFatal(new IOException("网络连接断开")));
        assertFalse(FatalExceptionClassifier.isFatal(new TimeoutException("请求超时")));
        assertFalse(FatalExceptionClassifier.isFatal(new RuntimeException("普通业务抖动")));
    }

    @Test
    @DisplayName("自定义致命异常注册与判定")
    void testCustomFatalRegistration() {
        class CustomPoisonPillException extends RuntimeException {
            CustomPoisonPillException(String msg) {
                super(msg);
            }
        }

        // 未注册前为 false
        assertFalse(FatalExceptionClassifier.isFatal(new CustomPoisonPillException("未注册")));

        // 注册后为 true
        FatalExceptionClassifier.registerFatal(CustomPoisonPillException.class);
        assertTrue(FatalExceptionClassifier.isFatal(new CustomPoisonPillException("已注册")));
    }

    @Test
    @DisplayName("getRootCause 提取最深层异常")
    void testGetRootCause() {
        assertNull(FatalExceptionClassifier.getRootCause(null));

        Exception single = new Exception("单层");
        assertSame(single, FatalExceptionClassifier.getRootCause(single));

        IllegalArgumentException root = new IllegalArgumentException("根因");
        Exception multi = new Exception("Level 1", new RuntimeException("Level 2", root));
        assertSame(root, FatalExceptionClassifier.getRootCause(multi));
    }
}
