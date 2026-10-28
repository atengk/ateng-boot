package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.entity.BaseDeleteIdEntity;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.injector.methods.UpdateBatchSomeColumn;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 原生批量更新与主键排序防死锁引擎全维单元测试与 H2 集成测试
 * 覆盖：
 * 1. BatchUtil 批量更新空集合防御、null Mapper 防御与切片批次逻辑
 * 2. 内存主键正序排列防死锁铁律（Anti-Deadlock Invariant）
 * 3. UpdateBatchSomeColumn SQL 语法树编译断言（CASE WHEN / ELSE 动态回退 / 排除主键与仅插入列）
 * 4. 字段更新审计自愈（仅填充 updateTime / updateBy，绝不覆盖 createTime / createBy）
 * 5. H2 内存库端到端集成测试（乱序多行字段更新、null 字段动态回退保护、逻辑删除过滤）
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("原生批量更新与主键排序防死锁引擎测试")
class BatchUpdateTest {

    // ==================== 测试实体与 Mapper ====================

    @Getter
    @Setter
    @ToString(callSuper = true)
    @TableName("t_user_batch_update")
    static class UserUpdateEntity extends BaseDeleteIdEntity {
        private String username;
        private String email;
        private Integer age;
    }

    interface UserUpdateMapper extends AtengBaseMapper<UserUpdateEntity> {
    }

