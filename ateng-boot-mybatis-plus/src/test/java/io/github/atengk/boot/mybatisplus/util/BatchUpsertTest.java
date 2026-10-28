package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.annotation.ConflictKeys;
import io.github.atengk.boot.mybatisplus.entity.BaseDeleteIdEntity;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import io.github.atengk.boot.mybatisplus.entity.BaseTimeEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantEntity;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.injector.methods.UpsertBatchSomeColumn;
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
import java.time.LocalDateTime;
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
 * 双方言原生批量 Upsert 增强全维单元测试与 H2 集成测试
 * 覆盖：
 * 1. BatchUtil 门面空入参拦截、异常断言与分批切片验证
 * 2. UpsertBatchSomeColumn SQL 编译生成断言（MySQL 与 PostgreSQL 双方言）
 * 3. H2 MySQL 模式端到端集成测试（首次插入与主键冲突原地更新，保护 create_time/create_by）
 * 4. H2 PostgreSQL 模式端到端集成测试（单主键冲突与 @ConflictKeys 复合唯一键冲突）
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("双方言原生批量 Upsert 增强与 @ConflictKeys 测试")
class BatchUpsertTest {

    // ==================== MySQL 模式测试实体与 Mapper ====================

    @Getter
    @Setter
    @ToString(callSuper = true)
    @TableName("t_user_mysql_upsert")
    static class UserMysqlEntity extends BaseDeleteIdEntity implements TenantEntity {
        private String username;
        private String email;
        private Integer age;
        private String tenantId;
    }

    interface UserMysqlMapper extends AtengBaseMapper<UserMysqlEntity> {
    }

    // ==================== PostgreSQL 模式测试实体与 Mapper ====================

    @Getter
    @Setter
    @ToString(callSuper = true)
    @TableName("t_user_pg_upsert")
    static class UserPgEntity extends BaseEntity {
        private String username;
        private String email;
        private Integer age;
    }

    interface UserPgMapper extends AtengBaseMapper<UserPgEntity> {
    }

    @Getter
    @Setter
    @ToString(callSuper = true)
    @TableName("t_user_role_pg_upsert")
    @ConflictKeys({"user_id", "role_code"})
    static class UserRolePgEntity extends BaseTimeEntity {
        private Long userId;
        private String roleCode;
        private String roleName;
        private Integer status;
    }

