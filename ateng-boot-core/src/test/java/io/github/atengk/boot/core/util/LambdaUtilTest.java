package io.github.atengk.boot.core.util;

import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LambdaUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("LambdaUtil 方法引用解析测试")
class LambdaUtilTest {

    @Getter
    @Setter
    static class SampleUser implements Serializable {
        private Long id;
        private String name;
        private Integer age;
        private boolean deleted;
        private String URL;
    }

    @Test
    @DisplayName("推导标准 Getter 属性字段名")
    void shouldResolveStandardGetterFieldNames() {
        assertEquals("id", LambdaUtil.getFieldName(SampleUser::getId));
        assertEquals("name", LambdaUtil.getFieldName(SampleUser::getName));
        assertEquals("age", LambdaUtil.getFieldName(SampleUser::getAge));
    }

    @Test
    @DisplayName("推导 boolean is 属性字段名")
    void shouldResolveBooleanIsFieldNames() {
        assertEquals("deleted", LambdaUtil.getFieldName(SampleUser::isDeleted));
    }

    @Test
    @DisplayName("连续大写缩写属性名保持规范语义")
    void shouldPreserveConsecutiveUppercaseAcronyms() {
        assertEquals("URL", LambdaUtil.getFieldName(SampleUser::getURL));
    }

    @Test
    @DisplayName("空安全与前置校验")
    void shouldHandleNullDefensively() {
        assertThrows(NullPointerException.class, () -> LambdaUtil.getFieldName(null));
    }
}
