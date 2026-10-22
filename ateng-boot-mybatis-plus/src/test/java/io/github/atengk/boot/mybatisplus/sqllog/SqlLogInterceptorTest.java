package io.github.atengk.boot.mybatisplus.sqllog;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.properties.SqlLogProperties;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.cursor.Cursor;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 日志打印与可执行带参诊断拦截器单元测试
 * 覆盖查询、更新、插入敏感脱敏、游标只读防卫、超长 SQL 截断及异常失败现场捕获
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("SqlLogInterceptor 日志拦截器与执行诊断单元测试")
class SqlLogInterceptorTest {

    @Getter
    @Setter
    @ToString
    @TableName("t_sqllog_test_user")
    public static class TestUserEntity {
        @TableId
        private Long id;
        private String username;
        private String password;
        @FieldEncrypt
        private String phone;
        private String email;
    }

    public interface TestUserMapper extends AtengBaseMapper<TestUserEntity> {
        @Select("SELECT id, username, password, phone, email FROM t_sqllog_test_user WHERE id = #{id}")
        TestUserEntity selectUserById(@Param("id") Long id);

        @Select("SELECT id, username, password, phone, email FROM t_sqllog_test_user")
        List<TestUserEntity> selectAllUsers();

        @Select("SELECT id, username, password, phone, email FROM t_sqllog_test_user")
        Cursor<TestUserEntity> selectCursorUsers();

        @Update("UPDATE t_sqllog_test_user SET username = #{username} WHERE id = #{id}")
        int updateUsername(@Param("id") Long id, @Param("username") String username);

        @Insert("INSERT INTO t_sqllog_test_user (id, username, password, phone, email) VALUES (#{id}, #{username}, #{password}, #{phone}, #{email})")
        int insertRawUser(TestUserEntity user);

        @Select("SELECT * FROM non_existent_table")
        List<TestUserEntity> queryInvalidTable();
    }

    private static DataSource dataSource;