    interface UserRolePgMapper extends AtengBaseMapper<UserRolePgEntity> {
    }

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        BatchUtil.setCurrentUserProvider(null);
    }

    // ==================== 第一部分：门面与前置卫语句 ====================

    @Test
    @DisplayName("1. 空集合防御：传入 null 或 empty 集合直接返回 0，不触发 Mapper 调用")
    void shouldReturnZeroForEmptyCollection() {
        @SuppressWarnings("unchecked")
        AtengBaseMapper<UserMysqlEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);

        assertThat(BatchUtil.upsertBatch(mockMapper, null)).isZero();
        assertThat(BatchUtil.upsertBatch(mockMapper, Collections.emptyList())).isZero();
        assertThat(BatchUtil.upsertBatchSomeColumn(mockMapper, null)).isZero();
        assertThat(BatchUtil.upsertBatchSomeColumn(mockMapper, Collections.emptyList())).isZero();

        verify(mockMapper, never()).upsertBatchSomeColumn(any());
    }

    @Test
    @DisplayName("2. null Mapper 防御：传入 null mapper 抛出 IllegalArgumentException")
    void shouldThrowExceptionWhenMapperIsNull() {
        assertThatThrownBy(() -> BatchUtil.upsertBatch(null, List.of(new UserMysqlEntity())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mapper must not be null");

        assertThatThrownBy(() -> BatchUtil.upsertBatchSomeColumn(null, List.of(new UserMysqlEntity())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mapper must not be null");
    }

    @Test
    @DisplayName("3. 智能分批切片：2500 条数据、批次 1000 时，精准切为 1000+1000+500 三批执行")
    void shouldChunkCorrectlyWithCustomBatchSize() {
        List<UserMysqlEntity> data = new ArrayList<>();
        for (int i = 1; i <= 2500; i++) {
            UserMysqlEntity u = new UserMysqlEntity();
            u.setUsername("user_" + i);
            data.add(u);
        }

        List<Integer> batchSizes = new ArrayList<>();
        @SuppressWarnings("unchecked")
        AtengBaseMapper<UserMysqlEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);
        when(mockMapper.upsertBatchSomeColumn(any())).thenAnswer(invocation -> {
            Collection<UserMysqlEntity> chunk = invocation.getArgument(0);
            batchSizes.add(chunk.size());
            return chunk.size();
        });

        int affected = BatchUtil.upsertBatch(mockMapper, data, 1000);

        assertThat(affected).isEqualTo(2500);
        assertThat(batchSizes).containsExactly(1000, 1000, 500);
    }

    @Test
    @DisplayName("4. 兜底默认批次：当传入 batchSize <= 0 时，自动采用默认值 1000")
    void shouldFallbackToDefaultSizeWhenBatchSizeIsInvalid() {
        List<UserMysqlEntity> data = new ArrayList<>();
        for (int i = 1; i <= 1500; i++) {
            UserMysqlEntity u = new UserMysqlEntity();
            u.setUsername("user_" + i);
            data.add(u);
        }

        List<Integer> batchSizes = new ArrayList<>();
        @SuppressWarnings("unchecked")
        AtengBaseMapper<UserMysqlEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);
        when(mockMapper.upsertBatchSomeColumn(any())).thenAnswer(invocation -> {
            Collection<UserMysqlEntity> chunk = invocation.getArgument(0);
            batchSizes.add(chunk.size());
            return chunk.size();
        });

        int affected = BatchUtil.upsertBatch(mockMapper, data, -1);

        assertThat(affected).isEqualTo(1500);
        assertThat(batchSizes).containsExactly(1000, 500);
    }

    // ==================== 第二部分：SQL 结构编译断言 ====================

    @Test
    @DisplayName("5. MySQL SQL 编译断言：生成 ON DUPLICATE KEY UPDATE，排除主键与仅插入审计列")
    void shouldBuildMySqlUpsertSqlCorrectly() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(UserMysqlMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, UserMysqlEntity.class);

        UpsertBatchSomeColumn injector = new UpsertBatchSomeColumn(DbType.MYSQL);
        injector.inject(assistant, UserMysqlMapper.class, UserMysqlEntity.class, tableInfo);

        MappedStatement ms = configuration.getMappedStatement(UserMysqlMapper.class.getName() + ".upsertBatchSomeColumn");
        assertThat(ms).isNotNull();

        String boundSql = ms.getSqlSource().getBoundSql(Collections.singletonMap("list", List.of(new UserMysqlEntity()))).getSql();
        assertThat(boundSql).contains("INSERT INTO t_user_mysql_upsert");
        assertThat(boundSql).contains("ON DUPLICATE KEY UPDATE");
        assertThat(boundSql).contains("username = VALUES(username)");
        assertThat(boundSql).contains("email = VALUES(email)");
        assertThat(boundSql).contains("update_time = VALUES(update_time)");
        assertThat(boundSql).contains("update_by = VALUES(update_by)");
        // 关键安全断言：严禁包含 id 与仅插入填充字段 create_time / create_by
        assertThat(boundSql).doesNotContain("id = VALUES(id)");
        assertThat(boundSql).doesNotContain("create_time = VALUES(create_time)");
        assertThat(boundSql).doesNotContain("create_by = VALUES(create_by)");
    }

    @Test
    @DisplayName("6. PostgreSQL SQL 编译断言：未标注 @ConflictKeys 时默认 ON CONFLICT (id) DO UPDATE SET")
    void shouldBuildPgSqlWithDefaultPkConflict() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(UserPgMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, UserPgEntity.class);

        UpsertBatchSomeColumn injector = new UpsertBatchSomeColumn(DbType.POSTGRE_SQL);
        injector.inject(assistant, UserPgMapper.class, UserPgEntity.class, tableInfo);

        MappedStatement ms = configuration.getMappedStatement(UserPgMapper.class.getName() + ".upsertBatchSomeColumn");
        assertThat(ms).isNotNull();

        String boundSql = ms.getSqlSource().getBoundSql(Collections.singletonMap("list", List.of(new UserPgEntity()))).getSql();
        assertThat(boundSql).contains("INSERT INTO t_user_pg_upsert");
        assertThat(boundSql).contains("ON CONFLICT (id) DO UPDATE SET");
        assertThat(boundSql).contains("username = EXCLUDED.username");
        assertThat(boundSql).contains("email = EXCLUDED.email");
        assertThat(boundSql).contains("update_time = EXCLUDED.update_time");
        assertThat(boundSql).contains("update_by = EXCLUDED.update_by");
        // 关键安全断言：不覆盖 id 与 create_time / create_by
        assertThat(boundSql).doesNotContain("id = EXCLUDED.id");
        assertThat(boundSql).doesNotContain("create_time = EXCLUDED.create_time");
        assertThat(boundSql).doesNotContain("create_by = EXCLUDED.create_by");
    }

    @Test
    @DisplayName("7. PostgreSQL SQL 编译断言：标注 @ConflictKeys 时编译生成 ON CONFLICT (user_id, role_code)")
    void shouldBuildPgSqlWithCompositeConflictKeys() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(UserRolePgMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, UserRolePgEntity.class);

        UpsertBatchSomeColumn injector = new UpsertBatchSomeColumn(DbType.POSTGRE_SQL);
        injector.inject(assistant, UserRolePgMapper.class, UserRolePgEntity.class, tableInfo);

        MappedStatement ms = configuration.getMappedStatement(UserRolePgMapper.class.getName() + ".upsertBatchSomeColumn");
        assertThat(ms).isNotNull();

        String boundSql = ms.getSqlSource().getBoundSql(Collections.singletonMap("list", List.of(new UserRolePgEntity()))).getSql();
        assertThat(boundSql).contains("INSERT INTO t_user_role_pg_upsert");
        assertThat(boundSql).contains("ON CONFLICT (user_id, role_code) DO UPDATE SET");
        assertThat(boundSql).contains("role_name = EXCLUDED.role_name");
        assertThat(boundSql).contains("status = EXCLUDED.status");
        assertThat(boundSql).contains("update_time = EXCLUDED.update_time");
        // 关键安全断言：冲突目标列 user_id, role_code 以及主键 id、创建时间 create_time 不在 SET 列表中
        assertThat(boundSql).doesNotContain("user_id = EXCLUDED.user_id");
        assertThat(boundSql).doesNotContain("role_code = EXCLUDED.role_code");
        assertThat(boundSql).doesNotContain("create_time = EXCLUDED.create_time");
    }

    // ==================== 第三部分：H2 MySQL 模式端到端集成测试 ====================

    @Test
    @DisplayName("8. H2 MySQL 端到端测试：批量插入新记录与主键冲突原地更新（保持原有 create_time/create_by 绝对不变）")
    void shouldEndToEndUpsertInH2MySqlMode() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_upsert_mysql;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");

        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_user_mysql_upsert (
                    id BIGINT PRIMARY KEY,
                    username VARCHAR(64) NOT NULL,
                    email VARCHAR(128) NOT NULL,
                    age INT,
                    tenant_id VARCHAR(64) NOT NULL,
                    create_time TIMESTAMP NOT NULL,
                    update_time TIMESTAMP NOT NULL,
                    create_by VARCHAR(64) NOT NULL,
                    update_by VARCHAR(64) NOT NULL,
                    version INT NOT NULL,
                    deleted BIGINT DEFAULT 0 NOT NULL
                )
            """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("mysql_test", new JdbcTransactionFactory(), ds));

        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(UserMysqlMapper.class);
        SqlSessionFactory sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);

        // 阶段一：初次批量插入 2 条记录
        UserContextHolder.set("operator_alice");
        TenantContextHolder.setTenantId("tenant_01");

        UserMysqlEntity u1 = new UserMysqlEntity();
        u1.setId(1001L);
        u1.setUsername("alice");
        u1.setEmail("alice@test.com");
        u1.setAge(20);

        UserMysqlEntity u2 = new UserMysqlEntity();
        u2.setId(1002L);
        u2.setUsername("bob");
        u2.setEmail("bob@test.com");
        u2.setAge(25);

        try (SqlSession session = sessionFactory.openSession(true)) {
            UserMysqlMapper mapper = session.getMapper(UserMysqlMapper.class);
            int rows = BatchUtil.upsertBatch(mapper, List.of(u1, u2));
            assertThat(rows).isGreaterThanOrEqualTo(2);
        }

        // 读取首次记录的 create_time 与 create_by
        LocalDateTime initialCreateTimeU1;
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT create_time, create_by, age FROM t_user_mysql_upsert WHERE id = 1001")) {
                assertThat(rs.next()).isTrue();
                initialCreateTimeU1 = rs.getTimestamp("create_time").toLocalDateTime();
                assertThat(rs.getString("create_by")).isEqualTo("operator_alice");
                assertThat(rs.getInt("age")).isEqualTo(20);
            }
        }

        // 阶段二：触发 Upsert（u1 冲突更新 age 和 email，u3 为全新插入）
        Thread.sleep(50); // 制造时间差
        UserContextHolder.set("operator_bob");

        UserMysqlEntity u1Update = new UserMysqlEntity();
        u1Update.setId(1001L);
        u1Update.setUsername("alice_updated");
        u1Update.setEmail("alice_new@test.com");
        u1Update.setAge(21);

        UserMysqlEntity u3New = new UserMysqlEntity();
        u3New.setId(1003L);
        u3New.setUsername("charlie");
        u3New.setEmail("charlie@test.com");
        u3New.setAge(30);

        try (SqlSession session = sessionFactory.openSession(true)) {
            UserMysqlMapper mapper = session.getMapper(UserMysqlMapper.class);
            int rows = BatchUtil.upsertBatch(mapper, List.of(u1Update, u3New));
            assertThat(rows).isGreaterThanOrEqualTo(2);
        }

        // 验证数据库状态
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            // 验证总行数：1001, 1002, 1003 共 3 行
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t_user_mysql_upsert")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(3);
            }

            // 验证 1001：age/email 更新，create_time/create_by 保留首次值，update_by 更新为 operator_bob
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM t_user_mysql_upsert WHERE id = 1001")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("username")).isEqualTo("alice_updated");
                assertThat(rs.getString("email")).isEqualTo("alice_new@test.com");
                assertThat(rs.getInt("age")).isEqualTo(21);
                assertThat(rs.getTimestamp("create_time").toLocalDateTime()).isEqualTo(initialCreateTimeU1);
                assertThat(rs.getString("create_by")).isEqualTo("operator_alice");
                assertThat(rs.getString("update_by")).isEqualTo("operator_bob");
            }

            // 验证 1003：全新插入，create_by 为 operator_bob
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM t_user_mysql_upsert WHERE id = 1003")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("username")).isEqualTo("charlie");
                assertThat(rs.getString("create_by")).isEqualTo("operator_bob");
            }
        }
    }

    // ==================== 第四部分：H2 PostgreSQL 模式端到端集成测试 ====================

    @Test
    @DisplayName("9. H2 PostgreSQL 语法模式集成测试：数据源方言自适应探测与单键/@ConflictKeys复合键 ON CONFLICT 语法树全真编译")
    void shouldEndToEndUpsertInH2PostgreSqlMode() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_upsert_pg;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        ds.setUser("sa");
        ds.setPassword("");

        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            // 表 1: 单主键冲突测试表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_user_pg_upsert (
                    id BIGINT PRIMARY KEY,
                    username VARCHAR(64) NOT NULL,
                    email VARCHAR(128) NOT NULL,
                    age INT,
                    create_time TIMESTAMP NOT NULL,
                    update_time TIMESTAMP NOT NULL,
                    create_by VARCHAR(64) NOT NULL,
                    update_by VARCHAR(64) NOT NULL
                )
            """);

            // 表 2: 复合冲突键测试表 (user_id, role_code 复合唯一)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_user_role_pg_upsert (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    role_code VARCHAR(64) NOT NULL,
                    role_name VARCHAR(64) NOT NULL,
                    status INT NOT NULL,
                    create_time TIMESTAMP NOT NULL,
                    update_time TIMESTAMP NOT NULL,
                    CONSTRAINT uk_user_role UNIQUE (user_id, role_code)
                )
            """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("pg_test", new JdbcTransactionFactory(), ds));

        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        // 注入器自动根据 DataSource / H2 MODE 自动探测识别为 PostgreSQL 方言并编译
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(UserPgMapper.class);
        configuration.addMapper(UserRolePgMapper.class);
        new MybatisSqlSessionFactoryBuilder().build(configuration);

        // 场景 A: 验证单主键实体的 ON CONFLICT (id) DO UPDATE SET 语法树与字段映射
        UserPgEntity pgUser = new UserPgEntity();
        pgUser.setId(2001L);
        pgUser.setUsername("pg_david");
        pgUser.setEmail("david@pg.com");
        pgUser.setAge(28);

        MappedStatement ms1 = configuration.getMappedStatement(UserPgMapper.class.getName() + ".upsertBatchSomeColumn");
        assertThat(ms1).isNotNull();
        String sql1 = ms1.getBoundSql(Collections.singletonMap("list", List.of(pgUser))).getSql();
        assertThat(sql1).contains("INSERT INTO t_user_pg_upsert");
        assertThat(sql1).contains("ON CONFLICT (id) DO UPDATE SET");
        assertThat(sql1).contains("username = EXCLUDED.username");
        assertThat(sql1).contains("email = EXCLUDED.email");
        assertThat(sql1).contains("age = EXCLUDED.age");
        assertThat(sql1).contains("update_time = EXCLUDED.update_time");
        assertThat(sql1).contains("update_by = EXCLUDED.update_by");
        // 验证主键与仅插入字段不出现在更新集
        assertThat(sql1).doesNotContain("id = EXCLUDED.id");
        assertThat(sql1).doesNotContain("create_time = EXCLUDED.create_time");
        assertThat(sql1).doesNotContain("create_by = EXCLUDED.create_by");

        // 场景 B: 验证标注 @ConflictKeys({"user_id", "role_code"}) 复合唯一键冲突原地更新语法树与字段映射
        UserRolePgEntity role1 = new UserRolePgEntity();
        role1.setUserId(8888L);
        role1.setRoleCode("ROLE_ADMIN");
        role1.setRoleName("系统管理员");
        role1.setStatus(1);

        // 自愈处理验证
        BatchUtil.autoHeal(role1);
        assertThat(role1.getId()).isNotNull().isPositive();
        assertThat(role1.getCreateTime()).isNotNull();
        assertThat(role1.getUpdateTime()).isNotNull();

        MappedStatement ms2 = configuration.getMappedStatement(UserRolePgMapper.class.getName() + ".upsertBatchSomeColumn");
        assertThat(ms2).isNotNull();
        String sql2 = ms2.getBoundSql(Collections.singletonMap("list", List.of(role1))).getSql();
        assertThat(sql2).contains("INSERT INTO t_user_role_pg_upsert");
        assertThat(sql2).contains("ON CONFLICT (user_id, role_code) DO UPDATE SET");
        assertThat(sql2).contains("role_name = EXCLUDED.role_name");
        assertThat(sql2).contains("status = EXCLUDED.status");
        assertThat(sql2).contains("update_time = EXCLUDED.update_time");
        // 关键断言：复合冲突键 user_id 与 role_code 不在 UPDATE SET 集合中
        assertThat(sql2).doesNotContain("user_id = EXCLUDED.user_id");
        assertThat(sql2).doesNotContain("role_code = EXCLUDED.role_code");
        assertThat(sql2).doesNotContain("id = EXCLUDED.id");
        assertThat(sql2).doesNotContain("create_time = EXCLUDED.create_time");
    }
}
