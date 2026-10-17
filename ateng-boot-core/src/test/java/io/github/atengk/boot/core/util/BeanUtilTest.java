package io.github.atengk.boot.core.util;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BeanUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("BeanUtil 属性拷贝测试")
class BeanUtilTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class SourceUser {
        private Long id;
        private String name;
        private Integer age;
        private String email;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class TargetUser {
        private Long id;
        private String name;
        private Integer age;
        private String email;
    }

    @Test
    @DisplayName("copy: 实例化并完成属性拷贝")
    void shouldCopyPropertiesToNewInstance() {
        SourceUser source = new SourceUser(1L, "张三", 20, "zhangsan@example.com");
        TargetUser target = BeanUtil.copy(source, TargetUser.class);

        assertNotNull(target);
        assertEquals(1L, target.getId());
        assertEquals("张三", target.getName());
        assertEquals(20, target.getAge());
        assertEquals("zhangsan@example.com", target.getEmail());

        assertNull(BeanUtil.copy(null, TargetUser.class));
    }

    @Test
    @DisplayName("copyList: 批量拷贝对象列表")
    void shouldCopyListSuccessfully() {
        List<SourceUser> sourceList = Arrays.asList(
                new SourceUser(1L, "张三", 20, "a@b.com"),
                new SourceUser(2L, "李四", 25, "c@d.com")
        );

        List<TargetUser> targetList = BeanUtil.copyList(sourceList, TargetUser.class);
        assertEquals(2, targetList.size());
        assertEquals("张三", targetList.get(0).getName());
        assertEquals("李四", targetList.get(1).getName());

        assertTrue(BeanUtil.copyList(null, TargetUser.class).isEmpty());
        assertTrue(BeanUtil.copyList(Collections.emptyList(), TargetUser.class).isEmpty());
    }

    @Test
    @DisplayName("copyIgnoreNull: 仅用非空字段覆盖目标对象")
    void shouldCopyIgnoreNullProperties() {
        TargetUser target = new TargetUser(100L, "初始姓名", 18, "init@example.com");
        SourceUser patchSource = new SourceUser(null, "新姓名", null, "new@example.com");

        BeanUtil.copyIgnoreNull(patchSource, target);

        // id 和 age 保持原状，name 和 email 被新值覆盖
        assertEquals(100L, target.getId());
        assertEquals("新姓名", target.getName());
        assertEquals(18, target.getAge());
        assertEquals("new@example.com", target.getEmail());
    }
}
