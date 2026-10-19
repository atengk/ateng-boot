package io.github.atengk.boot.sample;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.atengk.boot.mybatisplus.audit.ChangeType;
import io.github.atengk.boot.mybatisplus.audit.DataChangeAuditContextHolder;
import io.github.atengk.boot.mybatisplus.audit.DataChangeAuditProvider;
import io.github.atengk.boot.mybatisplus.audit.DataChangeRecord;
import io.github.atengk.boot.mybatisplus.audit.FieldDiff;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryHelper;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryParser;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.sample.entity.BatchItemEntity;
import io.github.atengk.boot.sample.entity.UserEntity;
import io.github.atengk.boot.sample.mapper.BatchItemMapper;
import io.github.atengk.boot.sample.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 持久层 Phase 2.3 全场景端到端集成测试
 * 覆盖动态查询转译、双方言批量 Upsert、原生批量更新防死锁与数据变更 Diff 审计
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest(properties = {
        "ateng.boot.mybatis-plus.audit-change.enabled=true",
        "ateng.boot.mybatis-plus.audit-change.async=false",
        "ateng.boot.mybatis-plus.audit-change.publish-event=true"
})
@Import(SampleMybatisPlusPhase23IntegrationTest.TestAuditConfig.class)
@DisplayName("持久层 Phase 2.3 全场景端到端集成测试")
class SampleMybatisPlusPhase23IntegrationTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BatchItemMapper batchItemMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingDataChangeAuditProvider auditProvider;

    @TestConfiguration
    static class TestAuditConfig {
        @Bean
        public RecordingDataChangeAuditProvider recordingDataChangeAuditProvider() {
            return new RecordingDataChangeAuditProvider();
        }
    }

    static class RecordingDataChangeAuditProvider implements DataChangeAuditProvider {
        private final List<DataChangeRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void onDataChanged(List<DataChangeRecord> changedRecords) {
            records.addAll(changedRecords);
        }

        public List<DataChangeRecord> getRecords() {
            return records;
        }

        public void clear() {
            records.clear();
        }
    }

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM sys_user");
        jdbcTemplate.execute("DELETE FROM sys_batch_item");
        auditProvider.clear();
    }

    @Test
    @DisplayName("1. 动态查询条件转译与 URL 冒号语法端到端集成")
    void shouldExecuteDynamicQueryWithColonSyntaxAndOperators() {
        // 准备初始数据
        UserEntity u1 = new UserEntity();
        u1.setName("张三");
        u1.setAge(18);
        u1.setEmail("zhangsan@test.com");
        userMapper.insert(u1);

        UserEntity u2 = new UserEntity();
        u2.setName("李四");
        u2.setAge(25);
        u2.setEmail("lisi@test.com");
        userMapper.insert(u2);

        UserEntity u3 = new UserEntity();
        u3.setName("王五");
        u3.setAge(35);
        u3.setEmail("wangwu@test.com");
        userMapper.insert(u3);

        // 1.1 冒号操作符组合查询：age:ge=20 & name:like=李
        QueryWrapper<UserEntity> wrapper1 = DynamicQueryHelper.buildWrapper(
                "age:ge=20&name:like=李",
                UserEntity.class
        );
        List<UserEntity> list1 = userMapper.selectList(wrapper1);
        assertThat(list1).hasSize(1);
        assertThat(list1.get(0).getName()).isEqualTo("李四");

        // 1.2 波浪线 Between 范围查询：age:between=20~30
        QueryWrapper<UserEntity> wrapper2 = DynamicQueryHelper.buildWrapper(
                "age:between=20~30",
                UserEntity.class
        );
        List<UserEntity> list2 = userMapper.selectList(wrapper2);
        assertThat(list2).hasSize(1);
        assertThat(list2.get(0).getName()).isEqualTo("李四");

        // 1.3 IN 操作符查询：age:in=18,35
        QueryWrapper<UserEntity> wrapper3 = DynamicQueryHelper.buildWrapper(
                "age:in=18,35",
                UserEntity.class
        );
        List<UserEntity> list3 = userMapper.selectList(wrapper3);
        assertThat(list3).hasSize(2)
                .extracting(UserEntity::getName)
                .containsExactlyInAnyOrder("张三", "王五");
    }

    @Test
    @DisplayName("2. 作用域 OR 分组条件嵌套端到端集成")
    void shouldExecuteDynamicQueryWithScopedOrGroups() {
        UserEntity u1 = new UserEntity();
        u1.setName("张三");
        u1.setAge(22);
        u1.setEmail("zhangsan@test.com");
        userMapper.insert(u1);

        UserEntity u2 = new UserEntity();
        u2.setName("李四");
        u2.setAge(17); // 未满 18 岁
        u2.setEmail("lisi@test.com");
        userMapper.insert(u2);

        UserEntity u3 = new UserEntity();
        u3.setName("王五");
        u3.setAge(28);
        u3.setEmail("wangwu@test.com");
        userMapper.insert(u3);

        // 作用域 OR 条件：(name LIKE '张' OR email LIKE 'wang') AND age >= 18
        String queryString = "or_1:name:like=张&or_1:email:like=wang&age:ge=18";
        QueryWrapper<UserEntity> wrapper = DynamicQueryHelper.buildWrapper(queryString, UserEntity.class);

        List<UserEntity> result = userMapper.selectList(wrapper);
        assertThat(result).hasSize(2)
                .extracting(UserEntity::getName)
                .containsExactlyInAnyOrder("张三", "王五");
    }

    @Test
    @DisplayName("3. 动态查询六重安全防卫拦截（敏感属性与非法注入安全过滤）")
    void shouldSafelyFilterSensitiveAndInvalidFields() {
        // 尝试查询持久层加密字段 phone 与非法 SQL 注入字符属性
        String malformedQuery = "phone:eq=13800000000&select * from dual:eq=1&age:eq=25";
        QueryWrapper<UserEntity> wrapper = DynamicQueryHelper.buildWrapper(malformedQuery, UserEntity.class);

        // phone 与非法字段被静默安全过滤，仅保留 age = 25
        String targetSql = wrapper.getCustomSqlSegment();
        assertThat(targetSql).contains("age =");
        assertThat(targetSql).doesNotContain("phone");
        assertThat(targetSql).doesNotContain("select * from dual");
    }

    @Test
    @DisplayName("4. 双方言原生批量 Upsert 端到端集成 (upsertBatchSomeColumn & BatchUtil.upsertBatch)")
    void shouldExecuteUpsertBatchSomeColumnE2E() {
        // 4.1 先插入一条已有记录
        BatchItemEntity existing = new BatchItemEntity();
        existing.setId(1001L);
        existing.setName("Item_Original");
        existing.setCode("CODE_ORIGINAL");
        batchItemMapper.insert(existing);

        // 4.2 构造 2 条记录：一条冲突更新（ID=1001），一条新增插入（ID=1002）
        BatchItemEntity item1 = new BatchItemEntity();
        item1.setId(1001L);
        item1.setName("Item_Upserted");
        item1.setCode("CODE_UPDATED");

        BatchItemEntity item2 = new BatchItemEntity();
        item2.setId(1002L);
        item2.setName("Item_New");
        item2.setCode("CODE_NEW");

        // 4.3 执行批量 Upsert
        int affected = BatchUtil.upsertBatch(batchItemMapper, List.of(item1, item2));
        assertThat(affected).isGreaterThan(0);

        // 4.4 验证数据库总行数变为 2
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_batch_item", Long.class);
        assertThat(count).isEqualTo(2L);

        // 4.5 验证冲突记录已被更新
        Map<String, Object> updatedRow = jdbcTemplate.queryForMap(
                "SELECT name, code FROM sys_batch_item WHERE id = 1001"
        );
        assertThat(updatedRow.get("name")).isEqualTo("Item_Upserted");
        assertThat(updatedRow.get("code")).isEqualTo("CODE_UPDATED");

        // 4.6 验证新增记录已成功落盘
        Map<String, Object> newRow = jdbcTemplate.queryForMap(
                "SELECT name, code FROM sys_batch_item WHERE id = 1002"
        );
        assertThat(newRow.get("name")).isEqualTo("Item_New");
        assertThat(newRow.get("code")).isEqualTo("CODE_NEW");
    }

    @Test
    @DisplayName("5. 原生单条 CASE WHEN 批量更新与主键排序防死锁端到端集成")
    void shouldExecuteUpdateBatchSomeColumnWithAntiDeadlock() {
        // 5.1 预先插入 3 条记录
        List<BatchItemEntity> initList = new ArrayList<>();
        for (long id = 1L; id <= 3L; id++) {
            BatchItemEntity item = new BatchItemEntity();
            item.setId(id);
            item.setName("Name_" + id);
            item.setCode("Code_" + id);
            initList.add(item);
        }
        batchItemMapper.insertBatchSomeColumn(initList);

        // 5.2 乱序传入待更新列表（ID: 3, 1, 2），验证主键升序排序防死锁与动态空值保护
        BatchItemEntity u3 = new BatchItemEntity();
        u3.setId(3L);
        u3.setName("Name_3_New");
        u3.setCode("Code_3_New");

        BatchItemEntity u1 = new BatchItemEntity();
        u1.setId(1L);
        u1.setName("Name_1_New");
        u1.setCode(null); // code 为 null，触发 CASE WHEN ELSE 原值保护

        BatchItemEntity u2 = new BatchItemEntity();
        u2.setId(2L);
        u2.setName(null); // name 为 null，触发 CASE WHEN ELSE 原值保护
        u2.setCode("Code_2_New");

        int affected = BatchUtil.updateBatch(batchItemMapper, List.of(u3, u1, u2));
        assertThat(affected).isEqualTo(3);

        // 5.3 验证数据库记录
        Map<String, Object> row1 = jdbcTemplate.queryForMap("SELECT name, code FROM sys_batch_item WHERE id = 1");
        assertThat(row1.get("name")).isEqualTo("Name_1_New");
        assertThat(row1.get("code")).isEqualTo("Code_1"); // 保留原值

        Map<String, Object> row2 = jdbcTemplate.queryForMap("SELECT name, code FROM sys_batch_item WHERE id = 2");
        assertThat(row2.get("name")).isEqualTo("Name_2"); // 保留原值
        assertThat(row2.get("code")).isEqualTo("Code_2_New");

        Map<String, Object> row3 = jdbcTemplate.queryForMap("SELECT name, code FROM sys_batch_item WHERE id = 3");
        assertThat(row3.get("name")).isEqualTo("Name_3_New");
        assertThat(row3.get("code")).isEqualTo("Code_3_New");
    }

    @Test
    @DisplayName("6. 数据变更 Diff 审计拦截器捕获与编程式跳过端到端集成")
    void shouldCaptureDataChangeDiffAndSupportContextHolderBypass() {
        // 6.1 插入一条带 @AuditChange 的 BatchItemEntity
        BatchItemEntity item = new BatchItemEntity();
        item.setId(8888L);
        item.setName("Original_Batch");
        item.setCode("BATCH_001");
        batchItemMapper.insert(item);
        auditProvider.clear();

        // 6.2 正常单实体 updateById 执行
        item.setName("Modified_Batch");
        int updated = batchItemMapper.updateById(item);
        assertThat(updated).isEqualTo(1);

        // 验证捕获到变更 Diff 记录
        List<DataChangeRecord> records = auditProvider.getRecords();
        assertThat(records).hasSize(1);
        DataChangeRecord record = records.get(0);
        assertThat(record.getTableName()).isEqualTo("sys_batch_item");
        assertThat(record.getEntityClass()).isEqualTo(BatchItemEntity.class.getName());
        assertThat(record.getEntityId()).isEqualTo(8888L);
        assertThat(record.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(record.getDiffs()).isNotEmpty();

        FieldDiff nameDiff = record.getDiffs().stream()
                .filter(d -> "name".equals(d.getPropertyName()))
                .findFirst()
                .orElse(null);
        assertThat(nameDiff).isNotNull();
        assertThat(nameDiff.getOldValue()).isEqualTo("Original_Batch");
        assertThat(nameDiff.getNewValue()).isEqualTo("Modified_Batch");

        // 6.3 验证编程式闭包 DataChangeAuditContextHolder.runWithoutAudit 跳过审计
        auditProvider.clear();
        DataChangeAuditContextHolder.runWithoutAudit(() -> {
            item.setName("Bypassed_Batch");
            batchItemMapper.updateById(item);
        });

        // 闭包内更新不触发任何审计记录投递
        assertThat(auditProvider.getRecords()).isEmpty();

        // 数据库值已更新为最新
        Map<String, Object> dbRow = jdbcTemplate.queryForMap("SELECT name FROM sys_batch_item WHERE id = 8888");
        assertThat(dbRow.get("name")).isEqualTo("Bypassed_Batch");
    }
}