    @Getter
    @Setter
    static class CustomIdEntity {
        @TableId(type = IdType.ASSIGN_ID)
        private String customId;
        private String title;
    }

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        BatchUtil.setCurrentUserProvider(null);
    }

    // ==================== 第一部分：门面与前置卫语句 ====================

    @Test
    @DisplayName("1. 空集合防御：传入 null 或 empty 集合直接返回 0，不触发 Mapper 调用")
    void shouldReturnZeroForEmptyCollection() {
        @SuppressWarnings("unchecked")
        AtengBaseMapper<UserUpdateEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);

        assertThat(BatchUtil.updateBatch(mockMapper, null)).isZero();
        assertThat(BatchUtil.updateBatch(mockMapper, Collections.emptyList())).isZero();
        assertThat(BatchUtil.updateBatchSomeColumn(mockMapper, null)).isZero();
        assertThat(BatchUtil.updateBatchSomeColumn(mockMapper, Collections.emptyList())).isZero();

        verify(mockMapper, never()).updateBatchSomeColumn(any());
    }

    @Test
    @DisplayName("2. null Mapper 防御：传入 null mapper 抛出 IllegalArgumentException")
    void shouldThrowExceptionWhenMapperIsNull() {
        assertThatThrownBy(() -> BatchUtil.updateBatch(null, List.of(new UserUpdateEntity())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mapper must not be null");

        assertThatThrownBy(() -> BatchUtil.updateBatchSomeColumn(null, List.of(new UserUpdateEntity())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mapper must not be null");
    }

    @Test
    @DisplayName("3. 智能分批切片：2500 条数据、批次 1000 时，精准切为 1000+1000+500 三批执行")
    void shouldChunkCorrectlyWithCustomBatchSize() {
        List<UserUpdateEntity> data = new ArrayList<>();
        for (long i = 1; i <= 2500; i++) {
            UserUpdateEntity u = new UserUpdateEntity();
            u.setId(i);
            u.setUsername("user_" + i);
            data.add(u);
        }

        List<Integer> batchSizes = new ArrayList<>();
        @SuppressWarnings("unchecked")
        AtengBaseMapper<UserUpdateEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);
        when(mockMapper.updateBatchSomeColumn(any())).thenAnswer(invocation -> {
            Collection<UserUpdateEntity> chunk = invocation.getArgument(0);
            batchSizes.add(chunk.size());
            return chunk.size();
        });

        int affected = BatchUtil.updateBatch(mockMapper, data, 1000);

        assertThat(affected).isEqualTo(2500);
        assertThat(batchSizes).containsExactly(1000, 1000, 500);
    }

    @Test
    @DisplayName("4. 兜底默认批次：当传入 batchSize <= 0 时，自动采用默认值 1000")
    void shouldFallbackToDefaultSizeWhenBatchSizeIsInvalid() {
        List<UserUpdateEntity> data = new ArrayList<>();
        for (long i = 1; i <= 1500; i++) {
            UserUpdateEntity u = new UserUpdateEntity();
            u.setId(i);
            u.setUsername("user_" + i);
            data.add(u);
        }

        List<Integer> batchSizes = new ArrayList<>();
        @SuppressWarnings("unchecked")
        AtengBaseMapper<UserUpdateEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);
        when(mockMapper.updateBatchSomeColumn(any())).thenAnswer(invocation -> {
            Collection<UserUpdateEntity> chunk = invocation.getArgument(0);
            batchSizes.add(chunk.size());
            return chunk.size();
        });

        int affected = BatchUtil.updateBatch(mockMapper, data, -1);

        assertThat(affected).isEqualTo(1500);
        assertThat(batchSizes).containsExactly(1000, 500);
    }

    // ==================== 第二部分：并发防死锁排序断言 ====================

    @Test
    @DisplayName("5. 防死锁排序断言：对 BaseIdEntity 乱序列表自动按主键 ID 正序排列")
    void shouldSortBaseIdEntitiesByIdAscending() {
        UserUpdateEntity u1 = new UserUpdateEntity();
        u1.setId(5L);
        UserUpdateEntity u2 = new UserUpdateEntity();
        u2.setId(2L);
        UserUpdateEntity u3 = new UserUpdateEntity();
        u3.setId(8L);
        UserUpdateEntity u4 = new UserUpdateEntity();
        u4.setId(1L);

        List<UserUpdateEntity> sorted = BatchUtil.sortByIdAscending(List.of(u1, u2, u3, u4));
        assertThat(sorted).extracting(BaseDeleteIdEntity::getId).containsExactly(1L, 2L, 5L, 8L);
    }

    @Test
    @DisplayName("6. 防死锁排序断言：对自定义 @TableId 普通实体乱序列表自动按主键正序排列")
    void shouldSortCustomTableIdEntitiesByIdAscending() {
        CustomIdEntity c1 = new CustomIdEntity();
        c1.setCustomId("dept_003");
        CustomIdEntity c2 = new CustomIdEntity();
        c2.setCustomId("dept_001");
        CustomIdEntity c3 = new CustomIdEntity();
        c3.setCustomId("dept_002");

        List<CustomIdEntity> sorted = BatchUtil.sortByIdAscending(List.of(c1, c2, c3));
        assertThat(sorted).extracting(CustomIdEntity::getCustomId).containsExactly("dept_001", "dept_002", "dept_003");
    }

    @Test
    @DisplayName("7. AntiDeadlockSqlSource 兜底重排断言：直接调用 SQL 时参数 map 中的 list 被强制正序重排")
    void shouldSortListInAntiDeadlockSqlSource() {
        UserUpdateEntity u1 = new UserUpdateEntity();
        u1.setId(9L);
        UserUpdateEntity u2 = new UserUpdateEntity();
        u2.setId(3L);
        UserUpdateEntity u3 = new UserUpdateEntity();
        u3.setId(7L);

        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(UserUpdateMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, UserUpdateEntity.class);

        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("list", new ArrayList<>(List.of(u1, u2, u3)));

        UpdateBatchSomeColumn.AntiDeadlockSqlSource.sortListIfPresent(paramMap, tableInfo);

        @SuppressWarnings("unchecked")
        List<UserUpdateEntity> result = (List<UserUpdateEntity>) paramMap.get("list");
        assertThat(result).extracting(BaseDeleteIdEntity::getId).containsExactly(3L, 7L, 9L);
    }

    // ==================== 第三部分：审计自愈断言 ====================

    @Test
    @DisplayName("8. 更新审计自愈断言：仅注入 updateTime/updateBy，绝不覆盖 createTime/createBy")
    void shouldAutoHealForUpdateSafely() {
        UserContextHolder.set("test_operator");

        UserUpdateEntity entity = new UserUpdateEntity();
        LocalDateTime originalCreateTime = LocalDateTime.of(2020, 1, 1, 10, 0, 0);
        entity.setCreateTime(originalCreateTime);
        entity.setCreateBy("original_author");

        BatchUtil.autoHealForUpdate(entity);

        assertThat(entity.getCreateTime()).isEqualTo(originalCreateTime);
        assertThat(entity.getCreateBy()).isEqualTo("original_author");
        assertThat(entity.getUpdateTime()).isNotNull();
        assertThat(entity.getUpdateBy()).isEqualTo("test_operator");
    }

    // ==================== 第四部分：SQL 结构编译断言 ====================

    @Test
    @DisplayName("9. SQL 结构编译断言：生成标准 ANSI CASE WHEN，排除主键与仅插入审计列，包含逻辑删除条件")
    void shouldBuildUpdateBatchSqlCorrectly() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(UserUpdateMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, UserUpdateEntity.class);

        UpdateBatchSomeColumn injector = new UpdateBatchSomeColumn();
        injector.inject(assistant, UserUpdateMapper.class, UserUpdateEntity.class, tableInfo);

        MappedStatement ms = configuration.getMappedStatement(UserUpdateMapper.class.getName() + ".updateBatchSomeColumn");
        assertThat(ms).isNotNull();

        UserUpdateEntity sample = new UserUpdateEntity();
        sample.setId(1L);
        sample.setUsername("new_name");
        sample.setEmail("new_email@test.com");
        sample.setUpdateTime(LocalDateTime.now());
        sample.setUpdateBy("admin");
        // age 保持为 null，验证动态 <trim> 对全 null 字段不生成 CASE WHEN 的优化
        sample.setAge(null);

        String boundSql = ms.getSqlSource().getBoundSql(Collections.singletonMap("list", List.of(sample))).getSql();

        assertThat(boundSql).contains("UPDATE t_user_batch_update");
        assertThat(boundSql).contains("SET");
        assertThat(boundSql).contains("username = CASE id");
        assertThat(boundSql).contains("ELSE username END");
        assertThat(boundSql).contains("email = CASE id");
        assertThat(boundSql).contains("ELSE email END");
        assertThat(boundSql).contains("update_time = CASE id");
        assertThat(boundSql).contains("update_by = CASE id");
        assertThat(boundSql).contains("WHERE id IN");
        assertThat(boundSql).contains("deleted=0");

        // 动态优化断言：全 null 字段（如 age）不进入 SET 语句
        assertThat(boundSql).doesNotContain("age = CASE id");

        // 安全红线断言：严禁包含主键更新、逻辑删除更新、以及仅插入列（create_time / create_by）
        assertThat(boundSql).doesNotContain("id = CASE");
        assertThat(boundSql).doesNotContain("create_time = CASE");
        assertThat(boundSql).doesNotContain("create_by = CASE");
        assertThat(boundSql).doesNotContain("deleted = CASE");
    }

    // ==================== 第五部分：H2 内存库端到端集成测试 ====================

    @Test
    @DisplayName("10. H2 端到端集成测试：乱序批量更新、字段选择性更新（null保护）、保持 create_time 不变、逻辑删除行隔离")
    void shouldEndToEndUpdateInH2() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_batch_update;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");

        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_user_batch_update (
                    id BIGINT PRIMARY KEY,
                    username VARCHAR(64) NOT NULL,
                    email VARCHAR(128) NOT NULL,
                    age INT,
                    create_time TIMESTAMP NOT NULL,
                    create_by VARCHAR(64) NOT NULL,
                    update_time TIMESTAMP,
                    update_by VARCHAR(64),
                    deleted INT DEFAULT 0
                )
                """);
            stmt.execute("DELETE FROM t_user_batch_update");

            // 初始化 3 条未删除记录与 1 条已逻辑删除记录 (deleted=1)
            stmt.execute("INSERT INTO t_user_batch_update (id, username, email, age, create_time, create_by, deleted) VALUES " +
                    "(1, 'Alice', 'alice@test.com', 20, '2020-01-01 10:00:00', 'admin_alice', 0)");
            stmt.execute("INSERT INTO t_user_batch_update (id, username, email, age, create_time, create_by, deleted) VALUES " +
                    "(2, 'Bob', 'bob@test.com', 25, '2020-01-01 10:00:00', 'admin_bob', 0)");
            stmt.execute("INSERT INTO t_user_batch_update (id, username, email, age, create_time, create_by, deleted) VALUES " +
                    "(3, 'Charlie', 'charlie@test.com', 30, '2020-01-01 10:00:00', 'admin_charlie', 0)");
            stmt.execute("INSERT INTO t_user_batch_update (id, username, email, age, create_time, create_by, deleted) VALUES " +
                    "(4, 'DeletedUser', 'deleted@test.com', 40, '2020-01-01 10:00:00', 'admin_del', 1)");
        }

        SqlSessionFactory sessionFactory = buildSqlSessionFactory(ds, UserUpdateMapper.class, UserUpdateEntity.class);

        try (SqlSession session = sessionFactory.openSession(true)) {
            UserUpdateMapper mapper = session.getMapper(UserUpdateMapper.class);
            UserContextHolder.set("editor_ateng");

            // 构造乱序（3, 1, 2）实体，且分别更新不同字段：
            // ID=1: 更新 username 与 age，email 留空为 null（必须被 ELSE email END 保护）
            UserUpdateEntity u1 = new UserUpdateEntity();
            u1.setId(1L);
            u1.setUsername("Alice_Updated");
            u1.setAge(21);
            u1.setEmail(null);

            // ID=2: 更新 email，username 与 age 留空为 null（必须被保护）
            UserUpdateEntity u2 = new UserUpdateEntity();
            u2.setId(2L);
            u2.setUsername(null);
            u2.setEmail("bob_new@test.com");
            u2.setAge(null);

            // ID=3: 全量更新所有字段
            UserUpdateEntity u3 = new UserUpdateEntity();
            u3.setId(3L);
            u3.setUsername("Charlie_Updated");
            u3.setEmail("charlie_new@test.com");
            u3.setAge(35);

            // 故意传入乱序列表 [u3, u1, u2] 测试防死锁与切片
            int affected = BatchUtil.updateBatch(mapper, List.of(u3, u1, u2));
            assertThat(affected).isEqualTo(3);

            // 验证 ID=1 记录
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM t_user_batch_update WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("username")).isEqualTo("Alice_Updated");
                assertThat(rs.getString("email")).isEqualTo("alice@test.com"); // 动态 ELSE 回退保护
                assertThat(rs.getInt("age")).isEqualTo(21);
                assertThat(rs.getString("create_by")).isEqualTo("admin_alice"); // 严格保留
                assertThat(rs.getTimestamp("create_time")).isEqualTo(Timestamp.valueOf("2020-01-01 10:00:00"));
                assertThat(rs.getString("update_by")).isEqualTo("editor_ateng"); // 自动自愈
                assertThat(rs.getTimestamp("update_time")).isNotNull();
            }

            // 验证 ID=2 记录
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM t_user_batch_update WHERE id = 2")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("username")).isEqualTo("Bob"); // 动态 ELSE 回退保护
                assertThat(rs.getString("email")).isEqualTo("bob_new@test.com");
                assertThat(rs.getInt("age")).isEqualTo(25); // 动态 ELSE 回退保护
                assertThat(rs.getString("create_by")).isEqualTo("admin_bob");
                assertThat(rs.getString("update_by")).isEqualTo("editor_ateng");
            }

            // 验证 ID=3 记录
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM t_user_batch_update WHERE id = 3")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("username")).isEqualTo("Charlie_Updated");
                assertThat(rs.getString("email")).isEqualTo("charlie_new@test.com");
                assertThat(rs.getInt("age")).isEqualTo(35);
                assertThat(rs.getString("create_by")).isEqualTo("admin_charlie");
                assertThat(rs.getString("update_by")).isEqualTo("editor_ateng");
            }

            // 验证已逻辑删除行（ID=4）不受影响
            UserUpdateEntity u4 = new UserUpdateEntity();
            u4.setId(4L);
            u4.setUsername("ShouldNotChange");
            int affectedDel = BatchUtil.updateBatch(mapper, List.of(u4));
            assertThat(affectedDel).isZero();

            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM t_user_batch_update WHERE id = 4")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("username")).isEqualTo("DeletedUser");
            }
        }
    }

    private SqlSessionFactory buildSqlSessionFactory(DataSource dataSource, Class<?> mapperClass, Class<?> modelClass) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        configuration.setEnvironment(environment);

        GlobalConfig globalConfig = GlobalConfigUtils.getGlobalConfig(configuration);
        globalConfig.setSqlInjector(new AtengSqlInjector());
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());

        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(mapperClass.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, modelClass);

        AtengSqlInjector injector = new AtengSqlInjector();
        injector.inspectInject(assistant, mapperClass);

        configuration.addMapper(mapperClass);

        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }
}