    @BeforeAll
    static void initDatabase() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sqllog_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS t_sqllog_test_user (
                        id BIGINT PRIMARY KEY,
                        username VARCHAR(50),
                        password VARCHAR(100),
                        phone VARCHAR(200),
                        email VARCHAR(100)
                    );
                    """);
        }
    }

    @AfterAll
    static void destroyDatabase() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS t_sqllog_test_user;");
        }
    }

    @BeforeEach
    void resetTableData() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM t_sqllog_test_user;");
            stmt.execute("INSERT INTO t_sqllog_test_user (id, username, password, phone, email) VALUES (1, 'alice', 'alice_pwd', '13800000001', 'alice@example.com');");
            stmt.execute("INSERT INTO t_sqllog_test_user (id, username, password, phone, email) VALUES (2, 'bob', 'bob_pwd', '13800000002', 'bob@example.com');");
        }
    }

    private SqlSessionFactory createSessionFactory(SqlLogInterceptor interceptor) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        configuration.setEnvironment(environment);

        GlobalConfig globalConfig = GlobalConfigUtils.getGlobalConfig(configuration);
        globalConfig.setSqlInjector(new AtengSqlInjector());

        configuration.addMapper(TestUserMapper.class);
        configuration.addInterceptor(interceptor);

        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    @DisplayName("普通 SELECT 查询应输出完整的诊断看板并正确统计返回行数")
    void testQuerySuccessDashboard() {
        List<String> capturedLogs = new ArrayList<>();
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(properties, capturedLogs::add);
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            TestUserEntity user = mapper.selectUserById(1L);
            assertThat(user).isNotNull();
            assertThat(user.getUsername()).isEqualTo("alice");
        }

        assertThat(capturedLogs).hasSize(1);
        String log = capturedLogs.get(0);
        assertThat(log).contains("====== [MyBatis-Plus SQL 执行诊断] ======");
        assertThat(log).contains("MappedStatement : io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptorTest$TestUserMapper.selectUserById");
        assertThat(log).contains("实际执行耗时   :");
        assertThat(log).contains("执行结果影响   : 1 行");
        assertThat(log).contains("代码调用位置   :");
        assertThat(log).contains("WHERE id = 1");
    }

    @Test
    @DisplayName("UPDATE 操作应输出完整的诊断看板并正确统计影响行数")
    void testUpdateSuccessDashboard() {
        List<String> capturedLogs = new ArrayList<>();
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(properties, capturedLogs::add);
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            int affected = mapper.updateUsername(1L, "alice_updated");
            assertThat(affected).isEqualTo(1);
        }

        assertThat(capturedLogs).hasSize(1);
        String log = capturedLogs.get(0);
        assertThat(log).contains("====== [MyBatis-Plus SQL 执行诊断] ======");
        assertThat(log).contains("执行结果影响   : 1 行");
        assertThat(log).contains("UPDATE t_sqllog_test_user");
        assertThat(log).contains("SET username = 'alice_updated'");
    }

    @Test
    @DisplayName("INSERT 操作包含敏感属性时应自动执行脱敏反填为 '******'")
    void testInsertSensitiveMasking() {
        List<String> capturedLogs = new ArrayList<>();
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.setMaskSensitive(true);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(properties, capturedLogs::add);
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            TestUserEntity user = new TestUserEntity();
            user.setId(3L);
            user.setUsername("charlie");
            user.setPassword("secret_pwd_123");
            user.setPhone("13912345678");
            user.setEmail("charlie@example.com");

            int inserted = mapper.insertRawUser(user);
            assertThat(inserted).isEqualTo(1);
        }

        assertThat(capturedLogs).hasSize(1);
        String log = capturedLogs.get(0);
        assertThat(log).contains("====== [MyBatis-Plus SQL 执行诊断] ======");
        // password 和 @FieldEncrypt phone 必须反填为 '******'
        assertThat(log).contains("'******'");
        assertThat(log).doesNotContain("secret_pwd_123");
        assertThat(log).doesNotContain("13912345678");
        assertThat(log).contains("'charlie'");
        assertThat(log).contains("'charlie@example.com'");
    }

    @Test
    @DisplayName("关闭敏感脱敏 maskSensitive=false 时应输出真实明文")
    void testInsertMaskSensitiveDisabled() {
        List<String> capturedLogs = new ArrayList<>();
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.setMaskSensitive(false);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(properties, capturedLogs::add);
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            TestUserEntity user = new TestUserEntity();
            user.setId(4L);
            user.setUsername("david");
            user.setPassword("david_secret");
            user.setPhone("13988889999");
            user.setEmail("david@example.com");

            int inserted = mapper.insertRawUser(user);
            assertThat(inserted).isEqualTo(1);
        }

        assertThat(capturedLogs).hasSize(1);
        String log = capturedLogs.get(0);
        assertThat(log).contains("'david_secret'");
        assertThat(log).contains("'13988889999'");
    }

    @Test
    @DisplayName("超长 SQL 截断保护：超过 maxSqlLength 时应截断并输出长度与参数组数摘要")
    void testSqlTruncation() {
        List<String> capturedLogs = new ArrayList<>();
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.setMaxSqlLength(30);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(properties, capturedLogs::add);
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            mapper.selectUserById(1L);
        }

        assertThat(capturedLogs).hasSize(1);
        String log = capturedLogs.get(0);
        assertThat(log).contains("... [SQL 字符总长度");
        assertThat(log).contains("已截断显示前 30 字符");
        assertThat(log).contains("共包含 1 组参数]");
    }

    @Test
    @DisplayName("受管 Cursor 游标查询只读防卫：结果影响标注为 [Cursor 延迟游标流] 且不提前消耗游标")
    void testCursorReadOnlyDefense() throws Exception {
        List<String> capturedLogs = new ArrayList<>();
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(properties, capturedLogs::add);
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            try (Cursor<TestUserEntity> cursor = mapper.selectCursorUsers()) {
                assertThat(cursor).isNotNull();
                // 验证游标在此刻未被拦截器消费
                assertThat(cursor.isConsumed()).isFalse();

                List<TestUserEntity> list = new ArrayList<>();
                cursor.forEach(list::add);
                assertThat(list).hasSize(2);
            }
        }

        assertThat(capturedLogs).hasSize(1);
        String log = capturedLogs.get(0);
        assertThat(log).contains("执行结果影响   : [Cursor 延迟游标流]");
    }

    @Test
    @DisplayName("SQL 执行抛出异常时应输出失败现场看板并原样抛出原始异常")
    void testSqlExecutionFailureDiagnostics() {
        List<String> capturedLogs = new ArrayList<>();
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(properties, capturedLogs::add);
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            // 构造主键冲突异常（id=1 已在 @BeforeEach 中存在）
            TestUserEntity duplicateUser = new TestUserEntity();
            duplicateUser.setId(1L);
            duplicateUser.setUsername("duplicate_alice");
            duplicateUser.setPassword("pwd");
            duplicateUser.setPhone("13800000001");
            duplicateUser.setEmail("dup@example.com");

            assertThatThrownBy(() -> mapper.insertRawUser(duplicateUser))
                    .isInstanceOf(Exception.class);
        }

        assertThat(capturedLogs).hasSize(1);
        String log = capturedLogs.get(0);
        assertThat(log).contains("====== [MyBatis-Plus SQL 执行失败诊断] ======");
        assertThat(log).contains("MappedStatement : io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptorTest$TestUserMapper.insertRawUser");
        assertThat(log).contains("实际执行耗时   :");
        assertThat(log).contains("异常错误信息   :");
        assertThat(log).contains("INSERT INTO t_sqllog_test_user");
    }

    @Test
    @DisplayName("配置 showCallLocation=false 时诊断看板中应不输出代码调用位置")
    void testShowCallLocationDisabled() {
        List<String> capturedLogs = new ArrayList<>();
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.setShowCallLocation(false);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(properties, capturedLogs::add);
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            mapper.selectUserById(1L);
        }

        assertThat(capturedLogs).hasSize(1);
        String log = capturedLogs.get(0);
        assertThat(log).doesNotContain("代码调用位置");
    }

    @Test
    @DisplayName("拦截器全局关闭 enabled=false 时不执行任何拦截与日志打印")
    void testDisabledGlobalSwitch() {
        List<String> capturedLogs = new ArrayList<>();
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(false);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(properties, capturedLogs::add);
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            mapper.selectUserById(1L);
        }

        assertThat(capturedLogs).isEmpty();
    }

    @Test
    @DisplayName("batch 方法调用时应静默执行放行，不打印日志以避免传统批量刷屏")
    void testBatchMethodSuppressed() throws Throwable {
        List<String> capturedLogs = new ArrayList<>();
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(properties, capturedLogs::add);

        org.apache.ibatis.executor.statement.StatementHandler mockHandler = org.mockito.Mockito.mock(org.apache.ibatis.executor.statement.StatementHandler.class);

        java.lang.reflect.Method batchMethod = org.apache.ibatis.executor.statement.StatementHandler.class.getMethod("batch", Statement.class);
        org.apache.ibatis.plugin.Invocation invocation = new org.apache.ibatis.plugin.Invocation(mockHandler, batchMethod, new Object[]{null});

        Object result = interceptor.intercept(invocation);
        assertThat(result).isNull();
        assertThat(capturedLogs).isEmpty();
        org.mockito.Mockito.verify(mockHandler).batch(null);
    }
}
