package io.github.atengk.boot.sample;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.sample.entity.BatchItemEntity;
import io.github.atengk.boot.sample.mapper.BatchItemMapper;
import io.github.atengk.boot.sample.service.BatchItemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 原生高性能分批批量插入引擎端到端集成测试
 * 覆盖原生多值 SQL 注入、逻辑删除字段跳过、分批切片插入、事务原子回滚与防御性边界
 *
 * @author Ateng
 * @since 2026-10-16
 */
@SpringBootTest
@DisplayName("原生高性能分批批量插入引擎集成测试")
class SampleBatchInsertIntegrationTest {

    @Autowired
    private BatchItemMapper batchItemMapper;

    @Autowired
    private BatchItemService batchItemService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM sys_batch_item");
    }

    @Test
    @DisplayName("1. 原生单条多值 SQL 批量插入与逻辑删除字段跳过验证")
    void shouldInsertBatchSomeColumnAndSkipLogicDelete() {
        List<BatchItemEntity> items = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            BatchItemEntity item = new BatchItemEntity();
            item.setName("Item-" + i);
            item.setCode("CODE-" + i);
            // 故意在内存中将逻辑删除标记设为 1
            item.setDeleted(1);
            items.add(item);
        }

        // 1. 调用 AtengBaseMapper 原生多值批量插入方法
        int affectedRows = batchItemMapper.insertBatchSomeColumn(items);
        assertThat(affectedRows).isEqualTo(5);

        // 2. 验证每个实体自动生成并回填了主键 ID
        assertThat(items).allMatch(item -> item.getId() != null);

        // 3. 验证数据库实际记录数
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_batch_item", Long.class);
        assertThat(count).isEqualTo(5L);

        // 4. 验证逻辑删除字段：由于 InsertBatchSomeColumn 过滤了 isLogicDelete()，
        // deleted 字段被排除在 INSERT SQL 列之外，数据库落盘使用表默认值 0 而非内存中的 1
        Map<String, Object> rawDbRow = jdbcTemplate.queryForMap(
                "SELECT id, name, code, deleted FROM sys_batch_item WHERE id = ?", items.get(0).getId()
        );
        assertThat(rawDbRow.get("name")).isEqualTo("Item-1");
        assertThat(rawDbRow.get("code")).isEqualTo("CODE-1");
        assertThat(rawDbRow.get("deleted")).isEqualTo(0);
    }

    @Test
    @DisplayName("2. 业务层分批切片插入：2500 条数据按 batchSize=1000 成功切分为 3 批并完整入库")
    void shouldBatchInsertWithSlicingViaService() {
        int total = 2500;
        List<BatchItemEntity> items = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            BatchItemEntity item = new BatchItemEntity();
            item.setName("Batch-" + i);
            item.setCode("BCODE-" + i);
            items.add(item);
        }

        int affectedRows = batchItemService.insertBatch(items, 1000);
        assertThat(affectedRows).isEqualTo(total);

        // 验证数据库全量写入
        Long dbCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_batch_item", Long.class);
        assertThat(dbCount).isEqualTo((long) total);

        // 验证 MyBatis-Plus selectCount 查询
        Long mpCount = batchItemMapper.selectCount(new LambdaQueryWrapper<>());
        assertThat(mpCount).isEqualTo((long) total);
    }

    @Test
    @DisplayName("3. 默认批次大小分批插入：使用 AtengBaseService 默认批次参数成功执行")
    void shouldBatchInsertWithDefaultBatchSize() {
        int count = 50;
        List<BatchItemEntity> items = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            BatchItemEntity item = new BatchItemEntity();
            item.setName("Default-" + i);
            item.setCode("DEF-" + i);
            items.add(item);
        }

        int affectedRows = batchItemService.insertBatch(items);
        assertThat(affectedRows).isEqualTo(count);

        Long dbCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_batch_item", Long.class);
        assertThat(dbCount).isEqualTo((long) count);
    }

    @Test
    @DisplayName("4. 静态门面 BatchUtil：支持对任意 AtengBaseMapper 实施安全分批插入")
    void shouldBatchInsertViaBatchUtilFacade() {
        int count = 1200;
        List<BatchItemEntity> items = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            BatchItemEntity item = new BatchItemEntity();
            item.setName("Util-" + i);
            item.setCode("UTIL-" + i);
            items.add(item);
        }

        int affectedRows = BatchUtil.insertBatch(batchItemMapper, items, 500);
        assertThat(affectedRows).isEqualTo(count);

        Long dbCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_batch_item", Long.class);
        assertThat(dbCount).isEqualTo((long) count);
    }

    @Test
    @DisplayName("5. 事务原子性保证：分批插入过程中发生异常时，受 @Transactional 守护全量回滚")
    void shouldRollbackAllBatchesWhenExceptionOccurs() {
        int count = 500;
        List<BatchItemEntity> items = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            BatchItemEntity item = new BatchItemEntity();
            item.setName("Tx-" + i);
            item.setCode("TX-" + i);
            items.add(item);
        }

        // 执行在插入后抛出异常的方法
        assertThatThrownBy(() -> batchItemService.insertBatchWithException(items, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated exception");

        // 验证数据库无残留脏数据，全部批次原子回滚
        Long dbCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_batch_item", Long.class);
        assertThat(dbCount).isEqualTo(0L);
    }

    @Test
    @DisplayName("6. 边界与防御性测试：空集合或 null 输入安全返回 0，空 Mapper 抛出非法参数异常")
    void shouldHandleDefensiveEdgeCases() {
        assertThat(batchItemService.insertBatch(null)).isEqualTo(0);
        assertThat(batchItemService.insertBatch(Collections.emptyList())).isEqualTo(0);
        assertThat(BatchUtil.insertBatch(batchItemMapper, null, 100)).isEqualTo(0);
        assertThat(BatchUtil.insertBatch(batchItemMapper, Collections.emptyList(), 100)).isEqualTo(0);

        List<BatchItemEntity> items = List.of(new BatchItemEntity());
        assertThatThrownBy(() -> BatchUtil.insertBatch(null, items, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mapper must not be null");
    }
}
