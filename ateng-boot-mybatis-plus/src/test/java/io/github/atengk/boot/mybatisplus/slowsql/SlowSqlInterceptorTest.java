package io.github.atengk.boot.mybatisplus.slowsql;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 慢 SQL 性能审计与告警拦截器单元测试
 * 覆盖正常放行、超阈值告警触发、SPI 与 Spring 事件通知、参数脱敏（@FieldEncrypt 与密码凭据）、
 * SQL 多行缩进美化、调用栈定位及异常容错弹性防御
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("慢 SQL 性能审计与告警拦截器单元测试")
class SlowSqlInterceptorTest {

    @Getter
    @Setter
    @ToString
    @TableName("t_slow_test_user")
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
        @Select("SELECT id, username, password, phone, email FROM t_slow_test_user WHERE id = #{id}")
        TestUserEntity selectUserById(@Param("id") Long id);

        @Select("SELECT count(1) FROM t_slow_test_user")
        long countAll();

        @Select("SELECT id, username FROM t_slow_test_user WHERE username = #{username} AND password = #{pwd}")
        TestUserEntity login(@Param("username") String username, @Param("pwd") String pwd);
    }

    private static DataSource dataSource;

    @BeforeAll
    static void initH2Database() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_slow_sql;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_slow_test_user (
                    id BIGINT PRIMARY KEY,
                    username VARCHAR(64) NOT NULL,
                    password VARCHAR(128) NOT NULL,
                    phone VARCHAR(128),
                    email VARCHAR(128)
                )
            """);
        }
    }

    @AfterAll
    static void destroyH2Database() throws Exception {
        if (dataSource != null) {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS t_slow_test_user");
            }
        }
    }

    @BeforeEach
    void cleanTable() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM t_slow_test_user");
        }
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @AfterEach
    void cleanThreadContext() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    private SqlSessionFactory createSessionFactory(SlowSqlInterceptor interceptor) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));

        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addInterceptor(interceptor);
        configuration.addMapper(TestUserMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    @DisplayName("未超时 SQL 快速放行：零告警触发且零额外事件发布")
    void shouldPassThroughWhenExecutionTimeWithinThreshold() {
        List<SlowSqlEvent> capturedAlerts = new ArrayList<>();
        List<SlowSqlEvent> capturedEvents = new ArrayList<>();

        SlowSqlAlertProvider alertProvider = capturedAlerts::add;
        ApplicationEventPublisher eventPublisher = event -> {
            if (event instanceof SlowSqlEvent slowSqlEvent) {
                capturedEvents.add(slowSqlEvent);
            }
        };

        // 阈值设置为 10 秒，普通查询必定不超时
        SlowSqlInterceptor interceptor = new SlowSqlInterceptor(
                Duration.ofSeconds(10),
                true,
                true,
                alertProvider,
                eventPublisher
        );

        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            long count = mapper.countAll();
            assertThat(count).isZero();
        }

        // 验证未产生任何告警或事件
        assertThat(capturedAlerts).isEmpty();
        assertThat(capturedEvents).isEmpty();
    }

    @Test
    @DisplayName("超时慢 SQL 触发告警：SPI 通知与 Spring 事件发布成功，元数据完整")
    void shouldTriggerAlertAndPublishEventWhenExceedingThreshold() {
        List<SlowSqlEvent> capturedAlerts = new ArrayList<>();
        List<SlowSqlEvent> capturedEvents = new ArrayList<>();

        SlowSqlAlertProvider alertProvider = capturedAlerts::add;
        ApplicationEventPublisher eventPublisher = event -> {
            if (event instanceof SlowSqlEvent slowSqlEvent) {
                capturedEvents.add(slowSqlEvent);
            }
        };

        // 阈值设置为 0 纳秒，任何 SQL 均视为慢 SQL
        SlowSqlInterceptor interceptor = new SlowSqlInterceptor(
                Duration.ofNanos(0),
                true,
                true,
                alertProvider,
                eventPublisher
        );

        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);

            TestUserEntity user = new TestUserEntity();
            user.setId(1001L);
            user.setUsername("ateng");
            user.setPassword("secret123456");
            user.setPhone("13800138000");
            user.setEmail("ateng@example.com");
            mapper.insert(user);
        }

        assertThat(capturedAlerts).isNotEmpty();
        assertThat(capturedEvents).isNotEmpty();

        SlowSqlEvent event = capturedAlerts.get(0);
        assertThat(event.getStatementId()).contains("TestUserMapper.insert");
        assertThat(event.getSql()).contains("INSERT INTO");
        assertThat(event.getExecutionTime()).isNotNull();
        assertThat(event.getThreshold()).isEqualTo(Duration.ZERO);
        assertThat(event.getTriggerTime()).isNotNull();
        assertThat(event.getThreadName()).isEqualTo(Thread.currentThread().getName());
        assertThat(event.getCallLocation()).isNotEmpty().isNotEqualTo("UNKNOWN");

        // 验证 Spring 事件与 SPI 接收到相同事件
        assertThat(capturedEvents.get(0)).isSameAs(event);
    }

    @Test
    @DisplayName("敏感数据防线：@FieldEncrypt 字段与密码凭据关键字被掩码为 ******")
    void shouldMaskFieldEncryptAndSensitiveKeywords() {
        List<SlowSqlEvent> capturedAlerts = new ArrayList<>();
        SlowSqlAlertProvider alertProvider = capturedAlerts::add;

        SlowSqlInterceptor interceptor = new SlowSqlInterceptor(
                Duration.ofNanos(0),
                true,
                true, // 开启脱敏
                alertProvider,
                null
        );

        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);

            // 1. 测试实体插入脱敏：password 字段与 @FieldEncrypt phone 字段
            TestUserEntity user = new TestUserEntity();
            user.setId(2001L);
            user.setUsername("developer");
            user.setPassword("my_super_password");
            user.setPhone("13999998888");
            user.setEmail("dev@company.com");
            mapper.insert(user);

            SlowSqlEvent insertEvent = capturedAlerts.get(0);
            List<Object> insertParams = insertEvent.getParameters();
            // 验证密码字段与带注解的 phone 字段被脱敏为 ******
            assertThat(insertParams).contains("******");
            assertThat(insertParams).doesNotContain("my_super_password");
            assertThat(insertParams).doesNotContain("13999998888");
            // 验证普通字段保持原样
            assertThat(insertParams).contains("developer");
            assertThat(insertParams).contains("dev@company.com");

            // 2. 测试 @Param 多参数登录查询脱敏
            capturedAlerts.clear();
            mapper.login("developer", "login_pwd_999");

            SlowSqlEvent loginEvent = capturedAlerts.get(0);
            List<Object> loginParams = loginEvent.getParameters();
            assertThat(loginParams).contains("developer");
            assertThat(loginParams).contains("******");
            assertThat(loginParams).doesNotContain("login_pwd_999");
        }
    }

    @Test
    @DisplayName("无参数 SQL 审计：参数列表为空且安全不报异常")
    void shouldHandleParameterlessSqlSafely() {
        List<SlowSqlEvent> capturedAlerts = new ArrayList<>();
        SlowSqlAlertProvider alertProvider = capturedAlerts::add;

        SlowSqlInterceptor interceptor = new SlowSqlInterceptor(
                Duration.ofNanos(0),
                true,
                true,
                alertProvider,
                null
        );

        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            mapper.countAll();

            assertThat(capturedAlerts).hasSize(1);
            SlowSqlEvent event = capturedAlerts.get(0);
            assertThat(event.getStatementId()).contains("TestUserMapper.countAll");
            assertThat(event.getParameters()).isEmpty();
        }
    }

    @Test
    @DisplayName("多告警通知提供者支持：所有注册的 SPI 均被遍历调用")
    void shouldInvokeAllAlertProvidersWhenMultipleConfigured() {
        AtomicInteger count1 = new AtomicInteger();
        AtomicInteger count2 = new AtomicInteger();

        SlowSqlAlertProvider provider1 = event -> count1.incrementAndGet();
        SlowSqlAlertProvider provider2 = event -> count2.incrementAndGet();

        SlowSqlInterceptor interceptor = new SlowSqlInterceptor(
                Duration.ofNanos(0),
                true,
                true,
                List.of(provider1, provider2),
                null
        );

        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            mapper.countAll();
        }

        assertThat(count1.get()).isEqualTo(1);
        assertThat(count2.get()).isEqualTo(1);
        assertThat(interceptor.getAlertProviders()).hasSize(2);
        assertThat(interceptor.getAlertProvider()).isSameAs(provider1);
    }

    @Test
    @DisplayName("弹性防御：SPI 或事件发布者抛出异常时不阻断业务主流程")
    void shouldNotBreakBusinessFlowWhenAlertProviderFails() {
        SlowSqlAlertProvider failingProvider = event -> {
            throw new RuntimeException("Webhook network timeout");
        };
        ApplicationEventPublisher failingPublisher = event -> {
            throw new IllegalStateException("Event bus closed");
        };

        SlowSqlInterceptor interceptor = new SlowSqlInterceptor(
                Duration.ofNanos(0),
                true,
                true,
                failingProvider,
                failingPublisher
        );

        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);
        assertThatCode(() -> {
            try (SqlSession sqlSession = sessionFactory.openSession()) {
                TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
                mapper.countAll();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("formatSql 缩进美化功能测试")
    void testFormatSql() {
        String rawSql = "SELECT id, name FROM t_user WHERE id = ? AND deleted = 0 ORDER BY create_time DESC";
        String formatted = SlowSqlInterceptor.formatSql(rawSql);

        assertThat(formatted).startsWith("SELECT id, name");
        assertThat(formatted).contains("\n  FROM t_user");
        assertThat(formatted).contains("\n  WHERE id = ?");
        assertThat(formatted).contains("\n    AND deleted = 0");
        assertThat(formatted).contains("\n  ORDER BY create_time DESC");

        // 空串健壮性
        assertThat(SlowSqlInterceptor.formatSql(null)).isEmpty();
        assertThat(SlowSqlInterceptor.formatSql("   ")).isEmpty();
    }

    @Test
    @DisplayName("关闭脱敏或美化开关时：原样输出未脱敏参数与紧凑 SQL")
    void shouldRespectFormatAndMaskFlags() {
        List<SlowSqlEvent> capturedAlerts = new ArrayList<>();
        SlowSqlAlertProvider alertProvider = capturedAlerts::add;

        SlowSqlInterceptor interceptor = new SlowSqlInterceptor(
                Duration.ofNanos(0),
                false, // 不美化
                false, // 不脱敏
                alertProvider,
                null
        );

        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            mapper.login("admin", "plain_password_123");

            assertThat(capturedAlerts).hasSize(1);
            SlowSqlEvent event = capturedAlerts.get(0);
            // SQL 保持单行紧凑
            assertThat(event.getSql()).doesNotContain("\n");
            // 参数保持明文未被掩码
            assertThat(event.getParameters()).contains("plain_password_123");
        }
    }

    @Test
    @DisplayName("异步派发与四维上下文透传：开启 asyncEnabled 后不阻塞主线程，完整透传 MDC/User/TenantId/IgnoreTenant 并于完成后清理现场")
    void shouldDispatchSlowSqlAlertsAsynchronouslyWithFourDimContextPropagationAndCleanup() throws Exception {
        ThreadPoolTaskExecutor asyncPool = new ThreadPoolTaskExecutor();
        asyncPool.setCorePoolSize(1);
        asyncPool.setMaxPoolSize(1);
        asyncPool.setThreadNamePrefix("slow-sql-async-");
        asyncPool.initialize();

        try {
            CountDownLatch alertEnteredLatch = new CountDownLatch(1);
            CountDownLatch mainReturnedLatch = new CountDownLatch(1);
            CountDownLatch completedLatch = new CountDownLatch(2);

            AtomicReference<String> providerThreadName = new AtomicReference<>();
            AtomicReference<String> listenerThreadName = new AtomicReference<>();
            AtomicReference<String> capturedTraceId = new AtomicReference<>();
            AtomicReference<String> capturedUser = new AtomicReference<>();
            AtomicReference<String> capturedTenant = new AtomicReference<>();
            AtomicBoolean capturedIgnoreTenant = new AtomicBoolean(false);
            AtomicBoolean unblockedMainFirst = new AtomicBoolean(false);

            SlowSqlAlertProvider alertProvider = event -> {
                try {
                    providerThreadName.set(Thread.currentThread().getName());
                    capturedTraceId.set(MDC.get("traceId"));
                    capturedUser.set(UserContextHolder.get());
                    capturedTenant.set(TenantContextHolder.getTenantId());
                    capturedIgnoreTenant.set(TenantContextHolder.isIgnoreTenant());
                    alertEnteredLatch.countDown();
                    // 等待主线程确认先返回，验证非阻塞特性
                    unblockedMainFirst.set(mainReturnedLatch.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completedLatch.countDown();
                }
            };

            ApplicationEventPublisher publisher = evt -> {
                if (evt instanceof SlowSqlEvent) {
                    listenerThreadName.set(Thread.currentThread().getName());
                    completedLatch.countDown();
                }
            };

            SlowSqlInterceptor interceptor = new SlowSqlInterceptor(
                    Duration.ofNanos(0),
                    true,
                    true,
                    true,
                    List.of(alertProvider),
                    publisher,
                    asyncPool
            );

            MDC.put("traceId", "trace-slow-929");
            UserContextHolder.set("user-slow-ateng");
            TenantContextHolder.setTenantId("tenant-slow-009");
            TenantContextHolder.setIgnoreTenant(true);

            SqlSessionFactory sessionFactory = createSessionFactory(interceptor);
            try (SqlSession sqlSession = sessionFactory.openSession()) {
                TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
                mapper.countAll();
                // 主线程执行到此即释放锁：若回调同步阻塞在此则必定超时死锁，异步则可立即唤醒主线信号
                mainReturnedLatch.countDown();
            }

            assertThat(alertEnteredLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(unblockedMainFirst.get()).isTrue();

            assertThat(providerThreadName.get()).startsWith("slow-sql-async-");
            assertThat(listenerThreadName.get()).startsWith("slow-sql-async-");
            assertThat(capturedTraceId.get()).isEqualTo("trace-slow-929");
            assertThat(capturedUser.get()).isEqualTo("user-slow-ateng");
            assertThat(capturedTenant.get()).isEqualTo("tenant-slow-009");
            assertThat(capturedIgnoreTenant.get()).isTrue();

            // 异步任务完成后向同一单线程提交探针，验证 AsyncContext 确定性清理工作线程残留上下文
            CountDownLatch probeLatch = new CountDownLatch(1);
            AtomicReference<String> residualTraceId = new AtomicReference<>();
            AtomicReference<String> residualUser = new AtomicReference<>();
            AtomicReference<String> residualTenant = new AtomicReference<>();
            AtomicBoolean residualIgnoreTenant = new AtomicBoolean(true);

            asyncPool.execute(() -> {
                try {
                    residualTraceId.set(MDC.get("traceId"));
                    residualUser.set(UserContextHolder.get());
                    residualTenant.set(TenantContextHolder.getTenantId());
                    residualIgnoreTenant.set(TenantContextHolder.isIgnoreTenant());
                } finally {
                    probeLatch.countDown();
                }
            });

            assertThat(probeLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(residualTraceId.get()).isNull();
            assertThat(residualUser.get()).isNull();
            assertThat(residualTenant.get()).isNull();
            assertThat(residualIgnoreTenant.get()).isFalse();
        } finally {
            asyncPool.destroy();
        }
    }

    @Test
    @DisplayName("异步派发异常隔离：SlowSqlAlertProvider 或事件发布在异步线程抛出异常时安全捕获且不泄露上下文")
    void shouldSafelyCatchExceptionsInAsyncWorkerThread() throws Exception {
        ThreadPoolTaskExecutor asyncPool = new ThreadPoolTaskExecutor();
        asyncPool.setCorePoolSize(1);
        asyncPool.setMaxPoolSize(1);
        asyncPool.setThreadNamePrefix("slow-sql-err-worker-");
        asyncPool.initialize();

        try {
            AtomicReference<Throwable> uncaughtError = new AtomicReference<>();
            CountDownLatch finishedLatch = new CountDownLatch(2);
            Executor customPool = runnable -> asyncPool.execute(() -> {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    uncaughtError.set(t);
                } finally {
                    finishedLatch.countDown();
                }
            });

            SlowSqlAlertProvider throwingProvider = event -> {
                throw new RuntimeException("Async webhook timeout");
            };
            ApplicationEventPublisher throwingPublisher = event -> {
                finishedLatch.countDown();
                throw new IllegalStateException("Async publisher failed");
            };

            SlowSqlInterceptor interceptor = new SlowSqlInterceptor(
                    Duration.ofNanos(0),
                    true,
                    true,
                    true,
                    List.of(throwingProvider),
                    throwingPublisher,
                    customPool
            );

            SqlSessionFactory sessionFactory = createSessionFactory(interceptor);
            assertThatCode(() -> {
                try (SqlSession sqlSession = sessionFactory.openSession()) {
                    TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
                    mapper.countAll();
                }
            }).doesNotThrowAnyException();

            assertThat(finishedLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(uncaughtError.get()).isNull();
        } finally {
            asyncPool.destroy();
        }
    }

    @Test
    @DisplayName("未注入受管线程池时开启 asyncEnabled=true 安全降级为同步派发")
    void shouldFallbackToSyncWhenAsyncEnabledWithoutExecutor() {
        AtomicReference<String> execThreadName = new AtomicReference<>();
        SlowSqlAlertProvider provider = event -> execThreadName.set(Thread.currentThread().getName());

        SlowSqlInterceptor interceptor = new SlowSqlInterceptor(
                Duration.ofNanos(0),
                true,
                true,
                true,
                List.of(provider),
                null,
                null
        );

        SqlSessionFactory sessionFactory = createSessionFactory(interceptor);
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
            mapper.countAll();
        }

        assertThat(execThreadName.get()).isEqualTo(Thread.currentThread().getName());
    }
}
