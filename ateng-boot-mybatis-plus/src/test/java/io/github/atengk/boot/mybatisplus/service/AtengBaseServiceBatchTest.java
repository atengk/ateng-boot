package io.github.atengk.boot.mybatisplus.service;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.atengk.boot.mybatisplus.entity.BaseDeleteIdEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantEntity;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengBaseService 与 AtengBaseMapper 批量写入与冲突更新门面测试 (Issue #125)
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("AtengBaseService 批量写入与冲突更新门面测试")
class AtengBaseServiceBatchTest {

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static SqlSession sqlSession;
    private static TestItemMapper itemMapper;
    private static TestItemService itemService;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    @TableName("t_batch_service_item")
    static class TestBatchItemEntity extends BaseDeleteIdEntity implements TenantEntity, Serializable {
        @TableId
        private Long id;
        private String name;
        private String status;
        private Integer count;
        private String tenantId;
    }

    interface TestItemMapper extends AtengBaseMapper<TestBatchItemEntity> {
    }

    interface TestItemService extends AtengBaseService<TestBatchItemEntity> {
    }

    static class TestItemServiceImpl extends AtengServiceImpl<TestItemMapper, TestBatchItemEntity> implements TestItemService {
        public TestItemServiceImpl(TestItemMapper mapper) {
            this.baseMapper = mapper;
        }
    }

    @BeforeAll
    static void setUpAll() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:service_batch_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS t_batch_service_item");
            stmt.execute("""
                CREATE TABLE t_batch_service_item (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    count INT NOT NULL,
                    tenant_id VARCHAR(64),
                    create_time TIMESTAMP,
                    create_by VARCHAR(64),
                    update_time TIMESTAMP,
                    update_by VARCHAR(64),
                    version INT DEFAULT 1,
                    deleted BIGINT DEFAULT 0
                )
            """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("batch_service_test", new JdbcTransactionFactory(), dataSource));

        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(TestItemMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSession = sqlSessionFactory.openSession(true);

        itemMapper = sqlSession.getMapper(TestItemMapper.class);
        itemService = new TestItemServiceImpl(itemMapper);
    }

    @AfterAll
    static void tearDownAll() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @BeforeEach
    void setUpEach() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE t_batch_service_item");
        }
    }

    @Test
    @DisplayName("1. upsertBatch 批量冲突更新：已存在记录更新，不存在记录插入并自愈")
    void shouldUpsertBatchEntities() {
        // 先插入一条存量数据
        TestBatchItemEntity existing = new TestBatchItemEntity();
        existing.setId(100L);
        existing.setName("item-100");
        existing.setStatus("INIT");
        existing.setCount(10);
        itemService.save(existing);

        // 构造包含已存在 (id=100) 和新数据 (id=101) 的集合
        TestBatchItemEntity item1 = new TestBatchItemEntity();
        item1.setId(100L);
        item1.setName("item-100-updated");
        item1.setStatus("ACTIVE");
        item1.setCount(20);

        TestBatchItemEntity item2 = new TestBatchItemEntity();
        item2.setId(101L);
        item2.setName("item-101-new");
        item2.setStatus("INIT");
        item2.setCount(50);

        int affected = itemService.upsertBatch(List.of(item1, item2));
        assertThat(affected).isGreaterThan(0);

        TestBatchItemEntity loaded1 = itemService.getById(100L);
        assertThat(loaded1.getName()).isEqualTo("item-100-updated");
        assertThat(loaded1.getStatus()).isEqualTo("ACTIVE");
        assertThat(loaded1.getCount()).isEqualTo(20);

        TestBatchItemEntity loaded2 = itemService.getById(101L);
        assertThat(loaded2).isNotNull();
        assertThat(loaded2.getName()).isEqualTo("item-101-new");
        assertThat(loaded2.getCreateTime()).isNotNull(); // 验证自愈
    }

    @Test
    @DisplayName("2. updateBatch 原生单条 CASE WHEN 批量更新：按主键更新并自动排序")
    void shouldUpdateBatchEntities() {
        TestBatchItemEntity item1 = new TestBatchItemEntity();
        item1.setId(201L);
        item1.setName("name-201");
        item1.setStatus("OLD");
        item1.setCount(1);

        TestBatchItemEntity item2 = new TestBatchItemEntity();
        item2.setId(202L);
        item2.setName("name-202");
        item2.setStatus("OLD");
        item2.setCount(2);

        itemService.insertBatch(List.of(item1, item2));

        // 故意以逆序传入，验证 updateBatch 内部升序排序与更新成功
        TestBatchItemEntity update2 = new TestBatchItemEntity();
        update2.setId(202L);
        update2.setName("name-202-v2");
        update2.setStatus("NEW");
        update2.setCount(20);

        TestBatchItemEntity update1 = new TestBatchItemEntity();
        update1.setId(201L);
        update1.setName("name-201-v2");
        update1.setStatus("NEW");
        update1.setCount(10);

        int updated = itemService.updateBatch(List.of(update2, update1), 10);
        assertThat(updated).isEqualTo(2);

        TestBatchItemEntity loaded1 = itemService.getById(201L);
        assertThat(loaded1.getName()).isEqualTo("name-201-v2");
        assertThat(loaded1.getStatus()).isEqualTo("NEW");

        TestBatchItemEntity loaded2 = itemService.getById(202L);
        assertThat(loaded2.getName()).isEqualTo("name-202-v2");
        assertThat(loaded2.getStatus()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("3. upsert 单实体冲突更新便捷门面：验证 Service 与 Mapper 单条 upsert")
    void shouldUpsertSingleEntity() {
        TestBatchItemEntity entity = new TestBatchItemEntity();
        entity.setId(301L);
        entity.setName("single-insert");
        entity.setStatus("OK");
        entity.setCount(1);

        // 首次 upsert 为插入
        boolean success = itemService.upsert(entity);
        assertThat(success).isTrue();

        TestBatchItemEntity loaded = itemService.getById(301L);
        assertThat(loaded.getName()).isEqualTo("single-insert");

        // Mapper 层的 upsertSomeColumn
        entity.setName("single-update-via-mapper");
        int rows = itemMapper.upsertSomeColumn(entity);
        assertThat(rows).isGreaterThan(0);

        TestBatchItemEntity reloaded = itemService.getById(301L);
        assertThat(reloaded.getName()).isEqualTo("single-update-via-mapper");
    }

    @Test
    @DisplayName("4. 边界防卫：空集合与空实体调用 upsertBatch / updateBatch / upsert 安全保底")
    void shouldHandleEmptyAndNullDefensively() {
        assertThat(itemService.upsertBatch(null)).isZero();
        assertThat(itemService.upsertBatch(Collections.emptyList())).isZero();
        assertThat(itemService.updateBatch(null)).isZero();
        assertThat(itemService.updateBatch(Collections.emptyList())).isZero();
        assertThat(itemService.upsert(null)).isFalse();
        assertThat(itemMapper.upsertSomeColumn(null)).isZero();
    }
}
