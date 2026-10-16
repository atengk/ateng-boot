package io.github.atengk.boot.mybatisplus.util;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchUtil 门面工具类单元测试
 * 验证空集合前置卫语句、切片分批调用、自定义批次切片大小及累计影响行数
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("BatchUtil 批量工具类单元测试")
class BatchUtilTest {

    static class MockEntity {
        private final int id;
        public MockEntity(int id) { this.id = id; }
        public int getId() { return id; }
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("1. 空集合防御：传入 null 或 empty 集合直接返回 0，不触发 Mapper 调用")
    void shouldReturnZeroForEmptyCollection() {
        AtengBaseMapper<MockEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);

        assertThat(BatchUtil.insertBatch(mockMapper, null)).isEqualTo(0);
        assertThat(BatchUtil.insertBatch(mockMapper, Collections.emptyList())).isEqualTo(0);

        verify(mockMapper, never()).insertBatchSomeColumn(any());
    }

    @Test
    @DisplayName("2. null Mapper 防御：传入 null mapper 抛出 IllegalArgumentException")
    void shouldThrowExceptionWhenMapperIsNull() {
        assertThatThrownBy(() -> BatchUtil.insertBatch(null, List.of(new MockEntity(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mapper must not be null");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("3. 智能分批切片：2500 条数据、批次 1000 时，精准切为 1000+1000+500 三批执行")
    void shouldChunkCorrectlyWithCustomBatchSize() {
        List<MockEntity> data = new ArrayList<>();
        for (int i = 1; i <= 2500; i++) {
            data.add(new MockEntity(i));
        }

        List<Integer> batchSizes = new ArrayList<>();
        AtengBaseMapper<MockEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);
        when(mockMapper.insertBatchSomeColumn(any())).thenAnswer(invocation -> {
            Collection<MockEntity> chunk = invocation.getArgument(0);
            batchSizes.add(chunk.size());
            return chunk.size();
        });

        int affected = BatchUtil.insertBatch(mockMapper, data, 1000);

        assertThat(affected).isEqualTo(2500);
        assertThat(batchSizes).containsExactly(1000, 1000, 500);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("4. 兜底默认批次：当传入 batchSize <= 0 时，自动采用默认值 1000")
    void shouldFallbackToDefaultSizeWhenBatchSizeIsInvalid() {
        List<MockEntity> data = new ArrayList<>();
        for (int i = 1; i <= 1500; i++) {
            data.add(new MockEntity(i));
        }

        List<Integer> batchSizes = new ArrayList<>();
        AtengBaseMapper<MockEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);
        when(mockMapper.insertBatchSomeColumn(any())).thenAnswer(invocation -> {
            Collection<MockEntity> chunk = invocation.getArgument(0);
            batchSizes.add(chunk.size());
            return chunk.size();
        });

        int affected = BatchUtil.insertBatch(mockMapper, data, -1);

        assertThat(affected).isEqualTo(1500);
        assertThat(batchSizes).containsExactly(1000, 500);
    }
}
