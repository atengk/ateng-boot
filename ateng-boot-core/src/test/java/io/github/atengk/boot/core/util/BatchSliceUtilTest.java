package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 大批量切片流式处理实用工具单元测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
class BatchSliceUtilTest {

    @Test
    @DisplayName("测试大批量切片分批正确性")
    void testSlice() {
        List<Integer> list = List.of(1, 2, 3, 4, 5, 6, 7);
        List<List<Integer>> slices = BatchSliceUtil.slice(list, 3);

        assertThat(slices).hasSize(3);
        assertThat(slices.get(0)).containsExactly(1, 2, 3);
        assertThat(slices.get(1)).containsExactly(4, 5, 6);
        assertThat(slices.get(2)).containsExactly(7);
    }

    @Test
    @DisplayName("测试空列表与边界切片安全返回")
    void testSliceEmptyAndBounds() {
        assertThat(BatchSliceUtil.slice(null, 10)).isEmpty();
        assertThat(BatchSliceUtil.slice(Collections.emptyList(), 10)).isEmpty();

        assertThatThrownBy(() -> BatchSliceUtil.slice(List.of(1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be greater than 0");

        assertThatThrownBy(() -> BatchSliceUtil.slice(List.of(1), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试 forEachSlice 遍历分批消费")
    void testForEachSlice() {
        List<String> list = List.of("A", "B", "C", "D", "E");
        List<List<String>> consumed = new ArrayList<>();

        BatchSliceUtil.forEachSlice(list, 2, consumed::add);

        assertThat(consumed).hasSize(3);
        assertThat(consumed.get(0)).containsExactly("A", "B");
        assertThat(consumed.get(1)).containsExactly("C", "D");
        assertThat(consumed.get(2)).containsExactly("E");

        // null consumer
        assertThatThrownBy(() -> BatchSliceUtil.forEachSlice(list, 2, null))
                .isInstanceOf(NullPointerException.class);
    }
}
