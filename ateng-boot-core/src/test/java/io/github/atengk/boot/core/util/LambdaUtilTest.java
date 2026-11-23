package io.github.atengk.boot.core.util;

import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    @AfterEach
    void resetCache() {
        LambdaUtil.configureCache(1024, null);
        LambdaUtil.clearCache();
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

    @Test
    @DisplayName("缓存读写与清空验证")
    void shouldCacheAndClearMetadata() {
        LambdaUtil.clearCache();
        assertEquals(0, LambdaUtil.getCacheSize());

        assertEquals("name", LambdaUtil.getFieldName(SampleUser::getName));
        assertEquals(1, LambdaUtil.getCacheSize());

        // 重复调用命中缓存
        assertEquals("name", LambdaUtil.getFieldName(SampleUser::getName));
        assertEquals(1, LambdaUtil.getCacheSize());

        LambdaUtil.clearCache();
        assertEquals(0, LambdaUtil.getCacheSize());
    }

    @Test
    @DisplayName("configureCache: 动态配置 LRU 缓存规格、容量淘汰与合法性拦截")
    void shouldConfigureCacheWithCapacityAndTtl() {
        // 1. 配置小容量缓存规格
        LambdaUtil.configureCache(2, java.time.Duration.ofMinutes(5));
        assertEquals(0, LambdaUtil.getCacheSize());

        // 2. 依次读取 3 个不同字段名，验证触发 LRU 淘汰且容量稳定在 2
        assertEquals("id", LambdaUtil.getFieldName(SampleUser::getId));
        assertEquals("name", LambdaUtil.getFieldName(SampleUser::getName));
        assertEquals(2, LambdaUtil.getCacheSize());
        assertEquals("age", LambdaUtil.getFieldName(SampleUser::getAge));
        assertEquals(2, LambdaUtil.getCacheSize());

        // 3. 非法参数快速失败
        assertThrows(IllegalArgumentException.class, () -> LambdaUtil.configureCache(0, null));
        assertThrows(IllegalArgumentException.class, () -> LambdaUtil.configureCache(-10, null));
        assertThrows(IllegalArgumentException.class, () -> LambdaUtil.configureCache(10, java.time.Duration.ofMinutes(-1)));
    }
}
