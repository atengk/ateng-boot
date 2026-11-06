package io.github.atengk.boot.mybatisplus.sqllog;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.atengk.boot.core.audit.SqlLogModel;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.enums.SqlTypeEnum;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.annotation.IgnoreSqlLog;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

        @Select("SELECT CAST(username AS BIGINT) FROM t_sqllog_test_user WHERE id = #{id}")
        Long selectFailedSql(@Param("id") Long id);

        @IgnoreSqlLog("测试忽略查询")
        @Select("SELECT id, username FROM t_sqllog_test_user WHERE id = #{id}")
        TestUserEntity selectIgnoredUser(@Param("id") Long id);
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

    @AfterEach
    void cleanupContext() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
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

    @Test
    @DisplayName("SPI 结构化持久化管道：DML 写操作自动派发存储并发布 Spring SqlLogEvent 事件")
    void testStorageDmlDispatchAndSpringEvent() {
        List<String> capturedLogs = new ArrayList<>();
        List<SqlLogModel> storedModels = new ArrayList<>();
        List<SqlLogEvent> publishedEvents = new ArrayList<>();

        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.getStorage().setEnabled(true);
        properties.getStorage().setAsync(false);
        properties.getStorage().setIncludeSelect(false);

        SqlLogStorageProvider storageProvider = storedModels::add;
        org.springframework.context.ApplicationEventPublisher publisher = event -> {
            if (event instanceof SqlLogEvent sqlLogEvent) {
                publishedEvents.add(sqlLogEvent);
            }
        };

        SqlLogInterceptor interceptor = new SqlLogInterceptor(
                properties,
                capturedLogs::add,
                storageProvider,
                null,
                publisher,
                null
        );
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession(true)) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);

            // 1. 执行 INSERT 写操作
            TestUserEntity user = new TestUserEntity();
            user.setId(100L);
            user.setUsername("david");
            user.setPassword("pwd123");
            user.setPhone("13900000000");
            user.setEmail("david@test.com");
            int rows = mapper.insertRawUser(user);
            assertThat(rows).isEqualTo(1);

            // 2. 执行 SELECT 只读查询（includeSelect=false，不应进入存储）
            TestUserEntity queried = mapper.selectUserById(100L);
            assertThat(queried).isNotNull();

            // 3. 执行 UPDATE 写操作
            int updated = mapper.updateUsername(100L, "david_new");
            assertThat(updated).isEqualTo(1);
        }

        // 验证控制台打印了 3 条
        assertThat(capturedLogs).hasSize(3);

        // 验证存储 SPI 仅接收了 2 条 DML（INSERT 与 UPDATE），SELECT 被自动忽略
        assertThat(storedModels).hasSize(2);
        assertThat(publishedEvents).hasSize(2);

        // 验证 INSERT 模型
        SqlLogModel insertModel = storedModels.get(0);
        assertThat(insertModel.getSqlType()).isEqualTo(SqlTypeEnum.INSERT);
        assertThat(insertModel.getStatementId()).contains("insertRawUser");
        assertThat(insertModel.getStatus()).isEqualTo(0);
        assertThat(insertModel.isSuccess()).isTrue();
        assertThat(insertModel.getAffectedRows()).isEqualTo(1L);
        assertThat(insertModel.getCostTime()).isNotNull();
        assertThat(insertModel.getDatasource()).isNotBlank();
        assertThat(insertModel.getSql()).contains("INSERT INTO");

        // 验证 UPDATE 模型
        SqlLogModel updateModel = storedModels.get(1);
        assertThat(updateModel.getSqlType()).isEqualTo(SqlTypeEnum.UPDATE);
        assertThat(updateModel.getStatementId()).contains("updateUsername");
        assertThat(updateModel.getStatus()).isEqualTo(0);
        assertThat(updateModel.isSuccess()).isTrue();
        assertThat(updateModel.getAffectedRows()).isEqualTo(1L);
        assertThat(updateModel.getSql()).contains("UPDATE");

        // 验证 Spring 事件与模型对称性
        assertThat(publishedEvents.get(0).getLogModel()).isSameAs(insertModel);
        assertThat(publishedEvents.get(1).getLogModel()).isSameAs(updateModel);
    }

    @Test
    @DisplayName("SPI 结构化持久化管道：includeSelect=true 时放行只读查询进入存储")
    void testStorageIncludeSelectTrue() {
        List<SqlLogModel> storedModels = new ArrayList<>();
        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.setConsoleEnabled(false); // 测试静默控制台，仅存 SPI
        properties.getStorage().setEnabled(true);
        properties.getStorage().setAsync(false);
        properties.getStorage().setIncludeSelect(true);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(
                properties,
                null,
                storedModels::add,
                null,
                null,
                null
        );
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            mapper.selectUserById(1L);
        }

        assertThat(storedModels).hasSize(1);
        SqlLogModel selectModel = storedModels.get(0);
        assertThat(selectModel.getSqlType()).isEqualTo(SqlTypeEnum.SELECT);
        assertThat(selectModel.getStatus()).isEqualTo(0);
        assertThat(selectModel.getAffectedRows()).isEqualTo(1L);
        assertThat(selectModel.getStatementId()).contains("selectUserById");
    }

    @Test
    @DisplayName("细粒度防卫：@IgnoreSqlLog 注解声明式跳过控制台打印与 SPI 存储")
    void testIgnoreSqlLogAnnotation() {
        List<String> capturedLogs = new ArrayList<>();
        List<SqlLogModel> storedModels = new ArrayList<>();

        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.getStorage().setEnabled(true);
        properties.getStorage().setAsync(false);
        properties.getStorage().setIncludeSelect(true);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(
                properties,
                capturedLogs::add,
                storedModels::add,
                null,
                null,
                null
        );
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            // 调用标注了 @IgnoreSqlLog 的方法
            TestUserEntity user = mapper.selectIgnoredUser(1L);
            assertThat(user).isNotNull();
        }

        // 控制台与存储双管道均应完全跳过
        assertThat(capturedLogs).isEmpty();
        assertThat(storedModels).isEmpty();
    }

    @Test
    @DisplayName("细粒度防卫：excludeStatements 通配排除全局特定 MappedStatement")
    void testExcludeStatements() {
        List<String> capturedLogs = new ArrayList<>();
        List<SqlLogModel> storedModels = new ArrayList<>();

        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.setExcludeStatements(List.of("*.updateUsername"));
        properties.getStorage().setEnabled(true);
        properties.getStorage().setAsync(false);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(
                properties,
                capturedLogs::add,
                storedModels::add,
                null,
                null,
                null
        );
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession(true)) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            mapper.updateUsername(1L, "new_name");
        }

        assertThat(capturedLogs).isEmpty();
        assertThat(storedModels).isEmpty();
    }

    @Test
    @DisplayName("失败现场采集：SQL 执行异常时捕获 status=1 与错误堆栈，派发 SPI 后重抛原始异常")
    void testFailureSiteCapturedInStorage() {
        List<SqlLogModel> storedModels = new ArrayList<>();

        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.getStorage().setEnabled(true);
        properties.getStorage().setAsync(false);
        properties.getStorage().setIncludeSelect(true);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(
                properties,
                null,
                storedModels::add,
                null,
                null,
                null
        );
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

        // 存储提供者应全真捕获失败现场
        assertThat(storedModels).hasSize(1);
        SqlLogModel failModel = storedModels.get(0);
        assertThat(failModel.getStatus()).isEqualTo(1);
        assertThat(failModel.isSuccess()).isFalse();
        assertThat(failModel.getErrorMsg()).isNotBlank();
        assertThat(failModel.getStatementId()).contains("insertRawUser");
    }

    @Test
    @DisplayName("保底验证：DefaultSqlLogStorageProvider 在无外部实现时基于 SLF4J 安全输出 JSON")
    void testDefaultSqlLogStorageProvider() {
        DefaultSqlLogStorageProvider defaultProvider = new DefaultSqlLogStorageProvider();
        SqlLogModel model = SqlLogModel.builder()
                .traceId("trace-test")
                .statementId("TestMapper.select")
                .sql("SELECT 1")
                .sqlType(SqlTypeEnum.SELECT)
                .status(0)
                .build();

        // 验证空安全与常规调用均不抛出任何异常
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            defaultProvider.store(null);
            defaultProvider.store(model);
        });
    }

    @Test
    @DisplayName("失败现场采集：当 include-select=false 时，执行失败的 SELECT 语句仍必须全真记录审计")
    void testFailedSelectCapturedInStorageEvenWhenIncludeSelectFalse() {
        List<SqlLogModel> storedModels = new ArrayList<>();

        SqlLogProperties properties = new SqlLogProperties();
        properties.setEnabled(true);
        properties.getStorage().setEnabled(true);
        properties.getStorage().setAsync(false);
        // 关键防护：关闭 SELECT 审计
        properties.getStorage().setIncludeSelect(false);

        SqlLogInterceptor interceptor = new SqlLogInterceptor(
                properties,
                null,
                storedModels::add,
                null,
                null
        );
        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);

            // 1. 正常的 SELECT 查询应被 include-select=false 过滤跳过
            TestUserEntity user = mapper.selectUserById(1L);
            assertThat(user).isNotNull();
            assertThat(storedModels).isEmpty();

            // 2. 失败的 SELECT 查询必须 100% 采集现场，不受 includeSelect 过滤
            assertThatThrownBy(() -> mapper.selectFailedSql(1L))
                    .isInstanceOf(Exception.class);
        }

        // 验证失败现场已被审计采集
        assertThat(storedModels).hasSize(1);
        SqlLogModel failModel = storedModels.get(0);
        assertThat(failModel.getSqlType()).isEqualTo(SqlTypeEnum.SELECT);
        assertThat(failModel.getStatus()).isEqualTo(1);
        assertThat(failModel.isSuccess()).isFalse();
        assertThat(failModel.getErrorMsg()).isNotBlank();
        assertThat(failModel.getStatementId()).contains("selectFailedSql");
    }

    @Test
    @DisplayName("异步调度与上下文透传：异步线程池调度时通过 AsyncContext 完整透传四维快照")
    void testStorageAsyncDispatchWithContextSnapshot() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> asyncTraceId = new AtomicReference<>();
        AtomicReference<String> asyncTenantId = new AtomicReference<>();
        AtomicReference<String> asyncUserId = new AtomicReference<>();
        AtomicReference<Thread> asyncThread = new AtomicReference<>();
        List<SqlLogModel> storedModels = new CopyOnWriteArrayList<>();

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            SqlLogProperties properties = new SqlLogProperties();
            properties.setEnabled(true);
            properties.getStorage().setEnabled(true);
            properties.getStorage().setAsync(true);
            properties.getStorage().setIncludeSelect(true);

            SqlLogStorageProvider customStorage = model -> {
                storedModels.add(model);
                asyncThread.set(Thread.currentThread());
                asyncTraceId.set(MDC.get("traceId"));
                asyncTenantId.set(TenantContextHolder.getTenantId());
                asyncUserId.set(UserContextHolder.getUserId());
                latch.countDown();
            };

            ObjectProvider<Executor> executorProvider = new ObjectProvider<>() {
                @Override
                public Executor getObject(Object... args) {
                    return executorService;
                }

                @Override
                public Executor getIfAvailable() {
                    return executorService;
                }

                @Override
                public Executor getIfUnique() {
                    return executorService;
                }

                @Override
                public Executor getObject() {
                    return executorService;
                }
            };

            SqlLogInterceptor interceptor = new SqlLogInterceptor(
                    properties,
                    null,
                    customStorage,
                    executorProvider,
                    null
            );
            SqlSessionFactory sessionFactory = createSessionFactory(interceptor);

            // 主线程注入四维上下文
            MDC.put("traceId", "async-trace-001");
            TenantContextHolder.setTenantId("async-tenant-999");
            UserContextHolder.setUserId("async-user-888");

            try (SqlSession sqlSession = sessionFactory.openSession()) {
                TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
                mapper.selectUserById(1L);
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // 验证在不同的异步工作线程中执行
            assertThat(asyncThread.get()).isNotEqualTo(Thread.currentThread());
            // 验证四维上下文快照被 AsyncContext 完整还原
            assertThat(asyncTraceId.get()).isEqualTo("async-trace-001");
            assertThat(asyncTenantId.get()).isEqualTo("async-tenant-999");
            assertThat(asyncUserId.get()).isEqualTo("async-user-888");

            assertThat(storedModels).hasSize(1);
            assertThat(storedModels.get(0).getTraceId()).isEqualTo("async-trace-001");
            assertThat(storedModels.get(0).getTenantId()).isEqualTo("async-tenant-999");
            assertThat(storedModels.get(0).getUserId()).isEqualTo("async-user-888");
        } finally {
            executorService.shutdownNow();
        }
    }
}
